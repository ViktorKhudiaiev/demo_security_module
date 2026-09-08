package com.demo.securityapp.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;

/** Delivery state resides in the protected Audit DB, not in the attacker-controlled Main DB. */
public final class NotificationOutbox {
    static final long LEASE_MICROS = 300_000_000L;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    public NotificationOutbox(JdbcTemplate db) {
        this.db = db;
        transactions = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(db.getDataSource())));
    }

    public Optional<IncidentNotification> claim(long nowMicros) {
        return transactions.execute(status -> {
            List<UUID> candidates = db.query("""
                    SELECT operation_id FROM notification_outbox
                    WHERE delivered_at_micros IS NULL AND next_attempt_micros<=? AND lease_until_micros<=?
                    ORDER BY created_at_micros, event_seq LIMIT 1
                    """, (row, index) -> row.getObject(1, UUID.class), nowMicros, nowMicros);
            if (candidates.isEmpty()) return Optional.empty();
            UUID operationId = candidates.getFirst();
            UUID claim = UUID.randomUUID();
            int updated = db.update("""
                    UPDATE notification_outbox SET claim_token=?, lease_until_micros=?,
                    attempts=CASE WHEN attempts<2147483647 THEN attempts+1 ELSE attempts END
                    WHERE operation_id=? AND delivered_at_micros IS NULL
                    AND next_attempt_micros<=? AND lease_until_micros<=?
                    """, claim, nowMicros + LEASE_MICROS, operationId, nowMicros, nowMicros);
            if (updated == 0) return Optional.empty();
            return Optional.ofNullable(db.queryForObject("""
                    SELECT operation_id,event_id,event_seq,reason,created_at_micros,attempts,claim_token
                    FROM notification_outbox WHERE operation_id=? AND claim_token=?
                    """, (row, index) -> new IncidentNotification(row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class), row.getLong(3), row.getString(4), row.getLong(5),
                    row.getInt(6), row.getObject(7, UUID.class)), operationId, claim));
        });
    }

    public boolean delivered(IncidentNotification notification, long nowMicros) {
        return db.update("""
                UPDATE notification_outbox SET delivered_at_micros=?, claim_token=NULL,
                lease_until_micros=0,last_error=NULL
                WHERE operation_id=? AND claim_token=? AND delivered_at_micros IS NULL
                """, nowMicros, notification.operationId(), notification.claimToken()) == 1;
    }

    public void failed(IncidentNotification notification, long nowMicros) {
        int exponent = Math.max(0, Math.min(9, notification.attempts() - 1));
        long delayMicros = Math.min(300L, 1L << exponent) * 1_000_000L;
        db.update("""
                UPDATE notification_outbox SET next_attempt_micros=?,claim_token=NULL,
                lease_until_micros=0,last_error='DELIVERY_FAILED'
                WHERE operation_id=? AND claim_token=? AND delivered_at_micros IS NULL
                """, nowMicros + delayMicros, notification.operationId(), notification.claimToken());
    }

    public NotificationStatus status(NotificationSettings.Mode mode) {
        return db.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN delivered_at_micros IS NULL THEN 1 ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN delivered_at_micros IS NOT NULL THEN 1 ELSE 0 END),0),
                MIN(CASE WHEN delivered_at_micros IS NULL THEN created_at_micros ELSE NULL END)
                FROM notification_outbox
                """, (row, index) -> new NotificationStatus(mode.name().toLowerCase(Locale.ROOT),
                mode != NotificationSettings.Mode.DISABLED, row.getLong(1), row.getLong(2),
                row.getObject(3, Long.class)));
    }
}
