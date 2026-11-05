package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code LA-ARRIVAL-NEW-COLUMNS-1} over real HTTP (operator 2026-11-06, plan D-6): a later file's new column is kept as a
 * detail of an import Dataset - the schema and the Dataset's columns grow - and the older files, which lack it, are read
 * together with the newer ones by name. A file without a kept column is refused, naming it. Add a file may leave new
 * columns out ({@code newColumns=leave}); a run keeps only what every file it takes carries; an approval policy over
 * Datasets leaves it out and says why; a compaction merges files whose columns differ. Synthetic fixtures only.
 */
class ControlApiArrivalNewColumnsTest {

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

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private String uniqueId(String base) {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        return id;
    }

    /** Commit {@code csv} as the stream Dataset {@code id}; {@code extra} is more of the commit body (or empty). */
    private void commit(Ctx c, String id, String csv, String extra) throws Exception {
        JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", csv), 200);
        data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"" + extra + "}"), 200);
    }

    /** {@code csv} with one more column {@code name}, row i (from 1) holding {@code value.apply(i)}. */
    static String withColumn(String csv, String name, IntFunction<String> value) {
        StringBuilder sb = new StringBuilder();
        String[] lines = csv.split("\n");
        for (int i = 0; i < lines.length; i++) sb.append(lines[i]).append(',').append(i == 0 ? name : value.apply(i)).append('\n');
        return sb.toString();
    }

    private static String transfers(int rows) {
        return ControlApiOnboardFilesTest.transfers(rows);
    }

    /**
     * Every row of the Dataset as the engine reads it ({@code GET /datasets/{id}/rows}: the Dataset read Link Analysis and
     * BI use), each by column name; a missing value is null.
     */
    private List<Map<String, String>> rows(Ctx c, String id) throws Exception {
        JsonNode r = data(send(c, "GET", "/datasets/" + id + "/rows?limit=10000", null), 200);
        List<String> names = new ArrayList<>();
        for (JsonNode col : r.get("columns")) names.add(col.get("name").asText());
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode row : r.get("rows")) {
            Map<String, String> m = new java.util.TreeMap<>();
            for (int i = 0; i < names.size(); i++) {
                JsonNode v = row.isArray() ? row.get(i) : row.get(names.get(i));
                m.put(names.get(i), v == null || v.isNull() ? null : v.asText());
            }
            out.add(m);
        }
        return out;
    }

    private static long count(List<Map<String, String>> rows, String column) {
        return rows.stream().filter(r -> r.get(column) != null).count();
    }

    private Map<String, Object> column(Ctx c, String id, String name) {
        for (Object o : (List<?>) new ComponentStore(c.root().resolve("registry")).get("dataset", id).orElseThrow().content().get("columns"))
            if (o instanceof Map<?, ?> m && name.equals(m.get("name"))) {
                @SuppressWarnings("unchecked") Map<String, Object> col = (Map<String, Object>) m;
                return col;
            }
        return null;
    }

    @Test
    void aLaterFilesNewColumnIsKeptAsADetailAndReadTogetherWithTheOlderFiles(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("kept");
            commit(c, id, transfers(50), "");

            JsonNode wider = data(send(c, "POST", "/datasets/" + id + "/files?name=wider.csv&mode=append",
                    withColumn(transfers(20), "fee", i -> "1." + i)), 200);
            assertEquals(70, wider.get("rowsAfter").asLong(), wider.toString());
            assertEquals("[\"fee\"]", wider.get("keptColumns").toString(), wider.toString());
            assertFalse(wider.has("newColumns"), "nothing was left out: " + wider);
            assertFalse(wider.has("newColumnsReason"), wider.toString());
            Map<String, Object> fee = column(c, id, "fee");
            assertNotNull(fee, "the Dataset lists the kept column");
            assertEquals("detail", fee.get("role"));
            assertEquals("string", fee.get("type"));
            assertEquals("wider.csv", ((Map<?, ?>) fee.get(DatasetNewColumns.KEPT_FROM)).get("file"), fee.toString());
            assertTrue(Files.readString(root.resolve("imports").resolve(id + "_schema.toon")).contains("fee"), "the schema reads it");
            JsonNode arrival = data(send(c, "GET", "/datasets/" + id + "/arrivals", null), 200).get("arrivals").get(0);
            assertEquals("[\"fee\"]", arrival.get("keptColumns").toString(), arrival.toString());

            // the older file (no fee) and the newer one read together, by name: fee is empty on the older rows only
            List<Map<String, String>> all = rows(c, id);
            assertEquals(70, all.size());
            assertEquals(20, count(all, "fee"), "fee is empty on the older file's 50 rows");
            assertTrue(all.stream().anyMatch(r -> "1.1".equals(r.get("fee"))), "the newer file's values are read");
            assertEquals(50, all.stream().filter(r -> r.get("fee") == null && r.get("transfer_id") != null).count());
            JsonNode table = data(send(c, "GET", "/db/table?name=" + id + "/database&limit=500", null), 200);
            assertEquals(70, table.get("rows").size(), "the table browser reads the mixed files: " + table.get("columns"));

            // once kept, a file without it is refused like any missing column, saying where it came from
            HttpResponse<String> narrow = send(c, "POST", "/datasets/" + id + "/files?name=narrow.csv&mode=append", transfers(10));
            assertEquals(422, narrow.statusCode(), narrow.body());
            assertTrue(narrow.body().contains("'fee' is missing (kept from 'wider.csv' on 20"), narrow.body());

            // the person leaves a new column out on Add a file: as before LA-ARRIVAL-NEW-COLUMNS-1, named with the reason
            JsonNode left = data(send(c, "POST", "/datasets/" + id + "/files?name=taxed.csv&mode=append&newColumns=leave",
                    withColumn(withColumn(transfers(20), "fee", i -> "2"), "tax", i -> "0.2")), 200);
            assertEquals(90, left.get("rowsAfter").asLong(), left.toString());
            assertEquals("[\"tax\"]", left.get("newColumns").toString(), left.toString());
            assertTrue(left.get("newColumnsReason").asText().contains("you chose"), left.toString());
            assertFalse(left.has("keptColumns"), left.toString());
            assertNull(column(c, id, "tax"), "not kept");
            assertEquals(0, left.get("rowsRejected").asLong(), "the left-out column was cut from the file: " + left);
            assertEquals(40, count(rows(c, id), "fee"));
            assertEquals(422, send(c, "POST", "/datasets/" + id + "/files?name=x.csv&mode=append&newColumns=maybe",
                    withColumn(transfers(3), "fee", i -> "1")).statusCode());

            // Run now keeps only what EVERY file it takes carries: tax is in one drop of two, so it is left out of both
            Path inbox = Path.of("database", "inbox", id);
            Files.writeString(inbox.resolve("a_drop.csv"), withColumn(withColumn(transfers(5), "fee", i -> "3"), "tax", i -> "0.3"));
            Files.writeString(inbox.resolve("b_drop.csv"), withColumn(transfers(6).replace("T0000", "B0000"), "fee", i -> "3"));
            JsonNode two = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(101, two.get("rowsAfter").asLong(), two.toString());
            assertTrue(two.get("newColumnsReason").asText().contains("not every file"), two.toString());
            assertNull(column(c, id, "tax"));

            // ... and keeps it on its own when the one file it takes carries it (unattended runs keep)
            Files.writeString(inbox.resolve("c_drop.csv"), withColumn(withColumn(transfers(4).replace("T0000", "C0000"), "fee", i -> "4"), "tax", i -> "0.4"));
            JsonNode one = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals("[\"tax\"]", one.get("keptColumns").toString(), one.toString());
            assertEquals("c_drop.csv", ((Map<?, ?>) column(c, id, "tax").get(DatasetNewColumns.KEPT_FROM)).get("file"));
            List<Map<String, String>> t = rows(c, id);
            assertEquals(105, t.size());
            assertEquals(4, count(t, "tax"));
            assertEquals(55, count(t, "fee"));
        }
    }

    /** NDJSON reads by field name: a new field is kept the same way and older files read it as empty. */
    @Test
    void aNewJsonFieldIsKeptByName(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("json");
            StringBuilder first = new StringBuilder(), later = new StringBuilder();
            for (int i = 1; i <= 30; i++)
                first.append(String.format("{\"call_id\":\"C%03d\",\"caller\":\"N%02d\",\"callee\":\"N%02d\"}\n", i, i % 9, (i * 5) % 9 + 9));
            for (int i = 31; i <= 40; i++)
                later.append(String.format("{\"call_id\":\"C%03d\",\"caller\":\"N%02d\",\"callee\":\"N%02d\",\"cell\":\"X%d\"}\n", i, i % 9, i % 7 + 9, i));
            JsonNode d = data(send(c, "POST", "/onboard/files?name=calls.ndjson&kind=stream", first.toString()), 200);
            data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}"), 200);
            JsonNode s = data(send(c, "POST", "/datasets/" + id + "/files?name=later.ndjson&mode=append", later.toString()), 200);
            assertEquals("[\"cell\"]", s.get("keptColumns").toString(), s.toString());
            List<Map<String, String>> n = rows(c, id);
            assertEquals(40, n.size());
            assertEquals(10, count(n, "cell"));
        }
    }

    /** Under an approval policy over Datasets a column is never added around it: left out, and the result says why. */
    @Test
    void underAnApprovalPolicyANewColumnIsLeftOutAndTheResultSaysWhy(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("governed");
            commit(c, id, transfers(30), "");
            Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  dataset:\n    required: true\n");
            JsonNode s = data(send(c, "POST", "/datasets/" + id + "/files?name=wider.csv&mode=append",
                    withColumn(transfers(10), "fee", i -> "1")), 200);
            assertEquals(40, s.get("rowsAfter").asLong(), s.toString());
            assertEquals("[\"fee\"]", s.get("newColumns").toString(), s.toString());
            assertTrue(s.get("newColumnsReason").asText().contains("approval policy"), s.toString());
            assertNull(column(c, id, "fee"));
            assertFalse(Files.readString(root.resolve("imports").resolve(id + "_schema.toon")).contains("fee"));
        }
    }

    /** A kept column passes through the import's row filter: the filter's columns are in every file the import takes. */
    @Test
    void aFilteredImportKeepsANewColumnAndStillFilters(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("filtered");
            commit(c, id, transfers(60), ",\"filter\":[{\"column\":\"channel\",\"op\":\"eq\",\"value\":\"mobile\"}]");
            long mobileBefore = rows(c, id).size();
            JsonNode s = data(send(c, "POST", "/datasets/" + id + "/files?name=wider.csv&mode=append",
                    withColumn(transfers(30).replace("T000", "W000"), "fee", i -> "1")), 200);
            assertEquals("[\"fee\"]", s.get("keptColumns").toString(), s.toString());
            List<Map<String, String>> n = rows(c, id);
            assertEquals(mobileBefore + 10, n.size(), "transfers(30) holds 10 mobile rows");
            assertEquals(10, count(n, "fee"));
            assertEquals(0, n.stream().filter(r -> !"mobile".equals(r.get("channel"))).count());
        }
    }

    /** Compaction merges files whose columns differ by name: no row and no value is lost. */
    @Test
    void compactionMergesFilesWhoseColumnsDiffer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("compacted");
            commit(c, id, transfers(40), "");
            data(send(c, "POST", "/datasets/" + id + "/files?name=wider.csv&mode=append",
                    withColumn(transfers(15).replace("T000", "W000"), "fee", i -> "5." + i)), 200);
            data(send(c, "POST", "/datasets/" + id + "/files?name=widest.csv&mode=append",
                    withColumn(withColumn(transfers(5).replace("T000", "V000"), "fee", i -> "6"), "tax", i -> "0." + i)), 200);
            List<String> before = rows(c, id).stream().map(Object::toString).sorted().toList();
            JsonNode now = data(send(c, "POST", "/datasets/" + id + "/compact", null), 200);
            assertEquals(3, now.at("/compaction/filesBefore").asInt(), now.toString());
            assertEquals(1, now.at("/compaction/filesAfter").asInt(), now.toString());
            List<Map<String, String>> merged = rows(c, id);
            assertEquals(before, merged.stream().map(Object::toString).sorted().toList(), "the merged file holds every row and value");
            assertEquals(60, merged.size());
            assertEquals(20, count(merged, "fee"));
            assertEquals(5, count(merged, "tax"));
        }
    }
}
