package com.gamma.pipeline.exec;

import com.gamma.util.egress.EgressPolicy;

import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EgressAllowlist}'s one-time seeding (WEBHOOK-EGRESS-POLICY-1, operator 2026-09-27): a Space with no
 * {@code egress.toon} is seeded from the hosts its webhooks target today, persisted, and never seeded again.
 */
class EgressAllowlistTest {

    @TempDir
    Path root;

    @AfterEach
    void clearProperty() {
        System.clearProperty("notify.webhook.url");
        EgressAllowlist.clearLaunchConfig();
    }

    private void write(String rel, Map<String, Object> doc) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, JToon.encode(doc));
    }

    private void space() throws Exception {
        write("cbs_connection.toon", Map.of("connection", Map.of("id", "cbs", "connector", "https", "host", "CBS.internal")));
        write("pcrf_connection.toon", Map.of("connection", Map.of("id", "pcrf", "connector", "https", "host", "10.20.0.5")));
        write("unused_connection.toon", Map.of("connection", Map.of("id", "unused", "connector", "https", "host", "tickets.internal")));
        write("orders.toon", Map.of("pipeline", "orders", "webhook", Map.of("connection", "cbs")));
        write("pipelines/graph.toon", Map.of("nodes", List.of(Map.of("id", "w", "type", "sink.webhook",
                "config", Map.of("connection", "pcrf")))));
        write("registry/channels/ops.toon", Map.of("id", "ops", "kind", "WEBHOOK", "target", "https://alerts.internal/hook"));
        write("registry/channels/mail.toon", Map.of("id", "mail", "kind", "EMAIL", "target", "ops@mail.internal"));
        System.setProperty("notify.webhook.url", "http://notify.internal:8080/in");
    }

    @Test
    void theBootMigrationSeedsFromTheSpacesCurrentWebhookTargetsAndPersistsIt() throws Exception {
        space();
        EgressAllowlist.migrate(root);
        List<String> seeded = EgressAllowlist.entries(root);
        assertEquals(java.util.Set.of("cbs.internal", "10.20.0.5", "alerts.internal", "notify.internal"),
                java.util.Set.copyOf(seeded));
        assertEquals(4, seeded.size());
        assertFalse(seeded.contains("tickets.internal"), "a Connection no webhook Step names is not a target");
        Map<String, Object> onDisk = ToonHelper.load(root.resolve(EgressAllowlist.FILE).toString());
        assertEquals(seeded, onDisk.get("allow"));
        assertNotNull(onDisk.get("seededAt"), "the file records that it was seeded");

        EgressPolicy.Allowlist allow = EgressAllowlist.of(root);
        EgressPolicy.Resolver net = h -> new InetAddress[] {InetAddress.ofLiteral("10.1.1.1")};
        assertDoesNotThrow(() -> EgressPolicy.resolve("cbs.internal", allow, net), "a target that worked still works");
        assertThrows(EgressPolicy.Refused.class, () -> EgressPolicy.resolve("new.internal", allow, net),
                "a NEW private target needs an explicit entry");
    }

    @Test
    void aLaterPutThatRemovesEntriesIsNeverReSeeded() throws Exception {
        space();
        EgressAllowlist.migrate(root);
        assertFalse(EgressAllowlist.entries(root).isEmpty());
        Files.writeString(root.resolve(EgressAllowlist.FILE), JToon.encode(Map.of("allow", List.of("cbs.internal"))));
        assertEquals(List.of("cbs.internal"), EgressAllowlist.entries(root));
        EgressAllowlist.migrate(root);   // the next boot
        assertEquals(List.of("cbs.internal"), EgressAllowlist.entries(root), "stable across later boots");
    }

    @Test
    void aSpaceWithNoWebhookTargetsIsSeededEmptyAndThenStaysRecorded() throws Exception {
        EgressAllowlist.migrate(root);
        assertEquals(List.of(), EgressAllowlist.entries(root));
        assertTrue(Files.exists(root.resolve(EgressAllowlist.FILE)), "an empty seed is still recorded");
        write("late.toon", Map.of("webhook", Map.of("connection", "cbs")));
        write("cbs_connection.toon", Map.of("connection", Map.of("id", "cbs", "connector", "https", "host", "cbs.internal")));
        EgressAllowlist.migrate(root);
        assertEquals(List.of(), EgressAllowlist.entries(root), "a target added after the seed needs an explicit entry");
    }

    @Test
    void aTargetThatCanNeverBeAllowlistedIsSkipped() throws Exception {
        write("lo_connection.toon", Map.of("connection", Map.of("id", "lo", "connector", "https", "host", "127.0.0.1")));
        write("p.toon", Map.of("webhook", Map.of("connection", "lo")));
        EgressAllowlist.migrate(root);
        assertEquals(List.of(), EgressAllowlist.entries(root));
    }

    /** The lazy read path NEVER seeds: no file means EMPTY, and no file is written. */
    @Test
    void theReadPathNeverSeeds() throws Exception {
        space();
        assertEquals(List.of(), EgressAllowlist.entries(root));
        assertFalse(Files.exists(root.resolve(EgressAllowlist.FILE)));
    }

    /** Object-store Connections dial their host through the policy too, so a MinIO on a LAN is seeded at boot. */
    @Test
    void theBootMigrationAlsoSeedsEveryObjectStoreConnectionHost() throws Exception {
        write("minio_connection.toon", Map.of("connection", Map.of("id", "minio", "connector", "s3", "host", "MinIO.lan")));
        write("conns/blob_connection.toon", Map.of("connection", Map.of("id", "blob", "connector", "azure", "host", "10.30.0.7")));
        write("gcs_connection.toon", Map.of("connection", Map.of("id", "gcs", "connector", "gcs", "host", "gcs.lan")));
        write("db_connection.toon", Map.of("connection", Map.of("id", "db", "connector", "postgres", "host", "db.lan")));
        EgressAllowlist.migrate(root);
        assertEquals(java.util.Set.of("minio.lan", "10.30.0.7", "gcs.lan"), java.util.Set.copyOf(EgressAllowlist.entries(root)),
                "every s3/gcs/azure Connection host is seeded; a non-object-store Connection is not a target");
    }

    @Test
    void theReadPathNeverSeedsObjectStoreHostsEither() throws Exception {
        write("minio_connection.toon", Map.of("connection", Map.of("id", "minio", "connector", "s3", "host", "minio.lan")));
        assertEquals(List.of(), EgressAllowlist.entries(root));
        assertFalse(Files.exists(root.resolve(EgressAllowlist.FILE)));
    }

    /** A Space created through the product is recorded EMPTY, so a later boot never seeds it. */
    @Test
    void aSpaceRecordedEmptyAtCreateIsNeverSeeded() throws Exception {
        EgressAllowlist.recordEmpty(root);
        space();   // its author adds a private Connection + webhook Step before any boot
        EgressAllowlist.migrate(root);
        assertEquals(List.of(), EgressAllowlist.entries(root));
    }

    /** If the seed cannot be written nothing is allowed (fail closed). */
    @Test
    void aSeedThatCannotBePersistedAllowsNothing() throws Exception {
        System.setProperty("notify.webhook.url", "http://notify.internal/in");
        Path notADir = Files.writeString(root.resolve("blocker"), "x").resolve("config");
        EgressAllowlist.migrate(notADir);
        assertEquals(List.of(), EgressAllowlist.entries(notADir));
    }

    @Test
    void noRootMeansEmpty() {
        assertEquals(List.of(), EgressAllowlist.entries(null));
    }

    /**
     * Session decision 2026-09-27: with NO writable config root the launch config is trusted as an IN-MEMORY
     * allowlist — its LAN store is allowed, a host it does not name is not, and nothing is written.
     */
    @Test
    void withNoWriteRootTheLaunchConfigIsTrustedInMemoryOnly() throws Exception {
        String prior = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        try {
            write("minio_connection.toon", Map.of("connection", Map.of("id", "minio", "connector", "s3", "host", "minio.lan")));
            write("orders.toon", Map.of("pipeline", "orders", "webhook", Map.of("connection", "cbs")));
            write("cbs_connection.toon", Map.of("connection", Map.of("id", "cbs", "connector", "https", "host", "cbs.lan")));
            EgressAllowlist.bootDefaultSpace(root, List.of("blob.lan"));
            InetAddress lan = InetAddress.ofLiteral("10.1.2.3");
            EgressPolicy.Resolver toLan = h -> new InetAddress[] {lan};
            EgressPolicy.Allowlist in = EgressAllowlist.forCurrentSpace();
            for (String ok : List.of("minio.lan", "cbs.lan", "blob.lan"))
                assertEquals(lan, EgressPolicy.resolve(ok, in, toLan), ok);
            assertThrows(EgressPolicy.Refused.class, () -> EgressPolicy.resolve("elsewhere.lan", in, toLan),
                    "a host the launch config does not name stays denied");
            assertFalse(Files.exists(root.resolve(EgressAllowlist.FILE)), "never persisted");
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
        }
    }

    /** With a writable root the persisted one-time migration applies unchanged — no in-memory trust. */
    @Test
    void withAWriteRootTheBootIsThePersistedMigration() throws Exception {
        String prior = System.getProperty("assist.write.root");
        Path writeRoot = root.resolve("write");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            Path launch = Files.createDirectories(root.resolve("launch"));
            Files.writeString(launch.resolve("minio_connection.toon"),
                    JToon.encode(Map.of("connection", Map.of("id", "minio", "connector", "s3", "host", "launch-only.lan"))));
            EgressAllowlist.bootDefaultSpace(launch, List.of("minio.lan"));
            assertEquals(List.of("minio.lan"), EgressAllowlist.entries(writeRoot),
                    "the write root is seeded from its own targets plus the loaded Connections, not the launch dir scan");
            assertFalse(EgressAllowlist.forCurrentSpace().namesHost("launch-only.lan"));
        } finally {
            if (prior == null) System.clearProperty("assist.write.root");
            else System.setProperty("assist.write.root", prior);
        }
    }

    /** A single-tenant server's launch-config Connections live outside the write root; their hosts are passed in. */
    @Test
    void theBootMigrationSeedsObjectStoreHostsLoadedFromOutsideTheRoot() {
        EgressAllowlist.migrate(root, List.of("MinIO.LAN", " ", "10.9.9.9"));
        assertEquals(List.of("minio.lan", "10.9.9.9"), EgressAllowlist.entries(root));
        assertEquals("minio.lan", EgressAllowlist.objectStoreHost(new com.gamma.acquire.ConnectionProfile(
                "m", "s3", "MinIO.lan", 9000, null, null, null, null, Map.of(), null)));
        assertNull(EgressAllowlist.objectStoreHost(new com.gamma.acquire.ConnectionProfile(
                "h", "https", "cbs.lan", 443, null, null, null, null, Map.of(), null)));
    }
}
