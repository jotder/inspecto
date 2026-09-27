package com.gamma.pipeline.exec;

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
}
