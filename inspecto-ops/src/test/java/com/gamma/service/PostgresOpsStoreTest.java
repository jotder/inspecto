package com.gamma.service;

import com.gamma.objects.AnnotationKinds;
import com.gamma.objects.ObjectType;
import com.gamma.objects.TagAssignment;
import com.gamma.ops.DbObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.DbLinkStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.DbNoteStore;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.ObjectNote;
import com.gamma.ops.tag.DbTagAssignmentStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DAT-6, the {@code com.gamma.ops} half — the four operational stores (object / link / note /
 * tag-assignment) on <b>real PostgreSQL</b>. Split out of {@code PostgresStateStoreTest} when that class
 * moved to {@code inspecto} so the dialect coverage for the core stores could run in a default
 * {@code mvn -o clean test} (EDITION-GATED-TESTS-IN-WRONG-HOME-1). These four cannot follow it: their
 * stores live in {@code inspecto-ops}, which is {@code -Pedition-standard} and up.
 *
 * <p>Enable exactly as the core class does — {@code -Dinspecto.test.pg.url=…} or the environment variable
 * {@code INSPECTO_TEST_PG_URL}; with neither, every test reports SKIPPED <em>with the reason</em>. See
 * {@code PostgresStateStoreTest}'s javadoc for the runbook and the three Windows traps (the {@code &} in a
 * {@code -D} URL, the {@code Asia/Calcutta} zone alias, and {@code -DargLine} never reaching the fork).
 *
 * <p>🔴 The assertions are exact counts, so each run creates its own uniquely-named SCHEMA and drops it
 * afterwards. Never let this test write into the caller's default schema.
 */
class PostgresOpsStoreTest {

    /** System property (or {@code INSPECTO_TEST_PG_URL} env var) naming the server to test against. */
    private static final String URL_PROPERTY = "inspecto.test.pg.url";

    private static String adminUrl;
    private static String schema;
    private static String url;

    @BeforeAll
    static void connectToAnExistingPostgres() throws Exception {
        adminUrl = System.getProperty(URL_PROPERTY);
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
        if (adminUrl == null || adminUrl.isBlank()) return;   // unconfigured: @BeforeEach reports the skip

        schema = "inspecto_test_ops_" + Long.toHexString(System.nanoTime());
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        url = adminUrl + (adminUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    /**
     * The skip lives HERE, not in {@link #connectToAnExistingPostgres()}, and that is deliberate: an
     * {@code assumeTrue} inside {@code @BeforeAll} aborts the whole container and surefire then reports
     * "Tests run: 0, Skipped: 0" — the class disappears from the totals with no trace of why. Per-test,
     * every method is reported as SKIPPED <em>with this message</em>.
     */
    @BeforeEach
    void requireAConfiguredServer() {
        assumeTrue(url != null,
                "DAT-6 needs a PostgreSQL server: pass -D" + URL_PROPERTY
                        + "=jdbc:postgresql://host:5432/db?user=…&password=… or set INSPECTO_TEST_PG_URL. "
                        + "The embedded harness was removed 2026-09-07 — only the JDBC client driver ships.");
    }

    @AfterAll
    static void dropTheThrowawaySchema() throws Exception {
        if (schema == null || adminUrl == null) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void objectStore_createUpdateQueryRoundTrip() throws Exception {
        try (DbObjectStore store = DbObjectStore.open(url, null, null)) {
            OperationalObject obj = OperationalObject.builder(ObjectType.ALERT)
                    .id("PG-ALERT-1").title("disk full").status("OPEN").severity("HIGH")
                    .owner("ops").attr("rule", "disk>90").build();
            store.create(obj);

            Optional<OperationalObject> got = store.get("PG-ALERT-1");
            assertTrue(got.isPresent(), "object read back from Postgres");
            assertEquals("disk full", got.get().title());
            assertEquals("disk>90", got.get().attributes().get("rule"), "JSON attributes survived");

            store.update(got.get().withStatus("RESOLVED", System.currentTimeMillis(), true));
            assertEquals("RESOLVED", store.get("PG-ALERT-1").orElseThrow().status());

            List<OperationalObject> hits = store.query(
                    new ObjectQuery(ObjectType.ALERT, "RESOLVED", null, null, null, null, null, 10, 0));
            assertTrue(hits.stream().anyMatch(o -> o.id().equals("PG-ALERT-1")), "query filter matched");
        }
    }

    @Test
    void linkStore_appendAndReadRoundTrip() throws Exception {
        try (DbLinkStore store = DbLinkStore.open(url, null, null)) {
            store.add(ObjectLink.of("CASE-1", ObjectType.CASE, "INC-1", ObjectType.INCIDENT, "CONTAINS"));
            store.add(ObjectLink.of("INC-1", ObjectType.INCIDENT, "ALERT-1", ObjectType.ALERT, "ESCALATED_FROM"));

            List<ObjectLink> incident = store.incident("INC-1");
            assertEquals(2, incident.size(), "both edges touching INC-1 read back from Postgres");
            assertTrue(store.all(10).size() >= 2);
        }
    }

    @Test
    void noteStore_appendAndReadRoundTrip() throws Exception {
        try (DbNoteStore store = DbNoteStore.open(url, null, null)) {
            store.add(ObjectNote.comment("PG-ALERT-1", "alice", "looking into it"));
            store.add(ObjectNote.attachment("PG-ALERT-1", "bob", "log.txt", "text/plain", "s3://x", "the log"));

            List<ObjectNote> all = store.forObject("PG-ALERT-1", null);
            assertEquals(2, all.size(), "both notes read back from Postgres");
            List<ObjectNote> comments = store.forObject("PG-ALERT-1", NoteKind.COMMENT);
            assertEquals(1, comments.size(), "kind filter worked");
            assertEquals("the log", store.forObject("PG-ALERT-1", NoteKind.ATTACHMENT).get(0).body());
        }
    }

    /**
     * D10 on Postgres: the {@code target_kind} column + backfill land through the same
     * {@code ADD COLUMN IF NOT EXISTS} migration, a legacy row reads back as {@code object}, and two
     * families sharing an id stay separated.
     */
    @Test
    void noteStore_mixedTargetKindsAndLegacyRowMigration() throws Exception {
        String legacyTable = "inspecto_ops_notes";
        try (java.sql.Connection conn = com.gamma.util.JdbcDrivers.connect(url, null, null);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + legacyTable);
            st.execute("CREATE TABLE " + legacyTable + " (id VARCHAR PRIMARY KEY, object_id VARCHAR, "
                    + "kind VARCHAR, author VARCHAR, body VARCHAR, attributes VARCHAR, created_at BIGINT)");
            st.execute("INSERT INTO " + legacyTable
                    + " VALUES ('PG-OLD','PG-VIEW-1','COMMENT','alice','legacy note','',50)");
        }

        try (DbNoteStore store = DbNoteStore.open(url, null, null)) {          // ← runs the migration
            List<ObjectNote> migrated = store.forObject("PG-VIEW-1", null);
            assertEquals(1, migrated.size(), "legacy row survives the migration");
            assertEquals("object", migrated.get(0).targetKind(), "and is backfilled to 'object'");

            store.add(ObjectNote.comment("link-analysis-view", "PG-VIEW-1", "bob", "odd cluster"));
            assertEquals(1, store.forObject("PG-VIEW-1", null).size(), "same id, other family — no bleed");
            List<ObjectNote> onView = store.forTarget("link-analysis-view", "PG-VIEW-1", null);
            assertEquals(1, onView.size());
            assertEquals("odd cluster", onView.get(0).body());
        }
    }

    @Test
    void tagAssignmentStore_addIsIdempotentAndReadsBack() throws Exception {
        try (DbTagAssignmentStore store = DbTagAssignmentStore.open(url, null, null)) {
            TagAssignment first = store.add(
                    new TagAssignment("urgent", AnnotationKinds.OBJECT, "obj-1", "alice", 1_000L));
            assertEquals("alice", first.actor(), "the stored edge round-tripped from Postgres");

            // Re-tagging returns the ALREADY-STORED edge — the checked-then-inserted path, not a rewrite.
            TagAssignment again = store.add(
                    new TagAssignment("urgent", AnnotationKinds.OBJECT, "obj-1", "bob", 9_000L));
            assertEquals("alice", again.actor(), "re-tagging must not rewrite who applied it");
            assertEquals(1_000L, again.createdAt(), "nor when");

            assertEquals(List.of("urgent"), store.tagsOf(AnnotationKinds.OBJECT, "obj-1"),
                    "SELECT DISTINCT … ORDER BY tag works on Postgres");
            assertEquals(1, store.forTag("urgent").size(), "forTag read back through Postgres");

            assertTrue(store.remove("urgent", AnnotationKinds.OBJECT, "obj-1"), "delete reports a hit");
            assertTrue(store.tagsOf(AnnotationKinds.OBJECT, "obj-1").isEmpty(), "and the edge is gone");
        }
    }
}
