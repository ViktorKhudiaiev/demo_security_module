package com.demo.integrity;

public record SignedOperation(Operation operation, String keyId, String contentHash, String mac) {}
