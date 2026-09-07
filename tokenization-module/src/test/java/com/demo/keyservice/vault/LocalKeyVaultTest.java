package com.demo.keyservice.vault;

import com.demo.integrity.crypto.CanonicalEncoder;
import com.demo.integrity.dto.SignedCheckpoint;
import com.demo.integrity.model.Checkpoint;
import com.demo.integrity.model.Operation;
import com.demo.keyservice.exception.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LocalKeyVaultTest {
    @TempDir Path directory;
    @Test void fixedCanonicalBytesProduceIndependentGoldenHmac() throws Exception {
        byte[] secret = new byte[32]; java.util.Arrays.fill(secret, (byte) 0x0b);
        Files.write(directory.resolve("hmac-test.key"), secret);
        Files.writeString(directory.resolve("current-hmac"), "hmac-test");
        LocalKeyVault vault = new LocalKeyVault(directory.toString(), new ObjectMapper());
        try {
            Operation operation = new Operation(UUID.fromString("11111111-1111-1111-1111-111111111111"), 1,
                    UUID.fromString("22222222-2222-2222-2222-222222222222"), "TRANSFER",
                    UUID.fromString("33333333-3333-3333-3333-333333333333"), UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    12345, "USD", 1700000000000001L, null, "transfer-001");
            assertEquals("d8ea729dbe474b62efa1c7a2b72a62da48b480c5c277d1d121d05e89c2f88958",
                    vault.mac(CanonicalEncoder.encode(operation, "hmac-test"), "hmac-test"));
        } finally { vault.close(); }
    }
    @Test void restartKeepsKeysAndAnchorsAndRetainsOldMacAfterRotation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LocalKeyVault first = new LocalKeyVault(directory.toString(), mapper);
        String oldKey = first.currentKeyId(); byte[] content = {1,2,3}; String mac = first.mac(content, oldKey);
        String newKey = first.rotate();
        SignedCheckpoint head = first.sign(new Checkpoint("security-audit", 3, "aa".repeat(32), 1700000000000001L));
        String publicKey = first.publicKey().publicKey(); first.close();
        LocalKeyVault second = new LocalKeyVault(directory.toString(), mapper);
        try {
            assertEquals(newKey, second.currentKeyId());
            assertTrue(second.verify(content, oldKey, mac));
            assertEquals(publicKey, second.publicKey().publicKey());
            assertEquals(head, second.latest("security-audit").orElseThrow());
            assertThrows(ConflictException.class, () -> second.sign(new Checkpoint("security-audit", 2, "bb".repeat(32), head.createdAtMicros()+1)));
        } finally { second.close(); }
    }
}
