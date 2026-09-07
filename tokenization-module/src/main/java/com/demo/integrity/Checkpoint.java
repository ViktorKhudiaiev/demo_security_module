package com.demo.integrity;

public record Checkpoint(String logId, long treeSize, String rootHash, long createdAtMicros) {}
