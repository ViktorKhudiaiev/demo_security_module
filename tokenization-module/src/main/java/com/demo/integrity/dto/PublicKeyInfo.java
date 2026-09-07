package com.demo.integrity.dto;

/** Public verification material only; never contains a private key. */
public record PublicKeyInfo(String keyId, String publicKey) {}
