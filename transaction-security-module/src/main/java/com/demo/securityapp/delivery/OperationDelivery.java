package com.demo.securityapp.delivery;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** At-least-once transport of untrusted discovery hints, never financial authorization. */
public interface OperationDelivery {
    /** Returns only after durable acceptance; an uncertain failure may still cause a duplicate. */
    void publish(UUID operationId);

    /** Acknowledges each message only after the handler's durable inbox write succeeds. */
    int drain(int limit, Consumer<UUID> handler);

    Map<String, Object> health();
}
