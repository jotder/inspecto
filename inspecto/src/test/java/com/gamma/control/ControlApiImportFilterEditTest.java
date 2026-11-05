package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ADD-DATA-FILTER-EDIT-1} over real HTTP: {@code PUT /datasets/{id}/filter} rewrites an import's row conditions
 * after the commit - the Pipeline's {@code csv_settings.where} and the entry's {@code importFilter} together - and the new
 * filter applies to the next files only: the rows already imported and the earlier arrivals stay as they were. Clearing it
 * removes the {@code where}; a refused condition is 422 naming it and changes nothing; the route is {@code canUploadData}.
 * Synthetic fixtures only.
 */
class ControlApiImportFilterEditTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPLOADER = "Bearer uploader", BUILDER = "Bearer builder", ADMIN = "Bearer admin";
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

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        return send(c, method, path, body, null);
    }

    private HttpResponse<String> putFilter(Ctx c, String id, Object filter, String auth) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("filter", filter);
        return send(c, "PUT", "/datasets/" + id + "/filter", JSON.writeValueAsBytes(b), auth);
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

    /** A committed import of {@code rows}, column {@code channel} renamed {@code via}, keeping rows by {@code filter}. */
    private void commit(Ctx c, String id, List<Row> rows, List<Map<String, Object>> filter, String auth) throws Exception {
        JsonNode d = data(send(c, "POST", "/onboard/files?name=first.csv&kind=stream", bytes(csv(rows)), auth), 200);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", id);
        b.put("columns", List.of(Map.of("name", "channel", "rename", "via")));
        if (filter != null) b.put("filter", filter);
        data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", JSON.writeValueAsBytes(b), auth), 200);
    }

    // ── synthetic rows ──────────────────────────────────────────────────────────────────────────────────────

    private record Row(String id, String channel, BigDecimal amount, long qty, LocalDate bookedOn, String note) {}

    private static final String[] CHANNELS = {"mobile", "branch", "online", "Phone"};
    private static final String[] NOTES = {"Rent", "refund", "Gift card", "salary", "misc"};

    private static List<Row> rows(long seed, int n, int firstId) {
        Random r = new Random(seed);
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
            out.add(new Row(String.format("R%05d", firstId + i), CHANNELS[r.nextInt(CHANNELS.length)],
                    BigDecimal.valueOf(100 + r.nextInt(99_900), 2), r.nextInt(50), LocalDate.of(2026, 1, 1).plusDays(r.nextInt(120)),
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

    private static Map<String, Object> entry(Ctx c, String id) {
        return new ComponentStore(c.root().resolve("registry")).get("dataset", id).orElseThrow().content();
    }

    private static String where(Ctx c, String id) {
        return c.svc().configFor(id).orElseThrow().csv().where();
    }

    // ── edit, then the next files ───────────────────────────────────────────────────────────────────────────

    @Test
    void anEditedFilterAppliesToTheNextFilesAndTheRowsAlreadyImportedStay(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Predicate<Row> before = x -> x.channel().equals("mobile");
            List<Row> first = rows(21L, 160, 1);
            String id = uniqueId("edit");
            commit(c, id, first, List.of(cond("channel", "eq", "mobile")), null);
            Set<String> imported = expected(first, before);
            assertEquals(imported, keptIds(id));
            JsonNode arrivalsBefore = data(send(c, "GET", "/datasets/" + id + "/arrivals", null), 200).get("arrivals");

            // the card reads the conditions and the columns a condition may name, by the Dataset's names and types
            JsonNode read = data(send(c, "GET", "/datasets/" + id + "/filter", null), 200);
            assertEquals("[{\"column\":\"via\",\"op\":\"eq\",\"value\":\"mobile\"}]", read.get("filter").toString());
            Map<String, String> types = new LinkedHashMap<>();
            for (JsonNode col : read.get("columns")) types.put(col.get("name").asText(), col.get("type").asText());
            assertEquals("VARCHAR", types.get("via"), read.toString());
            assertEquals("BIGINT", types.get("qty"), read.toString());
            assertEquals("DATE", types.get("booked_on"), read.toString());
            assertFalse(types.containsKey("channel"), "the draft's name is gone: " + read);

            // edit: branch rows with qty 10..30, matched by the Dataset's (renamed) column
            JsonNode saved = data(putFilter(c, id, List.of(cond("via", "eq", "branch"), range("qty", 10, 30)), null), 200);
            assertEquals("[{\"column\":\"via\",\"op\":\"eq\",\"value\":\"branch\"},{\"column\":\"qty\",\"op\":\"range\",\"min\":\"10\",\"max\":\"30\"}]",
                    saved.get("filter").toString());
            assertEquals("(\"via\" = 'branch') AND (\"qty\" >= 10 AND \"qty\" <= 30)", where(c, id), "the Pipeline runs the new predicate");
            assertEquals("[{column=via, op=eq, value=branch}, {column=qty, op=range, min=10, max=30}]",
                    String.valueOf(entry(c, id).get(OnboardImport.IMPORT_FILTER)), "the card's sentence follows");
            assertEquals(imported, keptIds(id), "the rows already imported stay: nothing is re-read");

            // the next file is filtered by the new conditions; the earlier rows keep the old ones
            Predicate<Row> after = x -> x.channel().equals("branch") && x.qty() >= 10 && x.qty() <= 30;
            List<Row> second = rows(22L, 140, 1000);
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/files?name=second.csv&mode=append", bytes(csv(second))), 200);
            Set<String> both = new TreeSet<>(imported);
            both.addAll(expected(second, after));
            assertEquals(both, keptIds(id), run.toString());
            assertEquals(140 - expected(second, after).size(), run.get("rowsFilteredOut").asLong(), run.toString());
            assertEquals(saved.get("filter"), run.get("filter"), "the run summary names the conditions in force");

            // the earlier arrival is unchanged
            JsonNode arrivals = data(send(c, "GET", "/datasets/" + id + "/arrivals", null), 200).get("arrivals");
            JsonNode firstNow = null, firstThen = null;
            for (JsonNode a : arrivals) if (a.get("file").asText().equals("first.csv")) firstNow = a;
            for (JsonNode a : arrivalsBefore) if (a.get("file").asText().equals("first.csv")) firstThen = a;
            assertNotNull(firstNow, arrivals.toString());
            assertEquals(firstThen, firstNow, "the first file's arrival is as it was");
            assertEquals(160L - imported.size(), firstNow.get("rowsFilteredOut").asLong());
        }
    }

    @Test
    void clearingTheFilterRemovesTheWhereAndTheNextFileKeepsEveryRow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<Row> first = rows(31L, 90, 1);
            String id = uniqueId("clear");
            commit(c, id, first, List.of(range("amount", 500, null)), null);
            assertNotNull(where(c, id));
            Set<String> imported = keptIds(id);

            JsonNode cleared = data(putFilter(c, id, List.of(), null), 200);
            assertEquals("[]", cleared.get("filter").toString());
            assertNull(where(c, id), "no where is left in the Pipeline");
            Map<String, Object> csv = PipelineConfigLoad.csvSettings(root.resolve("imports").resolve(id + "_pipeline.toon"));
            assertFalse(csv.containsKey("where"), "the key itself is gone: " + csv);
            assertFalse(entry(c, id).containsKey(OnboardImport.IMPORT_FILTER), "the card says nothing about rows");

            List<Row> second = rows(32L, 70, 1000);
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/files?name=second.csv&mode=append", bytes(csv(second))), 200);
            Set<String> all = new TreeSet<>(imported);
            all.addAll(expected(second, x -> true));
            assertEquals(all, keptIds(id), run.toString());

            // null clears too, and an import with no filter can gain one
            data(putFilter(c, id, null, null), 200);
            data(putFilter(c, id, List.of(cond("note", "contains", "REF")), null), 200);
            assertEquals("(contains(lower(\"note\"), lower('REF')))", where(c, id));
        }
    }

    // ── refusals ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aRefusedConditionIs422NamingItAndChangesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("refuse");
            commit(c, id, rows(41L, 60, 1), List.of(cond("channel", "eq", "mobile")), null);
            String whereBefore = where(c, id);
            Object entryBefore = entry(c, id).get(OnboardImport.IMPORT_FILTER);
            byte[] pipelineBefore = Files.readAllBytes(root.resolve("imports").resolve(id + "_pipeline.toon"));

            Map<String, Object> refusals = new LinkedHashMap<>();
            refusals.put("'" + id + "' has no column 'channel'", List.of(cond("channel", "eq", "x")));   // the draft's old name
            refusals.put("op must be one of eq, ne, contains, range", List.of(cond("via", "like", "%m%")));
            refusals.put("is not a whole number", List.of(cond("via", "eq", "mobile"), cond("qty", "eq", "1 OR 1=1")));
            refusals.put("is not a number", List.of(range("amount", "1E999999999", null)));   // the huge-exponent guard
            refusals.put("contains applies to text columns", List.of(cond("qty", "contains", "1")));
            refusals.put("unknown key 'where'", List.of(Map.of("column", "qty", "op", "eq", "value", 1, "where", "1=1")));
            refusals.put("filter must be a list", "via = 'x'");
            for (var e : refusals.entrySet()) {
                HttpResponse<String> r = putFilter(c, id, e.getValue(), null);
                assertEquals(422, r.statusCode(), e.getKey() + " " + r.body());
                assertTrue(error(r).contains(e.getKey()), "expected '" + e.getKey() + "' in: " + error(r));
            }
            HttpResponse<String> noKey = send(c, "PUT", "/datasets/" + id + "/filter", bytes("{}"));
            assertEquals(422, noKey.statusCode(), noKey.body());
            assertTrue(error(noKey).contains("filter is required"), noKey.body());

            assertEquals(whereBefore, where(c, id), "a refused edit leaves the Pipeline's where");
            assertArrayEquals(pipelineBefore, Files.readAllBytes(root.resolve("imports").resolve(id + "_pipeline.toon")));
            assertEquals(entryBefore, entry(c, id).get(OnboardImport.IMPORT_FILTER), "and the entry's conditions");

            // an unknown Dataset, and one Add data did not write
            assertEquals(404, putFilter(c, "nope_" + id, List.of(), null).statusCode());
            assertEquals(404, send(c, "GET", "/datasets/nope_" + id + "/filter", null).statusCode());

            // a text value shaped like SQL is saved as a literal: the next file has no such row, so nothing is imported
            data(putFilter(c, id, List.of(cond("via", "eq", "x' OR '1'='1")), null), 200);
            assertEquals("(\"via\" = 'x'' OR ''1''=''1')", where(c, id));
            HttpResponse<String> none = send(c, "POST", "/datasets/" + id + "/files?name=second.csv&mode=append", bytes(csv(rows(42L, 40, 1000))));
            assertEquals(422, none.statusCode(), none.body());
            assertTrue(error(none).contains("row filter kept none of its 40 rows"), none.body());
        }
    }

    // ── the capability ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void editFilterNeedsCanUploadDataABuilderIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case UPLOADER -> Optional.of(new Subject("uploader-1", Set.of("canUploadData")));
            case BUILDER -> Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canUploadData")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, send(c, "GET", "/datasets/x/filter", null).statusCode());
            assertEquals(401, putFilter(c, "x", List.of(), null).statusCode());
            assertEquals(403, send(c, "GET", "/datasets/x/filter", null, BUILDER).statusCode());
            assertEquals(403, putFilter(c, "x", List.of(), BUILDER).statusCode());

            data(send(c, "PUT", "/settings/data", bytes("{\"selfImport\":true}"), ADMIN), 200);
            String id = uniqueId("gated");
            commit(c, id, rows(51L, 50, 1), null, UPLOADER);
            HttpResponse<String> builder = putFilter(c, id, List.of(cond("via", "eq", "mobile")), BUILDER);
            assertEquals(403, builder.statusCode(), builder.body());
            assertNull(where(c, id), "the builder's refused edit wrote nothing");
            data(send(c, "GET", "/datasets/" + id + "/filter", null, UPLOADER), 200);
            data(putFilter(c, id, List.of(cond("via", "eq", "mobile")), UPLOADER), 200);
            assertEquals("(\"via\" = 'mobile')", where(c, id));
        }
    }

    /** Reads the Pipeline file's {@code processing.csv_settings} as written. */
    private static final class PipelineConfigLoad {
        @SuppressWarnings("unchecked")
        static Map<String, Object> csvSettings(Path file) throws Exception {
            Map<String, Object> p = com.gamma.util.ToonHelper.load(file.toString());
            return (Map<String, Object>) ((Map<String, Object>) p.get("processing")).get("csv_settings");
        }
    }
}
