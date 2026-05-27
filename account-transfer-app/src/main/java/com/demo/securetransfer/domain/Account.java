package com.demo.securetransfer.domain;

import java.time.Instant;
import java.util.UUID;

public record Account(
        UUID id,
        String name,
        AccountStatus status,
        long balanceCents,
        Instant createdAt
) {
}
