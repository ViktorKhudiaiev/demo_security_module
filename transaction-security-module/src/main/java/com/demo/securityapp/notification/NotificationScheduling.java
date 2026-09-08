package com.demo.securityapp.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "processor.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduling {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduling.class);
    private final NotificationDispatcher dispatcher;

    public NotificationScheduling(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Bean
    ThreadPoolTaskScheduler notificationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-");
        return scheduler;
    }

    @Scheduled(fixedDelay = 1000, scheduler = "notificationTaskScheduler")
    public void dispatch() {
        try {
            dispatcher.dispatchOne();
        } catch (Exception error) {
            // The financial scheduler is independent. Avoid exposing DB/provider exception text.
            log.warn("Incident notification worker deferred; inspect protected outbox delivery state");
        }
    }
}
