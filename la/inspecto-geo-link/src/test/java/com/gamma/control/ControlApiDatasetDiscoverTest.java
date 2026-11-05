package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.EventLog;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-BASIC-DISCOVER-1 over real HTTP: {@code GET /inv/datasets/{id}/discover} answers a Dataset's rows, period, distinct
 * entities per Entity Type, kinds of link, Measure totals (with their units, read from the column roles) and busiest entities;
 * masks the busiest ids like exploration (every body SCANNED for the planted raw ids); says why a section was not measured
 * instead of a zero; reads the busiest from a link index when one exists; caches until the Dataset's files change; refuses
 * in gate order; and is audited.
 */
class ControlApiDatasetDiscoverTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String A = "27820000001", B = "27820000002", C = "27820000003", D = "27820000004", E = "27820000005";
    static final List<String> RAW = List.of(A, B, C, D, E);
    private static final HttpClient client = HttpClient.newHttpClient();

    /** The running server over the synthetic Datasets (shared with {@code ControlApiEntitySummaryTest}). */
    record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private static String row(String from, String to, String kind, int amount, String day) {
        return "('" + from + "','" + to + "','" + kind + "'," + amount + ",TIMESTAMP '" + day + " 10:00:00')";
    }

    /** Columns with roles (LA-COLUMN-ROLES-1) and a link mapping naming its primaries. */
    private static Map<String, Object> paysEntry(String view) {
        return Map.of("view", view,
                "columns", List.of(
                        Map.of("name", "caller", "classification", "MSISDN", "role", "entity"),
                        Map.of("name", "callee", "classification", "MSISDN", "role", "entity"),
                        Map.of("name", "link_kind", "role", "dimension"),
                        Map.of("name", "amount", "role", "measure", "unit", "USD"),
                        Map.of("name", "fee", "role", "measure"),
                        Map.of("name", "ts", "role", "temporal")),
                "defaultLinkMapping", Map.of("from", "caller", "to", "callee", "kind", "link_kind", "primaryTime", "ts",
                        "primaryMeasure", "amount"));
    }

    static Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            String rows = String.join(",",
                    row(A, B, "p2p", 10, "2026-10-01"), row(A, B, "p2p", 20, "2026-10-01"), row(A, C, "p2p", 5, "2026-10-02"),
                    row(B, D, "p2p", 7, "2026-10-03"), row(E, D, "p2p", 2000, "2026-10-04"), row(D, A, "cash_out", 1500, "2026-10-05"));
            ViewStore views = new ViewStore(writeRoot.resolve("views"));
            views.write(new ViewDefinition("pays_view", "flow-x", List.of(),
                    "SELECT caller, callee, link_kind, amount, amount / 100 AS fee, ts FROM (VALUES " + rows
                            + ") AS t(caller,callee,link_kind,amount,ts)", "2026-11-05T00:00:00Z"));
            views.write(new ViewDefinition("pairs_view", "flow-x", List.of(),
                    "SELECT caller, callee FROM (VALUES ('" + A + "','" + B + "'),('" + A + "','" + C + "')) AS t(caller,callee)",
                    "2026-11-05T00:00:00Z"));
            ComponentStore registry = new ComponentStore(writeRoot.resolve("registry"));
            registry.write("dataset", "pays_ds", paysEntry("pays_view"));
            registry.write("dataset", "pairs_ds", Map.of("view", "pairs_view", "columns", List.of(
                    Map.of("name", "caller", "classification", "MSISDN"), Map.of("name", "callee", "classification", "MSISDN"))));
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "domain_profile: mobile-money\n" + settings);
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    static HttpResponse<String> get(Ctx c, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    /** A 200 answer's data; the planted raw ids must appear in the body only when {@code rawShown}. */
    private JsonNode discover(Ctx c, String dataset, boolean rawShown) throws Exception {
        HttpResponse<String> r = get(c, "/inv/datasets/" + dataset + "/discover");
        assertEquals(200, r.statusCode(), r.body());
        if (!rawShown) for (String raw : RAW) assertFalse(r.body().contains(raw), "leaked the raw id " + raw + ": " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static List<String> list(JsonNode rows, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : rows) out.add(r.get(field).asText());
        return out;
    }

    @Test
    void everySectionReadsTheDatasetsRoles(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            JsonNode d = discover(c, "pays_ds", true);
            assertEquals(6, d.get("rows").asLong(), d.toString());
            assertFalse(d.get("cached").asBoolean(), "a view has no input files: never cached");
            assertEquals("ts", d.at("/roles/time").asText());

            JsonNode p = d.get("period");
            assertTrue(p.get("assessed").asBoolean(), p.toString());
            assertEquals("2026-10-01T10:00:00Z", p.get("from").asText());
            assertEquals("2026-10-05T10:00:00Z", p.get("to").asText());
            assertEquals(5, p.get("days").asInt());

            JsonNode types = d.at("/entityTypes/items");
            assertEquals(1, types.size(), types.toString());
            JsonNode cols = types.get(0).get("columns");
            assertEquals(List.of("caller", "callee"), List.of(cols.get(0).asText(), cols.get(1).asText()));
            assertEquals(5, types.get(0).get("distinct").asLong(), "five numbers across both ends: " + types);
            assertTrue(types.get(0).get("approximate").asBoolean());

            JsonNode kinds = d.at("/linkKinds/items");
            assertEquals(List.of("p2p", "cash_out"), list(kinds, "kind"));
            assertEquals(List.of("5", "1"), list(kinds, "links"));

            // every Measure column, with its unit - not only the mapping's primary
            JsonNode m = d.at("/measures/items");
            assertEquals(List.of("amount", "fee"), list(m, "column"), m.toString());
            assertEquals("USD", m.get(0).get("unit").asText());
            assertTrue(m.get(1).get("unit").isNull());
            assertEquals(3542.0, m.get(0).get("sum").asDouble());
            assertEquals(6, m.get(0).get("rows").asLong());
            assertTrue(m.get(0).get("median").isNumber(), m.toString());

            JsonNode busy = d.get("busiest");
            assertTrue(busy.get("assessed").asBoolean(), busy.toString());
            assertEquals(A, busy.get("from").get(0).get("entity").asText(), "A sends to two others: " + busy);
            assertEquals(2, busy.get("from").get(0).get("counterparts").asInt());
            assertEquals(3, busy.get("from").get(0).get("links").asInt());
            assertEquals(D, busy.get("to").get(0).get("entity").asText(), "D is reached by two others: " + busy);
            assertEquals("dataset", busy.at("/source/kind").asText());
            assertEquals("msisdn", busy.get("fromType").asText(), "what a busiest row's summary asks for");
            assertEquals("msisdn", busy.get("toType").asText());
        }
    }

    @Test
    void theBusiestLeaveAsAliasesWhenTheSpaceMasks(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            JsonNode d = discover(c, "pays_ds", false);
            assertTrue(d.at("/masking/masked").asBoolean(), d.toString());
            assertTrue(d.at("/busiest/from/0/entity").asText().startsWith("masked:"), d.toString());
            assertEquals(5, d.at("/entityTypes/items/0/distinct").asLong(), "counts are still shown");
            assertTrue(d.at("/entityTypes/items/0/masked").asBoolean());
        }
    }

    @Test
    void aSectionWithoutItsRoleSaysWhyNeverZero(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            JsonNode d = discover(c, "pairs_ds", true);
            assertEquals(2, d.get("rows").asLong());
            for (String s : List.of("period", "linkKinds", "measures")) {
                assertFalse(d.at("/" + s + "/assessed").asBoolean(), s + ": " + d);
                assertFalse(d.at("/" + s + "/reason").asText().isBlank(), s + " says why: " + d);
            }
            assertTrue(d.at("/period/days").isNull(), "an unmeasured period is null, never 0");
            assertTrue(d.at("/busiest/assessed").asBoolean(), "From and To come from the classified columns");
        }
    }

    @Test
    void aBusiestReadPastTheTimeoutIsNotAssessedAndNamesTheIndex(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            ControlApiEntityProfileScaleTest.Faulty f = new ControlApiEntityProfileScaleTest.Faulty();
            f.error = "INTERRUPT Error: Interrupted!";   // what DuckDB's statement timeout surfaces as
            f.fail = sql -> sql.contains("COUNT(DISTINCT");
            com.gamma.la.core.DatasetProviders.forTest(f);
            try {
                JsonNode d = discover(c, "pays_ds", true);
                JsonNode busy = d.get("busiest");
                assertFalse(busy.get("assessed").asBoolean(), busy.toString());
                assertTrue(busy.get("reason").asText().contains("link index"), busy.toString());
                assertFalse(busy.get("reason").asText().contains("INTERRUPT"), "the driver's words stay out: " + busy);
                assertEquals(0, busy.get("from").size());
                assertTrue(d.at("/measures/assessed").asBoolean(), "the other sections still answer: " + d);
            } finally {
                com.gamma.la.core.DatasetProviders.forTest(null);
            }
        }
    }

    @Test
    void withALinkIndexTheBusiestComeFromItsNodesTable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            JsonNode flat = discover(c, "pays_ds", true).get("busiest");
            Authenticators.forTest(ex -> "Bearer builder".equals(ex.getRequestHeaders().getFirst("Authorization"))
                    ? Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex"))) : Optional.empty());
            try {
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/inv/index/builds"))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer builder")
                        .POST(BodyPublishers.ofString("{\"dataset\":\"pays_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"timeCol\":\"ts\"}"))
                        .build(), BodyHandlers.ofString());
                assertEquals(202, r.statusCode(), r.body());
                String id = JSON.readTree(r.body()).at("/data/buildId").asText();
                long end = System.nanoTime() + 30_000_000_000L;
                String status;
                do {
                    status = JSON.readTree(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                            + "/api/v1/inv/index/builds/" + id)).header("Authorization", "Bearer builder").GET().build(),
                            BodyHandlers.ofString()).body()).at("/data/status").asText();
                    if (System.nanoTime() > end) throw new AssertionError("build " + id + " is still " + status);
                    Thread.sleep(10);
                } while (!status.equals("COMPLETED") && !status.equals("FAILED") && !status.equals("CANCELLED"));
                assertEquals("COMPLETED", status);
            } finally {
                Authenticators.forTest(null);
            }
            JsonNode indexed = discover(c, "pays_ds", true).get("busiest");
            assertEquals("index", indexed.at("/source/kind").asText(), indexed.toString());
            assertEquals(list(flat.get("from"), "entity"), list(indexed.get("from"), "entity"), "the index ranks as the Dataset does");
            assertEquals(list(flat.get("to"), "entity"), list(indexed.get("to"), "entity"));
        }
    }

    @Test
    void aFileBackedDatasetIsCachedUntilAFileArrives(@TempDir Path cfg, @TempDir Path root) throws Exception {
        java.util.concurrent.atomic.AtomicLong tick = new java.util.concurrent.atomic.AtomicLong();
        InputFingerprintCache.forTest(() -> tick.addAndGet(60_000L), 30_000L);   // every read lists the files again
        String store = "discover_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            new ComponentStore(root.resolve("registry")).write("dataset", "f_ds", Map.of("physicalRef", store,
                    "columns", List.of(Map.of("name", "s", "classification", "MSISDN"), Map.of("name", "t", "classification", "MSISDN"))));
            plant(dir.resolve("p1.parquet"), "('" + A + "','" + B + "')", 1_700_000_000_000L);
            JsonNode first = discover(c, "f_ds", true);
            assertFalse(first.get("cached").asBoolean(), first.toString());
            assertEquals(1, first.get("rows").asLong());
            JsonNode second = discover(c, "f_ds", true);
            assertTrue(second.get("cached").asBoolean(), "the same files: served from the cache");
            assertEquals(first.get("computedAt").asText(), second.get("computedAt").asText());

            plant(dir.resolve("p2.parquet"), "('" + C + "','" + D + "')", 1_700_000_100_000L);   // an arrival
            JsonNode third = discover(c, "f_ds", true);
            assertFalse(third.get("cached").asBoolean(), "a new File recomputes");
            assertEquals(2, third.get("rows").asLong());
        } finally {
            InputFingerprintCache.forTest(null, 0);
            rmTree(dir);
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void aFailedOrTimedOutReadIsNeverCachedAndAMaskingChangeOrANewIndexRecomputes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        java.util.concurrent.atomic.AtomicLong tick = new java.util.concurrent.atomic.AtomicLong();
        InputFingerprintCache.forTest(() -> tick.addAndGet(60_000L), 30_000L);
        String store = "discover_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            new ComponentStore(root.resolve("registry")).write("dataset", "f_ds", Map.of("physicalRef", store,
                    "columns", List.of(Map.of("name", "s", "classification", "MSISDN"), Map.of("name", "t", "classification", "MSISDN"))));
            plant(dir.resolve("p1.parquet"), "('" + A + "','" + B + "'),('" + A + "','" + C + "')", 1_700_000_000_000L);

            ControlApiEntityProfileScaleTest.Faulty f = new ControlApiEntityProfileScaleTest.Faulty();
            f.error = "INTERRUPT Error: Interrupted!";
            f.fail = sql -> sql.contains("COUNT(DISTINCT");   // the busiest, over the Dataset
            com.gamma.la.core.DatasetProviders.forTest(f);
            try {
                JsonNode timedOut = discover(c, "f_ds", true);
                assertFalse(timedOut.at("/busiest/assessed").asBoolean(), timedOut.toString());
                f.fail = sql -> sql.startsWith("SELECT COUNT(*) AS n");   // the one pass: rows, period, Measures
                f.error = "IO Error: the file went away";
                JsonNode failed = discover(c, "f_ds", true);
                assertFalse(failed.get("cached").asBoolean(), "a timed-out read was not kept: " + failed);
                assertTrue(failed.get("rows").isNull(), failed.toString());
                assertFalse(failed.get("rowsReason").asText().isBlank(), "an unmeasured count says why: " + failed);
            } finally {
                com.gamma.la.core.DatasetProviders.forTest(null);
            }
            JsonNode ok = discover(c, "f_ds", true);
            assertFalse(ok.get("cached").asBoolean(), "a failed read was not kept either: " + ok);
            assertEquals(2, ok.get("rows").asLong());
            assertTrue(discover(c, "f_ds", true).get("cached").asBoolean(), "a whole answer is kept");
            assertFalse(ok.at("/entityTypes/items/0/masked").asBoolean());

            // the Space starts masking everything: the cached answer is not reused
            Files.writeString(root.resolve("link-analysis.toon"), "domain_profile: mobile-money\nmasking_mode: all\n");
            JsonNode masked = discover(c, "f_ds", false);
            assertFalse(masked.get("cached").asBoolean(), masked.toString());
            assertTrue(masked.at("/entityTypes/items/0/masked").asBoolean(), masked.toString());

            // a link index built since answers the busiest at once, not after the next arrival
            Files.writeString(root.resolve("link-analysis.toon"), "domain_profile: mobile-money\nmasking_mode: none\n");
            assertEquals("dataset", discover(c, "f_ds", true).at("/busiest/source/kind").asText());
            buildIndex(c, "f_ds", "s", "t");
            JsonNode indexed = discover(c, "f_ds", true);
            assertFalse(indexed.get("cached").asBoolean(), indexed.toString());
            assertEquals("index", indexed.at("/busiest/source/kind").asText(), indexed.toString());
        } finally {
            InputFingerprintCache.forTest(null, 0);
            rmTree(dir);
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    private void buildIndex(Ctx c, String dataset, String from, String to) throws Exception {
        Authenticators.forTest(ex -> "Bearer builder".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex"))) : Optional.empty());
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/inv/index/builds"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer builder")
                    .POST(BodyPublishers.ofString("{\"dataset\":\"" + dataset + "\",\"sourceCol\":\"" + from + "\",\"targetCol\":\"" + to + "\"}"))
                    .build(), BodyHandlers.ofString());
            assertEquals(202, r.statusCode(), r.body());
            String id = JSON.readTree(r.body()).at("/data/buildId").asText();
            long end = System.nanoTime() + 30_000_000_000L;
            String status;
            do {
                status = JSON.readTree(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                        + "/api/v1/inv/index/builds/" + id)).header("Authorization", "Bearer builder").GET().build(),
                        BodyHandlers.ofString()).body()).at("/data/status").asText();
                if (System.nanoTime() > end) throw new AssertionError("build " + id + " is still " + status);
                Thread.sleep(10);
            } while (!status.equals("COMPLETED") && !status.equals("FAILED") && !status.equals("CANCELLED"));
            assertEquals("COMPLETED", status);
        } finally {
            Authenticators.forTest(null);
        }
    }

    @Test
    void refusesInGateOrderAndAuditsEveryRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            assertEquals(404, get(c, "/inv/datasets/nope/discover").statusCode());
            new ComponentStore(root.resolve("registry")).write("dataset", "drifted_ds", Map.of("view", "pays_view",
                    "columns", List.of(Map.of("name", "caller", "classification", "MSISDN")),
                    "defaultLinkMapping", Map.of("from", "caller", "to", "agent_id")));
            HttpResponse<String> drifted = get(c, "/inv/datasets/drifted_ds/discover");
            assertEquals(422, drifted.statusCode(), drifted.body());
            assertTrue(drifted.body().contains("agent_id"), drifted.body());

            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                discover(c, "pays_ds", true);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            Event e = seen.stream().filter(x -> LinkEventTypes.LINK_DATASET_DISCOVERED.equals(x.type())).findFirst().orElseThrow();
            assertEquals("pays_ds", e.attributes().get("dataset"));
            for (String raw : RAW) assertFalse(String.valueOf(e).contains(raw), "no entity id in the audit: " + e);
        }
    }

    /**
     * ENTITY-TYPE-RECORD-ROLES-1: {@code GET /inv/datasets/{id}/shapes} reads a sample unmasked on the server and answers
     * only which Entity Types' shapes each column fits - under the Space's default masking no raw id leaves.
     */
    @Test
    void shapesAnswerMarksPerColumnNeverAValue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "")) {
            HttpResponse<String> r = get(c, "/inv/datasets/pays_ds/shapes");
            assertEquals(200, r.statusCode(), r.body());
            for (String raw : RAW) assertFalse(r.body().contains(raw), "leaked the raw id " + raw + ": " + r.body());
            JsonNode d = JSON.readTree(r.body()).get("data");
            assertEquals(6, d.get("sampled").asInt());
            assertTrue(list(d.at("/columns/caller"), "type").contains("msisdn"), d.toString());
            for (JsonNode fit : d.at("/columns/caller"))
                if (fit.get("type").asText().equals("msisdn"))
                    assertFalse(fit.get("strong").asBoolean(), "bare digits are the weak reading of a phone number");
            assertEquals(0, d.at("/columns/link_kind").size(), "p2p fits no shape: " + d);
            assertEquals(404, get(c, "/inv/datasets/nope/shapes").statusCode());
        }
    }

    private static void plant(Path file, String values, long mtime) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("discover_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS v(s, t)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    }

    private static void rmTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
