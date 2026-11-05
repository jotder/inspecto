package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code LA-DATASET-COMPACT-1} over real HTTP: an import compacts its files on its own past the threshold (every edition,
 * D-7), every arrival keeps its period through the merge map (D-8), Compact now, a Replace everything resets the map,
 * retention on Standard by arrivals and by event date (D-9) and never on Basic, the crash heal, and the gates. Synthetic
 * fixtures only: transfers on a stated day.
 */
class ControlApiDatasetCompactionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPLOADER = "Bearer uploader", AUTHOR = "Bearer author", ANALYST = "Bearer analyst", ADMIN = "Bearer admin";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Authenticators.forTest(null);
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
            // Add a file and Run now share the standard bucket (burst 20, one more every 3 s): compaction needs 21+ arrivals,
            // so on the wall clock the test passed only when it ran slower than the refill. Each read of this clock is 3 s
            // later - one token back per request, whatever the machine's speed.
            java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
            api.useRateLimitClock(() -> nanos.addAndGet(3_000_000_000L));
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> json(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), auth);
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    /** {@code rows} transfers all booked on {@code day} ({@code yyyy-MM-dd}), ids prefixed so files differ. */
    static String transfersOn(String day, int rows) {
        StringBuilder sb = new StringBuilder("transfer_id,from_account,to_account,channel,amount,booked_at,currency\n");
        for (int i = 1; i <= rows; i++) {
            int from = (i * 7) % 40 + 1, to = (i * 13 + 5) % 40 + 1;
            if (to == from) to = to % 40 + 1;
            sb.append(String.format("T%s-%04d,ACC-%04d,ACC-%04d,mobile,%d.00,%s %02d:%02d:00,EUR\n", day.replace("-", ""), i, from, to,
                    10 + i, day, i % 24, i % 60));
        }
        return sb.toString();
    }

    private String imported(Ctx c, String base, String content, String auth) throws Exception {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", content.getBytes(StandardCharsets.UTF_8), auth), 200);
        data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", auth), 200);
        return id;
    }

    private JsonNode append(Ctx c, String id, String name, String content, String auth) throws Exception {
        return data(send(c, "POST", "/datasets/" + id + "/files?name=" + name + "&mode=append", content.getBytes(StandardCharsets.UTF_8), auth), 200);
    }

    private static int parquetFiles(String id) throws Exception {
        return DatasetCompaction.parquetFiles(Path.of("database", id, "database").toAbsolutePath().normalize()).size();
    }

    private static String day(int d) {
        return String.format("2026-09-%02d", d);
    }

    // ── compaction on its own, and the merge map ─────────────────────────────────────────────────────────────

    @Test
    void anImportCompactsPastTwentyFilesAndEveryArrivalKeepsItsPeriod(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic: compaction is every edition's
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "compacted", transfersOn(day(1), 5), null);
            for (int d = 2; d <= DatasetCompaction.THRESHOLD; d++) {
                JsonNode s = append(c, id, "day" + d + ".csv", transfersOn(day(d), 5), null);
                assertEquals("NOT_NEEDED", s.at("/compaction/result").asText(), s.toString());
            }
            assertEquals(DatasetCompaction.THRESHOLD, parquetFiles(id), "one file per arrival so far");
            JsonNode s = append(c, id, "day21.csv", transfersOn(day(21), 5), null);
            assertEquals("COMPACTED", s.at("/compaction/result").asText(), s.toString());
            assertEquals(1, parquetFiles(id), "every arrival in one file");
            assertEquals(105, OnboardImport.countRows(Path.of("database", id, "database")), "no row lost or doubled");

            Map<String, DatasetCompaction.Segment> merged = DatasetCompaction.merged(Path.of("database"), id);
            assertEquals(21, merged.size(), merged.keySet().toString());
            long first = 0;
            int d = 1;
            for (DatasetCompaction.Segment seg : merged.values()) {   // in arrival order, each its own day
                assertEquals(first, seg.firstRow(), seg.toString());
                assertEquals(5, seg.rows(), seg.toString());
                assertTrue(seg.coversFrom().startsWith(day(d)) && seg.coversTo().startsWith(day(d)), seg + " covers " + day(d));
                first += seg.rows();
                d++;
            }
            assertTrue(merged.keySet().stream().anyMatch(f -> f.contains("_day21_")), merged.keySet().toString());

            JsonNode storage = data(json(c, "GET", "/datasets/" + id + "/storage", null, null), 200);
            assertEquals(1, storage.get("files").asInt(), storage.toString());
            assertEquals(21, storage.get("compactedArrivals").asInt());
            assertFalse(storage.get("compactedAt").isNull());
            assertFalse(storage.get("retentionAvailable").asBoolean(), "Basic keeps every file");

            // the arrivals answer reads the merge map (D-8): every merged arrival is still kept, with its own day; and it carries
            // the storage and feed lines of Data status (one read)
            JsonNode listed = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200);
            assertEquals(21, listed.get("arrivals").size(), listed.toString());
            for (JsonNode a : listed.get("arrivals")) {
                assertTrue(a.get("kept").asBoolean(), a.toString());
                assertEquals(a.get("coversFrom").asText().substring(0, 10), a.get("coversTo").asText().substring(0, 10), a.toString());
            }
            assertTrue(listed.get("arrivals").get(0).get("coversFrom").asText().startsWith(day(21)), listed.toString());
            assertEquals(1, listed.at("/storage/files").asInt(), listed.toString());
            assertEquals("uploads", listed.at("/feed/kind").asText(), listed.toString());
            assertFalse(listed.get("feed").has("inbox"), "the arrivals read never names the server path");

            // one more arrival: two files, under the threshold; Compact now merges them, the older arrivals' map carried over
            append(c, id, "day22.csv", transfersOn(day(22), 5), null);
            assertEquals(2, parquetFiles(id));
            JsonNode now = data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
            assertEquals("COMPACTED", now.at("/compaction/result").asText(), now.toString());
            assertEquals(1, now.get("files").asInt());
            assertEquals(22, now.get("compactedArrivals").asInt());
            assertEquals(110, OnboardImport.countRows(Path.of("database", id, "database")));
            DatasetCompaction.Segment last = new ArrayList<>(DatasetCompaction.merged(Path.of("database"), id).values()).get(21);
            assertEquals(105, last.firstRow(), last.toString());
            assertTrue(last.coversFrom().startsWith(day(22)), last.toString());

            // Replace the period rewrites the merged file (day 5 goes): its segments no longer hold, so its arrivals stay kept with
            // no known period - and the next compaction carries it as ONE block, never slicing it by stale offsets
            JsonNode period = data(send(c, "POST", "/datasets/" + id + "/files?name=day5again.csv&mode=period",
                    transfersOn(day(5), 3).getBytes(StandardCharsets.UTF_8), null), 200);
            assertEquals(5, period.at("/period/rowsReplaced").asLong(), period.toString());
            long afterPeriod = OnboardImport.countRows(Path.of("database", id, "database"));
            assertEquals(108, afterPeriod);
            Map<String, DatasetCompaction.Segment> stale = DatasetCompaction.merged(Path.of("database"), id);
            assertEquals(22, stale.size());
            assertTrue(stale.values().stream().allMatch(seg -> seg.rows() < 0 && seg.coversFrom() == null), stale.toString());
            JsonNode recompacted = data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
            assertEquals("COMPACTED", recompacted.at("/compaction/result").asText(), recompacted.toString());
            assertEquals(afterPeriod, OnboardImport.countRows(Path.of("database", id, "database")), "no row lost or doubled");
            Map<String, DatasetCompaction.Segment> carried = DatasetCompaction.merged(Path.of("database"), id);
            assertEquals(24, carried.size(), "the 22 held arrivals, the block itself and the new upload: " + carried.keySet());
            assertEquals(23, data(json(c, "GET", "/datasets/" + id + "/storage", null, null), 200).get("compactedArrivals").asInt(),
                    "the block's own old file is not an arrival");
            assertTrue(carried.keySet().stream().anyMatch(f -> f.contains("_day5again_")), carried.keySet().toString());
            assertTrue(data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals").findValues("kept")
                    .stream().allMatch(JsonNode::asBoolean), "every arrival still kept");

            // a Replace everything sets the folder aside: the map falls silent with it
            data(send(c, "POST", "/datasets/" + id + "/files?name=fresh.csv&mode=replace",
                    transfersOn(day(28), 4).getBytes(StandardCharsets.UTF_8), null), 200);
            assertTrue(DatasetCompaction.merged(Path.of("database"), id).isEmpty(), "the old merged file is gone");
            assertEquals(0, data(json(c, "GET", "/datasets/" + id + "/storage", null, null), 200).get("compactedArrivals").asInt());
        }
    }

    /**
     * LA-DATASET-COMPACT-2 (a): a run of the import Pipeline that does not pass through Add data - the scheduler's run of a
     * watched folder or SFTP feed (Standard) - compacts on its commit too, before the Space's Jobs (the index build) see it.
     * While an import, Compact now or a delete holds the Dataset, the commit leaves it: that holder compacts itself.
     */
    @Test
    void aRunOutsideAddDataCompactsOnItsCommit(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));   // Standard: a feed runs on the scheduler
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "scheduled", transfersOn(day(1), 5), null);
            for (int d = 2; d <= DatasetCompaction.THRESHOLD; d++) append(c, id, "day" + d + ".csv", transfersOn(day(d), 5), null);
            assertEquals(DatasetCompaction.THRESHOLD, parquetFiles(id));
            Path inbox = Path.of("database", "inbox", id).toAbsolutePath();

            // held (an import in flight): the commit does not compact behind the holder's back
            String key = c.root() + "|" + id;
            assertTrue(OnboardImport.IN_FLIGHT.add(key));
            try {
                Files.createDirectories(inbox);
                Files.writeString(inbox.resolve("feed21.csv"), transfersOn(day(21), 5));
                assertTrue(c.svc().runPipeline(id).isPresent(), "the import Pipeline ran");
            } finally {
                OnboardImport.IN_FLIGHT.remove(key);
            }
            assertEquals(DatasetCompaction.THRESHOLD + 1, parquetFiles(id), "left to the run that holds the Dataset");

            // the scheduler's run: nobody holds the Dataset, so its commit compacts
            Files.writeString(inbox.resolve("feed22.csv"), transfersOn(day(22), 5));
            assertTrue(c.svc().runPipeline(id).isPresent(), "the import Pipeline ran");
            assertEquals(1, parquetFiles(id), "the feed's run compacted every file into one");
            assertEquals(110, OnboardImport.countRows(Path.of("database", id, "database")), "no row lost or doubled");
            assertEquals(22, data(json(c, "GET", "/datasets/" + id + "/storage", null, null), 200).get("compactedArrivals").asInt());
        }
    }

    // ── retention (Standard and up) ──────────────────────────────────────────────────────────────────────────

    @Test
    void retentionKeepsTheNewestArrivalsOrTheRecentDaysOnStandardOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            EditionFeatures.overrideForTest(Set.of());   // Basic: no policy, and one stored by hand is ignored
            String id = imported(c, "kept", transfersOn("2020-01-01", 4), null);
            HttpResponse<String> basic = json(c, "PUT", "/datasets/" + id + "/storage", "{\"retention\":{\"arrivals\":1}}", null);
            assertEquals(503, basic.statusCode(), basic.body());
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            Map<String, Object> entry = new LinkedHashMap<>(registry.get("dataset", id).orElseThrow().content());
            entry.put(DatasetCompaction.RETENTION, Map.of("arrivals", 1));
            registry.write("dataset", id, entry);
            append(c, id, "b.csv", transfersOn("2020-01-02", 4), null);
            data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
            assertEquals(8, OnboardImport.countRows(Path.of("database", id, "database")), "Basic never drops rows");

            EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
            // keep 2 arrivals: the merged pair counts as its two arrivals, so the oldest goes when a third arrives
            JsonNode set = data(json(c, "PUT", "/datasets/" + id + "/storage", "{\"retention\":{\"arrivals\":2}}", null), 200);
            assertEquals(2, set.at("/retention/arrivals").asInt(), set.toString());
            assertTrue(set.get("retentionAvailable").asBoolean());
            append(c, id, "c.csv", transfersOn("2099-01-03", 4), null);
            JsonNode kept = data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
            assertEquals(4, kept.at("/compaction/rowsRemoved").asLong(), kept.toString());
            assertEquals(1, kept.get("removedArrivals").asInt());
            List<DatasetCompaction.Segment> segs = new ArrayList<>(DatasetCompaction.merged(Path.of("database"), id).values());
            assertEquals(2, segs.size(), segs.toString());
            assertTrue(segs.get(0).coversFrom().startsWith("2020-01-02") && segs.get(1).coversFrom().startsWith("2099-01-03"), segs.toString());

            // keep 30 days, by the event date (booked_at): 2020 rows go, 2099 rows stay
            data(json(c, "PUT", "/datasets/" + id + "/storage", "{\"retention\":{\"days\":30}}", null), 200);
            JsonNode byDay = data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
            assertEquals(4, byDay.at("/compaction/rowsRemoved").asLong(), byDay.toString());
            assertEquals(4, OnboardImport.countRows(Path.of("database", id, "database")));
            assertEquals(1, DatasetCompaction.merged(Path.of("database"), id).size());

            // a date that cannot be read never makes a row go: the When column pointed at the currency ('EUR')
            Map<String, Object> odd = new LinkedHashMap<>(registry.get("dataset", id).orElseThrow().content());
            odd.put(DatasetRoutes.EVENT_DATE_KEY, "currency");
            registry.write("dataset", id, odd);
            data(json(c, "PUT", "/datasets/" + id + "/storage", "{\"retention\":{\"days\":1}}", null), 200);
            append(c, id, "d.csv", transfersOn("2020-02-02", 4), null);
            JsonNode unreadable = data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
            assertEquals(0, unreadable.at("/compaction/rowsRemoved").asLong(), unreadable.toString());
            assertEquals(8, OnboardImport.countRows(Path.of("database", id, "database")));
            // a When column the files do not have: compaction still runs (by when the files came: today, so all stay)
            odd.put(DatasetRoutes.EVENT_DATE_KEY, "no_such_column");
            registry.write("dataset", id, odd);
            JsonNode missing = data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
            assertEquals("COMPACTED", missing.at("/compaction/result").asText(), missing.toString());
            assertEquals(8, OnboardImport.countRows(Path.of("database", id, "database")));
            odd.put(DatasetRoutes.EVENT_DATE_KEY, "booked_at");   // back to what Add data wrote
            registry.write("dataset", id, odd);

            // null clears the policy
            JsonNode cleared = data(json(c, "PUT", "/datasets/" + id + "/storage", "{\"retention\":null}", null), 200);
            assertTrue(cleared.get("retention").isNull(), cleared.toString());

            for (String bad : List.of("{}", "{\"retention\":{\"days\":0}}", "{\"retention\":{\"days\":1.5}}",
                    "{\"retention\":{\"days\":3,\"arrivals\":2}}", "{\"retention\":{\"weeks\":2}}", "{\"retention\":7}")) {
                HttpResponse<String> r = json(c, "PUT", "/datasets/" + id + "/storage", bad, null);
                assertEquals(422, r.statusCode(), bad + " -> " + r.body());
            }

            // an approval policy over Datasets refuses the policy change
            Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  dataset:\n    required: true\n");
            HttpResponse<String> held = json(c, "PUT", "/datasets/" + id + "/storage", "{\"retention\":{\"days\":3}}", null);
            assertEquals(409, held.statusCode(), held.body());
            assertTrue(error(held).contains("approval policy"), held.body());
        }
    }

    // ── crash heal ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSwapACrashInterruptedIsFinishedOrDropped(@TempDir Path data) throws Exception {
        Path db = Files.createDirectories(data.resolve("ds").resolve("database"));
        Files.writeString(db.resolve("a.parquet"), "x");
        Files.writeString(db.resolve("compacted_1.parquet"), "x");
        Path pending = data.resolve("ds").resolve(DatasetCompaction.MAP + ".pending");
        Map<String, Object> map = Map.of("merged", "compacted_1.parquet", "arrivals", List.of(Map.of("file", "a.parquet", "rows", 1)));
        Files.writeString(pending, JSON.writeValueAsString(Map.of("merged", "compacted_1.parquet", "inputs",
                List.of("a.parquet", "compacted_1.parquet", "../escape.parquet"), "map", map)));
        Files.writeString(data.resolve("ds").resolve("escape.parquet"), "outside the database folder");
        DatasetCompaction.heal(data, "ds");
        assertFalse(Files.exists(pending));
        assertFalse(Files.exists(db.resolve("a.parquet")), "the merged file moved in: its inputs go");
        assertTrue(Files.exists(db.resolve("compacted_1.parquet")), "never the merged file itself");
        assertTrue(Files.exists(data.resolve("ds").resolve("escape.parquet")), "never a path outside the folder");
        assertEquals(1, DatasetCompaction.merged(data, "ds").size(), "the map is committed");

        Files.writeString(db.resolve("b.parquet"), "x");
        Files.writeString(pending, JSON.writeValueAsString(Map.of("merged", "compacted_2.parquet", "inputs", List.of("b.parquet"),
                "map", Map.of("merged", "compacted_2.parquet"))));
        DatasetCompaction.heal(data, "ds");
        assertFalse(Files.exists(pending), "a merged file that never moved in: the swap is dropped");
        assertTrue(Files.exists(db.resolve("b.parquet")), "and its inputs stay");
        assertEquals("compacted_1.parquet", JSON.readTree(data.resolve("ds").resolve(DatasetCompaction.MAP).toFile()).get("merged").asText());

        // an input that cannot be deleted (held, here a non-empty folder) stays named for the next heal; the map is committed
        Path held = Files.createDirectories(db.resolve("held.parquet"));
        Files.writeString(held.resolve("x"), "x");
        Files.writeString(db.resolve("c.parquet"), "x");
        Files.writeString(pending, JSON.writeValueAsString(Map.of("merged", "compacted_1.parquet", "inputs", List.of("held.parquet", "c.parquet"),
                "map", Map.of("merged", "compacted_1.parquet", "at", "now"))));
        DatasetCompaction.heal(data, "ds");
        assertFalse(Files.exists(db.resolve("c.parquet")));
        assertTrue(Files.exists(pending), "kept for a retry");
        assertEquals("[\"held.parquet\"]", JSON.readTree(pending.toFile()).get("inputs").toString());
        assertEquals("now", JSON.readTree(data.resolve("ds").resolve(DatasetCompaction.MAP).toFile()).get("at").asText());
    }

    /**
     * A file a reader holds open (Windows refuses its delete) is merged once, stays named for the next heal, and is never
     * merged a second time while it is held: compaction waits, and once it is released the rows are counted once.
     */
    @Test
    void aFileAReaderHoldsIsMergedOnceNeverTwice(@TempDir Path cfg, @TempDir Path root) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "only Windows refuses to delete a file held open");
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "held", transfersOn(day(1), 5), null);
            append(c, id, "b.csv", transfersOn(day(2), 5), null);
            append(c, id, "c.csv", transfersOn(day(3), 4), null);
            Path db = Path.of("database", id, "database").toAbsolutePath().normalize();
            Path heldFile = DatasetCompaction.parquetFiles(db).stream().filter(p -> p.getFileName().toString().contains("_b_"))
                    .findFirst().orElseThrow();
            try (var ch = Files.newByteChannel(heldFile, Set.of(java.nio.file.StandardOpenOption.READ,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE))) {
                JsonNode first = data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
                assertTrue(first.at("/compaction/message").asText().contains("could not be removed yet"), first.toString());
                JsonNode again = data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
                assertEquals("NOT_NEEDED", again.at("/compaction/result").asText(), "never merged a second time: " + again);
                assertTrue(Files.exists(heldFile));
            }
            JsonNode released = data(json(c, "POST", "/datasets/" + id + "/compact", null, null), 200);
            assertFalse(Files.exists(heldFile), "released: the next compaction's heal removes it");
            assertEquals(14, OnboardImport.countRows(db), "every row once: " + released);
            assertEquals(1, DatasetCompaction.parquetFiles(db).size());
        }
    }

    // ── gates (armed: 401 / 403 / 200) ───────────────────────────────────────────────────────────────────────

    @Test
    void storageNeedsCanUploadDataAndRetentionCanAuthorWorkbench(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case UPLOADER -> Optional.of(new Subject("uploader-1", Set.of("canUploadData")));
            case AUTHOR -> Optional.of(new Subject("author-1", Set.of("canUploadData", "canAuthorWorkbench")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canUploadData")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            data(json(c, "PUT", "/settings/data", "{\"selfImport\":true}", ADMIN), 200);
            String id = imported(c, "gated", transfersOn(day(3), 4), UPLOADER);
            String storage = "/datasets/" + id + "/storage", compact = "/datasets/" + id + "/compact", body = "{\"retention\":{\"days\":3}}";
            assertEquals(401, json(c, "GET", storage, null, null).statusCode());
            assertEquals(401, json(c, "PUT", storage, body, null).statusCode());
            assertEquals(401, json(c, "POST", compact, null, null).statusCode());
            assertEquals(403, json(c, "GET", storage, null, ANALYST).statusCode());
            assertEquals(403, json(c, "POST", compact, null, ANALYST).statusCode());
            assertEquals(403, json(c, "PUT", storage, body, UPLOADER).statusCode(), "a retention policy is a Dataset change");
            data(json(c, "GET", storage, null, UPLOADER), 200);
            data(json(c, "POST", compact, null, UPLOADER), 200);
            data(json(c, "PUT", storage, body, AUTHOR), 200);

            String key = c.root() + "|" + id;   // claimed like an import
            OnboardImport.IN_FLIGHT.add(key);
            try {
                assertEquals(409, json(c, "POST", compact, null, UPLOADER).statusCode());
            } finally {
                OnboardImport.IN_FLIGHT.remove(key);
            }
            assertEquals(404, json(c, "GET", "/datasets/no_such_import/storage", null, UPLOADER).statusCode());
        }
    }
}
