package com.demo.securetransfer.security.trigger;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

public class TransactionsImmutableTrigger implements Trigger {
    @Override
    public void init(Connection connection, String schemaName, String triggerName, String tableName, boolean before, int type) {
        // no-op
    }

    @Override
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("transactions table is append-only: UPDATE/DELETE are not allowed");
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public void remove() {
        // no-op
    }
}
