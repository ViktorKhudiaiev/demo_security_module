package com.demo.securityapp.domain;

import java.util.UUID;

/** Account state read from the protected ledger, not from untrusted source records. */
public record Account(UUID id, String name, String status, long balanceCents) {
}
