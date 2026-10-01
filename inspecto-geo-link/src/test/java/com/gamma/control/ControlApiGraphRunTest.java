package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.api.GraphRunRoutes;
import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphEngine;
import com.gamma.la.core.GraphInput;
import com.gamma.la.core.GraphResult;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.LinkIds;
import com.gamma.la.graph.RunControl;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 step 6 - {@code /inv/graph/*} over REAL HTTP with an ARMED Authenticator throughout: with no Subject attached
 * {@code withCapability} is a no-op and every gate below would pass against an ungated route.
 *
 * <p>Subjects: {@code analyst-1} owns the Investigation and holds {@code canRunLinkGraphAnalysis}; {@code reader-1} is the
 * same person WITHOUT it; {@code analyst-2} holds it but owns nothing; {@code admin-1} holds {@code canAdminister}.
 * A {@link GraphEngine} probe that blocks until released makes RUNNING / QUEUED / 202 deterministic; the real engine
 * answers the rest.
 */
class ControlApiGraphRunTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANALYST = "Bearer analyst", READER = "Bearer reader", OTHER = "Bearer other", ADMIN = "Bearer admin";
    private static final String CREATE = "{\"id\":\"inv-g\",\"purpose\":\"Fraud referral FR-12\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-g";
    private static final List<String> NODES = List.of("n1", "n2", "n3", "n4", "n5");
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        GraphRunRoutes.forTest(null, -1);
    }

    private static void subjects() {
        Set<String> owner = Set.of("canManageIncidents", "canRunLinkGraphAnalysis");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ANALYST -> Optional.of(new Subject("analyst-1", owner));
            case READER -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case OTHER -> Optional.of(new Subject("analyst-2", owner));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canRunLinkGraphAnalysis")));
            default -> Optional.empty();
        });
    }

    /**
     * n1>n2 and n2>n3 FIVE times each (count 5 - strong links), n1>n3 once (a weak shortcut), n3>n4 by sms, n4>n5. {@code settings} is the Space's
     * {@code link-analysis.toon} (written before the first request); a null {@code writeRoot} is a read-only control plane.
     */
    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
                new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                        "SELECT caller, callee, channel FROM (VALUES ('n1','n2','voice'),('n1','n2','voice'),('n1','n2','voice'),"
                                + "('n1','n2','voice'),('n1','n2','voice'),('n2','n3','voice'),('n2','n3','voice'),('n2','n3','voice'),"
                                + "('n2','n3','voice'),('n2','n3','voice'),('n1','n3','voice'),"
                                + "('n3','n4','sms'),('n4','n5','voice')) AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
                new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            }
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        return data(send(c, method, path, body, auth), 200);
    }

    /** The Investigation over the Dataset, with all five nodes seeded and one expand sealed. */
    private void investigation(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, ANALYST);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":" + JSON.writeValueAsString(NODES) + "}", ANALYST);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", ANALYST);
    }

    private static String run(String algorithm, String extra) {
        return "{\"investigationId\":\"inv-g\",\"algorithm\":\"" + algorithm + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private HttpResponse<String> start(Ctx c, String body, String auth) throws Exception {
        return send(c, "POST", "/inv/graph/runs", body, auth);
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void until(Check c) throws Exception {
        long end = System.nanoTime() + 15_000_000_000L;
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("condition not reached in 15 s");
            Thread.sleep(10);
        }
    }

    private String status(Ctx c, String runId, String auth) throws Exception {
        return ok(c, "GET", "/inv/graph/runs/" + runId, null, auth).get("status").asText();
    }

    /** An engine that works until released and honours cancel and the deadline at its checkpoints - a long job. */
    private static final class Blocking implements GraphEngine {
        final CountDownLatch release = new CountDownLatch(1);

        @Override public String engineId() { return "blocking-probe"; }
        @Override public Set<Algorithm> supported() { return EnumSet.allOf(Algorithm.class); }

        @Override
        public GraphResult run(Algorithm a, Map<String, Object> params, GraphInput in, RunControl ctl) {
            try {
                while (release.getCount() > 0) {
                    ctl.checkpoint();
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new GraphResult(a, new GraphResult.Flag(true), 0, 1);
        }
    }

    // ── the catalogue ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theCatalogueListsAll28WithCeilingsAndTheSpacesDefaultsClampedAndEchoed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\ngraph_run:\n  max_nodes: 77\n  timeout_ms: 9000\n  threads: 3\n")) {
            JsonNode d = ok(c, "GET", "/inv/graph/algorithms", null, READER);       // a read: no capability
            assertEquals("memory", d.get("engine").asText());
            assertEquals(28, d.get("algorithms").size());
            assertEquals(Algorithm.values().length, d.get("algorithms").size());
            JsonNode pr = null;
            for (JsonNode a : d.get("algorithms")) if ("pageRank".equals(a.get("id").asText())) pr = a;
            assertEquals(Algorithm.PAGE_RANK.inlineNodeCeiling(), pr.get("inlineNodeCeiling").asInt());
            assertEquals("damping", pr.get("params").get(0).get("name").asText());
            assertEquals("SCORES", pr.get("resultKind").asText());
            assertEquals(500_000, d.get("ceilings").get("maxNodes").asInt());
            assertEquals(77, d.get("defaults").get("maxNodes").asInt(), "the Space's stated default");
            assertEquals(9000, d.get("defaults").get("timeoutMs").asInt());
            assertEquals(500_000, d.get("defaults").get("maxEdges").asInt(), "unstated fields keep the shipped default");
            assertFalse(d.get("defaults").get("clamped").asBoolean());
            assertEquals(3, d.get("pool").get("threads").asInt());
        }
        try (Ctx c = open(cfg, root, "graph_run:\n  max_nodes: 9999999\n")) {
            JsonNode d = ok(c, "GET", "/inv/graph/algorithms", null, READER);
            assertEquals(500_000, d.get("defaults").get("maxNodes").asInt(), "a default above the hard ceiling is clamped...");
            assertTrue(d.get("defaults").get("clamped").asBoolean(), "...and says so");
        }
    }

    // ── start: inline 200 · 202 + Location · terminal at submit ─────────────────────────────────────────────────

    @Test
    void aSmallRunAnswersInlineWithItsResultTheBudgetAndWhatItConsumed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            JsonNode d = ok(c, "POST", "/inv/graph/runs", run("shortestPath", "\"params\":{\"from\":\"n1\",\"to\":\"n5\"}"), ANALYST);
            assertEquals("COMPLETED", d.get("status").asText());
            assertEquals("memory", d.get("engine").asText());
            assertEquals(List.of("n1", "n3", "n4", "n5"), texts(d.get("result").get("selection").get("nodeIds")));
            assertEquals(3, d.get("result").get("selection").get("edgeIds").size());
            assertEquals(5, d.get("consumed").get("nodes").asInt());
            assertEquals(5, d.get("consumed").get("edges").asInt(), "n1>n2 and n2>n3 (five rows each, one link each), n1>n3, n3>n4, n4>n5");
            assertEquals(50_000, d.get("budget").get("maxNodes").asInt(), "the standard default budget, echoed");
            assertFalse(d.get("cached").asBoolean());
            assertEquals(0, d.get("input").get("hiddenEntities").asInt());

            // the same request again is a cache hit: terminal at submit, answered 200 at once
            JsonNode again = ok(c, "POST", "/inv/graph/runs", run("shortestPath", "\"params\":{\"to\":\"n5\",\"from\":\"n1\"}"), ANALYST);
            assertTrue(again.get("cached").asBoolean());
            assertEquals(d.get("result").get("selection"), again.get("result").get("selection"));
            assertNotEquals(d.get("runId").asText(), again.get("runId").asText());

            // and it is readable by id, with its result
            JsonNode got = ok(c, "GET", "/inv/graph/runs/" + d.get("runId").asText(), null, ANALYST);
            assertEquals("COMPLETED", got.get("status").asText());
            assertEquals(d.get("result").get("selection"), got.get("result").get("selection"));
        }
    }

    @Test
    void weightsCountMakesTheHeavyDirectLinkLoseAndWeightsNoneMakesItWin(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            String p = "\"params\":{\"from\":\"n1\",\"to\":\"n3\",\"direction\":\"out\"}";
            JsonNode counted = ok(c, "POST", "/inv/graph/runs", run("weightedShortestPath", p + ",\"weights\":\"count\""), ANALYST);
            assertEquals(List.of("n1", "n2", "n3"), texts(counted.get("result").get("selection").get("nodeIds")),
                    "the two strong links (count 5 each) beat the weak direct shortcut (count 1)");
            JsonNode flat = ok(c, "POST", "/inv/graph/runs", run("weightedShortestPath", p + ",\"weights\":\"none\""), ANALYST);
            assertEquals(List.of("n1", "n3"), texts(flat.get("result").get("selection").get("nodeIds")), "unweighted: one hop beats two");
            JsonNode sms = ok(c, "POST", "/inv/graph/runs", run("connectedComponents", "\"kinds\":[\"sms\"]"), ANALYST);
            assertEquals(4, sms.get("result").get("groups").size(), "the kinds filter keeps one link: {n3,n4} and three singletons");
            assertEquals(1, sms.get("consumed").get("edges").asInt());
        }
    }

    @Test
    void aRunPastItsInlineWaitIs202WithALocationAndLaterCompletesWithItsResult(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Blocking engine = new Blocking();
        GraphRunRoutes.forTest(engine, 150);
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            HttpResponse<String> r = start(c, run("degreeCentrality", ""), ANALYST);
            JsonNode d = data(r, 202);
            String id = d.get("runId").asText();
            assertEquals("/api/v1/inv/graph/runs/" + id, r.headers().firstValue("Location").orElseThrow());
            assertTrue(Set.of("QUEUED", "RUNNING").contains(d.get("status").asText()), d.toString());
            assertFalse(d.has("result"), "a 202 never carries a result");
            assertTrue(Set.of("QUEUED", "RUNNING").contains(status(c, id, ANALYST)));

            engine.release.countDown();
            until(() -> "COMPLETED".equals(status(c, id, ANALYST)));
            JsonNode done = ok(c, "GET", "/inv/graph/runs/" + id, null, ANALYST);
            assertTrue(done.get("result").get("kind").asText().equals("SCORES") || done.get("result").has("value"), done.toString());
        }
    }

    @Test
    void aWorkingSetAboveTheAlgorithmsInlineCeilingIsNotWaitedFor(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Blocking engine = new Blocking();
        GraphRunRoutes.forTest(engine, 60_000);                       // would wait a minute if the ceiling did not decide
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            int n = Algorithm.BETWEENNESS_CENTRALITY.inlineNodeCeiling() + 100;
            new ViewStore(root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT 'n' || i AS caller, 'n' || (i + 1) AS callee, 'voice' AS channel FROM range(0, " + n + ") t(i)", "2026-09-30T00:00:00Z"));
            ok(c, "POST", "/inv/investigations", CREATE, ANALYST);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i <= n; i++) ids.add("n" + i);
            ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":" + JSON.writeValueAsString(ids) + "}", ANALYST);
            ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", ANALYST);
            long t0 = System.nanoTime();
            HttpResponse<String> r = start(c, run("betweennessCentrality", ""), ANALYST);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            JsonNode d = data(r, 202);
            assertTrue(ms < 30_000, "answered without waiting for the job: " + ms + " ms");
            assertEquals(n + 1, d.get("consumed").get("nodes").asInt());
            assertTrue(r.headers().firstValue("Location").isPresent());
            engine.release.countDown();
        }
    }

    // ── never a silent cap ──────────────────────────────────────────────────────────────────────────────────────

    /** The probe that would otherwise succeed: the SAME request completes with a result when its budget is not tiny. */
    @Test
    void aBudgetExceededRunIs200WithTheNumbersAndNeverAResultKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            String req = run("degreeCentrality", "");
            assertTrue(ok(c, "POST", "/inv/graph/runs", req, ANALYST).has("result"), "the engine handles this graph fine");

            HttpResponse<String> r = start(c, run("degreeCentrality", "\"budget\":{\"maxNodes\":2}"), ANALYST);
            JsonNode d = data(r, 200);
            assertEquals("BUDGET_EXCEEDED", d.get("status").asText());
            assertEquals("NODES", d.get("exceeded").asText());
            assertEquals(2, d.get("budget").get("maxNodes").asInt());
            assertEquals(5, d.get("consumed").get("nodes").asInt(), "the measured size");
            assertTrue(d.get("reason").asText().contains("5 nodes") && d.get("reason").asText().contains("2"), d.get("reason").asText());
            assertFalse(d.has("result"), d.toString());
            assertFalse(r.body().contains("\"result\""), "no result payload anywhere in the body: " + r.body());
            assertFalse(r.body().contains("\"scores\""), r.body());

            JsonNode again = ok(c, "GET", "/inv/graph/runs/" + d.get("runId").asText(), null, ANALYST);
            assertEquals("BUDGET_EXCEEDED", again.get("status").asText());
            assertFalse(again.has("result"));

            JsonNode edges = data(start(c, run("degreeCentrality", "\"budget\":{\"maxEdges\":1}"), ANALYST), 200);
            assertEquals("EDGES", edges.get("exceeded").asText());
            assertFalse(edges.has("result"));
        }
    }

    @Test
    void aRequestAboveTheServerCeilingIsClampedAndSaysSo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            JsonNode d = ok(c, "POST", "/inv/graph/runs", run("degreeCentrality", "\"budget\":{\"maxNodes\":2000000000}"), ANALYST);
            assertEquals(500_000, d.get("budget").get("maxNodes").asInt());
            assertTrue(d.get("budgetClamped").asBoolean());
        }
    }

    @Test
    void theSpacesDefaultBudgetAppliesWhenTheRequestStatesNone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\ngraph_run:\n  max_nodes: 3\n")) {
            investigation(c);
            JsonNode d = ok(c, "POST", "/inv/graph/runs", run("degreeCentrality", ""), ANALYST);
            assertEquals("BUDGET_EXCEEDED", d.get("status").asText());
            assertEquals(3, d.get("budget").get("maxNodes").asInt());
            JsonNode own = ok(c, "POST", "/inv/graph/runs", run("degreeCentrality", "\"budget\":{\"maxNodes\":10}"), ANALYST);
            assertEquals("COMPLETED", own.get("status").asText(), "a request that states its own budget overrides the default");
        }
    }

    // ── 422 · 404 · 403 · 503 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void badRequestsAreRefused422BeforeAnyRunExists(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            for (String bad : List.of(
                    run("noSuchAlgorithm", ""),
                    run("pageRank", "\"params\":{\"damping\":7}"),
                    run("pageRank", "\"params\":{\"bogus\":1}"),
                    run("shortestPath", "\"params\":{\"to\":\"n5\"}"),                    // missing node id
                    run("degreeCentrality", "\"weights\":\"heavy\""),
                    run("degreeCentrality", "\"budget\":{\"maxNodes\":0}"),
                    run("degreeCentrality", "\"budget\":{\"nodes\":5}"),
                    run("degreeCentrality", "\"kinds\":\"voice\""),
                    run("degreeCentrality", "\"at\":999"),                                 // past the head
                    run("degreeCentrality", "\"extra\":1"),
                    "{\"algorithm\":\"degreeCentrality\"}",                                 // no investigationId
                    "{\"investigationId\":\"inv-g\"}")) {                                   // no algorithm
                HttpResponse<String> r = start(c, bad, ANALYST);
                assertEquals(422, r.statusCode(), bad + " -> " + r.body());
            }
            assertEquals(0, ok(c, "GET", "/inv/graph/runs?investigationId=inv-g", null, ANALYST).get("total").asInt(),
                    "a refused request leaves no run behind");
        }
    }

    @Test
    void unknownInvestigationAndUnknownRunAreBothAbsent404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            assertEquals(404, start(c, "{\"investigationId\":\"ghost\",\"algorithm\":\"degreeCentrality\"}", ANALYST).statusCode());
            assertEquals(404, send(c, "GET", "/inv/graph/runs/gr-nope", null, ANALYST).statusCode());
            assertEquals(404, send(c, "POST", "/inv/graph/runs/gr-nope/cancel", null, ANALYST).statusCode());
            assertEquals(404, send(c, "GET", "/inv/graph/runs?investigationId=ghost", null, ANALYST).statusCode());
        }
    }

    @Test
    void startingARunNeedsTheCapabilityButReadingNeedsNone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            HttpResponse<String> denied = start(c, run("degreeCentrality", ""), READER);       // owner, WITHOUT the capability
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canRunLinkGraphAnalysis"), denied.body());
            assertEquals(401, start(c, run("degreeCentrality", ""), "Bearer nobody").statusCode(), "unauthenticated is refused too");

            String id = ok(c, "POST", "/inv/graph/runs", run("degreeCentrality", ""), ANALYST).get("runId").asText();
            assertEquals("COMPLETED", status(c, id, READER), "the same person without the capability may still READ their run");
            assertEquals(1, ok(c, "GET", "/inv/graph/runs?investigationId=inv-g", null, READER).get("total").asInt());
        }
    }

    @Test
    void anotherSubjectCannotStartReadListOrCancelYourRun(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Blocking engine = new Blocking();
        GraphRunRoutes.forTest(engine, 0);
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            String id = data(start(c, run("degreeCentrality", ""), ANALYST), 202).get("runId").asText();

            assertEquals(404, start(c, run("degreeCentrality", ""), OTHER).statusCode(), "not their Investigation: absent");
            assertEquals(404, send(c, "GET", "/inv/graph/runs/" + id, null, OTHER).statusCode(), "not their run: absent, not 403");
            assertEquals(404, send(c, "GET", "/inv/graph/runs?investigationId=inv-g", null, OTHER).statusCode());
            assertEquals(0, ok(c, "GET", "/inv/graph/runs", null, OTHER).get("total").asInt(), "their own list holds none of yours");
            assertEquals(403, send(c, "POST", "/inv/graph/runs/" + id + "/cancel", null, OTHER).statusCode(),
                    "only the starter or an administrator may cancel");
            assertTrue(Set.of("QUEUED", "RUNNING").contains(status(c, id, ANALYST)), "and it is still going");
            engine.release.countDown();
        }
    }

    @Test
    void theStarterAndAnAdministratorCanCancelARunningJobAnd409AfterItFinished(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Blocking engine = new Blocking();
        GraphRunRoutes.forTest(engine, 0);
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            String mine = data(start(c, run("degreeCentrality", ""), ANALYST), 202).get("runId").asText();
            until(() -> "RUNNING".equals(status(c, mine, ANALYST)));
            JsonNode cancelled = data(send(c, "POST", "/inv/graph/runs/" + mine + "/cancel", null, ANALYST), 202);
            assertTrue(cancelled.get("cancelRequested").asBoolean());
            until(() -> "CANCELLED".equals(status(c, mine, ANALYST)));
            assertFalse(ok(c, "GET", "/inv/graph/runs/" + mine, null, ANALYST).has("result"));
            assertEquals(409, send(c, "POST", "/inv/graph/runs/" + mine + "/cancel", null, ANALYST).statusCode(), "already finished");

            String theirs = data(start(c, run("connectedComponents", ""), ANALYST), 202).get("runId").asText();
            JsonNode byAdmin = data(send(c, "POST", "/inv/graph/runs/" + theirs + "/cancel", null, ADMIN), 202);
            assertTrue(byAdmin.get("cancelRequested").asBoolean() || "CANCELLED".equals(byAdmin.get("status").asText()), byAdmin.toString());
            until(() -> "CANCELLED".equals(status(c, theirs, ANALYST)));

            engine.release.countDown();
            JsonNode fin = ok(c, "POST", "/inv/graph/runs", run("bridges", ""), ANALYST);
            assertEquals("COMPLETED", fin.get("status").asText());
            assertEquals(409, send(c, "POST", "/inv/graph/runs/" + fin.get("runId").asText() + "/cancel", null, ADMIN).statusCode());
        }
    }

    @Test
    void aReadOnlyControlPlaneIs503(@TempDir Path cfg) throws Exception {
        subjects();
        try (Ctx c = open(cfg, null, "")) {
            HttpResponse<String> r = start(c, run("degreeCentrality", ""), ANALYST);
            assertEquals(503, r.statusCode(), r.body());
            assertEquals(503, send(c, "GET", "/inv/graph/runs/gr-x", null, ANALYST).statusCode());
            assertEquals(503, send(c, "POST", "/inv/graph/runs/gr-x/cancel", null, ANALYST).statusCode());
        }
    }

    @Test
    void aFullQueueIs503AndALosingAccessReadsAs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Blocking engine = new Blocking();
        GraphRunRoutes.forTest(engine, 0);
        try (Ctx c = open(cfg, root, "masking_mode: none\ngraph_run:\n  threads: 1\n  queue: 1\n")) {
            investigation(c);
            String first = data(start(c, run("degreeCentrality", ""), ANALYST), 202).get("runId").asText();
            until(() -> "RUNNING".equals(status(c, first, ANALYST)));
            data(start(c, run("connectedComponents", ""), ANALYST), 202);              // fills the one waiting place
            HttpResponse<String> full = start(c, run("bridges", ""), ANALYST);
            assertEquals(503, full.statusCode(), full.body());
            assertTrue(full.body().contains("STORE_BUSY"), full.body());

            // a caller who loses the Investigation loses the run too (access is the Investigation's)
            new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds",
                    Map.of("view", "calls_view", "owner", "analyst-9", "shares", List.of()));
            assertEquals(404, send(c, "GET", "/inv/graph/runs/" + first, null, ANALYST).statusCode());
            engine.release.countDown();
        }
    }

    // ── masking ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void maskedResultsCarryNoRawEntityIdAndNoRawEdgeEndpointAndTheEdgeIdsAreTheMaskedLinkIds(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            investigation(c);
            JsonNode entities = ok(c, "GET", INV + "/working-set?of=entities", null, ANALYST).get("rows");
            JsonNode links = ok(c, "GET", INV + "/working-set?of=links", null, ANALYST).get("rows");
            Set<String> maskedIds = new HashSet<>();
            for (JsonNode e : entities) maskedIds.add(e.get("entityId").asText());
            Set<String> servedLinkIds = new HashSet<>();
            for (JsonNode l : links) servedLinkIds.add(l.get("linkId").asText());
            assertTrue(maskedIds.stream().allMatch(s -> s.startsWith("masked:")), maskedIds.toString());
            String from = maskedIds.stream().sorted().findFirst().orElseThrow();

            List<String> bodies = new ArrayList<>();
            for (String[] q : new String[][] {
                    {"neighborhood", "\"params\":{\"node\":\"" + from + "\",\"hops\":3}"},      // sub-graph: nodes, edges, wire ids
                    {"degreeCentrality", ""}, {"pageRank", ""}, {"connectedComponents", ""}, {"bridges", ""},
                    {"articulationPoints", ""}, {"louvainCommunities", ""}, {"detectCommunities", ""}, {"linkPrediction", ""},
                    {"suspicionScore", ""}, {"hits", ""}, {"findCycles", ""}, {"cliques", "\"params\":{\"minSize\":2}"},
                    {"maximumSpanningForest", ""}}) {
                HttpResponse<String> r = start(c, run(q[0], q[1]), ANALYST);
                assertEquals(200, r.statusCode(), q[0] + " -> " + r.body());
                bodies.add(r.body());
                JsonNode d = JSON.readTree(r.body()).get("data");
                assertEquals("COMPLETED", d.get("status").asText(), r.body());
                assertEquals("all", d.get("masking").get("mode").asText());
                collectWire(d, servedLinkIds, q[0]);
            }
            for (String b : bodies) {
                for (String raw : NODES) assertFalse(b.contains("\"" + raw + "\""), "raw id " + raw + " in " + b);
                for (String s : strings(JSON.readTree(b)))
                    if (s.startsWith(LinkIds.PREFIX))
                        for (String part : LinkIds.decode(s))
                            assertFalse(NODES.contains(part), "raw endpoint " + part + " inside the edge id " + s);
            }
            // a ranking names the pseudonyms the Working Set shows
            JsonNode scores = JSON.readTree(bodies.get(1)).get("data").get("result").get("scores");
            for (JsonNode s : scores) assertTrue(maskedIds.contains(s.get("id").asText()), s.toString());

            // GET renders masked too; and a pseudonym is accepted back as a node-id parameter
            String id = JSON.readTree(bodies.get(1)).get("data").get("runId").asText();
            String got = send(c, "GET", "/inv/graph/runs/" + id, null, ANALYST).body();
            for (String raw : NODES) assertFalse(got.contains("\"" + raw + "\""), got);
            JsonNode path = ok(c, "POST", "/inv/graph/runs", run("shortestPath", "\"params\":{\"from\":\"" + from + "\",\"to\":\""
                    + maskedIds.stream().sorted().reduce((a, b) -> b).orElseThrow() + "\"}"), ANALYST);
            assertEquals("COMPLETED", path.get("status").asText());
            assertTrue(path.get("result").get("selection").get("nodeIds").size() >= 2, path.toString());
        }
    }

    /** Every wire id in the response is one the MASKED links relation serves - the ids line up with what the analyst sees. */
    private static void collectWire(JsonNode d, Set<String> served, String what) {
        for (String s : strings(d))
            if (s.startsWith(LinkIds.PREFIX))
                assertTrue(served.contains(s), what + ": edge id " + s + " is not a linkId the masked Working Set serves " + served);
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isTextual()) out.add(n.asText());
        n.forEach(ch -> out.addAll(strings(ch)));
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    // ── audit ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theLifeOfARunIsAudited_withoutItsParams(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            investigation(c);
            JsonNode done = ok(c, "POST", "/inv/graph/runs", run("shortestPath", "\"params\":{\"from\":\"n1\",\"to\":\"n5\"}"), ANALYST);
            JsonNode over = ok(c, "POST", "/inv/graph/runs", run("degreeCentrality", "\"budget\":{\"maxNodes\":2}"), ANALYST);
            until(() -> seen.stream().filter(e -> e.type().startsWith("LINK_GRAPH_RUN_")).count() >= 4);

            String id = done.get("runId").asText();
            Event started = find(seen, LinkEventTypes.LINK_GRAPH_RUN_STARTED, id);
            Event completed = find(seen, LinkEventTypes.LINK_GRAPH_RUN_COMPLETED, id);
            assertEquals("inv-g", completed.attributes().get("investigationId"));
            assertEquals("shortestPath", completed.attributes().get("algorithm"));
            assertEquals("5", completed.attributes().get("nodes"));
            assertEquals("memory", completed.attributes().get("engine"));
            assertTrue(completed.attributes().containsKey("elapsedMs"));
            assertEquals(started.attributes().get("key"), completed.attributes().get("key"));
            assertFalse(String.valueOf(completed.attributes().get("key")).isBlank());

            Event exceeded = find(seen, LinkEventTypes.LINK_GRAPH_RUN_BUDGET_EXCEEDED, over.get("runId").asText());
            assertEquals("NODES", exceeded.attributes().get("exceeded"));
            assertEquals("2", exceeded.attributes().get("maxNodes"));

            for (Event e : seen)
                if (e.type().startsWith("LINK_GRAPH_RUN_"))
                    for (var a : e.attributes().entrySet())
                        assertFalse(a.getKey().equals("params") || NODES.contains(String.valueOf(a.getValue())),
                                "an entity id or the params leaked into the audit: " + e.type() + " " + a);
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }

    private static Event find(List<Event> seen, String type, String runId) {
        return seen.stream().filter(e -> type.equals(e.type()) && runId.equals(e.attributes().get("runId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " for " + runId + " in " + seen.stream().map(Event::type).toList()));
    }
}
