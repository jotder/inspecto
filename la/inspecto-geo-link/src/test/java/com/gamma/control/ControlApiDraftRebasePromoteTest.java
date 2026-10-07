package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.LinkEventTypes;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7-5 - rebase (conflict report, confirmation, pinned reads, expiry) and promote (atomic append, four-eyes), over real HTTP with an
 * ARMED Authenticator and six Subjects (see {@link ControlApiDraftsTest}: a Subject-less test leaves every gate open).
 */
class ControlApiDraftRebasePromoteTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String DRAFTS = "/inv/investigations/inv-a/drafts";
    private static final String L = "Bearer lead", A = "Bearer analyst", A2 = "Bearer analyst2", R = "Bearer reviewer",
            S = "Bearer stranger", C = "Bearer casemember";
    private static final String BUILD = "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"kindCol\":\"channel\"}";
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
        AccessDeciders.forTest(null);
        DraftStore.mover = DraftStore.ATOMIC;
        DraftStore.promoteHook = step -> { };
    }

    private static void subjects() {
        Set<String> all = Set.of("canManageIncidents", "canRunLinkGraphAnalysis", "canApproveLinkExpansions",
                "canRevealLinkEntities", "canAuthorAlertRules", "canBuildLinkIndex");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case L -> Optional.of(new Subject("lead-1", all));
            case A -> Optional.of(new Subject("analyst-2", all));
            case A2 -> Optional.of(new Subject("analyst-6", all));
            case R -> Optional.of(new Subject("reviewer-3", all));
            case S -> Optional.of(new Subject("stranger-4", all));
            case C -> Optional.of(new Subject("case-5", all));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('alice-001','bob-002','voice'),('alice-001','carol-003','sms'),"
                            + "('bob-002','dave-004','voice'),('erin-005','frank-006','voice')) AS t(caller,callee,channel)",
                    "2026-10-03T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        return open(configDir, writeRoot, "masking_mode: none\n");
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.request().method() + " " + r.request().uri() + " -> " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        return data(send(c, method, path, body, auth), 200);
    }

    private int status(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body, auth).statusCode();
    }

    private static String message(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    private static String grantBody(String subject, String role) {
        return "{\"subject\":\"" + subject + "\",\"role\":\"" + role + "\"}";
    }

    /** inv-a: seed alice-001 (1), expand (2); analyst-2 and analyst-6 analysts, reviewer-3 a reviewer. */
    private void team(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"alice-001\"]}", L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", L);
        ok(c, "POST", INV + "/members", grantBody("analyst-2", "analyst"), L);
        ok(c, "POST", INV + "/members", grantBody("analyst-6", "analyst"), L);
        ok(c, "POST", INV + "/members", grantBody("reviewer-3", "reviewer"), L);
    }

    private String fork(Ctx c, String who) throws Exception {
        return data(send(c, "POST", DRAFTS, "{}", who), 201).get("draftId").asText();
    }

    private static String d(String draftId, String tail) {
        return DRAFTS + "/" + draftId + tail;
    }

    private void mainOp(Ctx c, String body) throws Exception {
        ok(c, "POST", INV + "/ops", body, L);
    }

    private Path invDir(Ctx c) {
        return c.root().resolve("audit/snapshots/investigations/inv-a");
    }

    private Path draftDir(Ctx c, String id) {
        return invDir(c).resolve("drafts").resolve(id);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lines(Path log) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isRegularFile(log)) return out;
        for (String line : Files.readAllLines(log)) if (!line.isBlank()) out.add(JSON.readValue(line, Map.class));
        return out;
    }

    /** The Draft's state hash by a FULL independent re-fold of (main log's first baseStep entries + the Draft's own log). */
    private String refold(Ctx c, String draftId, int baseStep) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>(lines(invDir(c).resolve("log.jsonl")).subList(0, baseStep));
        all.addAll(lines(draftDir(c, draftId).resolve("log.jsonl")));
        return InvestigationEvaluator.evaluate(all, -1, null).hash();
    }

    private static List<String> kinds(JsonNode report) {
        List<String> out = new ArrayList<>();
        for (JsonNode x : report.get("conflicts")) out.add(x.get("step").asInt() + ":" + x.get("kind").asText());
        return out;
    }

    // -- rebase: the conflict report, confirmation, equivalence ------------------------------------------------

    /** Draft ops (steps 3-6): seed erin (no-op after main), seed frank (superseded), expand all (changed), hide carol (blocked). */
    private String conflictFixture(Ctx c) throws Exception {
        team(c);
        String da = fork(c, A);
        ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
        ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"frank-006\"]}", A);
        ok(c, "POST", d(da, "/ops"), "{\"op\":\"expand\"}", A);
        ok(c, "POST", d(da, "/ops"), "{\"op\":\"hide\",\"ids\":[\"carol-003\"]}", A);
        mainOp(c, "{\"op\":\"seed\",\"ids\":[\"erin-005\",\"frank-006\"]}");
        mainOp(c, "{\"op\":\"seed\",\"ids\":[\"frank-006\"]}");
        mainOp(c, "{\"op\":\"exclude\",\"ids\":[\"dave-004\"],\"reason\":\"r\"}");
        mainOp(c, "{\"op\":\"exclude\",\"ids\":[\"carol-003\"],\"reason\":\"r\"}");
        return da;
    }

    @Test
    void theConflictReportListsOneOpOfEachKindAndRebaseNeedsEachConfirmed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            String da = conflictFixture(c);
            JsonNode report = ok(c, "GET", d(da, "/conflicts"), null, A);
            assertEquals(List.of("3:no-op", "4:superseded", "5:changed", "6:blocked"), kinds(report), report.toString());
            assertEquals(2, report.get("baseStep").asInt());
            assertEquals(6, report.get("mainHead").asInt());
            assertEquals(4, report.get("behind").asInt());
            assertEquals(List.of(4, 6), List.of(report.get("requiresConfirm").get(0).asInt(), report.get("requiresConfirm").get(1).asInt()));
            JsonNode changed = report.get("conflicts").get(2);
            assertTrue(changed.get("oldRows").asInt() > changed.get("newRows").asInt(), "both counts reported: " + changed);
            assertFalse(report.toString().contains("erin-005"), "a report holds steps, kinds and counts - never an id");
            assertEquals(2, ok(c, "GET", d(da, ""), null, A).get("baseStep").asInt(), "the report wrote nothing");

            // Q7: nothing leaves the record silently - the superseded one (and the blocked one) must be named
            HttpResponse<String> none = send(c, "POST", d(da, "/rebase"), "{}", A);
            assertEquals(409, none.statusCode(), none.body());
            assertTrue(message(none).contains("[4, 6]"), message(none));
            assertEquals(409, status(c, "POST", d(da, "/rebase"), "{\"confirm\":[4]}", A), "the blocked step is still unconfirmed");
            assertEquals(422, status(c, "POST", d(da, "/rebase"), "{\"confirm\":[3,4,6]}", A), "a no-op has nothing to confirm");
            assertEquals(409, status(c, "POST", d(da, "/rebase"), "{\"confirm\":[4,6],\"expectHead\":5}", A), "the head is not 5");
            assertEquals(2, ok(c, "GET", d(da, ""), null, A).get("baseStep").asInt(), "no refused rebase changed the Draft");

            JsonNode done = ok(c, "POST", d(da, "/rebase"), "{\"confirm\":[4,6],\"expectHead\":6}", A);
            assertEquals(6, done.get("toBase").asInt());
            assertEquals(2, done.get("carried").asInt());
            JsonNode draft = ok(c, "GET", d(da, ""), null, A);
            assertEquals(6, draft.get("baseStep").asInt());
            assertEquals(8, draft.get("headStep").asInt());
            assertFalse(draft.get("stale").asBoolean());
            assertEquals(1, draft.get("rebases").size());
            List<Map<String, Object>> own = lines(draftDir(c, da).resolve("log.jsonl"));
            assertEquals(List.of(7, 8), own.stream().map(e -> ((Number) e.get("step")).intValue()).toList());
            assertEquals(List.of(3, 5), own.stream().map(e -> ((Number) e.get("rebasedFrom")).intValue()).toList());

            // EQUIVALENCE: the rebased Draft's state is the independent fold of main[1..6] + its own carried ops
            String folded = refold(c, da, 6);
            JsonNode replay = ok(c, "GET", d(da, "/replay"), null, A);
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
            assertEquals(folded, replay.get("workingSet").get("hash").asText());
            assertEquals(folded, own.get(1).get("workingSetHash"));
            assertEquals(folded, done.get("workingSetHash").asText());
            // and the Draft keeps working on the new base
            assertEquals(9, ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"gina-007\"]}", A).get("step").asInt());
        }
    }

    @Test
    void aRebasedDraftWithNoConflictAtAllCarriesEverythingAndUndoneOpsAreCompacted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"frank-006\"]}", A);
            ok(c, "POST", d(da, "/undo"), "{}", A);
            mainOp(c, "{\"op\":\"annotate\",\"ids\":[\"alice-001\"],\"note\":\"main moved\"}");
            JsonNode report = ok(c, "GET", d(da, "/conflicts"), null, A);
            assertEquals(0, report.get("conflicts").size());
            assertEquals(1, report.get("carried").asInt(), "one effective op: the undone one and its undo entry are compacted away");
            ok(c, "POST", d(da, "/rebase"), "{}", A);
            assertEquals(1, lines(draftDir(c, da).resolve("log.jsonl")).size());
            assertEquals(refold(c, da, 3), ok(c, "GET", d(da, "/replay"), null, A).get("workingSet").get("hash").asText());
        }
    }

    @Test
    void aFailedSwapLeavesTheDraftExactlyAsItWas(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
            mainOp(c, "{\"op\":\"seed\",\"ids\":[\"gina-007\"]}");
            byte[] logBefore = Files.readAllBytes(draftDir(c, da).resolve("log.jsonl"));
            byte[] headerBefore = Files.readAllBytes(draftDir(c, da).resolve("header.json"));
            AtomicInteger calls = new AtomicInteger();
            DraftStore.mover = (from, to) -> {
                if (calls.incrementAndGet() == 2) throw new java.io.IOException("injected: the second rename fails");
                DraftStore.ATOMIC.move(from, to);
            };
            HttpResponse<String> failed = send(c, "POST", d(da, "/rebase"), "{}", A);
            assertTrue(failed.statusCode() >= 500, failed.statusCode() + " " + failed.body());
            DraftStore.mover = DraftStore.ATOMIC;
            assertTrue(Files.isDirectory(draftDir(c, da)));
            assertEquals(new String(logBefore), Files.readString(draftDir(c, da).resolve("log.jsonl")));
            assertEquals(new String(headerBefore), Files.readString(draftDir(c, da).resolve("header.json")));
            try (Stream<Path> s = Files.list(invDir(c).resolve("drafts"))) {
                assertEquals(1, s.count(), "no scratch or aside directory left behind");
            }
            ok(c, "POST", d(da, "/rebase"), "{}", A);   // and the retry works
            assertEquals(3, ok(c, "GET", d(da, ""), null, A).get("baseStep").asInt());
        }
    }

    // -- the role matrix ---------------------------------------------------------------------------------------

    @Test
    void theRoleMatrixOverRealHttp(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
            for (String who : List.of(A, L, R)) assertEquals(200, status(c, "GET", d(da, "/conflicts"), null, who), "conflicts as " + who);
            for (String who : List.of(A2, S, C)) assertEquals(404, status(c, "GET", d(da, "/conflicts"), null, who), "conflicts as " + who);
            assertEquals(403, status(c, "POST", d(da, "/rebase"), "{}", L), "a lead does not rewrite another's Draft");
            assertEquals(403, status(c, "POST", d(da, "/rebase"), "{}", R));
            for (String who : List.of(A2, S, C)) assertEquals(404, status(c, "POST", d(da, "/rebase"), "{}", who), "rebase as " + who);
            assertEquals(403, status(c, "POST", d(da, "/promote"), "{}", R), "a reviewer approves, never promotes");
            for (String who : List.of(A2, S, C)) assertEquals(404, status(c, "POST", d(da, "/promote"), "{}", who), "promote as " + who);
            assertEquals(2, lines(invDir(c).resolve("log.jsonl")).size(), "no refused act touched the main log");
            assertEquals(200, status(c, "POST", d(da, "/promote"), "{}", L), "a lead promotes any Draft");
            // D20: a Draft - promoted or not - has no Dossier route
            assertEquals(404, status(c, "GET", d(da, "/dossier"), null, L));
            assertEquals("promoted", ok(c, "GET", d(da, ""), null, L).get("state").asText());
            // and an analyst promotes their own
            String db = fork(c, A2);
            ok(c, "POST", d(db, "/ops"), "{\"op\":\"seed\",\"ids\":[\"frank-006\"]}", A2);
            assertEquals(200, status(c, "POST", d(db, "/promote"), "{}", A2));
        }
    }

    @Test
    void anUnknownOrMalformedDraftIsAbsentOnTheNewRoutes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);   // literal paths: check-authgate-coverage reads them
            String ghost = "/inv/investigations/inv-a/drafts/draft-00000000-0000-0000-0000-000000000000";
            assertEquals(404, status(c, "POST", "/inv/investigations/inv-a/drafts/draft-00000000-0000-0000-0000-000000000000/rebase", "{}", L));
            assertEquals(404, status(c, "POST", "/inv/investigations/inv-a/drafts/draft-00000000-0000-0000-0000-000000000000/promote", "{}", L));
            assertEquals(404, status(c, "GET", ghost + "/conflicts", null, L));
            assertEquals(404, status(c, "POST", "/inv/investigations/inv-a/drafts/draft-00000000-0000-0000-0000-000000000000/rebase", "{}", S));
            assertEquals(404, status(c, "POST", "/inv/investigations/inv-a/drafts/draft-00000000-0000-0000-0000-000000000000/promote", "{}", S));
            for (String tail : List.of("/promote", "/rebase", "/conflicts")) {
                String method = tail.equals("/conflicts") ? "GET" : "POST";
                assertEquals(422, status(c, method, DRAFTS + "/not-a-draft" + tail, "{}", L), "a malformed id is a 422 to a member: " + tail);
                assertEquals(404, status(c, method, DRAFTS + "/not-a-draft" + tail, "{}", S), "and learns a stranger nothing: " + tail);
            }
        }
    }

    // -- promote ---------------------------------------------------------------------------------------------------

    @Test
    void promoteAppendsTheDraftsOpsWithProvenanceAndClosesIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            String da = fork(c, A);
            try {
                ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
                ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"frank-006\"]}", A);
                ok(c, "POST", d(da, "/undo"), "{}", A);
                ok(c, "POST", d(da, "/ops"), "{\"op\":\"expand\",\"ids\":[\"erin-005\"]}", A);
                HttpResponse<String> undone = send(c, "POST", d(da, "/promote"), "{}", A);
                assertEquals(409, undone.statusCode(), "undone steps would renumber the state: " + undone.body());
                assertTrue(message(undone).contains("must rebase"), message(undone));
                ok(c, "POST", d(da, "/rebase"), "{}", A);   // compacts the undone op and its undo away
                String draftState = ok(c, "GET", d(da, "/replay"), null, A).get("workingSet").get("hash").asText();
                JsonNode out = ok(c, "POST", d(da, "/promote"), "{\"expectHead\":2}", A);
                assertEquals(2, out.get("fromStep").asInt());
                assertEquals(4, out.get("toStep").asInt(), "the undone op is not promoted: 2 effective ops");
                List<Map<String, Object>> main = lines(invDir(c).resolve("log.jsonl"));
                assertEquals(4, main.size());
                @SuppressWarnings("unchecked") Map<String, Object> prov = (Map<String, Object>) main.get(2).get("draft");
                assertEquals(da, prov.get("id"));
                assertEquals("analyst-2", prov.get("actor"));
                assertEquals("analyst-2", prov.get("promotedBy"));
                assertEquals(2, ((Number) prov.get("baseStep")).intValue());
                assertEquals(draftState, main.get(3).get("workingSetHash"), "the main log now holds exactly the Draft's state");
                JsonNode replay = data(send(c, "POST", INV + "/replay", "{}", L), 200);
                assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
                assertEquals(draftState, replay.get("workingSet").get("hash").asText());
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            // closed: marker kept, rows gone, no writes, pin released, not counted against D17
            Path dd = draftDir(c, da);
            assertTrue(Files.isRegularFile(dd.resolve("promoted.json")));
            assertFalse(Files.exists(dd.resolve("log.jsonl")));
            assertEquals(409, status(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"x\"]}", A));
            assertEquals(409, status(c, "POST", d(da, "/promote"), "{}", A));
            assertEquals(409, status(c, "POST", d(da, "/discard"), "{}", A));
            assertEquals("promoted", ok(c, "GET", d(da, ""), null, A).get("state").asText());
            assertEquals(0, ok(c, "GET", DRAFTS, null, A).get("items").size());
            assertEquals(1, ok(c, "GET", DRAFTS + "?closed=true", null, A).get("items").size());
            fork(c, A);

            List<Event> mine = seen.stream().filter(e -> e.type().equals(LinkEventTypes.LINK_DRAFT_PROMOTED)).toList();
            assertEquals(1, mine.size());
            assertEquals(da, mine.get(0).attributes().get("draftId"));
            assertEquals("2", mine.get(0).attributes().get("fromStep"));
            assertEquals("4", mine.get(0).attributes().get("toStep"));
            assertFalse(mine.get(0).attributes().toString().contains("erin-005"), "ids only, never rows");
        }
    }

    @Test
    void aMovedHeadIsMustRebaseAndAnEmptyDraftIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            assertEquals(422, status(c, "POST", d(da, "/promote"), "{}", A), "nothing to promote");
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
            mainOp(c, "{\"op\":\"seed\",\"ids\":[\"gina-007\"]}");
            HttpResponse<String> moved = send(c, "POST", d(da, "/promote"), "{}", A);
            assertEquals(409, moved.statusCode(), moved.body());
            assertTrue(message(moved).contains("must rebase"), message(moved));
            assertEquals(3, lines(invDir(c).resolve("log.jsonl")).size(), "a refused promote appended nothing");
            ok(c, "POST", d(da, "/rebase"), "{}", A);
            assertEquals(409, status(c, "POST", d(da, "/promote"), "{\"expectHead\":2}", A), "the report was read at another head");
            assertEquals(200, status(c, "POST", d(da, "/promote"), "{\"expectHead\":3}", A));
            assertEquals(4, lines(invDir(c).resolve("log.jsonl")).size());
        }
    }

    @Test
    void aFailedAppendHalfWayLeavesTheMainLogAndTheDraftUntouched(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"frank-006\"]}", A);
            byte[] before = Files.readAllBytes(invDir(c).resolve("log.jsonl"));
            DraftStore.promoteHook = step -> {
                if (step == 4) throw new IllegalStateException("injected: the second append fails");
            };
            HttpResponse<String> failed = send(c, "POST", d(da, "/promote"), "{}", A);
            assertTrue(failed.statusCode() >= 500, failed.statusCode() + " " + failed.body());
            DraftStore.promoteHook = step -> { };
            assertEquals(new String(before), Files.readString(invDir(c).resolve("log.jsonl")), "step 3 was written, then rolled back");
            assertFalse(Files.exists(invDir(c).resolve("sets").resolve("3.json")), "and its set file");
            assertFalse(Files.exists(draftDir(c, da).resolve("promoted.json")));
            assertEquals("open", ok(c, "GET", d(da, ""), null, A).get("state").asText());
            assertEquals(200, status(c, "POST", d(da, "/promote"), "{}", A), "the retry promotes whole");
            assertEquals(4, lines(invDir(c).resolve("log.jsonl")).size());
        }
    }

    @Test
    void promoteAgainstAConcurrentMainAppendNeverMixesTheTwo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 3; round++) {
                    String da = fork(c, A);
                    ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-00" + (5 + round) + "\"]}", A);
                    ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"frank-00" + (5 + round) + "\"]}", A);
                    int before = lines(invDir(c).resolve("log.jsonl")).size();
                    CountDownLatch go = new CountDownLatch(1);
                    Future<Integer> promote = pool.submit(() -> {
                        go.await();
                        return status(c, "POST", d(da, "/promote"), "{}", A);
                    });
                    Future<Integer> append = pool.submit(() -> {
                        go.await();
                        return status(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"main-" + before + "\"]}", L);
                    });
                    go.countDown();
                    int p = promote.get(), a = append.get();
                    assertEquals(200, a);
                    assertTrue(p == 200 || p == 409, "promote is whole or refused, never partial: " + p);
                    List<Map<String, Object>> main = lines(invDir(c).resolve("log.jsonl"));
                    assertEquals(before + (p == 200 ? 3 : 1), main.size());
                    for (int i = 0; i < main.size(); i++)
                        assertEquals(i + 1, ((Number) main.get(i).get("step")).intValue(), "steps stay contiguous");
                    JsonNode replay = data(send(c, "POST", INV + "/replay", "{}", L), 200);
                    assertTrue(replay.get("equivalent").asBoolean(), "round " + round + ": " + replay);
                    if (p == 409) {   // the Draft survives untouched: rebase + promote lands it
                        ok(c, "POST", d(da, "/rebase"), "{}", A);
                        assertEquals(200, status(c, "POST", d(da, "/promote"), "{}", A));
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // -- four-eyes: promote meets approval -----------------------------------------------------------------------------

    @Test
    void aPromoteCarryingASensitiveExpandIsHeldAndApprovedByAnotherPerson(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"expand\",\"ids\":[\"erin-005\"],\"budget\":500}", A);   // not sensitive: no threshold yet
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: none\nfour_eyes_budget_above: 100\n");   // the policy tightens
            HttpResponse<String> held = send(c, "POST", d(da, "/promote"), "{}", A);
            assertEquals(202, held.statusCode(), held.body());
            JsonNode pending = JSON.readTree(held.body()).get("data").get("pending");
            assertEquals("promote", pending.get("kind").asText());
            assertEquals("analyst-2", pending.get("requestedBy").asText());
            assertEquals(2, lines(invDir(c).resolve("log.jsonl")).size(), "nothing was appended before approval");
            assertEquals("open", ok(c, "GET", d(da, ""), null, A).get("state").asText());
            String rid = pending.get("id").asText();

            assertEquals(403, status(c, "POST", INV + "/pending/" + rid + "/approve", "{}", A), "a requester never approves their own");
            assertEquals(404, status(c, "POST", INV + "/pending/" + rid + "/approve", "{}", S));
            // a deny leaves the Draft open and the main log alone
            assertEquals(200, status(c, "POST", INV + "/pending/" + rid + "/deny", "{\"reason\":\"not now\"}", R));
            assertEquals(2, lines(invDir(c).resolve("log.jsonl")).size());
            assertEquals("open", ok(c, "GET", d(da, ""), null, A).get("state").asText());

            JsonNode again = JSON.readTree(send(c, "POST", d(da, "/promote"), "{}", A).body()).get("data").get("pending");
            JsonNode approved = ok(c, "POST", INV + "/pending/" + again.get("id").asText() + "/approve", "{}", R);
            assertEquals(4, approved.get("toStep").asInt());
            List<Map<String, Object>> main = lines(invDir(c).resolve("log.jsonl"));
            assertEquals(4, main.size());
            @SuppressWarnings("unchecked") Map<String, Object> approval = (Map<String, Object>) main.get(3).get("approval");
            assertEquals("reviewer-3", approval.get("approvedBy"));
            assertEquals("analyst-2", approval.get("requestedBy"));
            assertFalse(main.get(2).containsKey("approval"), "only the sensitive step carries the approval");
            assertEquals("promoted", ok(c, "GET", d(da, ""), null, A).get("state").asText());
        }
    }

    // -- the index: pinned reads, expiry -------------------------------------------------------------------------------

    private void build(Ctx c) throws Exception {
        String id = data(send(c, "POST", "/inv/index/builds", BUILD, L), 202).get("buildId").asText();
        long end = System.nanoTime() + 60_000_000_000L;
        while (true) {
            String st = ok(c, "GET", "/inv/index/builds/" + id, null, L).get("status").asText();
            if (st.equals("FAILED") || st.equals("CANCELLED")) throw new AssertionError("build " + st);
            if (st.equals("COMPLETED")) return;
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for the build");
            Thread.sleep(10);
        }
    }

    private static List<Path> pinFiles(Ctx c) throws Exception {
        Path idx = c.root().resolve("la-index");
        try (Stream<Path> s = Files.walk(idx)) {
            return s.filter(p -> p.getFileName().toString().equals("pins.json")).toList();
        }
    }

    private static void expirePins(Ctx c) throws Exception {
        for (Path p : pinFiles(c)) {
            ObjectNode n = (ObjectNode) JSON.readTree(Files.readString(p));
            for (JsonNode pin : n.get("pins")) ((ObjectNode) pin).put("expiresAt", "2020-01-01T00:00:00Z");
            Files.writeString(p, JSON.writeValueAsString(n));
        }
    }

    @Test
    void aDraftsExpandReadsItsPinnedVersionAndARebaseRepinsCurrent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\nindex:\n  enabled: true\n")) {
            team(c);
            build(c);
            String da = fork(c, A);
            assertEquals(1, ok(c, "GET", d(da, ""), null, A).get("pins").get("index").get("calls_ds").asLong());
            build(c);   // CURRENT is now v2; the Draft stays on v1
            JsonNode own = ok(c, "POST", d(da, "/ops"), "{\"op\":\"expand\",\"ids\":[\"bob-002\"]}", A);
            assertEquals(1, own.get("read").get("index").get("version").asInt(), "the Draft's expand reads ITS pinned version: " + own);
            JsonNode main = ok(c, "POST", INV + "/ops", "{\"op\":\"expand\",\"ids\":[\"carol-003\"]}", L);
            assertEquals(2, main.get("read").get("index").get("version").asInt(), "the main log reads CURRENT: " + main);

            // the rebase re-seals against CURRENT and re-pins it
            JsonNode done = ok(c, "POST", d(da, "/rebase"), "{\"confirm\":[]}", A);
            assertEquals(2, ok(c, "GET", d(da, ""), null, A).get("pins").get("index").get("calls_ds").asLong(), done.toString());
            List<Map<String, Object>> carried = lines(draftDir(c, da).resolve("log.jsonl"));
            @SuppressWarnings("unchecked") Map<String, Object> ix = (Map<String, Object>) ((Map<String, Object>) carried.get(0).get("read")).get("index");
            assertEquals(2, ((Number) ix.get("version")).intValue());
        }
    }

    @Test
    void anExpiredPinRefusesWritesAndPromoteWithMustRebaseUntilARebaseRenewsIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\nindex:\n  enabled: true\n")) {
            team(c);
            build(c);
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
            assertFalse(ok(c, "GET", d(da, ""), null, A).get("pinWarning").asBoolean());
            expirePins(c);
            JsonNode draft = ok(c, "GET", d(da, ""), null, A);
            assertTrue(draft.get("pinExpiry").get(0).get("expired").asBoolean());
            HttpResponse<String> w = send(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"frank-006\"]}", A);
            assertEquals(409, w.statusCode(), w.body());
            assertTrue(message(w).contains("must rebase"), message(w));
            HttpResponse<String> p = send(c, "POST", d(da, "/promote"), "{}", A);
            assertEquals(409, p.statusCode(), p.body());
            assertTrue(message(p).contains("must rebase"), message(p));
            assertEquals(2, lines(invDir(c).resolve("log.jsonl")).size());
            assertEquals(200, status(c, "POST", d(da, "/undo"), "{}", A), "an undo reads nothing, so it is not refused");
            ok(c, "POST", d(da, "/rebase"), "{}", A);   // at the current head: the refresh
            assertFalse(ok(c, "GET", d(da, ""), null, A).get("pinExpiry").get(0).get("expired").asBoolean());
            assertEquals(200, status(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"frank-006\"]}", A));
        }
    }

    @Test
    void everyRebaseAndPromoteIsAuditedWithIdsAndCountsOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            String da;
            try {
                da = fork(c, A);
                ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
                mainOp(c, "{\"op\":\"seed\",\"ids\":[\"gina-007\"]}");
                ok(c, "POST", d(da, "/rebase"), "{}", A);
                ok(c, "POST", d(da, "/promote"), "{}", A);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> mine = seen.stream().filter(e -> e.type().equals(LinkEventTypes.LINK_DRAFT_REBASED) || e.type().equals(LinkEventTypes.LINK_DRAFT_PROMOTED)).toList();
            assertEquals(List.of(LinkEventTypes.LINK_DRAFT_REBASED, LinkEventTypes.LINK_DRAFT_PROMOTED), mine.stream().map(Event::type).toList());
            assertEquals("2", mine.get(0).attributes().get("fromBase"));
            assertEquals("3", mine.get(0).attributes().get("toBase"));
            assertEquals("1", mine.get(0).attributes().get("carried"));
            for (Event e : mine) {
                assertEquals(da, e.attributes().get("draftId"));
                assertEquals("analyst-2", e.attributes().get("actor"));
                assertFalse(e.attributes().toString().contains("erin-005"), "never row content: " + e.attributes());
            }
            assertNotEquals(null, mine.get(1).attributes().get("toStep"));
        }
    }
}
