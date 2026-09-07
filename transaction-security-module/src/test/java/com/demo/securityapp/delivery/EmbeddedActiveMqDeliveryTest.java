package com.demo.securityapp.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class EmbeddedActiveMqDeliveryTest {
    @TempDir Path directory;
    final String name="broker-"+UUID.randomUUID();
    EmbeddedActiveMqDelivery open() { return new EmbeddedActiveMqDelivery(directory,name); }
    void until(BooleanSupplier completed,Runnable action) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!completed.getAsBoolean() && System.nanoTime()<deadline) action.run();
        assertThat(completed.getAsBoolean()).isTrue();
    }
    @Test void persistentHintSurvivesNewBrokerInstance() {
        UUID id=UUID.randomUUID();
        try(var first=open()){first.publish(id);}
        try(var second=open()) {
            List<UUID> received=new ArrayList<>();
            until(()->received.size()==1,()->second.drain(1,received::add));
            assertThat(received).containsExactly(id);
            assertThat(second.drain(1,received::add)).isZero();
        }
    }
    @Test void rollbackRedeliversWithoutDuplicatingCommittedSqlJob() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+name+";DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE jobs(id UUID PRIMARY KEY)");
        UUID id=UUID.randomUUID();
        try(var queue=open()) {
            queue.publish(id);
            assertThatThrownBy(()->queue.drain(1,value->{
                db.update("INSERT INTO jobs VALUES(?)",value);
                throw new IllegalStateException("SQL committed, JMS acknowledgment not reached");
            })).isInstanceOf(IllegalStateException.class);
            List<UUID> received=new ArrayList<>();
            until(()->received.size()==1,()->queue.drain(1,value->{
                db.update("MERGE INTO jobs(id) KEY(id) VALUES(?)",value);received.add(value);
            }));
            assertThat(received).containsExactly(id);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM jobs",Long.class)).isEqualTo(1);
            assertThat(queue.health().get("redeliveriesSinceStart")).isEqualTo(1L);
        }
    }
    @Test void failedDeliverySurvivesRestart() {
        UUID id=UUID.randomUUID();
        try(var first=open()) {
            first.publish(id);
            assertThatThrownBy(()->first.drain(1,value->{throw new IllegalStateException("SQL offline");})).isInstanceOf(IllegalStateException.class);
        }
        try(var second=open()) {
            List<UUID> received=new ArrayList<>();
            until(()->received.size()==1,()->second.drain(1,received::add));
            assertThat(received).containsExactly(id);
        }
    }
    @Test void handlerErrorCannotBeAcknowledgedByALaterSuccessfulDelivery() {
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        try(var queue=open()) {
            queue.publish(first);queue.publish(second);
            assertThatThrownBy(()->queue.drain(1,value->{throw new AssertionError("Handler failed before SQL");})).isInstanceOf(AssertionError.class);
            List<UUID> received=new ArrayList<>();
            until(()->received.size()==2,()->queue.drain(2,received::add));
            assertThat(received).containsExactlyInAnyOrder(first,second);
        }
    }
    @Test void boundedBurstPreservesEveryUniqueHint() {
        Set<UUID> expected=new HashSet<>();for(int i=0;i<200;i++)expected.add(UUID.randomUUID());
        try(var queue=open()) {
            expected.forEach(queue::publish);
            List<UUID> received=new ArrayList<>();
            until(()->received.size()==expected.size(),()->assertThat(queue.drain(17,received::add)).isLessThanOrEqualTo(17));
            assertThat(received).hasSize(200).containsExactlyInAnyOrderElementsOf(expected);
        }
    }
    @Test void closedTransportFailsWithoutImplicitBrokerCreation() {
        var queue=open();queue.close();queue.close();
        assertThat(queue.health().get("status")).isEqualTo("DOWN");
        assertThatThrownBy(()->queue.publish(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->queue.drain(1,value->{})).isInstanceOf(IllegalStateException.class);
    }
}
