package com.demo.transferapp.controller;

import com.demo.transferapp.TransferApplication;
import com.demo.transferapp.service.TransferService;
import com.demo.transferapp.support.StubServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TransferApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferApiIntegrationTest {
    static final String API_TOKEN = "test-application-api-credential-at-least-32";
    static final StubServices REMOTE = startDependencies();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    final HttpClient http = HttpClient.newHttpClient();
    static StubServices startDependencies() { try { return new StubServices(); } catch (Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:transfer-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        r.add("spring.datasource.username", () -> "sa"); r.add("spring.datasource.password", () -> "");
        r.add("spring.sql.init.mode", () -> "always"); r.add("spring.sql.init.schema-locations", () -> "classpath:primary-schema.sql");
        r.add("demo.api-token", () -> API_TOKEN); r.add("demo.key-url", REMOTE::url); r.add("demo.writer-token", () -> StubServices.WRITER);
        r.add("demo.processor-url", REMOTE::url); r.add("demo.processor-token", () -> StubServices.PROCESSOR);
    }
    @BeforeEach void reset() {
        for (String table : List.of("transaction_status_events", "transaction_outbox", "transactions", "accounts")) db.update("DELETE FROM " + table);
        REMOTE.reset();
    }
    @AfterAll static void closeDependencies() { REMOTE.close(); }
    HttpResponse<String> request(String method, String path, String body, String credential, String idempotency) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (credential != null) b.header("Authorization", "Bearer " + credential);
        if (idempotency != null) b.header("Idempotency-Key", idempotency);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    String transferJson(long amount) throws Exception {
        return json.writeValueAsString(Map.of("fromAccountId", UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "toAccountId", UUID.fromString("22222222-2222-2222-2222-222222222222"), "amountCents", amount, "currency", "USD"));
    }
    @Test void authenticationIsRequiredForBusinessEndpointsButHealthIsPublic() throws Exception {
        assertEquals(200, request("GET", "/health", null, null, null).statusCode());
        assertEquals(401, request("POST", "/api/transfers", transferJson(100), null, "test").statusCode());
        assertEquals(401, request("POST", "/api/transfers", transferJson(100), "wrong", "test").statusCode());
        assertEquals(401, request("GET", "/api/accounts/" + UUID.randomUUID(), null, null, null).statusCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
    }
    @Test void transferIsAcceptedAsPendingAndSameKeyReturnsSameOperation() throws Exception {
        HttpResponse<String> first = request("POST", "/api/transfers", transferJson(100), API_TOKEN, "retry-key");
        assertEquals(202, first.statusCode()); String id = json.readTree(first.body()).get("id").asText();
        assertEquals("PENDING", json.readTree(first.body()).get("status").asText());
        HttpResponse<String> retry = request("POST", "/api/transfers", transferJson(100), API_TOKEN, "retry-key");
        assertEquals(202, retry.statusCode()); assertEquals(id, json.readTree(retry.body()).get("id").asText());
        assertEquals(409, request("POST", "/api/transfers", transferJson(101), API_TOKEN, "retry-key").statusCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM transaction_outbox", Integer.class));
        HttpResponse<String> pending = request("GET", "/api/operations/" + id, null, API_TOKEN, null);
        assertEquals(200, pending.statusCode()); assertEquals(0, json.readTree(pending.body()).get("postings").size());
    }
    @Test void accountCreationPublishesFundingInsteadOfWritingAnOpeningBalance() throws Exception {
        String body = json.writeValueAsString(Map.of("name", "Alice", "initialBalanceCents", 500));
        HttpResponse<String> result = request("POST", "/api/accounts", body, API_TOKEN, "alice-account");
        assertEquals(201, result.statusCode()); var account = json.readTree(result.body());
        assertEquals(0, account.get("balanceCents").longValue()); assertEquals("PENDING", account.get("fundingStatus").asText());
        UUID fundingId = UUID.fromString(account.get("fundingOperationId").asText());
        assertEquals("FUNDING", REMOTE.receipts.get(fundingId).operation().type());
        assertEquals(TransferService.TREASURY, REMOTE.receipts.get(fundingId).operation().fromAccountId());
    }
    @Test void strictJsonRejectsAmbiguousNumbersDuplicateAndUnknownFields() throws Exception {
        String valid = transferJson(100);
        String[] invalid = { valid.replace("\"amountCents\":100", "\"amountCents\":100.5"),
                valid.replace("\"amountCents\":100", "\"amountCents\":100,\"amountCents\":101"),
                valid.replace("\"amountCents\":100", "\"amountCents\":\"100\""),
                valid.replace("\"amountCents\":100", "\"amountCents\":null"),
                valid.substring(0, valid.length()-1) + ",\"actorCanBypassChecks\":true}", "{not-json" };
        for (String body : invalid) assertEquals(400, request("POST", "/api/transfers", body, API_TOKEN, "invalid").statusCode(), body);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
    }
    @Test void requestValidationRejectsMissingHeaderBadIdsAndInvalidBusinessFields() throws Exception {
        assertEquals(400, request("POST", "/api/transfers", transferJson(100), API_TOKEN, null).statusCode());
        assertEquals(400, request("GET", "/api/accounts/not-a-uuid", null, API_TOKEN, null).statusCode());
        assertEquals(400, request("POST", "/api/transfers", transferJson(0), API_TOKEN, "invalid").statusCode());
        assertEquals(400, request("POST", "/api/transfers", transferJson(1_000_000_000_001L), API_TOKEN, "invalid").statusCode());
        assertEquals(400, request("POST", "/api/transfers", transferJson(1).replace("USD", "EUR"), API_TOKEN, "invalid").statusCode());
        assertEquals(400, request("POST", "/api/transfers", transferJson(1), API_TOKEN, "invalid spaces").statusCode());
        assertTrue(REMOTE.receipts.isEmpty());
    }
    @Test void missingAccountsAndOperationsReturn404() throws Exception {
        assertEquals(404, request("GET", "/api/accounts/" + UUID.randomUUID(), null, API_TOKEN, null).statusCode());
        assertEquals(404, request("GET", "/api/operations/" + UUID.randomUUID(), null, API_TOKEN, null).statusCode());
    }
    @Test void conflictingAccountRegistrationReturnsConflictWithoutMetadataRewrite() throws Exception {
        UUID id = UUID.randomUUID();
        String first = json.writeValueAsString(Map.of("id", id, "name", "Alice", "initialBalanceCents", 0));
        assertEquals(201, request("POST", "/api/accounts", first, API_TOKEN, null).statusCode());
        assertEquals(409, request("POST", "/api/accounts", first.replace("Alice", "Mallory"), API_TOKEN, null).statusCode());
        assertEquals("Alice", db.queryForObject("SELECT name FROM accounts WHERE id=?", String.class, id));
    }
    @Test void dependencyFailuresReturn503WithoutPublishingOrExposingCredentials() throws Exception {
        REMOTE.keysUnavailable = true;
        HttpResponse<String> unavailable = request("POST", "/api/transfers", transferJson(100), API_TOKEN, "retry-on-recovery");
        assertEquals(503, unavailable.statusCode());
        assertFalse(unavailable.body().contains(StubServices.WRITER)); assertFalse(unavailable.body().contains(API_TOKEN));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
        REMOTE.keysUnavailable = false; REMOTE.processorUnavailable = true;
        assertEquals(503, request("GET", "/api/accounts/" + UUID.randomUUID(), null, API_TOKEN, null).statusCode());
        assertEquals(503, request("GET", "/api/operations/" + UUID.randomUUID(), null, API_TOKEN, null).statusCode());
    }
}
