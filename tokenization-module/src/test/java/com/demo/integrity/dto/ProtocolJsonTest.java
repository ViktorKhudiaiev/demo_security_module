package com.demo.integrity.dto;

import com.demo.integrity.model.Checkpoint;
import com.demo.integrity.model.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Package moves must not change persisted receipts or HTTP wire contracts. */
class ProtocolJsonTest {
    private final ObjectMapper json = new ObjectMapper();

    private static final String OPERATION = """
            {"id":"11111111-1111-1111-1111-111111111111","schemaVersion":1,
             "ledgerId":"22222222-2222-2222-2222-222222222222","type":"TRANSFER",
             "fromAccountId":"33333333-3333-3333-3333-333333333333",
             "toAccountId":"44444444-4444-4444-4444-444444444444",
             "amountMinor":12345,"currency":"USD","createdAtMicros":1700000000000001,
             "relatedOperationId":null,"idempotencyKey":"transfer-001"}
            """;

    @Test
    void operationAndReceiptKeepOriginalFieldNamesAndNullRelation() throws Exception {
        Operation operation = json.readValue(OPERATION, Operation.class);
        assertWireShape(OPERATION, operation);
        String receipt = "{\"operation\":" + OPERATION
                + ",\"keyId\":\"test-key\",\"contentHash\":\"test-hash\",\"mac\":\"test-mac\"}";
        SignedOperation signed = json.readValue(receipt, SignedOperation.class);
        assertEquals(new SignedOperation(operation, "test-key", "test-hash", "test-mac"), signed);
        assertWireShape(receipt, signed);
    }

    @Test
    void checkpointEnvelopeStaysFlatWithoutAnExtraCheckpointProperty() throws Exception {
        String wire = """
                {"logId":"test-log","treeSize":3,"rootHash":"test-root",
                 "createdAtMicros":1700000000000001,"keyId":"test-key",
                 "signature":"test-signature","publicKey":"test-public-key"}
                """;
        SignedCheckpoint signed = json.readValue(wire, SignedCheckpoint.class);
        assertEquals(new Checkpoint("test-log", 3, "test-root", 1700000000000001L), signed.checkpoint());
        assertWireShape(wire, signed);
    }

    @Test
    void sharedInventoryAndResponseDtosKeepExistingJsonShapes() throws Exception {
        String receipt = "{\"operation\":" + OPERATION
                + ",\"keyId\":\"test-key\",\"contentHash\":\"test-hash\",\"mac\":\"test-mac\"}";
        String wire = "{\"items\":[{\"sequence\":7,\"signedOperation\":" + receipt
                + "}],\"nextSequence\":7}";
        SignedOperation signed = json.readValue(receipt, SignedOperation.class);
        IssuanceInventory inventory = new IssuanceInventory(List.of(new IssuanceInventoryItem(7, signed)), 7);
        assertEquals(inventory, json.readValue(wire, IssuanceInventory.class));
        assertWireShape(wire, inventory);
        assertWireShape("{\"valid\":true}", new VerificationResponse(true));
        assertWireShape("{\"keyId\":\"test-key\",\"publicKey\":\"test-public-key\"}",
                new PublicKeyInfo("test-key", "test-public-key"));
    }

    private void assertWireShape(String expected, Object value) throws Exception {
        // Compare actual JSON, not Jackson's in-memory IntegerNode/LongNode choices.
        assertEquals(json.readTree(expected), json.readTree(json.writeValueAsString(value)));
    }
}
