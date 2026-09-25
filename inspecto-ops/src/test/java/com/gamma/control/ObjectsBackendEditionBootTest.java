package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.objects.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.util.StoreHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code OBJECTS-BACKEND-DEFAULT-MEMORY-1} (operator decision 2026-09-25), through the real boot path —
 * {@code SpaceManager.discover}, the way every {@code serve.*} launcher starts — once per edition setting:
 * <ul>
 *   <li><b>Professional</b> (and Personal/Preview): no {@code -Dobjects.backend} ⇒ the Space's own
 *       {@code duckdb/} files, and an Incident survives a restart.</li>
 *   <li><b>Enterprise</b>: {@code serve.*} passes {@code -Dobjects.backend=postgres}; with no PostgreSQL URL the
 *       boot is refused, naming the property — and an unreachable one is never papered over with memory.</li>
 *   <li><b>Enterprise {@code -DemoAuth}</b>: {@code serve-demo.*} passes {@code -Dobjects.backend=db} with
 *       {@code -Dauth.mode=demo}; it boots on DuckDB.</li>
 * </ul>
 * ⚠ The root pom pins {@code objects.backend=memory} for the test reactor, so each case sets or CLEARS the
 * property itself and restores every property it touched.
 */
class ObjectsBackendEditionBootTest {

    private static final String[] TOUCHED = {"objects.backend", "auth.mode", "objects.db.url",
            "objects.links.db.url", "objects.notes.db.url", "objects.tags.db.url"};
    private final Map<String, String> prior = new HashMap<>();

    @BeforeEach
    void remember() {
        for (String k : TOUCHED) prior.put(k, System.getProperty(k));
    }

    @AfterEach
    void restore() {
        for (String k : TOUCHED) {
            String v = prior.get(k);
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        }
    }

    private static void seedSpace(Path root, String id) throws Exception {
        Path config = root.resolve(id).resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
    }

    @Test
    void professionalDefault_isTheSpaceDuckdb_andAnIncidentSurvivesARestart(@TempDir Path root) throws Exception {
        System.clearProperty("objects.backend");          // the engine default — what a Professional bundle runs
        seedSpace(root, "s1");

        String id;
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            assertEquals(1, spaces.size(), "space booted");
            StoreHealth.Resolved objects = StoreHealth.of("s1").get("objects");
            assertEquals(StoreHealth.Status.UP, objects.status(), objects.toString());
            assertTrue(objects.target().startsWith("jdbc:duckdb:"), objects.target());
            OperationalObject inc = TestOpsEngine.of(spaces.space(SpaceId.of("s1")).orElseThrow().service())
                    .open(ObjectType.INCIDENT, "survives a restart", "d", "HIGH", "corr", Map.of());
            id = inc.id();
        }
        try (Stream<Path> files = Files.list(root.resolve("s1").resolve("duckdb"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("inspecto-ops")),
                    "the object store's DuckDB file lives in the Space's duckdb/");
        }
        try (SpaceManager again = SpaceManager.discover(root)) {
            assertTrue(TestOpsEngine.of(again.space(SpaceId.of("s1")).orElseThrow().service()).get(id).isPresent(),
                    "the Incident was persisted, not held in memory");
        }
    }

    @Test
    void enterpriseWithoutAPostgresUrl_refusesToBoot_namingTheProperty(@TempDir Path root) throws Exception {
        System.setProperty("objects.backend", "postgres");   // what the Enterprise serve.sh/serve.bat pass
        seedSpace(root, "s1");
        IllegalStateException boom = assertThrows(IllegalStateException.class, () -> SpaceManager.discover(root));
        assertTrue(boom.getMessage().contains("-Dinspecto.db.url"), boom.getMessage());
        assertTrue(boom.getMessage().contains("INSPECTO_DB_URL"), boom.getMessage());
    }

    @Test
    void enterpriseWithAnUnreachablePostgres_neverFallsBackToMemory(@TempDir Path root) throws Exception {
        System.setProperty("objects.backend", "postgres");
        for (String k : new String[]{"objects.db.url", "objects.links.db.url", "objects.notes.db.url", "objects.tags.db.url"})
            System.setProperty(k, "jdbc:postgresql://127.0.0.1:1/nothing_listens_here");
        seedSpace(root, "s1");
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            // The degrade-to-memory path would have booted the Space "healthy" with Cases in the heap.
            assertEquals(0, spaces.size(), "the Space is refused rather than served from memory");
        }
    }

    @Test
    void enterpriseDemoBuild_bootsOnTheSpaceDuckdb(@TempDir Path root) throws Exception {
        System.setProperty("objects.backend", "db");          // serve-demo.* passes both of these
        System.setProperty("auth.mode", "demo");
        seedSpace(root, "s1");
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            assertEquals(1, spaces.size(), "the demo build boots with no PostgreSQL");
            StoreHealth.Resolved objects = StoreHealth.of("s1").get("objects");
            assertEquals(StoreHealth.Status.UP, objects.status(), objects.toString());
            assertTrue(objects.target().startsWith("jdbc:duckdb:"), objects.target());
        }
    }
}
