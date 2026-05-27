package com.demo.securetransfer.service;

import com.demo.securetransfer.domain.Account;
import com.demo.securetransfer.domain.AccountStatus;
import com.demo.securetransfer.domain.LedgerEntryType;
import com.demo.securetransfer.dto.AccountResponse;
import com.demo.securetransfer.dto.CreateAccountRequest;
import com.demo.securetransfer.repository.AccountRepository;
import com.demo.securetransfer.repository.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;

    public AccountService(AccountRepository accountRepository, LedgerRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        UUID accountId = UUID.randomUUID();
        accountRepository.create(accountId, request.name(), AccountStatus.ACTIVE, Instant.now());

        if (request.initialBalanceCents() > 0) {
            ledgerRepository.addEntry(accountId, null, request.initialBalanceCents(), LedgerEntryType.INITIAL_CREDIT);
        }

        return toResponse(accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Created account is not readable")));
    }

    public List<AccountResponse> findAll() {
        return accountRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public AccountResponse get(UUID id) {
        return toResponse(accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id)));
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.id(),
                account.name(),
                account.status(),
                account.balanceCents(),
                account.createdAt()
        );
    }
}
