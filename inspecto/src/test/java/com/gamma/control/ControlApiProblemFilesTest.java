package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for {@code GET /status/problem-files} — the cross-pipeline, file-grain companion to
 * {@code /status}'s pipeline-grain rollup (operator ask 2026-08-23: triaging ingest failures across
 * 100s of pipelines one Run Detail at a time does not scale).
 *
 * <p>Seeds TWO pipelines' status ledgers directly (the route reads through the {@code StatusStore}
 * seam, so a hand-written ledger is the honest fixture — it exercises the aggregation without
 * needing two real ingests), then pins: the FULL/PARTIAL split, that clean files never appear,
 * cross-pipeline aggregation with the pipeline stamped per row, newest-first ordering, the
 * {@code ?limit=} bound with an honest {@code truncated} + pre-limit summary counts, {@code ?since=}
 * filtering, quarantine-tree rows for files the ledger never saw, and the {@code 400} on a bad limit.
 */
class ControlApiProblemFilesTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /** The per-file status ledger header the engine writes (OperationalTables.FILES). */
    private static final String FILES_HEADER =
            "start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,"
            + "duration_ms,error,consignment_id";

    private Ctx open(Path root, String... pipelines) throws Exception {
        for (String p : pipelines) seedPipeline(root, "s1", p);
        SpaceManager spaces = SpaceManager.discover(root);
        assertEquals(1, spaces.size(), "space booted");
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    /** One discoverable pipeline whose dirs live under {@code <space>/<name>/}. */
    private void seedPipeline(Path root, String space, String name) throws Exception {
        Path config = root.resolve(space).resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        String toon = Files.readString(tmp);
        // Give each pipeline its own name and its own status/quarantine trees.
        Path base = root.resolve(space).resolve(name);
        String b = base.toString().replace('\\', '/');
        toon = toon.replaceFirst("(?m)^name:.*$", "name: " + name);
        // ⚠ Use the REAL dirs keys: the ledger lives under `status_dir`, not `status`, and the store
        // under `database`, not `db` — a near-miss here leaves every pipeline on the shared default,
        // so the seeded ledger is written where nothing reads it and the route honestly returns [].
        for (String key : new String[] {"status_dir", "quarantine", "backup", "temp", "errors", "markers", "database"})
            toon = toon.replaceFirst("(?m)^(\\s+)" + key + ":.*$", "$1" + key + ": " + b + "/" + key);
        Files.delete(tmp);
        Files.writeString(config.resolve(name + "_pipeline.toon"), toon);
    }

    /** Write a status ledger for {@code pipeline} whose rows are {@code filename,status,parsed,errors,time}. */
    private void ledger(Path root, String pipeline, String... rows) throws Exception {
        Path statusDir = root.resolve("s1").resolve(pipeline).resolve("status_dir");
        Files.createDirectories(statusDir);
        StringBuilder sb = new StringBuilder(FILES_HEADER).append('\n');
        for (String r : rows) {
            String[] f = r.split(",", -1);   // filename,status,parsed,errors,time
            sb.append(f[4]).append(',').append(f[4]).append(',').append(f[0]).append(',').append(f[1])
              .append(',').append(f[2]).append(',').append(f[3]).append(",,,10,").append(
                      "FULL".equals(f[1]) ? "boom" : "").append(",c-1\n");
        }
        // ⚠ The glob is `<pipeline>_status_*.csv` — one ledger PER RUN, not one per pipeline
        // (FileStatusStore.readRuns). A `<pipeline>_status.csv` is silently invisible.
        Files.writeString(statusDir.resolve(pipeline + "_status_20260820_010000.csv"),
                sb.toString(), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(), BodyHandlers.ofString());
    }

    private JsonNode data(int port, String path) throws Exception {
        HttpResponse<String> r = get(port, path);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static List<String> names(JsonNode rows) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : rows) out.add(r.get("filename").asText());
        return out;
    }

    // ── the happy path: FULL vs PARTIAL vs clean, across pipelines ─────────────

    @Test
    void splitsFullFromPartialAndOmitsCleanFiles(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "alpha")) {
            ledger(root, "alpha",
                    "clean.csv,SUCCESS,100,0,2026-08-20 01:00:00",
                    "partial.csv,SUCCESS,90,10,2026-08-20 02:00:00",
                    "bad.csv,QUARANTINED_UNREADABLE,0,0,2026-08-20 03:00:00");

            JsonNode d = data(c.port, "/spaces/s1/status/problem-files");
            assertEquals(2, d.get("total").asInt(), "the clean file is not a problem: " + d);
            assertEquals(1, d.get("fullCount").asInt());
            assertEquals(1, d.get("partialCount").asInt());
            assertEquals(1, d.get("pipelinesWithProblems").asInt());
            assertFalse(d.get("truncated").asBoolean());

            // Newest first.
            assertEquals(List.of("bad.csv", "partial.csv"), names(d.get("rows")));
            JsonNode full = d.get("rows").get(0);
            assertEquals("FULL", full.get("verdict").asText());
            assertEquals("QUARANTINED_UNREADABLE", full.get("status").asText());
            assertEquals("alpha", full.get("pipeline").asText(), "every row names its pipeline");
            JsonNode partial = d.get("rows").get(1);
            assertEquals("PARTIAL", partial.get("verdict").asText());
            assertEquals(10, partial.get("errorRows").asInt());
            assertEquals(90, partial.get("parsedRows").asInt());
        }
    }

    /**
     * The two unpack identity columns surface on a problem row: {@code origin} (the archive the
     * member came OUT of — a display basename) and {@code logicalName} (that inbox file's
     * extension-insensitive IDENTITY, poll-relative, so a re-delivery under another compression
     * spelling groups to it). ⚠ Every OTHER test in this class writes a ledger with neither column,
     * which is the pre-column tolerance half — an absent column reads blank, never a shifted value.
     */
    @Test
    void aProblemRowCarriesTheArchiveAndTheLogicalIdentity(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "alpha")) {
            // ⚠ "status_dir", matching the ledger() helper — a ledger under "status" is written
            // where nothing reads it and the route then honestly returns [] (see open()'s note).
            Path statusDir = root.resolve("s1").resolve("alpha").resolve("status_dir");
            Files.createDirectories(statusDir);
            Files.writeString(statusDir.resolve("alpha_status_20260820_010000.csv"),
                    FILES_HEADER + ",origin,logical_name\n"
                            + "2026-08-20 03:00:00,2026-08-20 03:00:00,00001_a.csv,QUARANTINED_MISMATCH,"
                            + "0,0,,,10,boom,c-1,bundle.zip,\"east/bundle\"\n",
                    StandardCharsets.UTF_8);

            JsonNode row = data(c.port, "/spaces/s1/status/problem-files").get("rows").get(0);
            assertEquals("bundle.zip", row.get("origin").asText(),
                    "the operator sees what they actually DROPPED");
            assertEquals("east/bundle", row.get("logicalName").asText(),
                    "…and the key a re-delivery under another spelling would group to");
        }
    }

    /** 🔴 The whole point of the route: ONE call covers every pipeline. */
    @Test
    void aggregatesAcrossPipelines(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "alpha", "beta")) {
            ledger(root, "alpha", "a-bad.csv,QUARANTINED_EMPTY,0,0,2026-08-20 01:00:00");
            ledger(root, "beta",  "b-partial.csv,SUCCESS,5,5,2026-08-20 09:00:00");

            JsonNode d = data(c.port, "/spaces/s1/status/problem-files");
            assertEquals(2, d.get("total").asInt(), d.toString());
            assertEquals(2, d.get("pipelinesWithProblems").asInt());
            // Newest first puts beta's row on top, proving the sort spans pipelines rather than
            // concatenating each pipeline's list.
            assertEquals(List.of("b-partial.csv", "a-bad.csv"), names(d.get("rows")));
            assertEquals("beta", d.get("rows").get(0).get("pipeline").asText());
            assertEquals("alpha", d.get("rows").get(1).get("pipeline").asText());
        }
    }

    // ── bounds and filters ────────────────────────────────────────────────────

    /** A diagnostic read must not become an export: bounded, with the TRUE total reported. */
    @Test
    void limitBoundsTheListAndCountsStayPreLimit(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "alpha")) {
            ledger(root, "alpha",
                    "f1.csv,SUCCESS,1,1,2026-08-20 01:00:00",
                    "f2.csv,SUCCESS,1,1,2026-08-20 02:00:00",
                    "f3.csv,QUARANTINED_UNREADABLE,0,0,2026-08-20 03:00:00");

            JsonNode d = data(c.port, "/spaces/s1/status/problem-files?limit=1");
            assertEquals(1, d.get("rows").size(), "the page is cut");
            assertTrue(d.get("truncated").asBoolean());
            assertEquals(3, d.get("total").asInt(), "total is the TRUE count, not the page size");
            // Summary cards must describe reality, not the page.
            assertEquals(1, d.get("fullCount").asInt());
            assertEquals(2, d.get("partialCount").asInt());
            assertEquals("f3.csv", d.get("rows").get(0).get("filename").asText(), "newest survives the cut");
        }
    }

    @Test
    void sinceFiltersByLedgerTime(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "alpha")) {
            ledger(root, "alpha",
                    "old.csv,SUCCESS,1,1,2026-08-19 23:00:00",
                    "new.csv,SUCCESS,1,1,2026-08-21 08:00:00");

            JsonNode d = data(c.port, "/spaces/s1/status/problem-files?since=2026-08-20");
            assertEquals(List.of("new.csv"), names(d.get("rows")));
            assertEquals(1, d.get("total").asInt());
            // A bare date works as well as a datetime (lexicographic on the ledger format).
            assertEquals(2, data(c.port, "/spaces/s1/status/problem-files?since=2026-08-19")
                    .get("total").asInt());
        }
    }

    @Test
    void badLimitIs400(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "alpha")) {
            assertEquals(400, get(c.port, "/spaces/s1/status/problem-files?limit=abc").statusCode());
            assertEquals(400, get(c.port, "/spaces/s1/status/problem-files?limit=0").statusCode());
        }
    }

    // ── the quarantine tree ───────────────────────────────────────────────────

    /**
     * A file rejected BEFORE any batch existed (corrupt download, empty) has no status-ledger row at
     * all — only a quarantine-tree entry. It must still be reported, or the pane would call a
     * pipeline clean while files sit in quarantine.
     */
    @Test
    void quarantineTreeOnlyFilesAreReportedOnce(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "alpha")) {
            ledger(root, "alpha", "known.csv,QUARANTINED_UNREADABLE,0,0,2026-08-20 03:00:00");
            // Two files in the tree: one the ledger already knows, one it never saw.
            Path q = root.resolve("s1").resolve("alpha").resolve("quarantine").resolve("corrupt_download");
            Files.createDirectories(q);
            Files.writeString(q.resolve("known.csv"), "x");
            Files.writeString(q.resolve("orphan.csv"), "x");

            JsonNode d = data(c.port, "/spaces/s1/status/problem-files");
            List<String> files = names(d.get("rows"));
            assertEquals(1, files.stream().filter("known.csv"::equals).count(),
                    "the ledger row wins — one row per problem, not two: " + files);
            assertTrue(files.contains("orphan.csv"), "a tree-only file is still reported: " + files);
            assertEquals(2, d.get("fullCount").asInt());
            assertEquals(0, d.get("partialCount").asInt());
        }
    }

    /** No problems anywhere is an empty list with zeroed counts — never an error. */
    @Test
    void cleanServiceReportsEmptyRatherThanFailing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "alpha")) {
            JsonNode d = data(c.port, "/spaces/s1/status/problem-files");
            assertEquals(0, d.get("total").asInt());
            assertEquals(0, d.get("rows").size());
            assertEquals(0, d.get("pipelinesWithProblems").asInt());
            assertFalse(d.get("truncated").asBoolean());
        }
    }
}
