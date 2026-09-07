package com.demo.securityapp.dto;

import java.util.UUID;

/** Test-only failure injection, accepted only when test hooks are explicitly enabled. */
public record FaultRequest(UUID operationId, long pauseAfterVerifyMillis, boolean failAfterDebit) {
}
