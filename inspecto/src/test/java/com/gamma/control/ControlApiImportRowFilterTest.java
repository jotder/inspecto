package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ADD-DATA-ROW-FILTER-1} over real HTTP: a commit's {@code filter: [{column, op, value | min | max}]} becomes the
 * import Pipeline's post-parse row predicate, and that Pipeline keeps exactly the rows the conditions describe - on the
 * commit and on every later file (Add a file, Run now, a Parquet replace as a Postgres snapshot refresh hands it over).
 * A refused condition is 422 naming it and writes nothing; no value is ever spliced into SQL. Synthetic fixtures only.
 */
class ControlApiImportRowFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> commit(Ctx c, JsonNode draft, Map<String, Object> body) throws Exception {
        return send(c, "POST", "/onboard/files/" + draft.get("draftId").asText() + "/commit",
                JSON.writeValueAsBytes(body));
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    private String uniqueId(String base) {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        return id;
    }

    private JsonNode draft(Ctx c, String name, byte[] content) throws Exception {
        return data(send(c, "POST", "/onboard/files?name=" + name + "&kind=stream", content), 200);
    }

    // ── synthetic rows ──────────────────────────────────────────────────────────────────────────────────────

    /** One generated row, as the file holds it. */
    private record Row(String id, String channel, BigDecimal amount, long qty, LocalDate bookedOn, String note) {}

    private static final String[] CHANNELS = {"mobile", "branch", "online", "Phone"};
    private static final String[] NOTES = {"Rent", "refund", "Gift card", "salary", "REFUND fee", "misc", "it's mine"};
    private static final LocalDate DAY0 = LocalDate.of(2026, 1, 1);

    private static List<Row> rows(long seed, int n, int firstId) {
        Random r = new Random(seed);
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
            out.add(new Row(String.format("R%05d", firstId + i), CHANNELS[r.nextInt(CHANNELS.length)],
                    BigDecimal.valueOf(100 + r.nextInt(99_900), 2), r.nextInt(50), DAY0.plusDays(r.nextInt(120)),
                    NOTES[r.nextInt(NOTES.length)]));
        return out;
    }

    private static String csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("row_id,from_account,to_account,channel,amount,qty,booked_on,note\n");
        int i = 0;
        for (Row x : rows) {
            i++;
            sb.append(x.id()).append(",ACC-").append(i % 17).append(",ACC-").append(i % 13 + 20).append(',').append(x.channel())
                    .append(',').append(x.amount().toPlainString()).append(',').append(x.qty()).append(',').append(x.bookedOn())
                    .append(',').append(x.note()).append('\n');
        }
        return sb.toString();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** The row ids now in the import's Parquet. */
    private static Set<String> keptIds(String id) throws Exception {
        com.gamma.util.DuckDbUtil.loadDriver();
        Set<String> out = new TreeSet<>();
        Path db = Path.of("database", id, "database").toAbsolutePath();
        try (java.sql.Connection conn = com.gamma.util.DuckDbUtil.openInMemory(null, List.of(Path.of("database", id).toAbsolutePath()), null);
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT row_id FROM read_parquet('"
                     + db.toString().replace('\\', '/') + "/**/*.parquet')")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Set<String> expected(List<Row> rows, Predicate<Row> keep) {
        Set<String> out = new TreeSet<>();
        for (Row r : rows) if (keep.test(r)) out.add(r.id());
        return out;
    }

    private static Map<String, Object> cond(String column, String op, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("column", column);
        m.put("op", op);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> range(String column, Object min, Object max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("column", column);
        m.put("op", "range");
        if (min != null) m.put("min", min);
        if (max != null) m.put("max", max);
        return m;
    }

    private static Map<String, Object> body(String id, List<Map<String, Object>> filter) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", id);
        b.put("filter", filter);
        return b;
    }

    /** A random condition over the generated columns, with the Java predicate it means. */
    private record Condition(Map<String, Object> wire, Predicate<Row> keep) {}

    private static Condition randomCondition(Random r) {
        switch (r.nextInt(7)) {
            case 0 -> {
                String ch = CHANNELS[r.nextInt(CHANNELS.length)];
                return new Condition(cond("channel", "eq", ch), x -> x.channel().equals(ch));
            }
            case 1 -> {
                String ch = CHANNELS[r.nextInt(CHANNELS.length)];
                return new Condition(cond("channel", "ne", ch), x -> !x.channel().equals(ch));
            }
            case 2 -> {
                String word = new String[]{"refund", "RENT", "REFUND", "Rent"}[r.nextInt(4)];
                if (r.nextBoolean()) {   // operator 2026-11-05: Match case makes it exact
                    Map<String, Object> exact = cond("note", "contains", word);
                    exact.put("matchCase", true);
                    return new Condition(exact, x -> x.note().contains(word));
                }
                return new Condition(cond("note", "contains", word),
                        x -> x.note().toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT)));
            }
            case 3 -> {
                BigDecimal lo = r.nextInt(3) == 0 ? null : BigDecimal.valueOf(100 + r.nextInt(50_000), 2);
                BigDecimal hi = r.nextInt(3) == 0 && lo != null ? null : BigDecimal.valueOf(50_000 + r.nextInt(50_000), 2);
                return new Condition(range("amount", lo == null ? null : lo.toPlainString(), hi),
                        x -> (lo == null || x.amount().compareTo(lo) >= 0) && (hi == null || x.amount().compareTo(hi) <= 0));
            }
            case 4 -> {
                long lo = r.nextInt(25), hi = lo + r.nextInt(25);
                return new Condition(range("qty", lo, hi), x -> x.qty() >= lo && x.qty() <= hi);
            }
            case 5 -> {
                LocalDate lo = DAY0.plusDays(r.nextInt(60));
                LocalDate hi = r.nextBoolean() ? null : lo.plusDays(r.nextInt(60));
                return new Condition(range("booked_on", lo.toString(), hi == null ? null : hi.toString()),
                        x -> !x.bookedOn().isBefore(lo) && (hi == null || !x.bookedOn().isAfter(hi)));
            }
            default -> {
                long q = r.nextInt(50);
                return new Condition(cond("qty", "eq", q), x -> x.qty() == q);
            }
        }
    }

    // ── the written filter keeps exactly the rows its conditions describe ───────────────────────────────────

    @Test
    void theWrittenFilterKeepsExactlyTheRowsTheConditionsDescribe(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Random seeds = new Random(20261105L);
            int committed = 0;
            for (int trial = 0; trial < 10; trial++) {
                long seed = seeds.nextLong();
                List<Row> rows = rows(seed, 150, 1);
                Random r = new Random(seed ^ 0x5DEECE66DL);
                List<Condition> conds = new ArrayList<>();
                for (int k = 1 + r.nextInt(3); k > 0; k--) conds.add(randomCondition(r));
                Predicate<Row> all = x -> conds.stream().allMatch(cd -> cd.keep().test(x));
                Set<String> want = expected(rows, all);
                String why = "seed " + seed + " conditions " + conds.stream().map(Condition::wire).toList();

                JsonNode d = draft(c, "rows.csv", bytes(csv(rows)));
                Map<String, String> types = new LinkedHashMap<>();
                for (JsonNode col : d.get("columns")) types.put(col.get("name").asText(), col.at("/type/value").asText());
                assertEquals("DOUBLE", types.get("amount"), types.toString());
                assertEquals("BIGINT", types.get("qty"), types.toString());
                assertEquals("DATE", types.get("booked_on"), types.toString());

                String id = uniqueId("filtered");
                HttpResponse<String> made = commit(c, d, body(id, conds.stream().map(Condition::wire).toList()));
                if (want.isEmpty()) {
                    assertEquals(422, made.statusCode(), why + " " + made.body());
                    assertTrue(error(made).contains("row filter kept none"), why + " " + made.body());
                    continue;
                }
                JsonNode s = data(made, 200);
                committed++;
                assertEquals(want, keptIds(id), why);
                assertEquals(150, s.get("rowsIn").asLong(), why + " " + s);
                assertEquals(150 - want.size(), s.get("rowsFilteredOut").asLong(), why + " " + s);
                assertEquals(want.size(), s.get("rowsAfter").asLong(), why + " " + s);
                assertEquals(conds.size(), s.get("filter").size(), "the summary names the conditions: " + s);
            }
            assertTrue(committed >= 5, "most random filters keep some rows: " + committed);
        }
    }

    // ── refusals: 422 naming the condition, nothing written, nothing spliced ────────────────────────────────

    @Test
    void anUnknownColumnABadOpOrAnInjectionShapedValueIsRefusedNamingTheCondition(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<Row> rows = rows(7L, 80, 1);
            JsonNode d = draft(c, "rows.csv", bytes(csv(rows)));
            String id = uniqueId("refused");
            Map<String, List<Map<String, Object>>> refusals = new LinkedHashMap<>();
            refusals.put("the draft has no column 'nope'", List.of(cond("nope", "eq", "x")));
            refusals.put("op must be one of eq, ne, contains, range", List.of(cond("channel", "like", "%mob%")));
            refusals.put("is not a whole number", List.of(cond("channel", "eq", "mobile"), cond("qty", "eq", "1 OR 1=1")));
            refusals.put("is not a date", List.of(range("booked_on", "2026-01-01' OR '1'='1", null)));
            refusals.put("is not a number", List.of(range("amount", "1); DROP TABLE t; --", null)));
            refusals.put("no control characters", List.of(cond("note", "eq", "a\u0000b")));
            refusals.put("contains applies to text columns", List.of(cond("qty", "contains", "1")));
            refusals.put("a range applies to number, date and time columns", List.of(range("note", "a", "z")));
            refusals.put("a range needs a min, a max or both", List.of(range("amount", null, null)));
            refusals.put("is after max", List.of(range("qty", 9, 3)));
            Map<String, Object> caseOnEq = cond("channel", "eq", "mobile");
            caseOnEq.put("matchCase", true);
            refusals.put("matchCase belongs to contains", List.of(caseOnEq));
            Map<String, Object> caseNotBool = cond("note", "contains", "refund");
            caseNotBool.put("matchCase", "yes");
            refusals.put("matchCase is true or false", List.of(caseNotBool));
            refusals.put("unknown key 'where'", List.of(Map.of("column", "qty", "op", "eq", "value", 1, "where", "1=1")));
            for (var e : refusals.entrySet()) {
                HttpResponse<String> r = commit(c, d, body(id, e.getValue()));
                assertEquals(422, r.statusCode(), e.getKey() + " " + r.body());
                String msg = error(r);
                assertTrue(msg.contains(e.getKey()), "expected '" + e.getKey() + "' in: " + msg);
                assertTrue(msg.contains("filter[" + (e.getValue().size() - 1) + "]"), "the refusal names the condition: " + msg);
            }
            // a left-out column cannot keep rows
            Map<String, Object> dropped = body(id, List.of(cond("note", "eq", "Rent")));
            dropped.put("columns", List.of(Map.of("name", "note", "drop", true)));
            HttpResponse<String> left = commit(c, d, dropped);
            assertEquals(422, left.statusCode(), left.body());
            assertTrue(error(left).contains("'note' is left out"), left.body());

            // a text value shaped like SQL is a literal: spliced raw it would keep every row; quoted it equals none
            HttpResponse<String> injected = commit(c, d, body(id, List.of(cond("channel", "eq", "x' OR '1'='1"))));
            assertEquals(422, injected.statusCode(), injected.body());
            assertTrue(error(injected).contains("row filter kept none of its 80 rows"), injected.body());

            // nothing of any refused commit was kept, and the draft still commits
            assertFalse(Files.exists(root.resolve("imports").resolve(id + "_pipeline.toon")), "no Pipeline left behind");
            assertTrue(new ComponentStore(root.resolve("registry")).get("dataset", id).isEmpty(), "no Dataset left behind");
            JsonNode ok = data(commit(c, d, body(id, List.of(cond("note", "eq", "it's mine")))), 200);
            assertEquals(expected(rows, x -> x.note().equals("it's mine")), keptIds(id), ok.toString());
            String where = c.svc.configFor(id).orElseThrow().csv().where();
            assertEquals("(\"note\" = 'it''s mine')", where, "the quote is doubled inside the literal");
        }
    }

    /** Operator 2026-11-05: contains ignores case unless the condition says Match case; the entry keeps the switch. */
    @Test
    void containsIgnoresCaseUnlessTheConditionMatchesCase(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<Row> rows = rows(31L, 200, 1);
            JsonNode d = draft(c, "rows.csv", bytes(csv(rows)));
            String loose = uniqueId("loose");
            data(commit(c, d, body(loose, List.of(cond("note", "contains", "REFUND")))), 200);
            assertEquals(expected(rows, x -> x.note().toLowerCase(Locale.ROOT).contains("refund")), keptIds(loose));

            JsonNode d2 = draft(c, "rows.csv", bytes(csv(rows)));
            String exact = uniqueId("exact");
            Map<String, Object> cs = cond("note", "contains", "REFUND");
            cs.put("matchCase", true);
            data(commit(c, d2, body(exact, List.of(cs))), 200);
            Set<String> want = expected(rows, x -> x.note().contains("REFUND"));
            assertFalse(want.isEmpty(), "the fixture holds an upper-case REFUND");
            assertNotEquals(expected(rows, x -> x.note().toLowerCase(Locale.ROOT).contains("refund")), want, "the fixture tells the two apart");
            assertEquals(want, keptIds(exact));
            assertEquals("[{column=note, op=contains, value=REFUND, matchCase=true}]",
                    String.valueOf(new ComponentStore(root.resolve("registry")).get("dataset", exact).orElseThrow().content()
                            .get(OnboardImport.IMPORT_FILTER)));
        }
    }

    // ── every later file through the same Pipeline ──────────────────────────────────────────────────────────

    @Test
    void laterFilesAddedOrDroppedInTheInboxAreFilteredTooAndTheArrivalsSayHowManyRowsWereLeftOut(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            Predicate<Row> keep = x -> x.channel().equals("mobile") && x.amount().compareTo(new BigDecimal("100")) >= 0;
            List<Map<String, Object>> filter = List.of(cond("channel", "eq", "mobile"), range("amount", 100, null));
            List<Row> first = rows(11L, 200, 1);
            JsonNode d = draft(c, "first.csv", bytes(csv(first)));
            String id = uniqueId("later");
            Map<String, Object> b = body(id, filter);
            b.put("columns", List.of(Map.of("name", "channel", "rename", "via")));   // conditions name the draft column
            JsonNode made = data(commit(c, d, b), 200);
            assertEquals(expected(first, keep), keptIds(id), made.toString());

            Map<String, Object> entry = new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content();
            assertEquals("[{column=via, op=eq, value=mobile}, {column=amount, op=range, min=100}]",
                    String.valueOf(entry.get(OnboardImport.IMPORT_FILTER)), "the entry keeps the conditions by the Dataset's names");
            assertEquals("(\"via\" = 'mobile') AND (\"amount\" >= 100)", c.svc.configFor(id).orElseThrow().csv().where(),
                    "the Pipeline's predicate names the Dataset's columns");

            // Add a file, replacing everything
            List<Row> second = rows(12L, 120, 1000);
            JsonNode rep = data(send(c, "POST", "/datasets/" + id + "/files?name=second.csv", bytes(csv(second))), 200);
            assertEquals(expected(second, keep), keptIds(id), rep.toString());
            assertEquals(120 - expected(second, keep).size(), rep.get("rowsFilteredOut").asLong(), rep.toString());

            // Add a file, adding its rows
            List<Row> third = rows(13L, 90, 2000);
            JsonNode app = data(send(c, "POST", "/datasets/" + id + "/files?name=third.csv&mode=append", bytes(csv(third))), 200);
            Set<String> both = new TreeSet<>(expected(second, keep));
            both.addAll(expected(third, keep));
            assertEquals(both, keptIds(id), app.toString());

            // a file a system drops in the inbox, taken by Run now
            List<Row> dropped = rows(14L, 70, 3000);
            Files.writeString(Path.of("database", "inbox", id).resolve("system_drop.csv"), csv(dropped));
            JsonNode now = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            both.addAll(expected(dropped, keep));
            assertEquals(both, keptIds(id), now.toString());
            assertEquals(70 - expected(dropped, keep).size(), now.get("rowsFilteredOut").asLong(), now.toString());

            // every arrival records how many rows the filter left out of it
            JsonNode arrivals = data(send(c, "GET", "/datasets/" + id + "/arrivals", null), 200).get("arrivals");
            Map<String, Long> leftOut = new LinkedHashMap<>();
            for (JsonNode a : arrivals) leftOut.put(a.get("file").asText(), a.get("rowsFilteredOut").asLong(-1));
            assertEquals(200L - expected(first, keep).size(), leftOut.get("first.csv"), arrivals.toString());
            assertEquals(120L - expected(second, keep).size(), leftOut.get("second.csv"), arrivals.toString());
            assertEquals(90L - expected(third, keep).size(), leftOut.get("third.csv"), arrivals.toString());
            assertEquals(70L - expected(dropped, keep).size(), leftOut.get("system_drop.csv"), arrivals.toString());
        }
    }

    /**
     * A Postgres snapshot refresh copies the table into a staged Parquet file and hands it to {@link OnboardImport#addStaged}
     * as a replace ({@link PostgresSnapshot#refresh}); this drives that hand-over directly, with no exchange
     * ({@code ex == null}), so it runs without a database. The real-Postgres path is
     * {@code ControlApiPostgresSnapshotTest#aSnapshotKeepsOnlyTheFilteredRowsOnEveryRefresh}.
     */
    @Test
    void aParquetReplaceAsASnapshotRefreshHandsItOverKeepsOnlyTheFilteredRows(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            Predicate<Row> keep = x -> !x.channel().equals("branch") && !x.bookedOn().isAfter(DAY0.plusDays(59));
            List<Map<String, Object>> filter = List.of(cond("channel", "ne", "branch"), range("booked_on", null, DAY0.plusDays(59).toString()));
            List<Row> first = rows(21L, 160, 1);
            JsonNode d = draft(c, "table.parquet", Files.readAllBytes(parquet(tmp.resolve("first.parquet"), first)));
            assertEquals("parquet", d.at("/format/type/value").asText());
            String id = uniqueId("snapshotlike");
            JsonNode made = data(commit(c, d, body(id, filter)), 200);
            assertEquals(expected(first, keep), keptIds(id), made.toString());

            List<Row> next = rows(22L, 140, 5000);
            Path dataRoot = Path.of("database").toAbsolutePath().normalize();
            Path stage = dataRoot.resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
            Files.createDirectories(stage);
            Path file = parquet(stage.resolve(OnboardRoutes.storedName("table.parquet")), next);
            Map<String, Object> content = new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content();
            String key = root + "|" + id;
            assertTrue(OnboardImport.IN_FLIGHT.add(key));
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) OnboardImport.addStaged(new OnboardImport.Space(root, dataRoot, c.svc, "system:poll"),
                    content, id, key, stage, file, "table.parquet", "replace", null);
            assertEquals(expected(next, keep), keptIds(id), String.valueOf(s));
            assertEquals((long) (140 - expected(next, keep).size()), ((Number) s.get("rowsFilteredOut")).longValue(), String.valueOf(s));
        }
    }

    /** {@code rows} written as Parquet with the types the import reads (amount a number, booked_on a date). */
    private static Path parquet(Path target, List<Row> rows) throws Exception {
        Path src = target.resolveSibling(target.getFileName() + ".csv");
        Files.writeString(src, csv(rows));
        com.gamma.util.DuckDbUtil.loadDriver();
        try (java.sql.Connection conn = com.gamma.util.DuckDbUtil.openInMemory(null, List.of(target.getParent().toAbsolutePath()), null);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT row_id, from_account, to_account, channel, CAST(amount AS DOUBLE) AS amount, CAST(qty AS BIGINT) AS qty, "
                    + "CAST(booked_on AS DATE) AS booked_on, note FROM read_csv('" + src.toAbsolutePath().toString().replace('\\', '/')
                    + "', header = true, all_varchar = true)) TO '" + target.toAbsolutePath().toString().replace('\\', '/') + "' (FORMAT parquet)");
        }
        Files.delete(src);
        return target;
    }
}
