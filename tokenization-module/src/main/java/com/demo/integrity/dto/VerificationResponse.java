package com.demo.integrity.dto;

/** Result of the key service's MAC and independent issuance-receipt verification. */
public record VerificationResponse(boolean valid) {}
