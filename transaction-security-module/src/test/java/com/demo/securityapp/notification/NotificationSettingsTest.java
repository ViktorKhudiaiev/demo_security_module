package com.demo.securityapp.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationSettingsTest {
    private NotificationSettings mailpit(String from, String recipients, String host) {
        return new NotificationSettings("mailpit", from, recipients, host, 1025, "", "", "starttls", 30);
    }

    @Test
    void defaultsDisableDeliveryWithoutRequiringSecrets() {
        assertThat(NotificationSettings.from(new MockEnvironment()).mode())
                .isEqualTo(NotificationSettings.Mode.DISABLED);
    }

    @Test
    void configuredBareRecipientsAreParsedIndividuallyAndDeduplicated() {
        var settings = mailpit("incidents@example.test", "one@example.test, two@example.test,one@example.test",
                "127.0.0.1");
        assertThat(settings.recipients()).containsExactly("one@example.test", "two@example.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@example.test\r\nBcc: thief@example.test", "a@example.test\n", "",
            "not-an-address", "one@example.test;two@example.test", "Someone <one@example.test>",
            "group:one@example.test;", "a@example.test,,b@example.test"})
    void rejectsMalformedRecipientsAndHeaderInjection(String recipient) {
        assertThatThrownBy(() -> mailpit("incidents@example.test", recipient, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMultipleOrInjectedFromAddresses() {
        assertThatThrownBy(() -> mailpit("a@example.test,b@example.test", "to@example.test", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mailpit("a@example.test\r\nReply-To:b@example.test", "to@example.test", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp.example.test", "localhost", "0.0.0.0", "127.0.0.2", "127.0.0.1\n"})
    void mailpitCannotBeUsedAsUnauthenticatedExternalRelay(String host) {
        assertThatThrownBy(() -> mailpit("from@example.test", "to@example.test", host))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authenticatedSmtpCannotDisableTlsOrOmitCredentials() {
        assertThatThrownBy(() -> new NotificationSettings("smtp", "from@example.test", "to@example.test",
                "smtp.example.test", 587, "user", "password", "none", 30))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TLS");
        assertThatThrownBy(() -> new NotificationSettings("smtp", "from@example.test", "to@example.test",
                "smtp.example.test", 587, "", "", "starttls", 30))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("authentication");
    }

    @Test
    void validatesRateRecipientCapAndCaptureCredentials() {
        assertThatThrownBy(() -> new NotificationSettings("disabled", "", "", "", 0, "", "", "", 61))
                .isInstanceOf(IllegalArgumentException.class);
        String recipients = java.util.stream.IntStream.range(0, 21).mapToObj(n -> "person" + n + "@example.test")
                .collect(java.util.stream.Collectors.joining(","));
        assertThatThrownBy(() -> mailpit("from@example.test", recipients, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationSettings("mailpit", "from@example.test", "to@example.test",
                "127.0.0.1", 1025, "secret-user", "secret", "starttls", 30))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
