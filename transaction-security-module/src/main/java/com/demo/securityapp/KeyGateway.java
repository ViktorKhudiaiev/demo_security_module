package com.demo.securityapp;

import com.demo.integrity.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

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
        JdkClientHttpRequestFactory factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        inventoryClient = RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("Authorization", "Bearer " + token).build();
        pinnedPublicKey=env.getProperty("processor.checkpoint-public-key");
    }
    public Optional<SignedOperation> receipt(UUID id) { return verifier.findById(id); }
    public boolean verify(SignedOperation op) { return verifier.verify(op); }
    public Inventory inventory(long after) {
        return inventoryClient.get().uri("/v1/issuances/inventory?afterSequence=" + after + "&limit=100")
                .retrieve().body(Inventory.class);
    }
    public SignedCheckpoint sign(Checkpoint checkpoint) { return validate(signer.signCheckpoint(checkpoint)); }
    public Optional<SignedCheckpoint> latest() { return signer.latestCheckpoint().map(this::validate); }
    private SignedCheckpoint validate(SignedCheckpoint checkpoint) {
        if(pinnedPublicKey==null) {
            synchronized(this) {
                if(pinnedPublicKey==null) {
                    // Local demo pins from the independently configured key service, never from audit evidence.
                    PublicKeyInfo key=inventoryClient.get().uri("/v1/checkpoints/public-key").retrieve().body(PublicKeyInfo.class);
                    pinnedPublicKey=Objects.requireNonNull(key).publicKey();
                }
            }
        }
        if(!CheckpointEncoder.verify(checkpoint,pinnedPublicKey))throw new IllegalStateException("Checkpoint signature or pinned public key mismatch");
        return checkpoint;
    }
    public record PublicKeyInfo(String keyId,String publicKey){}
    public record InventoryItem(long sequence, SignedOperation signedOperation) {}
    public record Inventory(List<InventoryItem> items, long nextSequence) {}
}
