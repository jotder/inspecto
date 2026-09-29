package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.acquire.RemoteFile;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link KafkaConnector} against a REAL broker (ASSURE-PUSH-INGEST-1) — the check {@code MockConsumer} cannot
 * make: that assign/seek, the offset bounds and the drain behave on the wire.
 *
 * <p>⚠ SKIPS (never passes vacuously) unless a broker answers at {@code -Dkafka.it.bootstrap} (default
 * {@code localhost:9092}). No broker ships with the build or the CI image; to run it, start one, e.g.
 * {@code docker run -p 9092:9092 apache/kafka:3.8.0}, then run this class. The topic is auto-created by the
 * producer (the broker default), named uniquely per run.
 */
class KafkaRealBrokerTest {

    private static final String BOOTSTRAP = System.getProperty("kafka.it.bootstrap", "localhost:9092");

    @AfterEach
    void tearDown() {
        AcquisitionLedgers.use(null);
    }

    private static boolean brokerAnswers() {
        String[] hp = BOOTSTRAP.split(",")[0].trim().split(":");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void drainsAProducedBacklogAsOneSliceAndResumesFromTheCommittedFrontier(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(brokerAnswers(), "SKIPPED: no Kafka broker at " + BOOTSTRAP
                + " (set -Dkafka.it.bootstrap=host:port, or start one: docker run -p 9092:9092 apache/kafka:3.8.0)");
        String topic = "inspecto-it-" + UUID.randomUUID().toString().substring(0, 8);
        Properties pp = new Properties();
        pp.put("bootstrap.servers", BOOTSTRAP);
        pp.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        pp.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(pp)) {
            for (int i = 0; i < 5; i++) producer.send(new ProducerRecord<>(topic, 0, "k" + i, "v" + i)).get();
        }

        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        ConnectionProfile profile = new ConnectionProfile("kafka-it", "kafka", null, 0, null, null, null, null,
                Map.of("topic", topic, "bootstrap_servers", BOOTSTRAP), null);
        DiscoveryContext all = new DiscoveryContext(List.of("*"), List.of(), DiscoveryContext.UNBOUNDED);
        try (KafkaConnector c = new KafkaConnector(profile, KafkaConsumer::new)) {
            assertEquals(5, c.pendingRecords());
            List<RemoteFile> found = c.discover(all);
            assertEquals(List.of(topic + "-p0-0-5.ndjson"), found.stream().map(RemoteFile::relativePath).toList());
            Path dest = c.fetchTo(found.getFirst(), dir.resolve(found.getFirst().relativePath()));
            assertEquals(5, Files.readAllLines(dest).size());
            assertEquals(0, c.pendingRecords(), "the uncommitted slice fences its partition");
            var wm = AcquisitionLedgers.takeDbWatermark(dest).orElseThrow();
            ledger.recordDbWatermark(wm.key(), wm.value());
            assertEquals("5", wm.value());
            assertTrue(c.discover(all).isEmpty(), "committed to the end — nothing left");
        }
    }
}
