package com.demo.securetransfer.repository;

import com.demo.securetransfer.domain.LedgerEntry;
import com.demo.securetransfer.domain.LedgerEntryType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class LedgerRepository {
    private final JdbcTemplate jdbcTemplate;

    public LedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void addEntry(UUID accountId, UUID transactionId, long amountCents, LedgerEntryType entryType) {
        add(new LedgerEntry(UUID.randomUUID(), accountId, transactionId, amountCents, entryType, Instant.now()));
    }

    public void add(LedgerEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, account_id, transaction_id, amount_cents, entry_type, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                entry.id(),
                entry.accountId(),
                entry.transactionId(),
                entry.amountCents(),
                entry.entryType().name(),
                Timestamp.from(entry.createdAt()));
    }
}
