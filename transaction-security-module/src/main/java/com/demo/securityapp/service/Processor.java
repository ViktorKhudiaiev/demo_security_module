package com.demo.securityapp.service;

import com.demo.integrity.crypto.CanonicalEncoder;
import com.demo.integrity.dto.IssuanceInventory;
import com.demo.integrity.dto.IssuanceInventoryItem;
import com.demo.integrity.dto.SignedOperation;
import com.demo.integrity.model.Operation;
import com.demo.securityapp.audit.AuditLog;
import com.demo.securityapp.client.KeyGateway;
import com.demo.securityapp.crypto.MerkleTree;
import com.demo.securityapp.domain.Account;
import com.demo.securityapp.dto.FaultRequest;
import com.demo.securityapp.delivery.OperationDelivery;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Service
public class Processor {
    public static final UUID TREASURY=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate primary;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final KeyGateway keys;
    private final AuditLog audit;
    private final OperationDelivery delivery;
    private final ObjectMapper json;
    private final UUID ledgerId;
    private final boolean faultsEnabled;
    private final Map<UUID,FaultRequest> faults=new ConcurrentHashMap<>();
    private final Set<UUID> paused=ConcurrentHashMap.newKeySet();
    private long inventoryCursor;
    private final ExecutorService workers=Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("protected-worker-",0).factory());
    public Processor(JdbcTemplate primaryDb,JdbcTemplate settlementDb,KeyGateway keys,AuditLog audit,ObjectMapper json,Environment env,OperationDelivery delivery) {
        this.primary=primaryDb;this.db=settlementDb;this.keys=keys;this.audit=audit;
        this.delivery=delivery;
        this.json=json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        ledgerId=UUID.fromString(env.getProperty("processor.ledger-id","00000000-0000-0000-0000-000000000010"));
        faultsEnabled=env.getProperty("processor.test-faults-enabled",Boolean.class,false);
    }
    public static long now(){Instant t=Instant.now();return Math.addExact(Math.multiplyExact(t.getEpochSecond(),1_000_000),t.getNano()/1000);}
    public void discover() {
        for(Map<String,Object> row:primary.queryForList("SELECT id,transaction_id FROM transaction_outbox WHERE processed_at_micros IS NULL ORDER BY created_at_micros LIMIT 100")) {
            UUID id=uuid(row.get("transaction_id"));
            delivery.publish(id);
            // Ack only after persistent broker acceptance. Source outbox is an untrusted hint.
            primary.update("UPDATE transaction_outbox SET processed_at_micros=? WHERE id=? AND processed_at_micros IS NULL",now(),uuid(row.get("id")));
        }
    }
    public void acceptQueued() { delivery.drain(100, this::enqueue); }
    public Map<String,Object> deliveryHealth() { return delivery.health(); }
    private void scheduleCandidate(UUID id) {
        if (db.queryForList("SELECT operation_id FROM operation_jobs WHERE operation_id=?",id).isEmpty()) delivery.publish(id);
    }
    public void reconcile() {
        // Bound each invocation, but traverse retained histories faster than one page per second.
        for(int pageNumber=0;pageNumber<5;pageNumber++) {
        IssuanceInventory page=keys.inventory(inventoryCursor);
        for(IssuanceInventoryItem item:page.items()) {
            SignedOperation expected=item.signedOperation(); UUID id=expected.operation().id();
            if(!expected.operation().ledgerId().equals(ledgerId)) continue;
            scheduleCandidate(id);
            Optional<SignedOperation> current;
            try {current=source(id);} catch(InvalidRecord e) {incident(id,e.getMessage(),"MALFORMED");continue;}
            if(current.isEmpty()) {
                if(now()-expected.operation().createdAtMicros()>5_000_000) {
                    enqueue(id); // A rejection needs a durable serialization row; it cannot authorize money.
                    incident(id,"Issued primary record is missing","MISSING");
                    rejectIfPending(id,"QUARANTINED","Issued primary record is missing",expected);
                }
            } else if(!same(current.get(),expected)) {
                enqueue(id);
                incident(id,"Primary record differs from independent issuance receipt","MISMATCH");
                rejectIfPending(id,"QUARANTINED","Primary record differs from issuance receipt",expected);
            }
        }
        inventoryCursor=page.items().size()<100?0:page.nextSequence();
        if(page.items().size()<100)break;
        }
        // A fabricated row may lack an outbox and an issuance receipt. Scan the source independently.
        // UUID ordering cursor is advanced separately, so no source row is trusted as an authorization.
        scanPrimary();
    }
    private UUID sourceCursor;
    private void scanPrimary() {
        List<UUID> ids=sourceCursor==null
                ?primary.query("SELECT id FROM transactions ORDER BY id LIMIT 100",(rs,n)->rs.getObject(1,UUID.class))
                :primary.query("SELECT id FROM transactions WHERE id>? ORDER BY id LIMIT 100",(rs,n)->rs.getObject(1,UUID.class),sourceCursor);
        for(UUID id:ids)scheduleCandidate(id);
        sourceCursor=ids.size()<100?null:ids.getLast();
    }
    public void enqueue(UUID id) {
        try{db.update("INSERT INTO operation_jobs(operation_id,status,attempts,lease_until_micros,next_attempt_micros) VALUES(?,'READY',0,0,0)",id);}
        catch(DuplicateKeyException ignored){}
    }
    public synchronized void processReady() {
        if(!audit.healthy())return;
        long time=now();
        List<UUID> ids=db.query("SELECT operation_id FROM operation_jobs WHERE (status IN ('READY','RETRY') AND next_attempt_micros<=?) OR (status='PROCESSING' AND lease_until_micros<?) ORDER BY next_attempt_micros LIMIT 100",(rs,n)->rs.getObject(1,UUID.class),time,time);
        // At most 100 admitted jobs and two concurrent workers. SQL claims/locks remain authoritative.
        var batch=ids.stream().map(id->workers.submit(()->process(id))).toList();
        awaitBatch(batch);
    }
    // Keep admission closed until every submitted task finishes, including failure/shutdown paths.
    static void awaitBatch(List<? extends Future<?>> batch) {
        Throwable failure=null;
        boolean interrupted=false;
        for(Future<?> task:batch) {
            boolean finished=false;
            while(!finished) {
                try {task.get();finished=true;}
                catch(InterruptedException e){interrupted=true;}
                catch(java.util.concurrent.ExecutionException e){if(failure==null)failure=e.getCause();finished=true;}
                catch(java.util.concurrent.CancellationException e){if(failure==null)failure=e;finished=true;}
            }
        }
        if(interrupted)Thread.currentThread().interrupt();
        if(failure!=null)throw new IllegalStateException("Protected worker failed; lease permits recovery",failure);
    }
    @PreDestroy public void closeWorkers() {
        workers.shutdown();
        try {if(!workers.awaitTermination(10,TimeUnit.SECONDS))cancelQueued(workers.shutdownNow());}
        catch(InterruptedException e){cancelQueued(workers.shutdownNow());Thread.currentThread().interrupt();}
    }
    static void cancelQueued(List<Runnable> removed) {
        // shutdownNow removes queued FutureTasks without completing them. Release the batch waiter.
        for(Runnable task:removed)if(task instanceof Future<?> future)future.cancel(false);
    }
    public void process(UUID id) {
        if(!audit.healthy())return;
        long time=now();
        int claimed=db.update("UPDATE operation_jobs SET status='PROCESSING',attempts=attempts+1,lease_until_micros=? WHERE operation_id=? AND ((status IN ('READY','RETRY') AND next_attempt_micros<=?) OR (status='PROCESSING' AND lease_until_micros<?))",time+30_000_000,id,time,time);
        if(claimed==0)return;
        try {
            Optional<SignedOperation> issuance=keys.receipt(id);
            if(issuance.isEmpty()){rejectIfPending(id,"QUARANTINED","No independent issuance receipt",null);return;}
            SignedOperation expected=issuance.get();
            Optional<SignedOperation> maybe=source(id);
            if(maybe.isEmpty()) {
                if(now()-expected.operation().createdAtMicros()<5_000_000)throw new IllegalStateException("Waiting for primary publication");
                rejectIfPending(id,"QUARANTINED","Issued primary record is missing",expected);return;
            }
            SignedOperation checked=maybe.get();
            if(!same(checked,expected) || !keys.verify(checked)) {
                rejectIfPending(id,"QUARANTINED","Integrity verification failed",expected);return;
            }
            if(!ledgerId.equals(checked.operation().ledgerId())) {
                rejectIfPending(id,"QUARANTINED","Wrong ledger domain",expected);return;
            }
            audit.append(eventId(id,"VERIFIED"),id,"VERIFIED",checked.contentHash());
            FaultRequest fault=faults.remove(id);
            if(fault!=null && fault.pauseAfterVerifyMillis()>0) {
                paused.add(id);
                try {Thread.sleep(fault.pauseAfterVerifyMillis());}finally{paused.remove(id);}
            }
            SignedOperation finalSnapshot=source(id).orElseThrow(()->new InvalidRecord("Primary deleted after verification"));
            if(!same(finalSnapshot,checked)) {
                rejectIfPending(id,"QUARANTINED","Record changed after verification (TOCTOU)",expected);return;
            }
            // Settle only these exact checked fields. No later read of mutable source supplies amounts or accounts.
            settle(finalSnapshot,fault!=null && fault.failAfterDebit());
        } catch(InvalidRecord e) {
            rejectIfPending(id,"QUARANTINED",e.getMessage(),null);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); retry(id,"Processing interrupted");
        } catch(Exception e) {retry(id,e.getClass().getSimpleName()+": "+e.getMessage());}
    }
    public void settle(SignedOperation signed,boolean failAfterDebit) {
        Operation op=signed.operation();
        tx.executeWithoutResult(status->{
            // Same operation and every retry serialize on this durable row.
            db.queryForObject("SELECT attempts FROM operation_jobs WHERE operation_id=? FOR UPDATE",Integer.class,op.id());
            if(!db.queryForList("SELECT status FROM operation_results WHERE operation_id=?",op.id()).isEmpty()) {
                db.update("UPDATE operation_jobs SET status='DONE',lease_until_micros=0 WHERE operation_id=?",op.id());return;
            }
            String rejection=businessRejection(op);
            if(rejection!=null){outcome(signed,"REJECTED",rejection);return;}
            List<UUID> accountIds=new ArrayList<>(List.of(op.fromAccountId(),op.toAccountId()));
            accountIds.sort(Comparator.naturalOrder());
            Map<UUID,Account> locked=new HashMap<>();
            for(UUID accountId:accountIds) {
                List<Account> found=db.query("SELECT * FROM accounts WHERE id=? FOR UPDATE",(rs,n)->new Account(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("status"),rs.getLong("balance_cents")),accountId);
                if(found.isEmpty()){outcome(signed,"REJECTED","Unknown account");return;}
                locked.put(accountId,found.getFirst());
            }
            Account from=locked.get(op.fromAccountId()),to=locked.get(op.toAccountId());
            if(!from.status().equals("ACTIVE")||!to.status().equals("ACTIVE")){outcome(signed,"REJECTED","Account held");return;}
            if(!op.fromAccountId().equals(TREASURY) && from.balanceCents()<op.amountMinor()){outcome(signed,"REJECTED","Insufficient funds");return;}
            long debit,credit;
            try{debit=Math.subtractExact(from.balanceCents(),op.amountMinor());credit=Math.addExact(to.balanceCents(),op.amountMinor());}
            catch(ArithmeticException overflow){outcome(signed,"REJECTED","Balance overflow");return;}
            db.update("INSERT INTO ledger_journal(operation_id,content_hash,operation_type,related_operation_id,completed_at_micros) VALUES(?,?,?,?,?)",op.id(),signed.contentHash(),op.type(),op.relatedOperationId(),now());
            db.update("INSERT INTO ledger_postings(operation_id,leg,account_id,amount_cents) VALUES(?,0,?,?)",op.id(),op.fromAccountId(),-op.amountMinor());
            db.update("UPDATE accounts SET balance_cents=? WHERE id=?",debit,op.fromAccountId());
            if(failAfterDebit)throw new IllegalStateException("Injected failure after debit");
            db.update("INSERT INTO ledger_postings(operation_id,leg,account_id,amount_cents) VALUES(?,1,?,?)",op.id(),op.toAccountId(),op.amountMinor());
            db.update("UPDATE accounts SET balance_cents=? WHERE id=?",credit,op.toAccountId());
            outcome(signed,"COMPLETED",null);
        });
    }
    private String businessRejection(Operation op) {
        if(!op.currency().equals("USD"))return "Demo ledger currency is USD";
        if(op.fromAccountId()==null || op.fromAccountId().equals(op.toAccountId()))return "Invalid account pair";
        if(op.type().equals("FUNDING"))return !TREASURY.equals(op.fromAccountId())||TREASURY.equals(op.toAccountId())?"Funding must originate from treasury":null;
        if(op.type().equals("TRANSFER")||op.type().equals("CORRECTION")) {
            if(TREASURY.equals(op.fromAccountId())||TREASURY.equals(op.toAccountId()))return "Transfers cannot use treasury";
        }
        if(op.relatedOperationId()!=null) {
            // Lock predecessor: concurrent reversals/corrections cannot pass uniqueness together.
            db.query("SELECT operation_id FROM operation_jobs WHERE operation_id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),op.relatedOperationId());
            List<String> originals=db.query("SELECT signed_json FROM operation_results WHERE operation_id=? AND status='COMPLETED'",(rs,n)->rs.getString(1),op.relatedOperationId());
            if(originals.isEmpty())return "Referenced operation is not completed";
            Operation original=readSigned(originals.getFirst()).operation();
            if(op.type().equals("REVERSAL")) {
                if(original.type().equals("REVERSAL"))return "Reversing a reversal is unsupported";
                if(!Objects.equals(op.fromAccountId(),original.toAccountId())||!Objects.equals(op.toAccountId(),original.fromAccountId())||op.amountMinor()!=original.amountMinor()||!op.currency().equals(original.currency()))return "Reversal must exactly compensate original";
                if(!db.queryForList("SELECT operation_id FROM ledger_journal WHERE related_operation_id=? AND operation_type='REVERSAL'",op.relatedOperationId()).isEmpty())return "Original already reversed";
            } else if(op.type().equals("CORRECTION")) {
                if(original.type().equals("REVERSAL"))return "Cannot correct a reversal";
                if(db.queryForList("SELECT operation_id FROM ledger_journal WHERE related_operation_id=? AND operation_type='REVERSAL'",op.relatedOperationId()).isEmpty())return "Original must be reversed before correction";
                if(!db.queryForList("SELECT operation_id FROM ledger_journal WHERE related_operation_id=? AND operation_type='CORRECTION'",op.relatedOperationId()).isEmpty())return "Original already corrected";
            }
        }
        return null;
    }
    private void outcome(SignedOperation signed,String status,String reason) {
        UUID id=signed.operation().id();long time=now();
        db.update("INSERT INTO operation_results(operation_id,status,reason,content_hash,signed_json,completed_at_micros) VALUES(?,?,?,?,?,?)",id,status,reason,signed.contentHash(),write(signed),time);
        addOutcome(id,status,reason,signed.contentHash(),time);
    }
    private void addOutcome(UUID id,String status,String reason,String hash,long time) {
        db.update("INSERT INTO settlement_outbox(id,operation_id,status,reason,content_hash,created_at_micros) VALUES(?,?,?,?,?,?)",eventId(id,"OUTCOME"),id,status,reason,hash,time);
        db.update("UPDATE operation_jobs SET status='DONE',lease_until_micros=0,last_error=NULL WHERE operation_id=?",id);
    }
    private void rejectIfPending(UUID id,String status,String reason,SignedOperation expected) {
        if(status.equals("QUARANTINED"))incident(id,reason,"QUARANTINED");
        tx.executeWithoutResult(t->{
            db.queryForObject("SELECT attempts FROM operation_jobs WHERE operation_id=? FOR UPDATE",Integer.class,id);
            if(!db.queryForList("SELECT status FROM operation_results WHERE operation_id=?",id).isEmpty())return;
            long time=now();
            db.update("INSERT INTO operation_results(operation_id,status,reason,content_hash,signed_json,completed_at_micros) VALUES(?,?,?,?,?,?)",id,status,reason,expected==null?null:expected.contentHash(),expected==null?null:write(expected),time);
            addOutcome(id,status,reason,expected==null?null:expected.contentHash(),time);
        });
    }
    public void relay() {
        for(Map<String,Object> row:db.queryForList("SELECT * FROM account_audit_outbox WHERE relayed_at_micros IS NULL ORDER BY created_at_micros LIMIT 100")) {
            UUID event=uuid(row.get("id"));
            audit.append(event,null,row.get("event_type").toString(),write(row));
            db.update("UPDATE account_audit_outbox SET relayed_at_micros=? WHERE id=?",now(),event);
        }
        for(Map<String,Object> row:db.queryForList("SELECT * FROM settlement_outbox WHERE relayed_at_micros IS NULL ORDER BY created_at_micros LIMIT 100")) {
            UUID event=uuid(row.get("id")),id=uuid(row.get("operation_id"));
            audit.append(event,id,row.get("status").toString(),write(row));
            try{primary.update("INSERT INTO transaction_status_events(id,transaction_id,status,reason,created_at_micros) VALUES(?,?,?,?,?)",event,id,row.get("status"),row.get("reason"),row.get("created_at_micros"));}
            catch(DuplicateKeyException ignored){}
            db.update("UPDATE settlement_outbox SET relayed_at_micros=? WHERE id=?",now(),event);
        }
    }
    private void retry(UUID id,String message) {
        if(message==null)message="Dependency unavailable";
        db.update("UPDATE operation_jobs SET status='RETRY',lease_until_micros=0,next_attempt_micros=?,last_error=? WHERE operation_id=? AND status<>'DONE'",now()+1_000_000,message.substring(0,Math.min(500,message.length())),id);
    }
    private void incident(UUID id,String reason,String category) {
        audit.append(eventId(id,"INCIDENT/"+category+"/"+reason),id,"INTEGRITY_INCIDENT",reason);
    }
    public Optional<SignedOperation> source(UUID id) {
        return primary.query("SELECT id,key_id,content_hash,mac,created_at_micros,CASE WHEN CHAR_LENGTH(operation_json)<=16384 THEN operation_json ELSE NULL END AS bounded_json FROM transactions WHERE id=?",(rs,n)->{
            try {
                String raw=rs.getString("bounded_json");
                if(raw==null||raw.length()>16_384)throw new InvalidRecord("Invalid operation payload size");
                Operation op=json.readValue(raw,Operation.class);
                if(!op.id().equals(id)||op.createdAtMicros()!=rs.getLong("created_at_micros"))throw new InvalidRecord("Primary identity or timestamp mismatch");
                SignedOperation signed=new SignedOperation(op,rs.getString("key_id"),rs.getString("content_hash"),rs.getString("mac"));
                if(!CanonicalEncoder.hashHex(op,signed.keyId()).equals(signed.contentHash()))throw new InvalidRecord("Canonical content hash mismatch");
                return signed;
            }catch(Exception e){if(e instanceof InvalidRecord i)throw i;throw new InvalidRecord("Malformed canonical operation");}
        },id).stream().findFirst();
    }
    private static boolean same(SignedOperation a,SignedOperation b){return a.equals(b);}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private SignedOperation readSigned(String value){try{return json.readValue(value,SignedOperation.class);}catch(Exception e){throw new IllegalStateException("Protected receipt invalid",e);}}
    public Account register(UUID id,String name) {
        if(id==null||TREASURY.equals(id)||name==null||name.isBlank()||name.length()>128)throw new IllegalArgumentException("Valid id and name (1..128) required");
        try{tx.executeWithoutResult(status->{
            db.update("INSERT INTO accounts(id,name,status,balance_cents) VALUES(?,?,'ACTIVE',0)",id,name);
            accountEvent(eventId(id,"ACCOUNT_CREATED"),id,"ACCOUNT_CREATED");
        });}
        catch(DuplicateKeyException e){if(!account(id).name().equals(name))throw new IllegalArgumentException("Account id already has another name");}
        return account(id);
    }
    public Account account(UUID id) {
        return db.query("SELECT * FROM accounts WHERE id=?",(rs,n)->new Account(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("status"),rs.getLong("balance_cents")),id).stream().findFirst().orElseThrow(()->new NoSuchElementException("Account not found"));
    }
    public Account hold(UUID id,boolean held) {
        if(TREASURY.equals(id))throw new IllegalArgumentException("Treasury hold is unsupported");
        tx.executeWithoutResult(status->{
            if(db.update("UPDATE accounts SET status=? WHERE id=?",held?"HELD":"ACTIVE",id)==0)throw new NoSuchElementException("Account not found");
            accountEvent(UUID.randomUUID(),id,held?"ACCOUNT_HELD":"ACCOUNT_RELEASED");
        });
        return account(id);
    }
    private void accountEvent(UUID id,UUID accountId,String type){db.update("INSERT INTO account_audit_outbox(id,account_id,event_type,created_at_micros) VALUES(?,?,?,?)",id,accountId,type,now());}
    public Map<String,Object> operation(UUID id) {
        List<Map<String,Object>> results=db.queryForList("SELECT * FROM operation_results WHERE operation_id=?",id);
        Map<String,Object> response=new LinkedHashMap<>();response.put("id",id);
        if(results.isEmpty()) {
            List<Map<String,Object>> jobs=db.queryForList("SELECT * FROM operation_jobs WHERE operation_id=?",id);
            if(jobs.isEmpty())throw new NoSuchElementException("Operation not found");
            response.put("status","PENDING");response.put("reason",jobs.getFirst().get("last_error"));
        } else {
            Map<String,Object> r=results.getFirst();response.put("status",r.get("status"));response.put("reason",r.get("reason"));response.put("contentHash",r.get("content_hash"));
            response.put("completedAtMicros",r.get("completed_at_micros"));
            if(r.get("signed_json")!=null){SignedOperation signed=readSigned(r.get("signed_json").toString());response.put("operation",signed.operation());response.put("signedOperation",signed);}
        }
        response.put("postings",db.query("SELECT account_id,amount_cents,leg FROM ledger_postings WHERE operation_id=? ORDER BY leg",(rs,n)->Map.of("accountId",rs.getObject(1,UUID.class),"amountCents",rs.getLong(2),"leg",rs.getInt(3)),id));
        response.put("outcomeRelayed",!db.queryForList("SELECT id FROM settlement_outbox WHERE operation_id=? AND relayed_at_micros IS NOT NULL",id).isEmpty());
        return response;
    }
    public void configureFault(FaultRequest fault) {
        if(!faultsEnabled)throw new NoSuchElementException("Test hooks disabled");
        if(fault.operationId()==null||fault.pauseAfterVerifyMillis()<0||fault.pauseAfterVerifyMillis()>15000)throw new IllegalArgumentException("Invalid fault configuration");
        faults.put(fault.operationId(),fault);
    }
    public Map<String,Object> faultState(UUID id){if(!faultsEnabled)throw new NoSuchElementException("Test hooks disabled");return Map.of("paused",paused.contains(id),"configured",faults.containsKey(id));}
    private static UUID eventId(UUID op,String kind){
        byte[] hash=MerkleTree.hash(("secure-transfer/event-id/v1/"+op+"/"+kind).getBytes(StandardCharsets.UTF_8));
        hash[6]=(byte)((hash[6]&0x0f)|0x80);hash[8]=(byte)((hash[8]&0x3f)|0x80);
        ByteBuffer bytes=ByteBuffer.wrap(hash);return new UUID(bytes.getLong(),bytes.getLong());
    }
    private static UUID uuid(Object value){return value instanceof UUID u?u:UUID.fromString(value.toString());}
    public static class InvalidRecord extends RuntimeException {public InvalidRecord(String message){super(message);}}
}
