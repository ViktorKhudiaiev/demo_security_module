package com.demo.securityapp.notification;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpNotificationSenderTest {
    private static NotificationSettings settings(String mode, String tls) {
        return new NotificationSettings(mode, "incidents@example.test", "one@example.test,two@example.test",
                "127.0.0.1", 1025, mode.equals("smtp") ? "account" : "",
                mode.equals("smtp") ? "secret-password" : "", tls, 30);
    }

    @Test
    void plainTextMimeUsesOnlyConfiguredBccAndStableEventMessageId() throws Exception {
        IncidentNotification incident = new IncidentNotification(UUID.randomUUID(), UUID.randomUUID(),
                42, "INTEGRITY_INCIDENT\r\nBcc: injected@example.test", 1_788_825_600_123_456L, 1,
                UUID.randomUUID());
        var sender = new SmtpNotificationSender(settings("mailpit", "starttls"));
        MimeMessage message = sender.message(incident);
        String stableId = message.getMessageID();
        message.saveChanges();
        assertThat(message.getMessageID()).isEqualTo(stableId)
                .isEqualTo("<integrity-" + incident.eventId() + "@record-integrity.invalid>");
        assertThat(sender.message(incident).getMessageID()).isEqualTo(stableId);
        assertThat(message.getRecipients(Message.RecipientType.BCC)).hasSize(2);
        assertThat(message.getRecipients(Message.RecipientType.TO)).isNull();
        assertThat(message.getHeader("Reply-To")).isNull();
        assertThat(message.getSubject()).isEqualTo("Record Integrity: security incident requires review");
        assertThat(message.isMimeType("text/plain")).isTrue();
        String text = (String) message.getContent();
        assertThat(text).contains(incident.eventId().toString(), incident.operationId().toString(),
                "Audit sequence: 42", "not proof of fraud", "after settlement", "at-least-once")
                .doesNotContain("\nBcc:", "secret-password", "amountMinor", "tokenBase64", "recipientAccount");
        // JavaMail SMTPTransport suppresses the BCC header on the wire using this same writeTo overload.
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        message.writeTo(wire, new String[] {"Bcc", "Content-Length"});
        assertThat(wire.toString(StandardCharsets.UTF_8)).doesNotContain("\r\nBcc:");
    }

    @Test
    void smtpAlwaysUsesAuthRequiredTlsHostnameValidationAndFiniteTimeouts() {
        var mail = SmtpNotificationSender.configuredMail(settings("smtp", "starttls"));
        var properties = mail.getJavaMailProperties();
        assertThat(properties).containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.connectiontimeout", "5000")
                .containsEntry("mail.smtp.timeout", "5000")
                .containsEntry("mail.smtp.writetimeout", "5000")
                .containsEntry("mail.smtp.sendpartial", "false")
                .containsEntry("mail.debug", "false");
        assertThat(properties).doesNotContainKey("mail.smtp.ssl.trust");
        assertThat(mail.getUsername()).isEqualTo("account");
        assertThat(SmtpNotificationSender.configuredMail(settings("smtp", "implicit"))
                .getJavaMailProperties()).containsEntry("mail.smtp.ssl.enable", "true");
    }

    @Test
    void localCaptureHasNoAuthenticationAndSettingsToStringDoesNotExposeSecrets() {
        assertThat(SmtpNotificationSender.configuredMail(settings("mailpit", "starttls"))
                .getJavaMailProperties()).doesNotContainKey("mail.smtp.auth");
        assertThat(settings("smtp", "starttls").toString()).doesNotContain("secret-password");
    }
}
