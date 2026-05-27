package com.demo.securetransfer.security.repository;

import com.demo.securetransfer.security.domain.OutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OutboxEvent> findUnprocessed(int limit) {
        return jdbcTemplate.query("""
                SELECT id, transaction_id, event_type, created_at
                FROM transaction_outbox
                WHERE processed_at IS NULL
                ORDER BY created_at
                LIMIT ?
                """, this::mapEvent, limit);
    }

    public void markProcessed(UUID eventId) {
        jdbcTemplate.update("""
                UPDATE transaction_outbox
                SET processed_at = ?
                WHERE id = ?
                """, Timestamp.from(Instant.now()), eventId);
    }

    private OutboxEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getString("event_type"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
