package com.demo.securityapp;

import com.demo.integrity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessorTest {
    JdbcTemplate primary,settlement;
    Processor processor;
    KeyGateway keys;
    AuditLog audit;
    InMemoryDelivery delivery = new InMemoryDelivery();
    ObjectMapper json=new ObjectMapper();
    UUID alice=UUID.randomUUID(),bob=UUID.randomUUID(),charlie=UUID.randomUUID();
    UUID ledger=UUID.fromString("00000000-0000-0000-0000-000000000010");
    @BeforeEach void setup() {
        primary=db();settlement=db();
        new ResourceDatabasePopulator(new ClassPathResource("settlement-schema.sql")).execute(settlement.getDataSource());
        primary.execute("CREATE TABLE transactions(id UUID PRIMARY KEY,operation_json TEXT,key_id VARCHAR,content_hash VARCHAR,mac VARCHAR,created_at_micros BIGINT)");
        primary.execute("CREATE TABLE transaction_outbox(id UUID PRIMARY KEY,transaction_id UUID,event_type VARCHAR,created_at_micros BIGINT,processed_at_micros BIGINT)");
        primary.execute("CREATE TABLE transaction_status_events(id UUID PRIMARY KEY,transaction_id UUID,status VARCHAR,reason VARCHAR,created_at_micros BIGINT)");
        keys=mock(KeyGateway.class);audit=mock(AuditLog.class);when(audit.healthy()).thenReturn(true);
        processor=new Processor(primary,settlement,keys,audit,json,new MockEnvironment().withProperty("processor.test-faults-enabled","true"),delivery);
        processor.register(alice,"Alice");processor.register(bob,"Bob");processor.register(charlie,"Charlie");
        settleFunding(alice,1000);
    }
    JdbcTemplate db(){return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa",""));}
    @AfterEach void stopWorkers(){processor.closeWorkers();}
    SignedOperation operation(String type,UUID from,UUID to,long amount,UUID related){
        Operation op=new Operation(UUID.randomUUID(),1,ledger,type,from,to,amount,"USD",Processor.now(),related,UUID.randomUUID().toString());
        return new SignedOperation(op,"key-v1",CanonicalEncoder.hashHex(op,"key-v1"),"00".repeat(32));
    }
    void publish(SignedOperation signed)throws Exception{
        Operation op=signed.operation();
        primary.update("INSERT INTO transactions VALUES(?,?,?,?,?,?)",op.id(),json.writeValueAsString(op),signed.keyId(),signed.contentHash(),signed.mac(),op.createdAtMicros());
        when(keys.receipt(op.id())).thenReturn(Optional.of(signed));when(keys.verify(signed)).thenReturn(true);
        processor.enqueue(op.id());
    }
    void settleFunding(UUID to,long amount){SignedOperation s=operation("FUNDING",Processor.TREASURY,to,amount,null);processor.enqueue(s.operation().id());processor.settle(s,false);}
    String status(UUID id){return processor.operation(id).get("status").toString();}
    long legs(UUID id){return settlement.queryForObject("SELECT COUNT(*) FROM ledger_postings WHERE operation_id=?",Long.class,id);}

    @Test void authenticTransferIsAtomicAndReplayHasNoExtraPostings()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,250,null);publish(s);processor.process(s.operation().id());
        assertThat(status(s.operation().id())).isEqualTo("COMPLETED");assertThat(processor.account(alice).balanceCents()).isEqualTo(750);
        assertThat(processor.account(bob).balanceCents()).isEqualTo(250);assertThat(legs(s.operation().id())).isEqualTo(2);
        processor.process(s.operation().id());processor.settle(s,false);
        assertThat(legs(s.operation().id())).isEqualTo(2);assertThat(processor.account(bob).balanceCents()).isEqualTo(250);
        assertThat(settlement.queryForObject("SELECT SUM(amount_cents) FROM ledger_postings WHERE operation_id=?",Long.class,s.operation().id())).isZero();
    }
    @Test void forgedRowWithoutReceiptCannotPost()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,100,null);publish(s);when(keys.receipt(s.operation().id())).thenReturn(Optional.empty());
        processor.process(s.operation().id());assertThat(status(s.operation().id())).isEqualTo("QUARANTINED");assertThat(legs(s.operation().id())).isZero();
        assertThat(processor.account(bob).status()).isEqualTo("ACTIVE");
    }
    @Test void alteredAmountIsQuarantined()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,100,null);publish(s);
        String raw=json.writeValueAsString(s.operation()).replace("\"amountMinor\":100","\"amountMinor\":900");
        primary.update("UPDATE transactions SET operation_json=? WHERE id=?",raw,s.operation().id());
        processor.process(s.operation().id());assertThat(status(s.operation().id())).isEqualTo("QUARANTINED");assertThat(legs(s.operation().id())).isZero();
    }
    @Test void unknownJsonFieldsAndDuplicateFieldsAreRejected()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,100,null);publish(s);
        String raw=json.writeValueAsString(s.operation());
        primary.update("UPDATE transactions SET operation_json=? WHERE id=?",raw.substring(0,raw.length()-1)+",\"extra\":true}",s.operation().id());
        assertThatThrownBy(()->processor.source(s.operation().id())).isInstanceOf(Processor.InvalidRecord.class);
        primary.update("UPDATE transactions SET operation_json=? WHERE id=?",raw.substring(0,raw.length()-1)+",\"amountMinor\":100}",s.operation().id());
        assertThatThrownBy(()->processor.source(s.operation().id())).isInstanceOf(Processor.InvalidRecord.class);
    }
    @Test void floatingPointAndStringIntegersCannotCanonicalizeAsAuthenticInteger()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,100,null);publish(s);String raw=json.writeValueAsString(s.operation());
        for(String value:List.of("100.0","100.1","\"100\"")){
            primary.update("UPDATE transactions SET operation_json=? WHERE id=?",raw.replace("\"amountMinor\":100","\"amountMinor\":"+value),s.operation().id());
            assertThatThrownBy(()->processor.source(s.operation().id())).isInstanceOf(Processor.InvalidRecord.class);
        }
    }
    @Test void accountHoldAndRegistrationHaveDurableAuditDespiteAuditOutage(){
        UUID id=UUID.randomUUID();doThrow(new IllegalStateException("audit offline")).when(audit).append(any(),any(),any(),any());
        processor.register(id,"Outage account");processor.hold(id,true);
        assertThat(processor.account(id).status()).isEqualTo("HELD");
        assertThat(settlement.queryForObject("SELECT COUNT(*) FROM account_audit_outbox WHERE account_id=? AND relayed_at_micros IS NULL",Long.class,id)).isEqualTo(2);
        assertThatThrownBy(processor::relay).isInstanceOf(IllegalStateException.class);
        doNothing().when(audit).append(any(),any(),any(),any());processor.relay();
        assertThat(settlement.queryForObject("SELECT COUNT(*) FROM account_audit_outbox WHERE account_id=? AND relayed_at_micros IS NULL",Long.class,id)).isZero();
    }
    @Test void debitFailureRollsBackEveryFinancialWriteAndRetryCompletes()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,200,null);publish(s);
        processor.configureFault(new Processor.Fault(s.operation().id(),0,true));processor.process(s.operation().id());
        assertThat(legs(s.operation().id())).isZero();assertThat(processor.account(alice).balanceCents()).isEqualTo(1000);assertThat(processor.account(bob).balanceCents()).isZero();
        settlement.update("UPDATE operation_jobs SET next_attempt_micros=0 WHERE operation_id=?",s.operation().id());processor.process(s.operation().id());
        assertThat(status(s.operation().id())).isEqualTo("COMPLETED");assertThat(legs(s.operation().id())).isEqualTo(2);
    }
    @Test void keyOutageDefersWithoutQuarantineOrPostings()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,200,null);publish(s);when(keys.receipt(s.operation().id())).thenThrow(new IllegalStateException("offline"));
        processor.process(s.operation().id());assertThat(status(s.operation().id())).isEqualTo("PENDING");assertThat(legs(s.operation().id())).isZero();
        assertThat(settlement.queryForObject("SELECT status FROM operation_jobs WHERE operation_id=?",String.class,s.operation().id())).isEqualTo("RETRY");
    }
    @Test void auditOutageBlocksProcessing()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,100,null);publish(s);when(audit.healthy()).thenReturn(false);
        processor.process(s.operation().id());assertThat(legs(s.operation().id())).isZero();assertThat(status(s.operation().id())).isEqualTo("PENDING");
    }
    @Test void accountHoldRejectsWithoutAutomaticallyHoldingOthers()throws Exception{
        processor.hold(bob,true);SignedOperation s=operation("TRANSFER",alice,bob,100,null);publish(s);processor.process(s.operation().id());
        assertThat(status(s.operation().id())).isEqualTo("REJECTED");assertThat(legs(s.operation().id())).isZero();assertThat(processor.account(alice).status()).isEqualTo("ACTIVE");
    }
    @Test void concurrentSpendingCannotOverdraw()throws Exception{
        SignedOperation one=operation("TRANSFER",alice,bob,700,null),two=operation("TRANSFER",alice,charlie,700,null);publish(one);publish(two);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)){
            Future<?> a=pool.submit(()->processor.process(one.operation().id()));Future<?> b=pool.submit(()->processor.process(two.operation().id()));a.get();b.get();
        }
        assertThat(List.of(status(one.operation().id()),status(two.operation().id()))).containsExactlyInAnyOrder("COMPLETED","REJECTED");
        assertThat(processor.account(alice).balanceCents()).isEqualTo(300);assertThat(processor.account(bob).balanceCents()+processor.account(charlie).balanceCents()).isEqualTo(700);
    }
    @Test void exactReversalOnceAndCorrectionRequireCompletedReversal()throws Exception{
        SignedOperation original=operation("TRANSFER",alice,bob,200,null);publish(original);processor.process(original.operation().id());
        SignedOperation tooEarly=operation("CORRECTION",alice,charlie,150,original.operation().id());publish(tooEarly);processor.process(tooEarly.operation().id());assertThat(status(tooEarly.operation().id())).isEqualTo("REJECTED");
        SignedOperation bad=operation("REVERSAL",bob,alice,199,original.operation().id());publish(bad);processor.process(bad.operation().id());assertThat(status(bad.operation().id())).isEqualTo("REJECTED");
        SignedOperation reverse=operation("REVERSAL",bob,alice,200,original.operation().id());publish(reverse);processor.process(reverse.operation().id());assertThat(status(reverse.operation().id())).isEqualTo("COMPLETED");
        SignedOperation duplicate=operation("REVERSAL",bob,alice,200,original.operation().id());publish(duplicate);processor.process(duplicate.operation().id());assertThat(status(duplicate.operation().id())).isEqualTo("REJECTED");
        SignedOperation correction=operation("CORRECTION",alice,charlie,150,original.operation().id());publish(correction);processor.process(correction.operation().id());assertThat(status(correction.operation().id())).isEqualTo("COMPLETED");
        assertThat(processor.account(alice).balanceCents()).isEqualTo(850);assertThat(processor.account(bob).balanceCents()).isZero();assertThat(processor.account(charlie).balanceCents()).isEqualTo(150);
    }
    @Test void changedAfterVerificationIsNotProcessed()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,100,null);publish(s);processor.configureFault(new Processor.Fault(s.operation().id(),400,false));
        try(ExecutorService executor=Executors.newSingleThreadExecutor()){
            Future<?> task=executor.submit(()->processor.process(s.operation().id()));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!(boolean)processor.faultState(s.operation().id()).get("paused")&&System.nanoTime()<deadline)Thread.sleep(5);
            assertThat(processor.faultState(s.operation().id()).get("paused")).isEqualTo(true);
            primary.update("UPDATE transactions SET mac=? WHERE id=?","ff".repeat(32),s.operation().id());task.get();
        }
        assertThat(status(s.operation().id())).isEqualTo("QUARANTINED");assertThat(legs(s.operation().id())).isZero();
    }
    @Test void durableInboxSurvivesSourceOutboxAcknowledgment()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,100,null);publish(s);
        primary.update("INSERT INTO transaction_outbox VALUES(?,?,?, ?,NULL)",UUID.randomUUID(),s.operation().id(),"ATTACKER_LABEL",Processor.now());
        processor.discover();assertThat(primary.queryForObject("SELECT COUNT(*) FROM transaction_outbox WHERE processed_at_micros IS NULL",Long.class)).isZero();
        Processor restarted=new Processor(primary,settlement,keys,audit,json,new MockEnvironment(),delivery);restarted.acceptQueued();restarted.processReady();assertThat(status(s.operation().id())).isEqualTo("COMPLETED");
        restarted.relay();restarted.relay();assertThat(primary.queryForObject("SELECT COUNT(*) FROM transaction_status_events WHERE transaction_id=?",Long.class,s.operation().id())).isEqualTo(1);
    }
    @Test void reconciliationFindsIssuedOperationWithoutSourceOutbox()throws Exception{
        SignedOperation s=operation("TRANSFER",alice,bob,100,null);publish(s);settlement.update("DELETE FROM operation_jobs WHERE operation_id=?",s.operation().id());
        when(keys.inventory(0)).thenReturn(new KeyGateway.Inventory(List.of(new KeyGateway.InventoryItem(1,s)),1));
        processor.reconcile();processor.acceptQueued();processor.processReady();assertThat(status(s.operation().id())).isEqualTo("COMPLETED");
        primary.update("UPDATE transactions SET mac=? WHERE id=?","ff".repeat(32),s.operation().id());
        processor.reconcile();
        assertThat(status(s.operation().id())).isEqualTo("COMPLETED");
        verify(audit,atLeastOnce()).append(any(),eq(s.operation().id()),eq("INTEGRITY_INCIDENT"),contains("differs"));
    }
    @Test void treasuryCannotBeSpentUsingTransferType()throws Exception{
        SignedOperation s=operation("TRANSFER",Processor.TREASURY,bob,100,null);publish(s);processor.process(s.operation().id());assertThat(status(s.operation().id())).isEqualTo("REJECTED");assertThat(legs(s.operation().id())).isZero();
    }
    @Test void unknownAccountAndOverflowCannotCreatePartialJournal()throws Exception{
        SignedOperation unknown=operation("TRANSFER",alice,UUID.randomUUID(),100,null);publish(unknown);processor.process(unknown.operation().id());assertThat(status(unknown.operation().id())).isEqualTo("REJECTED");assertThat(legs(unknown.operation().id())).isZero();
        settlement.update("UPDATE accounts SET balance_cents=? WHERE id=?",Long.MAX_VALUE,bob);
        SignedOperation overflow=operation("TRANSFER",alice,bob,1,null);publish(overflow);processor.process(overflow.operation().id());assertThat(status(overflow.operation().id())).isEqualTo("REJECTED");assertThat(legs(overflow.operation().id())).isZero();assertThat(processor.account(alice).balanceCents()).isEqualTo(1000);
    }
    @Test void testHooksAreDisabledUnlessExplicitlyEnabled(){Processor ordinary=new Processor(primary,settlement,keys,audit,json,new MockEnvironment(),delivery);assertThatThrownBy(()->ordinary.configureFault(new Processor.Fault(UUID.randomUUID(),100,false))).isInstanceOf(NoSuchElementException.class);}
    @Test void failedBrokerAcceptanceRetainsSourceHint() {
        UUID id=UUID.randomUUID();
        primary.update("INSERT INTO transaction_outbox VALUES(?,?,?, ?,NULL)",UUID.randomUUID(),id,"NEW",Processor.now());
        var failing=mock(com.demo.securityapp.delivery.OperationDelivery.class);
        doThrow(new IllegalStateException("broker offline")).when(failing).publish(id);
        Processor dispatch=new Processor(primary,settlement,keys,audit,json,new MockEnvironment(),failing);
        assertThatThrownBy(dispatch::discover).isInstanceOf(IllegalStateException.class);
        assertThat(primary.queryForObject("SELECT COUNT(*) FROM transaction_outbox WHERE processed_at_micros IS NULL",Long.class)).isEqualTo(1);
        assertThat(settlement.queryForObject("SELECT COUNT(*) FROM operation_jobs WHERE operation_id=?",Long.class,id)).isZero();
    }
    @Test void durableAcceptancePrecedesSourceAcknowledgmentAndDuplicateDeliveryIsSafe() {
        UUID id=UUID.randomUUID();
        primary.update("INSERT INTO transaction_outbox VALUES(?,?,?, ?,NULL)",UUID.randomUUID(),id,"NEW",Processor.now());
        processor.discover();
        assertThat(primary.queryForObject("SELECT COUNT(*) FROM transaction_outbox WHERE processed_at_micros IS NULL",Long.class)).isZero();
        assertThat(settlement.queryForObject("SELECT COUNT(*) FROM operation_jobs WHERE operation_id=?",Long.class,id)).isZero();
        delivery.publish(id);processor.acceptQueued();
        assertThat(settlement.queryForObject("SELECT COUNT(*) FROM operation_jobs WHERE operation_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void issuanceInventoryDetectsDeletionBeforeFirstObservation()throws Exception{
        SignedOperation fresh=operation("TRANSFER",alice,bob,100,null);Operation o=fresh.operation();
        Operation old=new Operation(o.id(),1,o.ledgerId(),o.type(),o.fromAccountId(),o.toAccountId(),o.amountMinor(),o.currency(),Processor.now()-10_000_000,null,o.idempotencyKey());
        SignedOperation s=new SignedOperation(old,"key-v1",CanonicalEncoder.hashHex(old,"key-v1"),fresh.mac());publish(s);
        primary.update("DELETE FROM transactions WHERE id=?",old.id());
        when(keys.inventory(0)).thenReturn(new KeyGateway.Inventory(List.of(new KeyGateway.InventoryItem(1,s)),1));processor.reconcile();
        assertThat(status(old.id())).isEqualTo("QUARANTINED");assertThat(legs(old.id())).isZero();
        verify(audit,atLeastOnce()).append(any(),eq(old.id()),eq("INTEGRITY_INCIDENT"),contains("missing"));
    }
}
