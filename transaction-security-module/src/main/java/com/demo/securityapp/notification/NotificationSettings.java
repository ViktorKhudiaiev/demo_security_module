package com.demo.securityapp.notification;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Configuration is intentionally not a record: generated toString must not reveal secrets. */
public final class NotificationSettings {
    public enum Mode { DISABLED, MAILPIT, SMTP }

    private final Mode mode;
    private final String from;
    private final List<String> recipients;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String tls;
    private final int maxPerMinute;

    public NotificationSettings(String mode, String from, String recipients, String host, int port,
            String username, String password, String tls, int maxPerMinute) {
        try {
            this.mode = Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Notification mode must be disabled, mailpit or smtp");
        }
        this.from = from;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.tls = tls;
        this.maxPerMinute = maxPerMinute;
        if (maxPerMinute < 1 || maxPerMinute > 60) {
            throw new IllegalArgumentException("Notification rate must be between 1 and 60 per minute");
        }
        if (this.mode == Mode.DISABLED) {
            this.recipients = List.of();
            return;
        }
        validateAddress(from);
        if (recipients == null || recipients.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("Notification recipients cannot contain control characters");
        }
        List<String> configured = new ArrayList<>();
        for (String recipient : recipients.split(",", -1)) {
            String address = recipient.strip();
            validateAddress(address);
            if (!configured.contains(address)) configured.add(address);
        }
        if (configured.isEmpty() || configured.size() > 20) {
            throw new IllegalArgumentException("Configure between 1 and 20 notification recipients");
        }
        this.recipients = List.copyOf(configured);
        if (port < 1 || port > 65535 || host == null || host.isBlank()
                || host.chars().anyMatch(c -> c <= 32 || c == 127)) {
            throw new IllegalArgumentException("Invalid notification SMTP endpoint");
        }
        if (this.mode == Mode.MAILPIT) {
            if (!(host.equals("127.0.0.1") || host.equals("::1"))) {
                throw new IllegalArgumentException("Mailpit capture must use a literal loopback address");
            }
            if (!username.isEmpty() || !password.isEmpty()) {
                throw new IllegalArgumentException("Mailpit capture does not accept SMTP credentials");
            }
        } else {
            if (username.isBlank() || password.isBlank()) {
                throw new IllegalArgumentException("SMTP requires configured authentication credentials");
            }
            if (!(tls.equals("starttls") || tls.equals("implicit"))) {
                throw new IllegalArgumentException("SMTP requires starttls or implicit TLS");
            }
        }
    }

    public static NotificationSettings from(Environment env) {
        String prefix = "processor.notifications.";
        return new NotificationSettings(env.getProperty(prefix + "mode", "disabled"),
                env.getProperty(prefix + "from", ""), env.getProperty(prefix + "recipients", ""),
                env.getProperty(prefix + "smtp.host", "127.0.0.1"),
                env.getProperty(prefix + "smtp.port", Integer.class, 1025),
                env.getProperty(prefix + "smtp.username", ""), env.getProperty(prefix + "smtp.password", ""),
                env.getProperty(prefix + "smtp.tls", "starttls"),
                env.getProperty(prefix + "max-per-minute", Integer.class, 30));
    }

    private static void validateAddress(String address) {
        try {
            if (address == null || address.isEmpty() || address.length() > 254
                    || address.chars().anyMatch(c -> c <= 32 || c >= 127)
                    || address.contains(",") || address.contains(";")
                    || address.contains("<") || address.contains(">") || address.contains(":")) {
                throw new AddressException();
            }
            InternetAddress parsed = new InternetAddress(address, true);
            parsed.validate();
            if (parsed.isGroup() || parsed.getPersonal() != null || !address.equals(parsed.getAddress())
                    || address.indexOf('@') <= 0 || address.endsWith("@")) {
                throw new AddressException();
            }
        } catch (AddressException error) {
            throw new IllegalArgumentException("Notification addresses must be bare, valid ASCII email addresses");
        }
    }

    public Mode mode() { return mode; }
    public String from() { return from; }
    public List<String> recipients() { return recipients; }
    public String host() { return host; }
    public int port() { return port; }
    public String username() { return username; }
    public String password() { return password; }
    public String tls() { return tls; }
    public int maxPerMinute() { return maxPerMinute; }
}
