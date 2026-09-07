package com.demo.transferapp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request fields shared by a new transfer and a replacement correction. */
public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @Min(1) @Max(1_000_000_000_000L) long amountCents,
        String currency) {
}
