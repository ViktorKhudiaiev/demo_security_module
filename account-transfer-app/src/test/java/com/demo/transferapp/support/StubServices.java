package com.demo.transferapp.support;

import com.demo.integrity.crypto.CanonicalEncoder;
import com.demo.integrity.dto.SignedOperation;
import com.demo.integrity.model.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP contract fixture; does not stand in for cryptographic/settlement security tests. */
public final class StubServices implements AutoCloseable {
    public static final String WRITER = "test-application-writer-credential";
    public static final String PROCESSOR = "test-application-processor-credential";
    public final Map<UUID, SignedOperation> receipts = new ConcurrentHashMap<>();
    public final Map<UUID, Map<String, Object>> accounts = new ConcurrentHashMap<>();
    public final Map<UUID, Map<String, Object>> outcomes = new ConcurrentHashMap<>();
    public volatile boolean keysUnavailable;
    public volatile boolean processorUnavailable;
    public volatile boolean unexpectedIssueResponse;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, SignedOperation> idempotency = new ConcurrentHashMap<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public StubServices() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle); server.setExecutor(executor); server.start();
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public void reset() { receipts.clear(); idempotency.clear(); accounts.clear(); outcomes.clear(); keysUnavailable = false; processorUnavailable = false; unexpectedIssueResponse = false; }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String token = path.startsWith("/v1/") ? WRITER : PROCESSOR;
            if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) { respond(exchange, 403, Map.of("error", "Forbidden")); return; }
            if (path.startsWith("/v1/") && keysUnavailable || path.startsWith("/internal/") && processorUnavailable) { respond(exchange, 503, Map.of("error", "Unavailable")); return; }
            if (path.equals("/v1/mac/issue") && exchange.getRequestMethod().equals("POST")) {
                Operation proposed = json.readValue(exchange.getRequestBody(), Operation.class);
                SignedOperation signed;
                synchronized (idempotency) {
                    String key = proposed.ledgerId() + "/" + proposed.idempotencyKey();
                    SignedOperation prior = idempotency.get(key);
                    if (prior != null && !CanonicalEncoder.businessHash(prior.operation()).equals(CanonicalEncoder.businessHash(proposed))) {
                        respond(exchange, 409, Map.of("error", "Idempotency conflict")); return;
                    }
                    signed = prior == null ? signed(proposed) : prior;
                    receipts.putIfAbsent(signed.operation().id(), signed); idempotency.putIfAbsent(key, signed);
                }
                if (unexpectedIssueResponse) {
                    Operation op = signed.operation();
                    signed = signed(new Operation(op.id(), op.schemaVersion(), op.ledgerId(), op.type(), op.fromAccountId(), op.toAccountId(), op.amountMinor() + 1, op.currency(), op.createdAtMicros(), op.relatedOperationId(), op.idempotencyKey()));
                }
                respond(exchange, 200, signed); return;
            }
            if (path.startsWith("/v1/issuances/") && exchange.getRequestMethod().equals("GET")) {
                SignedOperation signed = receipts.get(UUID.fromString(path.substring("/v1/issuances/".length())));
                respond(exchange, signed == null ? 404 : 200, signed == null ? Map.of("error", "Not found") : signed); return;
            }
            if (path.equals("/internal/accounts") && exchange.getRequestMethod().equals("POST")) {
                var request = json.readTree(exchange.getRequestBody()); UUID id = UUID.fromString(request.get("id").asText()); String name = request.get("name").asText();
                Map<String, Object> result = accounts.computeIfAbsent(id, ignored -> Map.of("id", id, "name", name, "balanceCents", 0L, "status", "ACTIVE"));
                respond(exchange, result.get("name").equals(name) ? 200 : 409, result); return;
            }
            if (path.startsWith("/internal/accounts/") && exchange.getRequestMethod().equals("GET")) {
                Map<String, Object> account = accounts.get(UUID.fromString(path.substring("/internal/accounts/".length())));
                respond(exchange, account == null ? 404 : 200, account == null ? Map.of("error", "Not found") : account); return;
            }
            if (path.startsWith("/internal/operations/") && exchange.getRequestMethod().equals("GET")) {
                Map<String, Object> outcome = outcomes.get(UUID.fromString(path.substring("/internal/operations/".length())));
                respond(exchange, outcome == null ? 404 : 200, outcome == null ? Map.of("error", "Not found") : outcome); return;
            }
            respond(exchange, 404, Map.of("error", "Unknown fixture route"));
        } catch (Exception failure) { respond(exchange, 500, Map.of("error", "Fixture failed: " + failure.getClass().getSimpleName())); }
        finally { exchange.close(); }
    }
    public static SignedOperation signed(Operation operation) { return new SignedOperation(operation, "test-key", CanonicalEncoder.hashHex(operation, "test-key"), "00".repeat(32)); }
    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] data = json.writeValueAsBytes(body); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, data.length); exchange.getResponseBody().write(data);
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
