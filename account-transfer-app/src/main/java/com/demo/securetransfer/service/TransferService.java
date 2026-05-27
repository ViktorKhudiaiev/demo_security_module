package com.demo.securetransfer.service;

import com.demo.securetransfer.domain.AccountStatus;
import com.demo.securetransfer.domain.Transaction;
import com.demo.securetransfer.domain.TransactionStatus;
import com.demo.securetransfer.dto.CreateTransferRequest;
import com.demo.securetransfer.dto.TransactionResponse;
import com.demo.securetransfer.repository.AccountRepository;
import com.demo.securetransfer.repository.TransactionRepository;
import com.demo.securetransfer.tokenization.domain.TokenPayload;
import com.demo.securetransfer.tokenization.service.TokenizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService {
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TokenizationService tokenizationService;

    public TransferService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TokenizationService tokenizationService
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.tokenizationService = tokenizationService;
    }

    @Transactional
    public TransactionResponse createTransfer(CreateTransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new IllegalArgumentException("Cannot transfer money to the same account");
        }

        requireActive(request.fromAccountId());
        requireActive(request.toAccountId());

        long availableBalance = accountRepository.balanceOf(request.fromAccountId());
        if (availableBalance < request.amountCents()) {
            throw new IllegalArgumentException("Insufficient funds. Available balance: " + availableBalance);
        }

        UUID transactionId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        TokenPayload payload = new TokenPayload(
                transactionId,
                request.fromAccountId(),
                request.toAccountId(),
                request.amountCents(),
                now
        );

        Transaction transaction = new Transaction(
                transactionId,
                request.fromAccountId(),
                request.toAccountId(),
                request.amountCents(),
                TransactionStatus.PENDING,
                tokenizationService.generateToken(payload),
                now,
                now,
                null
        );

        transactionRepository.create(transaction);
        return toResponse(transaction);
    }

    public List<TransactionResponse> findAll() {
        return transactionRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public TransactionResponse get(UUID id) {
        return toResponse(transactionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + id)));
    }

    private void requireActive(UUID accountId) {
        AccountStatus status = accountRepository.requireStatus(accountId);
        if (status != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Account is not active: " + accountId);
        }
    }

    private TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.fromAccountId(),
                transaction.toAccountId(),
                transaction.amountCents(),
                transaction.status(),
                transaction.token(),
                transaction.createdAt(),
                transaction.updatedAt(),
                transaction.failureReason()
        );
    }
}
