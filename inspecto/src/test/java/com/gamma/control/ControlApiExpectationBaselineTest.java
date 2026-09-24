package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DUCKLE-C8 — real-HTTP + real-DuckDB tests for the {@code baseline} Expectation kind and its audited
 * {@code POST /expectations/{name}/baseline/accept|clear} ops: every gate (503 no write root · 403 missing
 * {@code canOperateRuns} with a real Subject · 422 not a baseline · 404 unknown · 409 already accepted), the
 * accept-only-on-a-whole-successful-run rule, a refused run still recording its profile, and a missing group
 * caught by {@code requireExistingGroups}. The legacy space's data root is {@code ./database}; each test
 * seeds a uniquely named Parquet store there and removes it.
 */
class ControlApiExpectationBaselineTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final String target = "baseline_" + System.nanoTime();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);   // captures the write root at construction
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    @AfterEach
    void cleanup() {
        Authenticators.forTest(null);
        try (var paths = Files.walk(Path.of("database").resolve(target))) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // best-effort test cleanup
        }
    }

    private String rowsBody(String name, int window) {
        return "{\"name\":\"" + name + "\",\"target\":\"" + target + "\",\"kind\":\"baseline\","
                + "\"baselineWindow\":" + window + ",\"measures\":[\"row_count\"],"
                + "\"maxIncrease\":10,\"maxDecrease\":10,\"limitUnit\":\"percent\"}";
    }

    // ── the kind end to end ────────────────────────────────────────────────────────

    @Test
    void aRefusedRunRecordsItsProfileAndOnlyAnAcceptMovesTheBaseline(@TempDir Path dir) throws Exception {
        seed("SELECT range AS id, 'EU' AS region FROM range(100)");
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/expectations", rowsBody("rows", 1)).statusCode());

            // cold start: nothing to compare → PASSED, and the run's profile becomes the baseline
            JsonNode r1 = evaluate(c, "rows");
            assertEquals("PASSED", r1.get("status").asText(), r1.toString());
            assertEquals(0, r1.get("baselineSize").asInt());

            // +50% rows → FAILED against the accepted 100, and the refused profile is still recorded
            seed("SELECT range AS id, 'EU' AS region FROM range(150)");
            JsonNode r2 = evaluate(c, "rows");
            assertEquals("FAILED", r2.get("status").asText(), r2.toString());
            assertEquals(1, r2.get("violations").asLong());
            assertEquals(1, r2.get("baselineSize").asInt());
            JsonNode f = r2.get("findings").get(0);
            assertEquals(100, f.get("baseline").asDouble());
            assertEquals(150, f.get("current").asDouble());
            assertEquals("above", f.get("direction").asText());
            String refused = r2.get("profileId").asText();

            // a second evaluation still compares against 100 — the refused profile did not move the baseline
            assertEquals("FAILED", evaluate(c, "rows").get("status").asText());

            // explicit, audited accept of the refused profile: the response carries the window it replaced
            HttpResponse<String> acc = send(c.port, "POST", "/expectations/rows/baseline/accept",
                    "{\"profileId\":\"" + refused + "\"}");
            assertEquals(200, acc.statusCode(), acc.body());
            JsonNode op = V1Body.of(acc.body());
            assertEquals("accept", op.get("op").asText());
            assertEquals(refused, op.get("profileId").asText());
            assertEquals(r1.get("profileId").asText(), op.get("replaced").get("window").get(0).asText());
            assertEquals(409, send(c.port, "POST", "/expectations/rows/baseline/accept",
                    "{\"profileId\":\"" + refused + "\"}").statusCode(), "already accepted");
            assertEquals(404, send(c.port, "POST", "/expectations/rows/baseline/accept",
                    "{\"profileId\":\"p-999\"}").statusCode(), "unknown profile");

            // the window (N=1) is now the 150 profile → the same input passes
            assertEquals("PASSED", evaluate(c, "rows").get("status").asText());

            // clear: audited with what it replaced; the next run is a cold start again
            HttpResponse<String> clr = send(c.port, "POST", "/expectations/rows/baseline/clear", null);
            assertEquals(200, clr.statusCode(), clr.body());
            assertTrue(V1Body.of(clr.body()).get("replaced").get("accepted").size() >= 2, clr.body());
            assertEquals(0, evaluate(c, "rows").get("baselineSize").asInt());
        }
    }

    @Test
    void aSweepAcceptsProfilesOnlyWhenEveryCheckPassed(@TempDir Path dir) throws Exception {
        seed("SELECT range AS id, CASE WHEN range = 0 THEN NULL ELSE 'x' END AS email FROM range(10)");
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/expectations", rowsBody("rows", 3)).statusCode());
            assertEquals(200, send(c.port, "POST", "/expectations", "{\"name\":\"nn\",\"target\":\"" + target
                    + "\",\"column\":\"email\",\"kind\":\"non_null\"}").statusCode());

            // the baseline itself PASSES (cold start), but 'nn' fails → the sweep did not succeed as a whole
            assertEquals(200, send(c.port, "POST", "/expectations/evaluate", null).statusCode());
            JsonNode again = evaluate(c, "rows");
            assertEquals(0, again.get("baselineSize").asInt(),
                    "a profile from a failed sweep must not have been accepted");
            // that single run DID succeed as a whole, so its profile is accepted
            assertEquals(1, evaluate(c, "rows").get("baselineSize").asInt());
        }
    }

    @Test
    void requireExistingGroupsCatchesTheMissingPartitionTotalsHide(@TempDir Path dir) throws Exception {
        seed("SELECT range AS id, CASE WHEN range % 2 = 0 THEN 'EU' ELSE 'US' END AS region FROM range(100)");
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/expectations", "{\"name\":\"g\",\"target\":\"" + target
                    + "\",\"kind\":\"baseline\",\"groupBy\":[\"region\"],\"requireExistingGroups\":true,"
                    + "\"maxDecrease\":90}").statusCode());
            assertEquals("PASSED", evaluate(c, "g").get("status").asText());

            // US vanished; EU holds steady — a totals-only check sees one normal group
            seed("SELECT range AS id, 'EU' AS region FROM range(50)");
            JsonNode r = evaluate(c, "g");
            assertEquals("FAILED", r.get("status").asText(), r.toString());
            assertEquals("missing_group", r.get("findings").get(0).get("direction").asText());
            assertEquals("[\"US\"]", r.get("findings").get(0).get("group").asText());
        }
    }

    // ── gates ────────────────────────────────────────────────────────────────────

    @Test
    void noDurableStoreFailsClosedWith503(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            assertEquals(503, send(c.port, "POST", "/expectations/rows/baseline/accept", "{}").statusCode());
            assertEquals(503, send(c.port, "POST", "/expectations/rows/baseline/clear", null).statusCode());
        }
    }

    @Test
    void anUnreadableHistoryFailsClosedWith503(@TempDir Path dir) throws Exception {
        seed("SELECT range AS id FROM range(3)");
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            assertEquals(200, send(c.port, "POST", "/expectations", rowsBody("rows", 3)).statusCode());
            Files.createDirectories(wr.resolve("expectation-baselines"));
            Files.writeString(wr.resolve("expectation-baselines").resolve("rows.json"), "{ torn");
            HttpResponse<String> r = send(c.port, "POST", "/expectations/rows/evaluate", null);
            assertEquals(503, r.statusCode(), "a torn history must not read as a cold start: " + r.body());
        }
    }

    @Test
    void notABaselineIs422AndUnknownIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/expectations", "{\"name\":\"nn\",\"target\":\"t\","
                    + "\"column\":\"c\",\"kind\":\"non_null\"}").statusCode());
            assertEquals(422, send(c.port, "POST", "/expectations/nn/baseline/accept", "{}").statusCode());
            assertEquals(422, send(c.port, "POST", "/expectations/nn/baseline/clear", null).statusCode());
            assertEquals(404, send(c.port, "POST", "/expectations/nope/baseline/clear", null).statusCode());
            // no recorded profile yet → nothing to accept
            assertEquals(200, send(c.port, "POST", "/expectations", rowsBody("rows", 3)).statusCode());
            assertEquals(404, send(c.port, "POST", "/expectations/rows/baseline/accept", "{}").statusCode());
            // the record constructor is the validator: a baseline with no limit is refused at authoring
            assertEquals(422, send(c.port, "POST", "/expectations", "{\"name\":\"nolimit\",\"target\":\"t\","
                    + "\"kind\":\"baseline\"}").statusCode());
        }
    }

    // Forced Authenticator standing in for the Standard edition's security module (same seam as
    // ControlApiNavMenusTest). Without a Subject, withCapability is a no-op and a 403 test proves nothing.
    private static final Authenticator SEED_ROLES = ex -> switch (
            String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
        case "Bearer ops" -> Optional.of(new Subject("ops", Roles.SEED.get("operations").capabilities()));
        case "Bearer developer" -> Optional.of(new Subject("dev", Roles.SEED.get("developer").capabilities()));
        default -> Optional.empty();
    };

    @Test
    void acceptAndClearRequireCanOperateRuns(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/expectations", rowsBody("rows", 3)).statusCode());
            Authenticators.forTest(SEED_ROLES);
            assertFalse(Roles.SEED.get("developer").capabilities().contains(Roles.CAN_OPERATE_RUNS));
            for (String op : List.of("accept", "clear")) {
                HttpResponse<String> denied = send(c.port, "POST", "/expectations/rows/baseline/" + op, "{}",
                        "Authorization", "Bearer developer");
                assertEquals(403, denied.statusCode(), denied.body());
            }
            // operations holds canOperateRuns: past the gate (clear on an empty history is a no-op success)
            HttpResponse<String> ok = send(c.port, "POST", "/expectations/rows/baseline/clear", "{}",
                    "Authorization", "Bearer ops");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("ops", V1Body.of(ok.body()).get("actor").asText(), "the op is attributed to its actor");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private JsonNode evaluate(Ctx c, String name) throws Exception {
        HttpResponse<String> r = send(c.port, "POST", "/expectations/" + name + "/evaluate", null);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body()).get("lastResult");
    }

    /** (Re)write the target as a single-partition Parquet store under the legacy data root. */
    private void seed(String selectSql) throws Exception {
        Path dir = Path.of("database").resolve(target).resolve("p=0");
        Files.createDirectories(dir);
        String parquet = dir.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("baseline_test_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (" + selectSql + ") TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (headers.length > 0) b.headers(headers);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
