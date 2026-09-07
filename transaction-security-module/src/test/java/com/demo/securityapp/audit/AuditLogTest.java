package com.demo.securityapp.audit;

import com.demo.integrity.dto.SignedCheckpoint;
import com.demo.integrity.model.Checkpoint;
import com.demo.securityapp.client.KeyGateway;
import com.demo.securityapp.crypto.MerkleTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLogTest {
    JdbcTemplate db;
    AuditLog audit;
    AtomicReference<SignedCheckpoint> anchor;
    KeyGateway keys;
    @BeforeEach void setup(){
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa",""));
        new ResourceDatabasePopulator(new ClassPathResource("audit-schema.sql")).execute(db.getDataSource());
        keys=mock(KeyGateway.class);anchor=new AtomicReference<>();
        when(keys.latest()).thenAnswer(call->Optional.ofNullable(anchor.get()));
        when(keys.sign(any())).thenAnswer(call->{Checkpoint c=call.getArgument(0);SignedCheckpoint s=new SignedCheckpoint(c.logId(),c.treeSize(),c.rootHash(),c.createdAtMicros(),"signer-v1","test-signature","test-key");anchor.set(s);return s;});
        audit=new AuditLog(db,keys,new ObjectMapper());
    }
    void event(String detail){audit.append(UUID.randomUUID(),UUID.randomUUID(),"VERIFIED",detail);}
    @Test void appendIsIdempotentOrderedAndAnchored(){
        UUID event=UUID.randomUUID(),op=UUID.randomUUID();audit.append(event,op,"VERIFIED","a");audit.append(event,op,"VERIFIED","a");event("b");audit.checkpoint();
        assertThat(audit.healthy()).isTrue();assertThat(anchor.get().treeSize()).isEqualTo(2);assertThat(audit.events(op)).hasSize(1);
        assertThat(audit.proof(0)).containsKey("checkpoint");
    }
    @Test void deletedMiddleEventStopsCheckpointAndGate(){
        event("a");event("b");event("c");audit.checkpoint();db.update("DELETE FROM audit_events WHERE seq=1");
        assertThatThrownBy(audit::checkpoint).isInstanceOf(IllegalStateException.class);assertThat(audit.healthy()).isFalse();
    }
    @Test void externalAnchorDetectsWholeTailRollbackEvenIfLocalHeadAndCheckpointsRewritten(){
        event("a");event("b");audit.checkpoint();db.update("DELETE FROM audit_events WHERE seq=1");db.update("UPDATE audit_head SET next_seq=1");db.update("DELETE FROM audit_checkpoints");
        assertThatThrownBy(audit::checkpoint).hasMessageContaining("truncation");assertThat(audit.healthy()).isFalse();assertThat(anchor.get().treeSize()).isEqualTo(2);
    }
    @Test void changingAuditContentInvalidatesLeaf(){
        event("original");audit.checkpoint();db.update("UPDATE audit_events SET detail='forged' WHERE seq=0");
        assertThatThrownBy(audit::checkpoint).hasMessageContaining("content changed");
    }
    @Test void externalAnchorDetectsRecomputedForgedLeaf(){
        event("original");audit.checkpoint();Map<String,Object> row=db.queryForMap("SELECT * FROM audit_events WHERE seq=0");
        byte[] bytes=AuditLog.eventBytes(0,(UUID)row.get("event_id"),(UUID)row.get("operation_id"),"VERIFIED","forged",((Number)row.get("created_at_micros")).longValue());
        db.update("UPDATE audit_events SET detail='forged',leaf_hash=? WHERE seq=0",HexFormat.of().formatHex(MerkleTree.leaf(bytes)));
        assertThatThrownBy(audit::checkpoint).hasMessageContaining("modification or rollback");
    }
    @Test void checkpointDependencyOutageClosesGate(){event("a");when(keys.latest()).thenThrow(new IllegalStateException("offline"));assertThatThrownBy(audit::checkpoint).isInstanceOf(IllegalStateException.class);assertThat(audit.healthy()).isFalse();}
}
