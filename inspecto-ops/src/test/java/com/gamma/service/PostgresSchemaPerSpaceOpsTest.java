package com.gamma.service;

import com.gamma.objects.AnnotationKinds;
import com.gamma.objects.ObjectType;
import com.gamma.objects.TagAssignment;
import com.gamma.ops.DbObjectStore;
import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.DbLinkStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.DbNoteStore;
import com.gamma.ops.note.ObjectNote;
import com.gamma.ops.tag.DbTagAssignmentStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Schema-per-space on <b>real PostgreSQL</b> for the four operational-object families (objects / links / notes /
 * tags) with TWO Spaces and many concurrent writers per Space (BACKLOG "Postgres multi-user"). URLs come from the
 * production {@link OperationalDb#urlFor(OperationalDb.Family, SpaceRoot, String)} so the Space schema is applied
 * exactly as at boot. Enabled by {@code INSPECTO_TEST_PG_URL} / {@code -Dinspecto.test.pg.url}; skips PER TEST.
 * Only throwaway {@code space_pgops_*} schemas are created, and dropped afterwards.
 */
class PostgresSchemaPerSpaceOpsTest {

    private static final int WRITERS = 12;   // above the Hikari default pool size, so connections are reused

    private static String adminUrl;
    private static final List<String> schemas = new ArrayList<>();
    private static SpaceRoot a, b;

    @BeforeAll
    static void connect() {
        adminUrl = System.getProperty("inspecto.test.pg.url");
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
        if (adminUrl == null || adminUrl.isBlank()) return;
        String tag = Long.toHexString(System.nanoTime());
        a = SpaceRoot.under(Path.of("target", "spaces-x", "pgops-a-" + tag));
        b = SpaceRoot.under(Path.of("target", "spaces-x", "pgops-b-" + tag));
        schemas.add(OperationalDb.schemaFor(a.id()));
        schemas.add(OperationalDb.schemaFor(b.id()));
        withShared(() -> { OperationalDb.ensureSpaceSchemas(a); OperationalDb.ensureSpaceSchemas(b); });
    }

    @AfterAll
    static void drop() throws Exception {
        if (adminUrl == null || adminUrl.isBlank()) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            for (String sc : schemas) s.execute("DROP SCHEMA IF EXISTS " + sc + " CASCADE");
        }
    }

    @BeforeEach
    void requireServer() {
        assumeTrue(adminUrl != null && !adminUrl.isBlank(),
                "needs PostgreSQL: set INSPECTO_TEST_PG_URL or -Dinspecto.test.pg.url");
    }

    private static void withShared(Runnable body) {
        String[] keys = {"inspecto.db", "inspecto.db.url", "objects.backend"};
        String[] old = {System.getProperty(keys[0]), System.getProperty(keys[1]), System.getProperty(keys[2])};
        System.setProperty("inspecto.db", "postgres");
        System.setProperty("inspecto.db.url", adminUrl);
        System.setProperty("objects.backend", "postgres");   // the test reactor pins "memory"; the families must be ENABLED
        try { body.run(); }
        finally {
            for (int i = 0; i < keys.length; i++)
                if (old[i] == null) System.clearProperty(keys[i]); else System.setProperty(keys[i], old[i]);
        }
    }

    private static String url(OperationalDb.Family f, SpaceRoot s) {
        String[] out = new String[1];
        withShared(() -> out[0] = OperationalDb.urlFor(f, s, "jdbc:duckdb:unused"));
        return out[0];
    }

    /** Runs {@code n} tasks per Space simultaneously (all released by one latch); rethrows the first failure. */
    private static void race(int n, java.util.function.BiConsumer<SpaceRoot, Integer> task) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n * 2);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (SpaceRoot s : List.of(a, b))
                for (int i = 0; i < n; i++) {
                    int w = i;
                    fs.add(pool.submit((Callable<Void>) () -> { go.await(); task.accept(s, w); return null; }));
                }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void objects_sameIdsInTwoSpacesCoexist_andConcurrentPatchesStayInTheirSpace() throws Exception {
        try (DbObjectStore sa = DbObjectStore.open(url(OperationalDb.Family.OBJECTS, a), null, null);
             DbObjectStore sb = DbObjectStore.open(url(OperationalDb.Family.OBJECTS, b), null, null)) {
            // Same primary key in both Spaces: only schema separation lets both exist.
            sa.create(OperationalObject.builder(ObjectType.CASE).id("SHARED-ID").title("in-a").status("OPEN").build());
            sb.create(OperationalObject.builder(ObjectType.CASE).id("SHARED-ID").title("in-b").status("OPEN").build());
            assertEquals("in-a", sa.get("SHARED-ID").orElseThrow().title());
            assertEquals("in-b", sb.get("SHARED-ID").orElseThrow().title());

            ObjectService svcA = new ObjectService(sa), svcB = new ObjectService(sb);
            OperationalObject ca = svcA.open(ObjectType.CASE, "hot-a", "d", "HIGH", "LOW", "ops", null, null, Map.of());
            OperationalObject cb = svcB.open(ObjectType.CASE, "hot-b", "d", "HIGH", "LOW", "ops", null, null, Map.of());
            race(WRITERS, (s, w) -> {
                if (s == a) svcA.patch(ca.id(), null, null, null, Map.of("k" + w, "a"));
                else svcB.patch(cb.id(), null, null, null, Map.of("k" + w, "b"));
            });
            OperationalObject ra = sa.get(ca.id()).orElseThrow(), rb = sb.get(cb.id()).orElseThrow();
            for (int i = 0; i < WRITERS; i++) {
                assertEquals("a", ra.attributes().get("k" + i), "A lost patch k" + i);
                assertEquals("b", rb.attributes().get("k" + i), "B lost patch k" + i);
            }
            assertEquals(WRITERS, ra.version());
            assertEquals(WRITERS, rb.version());
            assertTrue(sa.get(cb.id()).isEmpty(), "B's object must not be visible in A");
            assertTrue(sb.get(ca.id()).isEmpty(), "A's object must not be visible in B");
        }
    }

    @Test
    void links_concurrentAppendsAreCountedPerSpace() throws Exception {
        try (DbLinkStore sa = DbLinkStore.open(url(OperationalDb.Family.LINKS, a), null, null);
             DbLinkStore sb = DbLinkStore.open(url(OperationalDb.Family.LINKS, b), null, null)) {
            race(WRITERS, (s, w) -> (s == a ? sa : sb).add(
                    ObjectLink.of("CASE-" + w, ObjectType.CASE, "INC-HUB", ObjectType.INCIDENT, "CONTAINS")));
            assertEquals(WRITERS, sa.incident("INC-HUB").size(), "A sees exactly its own edges");
            assertEquals(WRITERS, sb.incident("INC-HUB").size(), "B sees exactly its own edges");
        }
    }

    @Test
    void notes_concurrentAppendsAreCountedPerSpace() throws Exception {
        try (DbNoteStore sa = DbNoteStore.open(url(OperationalDb.Family.NOTES, a), null, null);
             DbNoteStore sb = DbNoteStore.open(url(OperationalDb.Family.NOTES, b), null, null)) {
            race(WRITERS, (s, w) -> (s == a ? sa : sb).add(ObjectNote.comment("OBJ-1", "u" + w, "note " + w)));
            assertEquals(WRITERS, sa.forObject("OBJ-1", null).size());
            assertEquals(WRITERS, sb.forObject("OBJ-1", null).size());
        }
    }

    @Test
    void tags_racingToApplyTheSameTagYieldOneEdgePerSpace_andAllCallersSucceed() throws Exception {
        try (DbTagAssignmentStore sa = DbTagAssignmentStore.open(url(OperationalDb.Family.TAGS, a), null, null);
             DbTagAssignmentStore sb = DbTagAssignmentStore.open(url(OperationalDb.Family.TAGS, b), null, null)) {
            race(WRITERS, (s, w) -> {
                DbTagAssignmentStore st = s == a ? sa : sb;
                st.add(new TagAssignment("urgent", AnnotationKinds.OBJECT, "obj-1", "u" + w, 1_000L + w));   // contended
                st.add(new TagAssignment("t" + w, AnnotationKinds.OBJECT, "obj-1", "u" + w, 1_000L + w));    // distinct
            });
            assertEquals(1, sa.forTag("urgent").size(), "one 'urgent' edge in A despite the race");
            assertEquals(1, sb.forTag("urgent").size(), "one 'urgent' edge in B despite the race");
            assertEquals(WRITERS + 1, sa.tagsOf(AnnotationKinds.OBJECT, "obj-1").size());
            assertEquals(WRITERS + 1, sb.tagsOf(AnnotationKinds.OBJECT, "obj-1").size());
        }
    }
}
