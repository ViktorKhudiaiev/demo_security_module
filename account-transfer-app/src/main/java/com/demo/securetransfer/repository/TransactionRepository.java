package com.demo.securetransfer.repository;

import com.demo.securetransfer.domain.Transaction;
import com.demo.securetransfer.domain.TransactionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransactionRepository {
    private final JdbcTemplate jdbcTemplate;

    public TransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(Transaction transaction) {
        jdbcTemplate.update("""
                INSERT INTO transactions (
                    id, from_account_id, to_account_id, amount_cents, created_at, token
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                transaction.id(),
                transaction.fromAccountId(),
                transaction.toAccountId(),
                transaction.amountCents(),
                Timestamp.from(transaction.createdAt()),
                transaction.token());
        appendStatus(transaction.id(), transaction.status(), transaction.failureReason(), transaction.createdAt());
    }

    public Optional<Transaction> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT
                    t.id, t.from_account_id, t.to_account_id, t.amount_cents, t.created_at, t.token,
                    s.status, s.created_at AS status_created_at, s.failure_reason
                FROM transactions t
                JOIN transaction_status_events s ON s.transaction_id = t.id
                WHERE t.id = ?
                AND s.created_at = (
                    SELECT MAX(s2.created_at)
                    FROM transaction_status_events s2
                    WHERE s2.transaction_id = t.id
                )
                """, this::mapTransaction, id).stream().findFirst();
    }

    public List<Transaction> findAll() {
        return jdbcTemplate.query("""
                SELECT
                    t.id, t.from_account_id, t.to_account_id, t.amount_cents, t.created_at, t.token,
                    s.status, s.created_at AS status_created_at, s.failure_reason
                FROM transactions t
                JOIN transaction_status_events s ON s.transaction_id = t.id
                WHERE s.created_at = (
                    SELECT MAX(s2.created_at)
                    FROM transaction_status_events s2
                    WHERE s2.transaction_id = t.id
                )
                ORDER BY t.created_at DESC
                """, this::mapTransaction);
    }

    public void appendStatus(UUID id, TransactionStatus status, String failureReason) {
        appendStatus(id, status, failureReason, Instant.now());
    }

    private void appendStatus(UUID id, TransactionStatus status, String failureReason, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO transaction_status_events (id, transaction_id, status, failure_reason, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                id,
                status.name(),
                failureReason,
                Timestamp.from(createdAt));
    }

    private Transaction mapTransaction(ResultSet rs, int rowNum) throws SQLException {
        return new Transaction(
                rs.getObject("id", UUID.class),
                rs.getObject("from_account_id", UUID.class),
                rs.getObject("to_account_id", UUID.class),
                rs.getLong("amount_cents"),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getString("token"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("status_created_at").toInstant(),
                rs.getString("failure_reason")
        );
    }
}
