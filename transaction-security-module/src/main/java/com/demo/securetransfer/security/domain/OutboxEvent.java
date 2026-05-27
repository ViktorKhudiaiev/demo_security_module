package com.demo.securetransfer.security.domain;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        UUID transactionId,
        String eventType,
        Instant createdAt
) {
}
