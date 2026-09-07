package com.demo.integrity.client;

import com.demo.integrity.dto.SignedCheckpoint;
import com.demo.integrity.dto.SignedOperation;
import com.demo.integrity.dto.VerificationResponse;
import com.demo.integrity.model.Checkpoint;
import com.demo.integrity.model.Operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public final class IntegrityClient {
    private final String baseUrl;
    private final String credential;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    public IntegrityClient(String baseUrl, String credential) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        if (credential == null || credential.isBlank()) throw new IllegalArgumentException("Service credential required");
        this.credential = credential;
    }
    public SignedOperation issue(Operation op) { return read(request("POST", "/v1/mac/issue", op), SignedOperation.class); }
    public boolean verify(SignedOperation op) { return read(request("POST", "/v1/mac/verify", op), VerificationResponse.class).valid(); }
    public Optional<SignedOperation> findById(UUID id) { return lookup("/v1/issuances/" + id); }
    public Optional<SignedOperation> findByIdempotency(UUID ledgerId, String idem) {
        return lookup("/v1/issuances?ledgerId=" + ledgerId + "&idempotencyKey=" + URLEncoder.encode(idem, StandardCharsets.UTF_8));
    }
    public SignedCheckpoint signCheckpoint(Checkpoint checkpoint) { return read(request("POST", "/v1/checkpoints", checkpoint), SignedCheckpoint.class); }
    public Optional<SignedCheckpoint> latestCheckpoint() {
        HttpResponse<String> result = request("GET", "/v1/checkpoints/latest", null);
        return result.statusCode() == 404 ? Optional.empty() : Optional.of(read(result, SignedCheckpoint.class));
    }
    private Optional<SignedOperation> lookup(String path) {
        HttpResponse<String> result = request("GET", path, null);
        return result.statusCode() == 404 ? Optional.empty() : Optional.of(read(result, SignedOperation.class));
    }
    private HttpResponse<String> request(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + credential).header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Key service request interrupted", e); }
        catch (IOException e) { throw new IllegalStateException("Key service unavailable", e); }
    }
    private <T> T read(HttpResponse<String> result, Class<T> type) {
        if (result.statusCode() < 200 || result.statusCode() >= 300) throw new ServiceException(result.statusCode());
        try { return mapper.readValue(result.body(), type); }
        catch (IOException e) { throw new IllegalStateException("Invalid key service response", e); }
    }
    public static final class ServiceException extends IllegalStateException {
        private final int status;
        public ServiceException(int status) { super("Key service returned HTTP " + status); this.status = status; }
        public int status() { return status; }
    }
}
