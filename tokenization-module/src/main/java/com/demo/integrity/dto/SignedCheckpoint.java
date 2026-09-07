package com.demo.integrity.dto;

import com.demo.integrity.model.Checkpoint;

/** Public checkpoint response; the trusted verification key must be pinned separately. */
public record SignedCheckpoint(String logId, long treeSize, String rootHash, long createdAtMicros,
                               String keyId, String signature, String publicKey) {
    public Checkpoint checkpoint() { return new Checkpoint(logId, treeSize, rootHash, createdAtMicros); }
}
