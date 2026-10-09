package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceManager;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for the per-DAY Reconciliation (RECON-PERF-1, operator 2026-10-09): {@code day} scoping on
 * {@code /recon/run}, {@code /recon/breaks} and {@code /recon/{id}/record}, the latest-day default and
 * {@code availableDays}, server-side paging + the Break filter with page-independent totals, the deterministic
 * {@code sample}, the per-day result cache and its invalidation, and the saved tolerance {@code bands} 422.
 *
 * <p>Fixture — the ra_c01 shape (HLR vs CRM vs CBS on msisdn, compare {@code active}), two days:
 * <pre>
 *   2026-09-25  HLR m1..m6           CRM m1..m6           CBS m1..m6       — every key everywhere, all equal
 *   2026-09-26  HLR m1 m2 m3 m4 m5   CRM m1 m2 m4 m5 m6   CBS m1 m2 m3 m4  — m2 active 1 vs 0 on CRM (value break),
 *                                                                          m3 missing on CRM, m6 missing on HLR,
 *                                                                          m5 + m6 missing on CBS
 *   2026-09-26 also carries m99 on HLR only with a NULL day — never on any day
 * </pre>
 */
class ControlApiReconDayTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path data) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    private static final String D1 = "2026-09-25", D2 = "2026-09-26";

    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Path data = base.resolve("data");

        String day1 = "('m1',1,DATE '" + D1 + "'),('m2',1,DATE '" + D1 + "'),('m3',1,DATE '" + D1 + "'),"
                + "('m4',1,DATE '" + D1 + "'),('m5',1,DATE '" + D1 + "'),('m6',1,DATE '" + D1 + "')";
        seed(data, "hlr", day1 + ",('m1',1,DATE '" + D2 + "'),('m2',1,DATE '" + D2 + "'),('m3',1,DATE '" + D2 + "'),"
                + "('m4',1,DATE '" + D2 + "'),('m5',1,DATE '" + D2 + "'),('m99',1,NULL)");
        seed(data, "crm", day1 + ",('m1',1,DATE '" + D2 + "'),('m2',0,DATE '" + D2 + "'),('m4',1,DATE '" + D2 + "'),"
                + "('m5',1,DATE '" + D2 + "'),('m6',1,DATE '" + D2 + "')");
        seed(data, "cbs", day1 + ",('m1',1,DATE '" + D2 + "'),('m2',1,DATE '" + D2 + "'),('m3',1,DATE '" + D2 + "'),"
                + "('m4',1,DATE '" + D2 + "')");
        seed(data, "undated", "('m1',1,DATE '" + D1 + "')");

        ComponentStore store = new ComponentStore(config.resolve("registry"));
        // HLR declares its day via columns[].role temporal, CRM/CBS via dateField — both resolutions are exercised.
        store.write("dataset", "hlr_ds", Map.of("physicalRef", "hlr",
                "columns", List.of(Map.of("name", "event_date", "type", "date", "role", "temporal"))));
        store.write("dataset", "crm_ds", Map.of("physicalRef", "crm", "dateField", "event_date"));
        store.write("dataset", "cbs_ds", Map.of("physicalRef", "cbs", "dateField", "event_date"));
        store.write("dataset", "undated_ds", Map.of("physicalRef", "undated"));
        store.write("dataset", "undated2_ds", Map.of("physicalRef", "crm"));
        store.write("reconciliation", "whole_period", Map.of(
                "datasets", List.of("undated_ds", "undated2_ds"), "keyColumns", List.of("msisdn")));
        store.write("reconciliation", "ra_c01", Map.of(
                "datasets", List.of("hlr_ds", "crm_ds", "cbs_ds"),
                "keyColumns", List.of("msisdn"),
                "compareColumns", List.of(Map.of("column", "active", "toleranceType", "exact"))));
        store.write("reconciliation", "two_way", Map.of(
                "datasets", List.of("hlr_ds", "crm_ds"), "keyColumns", List.of("msisdn"),
                "compareColumns", List.of(Map.of("column", "active"))));
        store.write("reconciliation", "undated_recon", Map.of(
                "datasets", List.of("hlr_ds", "undated_ds"), "keyColumns", List.of("msisdn")));

        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        com.gamma.recon.ReconDayTestAccess.clearCache();
        return new Ctx(spaces, api, api.port(), data);
    }

    static void seed(Path data, String name, String values) throws Exception {
        Path partition = data.resolve(name).resolve("dt=2026");
        Files.createDirectories(partition);
        String parquet = partition.resolve("data.parquet").toString().replace(File.separatorChar, '/');
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("recon_day_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") t(msisdn, active, event_date)) TO '" + parquet
                    + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private JsonNode run(Ctx c, String body) throws Exception {
        HttpResponse<String> r = post(c.port, "/spaces/s1/recon/run", body);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static List<String> keys(JsonNode run) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : run.get("rows")) out.add(row.get("key").get("msisdn").asText());
        return out;
    }

    // ── day scoping ────────────────────────────────────────────────────────────────

    @Test
    void aDayReadsOnlyThatDaysRows(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode d1 = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D1 + "\",\"limit\":200}");
            assertEquals(D1, d1.get("day").asText());
            assertEquals(List.of("m1", "m2", "m3", "m4", "m5", "m6"), keys(d1));
            // negative probe: D2's breaks and the undated m99 are NOT in D1
            assertEquals(0, d1.get("summary").get("byType").get("value_break").asInt(), d1.get("summary").toString());
            assertEquals(6, d1.get("totals").get("a").get("__records").asInt(), "HLR has 6 rows on D1, 12 in all");
            assertFalse(keys(d1).contains("m99"));

            JsonNode d2 = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D2 + "\",\"limit\":200}");
            assertEquals(List.of("m1", "m2", "m3", "m4", "m5", "m6"), keys(d2));
            assertEquals(5, d2.get("totals").get("a").get("__records").asInt(), "m99 has no day, so it is on no day");
            assertEquals(1, d2.get("summary").get("byType").get("value_break").asInt(), "m2 1 vs 0 on CRM");
            assertEquals(1, d2.get("summary").get("byType").get("missing_right").asInt(), "m3 not on CRM");
            assertEquals(1, d2.get("summary").get("byType").get("missing_left").asInt(), "m6 not on HLR");
            assertTrue(d2.get("statistics").has("cached"));
        }
    }

    @Test
    void noDayMeansTheLatestDayAndAvailableDaysAreNewestFirst(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode r = run(c, "{\"id\":\"ra_c01\"}");
            assertEquals(D2, r.get("day").asText());
            assertEquals(List.of(D2, D1), strings(r.get("availableDays")));
            assertEquals(50, r.get("page").get("limit").asInt(), "the default page size");
        }
    }

    @Test
    void aDatasetWithNoTemporalColumnIs422NamingTheFix(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = post(c.port, "/spaces/s1/recon/run", "{\"id\":\"undated_recon\"}");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("undated_ds") && r.body().contains("role: temporal"), r.body());
        }
    }

    @Test
    void noSideWithADayColumnComparesTheWholeRelationsAndSaysSo(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode r = run(c, "{\"id\":\"whole_period\"}");
            assertFalse(r.get("dayScoped").asBoolean());
            assertTrue(r.get("day").isNull());
            assertEquals(0, r.get("availableDays").size());
            assertEquals(11, r.get("totals").get("b").get("__records").asInt(), "every day of CRM (D1 6 + D2 5): unscoped");
            HttpResponse<String> withDay = post(c.port, "/spaces/s1/recon/run", "{\"id\":\"whole_period\",\"day\":\"" + D2 + "\"}");
            assertEquals(422, withDay.statusCode(), "a day cannot be read where no side has one: " + withDay.body());
            // and the dated reconciliations say they are day-scoped
            assertTrue(run(c, "{\"id\":\"two_way\"}").get("dayScoped").asBoolean());
        }
    }

    @Test
    void aBadDayOrPageIs422(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            for (String body : List.of("{\"id\":\"ra_c01\",\"day\":\"26/09/2026\"}",
                    "{\"id\":\"ra_c01\",\"day\":\"2026-09-26' OR 1=1 --\"}",
                    "{\"id\":\"ra_c01\",\"limit\":0}", "{\"id\":\"ra_c01\",\"limit\":201}",
                    "{\"id\":\"ra_c01\",\"offset\":-1}", "{\"id\":\"ra_c01\",\"filter\":\"nope\"}",
                    "{\"id\":\"two_way\",\"filter\":\"missing_c\"}", "{\"id\":\"ra_c01\",\"sample\":-5}")) {
                HttpResponse<String> r = post(c.port, "/spaces/s1/recon/run", body);
                assertEquals(422, r.statusCode(), body + " -> " + r.body());
            }
        }
    }

    // ── paging + filter ────────────────────────────────────────────────────────────

    @Test
    void pagesAndFiltersSliceTheDayWhileTotalsStayTheWholeDay(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode p1 = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D2 + "\",\"limit\":2,\"offset\":0}");
            JsonNode p2 = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D2 + "\",\"limit\":2,\"offset\":2}");
            JsonNode p3 = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D2 + "\",\"limit\":2,\"offset\":4}");
            assertEquals(List.of("m1", "m2"), keys(p1));
            assertEquals(List.of("m3", "m4"), keys(p2));
            assertEquals(List.of("m5", "m6"), keys(p3));
            assertEquals(6, p1.get("page").get("total").asInt());
            assertEquals(p1.get("totals"), p3.get("totals"), "totals are the whole day's, whatever the page");
            assertEquals(p1.get("summary"), p3.get("summary"));
            assertTrue(p1.get("statistics").get("truncated").asBoolean(), "more pages follow");
            assertFalse(p3.get("statistics").get("truncated").asBoolean());

            Map<String, List<String>> expected = Map.of(
                    "all", List.of("m1", "m2", "m3", "m4", "m5", "m6"),
                    "breaks", List.of("m2", "m3", "m5", "m6"),
                    "missing_a", List.of("m6"),
                    "missing_b", List.of("m3"),
                    "missing_c", List.of("m5", "m6"),
                    "value_break", List.of("m2"));
            for (Map.Entry<String, List<String>> e : expected.entrySet()) {
                JsonNode r = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D2 + "\",\"filter\":\"" + e.getKey() + "\"}");
                assertEquals(e.getValue(), keys(r), e.getKey());
                assertEquals(e.getValue().size(), r.get("page").get("total").asInt(), e.getKey());
                assertEquals(p1.get("summary"), r.get("summary"), "the summary ignores the filter: " + e.getKey());
            }
            // a filtered page past the end is empty, with the true total
            JsonNode past = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D2 + "\",\"filter\":\"breaks\",\"offset\":10}");
            assertEquals(0, past.get("rows").size());
            assertEquals(4, past.get("page").get("total").asInt());
        }
    }

    // ── sample ─────────────────────────────────────────────────────────────────────

    @Test
    void aSampleIsDeterministicLabelledAndBounded(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode s1 = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D2 + "\",\"sample\":3}");
            com.gamma.recon.ReconDayTestAccess.clearCache();   // the second answer must be RECOMPUTED, not read back
            JsonNode s2 = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D2 + "\",\"sample\":3}");
            assertFalse(s2.get("statistics").get("cached").asBoolean());
            assertEquals(keys(s1), keys(s2), "the same keys every time");
            assertEquals(3, keys(s1).size());
            assertTrue(s1.get("sample").get("sampled").asBoolean());
            assertEquals(3, s1.get("sample").get("keys").asInt());
            assertEquals(6, s1.get("sample").get("totalKeys").asInt());
            assertEquals(3, s1.get("summary").get("groups").asInt(), "the summary covers the sampled keys only");
            JsonNode full = run(c, "{\"id\":\"ra_c01\",\"day\":\"" + D2 + "\"}");
            assertTrue(full.get("sample").isNull(), "an unsampled run says so");
        }
    }

    // ── cache ──────────────────────────────────────────────────────────────────────

    @Test
    void aSecondPageReadsTheCacheAndChangedDataInvalidatesIt(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            JsonNode first = run(c, "{\"id\":\"two_way\",\"day\":\"" + D2 + "\",\"limit\":2}");
            assertFalse(first.get("statistics").get("cached").asBoolean());
            JsonNode second = run(c, "{\"id\":\"two_way\",\"day\":\"" + D2 + "\",\"limit\":2,\"offset\":2}");
            assertTrue(second.get("statistics").get("cached").asBoolean(), "the same day is computed once");

            // CRM's file changes: m3 now present on D2 — the cached day must not be served
            Thread.sleep(1100);   // a coarse filesystem mtime must still move
            seed(c.data(), "crm", "('m1',1,DATE '" + D2 + "'),('m2',0,DATE '" + D2 + "'),('m3',1,DATE '" + D2 + "'),"
                    + "('m4',1,DATE '" + D2 + "'),('m5',1,DATE '" + D2 + "'),('m6',1,DATE '" + D2 + "'),"
                    + "('m7',1,DATE '2026-09-27')");
            JsonNode after = run(c, "{\"id\":\"two_way\",\"day\":\"" + D2 + "\",\"filter\":\"missing_b\"}");
            assertFalse(after.get("statistics").get("cached").asBoolean(), "changed data is a different key");
            assertEquals(List.of(), keys(after), "m3 is on CRM now");
            assertEquals(List.of("2026-09-27", D2, D1), strings(after.get("availableDays")),
                    "the days list is recomputed too: CRM's new day appears");
        }
    }

    // ── breaks + record ────────────────────────────────────────────────────────────

    @Test
    void breaksAndRecordAreDayScoped(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> b1 = post(c.port, "/spaces/s1/recon/breaks", "{\"id\":\"two_way\",\"day\":\"" + D1 + "\"}");
            assertEquals(200, b1.statusCode(), b1.body());
            assertEquals(0, V1Body.of(b1.body()).get("missing_right").get("rowCount").asInt(), "D1 has no breaks");

            HttpResponse<String> r1 = post(c.port, "/spaces/s1/recon/two_way/record", "{\"day\":\"" + D1 + "\"}");
            assertEquals(200, r1.statusCode(), r1.body());
            JsonNode s1 = V1Body.of(r1.body());
            assertEquals(D1, s1.get("day").asText());
            assertEquals(0, s1.get("breaks").size(), "recording D1 records none of D2's breaks");

            HttpResponse<String> r2 = post(c.port, "/spaces/s1/recon/two_way/record", "");
            assertEquals(200, r2.statusCode(), r2.body());
            JsonNode s2 = V1Body.of(r2.body());
            assertEquals(D2, s2.get("day").asText(), "no day = the latest");
            Set<String> types = new TreeSet<>();
            for (JsonNode b : s2.get("breaks")) types.add(b.get("key").asText() + ":" + b.get("type").asText());
            assertEquals(Set.of("m2:value_break", "m3:missing_right", "m6:missing_left"), types);

            HttpResponse<String> bad = post(c.port, "/spaces/s1/recon/two_way/record", "{\"day\":\"yesterday\"}");
            assertEquals(422, bad.statusCode(), bad.body());
        }
    }

    // ── per-day Break lifecycle (operator, 2026-10-09) ──────────────────────────────

    @Test
    void recordingOneDayNeverTouchesAnotherDaysBreaks(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // an UNDATED Break, as a state file written before the per-day lifecycle holds it
            new com.gamma.recon.ReconStateStore(root.resolve("s1").resolve("config")).record("two_way",
                    List.of(com.gamma.recon.ReconBreaks.Break.identityOnly("AB", "missing_left", "legacy", null, "open", null, null)),
                    "2026-09-01T00:00:00Z");

            HttpResponse<String> d2 = post(c.port, "/spaces/s1/recon/two_way/record", "{\"day\":\"" + D2 + "\"}");
            assertEquals(200, d2.statusCode(), d2.body());
            // D1 compares clean: before the per-day lifecycle this run AUTO-CLOSED every D2 Break (the negative probe)
            HttpResponse<String> d1 = post(c.port, "/spaces/s1/recon/two_way/record", "{\"day\":\"" + D1 + "\"}");
            assertEquals(200, d1.statusCode(), d1.body());
            Map<String, String> byKey = new java.util.TreeMap<>();
            for (JsonNode b : V1Body.of(d1.body()).get("breaks"))
                byKey.put(b.path("day").asText("undated") + ":" + b.get("key").asText(), b.get("status").asText());
            assertEquals(Map.of(D2 + ":m2", "open", D2 + ":m3", "open", D2 + ":m6", "open", "undated:legacy", "open"), byKey,
                    "D2's Breaks and the undated one stay open after recording D1");

            // the same key on two days is two records: resolving D2's m3 leaves D1's m3 alone
            assertEquals(200, post(c.port, "/spaces/s1/recon/two_way/breaks/status",
                    "{\"day\":\"" + D1 + "\",\"type\":\"missing_right\",\"key\":\"m3\",\"status\":\"open\",\"note\":\"d1\"}").statusCode());
            HttpResponse<String> st = post(c.port, "/spaces/s1/recon/two_way/breaks/status",
                    "{\"day\":\"" + D2 + "\",\"type\":\"missing_right\",\"key\":\"m3\",\"status\":\"resolved\"}");
            assertEquals(200, st.statusCode(), st.body());
            assertEquals(D2, V1Body.of(st.body()).get("break").get("day").asText());
            JsonNode state = V1Body.of(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                    + "/api/v1/spaces/s1/recon/two_way/state")).GET().build(), BodyHandlers.ofString()).body());
            Map<String, String> m3 = new java.util.TreeMap<>();
            for (JsonNode b : state.get("breaks"))
                if ("m3".equals(b.get("key").asText())) m3.put(b.path("day").asText(), b.get("status").asText());
            assertEquals(Map.of(D1, "open", D2, "resolved"), m3);
            HttpResponse<String> bad = post(c.port, "/spaces/s1/recon/two_way/breaks/status",
                    "{\"day\":\"tomorrow\",\"type\":\"missing_right\",\"key\":\"m3\",\"status\":\"resolved\"}");
            assertEquals(422, bad.statusCode(), bad.body());
        }
    }

    // ── bands ──────────────────────────────────────────────────────────────────────

    @Test
    void savedBandsAreValidatedAtAuthoring(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String base = "\"datasets\":[\"hlr_ds\",\"crm_ds\"],\"keyColumns\":[\"msisdn\"]";
            for (String bands : List.of("{\"okBelow\":3,\"warnAbove\":2}", "{\"okBelow\":-1,\"warnAbove\":2}",
                    "{\"okBelow\":1,\"warnAbove\":101}", "{\"okBelow\":\"1\",\"warnAbove\":2}", "[1,2]")) {
                HttpResponse<String> r = post(c.port, "/spaces/s1/components/reconciliation",
                        "{\"id\":\"b_bad\"," + base + ",\"bands\":" + bands + "}");
                assertEquals(422, r.statusCode(), bands + " -> " + r.body());
            }
            HttpResponse<String> ok = post(c.port, "/spaces/s1/components/reconciliation",
                    "{\"id\":\"b_ok\"," + base + ",\"bands\":{\"okBelow\":1,\"warnAbove\":2}}");
            assertEquals(200, ok.statusCode(), ok.body());
            HttpResponse<String> equal = post(c.port, "/spaces/s1/components/reconciliation",
                    "{\"id\":\"b_eq\"," + base + ",\"bands\":{\"okBelow\":0,\"warnAbove\":0}}");
            assertEquals(200, equal.statusCode(), "0 <= ok <= warn <= 100 is inclusive: " + equal.body());
        }
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.asText());
        return out;
    }
}
