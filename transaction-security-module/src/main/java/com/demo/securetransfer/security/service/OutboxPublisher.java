package com.demo.securetransfer.security.service;

import com.demo.securetransfer.security.messaging.TransactionEventBroker;
import com.demo.securetransfer.security.repository.OutboxRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private final OutboxRepository outboxRepository;
    private final TransactionEventBroker broker;

    public OutboxPublisher(OutboxRepository outboxRepository, TransactionEventBroker broker) {
        this.outboxRepository = outboxRepository;
        this.broker = broker;
    }

    @Scheduled(fixedDelayString = "${demo.security.poll-interval-ms}")
    public void publishPendingEvents() {
        outboxRepository.findUnprocessed(25).forEach(event -> {
            broker.publish(event);
            outboxRepository.markProcessed(event.id());
        });
    }
}
