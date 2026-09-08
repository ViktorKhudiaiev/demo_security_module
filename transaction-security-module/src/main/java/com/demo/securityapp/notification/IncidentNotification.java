package com.demo.securityapp.notification;

import java.util.UUID;

/** Minimal incident metadata only; never contains an operation payload or key material. */
public record IncidentNotification(UUID operationId, UUID eventId, long eventSeq,
        String reason, long createdAtMicros, int attempts, UUID claimToken) {
}
