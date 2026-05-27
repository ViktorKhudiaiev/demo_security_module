package com.demo.securetransfer.repository;

import com.demo.securetransfer.domain.Account;
import com.demo.securetransfer.domain.AccountStatus;
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
public class AccountRepository {
    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(UUID id, String name, AccountStatus status, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO accounts (id, name, status, created_at)
                VALUES (?, ?, ?, ?)
                """, id, name, status.name(), Timestamp.from(createdAt));
    }

    public void block(UUID accountId) {
        jdbcTemplate.update("""
                UPDATE accounts
                SET status = ?
                WHERE id = ?
                """, AccountStatus.BLOCKED.name(), accountId);
    }

    public Optional<Account> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT a.id, a.name, a.status, a.created_at, COALESCE(SUM(l.amount_cents), 0) AS balance_cents
                FROM accounts a
                LEFT JOIN ledger_entries l ON l.account_id = a.id
                WHERE a.id = ?
                GROUP BY a.id, a.name, a.status, a.created_at
                """, this::mapAccount, id).stream().findFirst();
    }

    public List<Account> findAll() {
        return jdbcTemplate.query("""
                SELECT a.id, a.name, a.status, a.created_at, COALESCE(SUM(l.amount_cents), 0) AS balance_cents
                FROM accounts a
                LEFT JOIN ledger_entries l ON l.account_id = a.id
                GROUP BY a.id, a.name, a.status, a.created_at
                ORDER BY a.created_at
                """, this::mapAccount);
    }

    public AccountStatus requireStatus(UUID id) {
        return jdbcTemplate.query("""
                SELECT status
                FROM accounts
                WHERE id = ?
                """, (rs, rowNum) -> AccountStatus.valueOf(rs.getString("status")), id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));
    }

    public long balanceOf(UUID accountId) {
        Long balance = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount_cents), 0)
                FROM ledger_entries
                WHERE account_id = ?
                """, Long.class, accountId);
        return balance == null ? 0 : balance;
    }

    private Account mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getLong("balance_cents"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
