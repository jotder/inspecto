package com.gamma.acquire.kafka;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.RemoteFile;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T12 of {@code policy-narrowing-design.md} (Kafka leader hosts): split out of the core connectors module's
 * {@code SafetyPolicyEgressTest} when the Kafka connector became its own module.
 */
class KafkaEgressTest {

    private static final DiscoveryContext ALL = new DiscoveryContext(List.of("*"), List.of(), DiscoveryContext.UNBOUNDED);

    @TempDir Path tmp;

    @AfterEach
    void stop() {
        System.clearProperty("system.config.dir");
    }

    private void serverPolicy(String text) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("server"));
        Files.writeString(dir.resolve("safety-policy.toon"), text);
        System.setProperty("system.config.dir", dir.toString());
    }

    private static KafkaConnector kafka(MockConsumer<byte[], byte[]> mock) {
        Map<String, String> options = new HashMap<>(Map.of("topic", "t"));
        return new KafkaConnector(new ConnectionProfile("k", "kafka", "127.0.0.1", 9092, null, null, null, null,
                options, null), props -> mock);
    }

    @Test
    void aPartitionLeaderOutsideTheAllowSetIsNeverFetchedFrom() throws Exception {
        serverPolicy("allow:\n  hosts[1]: 127.0.0.1\n");
        MockConsumer<byte[], byte[]> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        mock.updatePartitions("t", List.of(new PartitionInfo("t", 0, new Node(7, "broker7.denied.test", 9092), null, null)));
        try (KafkaConnector c = kafka(mock)) {
            AcquisitionException e = assertThrows(AcquisitionException.class, () -> c.discover(ALL));
            assertTrue(e.getMessage().contains("broker7.denied.test"), e.getMessage());
            Path dest = tmp.resolve("slice.ndjson");
            assertThrows(AcquisitionException.class, () -> c.fetchTo(
                    new RemoteFile("t-p0-0-1.ndjson", "t-p0-0-1.ndjson", -1, null, null, null, null), dest));
            assertFalse(Files.exists(dest), "no fetch, no file");
            assertTrue(mock.assignment().isEmpty(), "the consumer never assigned the partition");
        }
    }

    @Test
    void aLeaderInsideTheAllowSetPassesTheGate() throws Exception {
        serverPolicy("allow:\n  hosts[1]: 127.0.0.1\n");
        MockConsumer<byte[], byte[]> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        mock.updatePartitions("t", List.of(new PartitionInfo("t", 0, new Node(7, "127.0.0.1", 9092), null, null)));
        TopicPartition tp = new TopicPartition("t", 0);
        mock.updateBeginningOffsets(Map.of(tp, 0L));
        mock.updateEndOffsets(Map.of(tp, 0L));
        try (KafkaConnector c = kafka(mock)) {
            assertEquals(List.of(), c.discover(ALL));
        }
    }
}
