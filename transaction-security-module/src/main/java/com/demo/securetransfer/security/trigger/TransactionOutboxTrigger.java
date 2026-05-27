package com.demo.securetransfer.security.trigger;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public class TransactionOutboxTrigger implements Trigger {
    private static final int TRANSACTION_ID_INDEX = 0;
    private static final int STATUS_INDEX = 5;

    @Override
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        String newStatus = String.valueOf(newRow[STATUS_INDEX]);
        String oldStatus = oldRow == null ? null : String.valueOf(oldRow[STATUS_INDEX]);

        if (!"PENDING".equals(newStatus) || "PENDING".equals(oldStatus)) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transaction_outbox (id, transaction_id, event_type, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, newRow[TRANSACTION_ID_INDEX]);
            statement.setString(3, "TRANSACTION_PENDING");
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    @Override
    public void init(Connection connection, String schemaName, String triggerName, String tableName, boolean before, int type) {
    }

    @Override
    public void close() {
    }

    @Override
    public void remove() {
    }
}
