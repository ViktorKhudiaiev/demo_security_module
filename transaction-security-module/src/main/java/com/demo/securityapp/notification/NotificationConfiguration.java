package com.demo.securityapp.notification;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration
public class NotificationConfiguration {
    @Bean
    NotificationSettings notificationSettings(Environment environment) {
        return NotificationSettings.from(environment);
    }

    @Bean
    NotificationOutbox notificationOutbox(@Qualifier("auditDb") JdbcTemplate auditDb) {
        return new NotificationOutbox(auditDb);
    }

    @Bean
    NotificationSender notificationSender(NotificationSettings settings) {
        return new SmtpNotificationSender(settings);
    }

    @Bean
    NotificationDispatcher notificationDispatcher(NotificationOutbox outbox,
            NotificationSender sender, NotificationSettings settings) {
        return new NotificationDispatcher(outbox, sender, settings, Clock.systemUTC());
    }
}
