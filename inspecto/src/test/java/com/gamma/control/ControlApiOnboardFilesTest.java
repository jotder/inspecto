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
            for (String k : List.of("from", "to", "kind", "time", "amount", "details")) assertChoice(link.get(k));
            assertEquals("from_account", link.get("from").get("value").asText());
            assertEquals("to_account", link.get("to").get("value").asText());
            assertEquals("high", link.get("from").get("confidence").asText());
            assertTrue(link.get("from").get("evidence").asText().contains("the names say from_account → to_account"), link.toString());
            assertEquals("channel", link.get("kind").get("value").asText());
            assertEquals("booked_at", link.get("time").get("value").asText());
            assertEquals("amount", link.get("amount").get("value").asText());
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
            assertTrue(imei.get("evidence").asText().contains("valid IMEI check digit"), imei.toString());
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
        for (char d = '0'; d <= '9'; d++) if (EntityShapes.luhn(body + d)) return d;
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
            assertEquals("booked_at", m.get("time").asText());
            assertEquals("amount", m.get("amount").asText());
            int classified = 0;
            for (JsonNode col : ds.get("columns")) if ("ACCOUNT".equals(col.path("classification").asText())) classified++;
            assertEquals(2, classified, ds.get("columns").toString());

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

    @Test
    void aColumnsEntityTypeCanBeChangedOnAnExistingDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "calls", Map.of("physicalRef", "calls/database",
                    "columns", List.of(Map.of("name", "a_party", "type", "string"), Map.of("name", "b_party", "type", "string"))));
            JsonNode r = data(json(c, "PUT", "/datasets/calls/columns/a_party", "{\"entityType\":\"msisdn\"}", null), 200);
            assertEquals("MSISDN", r.get("classification").asText());
            JsonNode ds = data(json(c, "GET", "/components/dataset/calls", null, null), 200).get("content");
            assertTrue(ds.get("columns").toString().contains("\"classification\":\"MSISDN\""), ds.toString());
            data(json(c, "PUT", "/datasets/calls/columns/a_party", "{\"classification\":null}", null), 200);
            assertFalse(data(json(c, "GET", "/components/dataset/calls", null, null), 200).toString().contains("MSISDN"));
            assertEquals(422, json(c, "PUT", "/datasets/calls/columns/nope", "{\"entityType\":\"msisdn\"}", null).statusCode());
            assertEquals(422, json(c, "PUT", "/datasets/calls/columns/a_party", "{\"entityType\":\"spaceship\"}", null).statusCode());
            assertEquals(404, json(c, "PUT", "/datasets/none/columns/a_party", "{\"entityType\":\"msisdn\"}", null).statusCode());

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
                    {"PUT", "/datasets/x/columns/y"}, {"PUT", "/settings/data"}})
                assertEquals(401, send(c, r[0], r[1].endsWith("/files") ? r[1] + "?name=t.csv" : r[1], "{}".getBytes(StandardCharsets.UTF_8), null).statusCode(), r[1]);
            // 403: a Subject without canUploadData / canAuthorWorkbench / canAdminister
            assertEquals(403, send(c, "POST", "/onboard/files?name=t.csv", csv, ANALYST).statusCode());
            assertEquals(403, json(c, "POST", "/onboard/files/x/commit", "{}", ANALYST).statusCode());
            assertEquals(403, json(c, "GET", "/onboard/runs/x", null, ANALYST).statusCode());
            assertEquals(403, send(c, "POST", "/datasets/x/files?name=t.csv", csv, ANALYST).statusCode());
            assertEquals(403, json(c, "POST", "/datasets/x/run", null, ANALYST).statusCode());
            assertEquals(403, json(c, "PUT", "/datasets/x/columns/y", "{\"entityType\":null}", UPLOADER).statusCode());
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
