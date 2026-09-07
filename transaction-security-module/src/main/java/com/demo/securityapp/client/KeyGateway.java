package com.demo.securityapp.client;

import com.demo.integrity.client.IntegrityClient;
import com.demo.integrity.crypto.CheckpointEncoder;
import com.demo.integrity.dto.IssuanceInventory;
import com.demo.integrity.dto.PublicKeyInfo;
import com.demo.integrity.dto.SignedCheckpoint;
import com.demo.integrity.dto.SignedOperation;
import com.demo.integrity.model.Checkpoint;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authenticated key-service access and independently pinned checkpoint verification. */
@Component
public class KeyGateway {
    private final IntegrityClient verifier;
    private final IntegrityClient signer;
    private final RestClient inventoryClient;
    private volatile String pinnedPublicKey;

    public KeyGateway(Environment env) {
        String url = env.getRequiredProperty("processor.key-url");
        String token = env.getRequiredProperty("processor.verifier-token");
        verifier = new IntegrityClient(url, token);
        signer = new IntegrityClient(url, env.getRequiredProperty("processor.signer-token"));

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        inventoryClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
        pinnedPublicKey = env.getProperty("processor.checkpoint-public-key");
    }

    public Optional<SignedOperation> receipt(UUID id) {
        return verifier.findById(id);
    }

    public boolean verify(SignedOperation operation) {
        return verifier.verify(operation);
    }

    public IssuanceInventory inventory(long after) {
        return inventoryClient.get()
                .uri("/v1/issuances/inventory?afterSequence=" + after + "&limit=100")
                .retrieve()
                .body(IssuanceInventory.class);
    }

    public SignedCheckpoint sign(Checkpoint checkpoint) {
        return validate(signer.signCheckpoint(checkpoint));
    }

    public Optional<SignedCheckpoint> latest() {
        return signer.latestCheckpoint().map(this::validate);
    }

    private SignedCheckpoint validate(SignedCheckpoint checkpoint) {
        if (pinnedPublicKey == null) {
            synchronized (this) {
                if (pinnedPublicKey == null) {
                    // Pin from the configured key service in the local demo, never from audit evidence.
                    PublicKeyInfo key = inventoryClient.get()
                            .uri("/v1/checkpoints/public-key")
                            .retrieve()
                            .body(PublicKeyInfo.class);
                    pinnedPublicKey = Objects.requireNonNull(key).publicKey();
                }
            }
        }
        if (!CheckpointEncoder.verify(checkpoint, pinnedPublicKey)) {
            throw new IllegalStateException("Checkpoint signature or pinned public key mismatch");
        }
        return checkpoint;
    }
}
