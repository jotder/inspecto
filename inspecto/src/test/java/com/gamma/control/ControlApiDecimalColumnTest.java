package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.util.UUID;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DECIMAL-TEXT-NOT-BIGINT-1} over real HTTP (P2 defect, live check 2026-11-06): an {@code amount} column whose sampled
 * values all end in {@code .00} was typed BIGINT, and a later file's {@code 12.25} was stored as 12 with no rejection. Now
 * the draft types text with a decimal point (or a fraction anywhere in the file) as DOUBLE, and a later file whose value
 * would be cut by an existing whole-number column is refused (Add a file 422; a waiting file set aside), naming the column
 * and the value. Whole numbers keep landing in a BIGINT column. Synthetic fixtures only.
 */
class ControlApiDecimalColumnTest {

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
            return new Ctx(svc, api, api.port());
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

    /** {@code rows} payments, row i (from 1) paying {@code amount.apply(i)}; {@code tag} keeps ids distinct per file. */
    private static String payments(String tag, int rows, IntFunction<String> amount) {
        StringBuilder sb = new StringBuilder("payment_id,from_account,to_account,amount\n");
        for (int i = 1; i <= rows; i++)
            sb.append(String.format("%s%05d,ACC-%04d,ACC-%04d,%s\n", tag, i, i % 40 + 1, (i * 7) % 40 + 1, amount.apply(i)));
        return sb.toString();
    }

    private static JsonNode amountType(JsonNode draft) {
        for (JsonNode c : draft.get("columns")) if ("amount".equals(c.get("name").asText())) return c.get("type");
        throw new AssertionError("no amount column in " + draft.get("columns"));
    }

    /** Draft {@code csv}, assert its amount type, and commit it as the stream Dataset {@code id}. */
    private void commit(Ctx c, String id, String csv, String expectedType) throws Exception {
        JsonNode d = data(send(c, "POST", "/onboard/files?name=payments.csv&kind=stream", csv), 200);
        assertEquals(expectedType, amountType(d).get("value").asText(), amountType(d).toString());
        data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}"), 200);
    }

    /** The Dataset's amount values as the engine reads them, sorted. */
    private List<String> amounts(Ctx c, String id) throws Exception {
        JsonNode r = data(send(c, "GET", "/datasets/" + id + "/rows?limit=10000", null), 200);
        int at = -1, i = 0;
        for (JsonNode col : r.get("columns")) {
            if ("amount".equals(col.get("name").asText())) at = i;
            i++;
        }
        assertTrue(at >= 0, r.get("columns").toString());
        List<String> out = new ArrayList<>();
        for (JsonNode row : r.get("rows")) {
            JsonNode v = row.isArray() ? row.get(at) : row.get("amount");
            out.add(v == null || v.isNull() ? null : v.asText());
        }
        return out;
    }

    @Test
    void amountsWrittenWithDotZeroAreADecimalAndALaterFractionIsKept(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("cents");
            commit(c, id, payments("P", 20, i -> (10 + i * 37) + ".00"), "DOUBLE");

            JsonNode later = data(send(c, "POST", "/datasets/" + id + "/files?name=later.csv&mode=append",
                    payments("L", 3, i -> "12.25")), 200);
            assertEquals(0, later.get("rowsRejected").asLong(), later.toString());
            assertEquals(23, later.get("rowsAfter").asLong(), later.toString());
            List<String> amounts = amounts(c, id);
            assertEquals(3, amounts.stream().filter("12.25"::equals).count(), "the cents are kept: " + amounts);
            assertFalse(amounts.contains("12"), amounts.toString());
        }
    }

    @Test
    void aFractionPastTheSampleMakesTheDraftADecimal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // 1,200 rows: the first 1,000 (the sample) are whole numbers, row 1,150 has cents
            JsonNode d = data(send(c, "POST", "/onboard/files?name=long.csv&kind=stream",
                    payments("P", 1200, i -> i == 1150 ? "99.95" : String.valueOf(10 + i))), 200);
            JsonNode t = amountType(d);
            assertEquals("DOUBLE", t.get("value").asText(), t.toString());
            assertTrue(t.get("evidence").asText().contains("99.95"), t.toString());
        }
    }

    @Test
    void aWholeNumberDatasetTakesWholeNumbersAndRefusesAFractionByName(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("whole");
            commit(c, id, payments("P", 20, i -> String.valueOf(1000 + i)), "BIGINT");

            // an existing BIGINT column keeps working for whole numbers, '60.00' included (nothing is cut)
            JsonNode ok = data(send(c, "POST", "/datasets/" + id + "/files?name=more.csv&mode=append",
                    payments("M", 2, i -> i == 1 ? "50" : "60.00")), 200);
            assertEquals(22, ok.get("rowsAfter").asLong(), ok.toString());
            assertTrue(amounts(c, id).containsAll(List.of("50", "60")), amounts(c, id).toString());

            // a fraction is refused, naming the column and the value; nothing lands
            HttpResponse<String> cut = send(c, "POST", "/datasets/" + id + "/files?name=cents.csv&mode=append",
                    payments("C", 3, i -> i == 2 ? "12.25" : "13"));
            assertEquals(422, cut.statusCode(), cut.body());
            assertTrue(cut.body().contains("'amount' holds whole numbers and this file has '12.25'"), cut.body());
            assertEquals(22, amounts(c, id).size());
            assertFalse(amounts(c, id).contains("12"), amounts(c, id).toString());

            // the whole later file is checked, not only its first 1,000 rows
            HttpResponse<String> deep = send(c, "POST", "/datasets/" + id + "/files?name=deep.csv&mode=append",
                    payments("D", 1200, i -> i == 1190 ? "7.5" : "7"));
            assertEquals(422, deep.statusCode(), deep.body());
            assertTrue(deep.body().contains("'7.5'"), deep.body());
            assertEquals(22, amounts(c, id).size());

            // a waiting file with a fraction is set aside with the same reason; Run now takes nothing from it
            Path inbox = Path.of("database", "inbox", id);
            Files.createDirectories(inbox);
            Files.writeString(inbox.resolve("drop.csv"), payments("W", 3, i -> "8.10"));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(22, run.get("rowsAfter").asLong(), run.toString());
            assertTrue(run.get("refused").toString().contains("holds whole numbers and this file has '8.10'"), run.toString());
            assertFalse(Files.exists(inbox.resolve("drop.csv")), "set aside");
        }
    }

    /** A headerless import reads line 1 as data, so a fraction on line 1 alone is refused too (verification finding). */
    @Test
    void aHeaderlessImportRefusesAFractionOnTheFirstLine(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("bare");
            String body = payments("P", 20, i -> String.valueOf(1000 + i));
            JsonNode d = data(send(c, "POST", "/onboard/files?name=bare.csv&kind=stream&header=false",
                    body.substring(body.indexOf('\n') + 1)), 200);
            data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}"), 200);

            HttpResponse<String> cut = send(c, "POST", "/datasets/" + id + "/files?name=first.csv&mode=append",
                    "L1,ACC-1,ACC-12,12.25\nL2,ACC-2,ACC-13,7\n");
            assertEquals(422, cut.statusCode(), cut.body());
            assertTrue(cut.body().contains("'col_4' holds whole numbers and this file has '12.25'"), cut.body());
        }
    }

    /** {@code csv} with one more column {@code name}, row i (from 1) holding {@code value.apply(i)}. */
    private static String withColumn(String csv, String name, IntFunction<String> value) {
        return ControlApiArrivalNewColumnsTest.withColumn(csv, name, value);
    }

    /** The import schema's raw field {@code name}: its type as written. */
    private static String schemaType(Path root, String id, String name) throws Exception {
        java.util.Map<String, Object> s = com.gamma.util.ToonHelper.load(root.resolve("imports").resolve(id + "_schema.toon").toString());
        for (Object f : (List<?>) ((java.util.Map<?, ?>) s.get("raw")).get("fields"))
            if (f instanceof java.util.Map<?, ?> m && name.equals(m.get("name"))) return String.valueOf(m.get("type"));
        throw new AssertionError("no field " + name);
    }

    /**
     * A kept new column (LA-ARRIVAL-NEW-COLUMNS-1) gets the type its values imply, by the same rules (operator 2026-11-06):
     * {@code 0.50} is DOUBLE, so it can take a range filter; whole numbers are BIGINT and a later fraction into it is
     * refused; text stays text.
     */
    @Test
    void aKeptNewColumnIsTypedByItsValues(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("kept");
            commit(c, id, payments("P", 20, i -> String.valueOf(1000 + i)), "BIGINT");

            String wider = withColumn(withColumn(withColumn(payments("F", 10, i -> "7"), "fee", i -> "0.50"), "qty", i -> String.valueOf(i)),
                    "note", i -> "n" + i);
            JsonNode s = data(send(c, "POST", "/datasets/" + id + "/files?name=wider.csv&mode=append", wider), 200);
            assertEquals("[\"fee\",\"qty\",\"note\"]", s.get("keptColumns").toString(), s.toString());
            assertEquals("DOUBLE", schemaType(root, id, "fee"));
            assertEquals("BIGINT", schemaType(root, id, "qty"));
            assertEquals("VARCHAR", schemaType(root, id, "note"));

            // the decimal kept column takes a range filter (a text column could not)
            data(send(c, "PUT", "/datasets/" + id + "/filter",
                    "{\"filter\":[{\"column\":\"fee\",\"op\":\"range\",\"min\":\"0.25\"}]}"), 200);

            // the whole-number kept column refuses a fraction, like any BIGINT column
            HttpResponse<String> cut = send(c, "POST", "/datasets/" + id + "/files?name=cut.csv&mode=append",
                    withColumn(withColumn(withColumn(payments("G", 3, i -> "7"), "fee", i -> "1.75"), "qty", i -> i == 2 ? "2.5" : "2"),
                            "note", i -> "x"));
            assertEquals(422, cut.statusCode(), cut.body());
            assertTrue(cut.body().contains("'qty' holds whole numbers and this file has '2.5'"), cut.body());

            // a later value that is not a number is taken with the column empty, as for any typed column
            JsonNode na = data(send(c, "POST", "/datasets/" + id + "/files?name=na.csv&mode=append",
                    withColumn(withColumn(withColumn(payments("H", 2, i -> "7"), "fee", i -> i == 1 ? "n/a" : "0.75"), "qty", i -> "3"),
                            "note", i -> "y")), 200);
            assertEquals(0, na.get("rowsRejected").asLong(), na.toString());
            // ... and the range filter above leaves out that row, whose fee is empty; the 0.75 one lands
            assertEquals(1, na.get("rowsFilteredOut").asLong(), na.toString());
            assertEquals(31, na.get("rowsAfter").asLong(), na.toString());
        }
    }

    /** Two files of one run disagree: whole numbers and decimals widen to DOUBLE; numbers and text stay text. */
    @Test
    void aKeptColumnWhoseFilesDisagreeIsWidened(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("mixed");
            commit(c, id, payments("P", 20, i -> String.valueOf(1000 + i)), "BIGINT");
            Path inbox = Path.of("database", "inbox", id);
            Files.createDirectories(inbox);
            Files.writeString(inbox.resolve("a.csv"), withColumn(withColumn(payments("A", 5, i -> "7"), "fee", i -> "1"), "ref", i -> "9"));
            Files.writeString(inbox.resolve("b.csv"), withColumn(withColumn(payments("B", 5, i -> "7"), "fee", i -> "1.5"), "ref", i -> "r" + i));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals("[\"fee\",\"ref\"]", run.get("keptColumns").toString(), run.toString());
            assertEquals("DOUBLE", schemaType(root, id, "fee"));
            assertEquals("VARCHAR", schemaType(root, id, "ref"));
            assertEquals(30, run.get("rowsAfter").asLong(), run.toString());
        }
    }

    /** NDJSON reads by field name: a fraction into a whole-number field is refused the same way. */
    @Test
    void anNdjsonFractionIntoAWholeNumberFieldIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("events");
            StringBuilder first = new StringBuilder();
            for (int i = 1; i <= 20; i++) first.append("{\"payment_id\":\"P").append(i).append("\",\"amount\":").append(1000 + i).append("}\n");
            JsonNode d = data(send(c, "POST", "/onboard/files?name=events.ndjson&kind=stream", first.toString()), 200);
            assertEquals("BIGINT", amountType(d).get("value").asText(), amountType(d).toString());
            data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}"), 200);

            HttpResponse<String> cut = send(c, "POST", "/datasets/" + id + "/files?name=later.ndjson&mode=append",
                    "{\"payment_id\":\"Q1\",\"amount\":7}\n{\"payment_id\":\"Q2\",\"amount\":12.25}\n");
            assertEquals(422, cut.statusCode(), cut.body());
            assertTrue(cut.body().contains("'amount' holds whole numbers and this file has '12.25'"), cut.body());
        }
    }
}
