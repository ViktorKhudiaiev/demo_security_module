package com.demo.securityapp.dto;

import java.util.UUID;

/** Internal account-registration request; balances are not supplied by the caller. */
public record RegisterAccountRequest(UUID id, String name) {
}
