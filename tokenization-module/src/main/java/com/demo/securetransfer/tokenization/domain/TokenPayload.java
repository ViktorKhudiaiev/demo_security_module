package com.demo.securetransfer.tokenization.domain;

import java.time.Instant;
import java.util.UUID;

public record TokenPayload(
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        Instant createdAt
) {
    public String canonicalString() {
        return "transactionId=%s|fromAccountId=%s|toAccountId=%s|amountCents=%d|createdAt=%s"
                .formatted(transactionId, fromAccountId, toAccountId, amountCents, createdAt);
    }
}
