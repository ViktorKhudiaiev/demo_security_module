package com.demo.keyservice.service;

import com.demo.integrity.crypto.CanonicalEncoder;
import com.demo.integrity.dto.IssuanceInventory;
import com.demo.integrity.dto.IssuanceInventoryItem;
import com.demo.integrity.dto.SignedOperation;
import com.demo.integrity.model.Operation;
import com.demo.keyservice.exception.ConflictException;
import com.demo.keyservice.vault.LocalKeyVault;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.MessageDigest;
import java.util.*;

@Service
public final class IssuanceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final LocalKeyVault vault;
    private final TransactionTemplate transaction;
    public IssuanceService(JdbcTemplate jdbc, ObjectMapper mapper, LocalKeyVault vault, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.mapper = mapper; this.vault = vault; this.transaction = new TransactionTemplate(manager);
    }
    /** Serializes local issuance/idempotency decisions; receipt commits before returning the MAC. */
    public synchronized SignedOperation issue(Operation operation) {
        CanonicalEncoder.validate(operation);
        String businessHash = CanonicalEncoder.businessHash(operation);
        return transaction.execute(status -> {
            Optional<SignedOperation> prior = findByIdempotency(operation.ledgerId(), operation.idempotencyKey());
            if (prior.isPresent()) {
                if (!CanonicalEncoder.businessHash(prior.get().operation()).equals(businessHash))
                    throw new ConflictException("Idempotency key already used for different business parameters");
                Optional<SignedOperation> duplicateId = findById(operation.id());
                if (duplicateId.isPresent() && !duplicateId.get().operation().id().equals(prior.get().operation().id()))
                    throw new ConflictException("Operation id already used");
                return prior.get();
            }
            if (findById(operation.id()).isPresent()) throw new ConflictException("Operation id already used");
            String keyId = vault.currentKeyId();
            byte[] canonical = CanonicalEncoder.encode(operation, keyId);
            SignedOperation signed = new SignedOperation(operation, keyId, CanonicalEncoder.hashHex(canonical), vault.mac(canonical, keyId));
            jdbc.update("INSERT INTO issuance_receipts(id,ledger_id,idempotency_key,business_hash,signed_json,issued_at_micros) VALUES (?,?,?,?,?,?)",
                    operation.id(), operation.ledgerId(), operation.idempotencyKey(), businessHash, encode(signed), epochMicros());
            return signed;
        });
    }
    public boolean verify(SignedOperation signed) {
        try {
            byte[] canonical = CanonicalEncoder.encode(signed.operation(), signed.keyId());
            boolean hashMatches = MessageDigest.isEqual(HexFormat.of().parseHex(CanonicalEncoder.hashHex(canonical)), HexFormat.of().parseHex(signed.contentHash()));
            return hashMatches && vault.verify(canonical, signed.keyId(), signed.mac())
                    && findById(signed.operation().id()).filter(signed::equals).isPresent();
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
    public Optional<SignedOperation> findById(UUID id) {
        return jdbc.query("SELECT signed_json FROM issuance_receipts WHERE id=?", (rs, row) -> decode(rs.getString(1)), id).stream().findFirst();
    }
    public Optional<SignedOperation> findByIdempotency(UUID ledgerId, String key) {
        return jdbc.query("SELECT signed_json FROM issuance_receipts WHERE ledger_id=? AND idempotency_key=?", (rs, row) -> decode(rs.getString(1)), ledgerId, key).stream().findFirst();
    }
    public IssuanceInventory inventory(long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid inventory pagination");
        List<IssuanceInventoryItem> items = jdbc.query("SELECT sequence,signed_json FROM issuance_receipts WHERE sequence>? ORDER BY sequence LIMIT ?",
                (rs, row) -> new IssuanceInventoryItem(rs.getLong(1), decode(rs.getString(2))), afterSequence, limit);
        return new IssuanceInventory(items, items.isEmpty() ? afterSequence : items.getLast().sequence());
    }
    private String encode(SignedOperation signed) {
        try { return mapper.writeValueAsString(signed); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode issuance receipt", e); }
    }
    private SignedOperation decode(String json) {
        try { return mapper.readValue(json, SignedOperation.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted issuance receipt", e); }
    }
    private static long epochMicros() { java.time.Instant now = java.time.Instant.now(); return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000), now.getNano() / 1000); }
}
