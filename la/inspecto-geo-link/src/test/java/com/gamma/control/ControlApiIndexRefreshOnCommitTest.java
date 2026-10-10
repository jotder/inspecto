package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DEMO-INDEX-1 / DR-T1 - "a day of data arrives, the index refreshes itself", through the REAL signal-to-Job wiring: a real
 * {@link CollectorService} (real {@code JobService}, real {@code link-index} Platform Service, real {@code IndexBuilder}) runs a
 * real Pipeline, whose commit is mirrored as a {@code pipeline.commit} Signal ({@code JobService.mirrorPipelineCommit}); a saved
 * {@code la.index.build} Job with {@code on_signal: pipeline.commit} fires, appends index version N+1, and the next read is served
 * from it ({@code source.kind: index}). The negative: the same Job on {@code on_signal: job.dataset.produced} (emitted ONLY by the
 * {@code sql.template} Job) does NOT fire on a file commit.
 *
 * <p>Honest seam: the Pipeline commits its own output under its own {@code dirs.database}; the Dataset under test reads a
 * {@code physicalRef} store under the legacy {@code database/} root, so "the day's file" is planted there by the test just before the
 * Pipeline run. The commit, the Signal mirror, the {@code when} guard, the Job run, the delegated-principal build and the indexed read
 * are all the real thing.
 */
class ControlApiIndexRefreshOnCommitTest {

    @BeforeAll static void raiseBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner";
    private static final String BUILD = "{\"dataset\":\"f_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"kindCol\":\"kind\",\"timeCol\":\"ts\",\"attrCols\":[\"c\"]}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, Path root, Path inbox, Path store) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        InputFingerprintCache.forTest(null, 0);
    }

    private static Map<String, Object> job(String name, String onSignal) {
        Map<String, Object> j = new java.util.LinkedHashMap<>();
        j.put("name", name);
        j.put("type", "la.index.build");
        j.put("on_signal", onSignal);
        j.put("when", "$signal.pipeline == dayfeed");
        j.put("dataset", "f_ds");
        j.put("source_col", "s");
        j.put("target_col", "t");
        j.put("kind_col", "kind");
        j.put("time_col", "ts");
        j.put("attr_cols", "c");
        j.put("updatedBy", "analyst-1");                                                      // the save-path stamp: owner == last editor
        j.put("owner", "analyst-1");                                                         // allow_full stays UNSET: an append only
        return Map.of("job", j);
    }

    /** A one-schema CSV Pipeline named {@code dayfeed} (the poll dir is {@code <root>/inbox}); the recipe of CollectorServiceTriggerTest. */
    private static Path dayFeed(Path root) throws Exception {
        Path schema = root.resolve("schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String r = root.toString().replace('\\', '/');
        String toon = """
                name: dayfeed
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  quarantine: %1$s/quarantine
                  markers: %1$s/markers
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: "%2$s"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(r, schema.toString().replace('\\', '/'));
        Path p = root.resolve("dayfeed_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private Ctx open(Path cfg, Path writeRoot, String store, String... onSignals) throws Exception {
        AtomicLong tick = new AtomicLong();
        InputFingerprintCache.forTest(() -> tick.addAndGet(60_000L), 30_000L);
        Authenticators.forTest(ex -> "Bearer owner".equals(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")))
                ? Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canManageIncidents"))) : Optional.empty());
        Path pipe = dayFeed(cfg);
        List<JobConfig> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < onSignals.length; i++) jobs.add(JobConfig.fromMap(job("idx_refresh_" + i, onSignals[i])));
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), List.of(), jobs, 3600, 1, null);
            svc.start();                                                                       // arms the Job layer (the bus subscriptions + the pipeline.commit mirror)
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "f_ds",
                    Map.of("physicalRef", store, "owner", "analyst-1", "shares", List.of()));
            Path inbox = cfg.resolve("inbox");
            Files.createDirectories(inbox);
            return new Ctx(svc, api, writeRoot, inbox, Path.of("database").resolve(store));
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api.port() + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", OWNER);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void fullBuild(Ctx c) throws Exception {
        String id = data(send(c, "POST", "/inv/index/builds", BUILD), 202).get("buildId").asText();
        for (int i = 0; i < 6_000; i++) {
            String st = data(send(c, "GET", "/inv/index/builds/" + id, null), 200).get("status").asText();
            if (st.equals("COMPLETED")) return;
            assertFalse(st.equals("FAILED") || st.equals("CANCELLED"), st);
            Thread.sleep(10);
        }
        throw new AssertionError("the first full build never completed");
    }

    private JsonNode indexVersions(Ctx c) throws Exception {
        for (JsonNode ix : data(send(c, "GET", "/inv/index", null), 200).get("indexes"))
            if ("f_ds".equals(ix.get("dataset").asText())) return ix;
        throw new AssertionError("no index of f_ds");
    }

    private long currentVersion(Ctx c) throws Exception {
        JsonNode ix = indexVersions(c);
        return ix.get("version").asLong();
    }

    private JsonNode paths(Ctx c, String start) throws Exception {
        return data(send(c, "POST", "/inv/traversal/recursive-paths",
                "{\"dataset\":\"f_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"startNode\":\"" + start + "\",\"maxDepth\":2}"), 200);
    }

    private static void plant(Path file, String values, long mtime) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("idx_refresh_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS v(s,t,kind,ts,c)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    }

    private static final long T0 = 1_700_000_000_000L, DAY = 86_400_000L;
    private static final String DAY0 = "('A','B','call',TIMESTAMP '2026-03-01 09:00:00','x'),('B','C','call',TIMESTAMP '2026-03-02 09:00:00','x')";
    private static final String DAY1 = "('C','D','call',TIMESTAMP '2026-03-03 09:00:00','x'),('D','E','sms',TIMESTAMP '2026-03-04 09:00:00','y')";

    /** The day's file arrives through the real Pipeline: one CSV in the inbox, one real run, one commit. */
    private static void commitADay(Ctx c, String name) throws Exception {
        Files.writeString(c.inbox.resolve(name), "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-02-02\n");
        c.svc.runPipeline("dayfeed").orElseThrow();
    }

    private static List<JobRun> runsOf(Ctx c, String job) {
        return c.svc.jobService().orElseThrow().runsFor(job);
    }

    private static boolean reaches(JsonNode d, String node) {
        for (JsonNode p : d.get("paths"))
            for (JsonNode n : p.get("nodes")) if (node.equals(n.asText())) return true;
        return false;
    }

    @Test
    void aCommittedDayFiresTheJobAppendsANewIndexVersionAndTheNextReadIsServedFromIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "refresh_" + System.nanoTime();
        try (Ctx c = open(cfg, root, store, "pipeline.commit")) {
            plant(c.store.resolve("day0.parquet"), DAY0, T0);
            fullBuild(c);                                                                       // the operator's first (full) build
            long v1 = currentVersion(c);
            JsonNode before = paths(c, "C");
            assertEquals("index", before.at("/source/kind").asText(), "the default is ON: no index.enabled was ever written");
            assertFalse(reaches(before, "D"), "day 1 has not landed");

            plant(c.store.resolve("day1.parquet"), DAY1, T0 + DAY);                             // the next day lands ...
            commitADay(c, "day1.csv");                                                          // ... and the Pipeline commits it

            for (int i = 0; i < 3_000 && runsOf(c, "idx_refresh_0").stream().noneMatch(r -> !"RUNNING".equals(r.status())); i++) Thread.sleep(10);
            JobRun run = runsOf(c, "idx_refresh_0").stream().filter(r -> !"RUNNING".equals(r.status())).findFirst()
                    .orElseThrow(() -> new AssertionError("the la.index.build Job never ran on pipeline.commit: " + runsOf(c, "idx_refresh_0")));
            assertEquals("SUCCESS", run.status(), run.message());
            assertTrue(run.trigger().startsWith("signal:pipeline.commit"), run.trigger());

            assertEquals(v1 + 1, currentVersion(c), "index version N+1 (an append) appeared");
            JsonNode after = paths(c, "C");
            assertEquals("index", after.at("/source/kind").asText(), after.get("source").toString());
            assertEquals(v1 + 1, after.at("/source/version").asLong(), "served from the refreshed version");
            assertTrue(reaches(after, "D"), "the new day is visible: C>D");
        }
    }

    @Test
    void aJobOnJobDatasetProducedDoesNotFireOnAFileCommit(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "refresh_neg_" + System.nanoTime();
        try (Ctx c = open(cfg, root, store, "job.dataset.produced", "pipeline.commit")) {   // job 0: the wrong signal; job 1: the right one (the control)
            plant(c.store.resolve("day0.parquet"), DAY0, T0);
            fullBuild(c);
            long v1 = currentVersion(c);
            plant(c.store.resolve("day1.parquet"), DAY1, T0 + DAY);
            commitADay(c, "day1.csv");

            for (int i = 0; i < 3_000 && runsOf(c, "idx_refresh_1").stream().noneMatch(r -> !"RUNNING".equals(r.status())); i++) Thread.sleep(10);
            assertEquals("SUCCESS", runsOf(c, "idx_refresh_1").stream().filter(r -> !"RUNNING".equals(r.status())).findFirst().orElseThrow().status(),
                    "the control Job (pipeline.commit) fired, so the commit really happened and was mirrored");
            Thread.sleep(500);
            assertTrue(runsOf(c, "idx_refresh_0").isEmpty(), "job.dataset.produced is emitted only by the sql.template Job: " + runsOf(c, "idx_refresh_0"));
            assertEquals(v1 + 1, currentVersion(c), "exactly one append, by the pipeline.commit Job");
        }
    }
}
