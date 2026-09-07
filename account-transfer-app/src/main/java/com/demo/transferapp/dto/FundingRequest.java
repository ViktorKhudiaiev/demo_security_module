package com.demo.transferapp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Signed treasury funding request; amounts are integer minor currency units. */
public record FundingRequest(
        @NotNull UUID toAccountId,
        @Min(1) @Max(1_000_000_000_000L) long amountCents,
        String currency) {
}
