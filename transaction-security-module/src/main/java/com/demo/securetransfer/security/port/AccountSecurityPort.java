package com.demo.securetransfer.security.port;

import java.util.UUID;

public interface AccountSecurityPort {
    long balanceOf(UUID accountId);

    void block(UUID accountId);
}
