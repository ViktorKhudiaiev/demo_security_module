package com.demo.securetransfer.security.port;

import java.util.UUID;

public interface LedgerSecurityPort {
    void addDebit(UUID accountId, UUID transactionId, long amountCents);

    void addCredit(UUID accountId, UUID transactionId, long amountCents);
}
