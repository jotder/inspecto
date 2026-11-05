package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Add data over real HTTP ({@code LA-ONBOARD-1} phase 2, OB-2/OB-3/OB-4/OB-7/OB-8): {@code POST /onboard/files} answers a
 * draft whose every choice carries evidence and an OF-2 confidence; {@code POST /onboard/files/{draftId}/commit} writes an
 * ordinary {@code origin: import} Pipeline with index selectors, runs it once and registers the Dataset with classifications,
 * {@code primaryEntityType} and {@code defaultLinkMapping}; a failed commit leaves nothing behind; Add a file and Run now;
 * the per-Space {@code data.self_import} switch and the capability gates; the DT-8 limits and the Excel refusal. Synthetic
 * fixtures only. The Link Analysis half (projection without a saved view, profile links, the in-run index) is
 * {@code ControlApiOnboardLinkAnalysisTest} in {@code inspecto-geo-link}, where that module is on the classpath.
 */
class ControlApiOnboardFilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPLOADER = "Bearer uploader", ANALYST = "Bearer analyst", ADMIN = "Bearer admin", AUTHOR = "Bearer author", UPLOADER2 = "Bearer uploader2";
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
        System.clearProperty("duckdb.extension.dir");
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        // the default Space's data root is ./database in this module: leave nothing behind in the checkout
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

    private String uniqueId(String base) {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        return id;
    }

    // ── synthetic fixtures ──────────────────────────────────────────────────────────────────────────────────

    /** {@code rows} transfers between 40 accounts over three channels; deterministic. */
    static String transfers(int rows) {
        StringBuilder sb = new StringBuilder("transfer_id,from_account,to_account,channel,amount,booked_at,currency\n");
        String[] channels = {"mobile", "branch", "online"};
        for (int i = 1; i <= rows; i++) {
            int from = (i * 7) % 40 + 1, to = (i * 13 + 5) % 40 + 1;
            if (to == from) to = to % 40 + 1;
            sb.append(String.format("T%05d,ACC-%04d,ACC-%04d,%s,%d.%02d,2026-09-%02d %02d:%02d:00,EUR\n", i, from, to,
                    channels[i % 3], 10 + (i * 37) % 900, i % 100, 1 + i % 28, i % 24, i % 60));
        }
        return sb.toString();
    }

    static String accounts(int rows) {
        StringBuilder sb = new StringBuilder("account_id,holder_name,kyc_level,segment\n");
        for (int i = 1; i <= rows; i++)
            sb.append(String.format("ACC-%04d,Holder %d,%d,%s\n", i, i, 1 + i % 3, i % 2 == 0 ? "retail" : "business"));
        return sb.toString();
    }

    private JsonNode draft(Ctx c, String name, String kind, String content, String auth) throws Exception {
        return data(send(c, "POST", "/onboard/files?name=" + name + "&kind=" + kind, content.getBytes(StandardCharsets.UTF_8), auth), 200);
    }

    private static JsonNode column(JsonNode draft, String name) {
        for (JsonNode c : draft.get("columns")) if (c.get("name").asText().equals(name)) return c;
        throw new AssertionError("no column " + name + " in " + draft.get("columns"));
    }

    private static JsonNode entityOf(JsonNode draft, String column) {
        for (JsonNode c : draft.get("entityTypes").get("columns")) if (c.get("column").asText().equals(column)) return c.get("entityType");
        throw new AssertionError("no entity proposal for " + column);
    }

    private static void assertChoice(JsonNode choice) {
        assertTrue(choice.has("value") && choice.has("evidence") && choice.has("confidence"), "a choice is {value, evidence, confidence}: " + choice);
        assertFalse(choice.get("evidence").asText().isBlank(), "evidence is stated: " + choice);
        assertTrue(Set.of("high", "low").contains(choice.get("confidence").asText()), choice.toString());
    }

    // ── the draft ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theDraftProposesEveryChoiceWithEvidenceAndConfidence(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(120), null);
            assertTrue(d.get("draftId").asText().matches("d[0-9a-f]{32}"), d.toString());
            assertEquals(120, d.get("file").get("rows").asLong());
            assertEquals(20, d.get("rows").size(), "a preview of the first rows");

            JsonNode f = d.get("format");
            for (String k : List.of("type", "delimiter", "header", "quote")) assertChoice(f.get(k));
            assertEquals("csv", f.get("type").get("value").asText());
            assertEquals(",", f.get("delimiter").get("value").asText());
            assertEquals("high", f.get("delimiter").get("confidence").asText(), f.toString());
            assertTrue(f.get("delimiter").get("evidence").asText().contains("120 sampled lines splits into 7 fields"), f.toString());
            assertTrue(f.get("header").get("value").asBoolean());
            assertEquals("high", f.get("header").get("confidence").asText());

            JsonNode amount = column(d, "amount").get("type");
            assertChoice(amount);
            assertEquals("DOUBLE", amount.get("value").asText());
            assertEquals("high", amount.get("confidence").asText());
            assertTrue(amount.get("evidence").asText().contains("all 120 sampled values parse as double"), amount.toString());
            JsonNode booked = column(d, "booked_at").get("type");
            assertEquals("TIMESTAMP", booked.get("value").asText());
            assertEquals("%Y-%m-%d %H:%M:%S", booked.get("format").asText(), "the proven strptime format rides the choice");

            JsonNode from = entityOf(d, "from_account");
            assertChoice(from);
            assertEquals("account", from.get("value").asText());
            assertEquals("ACCOUNT", from.get("classification").asText());
            assertEquals("high", from.get("confidence").asText(), from.toString());
            assertTrue(from.get("evidence").asText().contains("the name 'from_account' says account"), from.toString());
            assertTrue(entityOf(d, "amount").get("value").isNull(), "an amount is not an identifier");
            assertTrue(entityOf(d, "transfer_id").get("value").isNull());

            JsonNode et = d.get("entityTypes");
            assertEquals("account", et.get("primary").get("value").asText());
            assertTrue(et.get("primary").get("evidence").asText().contains("2 columns hold account (from_account, to_account)"), et.toString());
            JsonNode link = et.get("link");
            for (String k : List.of("from", "to", "kind", "primaryTime", "primaryMeasure", "details")) assertChoice(link.get(k));
            assertEquals("from_account", link.get("from").get("value").asText());
            assertEquals("to_account", link.get("to").get("value").asText());
            assertEquals("high", link.get("from").get("confidence").asText());
            assertTrue(link.get("from").get("evidence").asText().contains("the names say from_account → to_account"), link.toString());
            assertEquals("channel", link.get("kind").get("value").asText());
            assertEquals("booked_at", link.get("primaryTime").get("value").asText());
            assertEquals("amount", link.get("primaryMeasure").get("value").asText());
            assertEquals("[\"transfer_id\",\"currency\"]", link.get("details").get("value").toString());

            JsonNode lim = d.get("limits");
            assertEquals(500L * 1024 * 1024, lim.get("maxBytes").asLong());
            assertEquals(10_000_000L, lim.get("maxRows").asLong());
            assertEquals(20, lim.get("maxImports").asInt());
            assertEquals(0, lim.get("imports").asInt());
            assertFalse(Files.exists(root.resolve("imports")), "a draft writes no config");
        }
    }

    @Test
    void aSmallSampleIsLowConfidenceAndValueShapesAreRecognised(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // 12 rows: below OF-2's 50 non-blank values, so nothing is high
            JsonNode d = draft(c, "few.csv", "stream", transfers(12), null);
            assertEquals("low", d.get("format").get("delimiter").get("confidence").asText());
            assertEquals("low", column(d, "amount").get("type").get("confidence").asText());
            assertEquals("low", entityOf(d, "from_account").get("confidence").asText());

            // value shapes: valid IMEIs (Luhn) and + phone numbers, under names that say nothing
            StringBuilder sb = new StringBuilder("a,b,c\n");
            for (int i = 0; i < 60; i++) {
                String body = String.format("35%012d", 100000 + i);
                sb.append(body).append(luhnDigit(body)).append(",+3361234").append(String.format("%04d", i)).append(",x").append(i).append('\n');
            }
            JsonNode s = draft(c, "shapes.csv", "stream", sb.toString(), null);
            JsonNode imei = entityOf(s, "a");
            assertEquals("imei", imei.get("value").asText(), imei.toString());
            assertTrue(imei.get("evidence").asText().contains("15-digit numbers with a valid check digit"), imei.toString());
            assertEquals("high", imei.get("confidence").asText());
            JsonNode phone = entityOf(s, "b");
            assertEquals("msisdn", phone.get("value").asText(), phone.toString());
            assertEquals("a", s.get("entityTypes").get("link").get("from").get("value").asText(), "two kinds of thing: a link proposed, low");
            assertEquals("low", s.get("entityTypes").get("link").get("from").get("confidence").asText());

            // the user's own format choice re-reads the file and is high confidence
            JsonNode o = data(send(c, "POST", "/onboard/files?name=semi.csv&delimiter=;", "a;b\n1;2\n".getBytes(StandardCharsets.UTF_8), null), 200);
            assertEquals(";", o.get("format").get("delimiter").get("value").asText());
            assertEquals("high", o.get("format").get("delimiter").get("confidence").asText());
        }
    }

    private static char luhnDigit(String body) {
        for (char d = '0'; d <= '9'; d++) if (com.gamma.entitystore.ValueShapes.fits("luhn(15)", body + d)) return d;
        throw new AssertionError();
    }

    // ── commit ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void oneConfirmWritesAnImportPipelineRunsItAndRegistersTheDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("transfers");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(120), null);
            JsonNode s = data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            assertEquals(id, s.get("dataset").asText());
            assertEquals(120, s.get("rowsIn").asLong(), s.toString());
            assertEquals(120, s.get("rowsAfter").asLong());
            assertEquals(0, s.get("rowsRejected").asLong());
            assertEquals(0, s.get("quarantined").size());
            assertTrue(s.get("followOnInRun").asBoolean(), "DT-16: the follow-on work ran inside this run");
            assertEquals("NOT_AVAILABLE", s.get("index").get("result").asText(), "no Link Analysis module in this module's classpath");
            assertEquals(0, s.get("expectations").size());

            // an ordinary Pipeline marked origin: import, never polled, index selectors (OB-4)
            String toon = Files.readString(root.resolve("imports").resolve(id + "_pipeline.toon"));
            assertTrue(toon.contains("origin: import"), toon);
            assertTrue(toon.contains("type: manual"), toon);
            assertTrue(toon.contains("accepts_upload: true"), toon);
            String schema = Files.readString(root.resolve("imports").resolve(id + "_schema.toon"));
            assertTrue(schema.contains("from_account,\"1\",VARCHAR") || schema.contains("selector: \"1\""), schema);
            JsonNode pipe = data(json(c, "GET", "/config/pipeline/" + id, null, null), 200);
            assertEquals("import", pipe.get("config").get("origin").asText());

            JsonNode ds = data(json(c, "GET", "/components/dataset/" + id, null, null), 200).get("content");
            assertEquals(id + "/database", ds.get("physicalRef").asText());
            assertEquals("import", ds.get("origin").asText());
            assertEquals("account", ds.get("primaryEntityType").asText());
            assertEquals("booked_at", ds.get("eventDate").asText());
            JsonNode m = ds.get("defaultLinkMapping");
            assertEquals("from_account", m.get("from").asText());
            assertEquals("to_account", m.get("to").asText());
            assertEquals("channel", m.get("kind").asText());
            assertEquals("booked_at", m.get("primaryTime").asText());
            assertEquals("amount", m.get("primaryMeasure").asText());
            assertFalse(m.has("time") || m.has("amount"), "the old names are only read, never written: " + m);
            int classified = 0;
            for (JsonNode col : ds.get("columns")) if ("ACCOUNT".equals(col.path("classification").asText())) classified++;
            assertEquals(2, classified, ds.get("columns").toString());
            // LA-COLUMN-ROLES-1 P1: every column is written with its role
            assertEquals("entity", stored(ds, "from_account").get("role").asText());
            assertEquals("temporal", stored(ds, "booked_at").get("role").asText());
            assertEquals("measure", stored(ds, "amount").get("role").asText());
            assertEquals("dimension", stored(ds, "channel").get("role").asText());
            assertEquals("detail", stored(ds, "transfer_id").get("role").asText());

            // the rows are queryable as a Dataset
            JsonNode rows = data(json(c, "GET", "/datasets/" + id + "/rows?limit=5", null, null), 200);
            assertTrue(rows.toString().contains("ACC-"), rows.toString());
            assertFalse(Files.exists(Path.of("database", OnboardRoutes.DRAFTS, d.get("draftId").asText())), "the draft is gone");

            // the same name again is a conflict, refused before anything is written
            JsonNode d2 = draft(c, "transfers.csv", "stream", transfers(60), null);
            HttpResponse<String> again = json(c, "POST", "/onboard/files/" + d2.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null);
            assertEquals(409, again.statusCode(), again.body());
        }
    }

    /** LA-COLUMN-ROLES-1 P1: the draft proposes a role per column with its evidence, and a text column's distinct count. */
    @Test
    void theDraftProposesARolePerColumn(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(120), null);
            for (String col : List.of("transfer_id", "from_account", "to_account", "amount", "currency", "channel", "booked_at"))
                assertChoice(roleOf(d, col));
            assertEquals("entity", roleOf(d, "from_account").get("value").asText());
            assertEquals("temporal", roleOf(d, "booked_at").get("value").asText());
            assertEquals("measure", roleOf(d, "amount").get("value").asText());
            assertEquals("dimension", roleOf(d, "channel").get("value").asText(), "the link's kind is a Category");
            JsonNode currency = roleOf(d, "currency");
            assertEquals("dimension", currency.get("value").asText(), "a few repeating values: " + currency);
            assertTrue(currency.get("evidence").asText().contains("distinct values in 120 sampled rows"), currency.toString());
            JsonNode id = roleOf(d, "transfer_id");
            assertEquals("detail", id.get("value").asText(), "every value different: not a Category");
            assertEquals("high", id.get("confidence").asText());
            assertEquals(120, columnRow(d, "transfer_id").get("distinct").asInt());
            assertFalse(columnRow(d, "amount").has("distinct"), "a distinct count only for a text column");
        }
    }

    /** LA-COLUMN-ROLES-1 P1: a stated role is written; a contradiction with the link's primaries or the Entity Type is refused. */
    @Test
    void aCommitWritesStatedRolesAndRefusesAContradiction(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("roles");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(80), null);
            String body = "{\"name\":\"" + id + "\",\"columns\":[{\"name\":\"currency\",\"role\":\"detail\"}],"
                    + "\"link\":{\"from\":\"from_account\",\"to\":\"to_account\",\"kind\":\"transfer_id\",\"primaryTime\":\"booked_at\","
                    + "\"primaryMeasure\":\"amount\"}}";
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", body, null), 200);
            JsonNode ds = data(json(c, "GET", "/components/dataset/" + id, null, null), 200).get("content");
            assertEquals("detail", stored(ds, "currency").get("role").asText(), "the stated role wins over the proposal");
            assertEquals("measure", stored(ds, "amount").get("role").asText());
            assertEquals("dimension", stored(ds, "transfer_id").get("role").asText(),
                    "the column the link's kind names is a Category, though its proposal was a Detail");

            // a body may still say time / amount (the primaries' old names); the Dataset is written with the new ones
            String oldId = uniqueId("oldnames");
            JsonNode d3 = draft(c, "transfers.csv", "stream", transfers(60), null);
            data(json(c, "POST", "/onboard/files/" + d3.get("draftId").asText() + "/commit", "{\"name\":\"" + oldId + "\",\"link\":"
                    + "{\"from\":\"from_account\",\"to\":\"to_account\",\"time\":\"booked_at\",\"amount\":\"amount\"}}", null), 200);
            JsonNode old = data(json(c, "GET", "/components/dataset/" + oldId, null, null), 200).get("content");
            assertEquals("booked_at", old.at("/defaultLinkMapping/primaryTime").asText(), old.toString());
            assertEquals("amount", old.at("/defaultLinkMapping/primaryMeasure").asText(), old.toString());
            assertEquals("booked_at", old.get("eventDate").asText());

            // the primary Time names a column stated as a Measure: refused before anything is written
            JsonNode d2 = draft(c, "transfers.csv", "stream", transfers(60), null);
            String clash = "{\"name\":\"" + uniqueId("clash") + "\",\"columns\":[{\"name\":\"booked_at\",\"role\":\"dimension\"}],"
                    + "\"link\":{\"from\":\"from_account\",\"to\":\"to_account\",\"primaryTime\":\"booked_at\"}}";
            HttpResponse<String> r = json(c, "POST", "/onboard/files/" + d2.get("draftId").asText() + "/commit", clash, null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("primaryTime names 'booked_at', which is not a Time column"), r.body());

            // an Entity role without an Entity Type, and another role on a column that holds one
            String noType = "{\"name\":\"" + uniqueId("nt") + "\",\"columns\":[{\"name\":\"currency\",\"role\":\"entity\"}]}";
            r = json(c, "POST", "/onboard/files/" + d2.get("draftId").asText() + "/commit", noType, null);
            assertEquals(422, r.statusCode(), r.body());
            String typed = "{\"name\":\"" + uniqueId("ty") + "\",\"columns\":[{\"name\":\"from_account\",\"role\":\"measure\"}]}";
            r = json(c, "POST", "/onboard/files/" + d2.get("draftId").asText() + "/commit", typed, null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("holds an Entity Type, so its role is entity"), r.body());

            // a Measure on a text column
            String text = "{\"name\":\"" + uniqueId("tx") + "\",\"columns\":[{\"name\":\"currency\",\"role\":\"measure\"}]}";
            r = json(c, "POST", "/onboard/files/" + d2.get("draftId").asText() + "/commit", text, null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("is a measure but holds string values, not numbers"), r.body());
        }
    }

    private static JsonNode columnRow(JsonNode draft, String column) {
        for (JsonNode c : draft.get("entityTypes").get("columns")) if (column.equals(c.get("column").asText())) return c;
        throw new AssertionError("no column " + column + " in " + draft.get("entityTypes"));
    }

    private static JsonNode roleOf(JsonNode draft, String column) {
        return columnRow(draft, column).get("role");
    }

    private static JsonNode stored(JsonNode dataset, String column) {
        for (JsonNode c : dataset.get("columns")) if (column.equals(c.get("name").asText())) return c;
        throw new AssertionError("no column " + column + " in " + dataset.get("columns"));
    }

    @Test
    void theUserCanOverrideEveryProposalAtCommit(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("payments");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(80), null);
            String body = "{\"name\":\"" + id + "\",\"columns\":[{\"name\":\"currency\",\"drop\":true},"
                    + "{\"name\":\"transfer_id\",\"rename\":\"ref\"},{\"name\":\"to_account\",\"entityType\":\"wallet\"}],"
                    + "\"primaryEntityType\":\"wallet\",\"link\":{\"from\":\"from_account\",\"to\":\"to_account\",\"details\":[\"transfer_id\"]}}";
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", body, null), 200);
            JsonNode ds = data(json(c, "GET", "/components/dataset/" + id, null, null), 200).get("content");
            assertEquals("wallet", ds.get("primaryEntityType").asText());
            assertEquals("[\"ref\"]", ds.get("defaultLinkMapping").get("details").toString());
            assertFalse(ds.get("defaultLinkMapping").has("kind"), "a stated link replaces the proposal whole");
            assertFalse(ds.get("columns").toString().contains("currency"), "dropped");
            assertTrue(ds.get("columns").toString().contains("\"WALLET\""), ds.get("columns").toString());

            // a choice naming nothing real is refused before anything is written
            JsonNode d2 = draft(c, "transfers.csv", "stream", transfers(60), null);
            String bad = "{\"name\":\"" + uniqueId("bad") + "\",\"columns\":[{\"name\":\"to_account\",\"entityType\":\"spaceship\"}]}";
            HttpResponse<String> r = json(c, "POST", "/onboard/files/" + d2.get("draftId").asText() + "/commit", bad, null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(error(r).contains("no Entity Type 'spaceship'"), r.body());
        }
    }

    @Test
    void aReferenceFileNamesItsIdentifyingColumnAndRegistersNoLinks(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("accounts");
            JsonNode d = draft(c, "accounts.csv", "reference", accounts(60), null);
            JsonNode et = d.get("entityTypes");
            assertFalse(et.has("link"), et.toString());
            assertEquals("account_id", et.get("identifying").get("value").asText());
            assertEquals("high", et.get("identifying").get("confidence").asText(), et.toString());
            assertTrue(et.get("identifying").get("evidence").asText().contains("every one of its 60 sampled values is different"));
            JsonNode s = data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            assertEquals(60, s.get("rowsAfter").asLong());
            assertEquals("SKIPPED", s.get("index").get("result").asText(), "a Reference has no link to index");
            JsonNode ds = data(json(c, "GET", "/components/dataset/" + id, null, null), 200).get("content");
            assertEquals("reference", ds.get("kind").asText());
            assertEquals("account_id", ds.get("identifyingColumn").asText());
            assertFalse(ds.has("defaultLinkMapping"));
            assertTrue(Files.readString(root.resolve("imports").resolve(id + "_pipeline.toon")).contains("produces: reference"));
        }
    }

    /**
     * A commit whose run loads nothing undoes every write: no config file, no registered Pipeline, no Dataset, no inbox or data
     * directory - and the file is back in its draft, so the user can change a choice and commit again. Falsified: with the
     * rollback in {@code OnboardImport.commit} removed, the config files and the registration stay (asserted below).
     */
    @Test
    void aFailedCommitLeavesNothingBehind(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("broken");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(60), null);
            String draftId = d.get("draftId").asText();
            // the file loses its rows after the draft was read: the run will load nothing
            Path stored = Path.of("database", OnboardRoutes.DRAFTS, draftId, "transfers.csv");
            Files.writeString(stored, "transfer_id,from_account,to_account,channel,amount,booked_at,currency\n");

            HttpResponse<String> r = json(c, "POST", "/onboard/files/" + draftId + "/commit", "{\"name\":\"" + id + "\"}", null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(error(r).contains("nothing was imported"), r.body());

            assertFalse(Files.exists(root.resolve("imports").resolve(id + "_pipeline.toon")), "no pipeline file");
            assertFalse(Files.exists(root.resolve("imports").resolve(id + "_schema.toon")), "no schema file");
            assertEquals(404, json(c, "GET", "/config/pipeline/" + id, null, null).statusCode(), "not registered");
            assertTrue(c.svc().configFor(id).isEmpty(), "not in the live registry");
            assertFalse(new ComponentStore(root.resolve("registry")).exists("dataset", id), "no Dataset");
            assertFalse(Files.exists(Path.of("database", id)), "no data directory");
            assertFalse(Files.exists(Path.of("database", "inbox", id)), "no inbox");
            assertTrue(Files.isRegularFile(stored), "the file is back in its draft");

            // and the same draft commits once its file is whole again
            Files.writeString(stored, transfers(60));
            data(json(c, "POST", "/onboard/files/" + draftId + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
        }
    }

    /** Above import.inline_max_bytes the commit answers 202 and the run is followed; a failure there still leaves nothing. */
    @Test
    void aLargeFileIsFollowedAt202AndAFailedFollowedRunLeavesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(json(c, "PUT", "/settings/link-analysis/limits", "{\"import.inline_max_bytes\":0}", null), 200);
            String id = uniqueId("followed");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(70), null);
            JsonNode r = data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 202);
            assertEquals("running", r.get("state").asText(), r.toString());
            assertEquals("/onboard/runs/" + r.get("runId").asText(), r.get("poll").asText());
            JsonNode done = follow(c, r.get("runId").asText());
            assertEquals("succeeded", done.get("state").asText(), done.toString());
            assertEquals(70, done.get("summary").get("rowsAfter").asLong());
            assertTrue(new ComponentStore(root.resolve("registry")).exists("dataset", id));

            String bad = uniqueId("followed_bad");
            JsonNode d2 = draft(c, "transfers.csv", "stream", transfers(60), null);
            Files.writeString(Path.of("database", OnboardRoutes.DRAFTS, d2.get("draftId").asText(), "transfers.csv"),
                    "transfer_id,from_account,to_account,channel,amount,booked_at,currency\n");
            JsonNode r2 = data(json(c, "POST", "/onboard/files/" + d2.get("draftId").asText() + "/commit", "{\"name\":\"" + bad + "\"}", null), 202);
            JsonNode failed = follow(c, r2.get("runId").asText());
            assertEquals("failed", failed.get("state").asText(), failed.toString());
            assertTrue(failed.get("message").asText().contains("nothing was imported"), failed.toString());
            assertFalse(Files.exists(root.resolve("imports").resolve(bad + "_pipeline.toon")));
            assertTrue(c.svc().configFor(bad).isEmpty());
            assertFalse(new ComponentStore(root.resolve("registry")).exists("dataset", bad));
            assertFalse(Files.exists(Path.of("database", "inbox", bad)));
        }
    }

    private JsonNode follow(Ctx c, String runId) throws Exception {
        return follow(c, runId, null);
    }

    // ── Add a file, Run now ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void addAFileReplacesOrAppendsAndRunNowReRuns(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("ledger");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(100), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            assertLastImport(registry, id, false, 0, 100, "transfers.csv");

            JsonNode rep = data(send(c, "POST", "/datasets/" + id + "/files?name=october.csv", transfers(60).getBytes(StandardCharsets.UTF_8), null), 200);
            assertLastImport(registry, id, true, 100, 60, "october.csv");
            assertTrue(rep.get("replaced").asBoolean());
            assertEquals(100, rep.get("rowsBefore").asLong(), rep.toString());
            assertEquals(60, rep.get("rowsAfter").asLong());
            assertEquals(-40, rep.get("rowsChanged").asLong(), "a re-import says how many rows changed");

            int versions = registry.versions("dataset", id).size();
            JsonNode app = data(send(c, "POST", "/datasets/" + id + "/files?name=november.csv&mode=append",
                    transfers(30).getBytes(StandardCharsets.UTF_8), null), 200);
            assertEquals(90, app.get("rowsAfter").asLong());
            assertEquals(30, app.get("rowsChanged").asLong());
            assertEquals(versions, registry.versions("dataset", id).size(), "the lastImport stamp archives no version");
            assertLastImport(registry, id, false, 60, 90, "november.csv");

            String appended = String.valueOf(((Map<?, ?>) registry.get("dataset", id).orElseThrow().content().get("lastImport")).get("batch"));
            JsonNode ledger = data(json(c, "GET", "/runs/" + id + "/batches", null, null), 200);
            assertEquals(appended, ledger.get(ledger.size() - 1).get("consignment_id").asText(), "the card matches the ledger's last run by id");
            JsonNode now = data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200);
            assertLastImport(registry, id, false, 60, 90, "november.csv");   // Run now imports no file: the entry is unchanged
            assertEquals(appended, String.valueOf(((Map<?, ?>) registry.get("dataset", id).orElseThrow().content().get("lastImport")).get("batch")));
            assertEquals(90, now.get("rowsAfter").asLong(), now.toString());
            assertEquals(0, now.get("rowsChanged").asLong());
            assertTrue(now.get("followOnInRun").asBoolean());

            // a file of another shape is refused before it lands; the data is untouched
            HttpResponse<String> wrong = send(c, "POST", "/datasets/" + id + "/files?name=narrow.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), null);
            assertEquals(422, wrong.statusCode(), wrong.body());
            assertEquals(90, data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200).get("rowsAfter").asLong());

            // a Dataset no import made is not Add data's to change
            new ComponentStore(root.resolve("registry")).write("dataset", "piped", Map.of("physicalRef", "piped/database"));
            assertEquals(409, json(c, "POST", "/datasets/piped/run", null, null).statusCode());
            assertEquals(404, json(c, "POST", "/datasets/nope/run", null, null).statusCode());
        }
    }

    /**
     * LA-ARRIVAL-LEDGER-1 (V1, V3): a file a foreign process puts in the import inbox is taken by Run now (no upload),
     * and every arrival lands as its own Parquet file named after its source - the arrival ledger relies on both.
     */
    @Test
    void aFileDroppedInTheInboxIsTakenByRunNowAndEachArrivalIsItsOwnParquetFile(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("drop");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(100), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            Path inbox = Path.of("database", "inbox", id);
            Files.writeString(inbox.resolve("foreign_drop.csv"), transfers(20));
            JsonNode now = data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200);
            assertEquals(20, now.get("rowsIn").asLong(), now.toString());
            assertEquals(120, now.get("rowsAfter").asLong(), now.toString());
            assertEquals("[]", listing(inbox), "the dropped file was taken");
            String files = listing(Path.of("database", id, "database"));
            assertTrue(files.contains("foreign_drop_out.parquet"), files);
            assertTrue(files.contains("_transfers_out.parquet"), files);
        }
    }

    /**
     * LA-ARRIVAL-LEDGER-1: GET /datasets/{id}/arrivals lists every file the import took, newest first - uploads with their
     * name, mode and sha256, a foreign drop as one from a system - with the period each covers; a Replace everything marks
     * the older arrivals as no longer kept.
     */
    @Test
    void theArrivalsOfAnImportDatasetAreListedNewestFirstWithTheirPeriod(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("arrivals");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(100), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            // a sidecar line written before senders were recorded (no "sender") still reads as an upload
            Path sidecar = Path.of("database", id, DatasetArrivals.SIDECAR);
            Files.writeString(sidecar, Files.readString(sidecar).replace("\"sender\":\"upload\",", ""));
            byte[] november = transfers(30).getBytes(StandardCharsets.UTF_8);
            data(send(c, "POST", "/datasets/" + id + "/files?name=november.csv&mode=append", november, null), 200);
            Files.writeString(Path.of("database", "inbox", id).resolve("foreign_drop.csv"), transfers(20));
            data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200);

            JsonNode out = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200);
            JsonNode list = out.get("arrivals");
            assertEquals(3, list.size(), out.toString());
            assertEquals("upload", list.get(2).get("sender").asText(), "a legacy line: " + list.get(2));
            assertNotNull(out.get("eventDateColumn").textValue(), "Add data names the When column: " + out);

            JsonNode drop = list.get(0), nov = list.get(1), first = list.get(2);
            assertEquals("system", drop.get("sender").asText(), drop.toString());
            assertTrue(drop.get("file").asText().contains("foreign_drop"), drop.toString());
            assertEquals(20, drop.get("rowsRead").asLong());
            assertEquals("append", drop.get("mode").asText(), "Run now adds what it takes: " + drop);
            // Run now records its own index outcome per file (no Link Analysis here: nothing to build)
            assertEquals("NOT_AVAILABLE", drop.get("index").asText(), drop.toString());
            assertTrue(drop.get("sha256").isNull(), "only an upload's bytes are hashed: " + drop);
            assertEquals("NOT_AVAILABLE", nov.get("index").asText(), nov.toString());

            assertEquals("upload", nov.get("sender").asText(), nov.toString());
            assertEquals("november.csv", nov.get("file").asText());
            assertEquals("append", nov.get("mode").asText());
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(november)),
                    nov.get("sha256").asText(), "the upload's own bytes");
            assertEquals("transfers.csv", first.get("file").asText());
            for (JsonNode a : list) {
                assertTrue(a.get("kept").asBoolean(), a.toString());
                assertTrue(a.get("coversFrom").asText().startsWith("2026-09"), "the period read from its Parquet: " + a);
                assertTrue(a.get("coversTo").asText().compareTo(a.get("coversFrom").asText()) >= 0, a.toString());
            }

            // Replace everything: the newest arrival is kept, every older one is set aside
            data(send(c, "POST", "/datasets/" + id + "/files?name=december.csv", transfers(10).getBytes(StandardCharsets.UTF_8), null), 200);
            JsonNode after = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals");
            assertEquals(4, after.size());
            assertEquals("replace", after.get(0).get("mode").asText());
            assertTrue(after.get(0).get("kept").asBoolean());
            for (int i = 1; i < 4; i++) assertFalse(after.get(i).get("kept").asBoolean(), after.get(i).toString());

            // a foreign process sending the SAME name again: the import Pipeline's duplicate policy skips it (no arrival)
            Files.writeString(Path.of("database", "inbox", id).resolve("foreign_drop.csv"), transfers(5));
            JsonNode resent = data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200);
            assertEquals(0, resent.get("rowsIn").asLong(), resent.toString());
            assertEquals(4, data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals").size());
            // ... while a new name is an arrival of its own - here taken by an upload's run, which records both files
            Files.writeString(Path.of("database", "inbox", id).resolve("second_drop.csv"), transfers(5));
            data(send(c, "POST", "/datasets/" + id + "/files?name=extra.csv&mode=append", transfers(7).getBytes(StandardCharsets.UTF_8), null), 200);
            JsonNode again = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals");
            assertEquals(6, again.size(), again.toString());
            JsonNode drop2 = null, extra = null;
            for (int i = 0; i < 2; i++)
                if ("system".equals(again.get(i).get("sender").asText())) drop2 = again.get(i); else extra = again.get(i);
            assertNotNull(drop2, again.toString());
            assertNotNull(extra, again.toString());
            assertEquals(5, drop2.get("rowsRead").asLong(), drop2.toString());
            assertEquals("NOT_AVAILABLE", drop2.get("index").asText(), drop2.toString());
            assertEquals("extra.csv", extra.get("file").asText(), extra.toString());
            assertEquals(7, extra.get("rowsRead").asLong(), extra.toString());

            // gates: unknown 404; a Dataset no import made answers 200 with no arrivals and why
            assertEquals(404, json(c, "GET", "/datasets/nope/arrivals", null, null).statusCode());
            new ComponentStore(root.resolve("registry")).write("dataset", "piped", Map.of("physicalRef", "piped/database"));
            JsonNode piped = data(json(c, "GET", "/datasets/piped/arrivals", null, null), 200);
            assertEquals(0, piped.get("arrivals").size());
            assertTrue(piped.get("reason").asText().contains("not made with Add data"), piped.toString());

            // a forged entry naming ANOTHER Dataset's import Pipeline reads nothing of it (its import fields are editable)
            new ComponentStore(root.resolve("registry")).write("dataset", "forged",
                    Map.of("physicalRef", id + "/database", "origin", "import", "importPipeline", id, "eventDate", "booked_at"));
            JsonNode forged = data(json(c, "GET", "/datasets/forged/arrivals", null, null), 200);
            assertEquals(0, forged.get("arrivals").size(), forged.toString());
            assertTrue(forged.get("eventDateColumn").isTextual());
        }
    }

    /** Each line's comma-separated fields with {@code a} and {@code b} swapped (header too). */
    private static String swapColumns(String csv, int a, int b) {
        StringBuilder sb = new StringBuilder();
        for (String line : csv.split("\n")) {
            String[] f = line.split(",", -1);
            String t = f[a];
            f[a] = f[b];
            f[b] = t;
            sb.append(String.join(",", f)).append('\n');
        }
        return sb.toString();
    }

    /** Each line with field {@code drop} removed, or {@code suffix} appended when {@code drop} is negative. */
    private static String editColumns(String csv, int drop, String headerSuffix, String valueSuffix) {
        StringBuilder sb = new StringBuilder();
        boolean header = true;
        for (String line : csv.split("\n")) {
            List<String> f = new java.util.ArrayList<>(List.of(line.split(",", -1)));
            if (drop >= 0) f.remove(drop);
            else f.add(header ? headerSuffix : valueSuffix);
            header = false;
            sb.append(String.join(",", f)).append('\n');
        }
        return sb.toString();
    }

    /**
     * LA-ARRIVAL-MODES-1 (plan §4 Modes, D-2): <i>Replace the period it covers</i> adds the file's rows and removes, from the
     * older data, exactly the rows on the days the file covers; every other day stays. Refused without a When column and
     * on a Reference; the originals of rewritten files are discarded once the import succeeded.
     */
    @Test
    void aFileReplacesExactlyTheDaysItCovers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("period");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(100), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            // transfers(n) puts row i on 2026-09-(1 + i % 28): a 7-row correction covers 2 - 8 September
            long onThoseDays = 0;
            for (int i = 1; i <= 100; i++) if (1 + i % 28 >= 2 && 1 + i % 28 <= 8) onThoseDays++;
            JsonNode s = data(send(c, "POST", "/datasets/" + id + "/files?name=week.csv&mode=period",
                    transfers(7).replace(",EUR\n", ",USD\n").getBytes(StandardCharsets.UTF_8), null), 200);
            assertEquals(100 - onThoseDays + 7, s.get("rowsAfter").asLong(), s.toString());
            JsonNode p = s.get("period");
            assertEquals(7, p.get("days").asInt(), p.toString());
            assertEquals("2026-09-02", p.get("from").asText());
            assertEquals("2026-09-08", p.get("to").asText());
            assertEquals(onThoseDays, p.get("rowsReplaced").asLong(), p.toString());
            assertFalse(s.get("replaced").asBoolean());

            JsonNode a = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals");
            assertEquals("period", a.get(0).get("mode").asText(), a.get(0).toString());
            assertTrue(a.get(1).get("kept").asBoolean(), "the older file keeps its other days: " + a.get(1));
            assertEquals("period", ((Map<?, ?>) new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow()
                    .content().get("lastImport")).get("mode"));
            try (var w = Files.list(Path.of("database", id))) {
                assertTrue(w.noneMatch(x -> x.getFileName().toString().startsWith("database.period-")), "the originals are discarded");
            }
            // the same correction again with other bytes is idempotent: the same days, the same rows
            JsonNode again = data(send(c, "POST", "/datasets/" + id + "/files?name=week2.csv&mode=period",
                    transfers(7).replace(",EUR\n", ",GBP\n").getBytes(StandardCharsets.UTF_8), null), 200);
            assertEquals(s.get("rowsAfter").asLong(), again.get("rowsAfter").asLong(), again.toString());
            assertEquals(7, again.at("/period/rowsReplaced").asLong(), again.toString());

            // a period step that fails after its run (the When column made a text one, so its days cannot be read) is undone:
            // the upload's own rows go, while a drop the same run took keeps its rows (it is already marked processed)
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            long held = again.get("rowsAfter").asLong();
            Map<String, Object> text = new java.util.LinkedHashMap<>(registry.get("dataset", id).orElseThrow().content());
            text.put("eventDate", "channel");
            registry.write("dataset", id, text);
            Files.writeString(Path.of("database", "inbox", id).resolve("riding_drop.csv"), transfers(4));
            HttpResponse<String> failed = send(c, "POST", "/datasets/" + id + "/files?name=bad_week.csv&mode=period",
                    transfers(6).replace(",EUR\n", ",CHF\n").getBytes(StandardCharsets.UTF_8), null);
            assertNotEquals(200, failed.statusCode(), failed.body());
            assertEquals(held + 4, data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200).get("rowsAfter").asLong(),
                    "the failed upload's rows are gone, the drop's stay, no older day was lost");

            // refused: a Dataset with no When column, and an unknown mode
            Map<String, Object> entry = new java.util.LinkedHashMap<>(registry.get("dataset", id).orElseThrow().content());
            entry.remove("eventDate");
            registry.write("dataset", id, entry);
            HttpResponse<String> noWhen = send(c, "POST", "/datasets/" + id + "/files?name=w3.csv&mode=period",
                    transfers(3).getBytes(StandardCharsets.UTF_8), null);
            assertEquals(422, noWhen.statusCode(), noWhen.body());
            assertTrue(noWhen.body().contains("no When column"), noWhen.body());
            assertEquals(422, send(c, "POST", "/datasets/" + id + "/files?name=w3.csv&mode=upsert",
                    transfers(3).getBytes(StandardCharsets.UTF_8), null).statusCode());
        }
    }

    /**
     * LA-ARRIVAL-MODES-1 (D-3): the same bytes added again (append or period) are refused with the first import's date,
     * until a Replace everything set those rows aside; Replace everything itself may always reload a file.
     */
    @Test
    void theSameFileAddedAgainIsRefusedUntilEverythingIsReplaced(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("dup");
            byte[] first = transfers(100).getBytes(StandardCharsets.UTF_8);
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(100), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            HttpResponse<String> dup = send(c, "POST", "/datasets/" + id + "/files?name=copy.csv&mode=append", first, null);
            assertEquals(409, dup.statusCode(), dup.body());
            assertTrue(dup.body().contains("already imported on 20") && dup.body().contains("as 'transfers.csv'")
                    && dup.body().contains("replace everything"), dup.body());
            assertEquals(100, data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200).get("rowsAfter").asLong(),
                    "nothing landed");

            byte[] oct = transfers(30).getBytes(StandardCharsets.UTF_8);
            data(send(c, "POST", "/datasets/" + id + "/files?name=october.csv&mode=append", oct, null), 200);
            assertEquals(409, send(c, "POST", "/datasets/" + id + "/files?name=october.csv&mode=append", oct, null).statusCode());
            assertEquals(409, send(c, "POST", "/datasets/" + id + "/files?name=october.csv&mode=period", oct, null).statusCode());

            byte[] nov = transfers(10).getBytes(StandardCharsets.UTF_8);
            data(send(c, "POST", "/datasets/" + id + "/files?name=november.csv", nov, null), 200);   // Replace everything
            assertEquals(40, data(send(c, "POST", "/datasets/" + id + "/files?name=october.csv&mode=append", oct, null), 200)
                    .get("rowsAfter").asLong(), "October's rows were set aside by the replace, so it may come back");
            assertEquals(10, data(send(c, "POST", "/datasets/" + id + "/files?name=november.csv", nov, null), 200)
                    .get("rowsAfter").asLong(), "Replace everything may always reload a file");
        }
    }

    /**
     * LA-ARRIVAL-MODES-1, the schema-drift policy (operator 2026-11-05, D-6): a later file is judged by its column NAMES
     * before anything lands. A reordered header no longer loads into the wrong columns - it is refused naming each moved
     * column; a missing column is refused by name; a new column is accepted, not kept, and named in the result and the
     * arrival. Run now judges a dropped file the same way and sets a refused one aside with the reason.
     */
    @Test
    void aLaterFileIsJudgedByItsColumnNames(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("drift");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(50), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);

            // channel (column 4) and amount (column 5) swapped: by position it would load amounts as channels
            HttpResponse<String> moved = send(c, "POST", "/datasets/" + id + "/files?name=swapped.csv&mode=append",
                    swapColumns(transfers(20), 3, 4).getBytes(StandardCharsets.UTF_8), null);
            assertEquals(422, moved.statusCode(), moved.body());
            assertTrue(moved.body().contains("'channel' is column 5 here and column 4 in '" + id + "'"), moved.body());
            assertTrue(moved.body().contains("'amount' is column 4 here and column 5"), moved.body());

            HttpResponse<String> missing = send(c, "POST", "/datasets/" + id + "/files?name=short.csv&mode=append",
                    editColumns(transfers(20), 6, null, null).getBytes(StandardCharsets.UTF_8), null);
            assertEquals(422, missing.statusCode(), missing.body());
            assertTrue(missing.body().contains("'currency' is missing"), missing.body());
            assertEquals(50, data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200).get("rowsAfter").asLong(),
                    "nothing of a refused file landed");

            JsonNode wider = data(send(c, "POST", "/datasets/" + id + "/files?name=wider.csv&mode=append",
                    editColumns(transfers(20), -1, "fee", "1.5").getBytes(StandardCharsets.UTF_8), null), 200);
            assertEquals(70, wider.get("rowsAfter").asLong(), wider.toString());
            assertEquals("[\"fee\"]", wider.get("newColumns").toString(), wider.toString());
            JsonNode list = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals");
            assertEquals("[\"fee\"]", list.get(0).get("newColumns").toString(), list.get(0).toString());

            // Run now: a dropped reordered file is refused and set aside, with its reason, and recorded as an arrival
            Path inbox = Path.of("database", "inbox", id);
            Files.writeString(inbox.resolve("swapped_drop.csv"), swapColumns(transfers(9), 3, 4));
            JsonNode now = data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200);
            assertEquals(70, now.get("rowsAfter").asLong(), now.toString());
            assertEquals("swapped_drop.csv", now.at("/refused/0/file").asText(), now.toString());
            assertTrue(now.at("/refused/0/reason").asText().contains("'channel' is column 5 here"), now.toString());
            assertEquals("[]", listing(inbox), "the refused file left the inbox");
            assertTrue(Files.isRegularFile(Path.of("database", id, "refused", "swapped_drop.csv")), "set aside, not deleted");
            JsonNode after = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals");
            assertEquals("REFUSED", after.get(0).get("status").asText(), after.get(0).toString());
            assertEquals("swapped_drop.csv", after.get(0).get("file").asText());
            assertTrue(after.get(0).get("reason").asText().contains("'amount' is column 4 here"), after.get(0).toString());
            assertEquals(3, after.size(), "the commit, wider.csv and the refused drop: " + after);

            // a header that needs cleaning up ("Transfer ID" is read as Transfer_ID): the new column is still left out
            String spaced = uniqueId("spaced");
            String named = transfers(30).replaceFirst("transfer_id", "Transfer ID");
            JsonNode d2 = draft(c, "spaced.csv", "stream", named, null);
            data(json(c, "POST", "/onboard/files/" + d2.get("draftId").asText() + "/commit", "{\"name\":\"" + spaced + "\"}", null), 200);
            JsonNode w2 = data(send(c, "POST", "/datasets/" + spaced + "/files?name=wider.csv&mode=append",
                    editColumns(transfers(10).replaceFirst("transfer_id", "Transfer ID"), -1, "fee", "1.5")
                            .getBytes(StandardCharsets.UTF_8), null), 200);
            assertEquals(40, w2.get("rowsAfter").asLong(), w2.toString());
            assertEquals("[\"fee\"]", w2.get("newColumns").toString(), w2.toString());
            assertEquals(0, w2.get("rowsRejected").asLong(), w2.toString());
        }
    }

    /**
     * LA-DATA-PAGE-1 (plan §8): a Mapping is a Dataset entry over an Add data Dataset's own data, with its own link mapping.
     * Its base must be an import Dataset of this Space and its physicalRef exactly the base's; it takes no files; the base
     * cannot be deleted while a Mapping reads it.
     */
    @Test
    void aMappingReadsItsBasesDataItsOwnWayTakesNoFilesAndHoldsItsBase(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("base");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(40), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            String cols = "\"columns\":[{\"name\":\"from_account\",\"type\":\"string\"},{\"name\":\"to_account\",\"type\":\"string\"},"
                    + "{\"name\":\"channel\",\"type\":\"string\"},{\"name\":\"booked_at\",\"type\":\"timestamp\"}]";
            String mapping = "{\"id\":\"by_channel\",\"origin\":\"mapping\",\"basedOn\":\"" + id + "\",\"physicalRef\":\"" + id
                    + "/database\"," + cols + ",\"defaultLinkMapping\":{\"from\":\"from_account\",\"to\":\"channel\",\"time\":\"booked_at\"},"
                    + "\"eventDate\":\"booked_at\"}";
            HttpResponse<String> made = json(c, "POST", "/components/dataset", mapping, null);
            assertTrue(made.statusCode() == 200 || made.statusCode() == 201, made.body());

            // its base must be ITS data: another physicalRef, no basedOn, itself, or a Dataset no import made are refused
            assertEquals(422, json(c, "POST", "/components/dataset", mapping.replace("\"by_channel\"", "\"m2\"")
                    .replace(id + "/database", "elsewhere/database"), null).statusCode());
            assertEquals(422, json(c, "POST", "/components/dataset", mapping.replace("\"by_channel\"", "\"m3\"")
                    .replace("\"basedOn\":\"" + id + "\",", ""), null).statusCode());
            // LA-PIPELINE-MAPPINGS-1 (Q-1): a Dataset a Pipeline keeps up to date (no origin) is a base like an import...
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            registry.write("dataset", "piped", Map.of("physicalRef", id + "/database"));
            HttpResponse<String> piped = json(c, "POST", "/components/dataset", mapping.replace("\"by_channel\"", "\"m4\"")
                    .replace("\"basedOn\":\"" + id + "\"", "\"basedOn\":\"piped\""), null);
            assertTrue(piped.statusCode() == 200 || piped.statusCode() == 201, piped.body());
            // ... but not one computed at read time, one with no stored data, or a Mapping
            registry.write("dataset", "computed", Map.of("physicalRef", id + "/database", "sql", "SELECT 1"));
            HttpResponse<String> computed = json(c, "POST", "/components/dataset", mapping.replace("\"by_channel\"", "\"m9\"")
                    .replace("\"basedOn\":\"" + id + "\"", "\"basedOn\":\"computed\""), null);
            assertEquals(422, computed.statusCode(), computed.body());
            assertTrue(computed.body().contains("is computed (sql)"), computed.body());
            registry.write("dataset", "pointerless", Map.of("description", "no stored data"));
            assertEquals(422, json(c, "POST", "/components/dataset", mapping.replace("\"by_channel\"", "\"m10\"")
                    .replace("\"basedOn\":\"" + id + "\"", "\"basedOn\":\"pointerless\""), null).statusCode());
            HttpResponse<String> ofMapping = json(c, "POST", "/components/dataset", mapping.replace("\"by_channel\"", "\"m11\"")
                    .replace("\"basedOn\":\"" + id + "\"", "\"basedOn\":\"by_channel\""), null);
            assertEquals(422, ofMapping.statusCode(), ofMapping.body());
            assertTrue(ofMapping.body().contains("is itself a Mapping"), ofMapping.body());
            assertEquals(422, json(c, "POST", "/components/dataset", "{\"id\":\"m5\",\"physicalRef\":\"x\",\"basedOn\":\"" + id + "\"}",
                    null).statusCode(), "basedOn is only for a Mapping");

            // nothing that reads other data or columns the base does not have; an unknown base answers like a hidden one
            assertEquals(422, json(c, "POST", "/components/dataset", mapping.replace("\"by_channel\"", "\"m6\"")
                    .replace("\"eventDate\"", "\"sql\":\"SELECT 1\",\"eventDate\""), null).statusCode());
            assertEquals(422, json(c, "POST", "/components/dataset", mapping.replace("\"by_channel\"", "\"m7\"")
                    .replace("{\"name\":\"booked_at\"", "{\"name\":\"secret_col\",\"type\":\"string\"},{\"name\":\"booked_at\""), null).statusCode(),
                    "a column the base does not have");
            assertEquals(404, json(c, "POST", "/components/dataset", mapping.replace("\"by_channel\"", "\"m8\"")
                    .replace("\"basedOn\":\"" + id + "\"", "\"basedOn\":\"nope\""), null).statusCode());

            // a Mapping takes no files, nor Run now: they go to its base
            HttpResponse<String> files = send(c, "POST", "/datasets/by_channel/files?name=more.csv", transfers(5).getBytes(StandardCharsets.UTF_8), null);
            assertEquals(409, files.statusCode(), files.body());
            assertTrue(files.body().contains("add files to '" + id + "'"), files.body());
            HttpResponse<String> run = json(c, "POST", "/datasets/by_channel/run", null, null);
            assertEquals(409, run.statusCode(), run.body());
            assertTrue(run.body().contains("add files to '" + id + "'"), run.body());

            // the base is held while the Mapping reads it; deleting the Mapping releases it
            HttpResponse<String> held = json(c, "DELETE", "/components/dataset/" + id, null, null);
            assertEquals(409, held.statusCode(), held.body());
            assertTrue(held.body().contains("by_channel"), held.body());
            data(json(c, "DELETE", "/components/dataset/by_channel", null, null), 200);
            assertEquals(409, json(c, "DELETE", "/components/dataset/piped", null, null).statusCode(), "m4 reads piped");
            data(json(c, "DELETE", "/components/dataset/m4", null, null), 200);
            data(json(c, "DELETE", "/components/dataset/piped", null, null), 200);
            data(json(c, "DELETE", "/components/dataset/" + id, null, null), 200);
        }
    }

    /** LA-DATA-PAGE-1: a Mapping of a base its author cannot see is 404 - exactly as a Mapping of a base that does not exist. */
    @Test
    void aMappingOfAHiddenBaseAnswersAsIfTheBaseWereAbsent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "private_base", Map.of("physicalRef", "private_base/database",
                    "origin", "import", "importPipeline", "private_base", "owner", "uploader-1", "shares", List.of()));
            String body = "{\"id\":\"%s\",\"origin\":\"mapping\",\"basedOn\":\"%s\",\"physicalRef\":\"private_base/database\","
                    + "\"defaultLinkMapping\":{\"from\":\"a\",\"to\":\"b\"}}";
            HttpResponse<String> hidden = json(c, "POST", "/components/dataset", body.formatted("peek", "private_base"), AUTHOR);
            HttpResponse<String> absent = json(c, "POST", "/components/dataset", body.formatted("peek2", "no_such_base"), AUTHOR);
            assertEquals(404, hidden.statusCode(), hidden.body());
            assertEquals(404, absent.statusCode(), absent.body());
            assertFalse(new ComponentStore(root.resolve("registry")).get("dataset", "peek").isPresent(), "nothing was written");
        }
    }

    /**
     * LA-PIPELINE-MAPPINGS-1 (Q-2): a Mapping never widens who reads its base. An unrestricted Mapping of a base shared away
     * from the caller reads as absent - by id and in the list - and appears once the base is shared with them.
     */
    @Test
    void aMappingIsReadOnlyByThoseWhoMayReadItsBase(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            registry.write("dataset", "private_base", Map.of("physicalRef", "private_base/database", "owner", "uploader-1",
                    "shares", List.of()));
            // written as its base's owner would, with no shares of its own: unrestricted by itself
            registry.write("dataset", "wide_view", Map.of("origin", "mapping", "basedOn", "private_base",
                    "physicalRef", "private_base/database", "owner", "uploader-1",
                    "defaultLinkMapping", Map.of("from", "a", "to", "b")));
            assertEquals(404, json(c, "GET", "/components/dataset/wide_view", null, AUTHOR).statusCode());
            assertFalse(json(c, "GET", "/components/dataset", null, AUTHOR).body().contains("wide_view"));
            assertEquals(200, json(c, "GET", "/components/dataset/wide_view", null, UPLOADER).statusCode(), "the base's owner reads it");

            // the positive twin: shared the base, the same caller reads the same Mapping
            registry.write("dataset", "private_base", Map.of("physicalRef", "private_base/database", "owner", "uploader-1",
                    "shares", List.of(Map.of("subjectType", "user", "subjectId", "author-1", "access", "view"))));
            assertEquals(200, json(c, "GET", "/components/dataset/wide_view", null, AUTHOR).statusCode());
            assertTrue(json(c, "GET", "/components/dataset", null, AUTHOR).body().contains("wide_view"));

            // a Mapping whose base is gone reads as absent too (fail-closed), never as a Dataset of its own
            registry.delete("dataset", "private_base");
            assertEquals(404, json(c, "GET", "/components/dataset/wide_view", null, AUTHOR).statusCode());
            // ... yet it can still be removed: deleting reads no rows, so its own envelope decides
            HttpResponse<String> removed = json(c, "DELETE", "/components/dataset/wide_view", null, AUTHOR);
            assertEquals(200, removed.statusCode(), removed.body());
            assertFalse(registry.get("dataset", "wide_view").isPresent());
        }
    }

    private static String listing(Path dir) throws Exception {
        if (!Files.exists(dir)) return "(none)";
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList().toString();
        }
    }

    /** LA-ONBOARD-RESIDUALS-1 (7): the registry entry keeps the last import's replace-or-append and row counts. */
    private static void assertLastImport(ComponentStore registry, String id, boolean replaced, long before, long after, String file) {
        Map<?, ?> last = (Map<?, ?>) registry.get("dataset", id).orElseThrow().content().get("lastImport");
        assertNotNull(last, "no lastImport on " + id);
        assertEquals(replaced, last.get("replaced"), last.toString());
        assertEquals(before, ((Number) last.get("rowsBefore")).longValue(), last.toString());
        assertEquals(after, ((Number) last.get("rowsAfter")).longValue(), last.toString());
        assertEquals(file, last.get("file"));
        assertNotNull(java.time.Instant.parse(String.valueOf(last.get("at"))));
        assertFalse(String.valueOf(last.get("batch")).isBlank(), "the run's ledger batch");
    }

    /** A file named .txt / .tab / upper-case lands under the import's own extension, so its file_pattern picks it up. */
    @Test
    void tsvAndTxtFilesCommitUnderTheImportsOwnExtension(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String tsvId = uniqueId("tabbed");
            String tsv = transfers(60).replace(',', '\t');
            JsonNode d = draft(c, "LEDGER.TAB", "stream", tsv, null);
            assertEquals("tsv", d.get("format").get("type").get("value").asText());
            assertEquals(60, data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + tsvId + "\"}", null), 200)
                    .get("rowsAfter").asLong());
            assertTrue(Files.readString(root.resolve("imports").resolve(tsvId + "_pipeline.toon")).contains("*.tsv"));

            String txtId = uniqueId("texted");
            JsonNode t = draft(c, "export.txt", "stream", transfers(60), null);
            assertEquals(60, data(json(c, "POST", "/onboard/files/" + t.get("draftId").asText() + "/commit", "{\"name\":\"" + txtId + "\"}", null), 200)
                    .get("rowsAfter").asLong());
            // Add a file: another .TXT of the same shape lands too; a TSV is not a CSV import's file
            assertEquals(30, data(send(c, "POST", "/datasets/" + txtId + "/files?name=MORE.TXT&mode=append",
                    transfers(30).getBytes(StandardCharsets.UTF_8), null), 200).get("rowsChanged").asLong());
            HttpResponse<String> wrong = send(c, "POST", "/datasets/" + txtId + "/files?name=x.tsv", tsv.getBytes(StandardCharsets.UTF_8), null);
            assertEquals(422, wrong.statusCode(), wrong.body());
        }
    }

    /**
     * Add a file and Run now act only on the Pipeline Add data wrote for THAT Dataset: a registry entry edited (through the
     * generic component route) to name another Pipeline is refused, so it can neither run nor set aside that Pipeline's data.
     * Falsified: with the {@code importPipeline == id} and config-path check removed from {@code importDataset}, the forged
     * entry's Run now answers 200.
     */
    @Test
    void aForgedImportEntryCannotRunOrReplaceAnotherPipeline(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String victim = uniqueId("victim");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(60), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + victim + "\"}", null), 200);
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            store.write("dataset", "forged", Map.of("physicalRef", victim + "/database", "origin", "import", "importPipeline", victim,
                    "importFormat", "csv", "kind", "stream"));
            assertEquals(409, json(c, "POST", "/datasets/forged/run", null, null).statusCode());
            assertEquals(409, send(c, "POST", "/datasets/forged/files?name=t.csv", transfers(5).getBytes(StandardCharsets.UTF_8), null).statusCode());
            // naming the mini Pipeline (not an import at all) is refused the same way
            store.write("dataset", "mini_etl", Map.of("physicalRef", "x", "origin", "import", "importPipeline", "mini_etl"));
            assertEquals(409, json(c, "POST", "/datasets/mini_etl/run", null, null).statusCode());
            assertEquals(60, data(json(c, "POST", "/datasets/" + victim + "/run", null, null), 200).get("rowsAfter").asLong(), "untouched");
            assertEquals(422, json(c, "POST", "/onboard/files/" + draft(c, "t.csv", "stream", transfers(5), null).get("draftId").asText()
                    + "/commit", "{\"name\":\"" + uniqueId("typed") + "\",\"primaryEntityType\":7}", null).statusCode(), "never a 500");
        }
    }

    /**
     * LA-ONBOARD-ORPHAN-PIPELINE-1: deleting an Add data Dataset retires its import Pipeline - unregistered, its config
     * files gone, the data kept - so an entry re-created under the same id has nothing to drive: Run now and Add a file
     * answer 409. An entry naming another Dataset's Pipeline retires nothing, and under an approval policy over Pipelines
     * the delete is refused rather than written around it. Falsified: with the retirement removed from the delete, the
     * re-created entry's Run now answers 200.
     */
    @Test
    void deletingAnImportDatasetRetiresItsPipeline(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("retired");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(60), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            String keep = uniqueId("kept");
            JsonNode d2 = draft(c, "transfers.csv", "stream", transfers(30), null);
            data(json(c, "POST", "/onboard/files/" + d2.get("draftId").asText() + "/commit", "{\"name\":\"" + keep + "\"}", null), 200);
            ComponentStore store = new ComponentStore(root.resolve("registry"));

            // an entry that names another Dataset's Pipeline retires nothing when it goes
            store.write("dataset", "pointer", Map.of("physicalRef", keep + "/database", "origin", "import", "importPipeline", keep));
            assertFalse(data(json(c, "DELETE", "/components/dataset/pointer", null, null), 200).has("retiredPipeline"));
            assertTrue(c.svc().configFor(keep).isPresent(), "another Dataset's Pipeline is untouched");

            // under an approval policy over Pipelines, retiring would write around it: the delete is refused
            Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  pipeline:\n    required: true\n");
            HttpResponse<String> held = json(c, "DELETE", "/components/dataset/" + id, null, null);
            assertEquals(409, held.statusCode(), held.body());
            assertTrue(store.exists("dataset", id) && c.svc().configFor(id).isPresent(), "nothing changed");
            Files.delete(root.resolve(ApprovalPolicy.FILE));

            JsonNode del = data(json(c, "DELETE", "/components/dataset/" + id, null, null), 200);
            assertEquals(id, del.get("retiredPipeline").asText(), del.toString());
            assertTrue(c.svc().configFor(id).isEmpty(), "unregistered");
            assertFalse(Files.exists(root.resolve("imports").resolve(id + "_pipeline.toon")));
            assertFalse(Files.exists(root.resolve("imports").resolve(id + "_schema.toon")));
            assertTrue(Files.isDirectory(Path.of("database", id, "database")), "the data is kept");

            // re-created under the same id with the import's own markers: nothing to drive
            store.write("dataset", id, Map.of("physicalRef", id + "/database", "origin", "import", "importPipeline", id,
                    "importFormat", "csv", "kind", "stream"));
            assertEquals(409, json(c, "POST", "/datasets/" + id + "/run", null, null).statusCode());
            assertEquals(409, send(c, "POST", "/datasets/" + id + "/files?name=t.csv", transfers(5).getBytes(StandardCharsets.UTF_8), null).statusCode());
            assertEquals(30, data(json(c, "POST", "/datasets/" + keep + "/run", null, null), 200).get("rowsAfter").asLong());
        }
    }

    /**
     * LA-ONBOARD-RETIRE-GAPS-1 (2): retiring runs BEFORE the registry entry is deleted and is idempotent, so a retire that
     * fails (here: the schema file is a non-empty directory) answers an error and leaves the entry in place; the same DELETE,
     * retried once the cause is gone, finishes. Falsified: with the entry deleted first, the retry answers 404 and the
     * Pipeline file stays registered with no entry to retire it from.
     */
    @Test
    void aRetireThatFailsLeavesTheEntryAndTheDeleteCanBeRetried(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("halfretired");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(10), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            Path schema = root.resolve("imports").resolve(id + "_schema.toon");
            Path pipeline = root.resolve("imports").resolve(id + "_pipeline.toon");
            Path blocked = root.resolve("imports").resolve(id + "_schema.toon.parked");
            Files.move(schema, blocked);
            Files.createDirectories(schema.resolve("inner"));   // deleteIfExists on a non-empty directory throws

            assertTrue(json(c, "DELETE", "/components/dataset/" + id, null, null).statusCode() >= 500);
            assertTrue(store.exists("dataset", id), "the entry is kept, so the delete can be retried");
            assertTrue(Files.exists(pipeline), "the Pipeline file is the marker a retry finds it by");

            Files.delete(schema.resolve("inner"));
            Files.delete(schema);
            assertEquals(id, data(json(c, "DELETE", "/components/dataset/" + id, null, null), 200).get("retiredPipeline").asText());
            assertFalse(store.exists("dataset", id));
            assertTrue(c.svc().configFor(id).isEmpty());
            assertFalse(Files.exists(pipeline));
        }
    }

    /**
     * LA-ONBOARD-RETIRE-GAPS-1 (3): a Dataset delete held by an approval policy over Datasets is applied by another person's
     * approval and that replay retires the import Pipeline too (a policy over Datasets alone does not refuse; one over
     * Pipelines or schemas does). Held, nothing is retired.
     */
    @Test
    void anApprovedReplayOfAHeldDatasetDeleteRetiresThePipelineToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("replayed");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(10), null);
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  dataset:\n    required: true\n");
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(Map.of("users", List.of("checker-1"))));
            Authenticators.forTest(ex -> {   // OIDC-shaped: one seeded role each, recorded as held (apply re-checks it)
                String[] who = switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
                    case AUTHOR -> new String[] {"author-1", "pipeline-developer"};
                    case "Bearer checker" -> new String[] {"checker-1", "admin"};
                    default -> null;
                };
                if (who == null) return Optional.empty();
                Roles.Def def = Roles.effective(ex).get(who[1]);
                ComponentAccess.heldRoles(ex, Set.of(who[1]));
                return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
            });
            String pc = data(json(c, "DELETE", "/components/dataset/" + id, null, AUTHOR), 202).at("/pendingChange/id").asText();
            assertTrue(store.exists("dataset", id) && c.svc().configFor(id).isPresent(), "held: nothing deleted, nothing retired");
            assertTrue(data(json(c, "POST", "/pending-changes/" + pc + "/approve", "{}", "Bearer checker"), 200).get("applied").asBoolean());
            assertFalse(store.exists("dataset", id));
            assertTrue(c.svc().configFor(id).isEmpty(), "the replay retired the Pipeline");
            assertFalse(Files.exists(root.resolve("imports").resolve(id + "_pipeline.toon")));
            assertFalse(Files.exists(root.resolve("imports").resolve(id + "_schema.toon")));
        }
    }

    /**
     * LA-ONBOARD-RESIDUALS-1 item 4: under an approval policy over any kind the commit writes (Dataset, Pipeline, schema)
     * Add data cannot turn its three writes into one Pending Change, so it refuses (409) before writing anything, and the
     * draft stays for a retry. Falsified: with the {@code holdRefusing} call removed from the commit, it answers 200.
     */
    @Test
    void aCommitUnderAnApprovalPolicyIsRefusedBeforeAnythingIsWritten(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = uniqueId("governed");
            JsonNode d = draft(c, "transfers.csv", "stream", transfers(60), null);
            for (String kind : List.of("dataset", "schema")) {
                Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  " + kind + ":\n    required: true\n");
                HttpResponse<String> r = json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null);
                assertEquals(409, r.statusCode(), r.body());
                assertTrue(error(r).contains("approval policy holds changes to '" + kind + "'"), r.body());
                assertFalse(Files.exists(root.resolve("imports")), "no config was written");
                assertFalse(new ComponentStore(root.resolve("registry")).exists("dataset", id));
                assertTrue(c.svc().configFor(id).isEmpty());
                assertFalse(Files.exists(Path.of("database", "inbox", id)));
            }
            Files.delete(root.resolve(ApprovalPolicy.FILE));
            assertEquals(60, data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200)
                    .get("rowsAfter").asLong(), "the draft stayed, so the same commit succeeds once the policy is lifted");
        }
    }

    /** ENTITY-TYPE-CATALOG-1 P2: a Dataset says what one row is (a record type of the Space's packs); Add data proposes it. */
    @Test
    void aDatasetSaysWhatARowIsAndAddDataProposesIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            registry.write("dataset", "calls", Map.of("physicalRef", "calls/database", "columns", List.of(Map.of("name", "a_party"))));
            // no pack in force: no record type to say
            HttpResponse<String> none = json(c, "PUT", "/datasets/calls/record-type", "{\"recordType\":\"voice_call\"}", null);
            assertEquals(422, none.statusCode(), none.body());

            Files.writeString(root.resolve(com.gamma.entitystore.LinkAnalysisSettings.FILE), "domain_profile: telecom\n");
            JsonNode r = data(json(c, "PUT", "/datasets/calls/record-type", "{\"recordType\":\"voice_call\"}", null), 200);
            assertEquals("voice_call", r.get("recordType").asText());
            assertEquals("voice_call", data(json(c, "GET", "/components/dataset/calls", null, null), 200).at("/content/recordType").asText());
            assertEquals(422, json(c, "PUT", "/datasets/calls/record-type", "{\"recordType\":\"transfer\"}", null).statusCode(),
                    "a record type of a pack the Space does not read");
            assertEquals(422, json(c, "PUT", "/datasets/calls/record-type", "{}", null).statusCode());
            assertEquals(422, json(c, "PUT", "/datasets/calls/record-type", "{\"recordType\":7}", null).statusCode());
            assertEquals(404, json(c, "PUT", "/datasets/none/record-type", "{\"recordType\":null}", null).statusCode());
            data(json(c, "PUT", "/datasets/calls/record-type", "{\"recordType\":null}", null), 200);
            assertFalse(data(json(c, "GET", "/components/dataset/calls", null, null), 200).get("content").has("recordType"));

            // the draft proposes the record type whose likely types the columns hold: numbers, handsets and the serving cell -> a call
            StringBuilder sb = new StringBuilder("caller,callee,device_serial,cell_id,duration\n");
            for (int i = 0; i < 60; i++) {
                String body = String.format("35%012d", 200000 + i);
                sb.append("+3361234").append(String.format("%04d", i)).append(",+3361299").append(String.format("%04d", i)).append(',')
                        .append(body).append(luhnDigit(body)).append(",SITE-").append(i % 7).append(',').append(i).append('\n');
            }
            JsonNode d = draft(c, "calls.csv", "stream", sb.toString(), null);
            JsonNode rt = d.get("entityTypes").get("recordType");
            assertEquals("voice_call", rt.get("value").asText(), rt.toString());
            assertEquals("high", rt.get("confidence").asText(), "three of its four likely types are held; a data session covers fewer of its six");
            assertTrue(rt.get("evidence").asText().contains("voice call record"), rt.toString());
            String draftId = d.get("draftId").asText();
            assertEquals(422, json(c, "POST", "/onboard/files/" + draftId + "/commit", "{\"name\":\"rt_bad\",\"recordType\":\"nope\"}", null)
                    .statusCode());
            String id = uniqueId("rt_calls");
            data(json(c, "POST", "/onboard/files/" + draftId + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            assertEquals("voice_call", data(json(c, "GET", "/components/dataset/" + id, null, null), 200).at("/content/recordType").asText(),
                    "left out of the commit, the proposal is written");
        }
    }

    @Test
    void aColumnsEntityTypeCanBeChangedOnAnExistingDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "calls", Map.of("physicalRef", "calls/database",
                    "columns", List.of(Map.of("name", "a_party", "type", "string"), Map.of("name", "b_party", "type", "number"))));
            JsonNode r = data(json(c, "PUT", "/datasets/calls/columns/a_party", "{\"entityType\":\"msisdn\"}", null), 200);
            assertEquals("MSISDN", r.get("classification").asText());
            JsonNode ds = data(json(c, "GET", "/components/dataset/calls", null, null), 200).get("content");
            assertTrue(ds.get("columns").toString().contains("\"classification\":\"MSISDN\""), ds.toString());
            data(json(c, "PUT", "/datasets/calls/columns/a_party", "{\"classification\":null}", null), 200);
            assertFalse(data(json(c, "GET", "/components/dataset/calls", null, null), 200).toString().contains("MSISDN"));
            assertEquals(422, json(c, "PUT", "/datasets/calls/columns/nope", "{\"entityType\":\"msisdn\"}", null).statusCode());
            assertEquals(422, json(c, "PUT", "/datasets/calls/columns/a_party", "{\"entityType\":\"spaceship\"}", null).statusCode());
            assertEquals(404, json(c, "PUT", "/datasets/none/columns/a_party", "{\"entityType\":\"msisdn\"}", null).statusCode());

            // LA-COLUMN-ROLES-1: the same route sets a column's role and a measure's unit, judged by the Dataset rules
            data(json(c, "PUT", "/datasets/calls/columns/a_party", "{\"entityType\":\"msisdn\",\"role\":\"entity\"}", null), 200);
            data(json(c, "PUT", "/datasets/calls/columns/b_party", "{\"role\":\"measure\",\"unit\":\"USD\"}", null), 200);
            String cols = data(json(c, "GET", "/components/dataset/calls", null, null), 200).at("/content/columns").toString();
            assertTrue(cols.contains("\"role\":\"entity\"") && cols.contains("\"unit\":\"USD\""), cols);
            // LA-COLUMN-ROLES-1 P1: a measure holds numbers
            HttpResponse<String> textMeasure = json(c, "PUT", "/datasets/calls/columns/a_party", "{\"entityType\":null,\"role\":\"measure\"}", null);
            assertEquals(422, textMeasure.statusCode(), textMeasure.body());
            assertTrue(error(textMeasure).contains("is a measure but holds string values"), textMeasure.body());
            // LA-COLUMN-ROLES-1 P2: a column kept off the links stores carried: false; null carries it again
            data(json(c, "PUT", "/datasets/calls/columns/b_party", "{\"carried\":false}", null), 200);
            assertTrue(data(json(c, "GET", "/components/dataset/calls", null, null), 200).at("/content/columns").toString()
                    .contains("\"carried\":false"));
            data(json(c, "PUT", "/datasets/calls/columns/b_party", "{\"carried\":null}", null), 200);
            assertFalse(data(json(c, "GET", "/components/dataset/calls", null, null), 200).at("/content/columns").toString()
                    .contains("carried"));
            assertEquals(422, json(c, "PUT", "/datasets/calls/columns/b_party", "{\"carried\":\"no\"}", null).statusCode());
            HttpResponse<String> badRole = json(c, "PUT", "/datasets/calls/columns/b_party", "{\"role\":\"amount\"}", null);
            assertEquals(422, badRole.statusCode(), badRole.body());
            assertEquals(422, json(c, "PUT", "/datasets/calls/columns/b_party", "{\"role\":\"entity\"}", null).statusCode(),
                    "an Entity column holds an Entity Type");
            assertEquals(422, json(c, "PUT", "/datasets/calls/columns/b_party", "{}", null).statusCode());
            // a column that stops being a measure drops its unit; clearing what an Entity column holds makes it a detail
            data(json(c, "PUT", "/datasets/calls/columns/b_party", "{\"role\":\"Temporal\"}", null), 200); // stored lower-case
            data(json(c, "PUT", "/datasets/calls/columns/a_party", "{\"entityType\":null}", null), 200);
            cols = data(json(c, "GET", "/components/dataset/calls", null, null), 200).at("/content/columns").toString();
            assertFalse(cols.contains("unit") || cols.contains("\"role\":\"entity\""), cols);
            assertTrue(cols.contains("\"role\":\"temporal\""), cols);

            // the Dataset rules hold on every write: a link mapping naming a column the entry does not list is refused
            HttpResponse<String> bad = json(c, "PUT", "/components/dataset/calls", "{\"physicalRef\":\"calls/database\",\"columns\":"
                    + "[{\"name\":\"a_party\"}],\"defaultLinkMapping\":{\"from\":\"a_party\",\"to\":\"c_party\"}}", null);
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(error(bad).contains("not one of the Dataset's columns"), bad.body());
        }
    }

    // ── limits and formats (DT-8) ───────────────────────────────────────────────────────────────────────────

    @Test
    void theSpaceLimitsAreEnforced(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(json(c, "PUT", "/settings/link-analysis/limits", "{\"import.max_bytes\":100}", null), 200);
            HttpResponse<String> big = send(c, "POST", "/onboard/files?name=t.csv", transfers(60).getBytes(StandardCharsets.UTF_8), null);
            assertEquals(413, big.statusCode(), big.body());
            assertTrue(error(big).contains("import.max_bytes"), big.body());

            data(json(c, "PUT", "/settings/link-analysis/limits", "{\"import.max_bytes\":1000000,\"import.max_rows\":10}", null), 200);
            HttpResponse<String> many = send(c, "POST", "/onboard/files?name=t.csv", transfers(60).getBytes(StandardCharsets.UTF_8), null);
            assertEquals(413, many.statusCode(), many.body());
            assertTrue(error(many).contains("holds 60 rows; this Space's import.max_rows is 10"), many.body());

            data(json(c, "PUT", "/settings/link-analysis/limits", "{\"import.max_rows\":1000,\"import.max_datasets\":1}", null), 200);
            String first = uniqueId("first");
            data(json(c, "POST", "/onboard/files/" + draft(c, "t.csv", "stream", transfers(60), null).get("draftId").asText()
                    + "/commit", "{\"name\":\"" + first + "\"}", null), 200);
            HttpResponse<String> second = json(c, "POST", "/onboard/files/" + draft(c, "t.csv", "stream", transfers(60), null)
                    .get("draftId").asText() + "/commit", "{\"name\":\"" + uniqueId("second") + "\"}", null);
            assertEquals(409, second.statusCode(), second.body());
            assertTrue(error(second).contains("import.max_datasets"), second.body());
        }
    }

    @Test
    void excelIsRefusedWithSaveAsCsvWhenTheBundleLacksTheExtension(@TempDir Path cfg, @TempDir Path root, @TempDir Path noExtensions) throws Exception {
        System.setProperty("duckdb.extension.dir", noExtensions.toString());   // extensions load ONLY from here, and it is empty
        try (Ctx c = open(cfg, root)) {
            byte[] zip = {'P', 'K', 3, 4, 0, 0, 0, 0};
            HttpResponse<String> r = send(c, "POST", "/onboard/files?name=book.xlsx", zip, null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(error(r).contains("save 'book.xlsx' as CSV"), r.body());
            assertEquals(422, send(c, "POST", "/onboard/files?name=old.xls", "x".getBytes(StandardCharsets.UTF_8), null).statusCode());
            HttpResponse<String> other = send(c, "POST", "/onboard/files?name=notes.docx", "x".getBytes(StandardCharsets.UTF_8), null);
            assertEquals(422, other.statusCode());
            assertTrue(error(other).contains("CSV, TSV, Parquet, JSON and NDJSON"), other.body());
            assertFalse(data(send(c, "POST", "/onboard/files?name=t.csv", transfers(5).getBytes(StandardCharsets.UTF_8), null), 200)
                    .get("limits").get("excel").asBoolean(), "the draft says Excel is not available here");
        }
    }

    @Test
    void ndjsonAndParquetAreRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            StringBuilder nd = new StringBuilder();
            for (int i = 0; i < 60; i++)
                nd.append("{\"payer_account\":\"ACC-").append(i % 7).append("\",\"payee_account\":\"ACC-").append(i % 5 + 10).append("\",\"amount\":").append(i).append(".5}\n");
            JsonNode d = draft(c, "events.ndjson", "stream", nd.toString(), null);
            assertEquals("ndjson", d.get("format").get("type").get("value").asText());
            assertEquals("payer_account", d.get("entityTypes").get("link").get("from").get("value").asText(), d.get("entityTypes").toString());
            String id = uniqueId("events");
            JsonNode s = data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            assertEquals(60, s.get("rowsAfter").asLong(), s.toString());

            // the import's own Parquet output, added back as a Parquet file: the file's declared types are taken
            Path parquet;
            try (Stream<Path> w = Files.walk(Path.of("database", id, "database"))) {
                parquet = w.filter(p -> p.toString().endsWith(".parquet")).findFirst().orElseThrow();
            }
            JsonNode p = data(send(c, "POST", "/onboard/files?name=out.parquet", Files.readAllBytes(parquet), null), 200);
            assertEquals("parquet", p.get("format").get("type").get("value").asText());
            assertEquals("DOUBLE", column(p, "amount").get("type").get("value").asText());
            assertTrue(column(p, "amount").get("type").get("evidence").asText().startsWith("the file declares"), p.toString());
        }
    }

    // ── data.self_import and the capability gates (armed: 401 / 403 / 200) ─────────────────────────────────

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case UPLOADER -> Optional.of(new Subject("uploader-1", Set.of("canUploadData")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canUploadData")));
            case AUTHOR -> Optional.of(new Subject("author-1", Set.of("canAuthorWorkbench")));
            case UPLOADER2 -> Optional.of(new Subject("uploader-2", Set.of("canUploadData")));
            default -> Optional.empty();
        });
    }

    @Test
    void withoutSignInSelfImportIsOnUntilTurnedOff(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode s = data(json(c, "GET", "/settings/data", null, null), 200);
            assertTrue(s.get("selfImport").isNull());
            assertTrue(s.get("selfImportDefault").asBoolean());
            assertTrue(s.get("selfImportInForce").asBoolean());
            assertTrue(data(json(c, "GET", "/bootstrap", null, null), 200).get("selfImport").asBoolean());

            data(json(c, "PUT", "/settings/data", "{\"selfImport\":false}", null), 200);
            assertFalse(data(json(c, "GET", "/bootstrap", null, null), 200).get("selfImport").asBoolean());
            for (HttpResponse<String> r : List.of(
                    send(c, "POST", "/onboard/files?name=t.csv", transfers(5).getBytes(StandardCharsets.UTF_8), null),
                    json(c, "POST", "/onboard/files/d" + "0".repeat(32) + "/commit", "{}", null),
                    json(c, "POST", "/datasets/x/run", null, null),
                    send(c, "POST", "/datasets/x/files?name=t.csv", "a\n".getBytes(StandardCharsets.UTF_8), null))) {
                assertEquals(403, r.statusCode(), r.body());
                assertTrue(error(r).contains("Add data is turned off in this Space"), r.body());
            }
            assertEquals(422, json(c, "PUT", "/settings/data", "{\"selfImport\":\"yes\"}", null).statusCode());
            data(json(c, "PUT", "/settings/data", "{\"selfImport\":null}", null), 200);
            assertTrue(data(json(c, "GET", "/settings/data", null, null), 200).get("selfImportInForce").asBoolean());
        }
    }

    @Test
    void withSignInSelfImportIsOffByDefaultAndNeedsCanUploadData(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            byte[] csv = transfers(60).getBytes(StandardCharsets.UTF_8);
            // 401 without a credential, on every route of the family
            for (String[] r : new String[][] {{"POST", "/onboard/files"}, {"POST", "/onboard/files/x/commit"},
                    {"GET", "/onboard/runs/x"}, {"POST", "/datasets/x/files"}, {"POST", "/datasets/x/run"},
                    {"PUT", "/datasets/x/columns/y"}, {"PUT", "/datasets/x/record-type"}, {"PUT", "/settings/data"}})
                assertEquals(401, send(c, r[0], r[1].endsWith("/files") ? r[1] + "?name=t.csv" : r[1], "{}".getBytes(StandardCharsets.UTF_8), null).statusCode(), r[1]);
            // 403: a Subject without canUploadData / canAuthorWorkbench / canAdminister
            assertEquals(403, send(c, "POST", "/onboard/files?name=t.csv", csv, ANALYST).statusCode());
            assertEquals(403, json(c, "POST", "/onboard/files/x/commit", "{}", ANALYST).statusCode());
            assertEquals(403, json(c, "GET", "/onboard/runs/x", null, ANALYST).statusCode());
            assertEquals(403, send(c, "POST", "/datasets/x/files?name=t.csv", csv, ANALYST).statusCode());
            assertEquals(403, json(c, "POST", "/datasets/x/run", null, ANALYST).statusCode());
            assertEquals(403, json(c, "PUT", "/datasets/x/columns/y", "{\"entityType\":null}", UPLOADER).statusCode());
            assertEquals(403, json(c, "PUT", "/datasets/x/record-type", "{\"recordType\":null}", UPLOADER).statusCode());
            assertEquals(403, json(c, "PUT", "/settings/data", "{\"selfImport\":true}", UPLOADER).statusCode());

            // the capability alone is not enough: with sign-in the Space's switch is off until an administrator turns it on
            assertFalse(data(json(c, "GET", "/settings/data", null, UPLOADER), 200).get("selfImportInForce").asBoolean());
            assertFalse(data(json(c, "GET", "/bootstrap", null, UPLOADER), 200).get("selfImport").asBoolean());
            HttpResponse<String> off = send(c, "POST", "/onboard/files?name=t.csv", csv, UPLOADER);
            assertEquals(403, off.statusCode(), off.body());
            assertTrue(error(off).contains("ask an administrator"), off.body());

            data(json(c, "PUT", "/settings/data", "{\"selfImport\":true}", ADMIN), 200);
            assertTrue(data(json(c, "GET", "/bootstrap", null, UPLOADER), 200).get("selfImport").asBoolean());
            JsonNode d = data(send(c, "POST", "/onboard/files?name=t.csv", csv, UPLOADER), 200);
            assertEquals(403, json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{}", ANALYST).statusCode());
            assertEquals(404, json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{}", UPLOADER2).statusCode(),
                    "another person's draft is not there for this caller");
            String id = uniqueId("signed");
            data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", UPLOADER), 200);
            JsonNode ds = data(json(c, "GET", "/components/dataset/" + id, null, UPLOADER), 200).get("content");
            assertEquals("uploader-1", ds.get("owner").asText(), "the importer owns the Dataset");
            // LA-ONBOARD-DEFECTS-1: shared with the Space like every other Dataset in it - it was `shares: []`, private to the
            // importer, with no screen to share it, so an analyst of the Space could not start a graph from it
            assertFalse(ds.has("shares"), "no sharing envelope: the owner is provenance, not a restriction");
            assertEquals("uploader-1", data(json(c, "GET", "/components/dataset/" + id, null, ANALYST), 200).at("/content/owner").asText(),
                    "a colleague in the Space sees the imported Dataset");
            data(json(c, "POST", "/datasets/" + id + "/run", null, UPLOADER), 200);
            assertEquals(404, json(c, "GET", "/onboard/runs/r" + "0".repeat(32), null, UPLOADER).statusCode());

            // the column route: canAuthorWorkbench, and edit access to the Dataset
            new ComponentStore(root.resolve("registry")).write("dataset", "open_ds", Map.of("physicalRef", "open_ds/database",
                    "columns", List.of(Map.of("name", "a"))));
            data(json(c, "PUT", "/datasets/open_ds/columns/a", "{\"entityType\":\"account\"}", AUTHOR), 200);
            data(json(c, "PUT", "/datasets/open_ds/record-type", "{\"recordType\":null}", AUTHOR), 200);
            // a Dataset made private through the component routes stays private to its owner
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            Map<String, Object> privateDs = new java.util.LinkedHashMap<>(registry.get("dataset", id).orElseThrow().content());
            privateDs.put("shares", List.of());
            registry.write("dataset", id + "_private", privateDs);
            assertEquals(404, json(c, "PUT", "/datasets/" + id + "_private/columns/amount", "{\"entityType\":null}", AUTHOR).statusCode(),
                    "another person's private Dataset is not there for this caller");
        }
    }

    /**
     * LA-ONBOARD-RESIDUALS-1 item 4: with sign-in a followed run (202) is its starter's - another uploader polling its runId
     * gets 404, an administrator and the starter get its state. Falsified: with the actor check removed from
     * {@code OnboardImport.run}, the second uploader reads the run (200).
     */
    @Test
    void aFollowedRunIsReadOnlyByItsStarterOrAnAdministrator(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            data(json(c, "PUT", "/settings/data", "{\"selfImport\":true}", ADMIN), 200);
            data(json(c, "PUT", "/settings/link-analysis/limits", "{\"import.inline_max_bytes\":0}", AUTHOR), 200);
            String id = uniqueId("owned_run");
            JsonNode d = data(send(c, "POST", "/onboard/files?name=t.csv", transfers(60).getBytes(StandardCharsets.UTF_8), UPLOADER), 200);
            JsonNode r = data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", UPLOADER), 202);
            String runId = r.get("runId").asText();
            assertFalse(r.has("actor"), "the starter is not echoed: " + r);

            HttpResponse<String> other = json(c, "GET", "/onboard/runs/" + runId, null, UPLOADER2);
            assertEquals(404, other.statusCode(), "another uploader cannot see the run: " + other.body());
            JsonNode admin = data(json(c, "GET", "/onboard/runs/" + runId, null, ADMIN), 200);
            assertEquals(runId, admin.get("runId").asText());
            assertFalse(admin.has("actor"), admin.toString());
            JsonNode done = follow(c, runId, UPLOADER);
            assertEquals("succeeded", done.get("state").asText(), done.toString());
            assertEquals(404, json(c, "GET", "/onboard/runs/" + runId, null, UPLOADER2).statusCode(), "still not after it finished");
        }
    }

    /**
     * LA-ONBOARD-RESIDUALS-1 item 4: in a multi-Space server a followed (202) commit and a followed Add a file run in the
     * Space they were started in - the background run uses the roots captured on the request thread, never "the current
     * Space". Everything lands in {@code west}; {@code east} holds nothing of it.
     */
    @Test
    void aFollowedRunInASecondSpaceLandsInThatSpace(@TempDir Path root) throws Exception {
        for (String space : List.of("east", "west")) Files.createDirectories(root.resolve(space).resolve("config"));
        com.gamma.service.SpaceManager spaces = com.gamma.service.SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            Ctx c = new Ctx(null, api, api.port(), root);
            data(json(c, "PUT", "/spaces/west/settings/link-analysis/limits", "{\"import.inline_max_bytes\":0}", null), 200);
            String id = "west_ledger";
            JsonNode d = data(send(c, "POST", "/spaces/west/onboard/files?name=transfers.csv&kind=stream",
                    transfers(70).getBytes(StandardCharsets.UTF_8), null), 200);
            JsonNode r = data(json(c, "POST", "/spaces/west/onboard/files/" + d.get("draftId").asText() + "/commit",
                    "{\"name\":\"" + id + "\"}", null), 202);
            JsonNode done = follow(c, r.get("runId").asText(), null);
            assertEquals("succeeded", done.get("state").asText(), done.toString());
            assertEquals(70, done.get("summary").get("rowsAfter").asLong());

            JsonNode add = data(send(c, "POST", "/spaces/west/datasets/" + id + "/files?name=more.csv&mode=append",
                    transfers(30).getBytes(StandardCharsets.UTF_8), null), 202);
            JsonNode added = follow(c, add.get("runId").asText(), null);
            assertEquals("succeeded", added.get("state").asText(), added.toString());
            assertEquals(100, added.get("summary").get("rowsAfter").asLong(), added.toString());

            assertTrue(Files.exists(root.resolve("west/config/imports").resolve(id + "_pipeline.toon")));
            assertEquals(200, json(c, "GET", "/spaces/west/components/dataset/" + id, null, null).statusCode());
            assertTrue(data(json(c, "GET", "/spaces/west/datasets/" + id + "/rows?limit=5", null, null), 200).toString().contains("ACC-"));
            assertFalse(Files.exists(root.resolve("east/config/imports")), "nothing in the other Space's config");
            assertEquals(404, json(c, "GET", "/spaces/east/components/dataset/" + id, null, null).statusCode());
            try (Stream<Path> east = Files.walk(root.resolve("east"))) {
                assertTrue(east.noneMatch(p -> p.getFileName().toString().contains(id)), "no data in the other Space");
            }
        } finally {
            api.close();
            spaces.close();
            com.gamma.metrics.MetricRegistry.global().reset();
        }
    }

    private JsonNode follow(Ctx c, String runId, String auth) throws Exception {
        for (int i = 0; i < 600; i++) {
            JsonNode s = data(json(c, "GET", "/onboard/runs/" + runId, null, auth), 200);
            if (!"running".equals(s.get("state").asText())) return s;
            Thread.sleep(50);
        }
        throw new AssertionError("the followed run never finished");
    }
}
