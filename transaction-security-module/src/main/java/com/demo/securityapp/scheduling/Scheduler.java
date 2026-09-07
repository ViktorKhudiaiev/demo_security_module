package com.demo.securityapp.scheduling;

import com.demo.securityapp.audit.AuditLog;
import com.demo.securityapp.service.Processor;
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
public class Scheduler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private final Processor processor;
    private final AuditLog audit;

    public Scheduler(Processor processor, AuditLog audit) {
        this.processor = processor;
        this.audit = audit;
    }

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(6);
        scheduler.setThreadNamePrefix("integrity-");
        return scheduler;
    }

    @Scheduled(fixedDelay = 100)
    void discover() {
        run("source discovery", processor::discover);
    }

    @Scheduled(fixedDelay = 5)
    void acceptQueued() {
        run("queue delivery", processor::acceptQueued);
    }

    @Scheduled(fixedDelay = 100)
    void process() {
        run("processing", processor::processReady);
    }

    @Scheduled(fixedDelay = 100)
    void relay() {
        run("outcome relay", processor::relay);
    }

    @Scheduled(fixedDelay = 1000)
    void reconcile() {
        run("reconciliation", processor::reconcile);
    }

    @Scheduled(fixedDelay = 2000)
    void checkpoint() {
        run("checkpoint", audit::checkpoint);
    }

    private void run(String task, Runnable action) {
        try {
            action.run();
        } catch (Exception error) {
            log.warn("{} deferred: {}", task, error.getMessage());
        }
    }
}
