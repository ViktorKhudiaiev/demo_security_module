package com.demo.securityapp.dto;

import com.demo.securityapp.domain.Account;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Package organization must not change the internal HTTP protocol. */
class DtoContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void registrationAndAccountKeepTheirExistingJsonFields() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000101");
        String request = "{\"id\":\"" + id + "\",\"name\":\"Alice\"}";
        assertThat(json.readValue(request, RegisterAccountRequest.class))
                .isEqualTo(new RegisterAccountRequest(id, "Alice"));

        Account account = new Account(id, "Alice", "ACTIVE", 1200);
        assertThat(json.readTree(json.writeValueAsString(account)))
                .isEqualTo(json.readTree("{\"id\":\"" + id
                        + "\",\"name\":\"Alice\",\"status\":\"ACTIVE\",\"balanceCents\":1200}"));
    }

    @Test
    void missingHoldFlagRemainsDistinctFromAnExplicitFalse() throws Exception {
        assertThat(json.readValue("{}", AccountHoldRequest.class).held()).isNull();
        assertThat(json.readValue("{\"held\":false}", AccountHoldRequest.class).held()).isFalse();
        assertThat(json.readValue("{\"held\":true}", AccountHoldRequest.class).held()).isTrue();
    }

    @Test
    void faultAndProofRecordsKeepTheirExistingJsonFields() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000102");
        FaultRequest fault = new FaultRequest(id, 400, true);
        assertThat(json.readTree(json.writeValueAsString(fault)))
                .isEqualTo(json.readTree("{\"operationId\":\"" + id
                        + "\",\"pauseAfterVerifyMillis\":400,\"failAfterDebit\":true}"));
        assertThat(json.readTree(json.writeValueAsString(new MerkleProofStep(true, "abcdef"))))
                .isEqualTo(json.readTree("{\"left\":true,\"hash\":\"abcdef\"}"));
    }
}
