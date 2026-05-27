package com.demo.securetransfer.security.port;

import com.demo.securetransfer.security.domain.SecurityTransactionView;

import java.util.Optional;
import java.util.UUID;

public interface TransactionSecurityPort {
    Optional<SecurityTransactionView> findById(UUID transactionId);

    void appendStatus(UUID transactionId, String status, String failureReason);
}
