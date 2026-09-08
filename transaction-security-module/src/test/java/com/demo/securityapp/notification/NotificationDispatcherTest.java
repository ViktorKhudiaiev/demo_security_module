package com.demo.securityapp.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationDispatcherTest {
    private JdbcTemplate db;
    private NotificationOutbox outbox;
    private MutableClock clock;

    @BeforeEach
    void createOutbox() {
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        new ResourceDatabasePopulator(new ClassPathResource("notification-schema.sql"))
                .execute(db.getDataSource());
        outbox = new NotificationOutbox(db);
        clock = new MutableClock();
    }

    private UUID enqueue() {
        UUID operation = UUID.randomUUID();
        db.update("""
                INSERT INTO notification_outbox(operation_id,event_id,event_seq,reason,created_at_micros)
                VALUES (?,?,?,?,?)
                """, operation, UUID.randomUUID(), 1, "INTEGRITY_INCIDENT", now());
        return operation;
    }

    private long now() { return clock.millis() * 1000L; }

    private static NotificationSettings settings(String mode, int rate) {
        return new NotificationSettings(mode, "incidents@example.test", "reviewer@example.test",
                "127.0.0.1", 1025, "", "", "starttls", rate);
    }

    private NotificationDispatcher dispatcher(NotificationSender sender) {
        return new NotificationDispatcher(outbox, sender, settings("mailpit", 60), clock);
    }

    @Test
    void sendsOnlyAfterClaimTransactionCommitsAndDoesNotResendDeliveredRow() {
        UUID operation = enqueue();
        List<IncidentNotification> delivered = new ArrayList<>();
        NotificationDispatcher dispatcher = dispatcher(notification -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(db.queryForObject("SELECT attempts FROM notification_outbox WHERE operation_id=?",
                    Integer.class, operation)).isEqualTo(1);
            delivered.add(notification);
        });
        assertThat(dispatcher.dispatchOne()).isTrue();
        clock.advance(1000);
        assertThat(dispatcher.dispatchOne()).isFalse();
        assertThat(delivered).hasSize(1);
        assertThat(db.queryForObject("SELECT delivered_at_micros FROM notification_outbox", Long.class))
                .isEqualTo(Instant.parse("2026-09-08T00:00:00Z").toEpochMilli() * 1000L);
    }

    @Test
    void disabledModeDoesNotTouchDatabaseOrSendAndRetainsPendingRecord() {
        enqueue();
        NotificationOutbox unavailable = mock(NotificationOutbox.class);
        NotificationSender sender = mock(NotificationSender.class);
        assertThat(new NotificationDispatcher(unavailable, sender, settings("disabled", 30), clock)
                .dispatchOne()).isFalse();
        verifyNoInteractions(unavailable, sender);
        assertThat(db.queryForObject("SELECT attempts FROM notification_outbox", Integer.class)).isZero();
    }

    @Test
    void providerFailureIsRedactedAndRetrySurvivesWorkerRestart() {
        enqueue();
        assertThat(dispatcher(notification -> { throw new IllegalStateException("password=secret provider response"); })
                .dispatchOne()).isFalse();
        assertThat(db.queryForObject("SELECT last_error FROM notification_outbox", String.class))
                .isEqualTo("DELIVERY_FAILED");
        assertThat(outbox.claim(now())).isEmpty();
        clock.advance(1000);
        List<IncidentNotification> delivered = new ArrayList<>();
        NotificationDispatcher restarted = new NotificationDispatcher(new NotificationOutbox(db), delivered::add,
                settings("mailpit", 60), clock);
        assertThat(restarted.dispatchOne()).isTrue();
        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().attempts()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT last_error FROM notification_outbox", String.class)).isNull();
    }

    @Test
    void exponentialRetryDelayIsCappedAtFiveMinutes() {
        enqueue();
        for (int attempt = 1; attempt <= 12; attempt++) {
            IncidentNotification notification = outbox.claim(now()).orElseThrow();
            outbox.failed(notification, now());
            long delay = Math.min(300, 1L << Math.min(9, attempt - 1)) * 1_000_000L;
            assertThat(db.queryForObject("SELECT next_attempt_micros FROM notification_outbox", Long.class))
                    .isEqualTo(now() + delay);
            clock.advance(delay / 1000L);
        }
    }

    @Test
    void crashedClaimIsRecoveredOnlyAfterLeaseAndOldWorkerCannotAcknowledgeNewClaim() {
        enqueue();
        IncidentNotification abandoned = outbox.claim(now()).orElseThrow();
        assertThat(new NotificationOutbox(db).claim(now())).isEmpty();
        clock.advance(NotificationOutbox.LEASE_MICROS / 1000L);
        IncidentNotification recovered = new NotificationOutbox(db).claim(now()).orElseThrow();
        assertThat(recovered.claimToken()).isNotEqualTo(abandoned.claimToken());
        assertThat(recovered.eventId()).isEqualTo(abandoned.eventId());
        assertThat(outbox.delivered(abandoned, now())).isFalse();
        outbox.failed(abandoned, now());
        assertThat(db.queryForObject("SELECT claim_token FROM notification_outbox", UUID.class))
                .isEqualTo(recovered.claimToken());
        assertThat(outbox.delivered(recovered, now())).isTrue();
    }

    @Test
    void smtpAcceptanceBeforeCrashCanBeRetriedWithSameEventId() {
        enqueue();
        IncidentNotification firstAccepted = outbox.claim(now()).orElseThrow();
        // Simulate SMTP acceptance followed by process loss before the delivered update.
        clock.advance(NotificationOutbox.LEASE_MICROS / 1000L);
        List<IncidentNotification> acceptedAgain = new ArrayList<>();
        assertThat(dispatcher(acceptedAgain::add).dispatchOne()).isTrue();
        assertThat(acceptedAgain.getFirst().eventId()).isEqualTo(firstAccepted.eventId());
    }

    @Test
    void multipleWorkersCannotClaimSameUnexpiredNotification() throws Exception {
        enqueue();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(6)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 6; worker++) {
                tasks.add(workers.submit(() -> {
                    start.await();
                    if (new NotificationOutbox(db).claim(now()).isPresent()) claims.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get();
        }
        assertThat(claims.get()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT attempts FROM notification_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void perProcessRateLimitsEvenWhenMoreIncidentsArePending() {
        enqueue();
        enqueue();
        List<IncidentNotification> delivered = new ArrayList<>();
        NotificationDispatcher dispatcher = new NotificationDispatcher(outbox, delivered::add,
                settings("mailpit", 30), clock);
        assertThat(dispatcher.dispatchOne()).isTrue();
        clock.advance(1999);
        assertThat(dispatcher.dispatchOne()).isFalse();
        clock.advance(1);
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(delivered).hasSize(2);
    }

    @Test
    void accidentalInvocationInsideFinancialTransactionIsRejectedBeforeSmtp() {
        enqueue();
        NotificationSender sender = mock(NotificationSender.class);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        assertThatThrownBy(() -> transaction.execute(status -> dispatcher(sender).dispatchOne()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inside a database transaction");
        verifyNoInteractions(sender);
        assertThat(db.queryForObject("SELECT attempts FROM notification_outbox", Integer.class)).isZero();
    }

    @Test
    void administrativeStatusCountsPendingAndDeliveredWithoutSensitiveConfiguration() {
        NotificationDispatcher dispatcher = dispatcher(notification -> { });
        assertThat(dispatcher.status()).isEqualTo(new NotificationStatus("mailpit", true, 0, 0, null));
        enqueue();
        assertThat(dispatcher.status().oldestPendingAtMicros()).isEqualTo(now());
        assertThat(dispatcher.status().pending()).isEqualTo(1);
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(dispatcher.status()).isEqualTo(new NotificationStatus("mailpit", true, 0, 1, null));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-08T00:00:00Z");
        void advance(long milliseconds) { instant = instant.plusMillis(milliseconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
