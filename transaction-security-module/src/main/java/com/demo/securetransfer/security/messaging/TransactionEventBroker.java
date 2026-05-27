package com.demo.securetransfer.security.messaging;

import com.demo.securetransfer.security.domain.OutboxEvent;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class TransactionEventBroker {
    private final BlockingQueue<OutboxEvent> queue = new LinkedBlockingQueue<>();

    public void publish(OutboxEvent event) {
        queue.offer(event);
    }

    public Optional<OutboxEvent> poll() {
        return Optional.ofNullable(queue.poll());
    }
}
