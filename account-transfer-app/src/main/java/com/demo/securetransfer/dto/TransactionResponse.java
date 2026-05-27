package com.demo.securetransfer.dto;

import com.demo.securetransfer.domain.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
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
