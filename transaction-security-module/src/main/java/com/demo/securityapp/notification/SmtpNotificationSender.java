package com.demo.securityapp.notification;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;

/** Plain-text incident alerts. Recipients come only from trusted deployment configuration. */
public final class SmtpNotificationSender implements NotificationSender {
    private final JavaMailSenderImpl mail;
    private final NotificationSettings settings;

    public SmtpNotificationSender(NotificationSettings settings) {
        this(settings, configuredMail(settings));
    }

    SmtpNotificationSender(NotificationSettings settings, JavaMailSenderImpl mail) {
        this.settings = settings;
        this.mail = mail;
    }

    static JavaMailSenderImpl configuredMail(NotificationSettings settings) {
        JavaMailSenderImpl mail = new JavaMailSenderImpl();
        mail.setHost(settings.host());
        mail.setPort(settings.port());
        mail.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties properties = mail.getJavaMailProperties();
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
        properties.setProperty("mail.smtp.sendpartial", "false");
        properties.setProperty("mail.debug", "false");
        if (settings.mode() == NotificationSettings.Mode.SMTP) {
            mail.setUsername(settings.username());
            mail.setPassword(settings.password());
            properties.setProperty("mail.smtp.auth", "true");
            properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            if (settings.tls().equals("implicit")) {
                properties.setProperty("mail.smtp.ssl.enable", "true");
            } else {
                properties.setProperty("mail.smtp.starttls.enable", "true");
                properties.setProperty("mail.smtp.starttls.required", "true");
            }
        }
        return mail;
    }

    @Override
    public void send(IncidentNotification notification) throws MessagingException {
        if (settings.mode() == NotificationSettings.Mode.DISABLED) return;
        mail.send(message(notification));
    }

    MimeMessage message(IncidentNotification notification) throws MessagingException {
        MimeMessage message = new StableMessage(mail.getSession(), notification.eventId().toString());
        message.setFrom(new InternetAddress(settings.from()));
        // BCC prevents an individual stakeholder from seeing the configured recipient list.
        for (String recipient : settings.recipients()) {
            message.addRecipient(Message.RecipientType.BCC, new InternetAddress(recipient));
        }
        message.setSubject("Record Integrity: security incident requires review", StandardCharsets.UTF_8.name());
        Instant created = Instant.ofEpochSecond(Math.floorDiv(notification.createdAtMicros(), 1_000_000L),
                Math.floorMod(notification.createdAtMicros(), 1_000_000L) * 1000L);
        message.setSentDate(Date.from(created));
        String reason = notification.reason().replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", " ");
        if (reason.length() > 512) reason = reason.substring(0, 512);
        message.setText("""
                An integrity incident was recorded in the protected audit log.

                Event ID: %s
                Operation ID: %s
                Audit sequence: %d
                Recorded at (UTC): %s
                Reason: %s

                Review the protected audit history and protected processing result.
                An incident or missing record is not proof of fraud. An incident may be
                discovered after settlement; this email does not assert that settlement
                was blocked or reversed. Follow the incident response procedure.

                Delivery is at-least-once; duplicate messages may carry the same Event ID.
                """.formatted(notification.eventId(), notification.operationId(), notification.eventSeq(),
                created, reason), StandardCharsets.UTF_8.name());
        message.saveChanges();
        return message;
    }

    private static final class StableMessage extends MimeMessage {
        private final String eventId;

        StableMessage(Session session, String eventId) {
            super(session);
            this.eventId = eventId;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", "<integrity-" + eventId + "@record-integrity.invalid>");
        }
    }
}
