package com.demo.transferapp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Account registration payload; opening credit is submitted as signed funding. */
public record AccountRequest(
        UUID id,
        @NotBlank @Size(max = 128) String name,
        @Min(0) @Max(1_000_000_000_000L) long initialBalanceCents) {
}
