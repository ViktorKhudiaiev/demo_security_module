package com.demo.securetransfer.domain;

import java.time.Instant;
import java.util.UUID;

public record Transaction(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        TransactionStatus status,
        String token,
        Instant createdAt,
        Instant updatedAt,
        String failureReason
) {
}
