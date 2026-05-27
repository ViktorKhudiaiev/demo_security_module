package com.demo.securetransfer.domain;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
        UUID id,
        UUID accountId,
        UUID transactionId,
        long amountCents,
        LedgerEntryType entryType,
        Instant createdAt
) {
}
