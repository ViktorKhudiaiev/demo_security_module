package com.demo.securetransfer.controller;

import com.demo.securetransfer.dto.CreateTransferRequest;
import com.demo.securetransfer.dto.TransactionResponse;
import com.demo.securetransfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public TransactionResponse create(@Valid @RequestBody CreateTransferRequest request) {
        return transferService.createTransfer(request);
    }

    @GetMapping
    public List<TransactionResponse> findAll() {
        return transferService.findAll();
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return transferService.get(id);
    }
}
