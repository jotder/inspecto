package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.CollectorConnectors;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.RemoteFile;
import com.gamma.config.safety.EgressRefusedException;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice S4 of {@code policy-narrowing-design.md}: the {@code EgressGate} at the act sites. A server Safety Policy
 * file ({@code -Dsystem.config.dir}) says which hosts a run may dial; each test pairs a refusal with an allowed twin
 * and asserts the OBSERVED socket/request count on a real loopback server, not just the exception.
 * T14 (a pipeline placed on disk, no save gate), T8 (a hop other than the target), T7 (the object-store request
 * gate; a 3xx is never followed - see {@code ObjectStoreEgressTest}), T12 (Kafka leader hosts).
 */
class SafetyPolicyEgressTest {

    private static final String CONN = "s4-sftp";
    private static final DiscoveryContext ALL = new DiscoveryContext(List.of("*"), List.of(), DiscoveryContext.UNBOUNDED);

    @TempDir Path tmp;

    private SshServer sshd;
    private int port;
    private final AtomicInteger sessions = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("sftproot"));
        Files.writeString(root.resolve("20200403_a.csv"), "ID,AMT,EVENT_DATE\na_1,1.0,2020-04-03\n");
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(KeyPairGenerator.getInstance("RSA").genKeyPair()));
        sshd.setPasswordAuthenticator((u, p, s) -> "user".equals(u) && "pw".equals(p));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
        sshd.addSessionListener(new SessionListener() {
            @Override public void sessionCreated(Session session) { sessions.incrementAndGet(); }
        });
        sshd.start();
        port = sshd.getPort();
    }

    @AfterEach
    void stop() throws Exception {
        System.clearProperty("system.config.dir");
        ConnectionRegistry.remove(CONN);
        ObjectStoreEgressFixture.reset();
        if (sshd != null) sshd.stop(true);
    }

    private void serverPolicy(String text) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("server"));
        Files.writeString(dir.resolve("safety-policy.toon"), text);
        System.setProperty("system.config.dir", dir.toString());
    }

    private void register(ConnectionProfile.Tunnel tunnel) {
        ConnectionRegistry.register(new ConnectionProfile(CONN, "sftp", "127.0.0.1", port, null, "/",
                "user", "pw", Map.of(), tunnel));
    }

    // ---- T14: a pipeline placed on disk without the save gate ------------------------------------------

    @Test
    void aPipelineOnDiskNamingAHostThePolicyDoesNotAllowDialsNothing() throws Exception {
        serverPolicy("allow:\n  hosts[1]: sftp.allowed.test\n");
        register(null);
        PipelineConfig cfg = pipeline("S4_REFUSED");
        Exception e = assertThrows(Exception.class, () -> CollectorProcessor.run(cfg));
        assertTrue(String.valueOf(e).contains("egress refused by the Safety Policy"), String.valueOf(e));
        assertEquals(0, sessions.get(), "the refused run never opened a socket");
    }

    @Test
    void theSameRunWithTheHostAllowedDials() throws Exception {
        serverPolicy("allow:\n  hosts[1]: 127.0.0.1\n");
        register(null);
        CollectorProcessor.run(pipeline("S4_ALLOWED"));
        assertTrue(sessions.get() > 0, "the allowed twin reached the endpoint");
    }

    // ---- T8: the bastion is a hop too ------------------------------------------------------------------

    @Test
    void aTargetAllowedButBastionNotIsRefusedBeforeAnySocket() throws Exception {
        serverPolicy("allow:\n  hosts[1]: 127.0.0.1\n");
        register(new ConnectionProfile.Tunnel("bastion.denied.test", 22, "u", null));
        PipelineConfig cfg = pipeline("S4_BASTION");
        EgressRefusedException e = assertThrows(EgressRefusedException.class, () -> CollectorConnectors.forConfig(cfg));
        assertTrue(e.getMessage().contains("bastion"), e.getMessage());
        assertEquals(0, sessions.get());
    }

    // ---- T7: the object-store request gate -------------------------------------------------------------

    @Test
    void anObjectStoreHostOutsideTheAllowSetIsRefusedAndTheStubSeesNothing() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", ex -> {
            requests.add(ex.getRequestURI().getPath());
            byte[] b = "ID\nr1\n".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        stub.start();
        try {
            ObjectStoreEgressFixture.allowLoopbackStubAsLan();
            RemoteFile a = new RemoteFile("a.csv", "a.csv", RemoteFile.SIZE_UNKNOWN, null, null, null, null);

            serverPolicy("allow:\n  hosts[1]: elsewhere.test\n");
            S3Connector refused = s3(stub.getAddress().getPort());
            AcquisitionException e = assertThrows(AcquisitionException.class, () -> refused.open(a));
            assertTrue(e.getMessage().contains("Safety Policy"), e.getMessage());
            assertEquals(List.of(), requests, "a refused request is never sent");

            serverPolicy("allow:\n  hosts[1]: 127.0.0.1\n");
            s3(stub.getAddress().getPort()).open(a).close();
            assertEquals(1, requests.size(), "the allowed twin is sent");
        } finally {
            stub.stop(0);
        }
    }

    private static S3Connector s3(int stubPort) {
        return new S3Connector(new ConnectionProfile("o", "s3", "127.0.0.1", stubPort, null, "bucket/in", "AK", "secret",
                Map.of("region", "us-east-1", "protocol", "http"), null));
    }

    // ---- T12: Kafka leader hosts -----------------------------------------------------------------------

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

    // ---- harness (the same minimal pipeline CollectorProcessorRemoteCycleTest loads) -------------------

    private PipelineConfig pipeline(String name) throws Exception {
        String d = tmp.toString().replace("\\", "/");
        Path schema = tmp.resolve("mini_schema.toon");
        Files.writeString(schema, """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """);
        String toon = "name: " + name + "\nversion: 1\n" + """
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              errors: %1$s/errors
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
              log_dir: %1$s/logs
            output:
              format: PARQUET
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%2$s"
              batch:
                max_files: 100
                max_bytes: 268435456
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, schema.toString().replace("\\", "/"))
                + "collector:\n  connector: sftp\n  connection: " + CONN + "\n";
        Path p = tmp.resolve(name.toLowerCase() + "_pipeline.toon");
        Files.writeString(p, toon);
        return PipelineConfig.load(p.toString());
    }
}
