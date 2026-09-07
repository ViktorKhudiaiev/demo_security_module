package com.demo.securityapp.dto;

/** Sibling hash and its position in a serialized Merkle inclusion proof. */
public record MerkleProofStep(boolean left, String hash) {
}
