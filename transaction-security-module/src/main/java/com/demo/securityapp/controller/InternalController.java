package com.demo.securityapp.controller;

import com.demo.securityapp.audit.AuditLog;
import com.demo.securityapp.domain.Account;
import com.demo.securityapp.dto.AccountHoldRequest;
import com.demo.securityapp.dto.FaultRequest;
import com.demo.securityapp.dto.RegisterAccountRequest;
import com.demo.securityapp.service.Processor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Internal HTTP boundary; processing and audit behavior remain in their services. */
@RestController
public class InternalController {
    private final Processor processor;
    private final AuditLog audit;

    public InternalController(Processor processor, AuditLog audit) {
        this.processor = processor;
        this.audit = audit;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        boolean auditHealthy = audit.healthy();
        Map<String, Object> queue = processor.deliveryHealth();
        String status = !"UP".equals(queue.get("status"))
                ? "WAITING_FOR_QUEUE"
                : auditHealthy ? "UP" : "WAITING_FOR_AUDIT";
        return Map.of("service", "integrity-processor", "status", status,
                "auditHealthy", auditHealthy, "queue", queue);
    }

    @PostMapping("/internal/accounts")
    ResponseEntity<Account> register(@RequestBody RegisterAccountRequest request) {
        return ResponseEntity.status(201).body(processor.register(request.id(), request.name()));
    }

    @GetMapping("/internal/accounts/{id}")
    Account account(@PathVariable UUID id) {
        return processor.account(id);
    }

    @PostMapping("/internal/accounts/{id}/hold")
    Account hold(@PathVariable UUID id, @RequestBody AccountHoldRequest request) {
        if (request.held() == null) {
            throw new IllegalArgumentException("held boolean is required");
        }
        return processor.hold(id, request.held());
    }

    @GetMapping("/internal/operations/{id}")
    Map<String, Object> operation(@PathVariable UUID id) {
        return processor.operation(id);
    }

    @GetMapping("/internal/audit")
    List<Map<String, Object>> audit(@RequestParam(required = false) UUID operationId) {
        return audit.events(operationId);
    }

    @GetMapping("/internal/audit/proof/{index}")
    Map<String, Object> proof(@PathVariable int index) {
        return audit.proof(index);
    }

    @PostMapping("/internal/test/faults")
    Map<String, Object> fault(@RequestBody FaultRequest fault) {
        processor.configureFault(fault);
        return Map.of("configured", true);
    }

    @GetMapping("/internal/test/faults/{id}")
    Map<String, Object> faultState(@PathVariable UUID id) {
        return processor.faultState(id);
    }

    @PostMapping("/internal/test/checkpoint")
    Map<String, Object> checkpoint() {
        processor.faultState(UUID.randomUUID());
        audit.checkpoint();
        return Map.of("healthy", audit.healthy());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> missing(NoSuchElementException error) {
        return ResponseEntity.status(404).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> malformed(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed request"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> failure(Exception error) {
        return ResponseEntity.status(503)
                .body(Map.of("error", "Dependency unavailable; no unverified operation is authorized"));
    }
}
