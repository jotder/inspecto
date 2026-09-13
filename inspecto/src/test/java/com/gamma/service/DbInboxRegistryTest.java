package com.gamma.service;

import com.gamma.service.SpaceInboxAudit.InboxDecl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fleet-wide inbox roster — scale-out plan §5.3 (`INBOX-REGISTRY-CROSS-POD-1`).
 *
 * <p>🔴 The property under test is not "rows round-trip". It is that a collision between Spaces <b>no single
 * pod hosts together</b> becomes visible, and — just as important — that a Space which has moved or changed
 * does not leave a ghost behind that reports a collision with itself. A detector whose findings cannot be
 * trusted is worse than none, because an operator learns to ignore it.
 */
class DbInboxRegistryTest {

    /** Two pods, neither able to see the other's config, both publishing into one registry. */
    @Test
    void aCollisionBetweenSpacesOnDIFFERENTPodsBecomesVisible(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("inbox.duckdb");
        try (DbInboxRegistry podA = DbInboxRegistry.open(url, null, null, "pod-a")) {
            podA.publish("tenant-a", List.of(new InboxDecl("tenant-a", "orders", "/data/shared/inbox")));
        }
        try (DbInboxRegistry podB = DbInboxRegistry.open(url, null, null, "pod-b")) {
            podB.publish("tenant-b", List.of(new InboxDecl("tenant-b", "orders", "/data/shared/inbox")));

            List<String> findings = SpaceInboxAudit.sharedInboxFindings(podB.declarations());
            assertEquals(1, findings.size(), "the shared inbox is reported: " + findings);
            assertTrue(findings.get(0).contains("tenant-a/orders"),
                    "⛔ the Space this pod does NOT host must be named — that is the entire point: "
                            + findings.get(0));
            assertTrue(findings.get(0).contains("tenant-b/orders"), findings.get(0));
        }
    }

    /**
     * 🔴 The ghost case. A Space moving to another pod must not collide with the rows it left behind, so
     * {@link DbInboxRegistry#publish} REPLACES a Space's rows rather than adding to them.
     */
    @Test
    void aSpaceThatMovesPodsDoesNotCollideWithItsOwnStaleRows(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("inbox.duckdb");
        try (DbInboxRegistry old = DbInboxRegistry.open(url, null, null, "pod-a")) {
            old.publish("tenant-a", List.of(new InboxDecl("tenant-a", "orders", "/data/a/inbox")));
        }
        try (DbInboxRegistry moved = DbInboxRegistry.open(url, null, null, "pod-b")) {
            moved.publish("tenant-a", List.of(new InboxDecl("tenant-a", "orders", "/data/a/inbox")));

            assertEquals(1, moved.declarations().size(), "one Space, one pipeline, one row — not two");
            assertTrue(SpaceInboxAudit.sharedInboxFindings(moved.declarations()).isEmpty(),
                    "⛔ a Space must never be reported as colliding with itself");
        }
    }

    /** A pipeline whose {@code dirs.poll} was removed must stop being reported — which is why a Space with
     *  NO declarations still has to publish. */
    @Test
    void publishingNothingClearsTheSpacesEarlierDeclarations(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("inbox.duckdb");
        try (DbInboxRegistry reg = DbInboxRegistry.open(url, null, null, "pod-a")) {
            reg.publish("tenant-a", List.of(new InboxDecl("tenant-a", "orders", "/data/shared/inbox")));
            reg.publish("tenant-b", List.of(new InboxDecl("tenant-b", "orders", "/data/shared/inbox")));
            assertEquals(1, SpaceInboxAudit.sharedInboxFindings(reg.declarations()).size(),
                    "the collision exists first, or this test proves nothing");

            reg.publish("tenant-a", List.of());
            assertEquals(1, reg.declarations().size(), "tenant-a's row is gone, tenant-b's stands");
            assertTrue(SpaceInboxAudit.sharedInboxFindings(reg.declarations()).isEmpty(),
                    "removing the declaration removes the finding");
        }
    }

    /** A declaration with no {@code dirs.poll} is not a claim on a directory and must not become a row. */
    @Test
    void aPipelineWithNoPollDirIsNotRegistered(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("inbox.duckdb");
        try (DbInboxRegistry reg = DbInboxRegistry.open(url, null, null, "pod-a")) {
            reg.publish("tenant-a", List.of(
                    new InboxDecl("tenant-a", "no-inbox", null),
                    new InboxDecl("tenant-a", "blank-inbox", "   "),
                    new InboxDecl("tenant-a", "orders", "/data/a/inbox")));
            assertEquals(1, reg.declarations().size(), "only the pipeline that actually polls is recorded");
            assertEquals("orders", reg.declarations().get(0).pipeline());
        }
    }

    /** ⛔ Fail-open, like every audit seam: a dead connection is a WARN and an empty roster, never a throw
     *  that takes down a boot which otherwise succeeded. */
    @Test
    void aBrokenRegistryDegradesRatherThanFailingTheBoot(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("inbox.duckdb");
        DbInboxRegistry reg = DbInboxRegistry.open(url, null, null, "pod-a");
        reg.close();

        assertDoesNotThrow(() -> reg.publish("tenant-a",
                List.of(new InboxDecl("tenant-a", "orders", "/data/a/inbox"))));
        assertEquals(List.of(), reg.declarations(),
                "an unreadable roster is empty — the caller then audits its own Spaces, and never "
                        + "reports the fleet as healthy");
    }

    /** Reopening must not fail on the table it created last time, and must still see its rows. */
    @Test
    void theSchemaSurvivesAReopen(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("inbox.duckdb");
        try (DbInboxRegistry first = DbInboxRegistry.open(url, null, null, "pod-a")) {
            first.publish("tenant-a", List.of(new InboxDecl("tenant-a", "orders", "/data/a/inbox")));
        }
        try (DbInboxRegistry second = DbInboxRegistry.open(url, null, null, "pod-a")) {
            assertEquals(1, second.declarations().size(), "the roster is durable across a restart");
        }
    }
}
