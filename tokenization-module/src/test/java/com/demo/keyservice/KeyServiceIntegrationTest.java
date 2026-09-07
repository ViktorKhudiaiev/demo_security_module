package com.demo.keyservice;

import com.demo.integrity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = KeyServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("keyservice")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KeyServiceIntegrationTest {
    static final String WRITER = "integration-writer-only";
    static final String VERIFIER = "integration-verifier-only";
    static final String ADMIN = "integration-admin-only";
    static final String SIGNER = "integration-signer-only";
    // Close the Spring context/vault after this class, then let JUnit remove the
    // owner-restricted fixture under the same identity that created it.
    @TempDir static Path KEYS;
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalKeyVault vault;
    HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:keyservice;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.username", () -> "sa"); r.add("spring.datasource.password", () -> "");
        r.add("spring.sql.init.mode", () -> "always");
        r.add("keyservice.directory", KEYS::toString);
        r.add("keyservice.writer-token", () -> WRITER); r.add("keyservice.verifier-token", () -> VERIFIER);
        r.add("keyservice.admin-token", () -> ADMIN); r.add("keyservice.signer-token", () -> SIGNER);
    }
    static Operation operation() { return new Operation(UUID.randomUUID(), 1, UUID.randomUUID(), "TRANSFER", UUID.randomUUID(), UUID.randomUUID(), 100, "USD", 1700000000000001L, null, UUID.randomUUID().toString()); }
    IntegrityClient client(String credential) { return new IntegrityClient("http://127.0.0.1:" + port, credential); }
    HttpResponse<String> request(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void receiptIsDurableBeforeReturnAndTamperingFails() {
        Operation op = operation();
        SignedOperation signed = client(WRITER).issue(op);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM issuance_receipts WHERE id=?", Integer.class, op.id()));
        assertTrue(client(VERIFIER).verify(signed));
        assertEquals(signed, client(VERIFIER).findById(op.id()).orElseThrow());
        assertEquals(signed, client(WRITER).findByIdempotency(op.ledgerId(), op.idempotencyKey()).orElseThrow());
        Operation changed = new Operation(op.id(), 1, op.ledgerId(), op.type(), op.fromAccountId(), op.toAccountId(), 101, op.currency(), op.createdAtMicros(), null, op.idempotencyKey());
        assertFalse(client(VERIFIER).verify(new SignedOperation(changed, signed.keyId(), signed.contentHash(), signed.mac())));
        assertFalse(client(VERIFIER).verify(new SignedOperation(op, signed.keyId(), signed.contentHash(), "00".repeat(32))));
        assertFalse(client(VERIFIER).verify(new SignedOperation(op, "unknown", signed.contentHash(), signed.mac())));
    }
    @Test void writerVerifierSignerAndAdminHaveDisjointCapabilities() throws Exception {
        Operation op = operation();
        assertEquals(401, request("POST", "/v1/mac/issue", op, null).statusCode());
        for (String token : new String[]{VERIFIER, ADMIN, SIGNER, "invalid"}) assertEquals(403, request("POST", "/v1/mac/issue", op, token).statusCode());
        SignedOperation signed = client(WRITER).issue(op);
        assertEquals(403, request("POST", "/v1/mac/verify", signed, WRITER).statusCode());
        assertEquals(403, request("POST", "/v1/keys/rotate", null, VERIFIER).statusCode());
        assertEquals(403, request("GET", "/v1/issuances/inventory", null, WRITER).statusCode());
        assertEquals(200, request("GET", "/health", null, null).statusCode());
    }
    @Test void retryReturnsOriginalAndConflictsAreRejected() {
        Operation original = operation(); SignedOperation signed = client(WRITER).issue(original);
        Operation retry = new Operation(UUID.randomUUID(), 1, original.ledgerId(), original.type(), original.fromAccountId(), original.toAccountId(), original.amountMinor(), original.currency(), original.createdAtMicros() + 10, null, original.idempotencyKey());
        assertEquals(signed, client(WRITER).issue(retry));
        Operation conflict = new Operation(retry.id(), 1, retry.ledgerId(), retry.type(), retry.fromAccountId(), retry.toAccountId(), retry.amountMinor() + 1, retry.currency(), retry.createdAtMicros(), null, retry.idempotencyKey());
        assertEquals(409, assertThrows(IntegrityClient.ServiceException.class, () -> client(WRITER).issue(conflict)).status());
        Operation duplicateId = new Operation(original.id(), 1, original.ledgerId(), original.type(), original.fromAccountId(), original.toAccountId(), original.amountMinor(), original.currency(), original.createdAtMicros(), null, "different-key");
        assertEquals(409, assertThrows(IntegrityClient.ServiceException.class, () -> client(WRITER).issue(duplicateId)).status());
    }
    @Test void rotationRetainsHistoricalVerification() throws Exception {
        SignedOperation old = client(WRITER).issue(operation());
        assertEquals(200, request("POST", "/v1/keys/rotate", null, ADMIN).statusCode());
        SignedOperation current = client(WRITER).issue(operation());
        assertNotEquals(old.keyId(), current.keyId());
        assertTrue(client(VERIFIER).verify(old)); assertTrue(client(VERIFIER).verify(current));
    }
    @Test void inventoryIncludesIssuedButNeverInsertedInPrimaryAndIsPaginated() throws Exception {
        SignedOperation signed = client(WRITER).issue(operation());
        HttpResponse<String> result = request("GET", "/v1/issuances/inventory?afterSequence=0&limit=1000", null, VERIFIER);
        assertEquals(200, result.statusCode());
        IssuanceService.Inventory inventory = mapper.readValue(result.body(), IssuanceService.Inventory.class);
        assertTrue(inventory.items().stream().anyMatch(i -> i.signedOperation().equals(signed)));
        assertTrue(inventory.nextSequence() > 0);
        assertEquals(400, request("GET", "/v1/issuances/inventory?limit=1001", null, VERIFIER).statusCode());
        assertTrue(client(VERIFIER).findById(UUID.randomUUID()).isEmpty());
    }
    @Test void jsonDoesNotSilentlyRoundNumbersAcceptDuplicatesOrDropUnknownFields() throws Exception {
        String valid = mapper.writeValueAsString(operation());
        String[] invalid = { valid.replace("\"amountMinor\":100", "\"amountMinor\":100.5"),
                valid.replace("\"amountMinor\":100", "\"amountMinor\":100,\"amountMinor\":101"),
                valid.replace("\"amountMinor\":100", "\"amountMinor\":\"100\""),
                valid.replace("\"relatedOperationId\":null,", ""),
                valid.substring(0, valid.length()-1) + ",\"uncommittedField\":true}" };
        for (String body : invalid) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/mac/issue"))
                    .header("Authorization", "Bearer " + WRITER).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertEquals(400, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(), body);
        }
    }
    @Test void signedCheckpointsAreExternallyVerifiableAndRejectRollbackOrConflictingRoots() throws Exception {
        String log = "test-" + UUID.randomUUID();
        Checkpoint first = new Checkpoint(log, 1, "ab".repeat(32), 1700000000000001L);
        assertEquals(403, request("POST", "/v1/checkpoints", first, WRITER).statusCode());
        SignedCheckpoint signed = client(SIGNER).signCheckpoint(first);
        String pinned = vault.publicKey().get("publicKey");
        assertTrue(CheckpointEncoder.verify(signed, pinned));
        assertFalse(CheckpointEncoder.verify(new SignedCheckpoint(log, 1, "cd".repeat(32), first.createdAtMicros(), signed.keyId(), signed.signature(), signed.publicKey()), pinned));
        assertEquals(409, request("POST", "/v1/checkpoints", new Checkpoint(log, 0, first.rootHash(), first.createdAtMicros()+1), SIGNER).statusCode());
        assertEquals(409, request("POST", "/v1/checkpoints", new Checkpoint(log, 1, "cd".repeat(32), first.createdAtMicros()+1), SIGNER).statusCode());
        SignedCheckpoint heartbeat = client(SIGNER).signCheckpoint(new Checkpoint(log, 1, first.rootHash(), first.createdAtMicros()+1));
        assertTrue(CheckpointEncoder.verify(heartbeat, pinned));
        assertEquals(heartbeat, mapper.readValue(request("GET", "/v1/checkpoints/latest?logId=" + log, null, null).body(), SignedCheckpoint.class));
        assertTrue(Files.isDirectory(KEYS.resolve("anchors")));
    }
}
