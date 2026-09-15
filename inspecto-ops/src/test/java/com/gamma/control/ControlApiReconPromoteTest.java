package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.objects.ObjectType;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.OperationalObject;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BREAK-INCIDENT-1}: {@code POST /recon/promote} hands one reconciliation Break to Ops as an
 * {@link ObjectType#INCIDENT}, deduped on {@code (reconciliation, type, key, column)} — full parity with the
 * SPA's {@code breakId} since {@code BREAK-DEDUPE-GRAIN-1} (2026-09-15); it was {@code (reconciliation, key)}
 * before, so one key breaking on two columns collapsed into a single Incident.
 *
 * <p>⚠ <b>This class lives in {@code inspecto-ops}, not beside the other recon route tests.</b> The route
 * itself is in core, and core's gates for it ({@code 503} write root, {@code 422}, {@code 404}, and the
 * {@code 503} when no object engine is installed) are covered in {@code ControlApiReconTest} there. The
 * <em>happy</em> path cannot be: it needs a real Object Engine, and {@code inspecto-ops} is an optional
 * edition module that a default {@code mvn test} does not even build. Asserting the promotion where the
 * engine exists is the only way to assert it at all.
 *
 * <p>🔴 The dedupe is the point of the test, not a detail. Reconciliation is <b>stateless compute</b> —
 * nothing persists a Break — so the Incident's reference to one is reconstructed from the request on each
 * call. If that identity stopped deduping, every re-run of a nightly reconciliation would hand the operator a
 * fresh clone of an Incident they are already working; if it dedupes too broadly, Breaks vanish. Both
 * directions are asserted, because the grain sat untested in either until 2026-09-15.
 */
class ControlApiReconPromoteTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static final String RECON = "orders_recon";

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot with a write root carrying a saved {@code reconciliation} component — promote requires one. */
    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        new ComponentStore(writeRoot.resolve("registry")).write("reconciliation", RECON,
                Map.of("datasets", List.of("a_ds", "b_ds"), "keyColumns", List.of("region")));
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> promote(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/recon/promote"))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> promoted(int port, String reconId) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/v1/recon/promoted?reconciliation=" + reconId))
                .GET().build(), BodyHandlers.ofString());
    }

    private List<OperationalObject> incidents(Ctx c) {
        return TestOpsEngine.of(c.svc).query(new ObjectQuery(
                ObjectType.INCIDENT, null, null, null, null, null, null, 100, 0, 0L, false));
    }

    @Test
    void promotingABreakOpensOneIncidentCarryingItsEvidence(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            HttpResponse<String> r = promote(c.port, "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\","
                    + "\"type\":\"value_break\",\"column\":\"amount\",\"runId\":\"run-77\"}");
            assertEquals(200, r.statusCode(), r.body());
            // ⚠ A 2xx /api/v1 body is the ENVELOPE — the payload is under `data`.
            JsonNode data = V1Body.of(r.body());
            assertFalse(data.get("deduped").asBoolean(), "the first promotion is not a duplicate");
            assertFalse(data.get("incidentId").isNull(), "the first promotion must name the Incident it opened");

            List<OperationalObject> opened = incidents(c);
            assertEquals(1, opened.size(), "exactly one Incident");
            OperationalObject incident = opened.get(0);
            assertEquals(data.get("incidentId").asText(), incident.id());
            assertEquals(RECON, incident.correlationId(), "the reconciliation is the Incident's scope");

            Map<String, String> attrs = incident.attributes();
            assertEquals(RECON, attrs.get("reconciliation"));
            assertEquals("EU|voice", attrs.get("breakKey"), "the readable half, kept alongside the identity");
            assertEquals("value_break", attrs.get("breakType"));
            assertEquals("amount", attrs.get("column"));
            // ⚠ `EU|voice` contains the separator, so the key's `|` is escaped and the part separators are not
            // — this literal is the whole point of escaping (see ReconRoutes.breakIdentity).
            assertEquals("value_break|EU\\|voice|amount", attrs.get("breakId"),
                    "the dedupe identity is (type, key, column), escaped so a `|` in a value cannot collide");
            assertEquals("run-77", attrs.get("runId"), "the run is evidence — without it nobody can re-find the Break");
            assertTrue(incident.title().contains("EU|voice"), incident.title());
        }
    }

    @Test
    void promotingTheSameBreakTwiceDoesNotOpenASecondIncident(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String body = "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\",\"type\":\"value_break\"}";
            assertEquals(200, promote(c.port, body).statusCode());

            HttpResponse<String> again = promote(c.port, body);
            assertEquals(200, again.statusCode(), "a duplicate promotion is idempotent, not an error");
            JsonNode data = V1Body.of(again.body());
            assertTrue(data.get("deduped").asBoolean(), "the second promotion must report suppression");
            assertTrue(data.get("incidentId").isNull(),
                    "the seam reports suppression without naming the survivor — documented on the route");

            assertEquals(1, incidents(c).size(),
                    "a nightly reconciliation re-run must not clone an Incident the operator is already working");
        }
    }

    /**
     * The dedupe must not be so broad that it swallows a different Break. Without this, a dedupe keyed on
     * the reconciliation alone would pass the test above while silently dropping every Break after the
     * first — the failure mode that matters most here.
     */
    @Test
    void aDifferentBreakInTheSameReconciliationOpensItsOwnIncident(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            assertEquals(200, promote(c.port,
                    "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\"}").statusCode());
            assertEquals(200, promote(c.port,
                    "{\"reconciliation\":\"" + RECON + "\",\"key\":\"APAC|sms\"}").statusCode());

            List<OperationalObject> opened = incidents(c);
            assertEquals(2, opened.size(), "two distinct Breaks are two distinct Incidents");
            assertEquals(java.util.Set.of("EU|voice", "APAC|sms"),
                    opened.stream().map(o -> o.attributes().get("breakKey")).collect(java.util.stream.Collectors.toSet()));
        }
    }

    /**
     * 🔴 The case {@code BREAK-DEDUPE-GRAIN-1} exists to fix, and the one nothing pinned before: <b>one key
     * breaking on two COLUMNS is two Incidents.</b> The server deduped on {@code breakKey} alone until
     * 2026-09-15, so the {@code count} promote below was silently suppressed with "an Incident for key … is
     * already open" and the operator simply never saw it.
     *
     * <p>⚠ The sibling test above varies only the KEY, so it passed both before and after the change — it
     * could never have caught this. That is why this test varies ONLY the column, holding the key fixed.
     */
    @Test
    void oneKeyBreakingOnTwoColumnsOpensTwoIncidents(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String base = "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\",\"type\":\"value_break\"";
            assertEquals(200, promote(c.port, base + ",\"column\":\"amount\"}").statusCode());

            HttpResponse<String> second = promote(c.port, base + ",\"column\":\"count\"}");
            assertEquals(200, second.statusCode());
            assertFalse(V1Body.of(second.body()).get("deduped").asBoolean(),
                    "a different column is a different Break — it must NOT be suppressed");

            assertEquals(2, incidents(c).size(), "amount and count are two things to investigate");
        }
    }

    /**
     * The other half of the same grain: one key+column breaking with two TYPES is two Incidents. ⚠ This is
     * the decision's explicitly <b>accepted cost</b> — a value Break and a missing-row Break on one
     * key+column no longer share an Incident — asserted here so it reads as chosen rather than as drift.
     */
    @Test
    void oneKeyAndColumnBreakingTwoWaysOpensTwoIncidents(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String base = "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\",\"column\":\"amount\"";
            assertEquals(200, promote(c.port, base + ",\"type\":\"value_break\"}").statusCode());
            assertEquals(200, promote(c.port, base + ",\"type\":\"missing_right\"}").statusCode());

            assertEquals(2, incidents(c).size(), "type is part of the identity");
        }
    }

    /**
     * Dedupe still fires on a FULL identity match — the widening must not degrade into "never dedupe", which
     * would pass both tests above while handing Ops a clone on every nightly re-run.
     */
    @Test
    void theSameKeyTypeAndColumnStillDedupes(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String body = "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\","
                    + "\"type\":\"value_break\",\"column\":\"amount\"}";
            assertEquals(200, promote(c.port, body).statusCode());
            assertTrue(V1Body.of(promote(c.port, body).body()).get("deduped").asBoolean());
            assertEquals(1, incidents(c).size());
        }
    }

    /**
     * 🔴 The escaping, pinned end-to-end. A reconciliation key is itself a join of the key columns' values, so
     * it routinely contains {@code |}. Without escaping, {@code (EU|voice, amount)} and {@code (EU, voice|amount)}
     * render the same identity and collide into ONE Incident — reintroducing, one level down, the exact defect
     * this decision removes. ⚠ A plain-join implementation passes every other test in this class.
     */
    @Test
    void aSeparatorInsideTheKeyCannotCollideWithADifferentBreak(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String t = "\",\"type\":\"value_break\"";
            assertEquals(200, promote(c.port,
                    "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice" + t + ",\"column\":\"amount\"}").statusCode());
            assertEquals(200, promote(c.port,
                    "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU" + t + ",\"column\":\"voice|amount\"}").statusCode());

            assertEquals(2, incidents(c).size(),
                    "the `|` inside the key must not be readable as the identity's separator");
        }
    }

    /**
     * How long suppression lasts, which is <b>not</b> what it looks like. Dedupe is over <em>non-terminal</em>
     * Incidents, and 🔴 for an Incident the only terminal state is {@code ARCHIVED} — {@code RESOLVED} is not
     * one ({@code Workflow.defaultFor}: {@code IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED}, terminal set
     * {@code {ARCHIVED}}). So an operator who resolves a promoted Break and sees it recur gets <b>no new
     * Incident</b> until the old one is archived.
     *
     * <p>⚠ That is the product's existing rule, not something this route chose, and it is asserted here
     * precisely because it is surprising: the obvious expectation ("resolved means a recurrence is new
     * news") is wrong, and a future change to the Incident workflow's terminal set would silently change
     * how recurring Breaks behave. This test is where that would be caught.
     *
     * <p>(The first half also documents why the test cannot simply call {@code resolve}: resolving an
     * Incident is gated on a completion checklist — timeline, cause analysis, corrective actions, SLA —
     * so {@code archive} is the reachable terminal move from a freshly opened Incident.)
     */
    @Test
    void suppressionLastsUntilTheIncidentIsArchivedNotMerelyResolved(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String body = "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\"}";
            assertEquals(200, promote(c.port, body).statusCode());
            String opened = incidents(c).get(0).id();
            assertTrue(V1Body.of(promote(c.port, body).body()).get("incidentId").isNull(),
                    "suppressed while the Incident is open");

            // ARCHIVED is the terminal state; `archive` is legal straight from IDENTIFIED.
            TestOpsEngine.of(c.svc).transition(opened, "archive", "alice");
            assertEquals("ARCHIVED", TestOpsEngine.of(c.svc).get(opened).orElseThrow().status());

            JsonNode data = V1Body.of(promote(c.port, body).body());
            assertFalse(data.get("deduped").asBoolean(),
                    "once the Incident is terminal, a recurrence of the same Break is new news");
            assertEquals(2, incidents(c).size(), "the archived one plus the fresh one");
        }
    }

    // ── GET /recon/promoted — the READ half (BREAK-INCIDENT-RESOLVE-1) ──────────────────

    /**
     * The map's keys are Break IDENTITIES — {@code (type, key, column)} — not bare keys
     * ({@code BREAK-DEDUPE-GRAIN-1}). These promotes send neither {@code type} nor {@code column}, so the
     * identity is the default type, the escaped key, and an empty column.
     *
     * <p>⚠ Written as literals rather than computed by a helper mirroring {@code ReconRoutes.breakIdentity}:
     * this spelling is a published contract the SPA's {@code breakId()} must match byte for byte, so the test
     * has to fail when the spelling changes — a mirrored helper would silently agree with itself.
     */
    private static final String EU_VOICE_ID = "break|EU\\|voice|";
    private static final String NA_DATA_ID = "break|NA\\|data|";

    @Test
    void promotedNamesTheIncidentEachBreakOpened(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            assertEquals(200, promote(c.port, "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\"}").statusCode());
            assertEquals(200, promote(c.port, "{\"reconciliation\":\"" + RECON + "\",\"key\":\"NA|data\"}").statusCode());

            JsonNode data = V1Body.of(promoted(c.port, RECON).body());
            JsonNode map = data.get("promoted");
            assertEquals(2, map.size(), "both promoted Breaks are reported: " + map);
            assertFalse(map.get(EU_VOICE_ID).asText().isBlank(), "the Break names its Incident, not just a flag");
            assertEquals(2, data.get("total").asInt());
            assertFalse(data.get("truncated").asBoolean());

            // The ids are the real Incidents, not invented.
            List<String> live = incidents(c).stream().map(OperationalObject::id).toList();
            assertTrue(live.contains(map.get(EU_VOICE_ID).asText()), "the reported id is a real Incident");
        }
    }

    @Test
    void aBreakThatWasNeverPromotedIsAbsent(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            assertEquals(200, promote(c.port, "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\"}").statusCode());
            JsonNode map = V1Body.of(promoted(c.port, RECON).body()).get("promoted");
            assertFalse(map.has(NA_DATA_ID), "an unpromoted Break must not appear: " + map);
        }
    }

    /**
     * 🔴 <b>The test this route exists for.</b> {@code promote} suppresses only while the Incident is
     * NOT terminal — pinned by {@code suppressionLastsUntilTheIncidentIsArchivedNotMerelyResolved} above.
     * The read MUST agree: once the Incident is ARCHIVED the Break is promotable again, so reporting it as
     * still promoted would tell an operator an available action is unavailable. A client that reconstructed
     * this by listing Incidents and matching {@code breakKey} would fail exactly here.
     */
    @Test
    void anArchivedIncidentStopsCountingAsPromoted(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String body = "{\"reconciliation\":\"" + RECON + "\",\"key\":\"EU|voice\"}";
            assertEquals(200, promote(c.port, body).statusCode());
            String opened = incidents(c).get(0).id();
            assertTrue(V1Body.of(promoted(c.port, RECON).body()).get("promoted").has(EU_VOICE_ID),
                    "promoted while the Incident is open");

            TestOpsEngine.of(c.svc).transition(opened, "archive", "alice");

            assertFalse(V1Body.of(promoted(c.port, RECON).body()).get("promoted").has(EU_VOICE_ID),
                    "⛔ an ARCHIVED Incident must NOT read as promoted — promote() would open a fresh one, so "
                            + "the offer and the dedupe would disagree");
        }
    }
}
