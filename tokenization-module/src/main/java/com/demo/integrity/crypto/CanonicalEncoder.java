package com.demo.integrity.crypto;

import com.demo.integrity.model.Operation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Protocol v1: ordered TLVs, each unsigned-byte field id, unsigned-byte type,
 * unsigned 32-bit big-endian byte length, then value. Null = type 0, length 0;
 * UTF-8 = type 1, UUID (16 network-order bytes) = 2, int32 = 3, int64 = 4.
 * Text identifiers are restricted ASCII: no implicit Unicode normalization or precision loss.
 */
public final class CanonicalEncoder {
    private CanonicalEncoder() {}

    public static void validate(Operation op) {
        if (op == null || op.id() == null || op.ledgerId() == null || op.toAccountId() == null)
            throw new IllegalArgumentException("Operation, id, ledger and destination are required");
        if (op.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported canonical schema version");
        if (op.type() == null || !Set.of("TRANSFER", "FUNDING", "REVERSAL", "CORRECTION").contains(op.type()))
            throw new IllegalArgumentException("Unsupported operation type");
        if (op.fromAccountId() == null)
            throw new IllegalArgumentException("Source account is required");
        if (op.toAccountId().equals(op.fromAccountId())) throw new IllegalArgumentException("Accounts must differ");
        UUID treasury = UUID.fromString("00000000-0000-0000-0000-000000000001");
        if (op.type().equals("FUNDING") && (!treasury.equals(op.fromAccountId()) || treasury.equals(op.toAccountId())))
            throw new IllegalArgumentException("Funding must originate from the system treasury");
        if (op.amountMinor() <= 0 || op.amountMinor() > 1_000_000_000_000L)
            throw new IllegalArgumentException("amountMinor must be between 1 and 1000000000000");
        if (op.currency() == null || !op.currency().matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Currency must contain three uppercase ASCII letters");
        if (op.createdAtMicros() <= 0) throw new IllegalArgumentException("Positive UTC epoch microseconds required");
        if (op.idempotencyKey() == null || !op.idempotencyKey().matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("Invalid idempotency key");
        boolean linked = op.type().equals("REVERSAL") || op.type().equals("CORRECTION");
        if (linked != (op.relatedOperationId() != null)) throw new IllegalArgumentException("Relation required only for reversal/correction");
        if (op.id().equals(op.relatedOperationId())) throw new IllegalArgumentException("Operation cannot reference itself");
    }

    public static byte[] encode(Operation op, String keyId) {
        validate(op);
        if (keyId == null || !keyId.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("Invalid keyId");
        return encodeFields(new Object[]{"secure-transfer/operation/hmac/v1", op.schemaVersion(), keyId,
                op.id(), op.ledgerId(), op.type(), op.fromAccountId(), op.toAccountId(), op.amountMinor(),
                op.currency(), op.createdAtMicros(), op.relatedOperationId(), op.idempotencyKey()});
    }

    /** Retry comparison deliberately omits server-generated operation id and timestamp. */
    public static String businessHash(Operation op) {
        validate(op);
        return hashHex(encodeFields(new Object[]{"secure-transfer/business/v1", op.schemaVersion(), op.ledgerId(),
                op.type(), op.fromAccountId(), op.toAccountId(), op.amountMinor(), op.currency(),
                op.relatedOperationId(), op.idempotencyKey()}));
    }

    static byte[] encodeFields(Object[] fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (int i = 0; i < fields.length; i++) {
                Object field = fields[i];
                int type;
                byte[] value;
                if (field == null) { type = 0; value = new byte[0]; }
                else if (field instanceof String s) { type = 1; value = s.getBytes(StandardCharsets.UTF_8); }
                else if (field instanceof UUID id) { type = 2; value = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
                else if (field instanceof Integer n) { type = 3; value = ByteBuffer.allocate(4).putInt(n).array(); }
                else if (field instanceof Long n) { type = 4; value = ByteBuffer.allocate(8).putLong(n).array(); }
                else throw new IllegalArgumentException("Unsupported canonical type");
                out.writeByte(i + 1); out.writeByte(type); out.writeInt(value.length); out.write(value);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String hashHex(Operation operation, String keyId) { return hashHex(encode(operation, keyId)); }
    public static String hashHex(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
