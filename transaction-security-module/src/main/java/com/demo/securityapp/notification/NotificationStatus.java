package com.demo.securityapp.notification;

/** Administrative status deliberately excludes SMTP configuration and recipient addresses. */
public record NotificationStatus(String mode, boolean enabled, long pending, long delivered,
        Long oldestPendingAtMicros) {
}
