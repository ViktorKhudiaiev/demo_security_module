package com.demo.securityapp;

import com.demo.integrity.crypto.CanonicalEncoder;
import com.demo.integrity.dto.SignedCheckpoint;
import com.demo.integrity.dto.SignedOperation;
import com.demo.integrity.model.Checkpoint;
import com.demo.integrity.model.Operation;
import com.demo.securityapp.audit.AuditLog;
import com.demo.securityapp.client.KeyGateway;
import com.demo.securityapp.service.Processor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP, persistent embedded broker and two JDBC databases; the key service is replaced. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=SecurityApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "processor.scheduling-enabled=false","processor.test-faults-enabled=false",
        "processor.app-token=integration-processor-app-token-32chars",
        "processor.admin-token=integration-processor-admin-token-32chars",
        "processor.primary.url=jdbc:h2:mem:http-primary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "processor.primary.username=sa","processor.primary.password=",
        "processor.audit.url=jdbc:h2:mem:http-audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "processor.audit.username=sa","processor.audit.password=",
        "processor.settlement.url=jdbc:h2:mem:http-audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "processor.settlement.username=sa","processor.settlement.password="})
class ProcessorServiceIntegrationTest {
    static final Path brokerDirectory;
    static { try { brokerDirectory=Files.createTempDirectory("integrity-http-broker-"); } catch(Exception e) {throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void brokerProperties(DynamicPropertyRegistry properties) {
        properties.add("processor.broker.directory",()->brokerDirectory.toString());
        properties.add("processor.broker.name",()->"http-integration");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate primaryDb;
    @Autowired JdbcTemplate settlementDb;
    @Autowired JdbcTemplate auditDb;
    @Autowired Processor processor;
    @Autowired AuditLog audit;
    @Autowired ObjectMapper json;
    @MockBean KeyGateway keys;
    HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void setup(){
        reset(keys);
        if(primaryDb.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='TRANSACTIONS'",Integer.class)==0){
            primaryDb.execute("CREATE TABLE transactions(id UUID PRIMARY KEY,operation_json TEXT,key_id VARCHAR,content_hash VARCHAR,mac VARCHAR,created_at_micros BIGINT)");
            primaryDb.execute("CREATE TABLE transaction_outbox(id UUID PRIMARY KEY,transaction_id UUID,event_type VARCHAR,created_at_micros BIGINT,processed_at_micros BIGINT)");
            primaryDb.execute("CREATE TABLE transaction_status_events(id UUID PRIMARY KEY,transaction_id UUID,status VARCHAR,reason VARCHAR,created_at_micros BIGINT)");
            new ResourceDatabasePopulator(new ClassPathResource("settlement-schema.sql")).execute(settlementDb.getDataSource());
            new ResourceDatabasePopulator(new ClassPathResource("audit-schema.sql"), new ClassPathResource("notification-schema.sql")).execute(auditDb.getDataSource());
        }
        when(keys.latest()).thenReturn(Optional.empty());
        when(keys.sign(any())).thenAnswer(call->{Checkpoint c=call.getArgument(0);return new SignedCheckpoint(c.logId(),c.treeSize(),c.rootHash(),c.createdAtMicros(),"test-key","test-signature","test-public");});
        audit.checkpoint();
    }
    HttpResponse<String> request(String method,String path,String token,Object body)throws Exception{
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void actualHttpRegistrationFundingAndTrustedOutcome()throws Exception{
        String token="integration-processor-app-token-32chars";UUID id=UUID.randomUUID();
        assertThat(request("POST","/internal/accounts",token,Map.of("id",id,"name","HTTP account")).statusCode()).isEqualTo(201);
        Operation operation=new Operation(UUID.randomUUID(),1,UUID.fromString("00000000-0000-0000-0000-000000000010"),"FUNDING",Processor.TREASURY,id,123,"USD",Processor.now(),null,UUID.randomUUID().toString());
        SignedOperation signed=new SignedOperation(operation,"test-key",CanonicalEncoder.hashHex(operation,"test-key"),"00".repeat(32));
        when(keys.receipt(operation.id())).thenReturn(Optional.of(signed));when(keys.verify(signed)).thenReturn(true);
        primaryDb.update("INSERT INTO transactions VALUES(?,?,?,?,?,?)",operation.id(),json.writeValueAsString(operation),signed.keyId(),signed.contentHash(),signed.mac(),operation.createdAtMicros());
        primaryDb.update("INSERT INTO transaction_outbox VALUES(?,?,?, ?,NULL)",UUID.randomUUID(),operation.id(),"NEW",Processor.now());
        processor.discover();processor.acceptQueued();processor.processReady();processor.relay();
        HttpResponse<String> result=request("GET","/internal/operations/"+operation.id(),token,null);
        assertThat(result.statusCode()).isEqualTo(200);assertThat(json.readTree(result.body()).get("status").asText()).isEqualTo("COMPLETED");assertThat(json.readTree(result.body()).get("outcomeRelayed").asBoolean()).isTrue();
        assertThat(json.readTree(request("GET","/internal/accounts/"+id,token,null).body()).get("balanceCents").asLong()).isEqualTo(123);
    }
    @Test void httpCredentialsAndDisabledTestHooksAreEnforced()throws Exception{
        assertThat(request("GET","/health",null,null).statusCode()).isEqualTo(200);
        assertThat(request("GET","/internal/operations/"+UUID.randomUUID(),null,null).statusCode()).isEqualTo(401);
        assertThat(request("POST","/internal/test/faults","integration-processor-app-token-32chars",Map.of("operationId",UUID.randomUUID())).statusCode()).isEqualTo(401);
        assertThat(request("POST","/internal/test/faults","integration-processor-admin-token-32chars",Map.of("operationId",UUID.randomUUID(),"pauseAfterVerifyMillis",0,"failAfterDebit",false)).statusCode()).isEqualTo(404);
        assertThat(request("POST","/internal/accounts/"+UUID.randomUUID()+"/hold","integration-processor-admin-token-32chars",Map.of()).statusCode()).isEqualTo(400);
    }
}
