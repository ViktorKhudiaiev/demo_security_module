package com.demo.integrity;

public record SignedCheckpoint(String logId, long treeSize, String rootHash, long createdAtMicros,
                               String keyId, String signature, String publicKey) {
    public Checkpoint checkpoint() { return new Checkpoint(logId, treeSize, rootHash, createdAtMicros); }
}
