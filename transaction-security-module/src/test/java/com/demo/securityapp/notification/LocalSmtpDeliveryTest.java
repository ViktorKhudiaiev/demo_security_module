package com.demo.securityapp.notification;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real JavaMail SMTP delivery to an ephemeral loopback-only test receiver; no public service. */
class LocalSmtpDeliveryTest {
    @Test
    void emitsOnePlainTextMessageWithTwoEnvelopeRecipientsAndNoBccHeader() throws Exception {
        try (ServerSocket receiver = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var worker = Executors.newSingleThreadExecutor()) {
            receiver.setSoTimeout(5000);
            var captured = worker.submit(() -> {
                List<String> recipients = new ArrayList<>();
                StringBuilder data = new StringBuilder();
                try (var socket = receiver.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                    output.print("220 loopback.test ESMTP\r\n");
                    output.flush();
                    String line;
                    boolean receivingData = false;
                    while ((line = input.readLine()) != null) {
                        if (receivingData) {
                            if (line.equals(".")) {
                                receivingData = false;
                                output.print("250 accepted\r\n");
                            } else {
                                data.append(line).append("\r\n");
                            }
                        } else if (line.startsWith("EHLO") || line.startsWith("HELO") || line.startsWith("MAIL FROM:")) {
                            output.print("250 OK\r\n");
                        } else if (line.startsWith("RCPT TO:")) {
                            recipients.add(line);
                            output.print("250 OK\r\n");
                        } else if (line.equals("DATA")) {
                            receivingData = true;
                            output.print("354 send message\r\n");
                        } else if (line.equals("QUIT")) {
                            output.print("221 goodbye\r\n");
                            output.flush();
                            break;
                        } else {
                            throw new IllegalStateException("Unexpected SMTP command in local test");
                        }
                        output.flush();
                    }
                }
                return new Capture(recipients, data.toString());
            });
            var settings = new NotificationSettings("mailpit", "incidents@example.test",
                    "one@example.test,two@example.test", "127.0.0.1", receiver.getLocalPort(),
                    "", "", "starttls", 30);
            UUID eventId = UUID.randomUUID();
            new SmtpNotificationSender(settings).send(new IncidentNotification(UUID.randomUUID(), eventId,
                    7, "Integrity discrepancy detected; inspect protected audit evidence.",
                    1_788_825_600_000_000L, 1, UUID.randomUUID()));
            Capture result = captured.get(10, TimeUnit.SECONDS);
            assertThat(result.recipients()).containsExactly("RCPT TO:<one@example.test>", "RCPT TO:<two@example.test>");
            assertThat(result.data()).contains("Content-Type: text/plain; charset=UTF-8",
                    "Message-ID: <integrity-" + eventId + "@record-integrity.invalid>",
                    "not proof of fraud", "after settlement")
                    .doesNotContain("\r\nBcc:", "one@example.test", "two@example.test", "text/html");
        }
    }

    private record Capture(List<String> recipients, String data) { }
}
