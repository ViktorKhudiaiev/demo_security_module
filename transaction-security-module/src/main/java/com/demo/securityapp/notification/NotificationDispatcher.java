package com.demo.securityapp.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Optional;

/** SMTP occurs only after the outbox claim transaction commits; retries are at-least-once. */
public final class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private final NotificationOutbox outbox;
    private final NotificationSender sender;
    private final NotificationSettings settings;
    private final Clock clock;
    private long nextDispatchMicros;

    public NotificationDispatcher(NotificationOutbox outbox, NotificationSender sender,
            NotificationSettings settings, Clock clock) {
        this.outbox = outbox;
        this.sender = sender;
        this.settings = settings;
        this.clock = clock;
    }

    public synchronized boolean dispatchOne() {
        if (settings.mode() == NotificationSettings.Mode.DISABLED) return false;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notification delivery cannot run inside a database transaction");
        }
        long now = nowMicros();
        if (now < nextDispatchMicros) return false;
        Optional<IncidentNotification> claimed = outbox.claim(now);
        if (claimed.isEmpty()) return false;
        nextDispatchMicros = now + (60_000_000L + settings.maxPerMinute() - 1) / settings.maxPerMinute();
        IncidentNotification notification = claimed.orElseThrow();
        try {
            sender.send(notification);
        } catch (Exception error) {
            // Do not log provider responses, credentials, message content or exception text.
            outbox.failed(notification, nowMicros());
            log.warn("Incident notification delivery deferred for event {}", notification.eventId());
            return false;
        }
        // A crash after SMTP acceptance and before this update can cause a duplicate message.
        return outbox.delivered(notification, nowMicros());
    }

    private long nowMicros() {
        return Math.multiplyExact(clock.millis(), 1000L);
    }

    public NotificationStatus status() {
        return outbox.status(settings.mode());
    }
}
