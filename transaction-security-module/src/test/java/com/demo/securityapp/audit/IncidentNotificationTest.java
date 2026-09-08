package com.demo.securityapp.audit;

import com.demo.integrity.crypto.CanonicalEncoder;
import com.demo.integrity.dto.IssuanceInventory;
import com.demo.integrity.dto.IssuanceInventoryItem;
import com.demo.integrity.dto.SignedCheckpoint;
import com.demo.integrity.dto.SignedOperation;
import com.demo.integrity.model.Checkpoint;
import com.demo.integrity.model.Operation;
import com.demo.securityapp.client.KeyGateway;
import com.demo.securityapp.delivery.OperationDelivery;
import com.demo.securityapp.service.Processor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real database tests for the audit-to-notification transaction boundary. */
class IncidentNotificationTest {
    private JdbcTemplate db;
    private AuditLog audit;
    private KeyGateway keys;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        db = database();
        new ResourceDatabasePopulator(
                new ClassPathResource("audit-schema.sql"),
                new ClassPathResource("notification-schema.sql"))
                .execute(db.getDataSource());
        keys = mock(KeyGateway.class);
        audit = new AuditLog(db, keys, json);
    }

    @Test
    void ordinaryAuditEventsAndBusinessRejectionsDoNotNotify() {
        UUID operation = UUID.randomUUID();
        for (String type : List.of("VERIFIED", "COMPLETED", "REJECTED", "QUARANTINED")) {
            audit.append(UUID.randomUUID(), operation, type, "Recorded outcome");
        }
        audit.append(UUID.randomUUID(), null, "ACCOUNT_CREATED", "Account registered");

        assertThat(audit.events(operation)).hasSize(4);
        assertThat(notificationCount()).isZero();
        assertThat(nextSequence()).isEqualTo(5);
    }

    @Test
    void fabricatedOperationIncidentCapturesOnlyMinimalDeliveryMetadata() {
        UUID operation = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        long before = Processor.now();

        audit.append(event, operation, "INTEGRITY_INCIDENT", "No independent issuance receipt");

        Map<String, Object> notice = db.queryForMap("SELECT * FROM notification_outbox");
        assertThat(notice.get("operation_id")).isEqualTo(operation);
        assertThat(notice.get("event_id")).isEqualTo(event);
        assertThat(((Number) notice.get("event_seq")).longValue()).isZero();
        assertThat(notice.get("reason")).isEqualTo("No independent issuance receipt");
        assertThat(((Number) notice.get("created_at_micros")).longValue())
                .isBetween(before, Processor.now());
        assertThat(((Number) notice.get("attempts")).intValue()).isZero();
        assertThat(((Number) notice.get("next_attempt_micros")).longValue()).isZero();
        assertThat(((Number) notice.get("lease_until_micros")).longValue()).isZero();
        assertThat(notice.get("claim_token")).isNull();
        assertThat(notice.get("delivered_at_micros")).isNull();
        assertThat(notice.get("last_error")).isNull();
        assertThat(notice.keySet().stream().map(String::toLowerCase)).containsExactlyInAnyOrder(
                "operation_id", "event_id", "event_seq", "reason", "created_at_micros",
                "attempts", "next_attempt_micros", "lease_until_micros", "claim_token",
                "delivered_at_micros", "last_error");
        assertThat(audit.events(operation).getFirst().get("created_at_micros"))
                .isEqualTo(notice.get("created_at_micros"));
    }

    @Test
    void repeatingAnAuditEventDoesNotDuplicateEitherHistoryOrNotification() {
        UUID operation = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        audit.append(event, operation, "INTEGRITY_INCIDENT", "Canonical content hash mismatch");
        Map<String, Object> original = db.queryForMap("SELECT * FROM notification_outbox");

        audit.append(event, operation, "INTEGRITY_INCIDENT", "Canonical content hash mismatch");

        assertThat(audit.events(operation)).hasSize(1);
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(nextSequence()).isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM notification_outbox")).isEqualTo(original);
    }

    @Test
    void distinctIncidentsKeepTheirHistoryButNotifyOnlyOncePerOperation() {
        UUID operation = UUID.randomUUID();
        UUID firstEvent = UUID.randomUUID();
        audit.append(firstEvent, operation, "INTEGRITY_INCIDENT", "Primary record differs from issuance receipt");
        Map<String, Object> firstNotice = db.queryForMap("SELECT * FROM notification_outbox");

        audit.append(UUID.randomUUID(), operation, "INTEGRITY_INCIDENT", "Issued primary record is missing");

        assertThat(audit.events(operation)).hasSize(2);
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(nextSequence()).isEqualTo(2);
        assertThat(db.queryForMap("SELECT * FROM notification_outbox")).isEqualTo(firstNotice);
    }

    @Test
    void differentOperationsRemainIndependentlyNotifiable() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        audit.append(UUID.randomUUID(), first, "INTEGRITY_INCIDENT", "No independent issuance receipt");
        audit.append(UUID.randomUUID(), second, "INTEGRITY_INCIDENT", "No independent issuance receipt");

        assertThat(notificationCount()).isEqualTo(2);
        assertThat(db.query("SELECT operation_id FROM notification_outbox", (rs, row) ->
                rs.getObject(1, UUID.class))).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void simultaneousIncidentsForOneOperationCreateOnlyOneNotification() throws Exception {
        UUID operation = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new java.util.ArrayList<>();
            for (int index = 0; index < 8; index++) {
                tasks.add(executor.submit(() -> audit.append(UUID.randomUUID(), operation,
                        "INTEGRITY_INCIDENT", "Integrity verification failed")));
            }
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(audit.events(operation)).hasSize(8);
        assertThat(nextSequence()).isEqualTo(8);
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    void laterIncidentDoesNotResetAnAlreadyDeliveredNotification() {
        UUID operation = UUID.randomUUID();
        audit.append(UUID.randomUUID(), operation, "INTEGRITY_INCIDENT", "Integrity verification failed");
        db.update("UPDATE notification_outbox SET delivered_at_micros=?,attempts=1 WHERE operation_id=?",
                Processor.now(), operation);
        Map<String, Object> delivered = db.queryForMap("SELECT * FROM notification_outbox");

        audit.append(UUID.randomUUID(), operation, "INTEGRITY_INCIDENT", "Issued primary record is missing");

        assertThat(audit.events(operation)).hasSize(2);
        assertThat(db.queryForMap("SELECT * FROM notification_outbox")).isEqualTo(delivered);
    }

    @Test
    void unrecognizedIncidentDetailStaysOnlyInProtectedAuditEvidence() {
        UUID operation = UUID.randomUUID();
        String detail = "Unexpected payload: token=fake-sensitive-token; account=fake-account-id; "
                + "amount=987654; " + "x".repeat(600);

        audit.append(UUID.randomUUID(), operation, "INTEGRITY_INCIDENT", detail);

        assertThat(audit.events(operation).getFirst().get("detail")).isEqualTo(detail);
        assertThat(db.queryForObject("SELECT reason FROM notification_outbox", String.class))
                .isEqualTo("Integrity discrepancy detected; inspect protected audit evidence.")
                .doesNotContain("fake-sensitive-token", "fake-account-id", "987654");
    }

    @Test
    void notificationInsertFailureRollsBackAuditAndAllowsTheSameEventToRetry() {
        UUID operation = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        db.execute("ALTER TABLE notification_outbox ADD CONSTRAINT reject_notification CHECK (event_seq <> 0)");

        assertThatThrownBy(() -> audit.append(event, operation, "INTEGRITY_INCIDENT", "No independent issuance receipt"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertEmptyAuditAndQueue();
        db.execute("ALTER TABLE notification_outbox DROP CONSTRAINT reject_notification");
        audit.append(event, operation, "INTEGRITY_INCIDENT", "No independent issuance receipt");
        assertThat(audit.events(operation)).hasSize(1);
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(nextSequence()).isEqualTo(1);
    }

    @Test
    void auditInsertFailureCannotLeaveAnOrphanNotification() {
        db.execute("ALTER TABLE audit_events ADD CONSTRAINT reject_incident CHECK (event_type <> 'INTEGRITY_INCIDENT')");

        assertThatThrownBy(() -> audit.append(UUID.randomUUID(), UUID.randomUUID(), "INTEGRITY_INCIDENT", "Integrity verification failed"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertEmptyAuditAndQueue();
    }

    @Test
    void auditHeadFailureRollsBackBothPendingInserts() {
        db.execute("ALTER TABLE audit_head ADD CONSTRAINT reject_sequence_advance CHECK (next_seq = 0)");

        assertThatThrownBy(() -> audit.append(UUID.randomUUID(), UUID.randomUUID(), "INTEGRITY_INCIDENT", "Integrity verification failed"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertEmptyAuditAndQueue();
    }

    @Test
    void postSettlementTamperingNotifiesWithoutUndoingTheProtectedOutcome() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("settlement-schema.sql"))
                .execute(db.getDataSource());
        JdbcTemplate primary = database();
        primary.execute("CREATE TABLE transactions(id UUID PRIMARY KEY,operation_json TEXT,key_id VARCHAR,content_hash VARCHAR,mac VARCHAR,created_at_micros BIGINT)");
        when(keys.latest()).thenReturn(Optional.empty());
        when(keys.sign(any())).thenAnswer(call -> {
            Checkpoint checkpoint = call.getArgument(0);
            return new SignedCheckpoint(checkpoint.logId(), checkpoint.treeSize(), checkpoint.rootHash(),
                    checkpoint.createdAtMicros(), "test-key", "test-signature", "test-public");
        });
        audit.checkpoint();
        Processor processor = new Processor(primary, db, keys, audit, json,
                new MockEnvironment(), mock(OperationDelivery.class));
        try {
            UUID recipient = UUID.randomUUID();
            processor.register(recipient, "Incident test recipient");
            Operation operation = new Operation(UUID.randomUUID(), 1,
                    UUID.fromString("00000000-0000-0000-0000-000000000010"), "FUNDING",
                    Processor.TREASURY, recipient, 123, "USD", Processor.now(), null,
                    UUID.randomUUID().toString());
            SignedOperation signed = new SignedOperation(operation, "test-key",
                    CanonicalEncoder.hashHex(operation, "test-key"), "00".repeat(32));
            when(keys.receipt(operation.id())).thenReturn(Optional.of(signed));
            when(keys.verify(signed)).thenReturn(true);
            when(keys.inventory(0)).thenReturn(new IssuanceInventory(
                    List.of(new IssuanceInventoryItem(1, signed)), 1));
            primary.update("INSERT INTO transactions VALUES(?,?,?,?,?,?)", operation.id(),
                    json.writeValueAsString(operation), signed.keyId(), signed.contentHash(),
                    signed.mac(), operation.createdAtMicros());
            processor.enqueue(operation.id());
            processor.process(operation.id());
            assertThat(processor.operation(operation.id()).get("status")).isEqualTo("COMPLETED");
            assertThat(notificationCount()).isZero();

            primary.update("UPDATE transactions SET mac=? WHERE id=?", "ff".repeat(32), operation.id());
            processor.reconcile();
            processor.reconcile();

            assertThat(notificationCount()).isEqualTo(1);
            assertThat(db.queryForObject("SELECT operation_id FROM notification_outbox", UUID.class))
                    .isEqualTo(operation.id());
            assertThat(processor.operation(operation.id()).get("status")).isEqualTo("COMPLETED");
            assertThat(processor.account(recipient).balanceCents()).isEqualTo(123);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ledger_postings WHERE operation_id=?",
                    Long.class, operation.id())).isEqualTo(2);
            assertThat(db.queryForObject("SELECT SUM(amount_cents) FROM ledger_postings WHERE operation_id=?",
                    Long.class, operation.id())).isZero();
        } finally {
            processor.closeWorkers();
        }
    }

    private void assertEmptyAuditAndQueue() {
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events", Long.class)).isZero();
        assertThat(notificationCount()).isZero();
        assertThat(nextSequence()).isZero();
    }

    private long notificationCount() {
        return db.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class);
    }

    private long nextSequence() {
        return db.queryForObject("SELECT next_seq FROM audit_head WHERE id=1", Long.class);
    }

    private static JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", ""));
    }
}
