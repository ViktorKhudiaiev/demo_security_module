package com.demo.securetransfer.dto;

import com.demo.securetransfer.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        AccountStatus status,
        long balanceCents,
        Instant createdAt
) {
}
