package com.demo.integrity.dto;

/** One durable issuance receipt and its reconciliation cursor. */
public record IssuanceInventoryItem(long sequence, SignedOperation signedOperation) {}
