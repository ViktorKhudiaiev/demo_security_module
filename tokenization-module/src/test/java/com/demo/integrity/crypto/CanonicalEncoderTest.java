package com.demo.integrity.crypto;

import com.demo.integrity.model.Checkpoint;
import com.demo.integrity.model.Operation;

import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalEncoderTest {
    static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID LEDGER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID SOURCE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID DESTINATION = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static Operation fixture() { return new Operation(ID, 1, LEDGER, "TRANSFER", SOURCE, DESTINATION, 12345, "USD", 1_700_000_000_000_001L, null, "transfer-001"); }

    @Test void canonicalGoldenVectorMatchesByteForByteAndIndependentSha256() {
        String expected = "0101000000217365637572652d7472616e736665722f6f7065726174696f6e2f686d61632f7631"
                + "02030000000400000001"
                + "030100000009686d61632d74657374"
                + "04020000001011111111111111111111111111111111"
                + "05020000001022222222222222222222222222222222"
                + "0601000000085452414e53464552"
                + "07020000001033333333333333333333333333333333"
                + "08020000001044444444444444444444444444444444"
                + "0904000000080000000000003039"
                + "0a0100000003555344"
                + "0b040000000800060a24181e4001"
                + "0c0000000000"
                + "0d010000000c7472616e736665722d303031";
        byte[] encoded = CanonicalEncoder.encode(fixture(), "hmac-test");
        assertEquals(expected, HexFormat.of().formatHex(encoded));
        assertEquals("1813bad87578b52caa23303f23369c469f5a4ccca2991ad9fefc4b93871c7bc4", CanonicalEncoder.hashHex(encoded));
    }
    @Test void oneMicrosecondAndEverySecurityRelevantMutationChangesHash() {
        Operation a = fixture();
        Operation[] mutations = {
            new Operation(UUID.randomUUID(), 1, LEDGER, "TRANSFER", SOURCE, DESTINATION, 12345, "USD", a.createdAtMicros(), null, "transfer-001"),
            new Operation(ID, 1, UUID.randomUUID(), "TRANSFER", SOURCE, DESTINATION, 12345, "USD", a.createdAtMicros(), null, "transfer-001"),
            new Operation(ID, 1, LEDGER, "TRANSFER", UUID.randomUUID(), DESTINATION, 12345, "USD", a.createdAtMicros(), null, "transfer-001"),
            new Operation(ID, 1, LEDGER, "TRANSFER", SOURCE, UUID.randomUUID(), 12345, "USD", a.createdAtMicros(), null, "transfer-001"),
            new Operation(ID, 1, LEDGER, "TRANSFER", SOURCE, DESTINATION, 12346, "USD", a.createdAtMicros(), null, "transfer-001"),
            new Operation(ID, 1, LEDGER, "TRANSFER", SOURCE, DESTINATION, 12345, "EUR", a.createdAtMicros(), null, "transfer-001"),
            new Operation(ID, 1, LEDGER, "TRANSFER", SOURCE, DESTINATION, 12345, "USD", a.createdAtMicros() + 1, null, "transfer-001"),
            new Operation(ID, 1, LEDGER, "TRANSFER", SOURCE, DESTINATION, 12345, "USD", a.createdAtMicros(), null, "transfer-002"),
            new Operation(ID, 1, LEDGER, "REVERSAL", SOURCE, DESTINATION, 12345, "USD", a.createdAtMicros(), UUID.randomUUID(), "transfer-001")
        };
        String original = CanonicalEncoder.hashHex(a, "hmac-test");
        for (Operation mutated : mutations) assertNotEquals(original, CanonicalEncoder.hashHex(mutated, "hmac-test"));
        assertNotEquals(original, CanonicalEncoder.hashHex(a, "hmac-other"));
    }
    @Test void nullEmptyAndConcatenationBoundariesAreUnambiguous() {
        assertFalse(java.util.Arrays.equals(CanonicalEncoder.encodeFields(new Object[]{null}), CanonicalEncoder.encodeFields(new Object[]{""})));
        assertFalse(java.util.Arrays.equals(CanonicalEncoder.encodeFields(new Object[]{"AB", "C"}), CanonicalEncoder.encodeFields(new Object[]{"A", "BC"})));
        assertFalse(java.util.Arrays.equals(CanonicalEncoder.encodeFields(new Object[]{}), CanonicalEncoder.encodeFields(new Object[]{null})));
    }
    @Test void schemaAndUnsupportedTextAreRejectedRatherThanSilentlyNormalized() {
        Operation a = fixture();
        assertThrows(IllegalArgumentException.class, () -> CanonicalEncoder.encode(new Operation(ID, 2, LEDGER, a.type(), SOURCE, DESTINATION, a.amountMinor(), "USD", a.createdAtMicros(), null, "test"), "key"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalEncoder.encode(new Operation(ID, 1, LEDGER, a.type(), SOURCE, DESTINATION, a.amountMinor(), "USD", a.createdAtMicros(), null, "e\u0301"), "key"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalEncoder.encode(new Operation(ID, 1, LEDGER, a.type(), SOURCE, DESTINATION, 0, "USD", a.createdAtMicros(), null, "test"), "key"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalEncoder.encode(new Operation(ID, 1, LEDGER, a.type(), SOURCE, DESTINATION, 1_000_000_000_001L, "USD", a.createdAtMicros(), null, "test"), "key"));
    }
    @Test void relationRedirectChangesCommitmentAndRetryFingerprintOmitsOnlyIdAndTime() {
        Operation a = fixture();
        Operation retry = new Operation(UUID.randomUUID(), 1, LEDGER, a.type(), SOURCE, DESTINATION, a.amountMinor(), "USD", a.createdAtMicros() + 1, null, a.idempotencyKey());
        assertEquals(CanonicalEncoder.businessHash(a), CanonicalEncoder.businessHash(retry));
        Operation r1 = new Operation(ID, 1, LEDGER, "CORRECTION", SOURCE, DESTINATION, a.amountMinor(), "USD", a.createdAtMicros(), UUID.randomUUID(), "correction");
        Operation r2 = new Operation(ID, 1, LEDGER, "CORRECTION", SOURCE, DESTINATION, a.amountMinor(), "USD", a.createdAtMicros(), UUID.randomUUID(), "correction");
        assertNotEquals(CanonicalEncoder.hashHex(r1, "key"), CanonicalEncoder.hashHex(r2, "key"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalEncoder.encode(new Operation(ID, 1, LEDGER, "CORRECTION", SOURCE, DESTINATION, a.amountMinor(), "USD", a.createdAtMicros(), null, "correction"), "key"));
    }
    @Test void checkpointFormatRejectsInvalidSizesHashAndUnboundedKeyId() {
        Checkpoint valid = new Checkpoint("security-audit", 0, "ab".repeat(32), 1700000000000001L);
        assertThrows(IllegalArgumentException.class, () -> CheckpointEncoder.encode(valid, "x".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> CheckpointEncoder.encode(new Checkpoint(valid.logId(), -1, valid.rootHash(), valid.createdAtMicros()), "key"));
        assertThrows(IllegalArgumentException.class, () -> CheckpointEncoder.encode(new Checkpoint(valid.logId(), 0, "AB".repeat(32), valid.createdAtMicros()), "key"));
    }
}
