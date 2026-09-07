package com.demo.transferapp;

import com.demo.integrity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class TransferService {
    public static final UUID LEDGER=UUID.fromString("00000000-0000-0000-0000-000000000010");
    public static final UUID TREASURY=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final IntegrityClient keys;
    private final ProcessorClient processor;
    private final ObjectMapper json;
    private final Clock clock;
    public TransferService(JdbcTemplate db,PlatformTransactionManager manager,IntegrityClient keys,ProcessorClient processor,ObjectMapper json,Clock clock){
        this.db=db;this.tx=new TransactionTemplate(manager);this.keys=keys;this.processor=processor;this.json=json;this.clock=clock;
    }
    private long now(){Instant t=clock.instant();return Math.addExact(Math.multiplyExact(t.getEpochSecond(),1_000_000),t.getNano()/1000);}
    public Map<String,Object> createAccount(UUID proposedId,String name,long initialBalance,String key){
        if(name==null||name.isBlank()||name.length()>128)throw new IllegalArgumentException("Name must have 1..128 characters");
        if(initialBalance<0||initialBalance>1_000_000_000_000L)throw new IllegalArgumentException("Invalid initial balance");
        UUID id=proposedId;
        if(id==null){
            validateKey(key);
            byte[] digest=HexFormat.of().parseHex(CanonicalEncoder.hashHex(("secure-transfer/account/v1/"+LEDGER+"/"+key).getBytes(StandardCharsets.UTF_8)));
            digest[6]=(byte)((digest[6]&0x0f)|0x80);digest[8]=(byte)((digest[8]&0x3f)|0x80);
            ByteBuffer bytes=ByteBuffer.wrap(digest);id=new UUID(bytes.getLong(),bytes.getLong());
        }
        if(TREASURY.equals(id))throw new IllegalArgumentException("Treasury is reserved");
        // Registration creates zero balance in protected settlement; no SQL seed credit bypass.
        Map<String,Object> account=processor.register(id,name);
        db.update("INSERT INTO accounts(id,name,created_at_micros) VALUES(?,?,?) ON CONFLICT DO NOTHING",id,name,now());
        Map<String,Object> result=new LinkedHashMap<>(account);
        if(initialBalance>0){
            var funded=submit("FUNDING",TREASURY,id,initialBalance,"USD",null,"initial-funding:"+id);
            result.put("fundingOperationId",funded.get("id"));result.put("fundingStatus",funded.get("status"));
        }
        return result;
    }
    public Map<String,Object> account(UUID id){return processor.account(id);}
    public Map<String,Object> transfer(UUID from,UUID to,long amount,String currency,String key){
        if(TREASURY.equals(from)||TREASURY.equals(to))throw new IllegalArgumentException("Use funding API for treasury operations");
        return submit("TRANSFER",from,to,amount,currency,null,key);
    }
    public Map<String,Object> funding(UUID to,long amount,String currency,String key){return submit("FUNDING",TREASURY,to,amount,currency,null,key);}
    public Map<String,Object> reversal(UUID originalId,String key){
        Operation original=completedOriginal(originalId);
        return submit("REVERSAL",original.toAccountId(),original.fromAccountId(),original.amountMinor(),original.currency(),originalId,key);
    }
    public Map<String,Object> correction(UUID originalId,UUID from,UUID to,long amount,String currency,String key){
        completedOriginal(originalId);
        // Processor authoritatively requires a successful compensation before replacement.
        return submit("CORRECTION",from,to,amount,currency,originalId,key);
    }
    private Operation completedOriginal(UUID id){
        if(!"COMPLETED".equals(processor.operation(id).get("status")))throw new Conflict("Original must be completed before reversal/correction");
        Operation original=keys.findById(id).orElseThrow(()->new NoSuchElementException("Original issuance not found")).operation();
        if(!LEDGER.equals(original.ledgerId()))throw new Conflict("Original belongs to another ledger");
        if("REVERSAL".equals(original.type()))throw new Conflict("Reversing/correcting a reversal is unsupported");
        return original;
    }
    private Map<String,Object> submit(String type,UUID from,UUID to,long amount,String currency,UUID related,String key){
        validateKey(key);
        if(currency==null)currency="USD";
        if(!"USD".equals(currency))throw new IllegalArgumentException("Demo ledger supports USD only");
        Operation proposed=new Operation(UUID.randomUUID(),1,LEDGER,type,from,to,amount,currency,now(),related,key);
        CanonicalEncoder.validate(proposed);
        // Account existence/status/available balance are decided from protected settlement,
        // atomically at execution. Primary metadata is never used to authorize money movement.
        SignedOperation signed=keys.issue(proposed);
        if(!CanonicalEncoder.businessHash(proposed).equals(CanonicalEncoder.businessHash(signed.operation())))
            throw new IllegalStateException("Key service returned an unexpected operation");
        publish(signed);
        Map<String,Object> response=new LinkedHashMap<>();response.put("id",signed.operation().id());response.put("status","PENDING");
        response.put("operation",signed.operation());response.put("contentHash",signed.contentHash());
        try{response.put("status",processor.operation(signed.operation().id()).get("status"));}
        catch(ProcessorClient.RemoteException e){if(e.status()!=404)throw e;}
        return response;
    }
    void publish(SignedOperation signed){
        tx.executeWithoutResult(status->{
            Operation op=signed.operation();
            int inserted=db.update("INSERT INTO transactions(id,operation_json,key_id,content_hash,mac,created_at_micros) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                op.id(),write(op),signed.keyId(),signed.contentHash(),signed.mac(),op.createdAtMicros());
            if(inserted==0){
                SignedOperation existing=db.queryForObject("SELECT * FROM transactions WHERE id=?",(rs,n)->{
                    if(rs.getLong("created_at_micros")!=op.createdAtMicros())throw new Conflict("Primary timestamp conflicts with independent issuance");
                    String raw=rs.getString("operation_json");
                    if(raw==null||raw.length()>16_384)throw new Conflict("Primary record exceeds canonical payload bounds");
                    try{return new SignedOperation(json.readValue(raw,Operation.class),rs.getString("key_id"),rs.getString("content_hash"),rs.getString("mac"));}
                    catch(Exception e){throw new Conflict("Primary record is malformed; manual investigation required");}
                },op.id());
                if(!signed.equals(existing))throw new Conflict("Primary record conflicts with independent issuance; it was not overwritten");
            }
            // DB-backed publication is committed with the row. Retry can safely repair a deleted hint.
            db.update("INSERT INTO transaction_outbox(id,transaction_id,event_type,created_at_micros) VALUES(?,?,'CREATED',?) ON CONFLICT DO NOTHING",op.id(),op.id(),op.createdAtMicros());
        });
    }
    public Map<String,Object> operation(UUID id){
        try{return processor.operation(id);}
        catch(ProcessorClient.RemoteException e){
            if(e.status()!=404)throw e;
            if(keys.findById(id).isEmpty())throw new NoSuchElementException("Operation not found");
            return Map.of("id",id,"status","PENDING","outcomeRelayed",false,"postings",List.of());
        }
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode operation",e);}}
    static void validateKey(String key){if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("Idempotency-Key (1..128 ASCII letters/digits/._:-) required");}
    public static class Conflict extends RuntimeException {public Conflict(String message){super(message);}}
}
