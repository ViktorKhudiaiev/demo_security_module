package com.demo.integrity.model;

/** Immutable statement about a log prefix; not a database entity or a signature. */
public record Checkpoint(String logId, long treeSize, String rootHash, long createdAtMicros) {}
