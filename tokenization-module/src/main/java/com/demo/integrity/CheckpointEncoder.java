package com.demo.integrity;

import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class CheckpointEncoder {
    private CheckpointEncoder() {}
    public static byte[] encode(Checkpoint c, String keyId) {
        if (c == null || c.logId() == null || !c.logId().matches("[A-Za-z0-9._:-]{1,128}")
                || c.treeSize() < 0 || c.createdAtMicros() <= 0 || c.rootHash() == null
                || !c.rootHash().matches("[0-9a-f]{64}") || keyId == null || !keyId.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("Invalid checkpoint");
        return CanonicalEncoder.encodeFields(new Object[]{"secure-transfer/checkpoint/ed25519/v1", 1,
                keyId, c.logId(), c.treeSize(), c.rootHash(), c.createdAtMicros()});
    }
    /** The caller must pin expectedPublicKey outside the untrusted evidence store. */
    public static boolean verify(SignedCheckpoint c, String expectedPublicKey) {
        try {
            if (!expectedPublicKey.equals(c.publicKey())) return false;
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(expectedPublicKey))));
            signature.update(encode(c.checkpoint(), c.keyId()));
            return signature.verify(Base64.getDecoder().decode(c.signature()));
        } catch (Exception invalid) { return false; }
    }
}
