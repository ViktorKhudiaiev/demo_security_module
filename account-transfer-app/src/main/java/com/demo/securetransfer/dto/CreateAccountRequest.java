package com.demo.securetransfer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateAccountRequest(
        @NotBlank String name,
        @Min(0) long initialBalanceCents
) {
}
