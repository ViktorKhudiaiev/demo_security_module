package com.demo.transferapp;

import com.demo.integrity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferServiceTest {
    StubServices remote;
    JdbcTemplate db;
    TransferService service;
    ObjectMapper json = new ObjectMapper();
    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        remote = new StubServices();
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("primary-schema.sql")).execute(source);
        db = new JdbcTemplate(source);
        service = new TransferService(db, new DataSourceTransactionManager(source), new IntegrityClient(remote.url(), StubServices.WRITER),
                new ProcessorClient(remote.url(), StubServices.PROCESSOR), json, Clock.fixed(Instant.parse("2026-09-05T12:00:00.123456Z"), ZoneOffset.UTC));
    }
    @AfterEach void cleanup() { remote.close(); }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    SignedOperation signed() { return StubServices.signed(new Operation(UUID.randomUUID(), 1, TransferService.LEDGER, "TRANSFER", alice, bob, 123, "USD", 1700000000000001L, null, UUID.randomUUID().toString())); }

    @Test void publishingCommitsOperationAndOutboxTogether() {
        SignedOperation signed = signed(); service.publish(signed);
        assertEquals(1, count("transactions")); assertEquals(1, count("transaction_outbox"));
        assertEquals(signed.operation().id(), db.queryForObject("SELECT transaction_id FROM transaction_outbox", UUID.class));
        assertEquals("CREATED", db.queryForObject("SELECT event_type FROM transaction_outbox", String.class));
    }
    @Test void outboxFailureRollsBackTheInsertedOperation() {
        db.execute("ALTER TABLE transaction_outbox ADD CONSTRAINT reject_created CHECK(event_type <> 'CREATED')");
        assertThrows(RuntimeException.class, () -> service.publish(signed()));
        assertEquals(0, count("transactions")); assertEquals(0, count("transaction_outbox"));
    }
    @Test void retryDoesNotDuplicateAndRepairsADeletedOutboxHint() {
        SignedOperation signed = signed(); service.publish(signed); service.publish(signed);
        assertEquals(1, count("transactions")); assertEquals(1, count("transaction_outbox"));
        db.update("DELETE FROM transaction_outbox WHERE id=?", signed.operation().id()); service.publish(signed);
        assertEquals(1, count("transaction_outbox"));
    }
    @Test void tamperedPrimaryRecordIsNeverOverwrittenByLegitimateRetry() {
        SignedOperation signed = signed(); service.publish(signed);
        db.update("UPDATE transactions SET mac=? WHERE id=?", "11".repeat(32), signed.operation().id());
        assertThrows(TransferService.Conflict.class, () -> service.publish(signed));
        assertEquals("11".repeat(32), db.queryForObject("SELECT mac FROM transactions WHERE id=?", String.class, signed.operation().id()));
        assertEquals(1, count("transactions"));
    }
    @Test void malformedPrimaryJsonProducesConflictWithoutOverwrite() {
        SignedOperation signed = signed(); service.publish(signed);
        db.update("UPDATE transactions SET operation_json='not-json' WHERE id=?", signed.operation().id());
        assertThrows(TransferService.Conflict.class, () -> service.publish(signed));
        assertEquals("not-json", db.queryForObject("SELECT operation_json FROM transactions WHERE id=?", String.class, signed.operation().id()));
    }
    @Test void concurrentIdenticalRetriesPublishExactlyOneOperationAndOutbox() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
            CountDownLatch start = new CountDownLatch(1); List<Future<Map<String,Object>>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) calls.add(pool.submit(() -> { start.await(); return service.transfer(alice, bob, 250, "USD", "concurrent-retry"); }));
            start.countDown(); Set<Object> ids = new HashSet<>();
            for (Future<Map<String,Object>> call : calls) ids.add(call.get(20, TimeUnit.SECONDS).get("id"));
            assertEquals(1, ids.size()); assertEquals(1, count("transactions")); assertEquals(1, count("transaction_outbox"));
            assertEquals(1, remote.receipts.size());
        }
    }
    @Test void changedBusinessAmountOnRetryIsRejectedWithoutExtraPublication() {
        service.transfer(alice, bob, 250, "USD", "same-key");
        assertEquals(409, assertThrows(IntegrityClient.ServiceException.class, () -> service.transfer(alice, bob, 251, "USD", "same-key")).status());
        assertEquals(1, count("transactions")); assertEquals(1, count("transaction_outbox"));
    }
    @Test void unavailableKeyServiceFailsClosedBeforePrimaryWrites() {
        remote.keysUnavailable = true;
        assertThrows(IntegrityClient.ServiceException.class, () -> service.transfer(alice, bob, 1, "USD", "unavailable"));
        assertEquals(0, count("transactions")); assertEquals(0, count("transaction_outbox"));
    }
    @Test void unexpectedIssuerBusinessPayloadIsRejectedBeforePublication() {
        remote.unexpectedIssueResponse = true;
        assertThrows(IllegalStateException.class, () -> service.transfer(alice, bob, 1, "USD", "wrong-response"));
        assertEquals(0, count("transactions")); assertEquals(0, count("transaction_outbox"));
    }
    @Test void initialFundingUsesProtectedRegistrationAndSignedTreasuryOperation() {
        Map<String,Object> created = service.createAccount(null, "Alice", 500, "account-idempotency");
        UUID id = UUID.fromString(created.get("id").toString());
        assertEquals(0L, ((Number) remote.accounts.get(id).get("balanceCents")).longValue());
        SignedOperation funding = remote.receipts.get(created.get("fundingOperationId"));
        assertEquals("FUNDING", funding.operation().type()); assertEquals(TransferService.TREASURY, funding.operation().fromAccountId());
        assertEquals(id, funding.operation().toAccountId()); assertEquals(500, funding.operation().amountMinor());
        assertEquals(1788609600123456L, funding.operation().createdAtMicros());
        assertEquals(created.get("fundingOperationId"), service.createAccount(null, "Alice", 500, "account-idempotency").get("fundingOperationId"));
        assertEquals(1, count("accounts")); assertEquals(1, count("transactions"));
    }
    @Test void fundingOutageCanLeaveOnlyAZeroBalanceAccountAndRetryRecovers() {
        remote.keysUnavailable = true;
        assertThrows(IntegrityClient.ServiceException.class, () -> service.createAccount(alice, "Alice", 500, null));
        assertEquals(0L, remote.accounts.get(alice).get("balanceCents")); assertEquals(0, count("transactions"));
        remote.keysUnavailable = false; service.createAccount(alice, "Alice", 500, null);
        assertEquals(1, count("transactions")); assertEquals(1, count("accounts"));
    }
    @Test void protectedProcessorOutageCannotCreatePrimaryAccountOrPretendPending() {
        remote.processorUnavailable = true;
        assertThrows(ProcessorClient.RemoteException.class, () -> service.createAccount(alice, "Alice", 0, null));
        assertThrows(ProcessorClient.RemoteException.class, () -> service.operation(UUID.randomUUID()));
        assertEquals(0, count("accounts")); assertEquals(0, count("transactions"));
    }
    @Test void pendingFallbackRequiresAnIndependentReceiptAndNeverInventsPostings() {
        UUID id = (UUID) service.transfer(alice, bob, 1, "USD", "pending").get("id");
        Map<String,Object> pending = service.operation(id);
        assertEquals("PENDING", pending.get("status")); assertEquals(List.of(), pending.get("postings"));
        assertThrows(NoSuchElementException.class, () -> service.operation(UUID.randomUUID()));
    }
    @Test void reversalCopiesOriginalTrustedPayloadAndBindsRelation() {
        UUID original = (UUID) service.transfer(alice, bob, 42, "USD", "original").get("id");
        remote.outcomes.put(original, Map.of("id", original, "status", "COMPLETED"));
        Operation reversal = (Operation) service.reversal(original, "reverse").get("operation");
        assertEquals("REVERSAL", reversal.type()); assertEquals(bob, reversal.fromAccountId()); assertEquals(alice, reversal.toAccountId());
        assertEquals(42, reversal.amountMinor()); assertEquals(original, reversal.relatedOperationId());
    }
    @Test void correctionRequiresCompletedOriginalAndBindsReplacementToIt() {
        UUID original = (UUID) service.transfer(alice, bob, 42, "USD", "original").get("id");
        remote.outcomes.put(original, Map.of("id", original, "status", "PENDING"));
        assertThrows(TransferService.Conflict.class, () -> service.correction(original, alice, bob, 43, "USD", "correction"));
        remote.outcomes.put(original, Map.of("id", original, "status", "COMPLETED"));
        Operation correction = (Operation) service.correction(original, alice, bob, 43, "USD", "correction").get("operation");
        assertEquals("CORRECTION", correction.type()); assertEquals(original, correction.relatedOperationId()); assertEquals(43, correction.amountMinor());
    }
    @Test void invalidAmountsCurrencyKeysAndTreasuryTransfersNeverReachIssuer() {
        assertThrows(IllegalArgumentException.class, () -> service.transfer(alice, bob, 0, "USD", "invalid"));
        assertThrows(IllegalArgumentException.class, () -> service.transfer(alice, bob, 1, "EUR", "invalid"));
        assertThrows(IllegalArgumentException.class, () -> service.transfer(alice, bob, 1, "USD", "spaces forbidden"));
        assertThrows(IllegalArgumentException.class, () -> service.transfer(TransferService.TREASURY, bob, 1, "USD", "invalid"));
        assertThrows(IllegalArgumentException.class, () -> service.createAccount(TransferService.TREASURY, "Treasury", 1, "invalid"));
        assertTrue(remote.receipts.isEmpty()); assertEquals(0, count("transactions"));
    }
}
