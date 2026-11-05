package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ONBOARDING-FIRST-RUN-1} C2, the fix catalogue ({@link OnboardFixes}): for EVERY entry, a file that has the
 * problem, the finding that names it (in the draft, a commit refusal, a quarantine reason or an Add a file refusal), its
 * one-click fix applied exactly as the finding states it, and the re-run that then imports the file. Real HTTP, synthetic
 * fixtures only. {@link #everyEntryHasATestThatAppliesItsFix} fails when an entry is added without such a test.
 */
class ControlApiOnboardFixesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The entries whose fix a test below applied and re-ran. */
    private static final Set<String> FIXED = ConcurrentHashMap.newKeySet();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                java.nio.file.Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    @Test
    void everyEntryHasATestThatAppliesItsFix() {
        for (String entry : OnboardFixes.ENTRIES) {
            String[] parts = entry.split("_");
            StringBuilder m = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) m.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            java.lang.reflect.Method test = null;
            for (java.lang.reflect.Method x : getClass().getDeclaredMethods()) if (x.getName().equals(m.toString())) test = x;
            assertNotNull(test, "catalogue entry '" + entry + "' needs a test " + m + "() that applies its fix and re-runs");
            assertTrue(test.isAnnotationPresent(Test.class), m.toString());
        }
    }

    @AfterAll
    static void everyEntryTestReachedItsReRun() {
        // a test that ran to its end applied its fix and re-ran; this holds for whichever entry tests ran
        assertTrue(OnboardFixes.ENTRIES.containsAll(FIXED), FIXED.toString());
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(Ctx c, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        return client.send(b.POST(BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path)).GET().build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private JsonNode draft(Ctx c, String name, byte[] content, String query) throws Exception {
        return data(post(c, "/onboard/files?name=" + name + query, content), 200);
    }

    private HttpResponse<String> commit(Ctx c, JsonNode draft, String id, String extra) throws Exception {
        ids.add(id);
        return post(c, "/onboard/files/" + draft.get("draftId").asText() + "/commit",
                ("{\"name\":\"" + id + "\"" + extra + "}").getBytes(StandardCharsets.UTF_8));
    }

    private String id(String base) {
        return base + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The one finding of {@code entry} in {@code findings}, checked for its shape. */
    private static JsonNode finding(JsonNode findings, String entry) {
        assertNotNull(findings, "findings are present");
        JsonNode hit = null;
        for (JsonNode f : findings) if (entry.equals(f.path("entry").asText())) hit = f;
        assertNotNull(hit, "a '" + entry + "' finding in " + findings);
        assertFalse(hit.path("cause").asText().isBlank(), hit.toString());
        assertFalse(hit.path("fix").path("label").asText().isBlank(), hit.toString());
        return hit;
    }

    private static boolean has(JsonNode findings, String entry) {
        for (JsonNode f : findings) if (entry.equals(f.path("entry").asText())) return true;
        return false;
    }

    /** The finding's {@code fix.redraft} as query parameters: what the screen sends when the user clicks the fix. */
    private static String redraftQuery(JsonNode finding) {
        StringBuilder q = new StringBuilder();
        for (Iterator<Map.Entry<String, JsonNode>> it = finding.get("fix").get("redraft").fields(); it.hasNext(); ) {
            var e = it.next();
            q.append('&').append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue().asText(), StandardCharsets.UTF_8));
        }
        return q.toString();
    }

    private static JsonNode errorFindings(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("details").path("findings");
    }

    private static double parquet(String id, String sql) throws Exception {
        com.gamma.util.DuckDbUtil.loadDriver();
        Path db = Path.of("database", id).toAbsolutePath();
        try (java.sql.Connection conn = com.gamma.util.DuckDbUtil.openInMemory(null, List.of(db), null);
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("WITH t AS (SELECT * FROM read_parquet('" + db.toString().replace('\\', '/')
                     + "/database/**/*.parquet')) " + sql)) {
            assertTrue(rs.next());
            return rs.getDouble(1);
        }
    }

    private static String rows(int n, java.util.function.IntFunction<String> row, String header) {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (int i = 1; i <= n; i++) sb.append(row.apply(i)).append('\n');
        return sb.toString();
    }

    // ── one test per entry ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void decimalMark(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            byte[] file = rows(240, i -> "B" + i + ";" + i + "." + String.format("%03d", i) + ",50", "booking;amount").getBytes(StandardCharsets.UTF_8);
            JsonNode d = draft(c, "kasse.csv", file, "&decimal=.");   // the user said '.' - the amounts read as text
            assertEquals("VARCHAR", d.at("/columns/1/type/value").asText());
            JsonNode f = finding(d.get("findings"), "decimal_mark");
            assertEquals("amount", f.get("column").asText());
            assertTrue(f.get("cause").asText().contains("'1.001,50'"), f.toString());
            assertEquals(",", f.at("/fix/redraft/decimal").asText());

            JsonNode fixed = draft(c, "kasse.csv", file, redraftQuery(f));
            assertFalse(has(fixed.get("findings"), "decimal_mark"), fixed.get("findings").toString());
            String id = id("kasse");
            data(commit(c, fixed, id, ""), 200);
            assertEquals(240.0 * 241 / 2 * 1001 + 120, parquet(id, "SELECT sum(amount) FROM t"), 0.001);
            FIXED.add("decimal_mark");
        }
    }

    @Test
    void dateFormat(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // day-first dates; one is not a real date
            byte[] file = rows(240, i -> "O" + i + "," + (i == 7 ? "31/02/2026" : String.format("%02d/%02d/2026", 13 + i % 15, 1 + i % 12)),
                    "order_id,ordered").getBytes(StandardCharsets.UTF_8);
            JsonNode d = draft(c, "orders.csv", file, "");
            JsonNode f = finding(d.get("findings"), "date_format");
            assertEquals("ordered", f.get("column").asText());
            assertTrue(f.get("cause").asText().contains("'31/02/2026'"), f.toString());
            assertEquals("%d/%m/%Y", f.at("/fix/columns/0/format").asText());

            String id = id("orders");
            data(commit(c, d, id, ",\"columns\":" + f.at("/fix/columns")), 200);
            assertEquals(239, parquet(id, "SELECT count(ordered) FROM t"), "the one unreal date stays empty");
            assertEquals(4, parquet(id, "SELECT count(*) FROM t WHERE ordered = DATE '2026-02-14'"), "read day first (rows 1, 61, 121, 181)");

            // both parts at most 12 (03/04/2026): day first and month first read alike - the finding says so and the
            // user picks; here the alternative, month first, is applied
            byte[] us = rows(240, i -> "U" + i + "," + (i == 9 ? "13/13/2026" : String.format("%02d/%02d/2026", 1 + i % 12, 1 + (i / 12) % 12)),
                    "order_id,ordered").getBytes(StandardCharsets.UTF_8);
            JsonNode du = draft(c, "us.csv", us, "");
            JsonNode a = finding(du.get("findings"), "date_format");
            assertTrue(a.get("cause").asText().contains("%m/%d/%Y"), "the other reading is named: " + a);
            JsonNode alt = a.at("/fix/alternatives/0/columns");
            assertEquals("%m/%d/%Y", alt.at("/0/format").asText(), a.toString());
            String usId = id("us");
            data(commit(c, du, usId, ",\"columns\":" + alt), 200);
            // row 2: 03/01/2026 month first is March 1
            assertEquals(1, parquet(usId, "SELECT count(*) FROM t WHERE order_id = 'U2' AND ordered = DATE '2026-03-01'"));
            FIXED.add("date_format");
        }
    }

    @Test
    void delimiter(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            byte[] file = rows(240, i -> "S" + i + ";" + i + ";north", "store;sales;region").getBytes(StandardCharsets.UTF_8);
            JsonNode d = draft(c, "stores.csv", file, "&delimiter=,");   // a wrong delimiter: one column
            assertEquals(1, d.get("columns").size());
            JsonNode f = finding(d.get("findings"), "delimiter");
            assertEquals(";", f.at("/fix/redraft/delimiter").asText());

            JsonNode fixed = draft(c, "stores.csv", file, redraftQuery(f));
            assertEquals(3, fixed.get("columns").size());
            String id = id("stores");
            data(commit(c, fixed, id, ""), 200);
            assertEquals(240, parquet(id, "SELECT count(DISTINCT store) FROM t"));
            FIXED.add("delimiter");
        }
    }

    @Test
    void headerRow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            byte[] file = rows(240, i -> "M" + i + "," + i * 3 + "," + (i % 5), "meter,reading,zone").getBytes(StandardCharsets.UTF_8);
            JsonNode d = draft(c, "meters.csv", file, "&header=false");
            JsonNode f = finding(d.get("findings"), "header_row");
            assertEquals("true", f.at("/fix/redraft/header").asText());

            JsonNode fixed = draft(c, "meters.csv", file, redraftQuery(f));
            assertEquals("reading", fixed.at("/columns/1/name").asText());
            String id = id("meters");
            data(commit(c, fixed, id, ""), 200);
            assertEquals(240.0 * 241 / 2 * 3, parquet(id, "SELECT sum(reading) FROM t"), 0.001, "no name row among the readings");
            FIXED.add("header_row");
        }
    }

    @Test
    void encoding(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            byte[] file = rows(240, i -> "C" + i + ",Müller " + i + ",Köln", "customer,holder,city").getBytes(StandardCharsets.ISO_8859_1);
            HttpResponse<String> refused = post(c, "/onboard/files?name=customers.csv", file);
            assertEquals(422, refused.statusCode(), refused.body());
            JsonNode f = finding(errorFindings(refused), "encoding");
            assertEquals("latin-1", f.at("/fix/redraft/encoding").asText());

            JsonNode fixed = draft(c, "customers.csv", file, redraftQuery(f));
            assertEquals("latin-1", fixed.at("/format/encoding/value").asText());
            String id = id("customers");
            data(commit(c, fixed, id, ""), 200);
            assertEquals(240, parquet(id, "SELECT count(*) FROM t WHERE city = 'Köln' AND holder LIKE 'Müller %'"), "read as Latin-1, kept as text");

            // past the lines the draft samples: the draft reads it as UTF-8, the commit's run rejects the rows it cannot
            // decode, and its summary names the cause with the same fix
            java.io.ByteArrayOutputStream late = new java.io.ByteArrayOutputStream();
            late.writeBytes(rows(1100, i -> "C" + i + ",Holder " + i + ",Berlin", "customer,holder,city").getBytes(StandardCharsets.UTF_8));
            late.writeBytes(rows(5, i -> "D" + i + ",Müller,Köln", "").substring(1).getBytes(StandardCharsets.ISO_8859_1));
            JsonNode d2 = draft(c, "late.csv", late.toByteArray(), "");
            JsonNode partly = data(commit(c, d2, id("late_bad"), ""), 200);
            assertEquals(5, partly.get("rowsRejected").asLong(), partly.toString());
            JsonNode why = finding(partly.get("findings"), "encoding");
            JsonNode fixedLate = draft(c, "late.csv", late.toByteArray(), redraftQuery(why));
            String lateId = id("late");
            assertEquals(1105, data(commit(c, fixedLate, lateId, ""), 200).get("rowsAfter").asLong());

            // UTF-16 (Excel's "Unicode Text", with its byte-order mark): the fix reads it as UTF-16, never as Latin-1
            String tsv = rows(240, i -> "C" + i + "\tMüller " + i + "\tKöln", "customer\tholder\tcity");
            java.io.ByteArrayOutputStream u16 = new java.io.ByteArrayOutputStream();
            u16.write(0xFF);
            u16.write(0xFE);
            u16.writeBytes(tsv.getBytes(StandardCharsets.UTF_16LE));
            HttpResponse<String> wide = post(c, "/onboard/files?name=unicode.tsv", u16.toByteArray());
            assertEquals(422, wide.statusCode(), wide.body());
            JsonNode w = finding(errorFindings(wide), "encoding");
            assertEquals("utf-16", w.at("/fix/redraft/encoding").asText(), w.toString());
            JsonNode fixedWide = draft(c, "unicode.tsv", u16.toByteArray(), redraftQuery(w));
            assertEquals("holder", fixedWide.at("/columns/1/name").asText(), fixedWide.get("columns").toString());
            String wideId = id("unicode");
            data(commit(c, fixedWide, wideId, ""), 200);
            assertEquals(240, parquet(wideId, "SELECT count(*) FROM t WHERE city = 'Köln'"));
            FIXED.add("encoding");
        }
    }

    /** A followed (202) commit refused for its encoding keeps the findings on the run, as the inline refusal does. */

    @Test
    void encodingFindingsSurviveAFollowedRun(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpRequest put = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/settings/link-analysis/limits"))
                    .PUT(BodyPublishers.ofString("{\"import.inline_max_bytes\":0}")).header("Content-Type", "application/json").build();
            assertEquals(200, client.send(put, BodyHandlers.ofString()).statusCode());
            JsonNode d = draft(c, "people.csv", rows(240, i -> "P" + i + ",Holder " + i, "person,holder").getBytes(StandardCharsets.UTF_8), "");
            // the draft's own file swapped for one whose every row is Latin-1: the run rejects them all
            java.nio.file.Files.write(Path.of("database", OnboardRoutes.DRAFTS, d.get("draftId").asText(), "people.csv"),
                    rows(240, i -> "P" + i + ",Müller " + i, "person,holder").getBytes(StandardCharsets.ISO_8859_1));
            JsonNode r = data(commit(c, d, id("people"), ""), 202);
            JsonNode run = null;
            for (int i = 0; i < 600; i++) {
                run = data(get(c, "/onboard/runs/" + r.get("runId").asText()), 200);
                if (!"running".equals(run.get("state").asText())) break;
                Thread.sleep(100);
            }
            assertEquals("failed", run.get("state").asText(), run.toString());
            assertEquals("latin-1", finding(run.get("findings"), "encoding").at("/fix/redraft/encoding").asText(), run.toString());
            FIXED.add("encoding");
        }
    }

    @Test
    void quote(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // a stray '"' (inches) inside every other value; the user chose '"' as the quote
            byte[] file = rows(240, i -> "P" + i + "," + (i % 2 == 0 ? i + "\" screen" : "small") + "," + i, "product,size,stock")
                    .getBytes(StandardCharsets.UTF_8);
            JsonNode d = draft(c, "products.csv", file, "&quote=%22");
            JsonNode f = finding(d.get("findings"), "quote");
            assertEquals("", f.at("/fix/redraft/quote").asText());


            JsonNode fixed = draft(c, "products.csv", file, redraftQuery(f));
            assertFalse(has(fixed.get("findings"), "quote"), fixed.get("findings").toString());
            String id = id("products");
            data(commit(c, fixed, id, ""), 200);
            assertEquals(240, parquet(id, "SELECT count(*) FROM t"));
            assertEquals(120, parquet(id, "SELECT count(*) FROM t WHERE size LIKE '%\" screen'"), "the inches mark stays in the value");
            FIXED.add("quote");
        }
    }

    @Test
    void missingColumn(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = id("sales");
            data(commit(c, draft(c, "sales.csv", rows(240, i -> "S" + i + "," + i + ",EUR", "sale,amount,currency")
                    .getBytes(StandardCharsets.UTF_8), ""), id, ""), 200);
            byte[] later = rows(30, i -> "T" + i + ",EUR", "sale,currency").getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> refused = post(c, "/datasets/" + id + "/files?name=later.csv&mode=append", later);
            assertEquals(422, refused.statusCode(), refused.body());
            JsonNode f = finding(errorFindings(refused), "missing_column");
            assertEquals("amount", f.get("column").asText());
            assertTrue(f.at("/fix/newDataset").asBoolean(), f.toString());

            // the fix: the file as its own Dataset
            String own = id("later");
            JsonNode s = data(commit(c, draft(c, "later.csv", later, ""), own, ""), 200);
            assertEquals(30, s.get("rowsAfter").asLong());
            FIXED.add("missing_column");
        }
    }

    @Test
    void theDraftOfAFileWithNoProblemHasNoFinding(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = draft(c, "transfers.csv", ControlApiOnboardFilesTest.transfers(240).getBytes(StandardCharsets.UTF_8), "");
            assertEquals(0, d.get("findings").size(), d.get("findings").toString());
            assertEquals(0, draft(c, "e.json", "[{\"a\":1,\"b\":{\"c\":\"x\"}}]".getBytes(StandardCharsets.UTF_8), "").get("findings").size());
            assertEquals(422, post(c, "/onboard/files?name=e.json&encoding=latin-1", "[]".getBytes(StandardCharsets.UTF_8)).statusCode());
            assertEquals(422, post(c, "/onboard/files?name=x.csv&encoding=cp1252", "a\n1\n".getBytes(StandardCharsets.UTF_8)).statusCode());
        }
    }
}
