package com.demo.securetransfer.security.domain;

import java.time.Instant;
import java.util.UUID;

public record SecurityTransactionView(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        String status,
        String token,
        Instant createdAt
) {
}
