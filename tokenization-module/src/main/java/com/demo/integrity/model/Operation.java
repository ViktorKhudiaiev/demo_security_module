package com.demo.integrity.model;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Immutable business payload. Application user authorization is an upstream responsibility. */
public record Operation(@JsonProperty(value = "id", required = true) UUID id,
                        @JsonProperty(value = "schemaVersion", required = true) int schemaVersion,
                        @JsonProperty(value = "ledgerId", required = true) UUID ledgerId,
                        @JsonProperty(value = "type", required = true) String type,
                        @JsonProperty(value = "fromAccountId", required = true) UUID fromAccountId,
                        @JsonProperty(value = "toAccountId", required = true) UUID toAccountId,
                        @JsonProperty(value = "amountMinor", required = true) long amountMinor,
                        @JsonProperty(value = "currency", required = true) String currency,
                        @JsonProperty(value = "createdAtMicros", required = true) long createdAtMicros,
                        @JsonProperty(value = "relatedOperationId", required = true) UUID relatedOperationId,
                        @JsonProperty(value = "idempotencyKey", required = true) String idempotencyKey) {}
