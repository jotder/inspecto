package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.objects.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The operational-store half of the raw table browser, split out of {@code ControlApiDbBrowserTest} when
 * that class moved to {@code inspecto} (EDITION-GATED-TESTS-IN-WRONG-HOME-1). The browser itself —
 * {@link com.gamma.control.DbBrowserRoutes} with {@link com.gamma.sql.SqlGuard} and the sandboxed
 * {@link com.gamma.query.QueryExecutor} — is core, so its catalog/table/query/fail-closed coverage now runs
 * in the default reactor. This case cannot follow it: it needs a live {@code DbObjectStore} (a
 * {@code BrowsableStore}) behind {@code -Dobjects.backend=db}, which only exists when
 * {@code inspecto-ops} is on the classpath.
 */
class ControlApiDbBrowserOpsTablesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /** Boot a one-space container whose {@code data/} holds a 3-row Hive-partitioned Parquet store "orders". */
    private Ctx open(Path root) throws Exception {
        seedSpace(root, "s1");
        SpaceManager spaces = SpaceManager.discover(root);
        assertEquals(1, spaces.size(), "space booted");
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private void seedSpace(Path root, String id) throws Exception {
        Path base = root.resolve(id);
        Path config = base.resolve("config");
        Files.createDirectories(config.resolve("inbox"));   // empty at boot
        Files.createDirectories(base.resolve("duckdb"));    // where DB-backed operational stores open their files
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));   // dir-scan discovers *_pipeline.toon

        // A real 3-row Parquet store under the space data dir (what dataRoot() resolves to).
        Path dataDir = root.resolve(id).resolve("data");
        Path partition = dataDir.resolve("orders").resolve("dt=2026");
        Files.createDirectories(partition);
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("dbbrowser_ops_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES (1,'alice'),(2,'bob'),(3,'carol')) t(id,name)) TO '"
                    + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        // A dataset component that owns the store, so the catalog can name the owner.
        new ComponentStore(config.resolve("registry")).write("dataset", "orders_ds", Map.of("physicalRef", "orders"));
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** Phase 2: with objects on the DB backend, the operational tables browse through the live connection. */
    @Test
    void operationalTablesBrowsableWhenDbBacked(@TempDir Path root) throws Exception {
        String prior = System.getProperty("objects.backend");
        System.setProperty("objects.backend", "db");   // makes DbObjectStore live (a BrowsableStore)
        try (Ctx c = open(root)) {
            // a link target in the same space to satisfy the mandatory ≥1-link create contract
            OperationalObject target = TestOpsEngine
                    .of(c.spaces.space(SpaceId.of("s1")).orElseThrow().service())
                    .open(ObjectType.INCIDENT, "link target", "d", "HIGH", "corr", java.util.Map.of());

            // seed one row via the API (POST /objects defaults to an INCIDENT)
            HttpResponse<String> created = postJson(c.port, "/spaces/s1/objects",
                    "{\"title\":\"DB browser probe\",\"type\":\"INCIDENT\",\"links\":[{\"to\":\"" + target.id() + "\"}]}");
            assertTrue(created.statusCode() < 300, created.body());

            // catalog now carries the operational objects group alongside the parquet stores
            JsonNode groups = JSON.readTree(get(c.port, "/spaces/s1/db/catalog").body()).get("data").get("groups");
            JsonNode ops = null;
            for (JsonNode g : groups) if ("ops:objects".equals(g.get("id").asText())) ops = g;
            assertNotNull(ops, "operational objects group present: " + groups);
            assertEquals("operational", ops.get("kind").asText());
            boolean hasTable = false;
            for (JsonNode t : ops.get("tables")) if ("inspecto_ops_objects".equals(t.get("name").asText())) hasTable = true;
            assertTrue(hasTable, "inspecto_ops_objects listed: " + ops.get("tables"));

            // browse the table through the live connection (the probe + its mandatory link target = 2 rows)
            JsonNode data = JSON.readTree(get(c.port,
                    "/spaces/s1/db/table?group=ops:objects&name=inspecto_ops_objects").body()).get("data");
            assertEquals(2, data.get("rows").size());
            List<String> titles = new java.util.ArrayList<>();
            for (JsonNode row : data.get("rows")) titles.add(row.get("title").asText());
            assertTrue(titles.contains("DB browser probe"), titles.toString());

            // ad-hoc read-only SQL over the live connection
            JsonNode q = JSON.readTree(postJson(c.port, "/spaces/s1/db/query",
                    "{\"group\":\"ops:objects\",\"sql\":\"SELECT title FROM inspecto_ops_objects WHERE title = 'DB browser probe'\"}").body()).get("data");
            assertEquals("DB browser probe", q.get("rows").get(0).get("title").asText());

            // unknown table for the group → 404; a mutating statement → 422
            assertEquals(404, get(c.port,
                    "/spaces/s1/db/table?group=ops:objects&name=inspecto_bogus").statusCode());
            assertEquals(422, postJson(c.port, "/spaces/s1/db/query",
                    "{\"group\":\"ops:objects\",\"sql\":\"DELETE FROM inspecto_ops_objects\"}").statusCode());
        } finally {
            if (prior != null) System.setProperty("objects.backend", prior);
            else System.clearProperty("objects.backend");
        }
    }
}
