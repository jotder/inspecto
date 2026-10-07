package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D-3 step 5 timing: {@code recursive-paths} answered from the index versus from the flat Dataset, through the REAL route,
 * one hop and depth 2, on a generated heavy-tailed corpus (never committed). Skipped unless
 * {@code -Dinspecto.bench.dir=<dir>} is set; {@code -Dinspecto.bench.edges=<n>} (default 1 000 000). Start nodes have out-degree
 * at most 20, so the frontier stays inside the index cap and the index serves every request measured.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class InvIndexedTraversalBench {

    private static final Path DIR = Path.of(System.getProperty("inspecto.bench.dir", "."));
    private static final long EDGES = Long.getLong("inspecto.bench.edges", 1_000_000L);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static String sqlPath(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private static Path corpus() throws SQLException {
        Path f = DIR.resolve("d3s5_" + EDGES + ".parquet");
        if (Files.exists(f)) return f;
        long nodes = EDGES / 5;
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT 'n' || CAST(floor(" + nodes + " * pow(hash(i) / 1.8446744073709552e19, 3)) AS BIGINT) AS src,"
                    + " 'n' || CAST(floor(" + nodes + " * pow(hash(i + 7777777777) / 1.8446744073709552e19, 2)) AS BIGINT) AS dst"
                    + " FROM range(" + EDGES + ") t(i)) TO '" + sqlPath(f) + "' (FORMAT parquet)");
        }
        return f;
    }

    /** Start nodes with out-degree about 2, 8 and 20 (the largest allowed: its frontier is at most 20 keys). */
    private static List<String> starts(Path f) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            for (int deg : new int[] {2, 8, 20}) {
                try (ResultSet r = s.executeQuery("SELECT min(src) FROM (SELECT src, count(*) n FROM read_parquet('" + sqlPath(f)
                        + "') GROUP BY src) WHERE n = " + deg)) {
                    r.next();
                    out.add(r.getString(1));
                }
            }
        }
        return out;
    }

    private double time(int port, String body, String expectSource) throws Exception {
        long t0 = System.nanoTime();
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/traversal/recursive-paths"))
                .method("POST", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        double ms = (System.nanoTime() - t0) / 1e6;
        assertEquals(200, r.statusCode(), r.body());
        JsonNode data = JSON.readTree(r.body()).get("data");
        assertEquals(expectSource, data.at("/source/kind").asText(), data.get("source").toString());
        return ms;
    }

    private static double p50(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    @Test
    void indexVersusFlat() throws Exception {
        Path f = corpus();
        List<String> starts = starts(f);
        Path cfg = Files.createTempDirectory(DIR, "cfg5"), root = Files.createTempDirectory(DIR, "root5");
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        try {
            api.start();
            new ViewStore(root.resolve("views")).write(new ViewDefinition("edges_view", "flow-x", List.of(),
                    "SELECT * FROM read_parquet('" + sqlPath(f) + "')", "2026-10-02T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "edges_ds", Map.of("view", "edges_view"));
            long b0 = System.nanoTime();
            HttpResponse<String> b = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1/inv/index/builds"))
                    .method("POST", HttpRequest.BodyPublishers.ofString("{\"dataset\":\"edges_ds\",\"sourceCol\":\"src\",\"targetCol\":\"dst\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            String id = JSON.readTree(b.body()).get("data").get("buildId").asText();
            String status;
            do {
                Thread.sleep(250);
                status = JSON.readTree(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1/inv/index/builds/" + id))
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).body()).get("data").get("status").asText();
            } while (!status.equals("COMPLETED") && !status.equals("FAILED"));
            System.out.printf("D3S5-BENCH %d edges: index build %s in %.1f s%n", EDGES, status, (System.nanoTime() - b0) / 1e9);
            for (String start : starts) {
                for (int depth : new int[] {1, 2}) {
                    String body = "{\"dataset\":\"edges_ds\",\"sourceCol\":\"src\",\"targetCol\":\"dst\",\"startNode\":\"" + start + "\",\"maxDepth\":" + depth + "}";
                    List<Double> idx = new ArrayList<>(), flat = new ArrayList<>();
                    Files.writeString(root.resolve("link-analysis.toon"), "index:\n  enabled: true\n");
                    time(api.port(), body, "index");
                    for (int i = 0; i < 20; i++) idx.add(time(api.port(), body, "index"));
                    Files.writeString(root.resolve("link-analysis.toon"), "index:\n  enabled: false\n");
                    time(api.port(), body, "dataset");
                    for (int i = 0; i < 20; i++) flat.add(time(api.port(), body, "dataset"));
                    System.out.printf("D3S5-BENCH %d edges, start %s depth %d: index p50 %.1f ms | flat p50 %.1f ms%n", EDGES, start, depth, p50(idx), p50(flat));
                }
            }
        } finally {
            api.close();
            svc.close();
            System.clearProperty("assist.write.root");
        }
    }
}
