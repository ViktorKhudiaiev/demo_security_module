package com.demo.securityapp.delivery;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.store.kahadb.KahaDBPersistenceAdapter;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** VM-only Classic broker. Its persistent journal shares the trusted processor host's fate. */
public final class EmbeddedActiveMqDelivery implements OperationDelivery, AutoCloseable {
    public static final String QUEUE = "integrity.operation-hints.v1";
    public static final String INVALID_QUEUE = "integrity.invalid-hints.v1";
    private final BrokerService broker = new BrokerService();
    private final Object producerLock = new Object();
    private final Object consumerLock = new Object();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong redelivered = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private Connection connection;
    private Session producerSession;
    private Session consumerSession;
    private MessageProducer producer;
    private MessageProducer invalidProducer;
    private MessageConsumer consumer;
    private volatile boolean closed;
    private volatile boolean failed;

    public EmbeddedActiveMqDelivery(Path directory, String brokerName) {
        try {
            broker.setBrokerName(brokerName);
            broker.setPersistent(true);
            broker.setUseJmx(false);
            broker.setUseShutdownHook(false);
            broker.setAdvisorySupport(false);
            broker.setSchedulerSupport(false);
            broker.setDeleteAllMessagesOnStartup(false);
            broker.setDataDirectoryFile(directory.toAbsolutePath().toFile());
            KahaDBPersistenceAdapter store = new KahaDBPersistenceAdapter();
            store.setDirectory(directory.resolve("kahadb").toAbsolutePath().toFile());
            store.setJournalDiskSyncStrategy("always");
            store.setConcurrentStoreAndDispatchQueues(false);
            store.setUseLock(true);
            store.getLocker().setFailIfLocked(true);
            broker.setPersistenceAdapter(store);
            broker.getSystemUsage().getMemoryUsage().setLimit(32L * 1024 * 1024);
            broker.getSystemUsage().getStoreUsage().setLimit(512L * 1024 * 1024);
            broker.getSystemUsage().getTempUsage().setLimit(64L * 1024 * 1024);
            broker.getSystemUsage().setSendFailIfNoSpaceAfterTimeout(3000);
            // No TCP connector or web console. create=false forbids an accidental second broker.
            broker.start();
            if (!broker.waitUntilStarted(10_000)) throw new IllegalStateException("Broker startup timed out");
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(
                    "vm://" + brokerName + "?create=false&waitForStart=10000");
            factory.setUseAsyncSend(false);
            factory.setAlwaysSyncSend(true);
            factory.setSendTimeout(5000);
            factory.setRequestTimeout(5000);
            factory.setCloseTimeout(5000);
            factory.setWatchTopicAdvisories(false);
            factory.getPrefetchPolicy().setQueuePrefetch(1);
            factory.getRedeliveryPolicy().setMaximumRedeliveries(-1);
            factory.getRedeliveryPolicy().setInitialRedeliveryDelay(1000);
            factory.getRedeliveryPolicy().setUseExponentialBackOff(true);
            factory.getRedeliveryPolicy().setBackOffMultiplier(2);
            factory.getRedeliveryPolicy().setMaximumRedeliveryDelay(10_000);
            connection = factory.createConnection();
            connection.setExceptionListener(error -> failed = true);
            producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            consumerSession = connection.createSession(true, Session.SESSION_TRANSACTED);
            producer = producerSession.createProducer(producerSession.createQueue(QUEUE));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.setTimeToLive(0);
            consumer = consumerSession.createConsumer(consumerSession.createQueue(QUEUE));
            invalidProducer = consumerSession.createProducer(consumerSession.createQueue(INVALID_QUEUE));
            invalidProducer.setDeliveryMode(DeliveryMode.PERSISTENT);
            connection.start();
        } catch (Exception e) {
            close();
            throw new IllegalStateException("Embedded broker could not start", e);
        }
    }

    @Override public void publish(UUID id) {
        synchronized (producerLock) {
            requireOpen();
            try {
                TextMessage message = producerSession.createTextMessage(id.toString());
                message.setIntProperty("protocolVersion", 1);
                producer.send(message);
                accepted.incrementAndGet();
            } catch (JMSException e) {
                throw new IllegalStateException("Durable queue acceptance failed; retain source hint", e);
            }
        }
    }

    @Override public int drain(int limit, Consumer<UUID> handler) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Drain limit must be 1..1000");
        synchronized (consumerLock) {
            requireOpen();
            int count = 0;
            try {
                for (; count < limit; count++) {
                    Message message = count == 0 ? consumer.receive(100) : consumer.receiveNoWait();
                    if (message == null) break;
                    UUID id = validatedId(message);
                    if (id == null) {
                        // Never deserialize ObjectMessage or echo untrusted content into logs.
                        TextMessage rejected = consumerSession.createTextMessage("Invalid operation-hint envelope");
                        invalidProducer.send(rejected);
                        consumerSession.commit();
                        invalid.incrementAndGet();
                        continue;
                    }
                    if (message.getJMSRedelivered()) redelivered.incrementAndGet();
                    handler.accept(id); // The protected SQL insert commits before JMS commit.
                    consumerSession.commit();
                    delivered.incrementAndGet();
                }
                return count;
            } catch (Throwable e) {
                // Even an Error must not leave a failed receive in the next successful commit.
                try { consumerSession.rollback(); } catch (Throwable rollback) { failed=true; e.addSuppressed(rollback); }
                if (e instanceof Error error) throw error;
                throw new IllegalStateException("Queue delivery deferred; durable hint will be retried", e);
            }
        }
    }

    private static UUID validatedId(Message message) throws JMSException {
        if (!(message instanceof TextMessage text)) return null;
        if (!Integer.valueOf(1).equals(message.getObjectProperty("protocolVersion"))) return null;
        String value = text.getText();
        if (value == null || value.length() != 36) return null;
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? id : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    private void requireOpen() {
        if (closed || failed || !broker.isStarted() || broker.isStopping()) throw new IllegalStateException("Broker unavailable");
    }

    @Override public Map<String, Object> health() {
        return Map.of("status", !closed && !failed && broker.isStarted() && !broker.isStopping() ? "UP" : "DOWN",
                "transport", "embedded-vm", "persistent", true,
                "acceptedSinceStart", accepted.get(), "deliveredSinceStart", delivered.get(),
                "redeliveriesSinceStart", redelivered.get(), "invalidSinceStart", invalid.get());
    }

    @Override public void close() {
        closed = true;
        try { if (connection != null) connection.close(); } catch (JMSException ignored) { }
        try { broker.stop(); broker.waitUntilStopped(); }
        catch (Exception e) { throw new IllegalStateException("Embedded broker shutdown failed", e); }
    }
}
