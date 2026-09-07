package com.demo.integrity.dto;

import com.demo.integrity.model.Operation;

/** Wire envelope containing the immutable operation and its integrity evidence. */
public record SignedOperation(Operation operation, String keyId, String contentHash, String mac) {}
