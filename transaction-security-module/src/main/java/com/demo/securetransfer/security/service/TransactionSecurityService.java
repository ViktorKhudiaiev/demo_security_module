package com.demo.securetransfer.security.service;

import com.demo.securetransfer.security.domain.SecurityTransactionView;
import com.demo.securetransfer.security.messaging.TransactionEventBroker;
import com.demo.securetransfer.security.port.AccountSecurityPort;
import com.demo.securetransfer.security.port.LedgerSecurityPort;
import com.demo.securetransfer.security.port.TransactionSecurityPort;
import com.demo.securetransfer.tokenization.domain.TokenPayload;
import com.demo.securetransfer.tokenization.service.TokenizationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class TransactionSecurityService {
    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";
    private static final String FAILED = "FAILED";
    private static final String SUSPICIOUS = "SUSPICIOUS";

    private final TransactionEventBroker broker;
    private final TransactionSecurityPort transactionPort;
    private final AccountSecurityPort accountPort;
    private final LedgerSecurityPort ledgerPort;
    private final TokenizationService tokenizationService;

    public TransactionSecurityService(
            TransactionEventBroker broker,
            TransactionSecurityPort transactionPort,
            AccountSecurityPort accountPort,
            LedgerSecurityPort ledgerPort,
            TokenizationService tokenizationService
    ) {
        this.broker = broker;
        this.transactionPort = transactionPort;
        this.accountPort = accountPort;
        this.ledgerPort = ledgerPort;
        this.tokenizationService = tokenizationService;
    }

    @Scheduled(fixedDelayString = "${demo.security.poll-interval-ms}")
    public void consumePendingEvents() {
        broker.poll().ifPresent(event -> validateAndComplete(event.transactionId()));
    }

    @Transactional
    public void validateAndComplete(UUID transactionId) {
        SecurityTransactionView transaction = transactionPort.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        if (!PENDING.equals(transaction.status())) {
            return;
        }

        transactionPort.appendStatus(transaction.id(), PROCESSING, null);

        String expectedToken = tokenizationService.generateToken(new TokenPayload(
                transaction.id(),
                transaction.fromAccountId(),
                transaction.toAccountId(),
                transaction.amountCents(),
                transaction.createdAt()
        ));

        if (!expectedToken.equals(transaction.token())) {
            accountPort.block(transaction.fromAccountId());
            accountPort.block(transaction.toAccountId());
            transactionPort.appendStatus(transaction.id(), SUSPICIOUS, "Token mismatch");
            return;
        }

        long availableBalance = accountPort.balanceOf(transaction.fromAccountId());
        if (availableBalance < transaction.amountCents()) {
            transactionPort.appendStatus(transaction.id(), FAILED, "Insufficient funds during final validation");
            return;
        }

        ledgerPort.addDebit(
                transaction.fromAccountId(),
                transaction.id(),
                transaction.amountCents()
        );
        ledgerPort.addCredit(
                transaction.toAccountId(),
                transaction.id(),
                transaction.amountCents()
        );
        transactionPort.appendStatus(transaction.id(), COMPLETED, null);
    }
}
