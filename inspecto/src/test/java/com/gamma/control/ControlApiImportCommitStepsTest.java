package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.ArrivalCompactor;
import com.gamma.onboard.ImportFollowOn;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} BE-1a over real HTTP: a commit of an import Pipeline outside Add data (a scheduled run) runs
 * the import's follow_on step as Add data does - compaction, then each follow-on, then the Expectations - once per run; a run
 * Add data holds does not run them a second time; the Pipeline's {@code import_steps} decides whether they run at all.
 * Synthetic fixtures only.
 */
class ControlApiImportCommitStepsTest {

    private static final String EXPECTATION = "probe_ids_present";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();
    /** What the probe follow-on saw each time it ran: the Dataset's Parquet files and the Expectation's last check. */
    private final List<Map<String, Object>> probed = Collections.synchronizedList(new ArrayList<>());

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        OnboardImport.followOnsForTest(null);
        EditionFeatures.overrideForTest(null);
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            // Add a file shares the standard rate bucket: each read of this clock is 3 s later, one token back per request
            java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
            api.useRateLimitClock(() -> nanos.addAndGet(3_000_000_000L));
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** A follow-on that records, each time it runs, how many Parquet files the Dataset has and the Expectation's last check. */
    private void probe(Path writeRoot) {
        OnboardImport.followOnsForTest(List.of(new ImportFollowOn() {
            public String key() { return "probe"; }
            public String does() { return "probes it"; }
            public Set<String> needs() { return Set.of(); }
            public Map<String, Object> run(Context ctx) {
                Map<String, Object> m = new LinkedHashMap<>();
                try {
                    m.put("files", ArrivalCompactor.parquetFiles(ctx.dataRoot().resolve(ctx.dataset()).resolve("database")).size());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                m.put("checkedAt", checkedAt(writeRoot));
                m.put("actor", ctx.actor());
                probed.add(m);
                return Map.of("result", "DONE");
            }
        }));
    }

    /** The Expectation's last check time, or null when it was never evaluated. */
    private static Object checkedAt(Path writeRoot) {
        return new ComponentStore(writeRoot.resolve("registry")).get("expectation", EXPECTATION)
                .map(ComponentRegistry.Component::content)
                .map(c -> c.get("lastResult") instanceof Map<?, ?> r ? r.get("checkedAt") : null).orElse(null);
    }

    private static Object lastStatus(Path writeRoot) {
        return new ComponentStore(writeRoot.resolve("registry")).get("expectation", EXPECTATION)
                .map(ComponentRegistry.Component::content)
                .map(c -> c.get("lastResult") instanceof Map<?, ?> r ? r.get("status") : null).orElse(null);
    }

    private static void expectation(Path writeRoot, String id) throws Exception {
        new ComponentStore(writeRoot.resolve("registry")).write("expectation", EXPECTATION, Map.of(
                "name", EXPECTATION, "kind", "non_null", "targetType", "pipeline", "target", id, "column", "transfer_id"));
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? b.method(method, BodyPublishers.noBody()) : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private String imported(Ctx c, String base) throws Exception {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream",
                ControlApiDatasetCompactionTest.transfersOn(day(1), 5).getBytes(StandardCharsets.UTF_8)));
        data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)));
        return id;
    }

    private JsonNode append(Ctx c, String id, int d) throws Exception {
        return data(send(c, "POST", "/datasets/" + id + "/files?name=day" + d + ".csv&mode=append",
                ControlApiDatasetCompactionTest.transfersOn(day(d), 5).getBytes(StandardCharsets.UTF_8)));
    }

    private static Path inbox(String id) throws Exception {
        return Files.createDirectories(Path.of("database", "inbox", id).toAbsolutePath());
    }

    private static int parquetFiles(String id) throws Exception {
        return ArrivalCompactor.parquetFiles(Path.of("database", id, "database").toAbsolutePath().normalize()).size();
    }

    private static String day(int d) {
        return String.format("2026-09-%02d", d);
    }

    @Test
    void aScheduledRunCompactsThenFollowsOnThenChecksTheExpectationsOnceAndAddDataDoesItOnlyOnce(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));   // Standard: a feed runs on the scheduler
        probe(root);
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "steps");
            for (int d = 2; d <= ArrivalCompactor.THRESHOLD; d++) append(c, id, d);
            assertEquals(ArrivalCompactor.THRESHOLD, parquetFiles(id));
            expectation(root, id);
            probed.clear();

            // the scheduler's run, nobody holding the Dataset: two files, so two commits
            Files.writeString(inbox(id).resolve("feed21.csv"), ControlApiDatasetCompactionTest.transfersOn(day(21), 5));
            Files.writeString(inbox(id).resolve("feed22.csv"), ControlApiDatasetCompactionTest.transfersOn(day(22), 5));
            assertTrue(c.svc().runPipeline(id).isPresent(), "the import Pipeline ran");
            assertEquals(1, probed.size(), "the follow-on ran once for the run's two commits: " + probed);
            assertEquals(2, probed.get(0).get("files"), "compaction ran first: 21 files merged on the first commit, then one more");
            assertNull(probed.get(0).get("checkedAt"), "the Expectations run after the follow-on");
            assertEquals("scheduler", probed.get(0).get("actor"));
            assertEquals("PASSED", lastStatus(root), "the Expectations ran after it");
            assertEquals(110, OnboardImport.countRows(Path.of("database", id, "database")), "no row lost or doubled");

            // Add a file: Add data holds the Dataset and does the follow_on step itself - once
            Object before = checkedAt(root);
            probed.clear();
            JsonNode s = append(c, id, 23);
            assertEquals(1, probed.size(), "the follow-on ran once, inside Add data's run: " + probed);
            assertEquals(before, probed.get(0).get("checkedAt"), "no Expectation was checked before Add data's own follow-on");
            assertNotEquals(before, checkedAt(root), "Add data checked the Expectations itself");
            // the answer's shape is Add data's own
            assertEquals("NOT_NEEDED", s.get("compaction").get("result").asText(), s.toString());
            assertEquals("DONE", s.get("probe").get("result").asText(), s.toString());
            assertEquals(1, s.get("expectations").size(), s.toString());
            assertEquals("PASSED", s.get("expectations").get(0).get("status").asText(), s.toString());
        }
    }

    @Test
    void importStepsWithoutFollowOnRunNoneAndAPipelineWithoutImportStepsRunsThemAll(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        probe(root);
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "gated");
            Path pipeline = root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon");
            String toon = Files.readString(pipeline);
            assertTrue(toon.contains("follow_on"), toon);
            List<String> lines = new ArrayList<>(toon.lines().toList());

            // import_steps without follow_on: neither a scheduled run nor Add data runs the step
            List<String> without = new ArrayList<>();
            for (String l : lines) without.add(l.trim().startsWith("import_steps") ? l.replace(",follow_on", "").replace("[4]", "[3]") : l);
            Files.write(pipeline, without);
            assertFalse(Files.readString(pipeline).contains("follow_on"), Files.readString(pipeline));
            probed.clear();
            Files.writeString(inbox(id).resolve("feed2.csv"), ControlApiDatasetCompactionTest.transfersOn(day(2), 5));
            assertTrue(c.svc().runPipeline(id).isPresent());
            assertEquals(10, OnboardImport.countRows(Path.of("database", id, "database")), "the run itself committed");
            JsonNode s = append(c, id, 3);
            assertEquals(List.of(), probed, "import_steps leaves out follow_on");
            assertFalse(s.has("probe"), s.toString());

            // no import_steps at all (written before BASIC-ONBOARD-1 P1): all four steps, follow_on included
            List<String> none = new ArrayList<>();
            for (String l : lines) if (!l.trim().startsWith("import_steps")) none.add(l);
            Files.write(pipeline, none);
            Files.writeString(inbox(id).resolve("feed4.csv"), ControlApiDatasetCompactionTest.transfersOn(day(4), 5));
            assertTrue(c.svc().runPipeline(id).isPresent());
            assertEquals(1, probed.size(), "a Pipeline without import_steps follows on: " + probed);
        }
    }
}
