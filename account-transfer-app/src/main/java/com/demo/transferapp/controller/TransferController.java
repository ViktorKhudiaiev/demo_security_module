package com.demo.transferapp.controller;

import com.demo.integrity.client.IntegrityClient;
import com.demo.transferapp.client.ProcessorClient;
import com.demo.transferapp.dto.AccountRequest;
import com.demo.transferapp.dto.FundingRequest;
import com.demo.transferapp.dto.TransferRequest;
import com.demo.transferapp.service.TransferService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
public class TransferController {
    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("service", "trusted-transfer-application", "status", "UP", "realPayments", false);
    }

    @PostMapping("/api/accounts")
    ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody AccountRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ResponseEntity.status(201).body(service.createAccount(
                request.id(), request.name(), request.initialBalanceCents(), key));
    }

    @GetMapping("/api/accounts/{id}")
    Map<String, Object> account(@PathVariable UUID id) {
        return service.account(id);
    }

    @PostMapping("/api/transfers")
    ResponseEntity<Map<String, Object>> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(service.transfer(
                request.fromAccountId(), request.toAccountId(), request.amountCents(),
                request.currency(), key));
    }

    @PostMapping("/api/fundings")
    ResponseEntity<Map<String, Object>> funding(
            @Valid @RequestBody FundingRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(service.funding(
                request.toAccountId(), request.amountCents(), request.currency(), key));
    }

    @PostMapping("/api/transfers/{id}/reversal")
    ResponseEntity<Map<String, Object>> reversal(
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(service.reversal(id, key));
    }

    @PostMapping("/api/transfers/{id}/correction")
    ResponseEntity<Map<String, Object>> correction(
            @PathVariable UUID id,
            @Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(service.correction(
                id, request.fromAccountId(), request.toAccountId(), request.amountCents(),
                request.currency(), key));
    }

    @GetMapping({"/api/operations/{id}", "/api/transfers/{id}"})
    Map<String, Object> operation(@PathVariable UUID id) {
        return service.operation(id);
    }

    @ExceptionHandler(TransferService.Conflict.class)
    ResponseEntity<Map<String, String>> conflict(TransferService.Conflict error) {
        return ResponseEntity.status(409).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> missing(NoSuchElementException error) {
        return ResponseEntity.status(404).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<Map<String, String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid request: check fields, integer amount, currency, and idempotency key"));
    }

    @ExceptionHandler(IntegrityClient.ServiceException.class)
    ResponseEntity<Map<String, String>> keyFailure(IntegrityClient.ServiceException error) {
        int status = error.status() == 409 ? 409 : error.status() == 400 ? 400 : 503;
        String message = status == 409
                ? "Idempotency key conflicts with an existing operation"
                : "Integrity service rejected or could not authorize the request";
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    @ExceptionHandler(ProcessorClient.RemoteException.class)
    ResponseEntity<Map<String, String>> processorFailure(ProcessorClient.RemoteException error) {
        int status = Set.of(400, 404, 409).contains(error.status()) ? error.status() : 503;
        return ResponseEntity.status(status).body(Map.of(
                "error", "Protected processor rejected the request or is unavailable"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unavailable(Exception error) {
        return ResponseEntity.status(503).body(Map.of(
                "error", "Dependency unavailable. Retry with the SAME Idempotency-Key; do not create a new payment."));
    }
}
