package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DATA-TRUST-POLISH-1 (6) - the coverage read is cached per Dataset input-file stamp, over REAL HTTP with an ARMED
 * Authenticator. The safety property: the cache never shows more or fewer days/rows than the disk (a new File, a removed File
 * or a rewritten File moves the stamp), a Dataset with no enumerable files is read live every time, and the gates run on
 * every call, cached or not. {@link #coldOpenBench} is the measurement (enabled with {@code -Dinspecto.bench.coverage=true}).
 */
class ControlApiCoverageCacheTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DAY = 86_400_000L, T0 = 1_788_000_000_000L;
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"inv-a\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\",\"timeCol\":\"ts\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String COV = INV + "/coverage?from=2026-09-01T00:00:00Z&to=2026-09-05T00:00:00Z";
    private static final String OWNER = "Bearer owner", OTHER = "Bearer other";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root, String store, Path dir) implements AutoCloseable {
        public void close() throws Exception {
            api.close();
            svc.close();
            rm(dir);
            rm(dir.resolveSibling(store + "_scratch"));
        }
    }

    private static void rm(Path dir) throws Exception {
        if (Files.isDirectory(dir)) try (var w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case OTHER -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path cfg, Path root, boolean view) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            String store = "cov_cache_" + System.nanoTime();
            if (view) {
                new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
                setView(root, "2026-09-01", "2026-09-03");
            } else {
                new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds", Map.of("physicalRef", store));
            }
            return new Ctx(svc, api, api.port(), root, store, Path.of("database").resolve(store));
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static void setView(Path root, String... days) throws Exception {
        StringBuilder v = new StringBuilder();
        for (String d : days) v.append(v.length() == 0 ? "" : ",").append("('alice','bob','voice',TIMESTAMP '").append(d).append(" 10:00:00')");
        new ViewStore(root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT caller, callee, channel, ts FROM (VALUES " + v + ") AS t(caller,callee,channel,ts)", "2026-10-03T00:00:00Z"));
    }

    /** One day lands as one Parquet File of {@code rows} rows at one instant (so two days encode to the same size), with a pinned mtime (the clock never decides). */
    private static Path land(Path dir, String name, String day, int rows, int mtimeStep) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("cov_cache_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT 'u' || (i % 500) AS caller, 'u' || ((i * 7) % 500) AS callee, 'voice' AS channel, "
                    + "TIMESTAMP '" + day + " 10:00:00' AS ts FROM range(" + rows + ") t(i)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(T0 + mtimeStep * DAY));
        return file;
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, OWNER);
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** The coverage response minus the one field that is the clock. */
    private JsonNode coverage(Ctx c, String path) throws Exception {
        ObjectNode n = (ObjectNode) ok(c, "GET", path, null);
        n.remove("readAt");
        return n;
    }

    private void investigation(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE);
    }

    @Test
    void aWarmReadIsTheColdReadAndANewOrRemovedFileMovesItAtOnce(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, false)) {
            land(c.dir, "d1.parquet", "2026-09-01", 50, 1);
            land(c.dir, "d3.parquet", "2026-09-03", 70, 3);
            investigation(c);
            JsonNode cold = coverage(c, COV);
            assertEquals("[\"2026-09-02\",\"2026-09-04\"]", cold.get("missingDays").toString());
            assertEquals(70, cold.get("perDay").get(2).get("rows").asInt());
            assertEquals(cold, coverage(c, COV), "warm = cold, field for field");

            Path d2 = land(c.dir, "d2.parquet", "2026-09-02", 30, 2);        // a new File: a day is covered
            JsonNode more = coverage(c, COV);
            assertEquals("[\"2026-09-04\"]", more.get("missingDays").toString(), "never staler than the disk");
            assertEquals(30, more.get("perDay").get(1).get("rows").asInt());

            Files.delete(d2);                                                  // a removed File: the day is missing again
            assertEquals(cold, coverage(c, COV), "never FRESHER than the disk");
        }
    }

    @Test
    void anUnchangedStampIsServedFromTheCacheAndAMovedMtimeOrADifferentWindowIsReread(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, false)) {
            Path f = land(c.dir, "d1.parquet", "2026-09-01", 50, 1);
            investigation(c);
            assertEquals("[\"2026-09-02\",\"2026-09-03\",\"2026-09-04\"]", coverage(c, COV).get("missingDays").toString());

            // Same length, same size, same mtime: only a cache can still answer with the old day.
            long size = Files.size(f);
            FileTime stamp = Files.getLastModifiedTime(f);
            Path other = null;
            String day = null;
            for (String d : List.of("2026-09-02", "2026-09-03", "2026-09-04"))                           // find a same-size encoding of another day
                for (int rows = 44; rows < 56 && day == null; rows++) {
                    other = land(c.dir.resolveSibling(c.store + "_scratch"), "x.parquet", d, rows, 1);
                    if (Files.size(other) == size) day = d;
                }
            assertTrue(day != null, "fixture: some other day encodes to the same size");
            Files.copy(other, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(f, stamp);
            assertEquals("[\"2026-09-02\",\"2026-09-03\",\"2026-09-04\"]", coverage(c, COV).get("missingDays").toString(), "stamp unchanged: the cache answers");

            // A different window is a different key: read live, and it sees the rewritten File (day 09-01 is gone from it).
            JsonNode live = coverage(c, INV + "/coverage?from=2026-09-01T00:00:00Z&to=2026-09-04T00:00:00Z");
            assertTrue(live.get("missingDays").toString().contains("2026-09-01"), live.toString());

            Files.setLastModifiedTime(f, FileTime.fromMillis(stamp.toMillis() + 5_000));
            JsonNode reread = coverage(c, COV);
            assertTrue(reread.get("missingDays").toString().contains("2026-09-01") && !reread.get("missingDays").toString().contains(day),
                    "stamp moved: re-read " + reread.get("missingDays"));
        }
    }

    @Test
    void aDatasetWithNoEnumerableFilesIsReadLiveEveryTime(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, true)) {
            investigation(c);
            assertEquals("[\"2026-09-02\",\"2026-09-04\"]", coverage(c, COV).get("missingDays").toString());
            setView(root, "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04");
            assertEquals("[]", coverage(c, COV).get("missingDays").toString(), "a view-backed Dataset has no stamp: never cached");
        }
    }

    @Test
    void theGatesRunOnACachedHit(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, false)) {
            land(c.dir, "d1.parquet", "2026-09-01", 50, 1);
            investigation(c);
            coverage(c, COV);
            coverage(c, COV);                                                   // warm
            assertEquals(404, send(c, "GET", COV, null, OTHER).statusCode(), "a caller without access to the Investigation is refused although the answer is cached");
            assertEquals(401, send(c, "GET", COV, null, "Bearer nobody").statusCode(), "an unauthenticated caller is refused");
            // R3: the Dataset is shared away from the owner - the warm cache must not answer for it.
            new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds",
                    Map.of("owner", "analyst-2", "physicalRef", c.store, "shares", List.of()));
            assertEquals(404, send(c, "GET", COV, null, OWNER).statusCode(), "a Dataset the caller can no longer view is the same 404 on a warm cache");
        }
    }

    /** The measurement: -Dinspecto.bench.coverage=true [-Dinspecto.bench.files=70 -Dinspecto.bench.rows=4600]. */
    @Test
    void coldOpenBench(@TempDir Path cfg, @TempDir Path root) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("inspecto.bench.coverage"));
        subjects();
        int files = Integer.getInteger("inspecto.bench.files", 70), rows = Integer.getInteger("inspecto.bench.rows", 4600);
        try (Ctx c = open(cfg, root, false)) {
            java.time.LocalDate d0 = java.time.LocalDate.parse("2026-07-01");
            for (int i = 0; i < files; i++) land(c.dir, "d" + i + ".parquet", d0.plusDays(i).toString(), rows, i);
            investigation(c);
            String to = d0.plusDays(files) + "T00:00:00Z";
            String path = INV + "/coverage?from=2026-07-01T00:00:00Z&to=" + to;
            ok(c, "POST", INV + "/ops", "{\"op\":\"window\",\"window\":{\"from\":\"2026-07-01T00:00:00Z\",\"to\":\"" + to + "\"}}");
            ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"u1\"]}");
            ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}");
            StringBuilder out = new StringBuilder("BENCH coverage " + files + " Files x " + rows + " rows:");
            for (int i = 0; i < 4; i++) {
                long t = System.nanoTime();
                ok(c, "GET", path, null);
                out.append(" cov#").append(i).append('=').append((System.nanoTime() - t) / 1_000_000).append("ms");
            }
            for (int i = 0; i < 3; i++) {
                long t = System.nanoTime();
                ok(c, "GET", INV + "/dossier", null);
                out.append(" dossier#").append(i).append('=').append((System.nanoTime() - t) / 1_000_000).append("ms");
            }
            System.out.println(out);
            assertTrue(out.length() > 0);
        }
    }
}
