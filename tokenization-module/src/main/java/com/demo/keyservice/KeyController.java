package com.demo.keyservice;

import com.demo.integrity.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public final class KeyController {
    private final IssuanceService issuance;
    private final LocalKeyVault vault;
    private final JdbcTemplate jdbc;
    public KeyController(IssuanceService issuance, LocalKeyVault vault, JdbcTemplate jdbc) { this.issuance = issuance; this.vault = vault; this.jdbc = jdbc; }
    @PostMapping("/v1/mac/issue") public SignedOperation issue(@RequestBody Operation operation) { return issuance.issue(operation); }
    @PostMapping("/v1/mac/verify") public Map<String, Boolean> verify(@RequestBody SignedOperation signed) { return Map.of("valid", issuance.verify(signed)); }
    @GetMapping("/v1/issuances/{id}") public ResponseEntity<SignedOperation> byId(@PathVariable UUID id) { return ResponseEntity.of(issuance.findById(id)); }
    @GetMapping("/v1/issuances") public ResponseEntity<SignedOperation> byIdempotency(@RequestParam UUID ledgerId, @RequestParam String idempotencyKey) { return ResponseEntity.of(issuance.findByIdempotency(ledgerId, idempotencyKey)); }
    @GetMapping("/v1/issuances/inventory") public IssuanceService.Inventory inventory(@RequestParam(defaultValue = "0") long afterSequence, @RequestParam(defaultValue = "1000") int limit) { return issuance.inventory(afterSequence, limit); }
    @PostMapping("/v1/keys/rotate") public Map<String, String> rotate() { return Map.of("keyId", vault.rotate()); }
    @PostMapping("/v1/checkpoints") public SignedCheckpoint checkpoint(@RequestBody Checkpoint checkpoint) { return vault.sign(checkpoint); }
    @GetMapping("/v1/checkpoints/public-key") public Map<String, String> publicKey() { return vault.publicKey(); }
    @GetMapping("/v1/checkpoints/latest") public ResponseEntity<SignedCheckpoint> latest(@RequestParam(defaultValue = "security-audit") String logId) { return ResponseEntity.of(vault.latest(logId)); }
    @GetMapping({"/health", "/actuator/health"}) public Map<String, String> health() { jdbc.queryForObject("SELECT 1", Integer.class); return Map.of("status", "UP"); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) { return Map.of("error", error.getMessage()); }
    @ExceptionHandler(ConflictException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(ConflictException error) { return Map.of("error", error.getMessage()); }
}
