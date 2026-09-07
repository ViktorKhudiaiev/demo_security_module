package com.demo.keyservice;

import com.demo.integrity.CanonicalEncoder;
import com.demo.integrity.Checkpoint;
import com.demo.integrity.CheckpointEncoder;
import com.demo.integrity.SignedCheckpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Local-only key custody. The OS account/host owner remains trusted; this is not an HSM. */
@Component
public final class LocalKeyVault {
    private final Path directory;
    private final ObjectMapper mapper;
    private final Map<String, byte[]> hmacKeys = new HashMap<>();
    private final Map<String, Optional<SignedCheckpoint>> checkpointCache = new HashMap<>();
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private final KeyPair signingKey;
    private final String signingKeyId;
    private String currentKeyId;

    public LocalKeyVault(@Value("${keyservice.directory}") String path, ObjectMapper mapper) throws Exception {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("KEY_DIRECTORY is required");
        this.mapper = mapper;
        directory = Path.of(path).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        restrict(directory);
        lockChannel = FileChannel.open(directory.resolve("vault.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        directoryLock = lockChannel.tryLock();
        if (directoryLock == null) throw new IllegalStateException("Key directory already in use");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "hmac-*.key")) {
            for (Path file : files) {
                byte[] secret = Files.readAllBytes(file);
                if (secret.length != 32) throw new IllegalStateException("Invalid persisted HMAC key");
                hmacKeys.put(file.getFileName().toString().replace(".key", ""), secret);
            }
        }
        Path pointer = directory.resolve("current-hmac");
        if (Files.exists(pointer)) {
            currentKeyId = Files.readString(pointer, StandardCharsets.UTF_8);
            if (!hmacKeys.containsKey(currentKeyId)) throw new IllegalStateException("Active HMAC key is missing");
        } else {
            if (!hmacKeys.isEmpty()) throw new IllegalStateException("HMAC key pointer missing; refusing silent replacement");
            if (Files.exists(directory.resolve("signing-key.json")) || Files.exists(directory.resolve("anchors")))
                throw new IllegalStateException("HMAC key material missing from an existing vault");
            rotate();
        }
        Path signingFile = directory.resolve("signing-key.json");
        if (Files.exists(signingFile)) {
            KeyFile saved = mapper.readValue(Files.readAllBytes(signingFile), KeyFile.class);
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            signingKey = new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(saved.publicKey()))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(saved.privateKey()))));
        } else {
            if (Files.exists(directory.resolve("anchors"))) throw new IllegalStateException("Signing key missing for existing anchors");
            signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            writeAtomic(signingFile, mapper.writeValueAsBytes(new KeyFile(Base64.getEncoder().encodeToString(signingKey.getPublic().getEncoded()),
                    Base64.getEncoder().encodeToString(signingKey.getPrivate().getEncoded()))));
        }
        signingKeyId = "ed25519-" + CanonicalEncoder.hashHex(signingKey.getPublic().getEncoded()).substring(0, 24);
        Files.createDirectories(directory.resolve("anchors"));
        restrict(directory.resolve("anchors"));
    }

    public synchronized String currentKeyId() { return currentKeyId; }
    public synchronized String rotate() {
        try {
            String id = "hmac-" + UUID.randomUUID();
            byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
            writeAtomic(directory.resolve(id + ".key"), secret);
            writeAtomic(directory.resolve("current-hmac"), id.getBytes(StandardCharsets.UTF_8));
            hmacKeys.put(id, secret); currentKeyId = id;
            return id;
        } catch (IOException e) { throw new IllegalStateException("Unable to persist HMAC key", e); }
    }
    public synchronized String mac(byte[] input, String keyId) {
        byte[] secret = hmacKeys.get(keyId);
        if (secret == null) throw new IllegalArgumentException("Unknown keyId");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public boolean verify(byte[] input, String keyId, String suppliedMac) {
        try { return MessageDigest.isEqual(HexFormat.of().parseHex(mac(input, keyId)), HexFormat.of().parseHex(suppliedMac)); }
        catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
    public Map<String, String> publicKey() {
        return Map.of("keyId", signingKeyId, "publicKey", Base64.getEncoder().encodeToString(signingKey.getPublic().getEncoded()));
    }
    public synchronized SignedCheckpoint sign(Checkpoint checkpoint) {
        byte[] encoded = CheckpointEncoder.encode(checkpoint, signingKeyId);
        Optional<SignedCheckpoint> previous = latest(checkpoint.logId());
        if (previous.isPresent()) {
            SignedCheckpoint p = previous.get();
            if (checkpoint.treeSize() < p.treeSize() || checkpoint.createdAtMicros() < p.createdAtMicros()
                    || (checkpoint.treeSize() == p.treeSize() && !checkpoint.rootHash().equals(p.rootHash())))
                throw new ConflictException("Checkpoint regression or conflicting root");
            if (checkpoint.equals(p.checkpoint())) return p;
        }
        try {
            Signature signer = Signature.getInstance("Ed25519"); signer.initSign(signingKey.getPrivate()); signer.update(encoded);
            SignedCheckpoint signed = new SignedCheckpoint(checkpoint.logId(), checkpoint.treeSize(), checkpoint.rootHash(), checkpoint.createdAtMicros(),
                    signingKeyId, Base64.getEncoder().encodeToString(signer.sign()), publicKey().get("publicKey"));
            Path logDirectory = anchorDirectory(checkpoint.logId()); Files.createDirectories(logDirectory); restrict(logDirectory);
            byte[] json = mapper.writeValueAsBytes(signed);
            Path history = logDirectory.resolve(checkpoint.treeSize() + "-" + checkpoint.createdAtMicros() + ".json");
            if (Files.exists(history) && !Arrays.equals(Files.readAllBytes(history), json)) throw new ConflictException("Anchor already exists with different content");
            if (!Files.exists(history)) writeAtomic(history, json);
            writeAtomic(logDirectory.resolve("latest.json"), json);
            checkpointCache.put(checkpoint.logId(), Optional.of(signed));
            return signed;
        } catch (GeneralSecurityException | IOException e) { throw new IllegalStateException("Unable to persist signed anchor", e); }
    }
    public synchronized Optional<SignedCheckpoint> latest(String logId) {
        if (logId == null || !logId.matches("[A-Za-z0-9._:-]{1,128}") || logId.equals(".") || logId.equals(".."))
            throw new IllegalArgumentException("Invalid logId");
        if (checkpointCache.containsKey(logId)) return checkpointCache.get(logId);
        Path logDirectory = anchorDirectory(logId);
        if (!Files.isDirectory(logDirectory)) return Optional.empty();
        // Scan immutable history, not only the replaceable latest pointer, for crash recovery.
        SignedCheckpoint newest = null;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(logDirectory, "*.json")) {
            for (Path file : files) {
                SignedCheckpoint candidate = mapper.readValue(Files.readAllBytes(file), SignedCheckpoint.class);
                if (!candidate.logId().equals(logId) || !CheckpointEncoder.verify(candidate, publicKey().get("publicKey")))
                    throw new IllegalStateException("Invalid persisted checkpoint anchor");
                if (newest == null || candidate.treeSize() > newest.treeSize()
                        || (candidate.treeSize() == newest.treeSize() && candidate.createdAtMicros() > newest.createdAtMicros())) newest = candidate;
            }
        } catch (IOException e) { throw new IllegalStateException("Unable to read persisted checkpoint anchor", e); }
        Optional<SignedCheckpoint> result = Optional.ofNullable(newest);
        checkpointCache.put(logId, result);
        return result;
    }
    private Path anchorDirectory(String logId) { return directory.resolve("anchors").resolve(CanonicalEncoder.hashHex(logId.getBytes(StandardCharsets.UTF_8))); }
    private void writeAtomic(Path target, byte[] data) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".key-write-", ".tmp");
        restrict(temp);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void restrict(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(path, Files.isDirectory(path) ? PosixFilePermissions.fromString("rwx------") : PosixFilePermissions.fromString("rw-------"));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl != null) {
                AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class));
                if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
                acl.setAcl(List.of(entry.build()));
            }
        }
    }
    @PreDestroy public void close() throws IOException { directoryLock.release(); lockChannel.close(); }
    private record KeyFile(String publicKey, String privateKey) {}
}
