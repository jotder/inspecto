package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceManager;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R2-03 (operator 2026-09-26, reversing C9) — real-HTTP + real-DuckDB tests for a Reconciliation's
 * <b>operational state</b>: {@code POST /recon/{id}/record} (compute every Break server-side, merge the
 * lifecycle, stamp the run), {@code POST /recon/{id}/breaks/status} (resolve / re-open one Break), and the
 * reads {@code GET /recon/{id}/state} + {@code GET /recon/state}. Every gate: 503 no write root · 422 unsafe id
 * / bad body · 404 unknown · 403 jail · 503 unreadable · 401/403/200 with a real Subject (the ONLY way to prove
 * {@code canOperateRuns} gates anything — {@code withCapability} is a no-op without one). And the lifecycle
 * merge end to end, including the Breaks beyond the old 200-row page the Board used to auto-close.
 */
class ControlApiReconStateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OLD = "2026-07-01T00:00:00Z";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    /**
     * One Space with the design doc's example (EU/voice matched, EU/data 118 vs 114 outside 0.5%, MEA/voice only
     * in A, APAC/sms only in B, US/voice matched) as {@code orders_recon}, plus {@code wide_recon}: 250 ids on A
     * against id 0 alone on B — 249 missing-right Breaks, more than one {@code /recon/breaks} page. And two
     * 3-way ones: {@code sim_recon}, the RA-C01 shape (HLR vs CRM vs CBS on msisdn, {@code active_flag} exact,
     * {@code one_to_one}) where m2 breaks on {@code active_flag} against BOTH C and B, and m3 is missing from
     * CRM only; and {@code wide3_recon}, 30,000 ids only in A against each of B and C — under the cap per pair,
     * over it together.
     */
    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));

        Path dataDir = base.resolve("data");
        seed(dataDir, "orders_a", "SELECT * FROM (VALUES ('EU','voice',100.0),('EU','voice',100.0),"
                + "('EU','data',118.0),('US','voice',50.0),('MEA','voice',10.0)) t(region, product, amount)");
        seed(dataDir, "orders_b", "SELECT * FROM (VALUES ('EU','voice',200.0),('EU','data',114.0),"
                + "('US','voice',50.0),('APAC','sms',7.0)) t(region, product, amount)");
        seed(dataDir, "wide_a", "SELECT range AS id, 1.0 AS amount FROM range(250)");
        seed(dataDir, "wide_b", "SELECT 0 AS id, 1.0 AS amount");
        seed(dataDir, "hlr", "SELECT * FROM (VALUES ('m1',1),('m2',1),('m3',1)) t(msisdn, active_flag)");
        seed(dataDir, "crm", "SELECT * FROM (VALUES ('m1',1),('m2',0)) t(msisdn, active_flag)");
        seed(dataDir, "cbs", "SELECT * FROM (VALUES ('m1',1),('m2',0),('m3',1)) t(msisdn, active_flag)");
        seed(dataDir, "wide3_a", "SELECT range AS id, 1.0 AS amount FROM range(30001)");

        ComponentStore store = new ComponentStore(config.resolve("registry"));
        store.write("dataset", "a_ds", Map.of("physicalRef", "orders_a"));
        store.write("dataset", "b_ds", Map.of("physicalRef", "orders_b"));
        store.write("dataset", "wa_ds", Map.of("physicalRef", "wide_a"));
        store.write("dataset", "wb_ds", Map.of("physicalRef", "wide_b"));
        store.write("dataset", "hlr_ds", Map.of("physicalRef", "hlr"));
        store.write("dataset", "crm_ds", Map.of("physicalRef", "crm"));
        store.write("dataset", "cbs_ds", Map.of("physicalRef", "cbs"));
        store.write("dataset", "w3_ds", Map.of("physicalRef", "wide3_a"));
        store.write("reconciliation", "sim_recon", Map.of(
                "datasets", List.of("hlr_ds", "crm_ds", "cbs_ds"),
                "keyColumns", List.of("msisdn"),
                "cardinality", "one_to_one",
                "compareColumns", List.of(Map.of("column", "active_flag", "toleranceType", "exact"))));
        store.write("reconciliation", "wide3_recon", Map.of(
                "datasets", List.of("w3_ds", "wb_ds", "wb_ds"),
                "keyColumns", List.of("id"),
                "compareColumns", List.of(Map.of("column", "amount"))));
        store.write("reconciliation", "orders_recon", Map.of(
                "datasets", List.of("a_ds", "b_ds"),
                "keyColumns", List.of("region", "product"),
                "compareColumns", List.of(Map.of("column", "amount", "toleranceType", "percent", "tolerance", 0.5))));
        store.write("reconciliation", "wide_recon", Map.of(
                "datasets", List.of("wa_ds", "wb_ds"),
                "keyColumns", List.of("id"),
                "compareColumns", List.of(Map.of("column", "amount"))));
        store.write("reconciliation", "ghost_side_recon", Map.of(
                "datasets", List.of("a_ds", "ghost_ds"),
                "keyColumns", List.of("region"),
                "compareColumns", List.of(Map.of("column", "amount"))));

        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config);
    }

    // ── POST /recon/{id}/record — the lifecycle merge ────────────────────────────────

    @Test
    void aRecordComputesEveryBreakAndStampsTheRun(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces/s1/recon/orders_recon/record", null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode s = V1Body.of(r.body());
            String runAt = s.get("lastRunAt").asText();
            assertEquals("orders_recon", s.get("reconciliation").asText());
            assertEquals(1, s.get("runs").asInt());
            assertEquals(Map.of("MEA · voice", "missing_right", "APAC · sms", "missing_left", "EU · data", "value_break"),
                    byKey(s, "type"));
            for (JsonNode b : s.get("breaks")) {
                assertEquals("open", b.get("status").asText());
                assertEquals(runAt, b.get("firstSeenAt").asText(), "a new Break carries the run's own instant");
            }
            JsonNode vb = find(s, "EU · data");
            assertEquals("amount", vb.get("column").asText());
            assertEquals(-4.0, vb.get("diff").asDouble(), "B − A");

            // the reads answer the same state, and the list reports the run
            JsonNode read = V1Body.of(send(c, "GET", "/spaces/s1/recon/orders_recon/state", null).body());
            assertEquals(s, read);
            JsonNode list = V1Body.of(send(c, "GET", "/spaces/s1/recon/state", null).body());
            assertEquals(5, list.get("total").asInt());
            assertFalse(list.get("truncated").asBoolean());
            Map<String, String> last = new LinkedHashMap<>();
            for (JsonNode row : list.get("states")) last.put(row.get("reconciliation").asText(), row.get("lastRunAt").asText());
            assertEquals(runAt, last.get("orders_recon"));
            assertEquals("null", last.get("wide_recon"), "a never-run Reconciliation is listed with no run");
        }
    }

    @Test
    void aSecondRunKeepsSightingsAndResolutionsAndAutoClosesWhatIsGone(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            writeState(c, "orders_recon", 4, List.of(
                    rec("value_break", "EU · data", "amount", "resolved", "known FX gap"),
                    rec("missing_right", "MEA · voice", null, "open", null),
                    rec("missing_left", "LATAM · voice", null, "open", null),
                    rec("missing_left", "OLD · sms", null, "auto_closed", null)));

            JsonNode s = V1Body.of(send(c, "POST", "/spaces/s1/recon/orders_recon/record", null).body());
            String runAt = s.get("lastRunAt").asText();
            assertEquals(5, s.get("runs").asInt());

            JsonNode resolved = find(s, "EU · data");
            assertEquals("resolved", resolved.get("status").asText(), "a resolution survives the run");
            assertEquals("known FX gap", resolved.get("note").asText());
            assertEquals(OLD, resolved.get("firstSeenAt").asText());
            assertEquals(118.0, resolved.get("leftValue").asDouble(), "values refresh from this run");

            assertEquals("open", find(s, "MEA · voice").get("status").asText());
            assertEquals(OLD, find(s, "MEA · voice").get("firstSeenAt").asText(), "⛔ never re-stamped");
            assertEquals(runAt, find(s, "APAC · sms").get("firstSeenAt").asText(), "first seen on this run");
            assertEquals("auto_closed", find(s, "LATAM · voice").get("status").asText(), "gone ⇒ auto-closed");
            assertNull(find(s, "OLD · sms"), "an auto-closed Break still gone is dropped");
        }
    }

    /**
     * 🔴 The second defect R2-03 fixed: the Board merged ONE {@code /recon/breaks} page (200 per set), so a
     * recorded Break past it read as gone and was auto-closed. Key 240 is past any 200-row page.
     */
    @Test
    void moreThanAPageOfBreaksIsRecordedAndNothingBeyondItIsAutoClosed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            writeState(c, "wide_recon", 1, List.of(rec("missing_right", "240", null, "resolved", "parked")));

            HttpResponse<String> r = send(c, "POST", "/spaces/s1/recon/wide_recon/record", null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode s = V1Body.of(r.body());
            assertEquals(249, s.get("breaks").size(), "ids 1..249 are only in A — every one recorded");
            JsonNode past = find(s, "240");
            assertEquals("resolved", past.get("status").asText(), "NOT auto-closed");
            assertEquals(OLD, past.get("firstSeenAt").asText());
        }
    }

    // ── POST /recon/{id}/breaks/status ───────────────────────────────────────────────

    @Test
    void aBreakIsResolvedAndReopenedByIdentityAndTheResolutionSurvivesARun(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "POST", "/spaces/s1/recon/orders_recon/record", null).statusCode());

            HttpResponse<String> r = send(c, "POST", "/spaces/s1/recon/orders_recon/breaks/status",
                    "{\"type\":\"value_break\",\"key\":\"EU · data\",\"column\":\"amount\",\"status\":\"resolved\",\"note\":\"  FX  \"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode b = V1Body.of(r.body()).get("break");
            assertEquals("resolved", b.get("status").asText());
            assertEquals("FX", b.get("note").asText(), "trimmed");

            JsonNode afterRun = V1Body.of(send(c, "POST", "/spaces/s1/recon/orders_recon/record", null).body());
            assertEquals("resolved", find(afterRun, "EU · data").get("status").asText());
            assertEquals(2, afterRun.get("runs").asInt());

            JsonNode reopened = V1Body.of(send(c, "POST", "/spaces/s1/recon/orders_recon/breaks/status",
                    "{\"type\":\"value_break\",\"key\":\"EU · data\",\"column\":\"amount\",\"status\":\"open\"}").body()).get("break");
            assertEquals("open", reopened.get("status").asText());
            assertNull(reopened.get("note"), "a re-open without a note clears it");

            // a Break no run has recorded yet is appended identity-only
            JsonNode appended = V1Body.of(send(c, "POST", "/spaces/s1/recon/orders_recon/breaks/status",
                    "{\"type\":\"missing_left\",\"key\":\"NEW · sms\",\"status\":\"resolved\"}").body()).get("break");
            assertEquals("resolved", appended.get("status").asText());
            JsonNode state = V1Body.of(send(c, "GET", "/spaces/s1/recon/orders_recon/state", null).body());
            assertNotNull(find(state, "NEW · sms"));
            assertEquals(2, state.get("runs").asInt(), "a status change is not a run");
        }
    }

    @Test
    void aStatusChangeValidatesItsBody(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String url = "/spaces/s1/recon/orders_recon/breaks/status";
            assertEquals(422, send(c, "POST", url, "{\"type\":\"bogus\",\"key\":\"k\",\"status\":\"open\"}").statusCode());
            assertEquals(422, send(c, "POST", url, "{\"type\":\"missing_left\",\"status\":\"open\"}").statusCode());
            assertEquals(422, send(c, "POST", url, "{\"type\":\"missing_left\",\"key\":7,\"status\":\"open\"}").statusCode());
            assertEquals(422, send(c, "POST", url, "{\"type\":\"missing_left\",\"key\":\"k\",\"status\":\"auto_closed\"}").statusCode(),
                    "auto_closed is the lifecycle's to set, never a caller's");
            assertEquals(422, send(c, "POST", url, "{\"type\":\"missing_left\",\"key\":\"k\",\"status\":\"open\",\"note\":\""
                    + "x".repeat(2_001) + "\"}").statusCode());
            assertEquals(404, send(c, "POST", "/spaces/s1/recon/ghost/breaks/status",
                    "{\"type\":\"missing_left\",\"key\":\"k\",\"status\":\"open\"}").statusCode());
            // ⚠ a single NULL key column's key IS "" — it must not read as a missing key
            assertEquals(200, send(c, "POST", url, "{\"type\":\"missing_left\",\"key\":\"\",\"status\":\"resolved\"}").statusCode());
        }
    }

    // ── a 3-way Reconciliation records its A↔C Breaks too ─────────────────────────────

    @Test
    void aThreeWayRecordStoresBothPairs(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces/s1/recon/sim_recon/record", null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode s = V1Body.of(r.body());
            Map<String, String> ids = new LinkedHashMap<>();
            for (JsonNode b : s.get("breaks"))
                ids.put(b.get("pair").asText() + " " + b.get("type").asText() + " " + b.get("key").asText(), b.get("status").asText());
            assertEquals(Map.of("AB missing_right m3", "open", "AB value_break m2", "open", "AC value_break m2", "open"), ids);
            assertEquals(s.get("lastRunAt").asText(), find(s, "AC", "m2").get("firstSeenAt").asText(),
                    "an A↔C Break is stamped by the run like an A↔B one");
            assertEquals(0.0, find(s, "AC", "m2").get("rightValue").asDouble(), "the compared side is C");
        }
    }

    /** 🔴 The pair is in the identity: resolving the A↔C Break on m2 must not touch the A↔B Break on m2. */
    @Test
    void resolvingAnAcBreakLeavesTheSameKeyAbBreakOpen(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "POST", "/spaces/s1/recon/sim_recon/record", null).statusCode());
            HttpResponse<String> r = send(c, "POST", "/spaces/s1/recon/sim_recon/breaks/status",
                    "{\"pair\":\"AC\",\"type\":\"value_break\",\"key\":\"m2\",\"column\":\"active_flag\",\"status\":\"resolved\",\"note\":\"CBS lags\"}");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("AC", V1Body.of(r.body()).get("break").get("pair").asText());

            JsonNode state = V1Body.of(send(c, "GET", "/spaces/s1/recon/sim_recon/state", null).body());
            assertEquals("resolved", find(state, "AC", "m2").get("status").asText());
            assertEquals("open", find(state, "AB", "m2").get("status").asText(), "the A↔B Break is untouched");
            assertEquals(3, state.get("breaks").size(), "matched an existing Break — nothing appended");

            JsonNode afterRun = V1Body.of(send(c, "POST", "/spaces/s1/recon/sim_recon/record", null).body());
            assertEquals("resolved", find(afterRun, "AC", "m2").get("status").asText(), "survives the next run");
            assertEquals("CBS lags", find(afterRun, "AC", "m2").get("note").asText());
            assertEquals("open", find(afterRun, "AB", "m2").get("status").asText());

            String url = "/spaces/s1/recon/orders_recon/breaks/status";
            assertEquals(422, send(c, "POST", url, "{\"pair\":\"AC\",\"type\":\"missing_left\",\"key\":\"k\",\"status\":\"open\"}").statusCode(),
                    "a 2-way Reconciliation has no A vs C Breaks");
            assertEquals(422, send(c, "POST", url, "{\"pair\":\"BC\",\"type\":\"missing_left\",\"key\":\"k\",\"status\":\"open\"}").statusCode());
        }
    }

    /** Migration rule: a state file R2-03 wrote (no {@code pair}) loads, and its Breaks are A↔B. */
    @Test
    void aLegacyPairLessStateLoadsAsAb(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            writeState(c, "sim_recon", 2, List.of(rec("value_break", "m2", "active_flag", "resolved", "fixed in CRM")));
            HttpResponse<String> read = send(c, "GET", "/spaces/s1/recon/sim_recon/state", null);
            assertEquals(200, read.statusCode(), read.body());
            assertEquals("AB", V1Body.of(read.body()).get("breaks").get(0).get("pair").asText());

            JsonNode s = V1Body.of(send(c, "POST", "/spaces/s1/recon/sim_recon/record", null).body());
            assertEquals("resolved", find(s, "AB", "m2").get("status").asText(), "the legacy resolution is the A↔B one");
            assertEquals(OLD, find(s, "AB", "m2").get("firstSeenAt").asText());
            assertEquals("open", find(s, "AC", "m2").get("status").asText(), "the A↔C Break does not inherit it");
            assertEquals(s.get("lastRunAt").asText(), find(s, "AC", "m2").get("firstSeenAt").asText());
        }
    }

    /** 30,000 A↔B + 30,000 A↔C: each pair is under the 50,000 cap, both together are refused — never recorded short. */
    @Test
    void moreThanTheCapOverBothPairsIsRefusedAndNothingIsRecorded(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c, "POST", "/spaces/s1/recon/wide3_recon/record", null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("60000 Breaks"), r.body());
            JsonNode s = V1Body.of(send(c, "GET", "/spaces/s1/recon/wide3_recon/state", null).body());
            assertEquals(0, s.get("runs").asInt());
            assertEquals(0, s.get("breaks").size());
        }
    }

    // ── fail-closed gates ────────────────────────────────────────────────────────────

    @Test
    void recordAndTheReadsFailClosed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(404, send(c, "POST", "/spaces/s1/recon/ghost/record", null).statusCode(), "unknown reconciliation");
            assertEquals(404, send(c, "POST", "/spaces/s1/recon/ghost_side_recon/record", null).statusCode(), "unknown dataset");
            assertEquals(422, send(c, "POST", "/spaces/s1/recon/bad..id/record", null).statusCode(), "unsafe id");
            assertEquals(422, send(c, "POST", "/spaces/s1/recon/bad..id/breaks/status",
                    "{\"type\":\"missing_left\",\"key\":\"k\",\"status\":\"open\"}").statusCode());
            assertEquals(404, send(c, "GET", "/spaces/s1/recon/ghost/state", null).statusCode());
            assertEquals(422, send(c, "GET", "/spaces/s1/recon/bad..id/state", null).statusCode());
            assertFalse(Files.exists(c.config.resolve("recon-state").resolve("ghost.json")), "a refusal writes nothing");
        }
    }

    @Test
    void anUnreadableStateIs503NeverANeverRunReconciliation(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Files.createDirectories(c.config.resolve("recon-state"));
            Files.writeString(c.config.resolve("recon-state").resolve("orders_recon.json"), "{not json");
            assertEquals(503, send(c, "GET", "/spaces/s1/recon/orders_recon/state", null).statusCode());
            assertEquals(503, send(c, "POST", "/spaces/s1/recon/orders_recon/record", null).statusCode());
            assertEquals(503, send(c, "GET", "/spaces/s1/recon/state", null).statusCode());
            assertEquals("{not json", Files.readString(c.config.resolve("recon-state").resolve("orders_recon.json")),
                    "the corrupt file is left for an operator, not overwritten");
        }
    }

    @Test
    void aStateDirectoryLinkedOutOfTheWriteRootIs403(@TempDir Path root, @TempDir Path elsewhere) throws Exception {
        try (Ctx c = open(root)) {
            assumeTrue(linkDir(c.config.resolve("recon-state"), elsewhere), "directory links unavailable here");
            assertEquals(403, send(c, "POST", "/spaces/s1/recon/orders_recon/record", null).statusCode());
            assertEquals(403, send(c, "POST", "/spaces/s1/recon/orders_recon/breaks/status",
                    "{\"type\":\"missing_left\",\"key\":\"k\",\"status\":\"open\"}").statusCode());
            assertEquals(403, send(c, "GET", "/spaces/s1/recon/orders_recon/state", null).statusCode());
            try (var files = Files.list(elsewhere)) {
                assertEquals(0, files.count(), "nothing was written outside the write root");
            }
        }
    }

    @Test
    void writeRootDisabledIs503ForTheWritesAnd404ForTheRead(@TempDir Path cfg) throws Exception {
        // Legacy single-space harness with no -Dassist.write.root → writeRoot() is null.
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        String prior = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                assertEquals(503, raw(api.port(), "POST", "/recon/x/record", null).statusCode());
                assertEquals(503, raw(api.port(), "POST", "/recon/x/breaks/status",
                        "{\"type\":\"missing_left\",\"key\":\"k\",\"status\":\"open\"}").statusCode());
                // a read writes nothing: with no registry there is no such reconciliation (as /recon/promoted)
                assertEquals(404, raw(api.port(), "GET", "/recon/x/state", null).statusCode());
                assertEquals(0, V1Body.of(raw(api.port(), "GET", "/recon/state", null).body()).get("total").asInt());
            } finally {
                api.close();
            }
        } finally {
            svc.close();
            if (prior != null) System.setProperty("assist.write.root", prior);
        }
    }

    // ── the capability: canOperateRuns, NOT canAuthorWorkbench ───────────────────────

    /** Forced Authenticator standing in for the Standard edition's security module. */
    private static final Authenticator SEED_ROLES = ex -> switch (
            String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
        case "Bearer ops" -> Optional.of(new Subject("ops", Roles.SEED.get("operations").capabilities()));
        case "Bearer developer" -> Optional.of(new Subject("dev", Roles.SEED.get("developer").capabilities()));
        default -> Optional.empty();
    };

    /**
     * 🔴 The defect R2-03 exists for: an {@code operations}-only user holds {@code canOperateRuns} but not
     * {@code canAuthorWorkbench}, and the run used to be recorded through the authoring PUT — so it 403'd and
     * no run was ever recorded. The PUT stays authoring-gated; recording is now the operate capability's.
     */
    @Test
    void recordingAndStatusChangesNeedCanOperateRunsAndAnOperatorHoldsIt(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Authenticators.forTest(SEED_ROLES);
            assertTrue(Roles.SEED.get("operations").capabilities().contains(Roles.CAN_OPERATE_RUNS));
            assertFalse(Roles.SEED.get("operations").capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH));
            assertFalse(Roles.SEED.get("developer").capabilities().contains(Roles.CAN_OPERATE_RUNS));
            String status = "{\"type\":\"value_break\",\"key\":\"EU · data\",\"column\":\"amount\",\"status\":\"resolved\"}";

            assertEquals(401, send(c, "POST", "/spaces/s1/recon/orders_recon/record", null).statusCode(), "no credential");
            HttpResponse<String> denied = send(c, "POST", "/spaces/s1/recon/orders_recon/record", null,
                    "Authorization", "Bearer developer");
            assertEquals(403, denied.statusCode(), denied.body());
            assertEquals(403, send(c, "POST", "/spaces/s1/recon/orders_recon/breaks/status", status,
                    "Authorization", "Bearer developer").statusCode());
            assertFalse(Files.exists(c.config.resolve("recon-state").resolve("orders_recon.json")), "a refusal writes nothing");

            HttpResponse<String> ok = send(c, "POST", "/spaces/s1/recon/orders_recon/record", null, "Authorization", "Bearer ops");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals(200, send(c, "POST", "/spaces/s1/recon/orders_recon/breaks/status", status,
                    "Authorization", "Bearer ops").statusCode());
            JsonNode s = V1Body.of(send(c, "GET", "/spaces/s1/recon/orders_recon/state", null, "Authorization", "Bearer ops").body());
            assertEquals(1, s.get("runs").asInt());
            assertEquals("resolved", find(s, "EU · data").get("status").asText());

            // …while the authoring PUT stays out of the operator's reach
            assertEquals(403, send(c, "PUT", "/spaces/s1/components/reconciliation/orders_recon",
                    "{\"datasets\":[\"a_ds\",\"b_ds\"],\"keyColumns\":[\"region\"],\"compareColumns\":[{\"column\":\"amount\"}]}",
                    "Authorization", "Bearer ops").statusCode());
        }
    }

    // ── state and config stay apart ──────────────────────────────────────────────────

    @Test
    void anAuthoringSaveNeitherCarriesNorTouchesTheRecordedState(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "POST", "/spaces/s1/recon/orders_recon/record", null).statusCode());
            HttpResponse<String> put = send(c, "PUT", "/spaces/s1/components/reconciliation/orders_recon",
                    "{\"datasets\":[\"a_ds\",\"b_ds\"],\"keyColumns\":[\"region\",\"product\"],"
                            + "\"compareColumns\":[{\"column\":\"amount\",\"toleranceType\":\"percent\",\"tolerance\":0.5}]}");
            assertEquals(200, put.statusCode(), put.body());

            JsonNode component = V1Body.of(send(c, "GET", "/spaces/s1/components/reconciliation/orders_recon", null).body());
            assertNull(component.get("content").get("breaks"), "run state is not config");
            assertNull(component.get("content").get("lastRunAt"));
            JsonNode s = V1Body.of(send(c, "GET", "/spaces/s1/recon/orders_recon/state", null).body());
            assertEquals(1, s.get("runs").asInt(), "the save left the recorded run alone");
            assertEquals(3, s.get("breaks").size());
        }
    }

    @Test
    void deletingAReconciliationDropsItsStateSoARecreatedOneStartsFresh(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "POST", "/spaces/s1/recon/orders_recon/record", null).statusCode());
            assertEquals(200, send(c, "DELETE", "/spaces/s1/components/reconciliation/orders_recon", null).statusCode());
            assertFalse(Files.exists(c.config.resolve("recon-state").resolve("orders_recon.json")));

            assertEquals(200, send(c, "POST", "/spaces/s1/components/reconciliation",
                    "{\"id\":\"orders_recon\",\"datasets\":[\"a_ds\",\"b_ds\"],\"keyColumns\":[\"region\"],"
                            + "\"compareColumns\":[{\"column\":\"amount\"}]}").statusCode());
            JsonNode s = V1Body.of(send(c, "GET", "/spaces/s1/recon/orders_recon/state", null).body());
            assertEquals(0, s.get("runs").asInt());
            assertTrue(s.get("lastRunAt").isNull());
            assertEquals(0, s.get("breaks").size());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Map<String, Object> rec(String type, String key, String column, String status, String note) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("key", key);
        b.put("type", type);
        if (column != null) b.put("column", column);
        b.put("status", status);
        if (note != null) b.put("note", note);
        b.put("firstSeenAt", OLD);
        return b;
    }

    /** Seed a recorded state as an earlier run would have left it. */
    private static void writeState(Ctx c, String id, int runs, List<Map<String, Object>> breaks) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("reconciliation", id);
        doc.put("lastRunAt", OLD);
        doc.put("runs", runs);
        doc.put("breaks", breaks);
        Files.createDirectories(c.config.resolve("recon-state"));
        Files.write(c.config.resolve("recon-state").resolve(id + ".json"), JSON.writeValueAsBytes(doc));
    }

    private static JsonNode find(JsonNode state, String key) {
        for (JsonNode b : state.get("breaks")) if (key.equals(b.get("key").asText())) return b;
        return null;
    }

    private static JsonNode find(JsonNode state, String pair, String key) {
        for (JsonNode b : state.get("breaks"))
            if (key.equals(b.get("key").asText()) && pair.equals(b.get("pair").asText())) return b;
        return null;
    }

    private static Map<String, String> byKey(JsonNode state, String field) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode b : state.get("breaks")) out.put(b.get("key").asText(), b.get(field).asText());
        return out;
    }

    /** A directory link out of the write root — a symlink, or on Windows without that privilege a junction. */
    private static boolean linkDir(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception unsupported) {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.isDirectory(link);
        } catch (Exception e) {
            return false;
        }
    }

    private static void seed(Path dataDir, String name, String select) throws Exception {
        Path partition = dataDir.resolve(name).resolve("dt=2026");
        Files.createDirectories(partition);
        String parquet = partition.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("recon_state_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String... headers) throws Exception {
        return raw(c.port, method, path, body, headers);
    }

    private HttpResponse<String> raw(int port, String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (headers.length > 0) b.headers(headers);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
