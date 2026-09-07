package com.demo.securityapp.dto;

/** Explicit operator instruction; a missing flag is distinct from false. */
public record AccountHoldRequest(Boolean held) {
}
