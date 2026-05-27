package com.demo.securetransfer.adapter.security;

import com.demo.securetransfer.domain.LedgerEntryType;
import com.demo.securetransfer.domain.TransactionStatus;
import com.demo.securetransfer.repository.AccountRepository;
import com.demo.securetransfer.repository.LedgerRepository;
import com.demo.securetransfer.repository.TransactionRepository;
import com.demo.securetransfer.security.domain.SecurityTransactionView;
import com.demo.securetransfer.security.port.AccountSecurityPort;
import com.demo.securetransfer.security.port.LedgerSecurityPort;
import com.demo.securetransfer.security.port.TransactionSecurityPort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class SecurityPersistenceAdapter implements AccountSecurityPort, LedgerSecurityPort, TransactionSecurityPort {
    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final TransactionRepository transactionRepository;

    public SecurityPersistenceAdapter(
            AccountRepository accountRepository,
            LedgerRepository ledgerRepository,
            TransactionRepository transactionRepository
    ) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public long balanceOf(UUID accountId) {
        return accountRepository.balanceOf(accountId);
    }

    @Override
    public void block(UUID accountId) {
        accountRepository.block(accountId);
    }

    @Override
    public void addDebit(UUID accountId, UUID transactionId, long amountCents) {
        ledgerRepository.addEntry(accountId, transactionId, -amountCents, LedgerEntryType.TRANSFER_DEBIT);
    }

    @Override
    public void addCredit(UUID accountId, UUID transactionId, long amountCents) {
        ledgerRepository.addEntry(accountId, transactionId, amountCents, LedgerEntryType.TRANSFER_CREDIT);
    }

    @Override
    public Optional<SecurityTransactionView> findById(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .map(transaction -> new SecurityTransactionView(
                        transaction.id(),
                        transaction.fromAccountId(),
                        transaction.toAccountId(),
                        transaction.amountCents(),
                        transaction.status().name(),
                        transaction.token(),
                        transaction.createdAt()
                ));
    }

    @Override
    public void appendStatus(UUID transactionId, String status, String failureReason) {
        transactionRepository.appendStatus(transactionId, TransactionStatus.valueOf(status), failureReason);
    }
}
