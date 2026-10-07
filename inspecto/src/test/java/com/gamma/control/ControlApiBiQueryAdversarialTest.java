package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import com.gamma.access.ComponentAccess;

import static org.junit.jupiter.api.Assertions.*;

class ControlApiBiQueryAdversarialTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String id = a.substring(7);
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        return Optional.of(new Subject(id, Set.of("canAuthorWorkbench")));
    };
    @AfterEach void tearDown() { Authenticators.forTest(null); }

    record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }
    Ctx open(Path cfg, Path root, boolean auth) throws Exception {
        Authenticators.forTest(auth ? FAKE : null);
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0); api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally { System.clearProperty("assist.write.root"); }
    }
    void seed(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('EU',30.0),('US',5.0)) AS t(region,amount)", "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view"));
    }
    HttpResponse<String> post(Ctx c, String user, String q) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/bi/query"))
                .method("POST", HttpRequest.BodyPublishers.ofString(
                        "{\"dataset\":\"sales_ds\",\"query\":\"" + q + "\",\"measures\":[{\"agg\":\"count\"}]}"));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void hiddenQueryIs404ForNonShareHolder(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            seed(c);
            new ComponentStore(c.root.resolve("registry")).write("query", "secret", Map.of("type", "sql",
                    "datasetId", "sales_ds", "text", "SELECT * FROM sales_ds", "owner", "alice", "shares", List.of()));
            var bob = post(c, "bob", "secret");
            assertEquals(404, bob.statusCode(), bob.body());
            assertTrue(bob.body().contains("no query 'secret'"), bob.body());
            var alice = post(c, "alice", "secret");
            assertEquals(200, alice.statusCode(), alice.body());
        }
    }

    @Test void injectionPayloadsAreRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String[] texts = {
            "SELECT * FROM sales_ds) SELECT 1; --",
            "SELECT * FROM sales_ds) SELECT 1; ATTACH '/tmp/x.db' AS x; --",
            "SELECT * FROM sales_ds; SELECT 2",
            "SELECT * FROM sales_ds --",
            "SELECT * FROM sales_ds /*",
            "SELECT * FROM sales_ds), z AS (SELECT * FROM read_parquet('/etc/x.parquet')",
            "SELECT * FROM read_text('/etc/hosts')",
            "SELECT * FROM glob('/*')",
            "COPY (SELECT 1) TO '/tmp/pwn.csv'",
            "PRAGMA database_list",
            "SELECT * FROM sales_ds) SELECT * FROM duckdb_settings() --",
            "SELECT getenv('PATH') AS region, 1 AS amount",
        };
        try (Ctx c = open(cfg, root, false)) {
            seed(c);
            StringBuilder out = new StringBuilder();
            for (String t : texts) {
                new ComponentStore(c.root.resolve("registry")).write("query", "q", Map.of("type", "sql", "datasetId", "sales_ds", "text", t));
                var r = post(c, null, "q");
                out.append(r.statusCode()).append(" <= ").append(t).append(" :: ").append(r.body(), 0, Math.min(160, r.body().length())).append('\n');
                if (r.statusCode() == 200) fail("accepted: " + t + "\n" + r.body());
            }
            System.out.println("ADV-RESULTS\n" + out);
        }
    }

    @Test void cteNameCollisionStaysInsideItsScope(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, false)) {
            seed(c);
            new ComponentStore(c.root.resolve("registry")).write("query", "q", Map.of("type", "sql", "datasetId", "sales_ds",
                    "text", "WITH \"__bound_query\" AS (SELECT * FROM sales_ds WHERE region='US') SELECT * FROM \"__bound_query\""));
            var r = post(c, null, "q");
            System.out.println("ADV-CTE " + r.statusCode() + " " + r.body());
        }
    }

    @Test void anotherDatasetIsNotReachableFromTheText(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, false)) {
            seed(c);
            var st = new ComponentStore(c.root.resolve("registry"));
            st.write("dataset", "other_ds", Map.of("view", "sales_view"));
            for (String t : new String[]{"SELECT * FROM other_ds", "SELECT * FROM sales_ds JOIN other_ds USING (region)",
                    "SELECT * FROM main.other_ds", "SELECT * FROM sales_view"}) {
                st.write("query", "q", Map.of("type", "sql", "datasetId", "sales_ds", "text", t));
                var r = post(c, null, "q");
                System.out.println("ADV-XDS " + r.statusCode() + " <= " + t);
                assertNotEquals(200, r.statusCode(), t + " " + r.body());
            }
        }
    }
}
