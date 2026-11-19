package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.onboard.ImportFollowOn;
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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} BE-1b over real HTTP: a scheduled run of an import Pipeline (a kept-up-to-date folder) runs the
 * import's pre-run steps before it reads its inbox - a waiting file whose columns do not match is set aside with its reason,
 * a new column every file the run takes carries is kept - and the commit steps after; a file still being written is neither
 * judged nor taken. Synthetic fixtures only.
 */
class ControlApiImportPreRunStepsTest {

    static final FileTime SETTLED = FileTime.fromMillis(System.currentTimeMillis() - 120_000L);
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();
    /** What the probe follow-on saw each time it ran: its actor, and whether the Dataset already read the new column. */
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
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private void probe(Path writeRoot) {
        OnboardImport.followOnsForTest(List.of(new ImportFollowOn() {
            public String key() { return "probe"; }
            public String does() { return "probes it"; }
            public Set<String> needs() { return Set.of(); }
            public Map<String, Object> run(Context ctx) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("actor", ctx.actor());
                m.put("fee", column(writeRoot, ctx.dataset(), "fee") != null);
                probed.add(m);
                return Map.of("result", "DONE");
            }
        }));
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private String imported(Ctx c, String base) throws Exception {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", ControlApiOnboardFilesTest.transfers(40)));
        data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}"));
        return id;
    }

    static Path drop(String id, String name, String content, boolean settled) throws Exception {
        Path f = Files.createDirectories(Path.of("database", "inbox", id)).resolve(name);
        Files.writeString(f, content);
        if (settled) Files.setLastModifiedTime(f, SETTLED);
        return f;
    }

    static Map<?, ?> column(Path writeRoot, String id, String name) {
        for (Object o : (List<?>) new ComponentStore(writeRoot.resolve("registry")).get("dataset", id).orElseThrow().content().get("columns"))
            if (o instanceof Map<?, ?> m && name.equals(m.get("name"))) return m;
        return null;
    }

    /** The scheduler's next tick finds the Pipeline due again (its cadence is minutes; the test does not wait for it). */
    @SuppressWarnings("unchecked")
    static void dueAgain(CollectorService svc) throws Exception {
        java.lang.reflect.Field g = CollectorService.class.getDeclaredField("runGuard");
        g.setAccessible(true);
        Object guard = g.get(svc);
        java.lang.reflect.Field m = guard.getClass().getDeclaredField("lastRunAtMs");
        m.setAccessible(true);
        ((Map<String, ?>) m.get(guard)).clear();
    }

    /** One poll cycle with every scheduled Pipeline due, whatever else the host runs. */
    static void poll(CollectorService svc) throws Exception {
        dueAgain(svc);
        svc.runAllOnce();
    }

    /** One poll cycle with every scheduled Pipeline due (Add data's own run reset the import's cadence). */
    static void tick(CollectorService svc) throws Exception {
        dueAgain(svc);
        assertEquals(2, svc.runAllOnce().total(), "the fixture's Pipeline and the import's");
    }

    static long rows(String id) throws Exception {
        return OnboardImport.countRows(Path.of("database", id, "database"));
    }

    @Test
    void aScheduledRunJudgesTheInboxAndKeepsNewColumnsBeforeItReadsAndFollowsOnAfter(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));   // Standard: a feed runs on the scheduler
        probe(root);
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "prerun");
            data(send(c, "PUT", "/datasets/" + id + "/feed", "{\"kind\":\"folder\"}"));   // stability 30 s, two sightings
            probed.clear();
            drop(id, "wrong.csv", "colour,shape\nred,square\n", true);
            drop(id, "wider.csv", ControlApiArrivalNewColumnsTest.withColumn(
                    ControlApiOnboardFilesTest.transfers(6).replace("T0000", "W0000"), "fee", i -> "1." + i), true);
            Path growing = drop(id, "growing.csv", "colour,shape\nblue,", false);   // being written, wrong so far

            // first tick: the pre-run steps judge and settle the settled files; the stability gate sees them once
            tick(c.svc());
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "wrong.csv")),
                    "a settled file whose columns do not match is set aside before the run reads the inbox");
            assertNotNull(column(root, id, "fee"), "the new column is kept before the run reads the file that carries it");
            assertEquals(40, rows(id), "nothing taken yet: the gate wants a second sighting");

            // second tick: the file is taken with its new column, then the follow-on runs - once, as the scheduler
            tick(c.svc());
            assertEquals(46, rows(id), "the wider file's rows");
            JsonNode table = data(send(c, "GET", "/datasets/" + id + "/rows?limit=1000", null));
            int fee = -1;
            for (int i = 0; i < table.get("columns").size(); i++) if ("fee".equals(table.get("columns").get(i).get("name").asText())) fee = i;
            assertTrue(fee >= 0, "the Dataset reads the kept column: " + table.get("columns"));
            int withFee = 0;
            for (JsonNode row : table.get("rows")) {
                JsonNode v = row.isArray() ? row.get(fee) : row.get("fee");
                if (v != null && !v.isNull()) withFee++;
            }
            assertEquals(6, withFee, "the run read the new column's values: the schema grew before the read");
            assertEquals(1, probed.size(), "the commit steps ran after the run, once: " + probed);
            assertEquals("scheduler", probed.get(0).get("actor"));
            assertEquals(Boolean.TRUE, probed.get(0).get("fee"));

            String all = data(send(c, "GET", "/datasets/" + id + "/arrivals", null)).get("arrivals").toString();
            assertTrue(all.contains("wrong.csv") && all.contains("REFUSED") && all.contains("does not have the columns"), all);
            assertTrue(all.contains("wider.csv"), all);
            assertTrue(Files.exists(growing), "a file still being written is not judged, not set aside, not taken");
            assertFalse(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "growing.csv")));
            assertFalse(all.contains("growing.csv"), all);
            assertFalse(OnboardImport.IN_FLIGHT.contains(root + "|" + id), "the run's claim is released");
        }
    }

    @Test
    void aScheduledRunOfADatasetAddDataHoldsDoesNotStart(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "held");
            data(send(c, "PUT", "/datasets/" + id + "/feed", "{\"kind\":\"folder\"}"));
            drop(id, "wrong.csv", "colour,shape\nred,square\n", true);
            String claim = root + "|" + id;
            assertTrue(OnboardImport.IN_FLIGHT.add(claim), "as Add data's run holds it");
            try {
                tick(c.svc());
                assertTrue(Files.exists(Path.of("database", "inbox", id, "wrong.csv")), "the holder judges its inbox, not the poll");
            } finally {
                OnboardImport.IN_FLIGHT.remove(claim);
            }
            tick(c.svc());
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "wrong.csv")), "the next tick judges it");
        }
    }

    /** Verifier finding (HIGH): a column the pre-run step keeps is read by the same scheduled run, not set aside. */
    @Test
    void aColumnKeptBeforeTheReadIsReadByTheSameScheduledRun(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "sametick");
            data(send(c, "PUT", "/datasets/" + id + "/feed", "{\"kind\":\"folder\"}"));
            Path wider = drop(id, "wider.csv", ControlApiArrivalNewColumnsTest.withColumn(
                    ControlApiOnboardFilesTest.transfers(6).replace("T0000", "W0000"), "fee", i -> "1." + i), false);
            tick(c.svc());   // modified just now: not judged; the stability gate sees it once
            assertNull(column(root, id, "fee"), "not judged yet");
            Files.setLastModifiedTime(wider, SETTLED);
            tick(c.svc());   // settled: judged, its column kept, and the gate's second sighting releases it - one run
            assertEquals(46, rows(id), "the run read the file with the schema the pre-run step grew");
            assertNotNull(column(root, id, "fee"), "the column stays kept");
            assertEquals(0, c.svc().statusStore().quarantine(c.svc().configFor(id).orElseThrow()).size(), "nothing set aside");
        }
    }
}
