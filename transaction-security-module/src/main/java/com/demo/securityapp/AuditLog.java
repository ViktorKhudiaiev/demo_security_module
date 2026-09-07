package com.demo.securityapp;

import com.demo.integrity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AuditLog {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final KeyGateway keys;
    private final ObjectMapper json;
    private volatile boolean healthy = false;
    private volatile String lastError = "Audit not yet verified";
    public AuditLog(JdbcTemplate auditDb, KeyGateway keys, ObjectMapper json) {
        this.db=auditDb; this.keys=keys; this.json=json;
        this.tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
    }
    public void append(UUID eventId, UUID operationId, String type, String detail) {
        tx.executeWithoutResult(status -> {
            long next = db.queryForObject("SELECT next_seq FROM audit_head WHERE id=1 FOR UPDATE", Long.class);
            if(!db.queryForList("SELECT seq FROM audit_events WHERE event_id=?",eventId).isEmpty()) return;
            long now = Processor.now();
            String leaf = hex(MerkleTree.leaf(eventBytes(next,eventId,operationId,type,detail,now)));
            db.update("INSERT INTO audit_events(seq,event_id,operation_id,event_type,detail,created_at_micros,leaf_hash) VALUES(?,?,?,?,?,?,?)",next,eventId,operationId,type,detail,now,leaf);
            db.update("UPDATE audit_head SET next_seq=? WHERE id=1",next+1);
        });
    }
    public boolean healthy() { return healthy; }
    public String lastError() { return lastError; }
    public List<Map<String,Object>> events(UUID op) {
        return op==null ? db.queryForList("SELECT * FROM audit_events ORDER BY seq DESC LIMIT 100")
                : db.queryForList("SELECT * FROM audit_events WHERE operation_id=? ORDER BY seq",op);
    }
    public synchronized void checkpoint() {
        try {
            List<byte[]> leaves = validatedLeaves();
            Optional<SignedCheckpoint> anchor = keys.latest();
            if(anchor.isPresent()) {
                SignedCheckpoint a = anchor.get();
                if(!a.logId().equals("security-audit") || a.treeSize()>leaves.size()) throw new IllegalStateException("External checkpoint detects audit truncation");
                if(!hex(MerkleTree.root(leaves.subList(0,Math.toIntExact(a.treeSize())))).equals(a.rootHash())) throw new IllegalStateException("External checkpoint detects audit modification or rollback");
            }
            String root = hex(MerkleTree.root(leaves));
            if(anchor.isEmpty() || anchor.get().treeSize()<leaves.size()) {
                SignedCheckpoint signed = keys.sign(new Checkpoint("security-audit",leaves.size(),root,Processor.now()));
                String encoded = json.writeValueAsString(signed);
                if(db.queryForList("SELECT tree_size FROM audit_checkpoints WHERE tree_size=?",leaves.size()).isEmpty())
                    db.update("INSERT INTO audit_checkpoints(tree_size,root_hash,checkpoint_json) VALUES(?,?,?)",leaves.size(),root,encoded);
            }
            healthy=true; lastError=null;
        } catch(Exception e) {
            healthy=false; lastError=e.getMessage();
            throw new IllegalStateException("Audit checkpoint unavailable or invalid: " + lastError,e);
        }
    }
    public Map<String,Object> proof(int index) {
        List<byte[]> leaves=validatedLeaves();
        SignedCheckpoint anchor=keys.latest().orElseThrow();
        if(anchor.treeSize()>leaves.size()) throw new IllegalStateException("Audit truncated");
        List<byte[]> signedLeaves=leaves.subList(0,Math.toIntExact(anchor.treeSize()));
        if(index<0||index>=signedLeaves.size())throw new IllegalArgumentException("Index is outside the signed checkpoint");
        if(!hex(MerkleTree.root(signedLeaves)).equals(anchor.rootHash()))throw new IllegalStateException("Audit does not match external checkpoint");
        return Map.of("index",index,"leafHash",hex(signedLeaves.get(index)),"proof",MerkleTree.proof(signedLeaves,index),"checkpoint",anchor);
    }
    private List<byte[]> validatedLeaves() {
        return tx.execute(status -> {
            long expected=db.queryForObject("SELECT next_seq FROM audit_head WHERE id=1 FOR UPDATE",Long.class);
            List<byte[]> leaves=db.query("SELECT * FROM audit_events ORDER BY seq",(rs,row)->{
                if(rs.getLong("seq")!=row) throw new IllegalStateException("Audit sequence gap");
                byte[] hash=MerkleTree.leaf(eventBytes(rs.getLong("seq"),rs.getObject("event_id",UUID.class),rs.getObject("operation_id",UUID.class),rs.getString("event_type"),rs.getString("detail"),rs.getLong("created_at_micros")));
                if(!hex(hash).equals(rs.getString("leaf_hash"))) throw new IllegalStateException("Audit event content changed");
                return hash;
            });
            if(expected!=leaves.size()) throw new IllegalStateException("Audit head/count mismatch");
            return leaves;
        });
    }
    public static byte[] eventBytes(long seq,UUID id,UUID op,String type,String detail,long micros) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(bytes);
            out.writeLong(seq); out.writeLong(micros);
            for(String field:new String[]{"secure-transfer/audit/v1",id.toString(),op==null?"":op.toString(),type,detail}) {
                byte[] value=field.getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);
            }
            return bytes.toByteArray();
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    private static String hex(byte[] value){return HexFormat.of().formatHex(value);}
}
