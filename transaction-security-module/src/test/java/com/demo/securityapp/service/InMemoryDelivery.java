package com.demo.securityapp.service;

import com.demo.securityapp.delivery.OperationDelivery;
import java.util.*;
import java.util.function.Consumer;

/** Test-only deterministic substitute; persistence is tested against the real broker separately. */
final class InMemoryDelivery implements OperationDelivery {
    private final Queue<UUID> ids = new ArrayDeque<>();
    public void publish(UUID id) { ids.add(id); }
    public int drain(int limit, Consumer<UUID> handler) {
        int count = 0;
        while (count < limit && !ids.isEmpty()) { handler.accept(ids.peek()); ids.remove(); count++; }
        return count;
    }
    public Map<String,Object> health() { return Map.of("status", "UP"); }
}
