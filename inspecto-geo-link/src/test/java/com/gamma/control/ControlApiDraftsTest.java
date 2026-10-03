package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.storage.IndexStore;
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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7-3 - Drafts, over real HTTP with an ARMED Authenticator and SIX Subjects (a Subject-less test makes
 * {@code withCapability} a no-op and every gate open, so nothing would be proven). Every Subject holds the same
 * capabilities, so what separates them is ONLY the membership role and whose Draft it is.
 */
class ControlApiDraftsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String DRAFTS = "/inv/investigations/inv-a/drafts";   // a LITERAL path per route: check-authgate-coverage reads them
    private static final String L = "Bearer lead", A = "Bearer analyst", A2 = "Bearer analyst2", R = "Bearer reviewer",
            S = "Bearer stranger", C = "Bearer casemember";
    private static final String SEED_E = "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        CaseTeamObjectEngine.CASES = null;
        Authenticators.forTest(null);
        AccessDeciders.forTest(null);
        DraftStore.mover = DraftStore.ATOMIC;
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

    /** inv-a: seed alice-001 (step 1), expand (step 2); analyst-2 and analyst-6 analysts, reviewer-3 a reviewer. */
    private void team(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"alice-001\"]}", L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", L);
        ok(c, "POST", INV + "/members", grantBody("analyst-2", "analyst"), L);
        ok(c, "POST", INV + "/members", grantBody("analyst-6", "analyst"), L);
        ok(c, "POST", INV + "/members", grantBody("reviewer-3", "reviewer"), L);
    }

    private String fork(Ctx c, String who, String body) throws Exception {
        return data(send(c, "POST", DRAFTS, body, who), 201).get("draftId").asText();
    }

    private static String d(String draftId, String tail) {
        return DRAFTS + "/" + draftId + tail;
    }

    private Path invDir(Ctx c) {
        return c.root().resolve("audit/snapshots/investigations/inv-a");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lines(Path log, int limit) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isRegularFile(log)) return out;
        for (String line : Files.readAllLines(log)) {
            if (line.isBlank() || out.size() >= limit) continue;
            out.add(JSON.readValue(line, Map.class));
        }
        return out;
    }

    /** The Draft's state hash by a FULL re-fold of (the main log's first baseStep entries + the Draft's own log) - independent of the routes. */
    private String refold(Ctx c, String draftId, int baseStep) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>(lines(invDir(c).resolve("log.jsonl"), baseStep));
        all.addAll(lines(invDir(c).resolve("drafts").resolve(draftId).resolve("log.jsonl"), Integer.MAX_VALUE));
        return InvestigationEvaluator.evaluate(all, -1, null).hash();
    }

    // -- the matrix -----------------------------------------------------------------------------------------------

    @Test
    void eachRoleReachesExactlyItsDraftRoutes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "someone", "case-5", false));
        try (Ctx c = open(cfg, root)) {
            team(c);
            ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", L);

            // fork: a lead and an analyst; never a reviewer (403: they know the Investigation exists); a stranger and a Case member see absence
            assertEquals(403, status(c, "POST", DRAFTS, "{}", R));
            assertEquals(404, status(c, "POST", DRAFTS, "{}", S));
            assertEquals(404, status(c, "POST", DRAFTS, "{}", C));
            String dl = fork(c, L, "{}"), da = fork(c, A, "{}"), db = fork(c, A2, "{}");
            assertEquals(3, new HashSet<>(List.of(dl, da, db)).size());

            // list: a lead and a reviewer see all (D7-Q8); an analyst only their own; strangers and Case members nothing
            assertEquals(3, ok(c, "GET", DRAFTS, null, L).get("items").size());
            assertEquals(3, ok(c, "GET", DRAFTS, null, R).get("items").size());
            JsonNode mine = ok(c, "GET", DRAFTS, null, A).get("items");
            assertEquals(1, mine.size());
            assertEquals(da, mine.get(0).get("draftId").asText());
            assertEquals("analyst-2", mine.get(0).get("actor").asText());
            assertEquals(2, mine.get(0).get("baseStep").asInt());
            assertEquals(404, status(c, "GET", DRAFTS, null, S));
            assertEquals(404, status(c, "GET", DRAFTS, null, C));

            // reads of A's Draft: A, the lead and the reviewer; NOT the peer analyst (404 - absent to them), the stranger, the Case member
            for (String tail : List.of("", "/log", "/working-set", "/replay")) {
                for (String who : List.of(A, L, R)) assertEquals(200, status(c, "GET", d(da, tail), null, who), tail + " as " + who);
                for (String who : List.of(A2, S, C)) assertEquals(404, status(c, "GET", d(da, tail), null, who), tail + " as " + who);
            }

            // writes to A's Draft: only A. The lead and the reviewer see it exist (403); the peer and the stranger do not (404)
            assertEquals(403, status(c, "POST", d(da, "/ops"), SEED_E, L), "a lead does NOT write another's Draft");
            assertEquals(403, status(c, "POST", d(da, "/ops"), SEED_E, R));
            assertEquals(404, status(c, "POST", d(da, "/ops"), SEED_E, A2));
            assertEquals(404, status(c, "POST", d(da, "/ops"), SEED_E, S));
            assertEquals(404, status(c, "POST", d(da, "/ops"), SEED_E, C));
            assertEquals(0, lines(invDir(c).resolve("drafts").resolve(da).resolve("log.jsonl"), 9).size(), "no refused write reached the log");
        }
    }

    @Test
    void theActorWritesAndTheRefusedUndoDiscardRows(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}"), db = fork(c, A2, "{}");
            JsonNode step = ok(c, "POST", d(da, "/ops"), SEED_E, A);
            assertEquals(3, step.get("step").asInt(), "a Draft's steps continue the main numbering: base 2, first own step 3");
            assertEquals(da, step.get("draftId").asText());
            for (String who : List.of(L, R)) assertEquals(403, status(c, "POST", d(da, "/undo"), "{}", who), "undo as " + who);
            for (String who : List.of(A2, S)) assertEquals(404, status(c, "POST", d(da, "/undo"), "{}", who), "undo as " + who);
            assertEquals(1, ok(c, "GET", d(da, ""), null, A).get("steps").asInt(), "no refused undo reached the log");
            assertEquals(200, status(c, "POST", d(da, "/undo"), "{}", A));

            // discard: the actor or a lead. A reviewer 403, a peer analyst and a stranger 404 (the Draft is absent to them)
            assertEquals(403, status(c, "POST", d(db, "/discard"), "{}", R));
            assertEquals(404, status(c, "POST", d(db, "/discard"), "{}", A));
            assertEquals(404, status(c, "POST", d(db, "/discard"), "{}", S));
            assertEquals("open", ok(c, "GET", d(db, ""), null, L).get("state").asText());
            assertEquals(200, status(c, "POST", d(db, "/discard"), "{}", L), "a lead discards another member's Draft");
            assertEquals(200, status(c, "POST", d(da, "/discard"), "{}", A), "the actor discards their own");
        }
    }

    // -- one Draft per member -------------------------------------------------------------------------------------

    @Test
    void oneLiveDraftPerMemberAndADiscardedOneDoesNotCount(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String first = fork(c, A, "{}");
            HttpResponse<String> again = send(c, "POST", DRAFTS, "{}", A);
            assertEquals(409, again.statusCode(), again.body());
            assertTrue(message(again).contains(first), "the 409 names the existing Draft: " + message(again));
            assertEquals(1, ok(c, "GET", DRAFTS, null, A).get("items").size());
            String other = fork(c, A2, "{}");    // another member is not affected
            assertNotEquals(first, other);
            ok(c, "POST", d(first, "/discard"), "{}", A);
            assertEquals(0, ok(c, "GET", DRAFTS, null, A).get("items").size());
            assertEquals(1, ok(c, "GET", DRAFTS + "?discarded=true", null, A).get("items").size());
            String second = fork(c, A, "{}");
            assertNotEquals(first, second);
        }
    }

    @Test
    void forkAtAnEarlierStepAndABadStepIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            assertEquals(422, status(c, "POST", DRAFTS, "{\"at\":99}", A));
            assertEquals(422, status(c, "POST", DRAFTS, "{\"at\":-1}", A));
            assertEquals(422, status(c, "POST", DRAFTS, "{\"at\":\"two\"}", A));
            assertEquals(0, ok(c, "GET", DRAFTS, null, A).get("items").size(), "a refused fork wrote nothing");
            String da = fork(c, A, "{\"at\":1}");
            JsonNode draft = ok(c, "GET", d(da, ""), null, A);
            assertEquals(1, draft.get("baseStep").asInt());
            assertEquals(1, draft.get("behind").asInt());
            assertTrue(draft.get("stale").asBoolean());
            // its state at the fork is the main log at step 1: one entity, no hop-1 neighbours
            assertEquals(1, ok(c, "GET", d(da, "/working-set"), null, A).get("total").asInt());
            assertEquals(2, ok(c, "GET", INV + "/working-set?at=2", null, L).get("head").get("step").asInt());
        }
    }

    // -- the base-state rule and the equivalence proof ------------------------------------------------------------

    @Test
    void aDraftsWorkingSetIsTheFullRefoldOfTheMainPrefixPlusItsOwnLog(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            // at the fork the Draft's state IS the main log's state at step 2
            JsonNode atFork = ok(c, "GET", d(da, "/working-set"), null, A);
            JsonNode mainAt2 = ok(c, "GET", INV + "/working-set?at=2", null, L);
            assertEquals(mainAt2.get("head").get("workingSetHash").asText(), atFork.get("head").get("workingSetHash").asText());
            assertEquals(refold(c, da, 2), atFork.get("head").get("workingSetHash").asText());

            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"expand\",\"ids\":[\"erin-005\"]}", A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"hide\",\"ids\":[\"carol-003\"]}", A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"exclude\",\"ids\":[\"bob-002\"],\"reason\":\"not relevant\"}", A);
            ok(c, "POST", d(da, "/undo"), "{}", A);          // reverts the exclude, never a main step

            String expected = refold(c, da, 2);
            for (String of : List.of("entities", "links", "excluded")) {
                JsonNode ws = ok(c, "GET", d(da, "/working-set?of=" + of), null, A);
                assertEquals(expected, ws.get("head").get("workingSetHash").asText(), of);
                assertEquals(7, ws.get("head").get("step").asInt(), "steps 3.. are the Draft's: seed, expand, hide, exclude, undo");
            }
            JsonNode entities = ok(c, "GET", d(da, "/working-set?of=entities"), null, A);
            Set<String> ids = new HashSet<>();
            for (JsonNode row : entities.get("rows")) ids.add(row.get("entityId").asText());
            assertEquals(Set.of("alice-001", "bob-002", "carol-003", "erin-005", "frank-006"), ids);
            JsonNode replay = ok(c, "GET", d(da, "/replay"), null, A);
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
            assertEquals(0, replay.get("mismatches").size());
            assertEquals(0, replay.get("setMismatches").size());
            assertEquals(expected, replay.get("workingSet").get("hash").asText());
            // the Draft's log lists its OWN entries only
            JsonNode log = ok(c, "GET", d(da, "/log"), null, A);
            assertEquals(5, log.get("total").asInt());
            assertEquals(3, log.get("entries").get(0).get("step").asInt());
            assertEquals(2, log.get("baseStep").asInt());

            // a Draft undo reverts its OWN latest op; with none left it refuses and the main prefix is untouched
            String db = fork(c, A2, "{}");
            assertEquals(409, status(c, "POST", d(db, "/undo"), "{}", A2), "nothing of the Draft's own to undo");
            assertEquals(2, lines(invDir(c).resolve("log.jsonl"), 99).size(), "the main log is untouched by every Draft write");
        }
    }

    @Test
    void theMainLogMovingAfterTheForkDoesNotChangeTheDraftAndIsReportedBehind(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            String hashBefore = ok(c, "GET", d(da, "/working-set"), null, A).get("head").get("workingSetHash").asText();
            assertFalse(ok(c, "GET", d(da, ""), null, A).get("stale").asBoolean());

            ok(c, "POST", INV + "/ops", SEED_E, L);                       // main step 3
            ok(c, "POST", INV + "/ops", "{\"op\":\"hide\",\"ids\":[\"bob-002\"]}", L);   // main step 4
            JsonNode draft = ok(c, "GET", d(da, ""), null, A);
            assertTrue(draft.get("stale").asBoolean());
            assertEquals(2, draft.get("behind").asInt());
            assertEquals(4, draft.get("mainHead").asInt());
            assertTrue(draft.get("baseIntact").asBoolean());
            assertEquals(hashBefore, ok(c, "GET", d(da, "/working-set"), null, A).get("head").get("workingSetHash").asText(),
                    "the base is pinned at the fork step");
            // the Draft keeps working on its own base: its first own step is still 3, and main's step 3 is not in it
            JsonNode step = ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"dave-004\"]}", A);
            assertEquals(3, step.get("step").asInt());
            assertEquals(refold(c, da, 2), ok(c, "GET", d(da, "/working-set"), null, A).get("head").get("workingSetHash").asText());
            Set<String> ids = new HashSet<>();
            for (JsonNode row : ok(c, "GET", d(da, "/working-set?of=entities"), null, A).get("rows")) ids.add(row.get("entityId").asText());
            assertFalse(ids.contains("erin-005"), "a main step taken after the fork is not in the Draft");
            assertTrue(ids.contains("dave-004"));
            // ...and the Draft's op is not in the main Working Set
            Set<String> mainIds = new HashSet<>();
            for (JsonNode row : ok(c, "GET", INV + "/working-set?of=entities", null, L).get("rows")) mainIds.add(row.get("entityId").asText());
            assertTrue(mainIds.contains("erin-005"));
            assertFalse(mainIds.contains("dave-004"));
            assertTrue(ok(c, "GET", d(da, "/replay"), null, A).get("equivalent").asBoolean());
        }
    }

    @Test
    void aMainPrefixRewrittenOnDiskFailsTheDraftClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            Path main = invDir(c).resolve("log.jsonl");
            String log = Files.readString(main);
            Files.writeString(main, log.replaceFirst("alice-001", "alice-002"));
            assertEquals(409, status(c, "GET", d(da, "/working-set"), null, A));
            assertEquals(409, status(c, "POST", d(da, "/ops"), SEED_E, A));
            assertFalse(ok(c, "GET", d(da, ""), null, A).get("baseIntact").asBoolean(), "the header still answers and says why");
        }
    }

    // -- D7-4: checkpointed append ----------------------------------------------------------------------------------

    @Test
    void aDraftAppendResumesFromItsCheckpointAndDoesNotRefoldTheLog(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            ok(c, "POST", d(da, "/ops"), SEED_E, A);                       // the first write may take the one cold fold
            ok(c, "POST", INV + "/ops", "{\"op\":\"hide\",\"ids\":[\"bob-002\"]}", L);   // the MAIN log moves (its own folds are not under test): the Draft's base is re-verified, not re-folded
            long folds = InvestigationEvaluator.foldCount();
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"expand\",\"ids\":[\"erin-005\"]}", A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"hide\",\"ids\":[\"carol-003\"]}", A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"exclude\",\"ids\":[\"bob-002\"],\"reason\":\"not relevant\"}", A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"keep\",\"ids\":[\"alice-001\"]}", A);
            assertEquals(folds, InvestigationEvaluator.foldCount(), "four Draft appends fold nothing: they resume from the last checkpoint");
            // equivalence: the incremental state IS the independent full re-fold, and replay agrees
            assertEquals(refold(c, da, 2), ok(c, "GET", d(da, "/working-set"), null, A).get("head").get("workingSetHash").asText());
            JsonNode replay = ok(c, "GET", d(da, "/replay"), null, A);
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
            assertEquals(refold(c, da, 2), replay.get("workingSet").get("hash").asText());
            // an undo cannot pop an incremental state: it folds, re-seeds the checkpoint, and the next op resumes from it
            ok(c, "POST", d(da, "/undo"), "{}", A);
            long afterUndo = InvestigationEvaluator.foldCount();
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"hide\",\"ids\":[\"erin-005\"]}", A);
            assertEquals(afterUndo, InvestigationEvaluator.foldCount());
            assertEquals(refold(c, da, 2), ok(c, "GET", d(da, "/working-set"), null, A).get("head").get("workingSetHash").asText());
        }
    }

    @Test
    void aWarmCheckpointNeverHidesATamperedMainPrefixOrDraftLog(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"hide\",\"ids\":[\"carol-003\"]}", A);   // base verdict and state checkpoint are both warm
            Path main = invDir(c).resolve("log.jsonl");
            String log = Files.readString(main);
            Files.writeString(main, log.replaceFirst("alice-001", "alice-0011"));   // a prefix rewrite that changes the file
            assertEquals(409, status(c, "POST", d(da, "/ops"), "{\"op\":\"hide\",\"ids\":[\"dave-004\"]}", A));
            assertEquals(409, status(c, "GET", d(da, "/replay"), null, A));
            Files.writeString(main, log);                                           // restored: the Draft works again and is still equivalent
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"keep\",\"ids\":[\"alice-001\"]}", A);
            assertTrue(ok(c, "GET", d(da, "/replay"), null, A).get("equivalent").asBoolean());
            // a Draft log edited behind the checkpoint's back is a miss, not a stale hit: replay sees the tamper
            Path own = invDir(c).resolve("drafts").resolve(da).resolve("log.jsonl");
            Files.writeString(own, Files.readString(own).replaceFirst("carol-003", "carol-0033"));
            assertFalse(ok(c, "GET", d(da, "/replay"), null, A).get("equivalent").asBoolean(), "the tampered Draft log no longer replays to its recorded hashes");
        }
    }

    // -- fork atomicity, pins -------------------------------------------------------------------------------------

    private static final String BUILD = "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"kindCol\":\"channel\"}";

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void until(Check c, String what) throws Exception {
        long end = System.nanoTime() + 60_000_000_000L;
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private void build(Ctx c) throws Exception {
        String id = data(send(c, "POST", "/inv/index/builds", BUILD, L), 202).get("buildId").asText();
        until(() -> {
            String st = ok(c, "GET", "/inv/index/builds/" + id, null, L).get("status").asText();
            if (st.equals("FAILED") || st.equals("CANCELLED")) throw new AssertionError("build " + st);
            return st.equals("COMPLETED");
        }, "index build " + id);
    }

    private static List<Path> pinFiles(Ctx c) throws Exception {
        Path idx = c.root().resolve("la-index");
        if (!Files.isDirectory(idx)) return List.of();
        try (Stream<Path> s = Files.walk(idx)) {
            return s.filter(p -> p.getFileName().toString().equals("pins.json")).toList();
        }
    }

    private static String pinsText(Ctx c) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Path p : pinFiles(c)) b.append(Files.readString(p));
        return b.toString();
    }

    private static IndexStore store(Ctx c, int keep) {
        Path idx = c.root().resolve("la-index");
        String hash = IndexStore.mappingHashes(idx, "calls_ds").get(0);
        return new IndexStore(idx, "calls_ds", hash, keep, Clock.systemUTC());
    }

    @Test
    void forkPinsTheCurrentIndexVersionAGcKeepsItAndDiscardUnpins(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\nindex:\n  enabled: true\n")) {
            team(c);
            build(c);
            String da = fork(c, A, "{}");
            JsonNode draft = ok(c, "GET", d(da, ""), null, A);
            long pinned = draft.get("pins").get("index").get("calls_ds").asLong();
            assertEquals(1, pinned);
            assertTrue(pinsText(c).contains(da), "pins.json lists the draftId: " + pinsText(c));
            assertEquals(1, draft.get("pinExpiry").size());
            assertFalse(draft.get("pinWarning").asBoolean(), "a fresh pin is 30 days from expiry");
            // the index moves on twice; the pinned version survives a gc that keeps only CURRENT and one older version
            build(c);
            build(c);
            build(c);
            Path v1 = store(c, 1).directory().resolve(String.format("v%06d", pinned));
            assertTrue(Files.isDirectory(v1));
            store(c, 1).gc(Duration.ZERO);
            assertTrue(Files.isDirectory(v1), "gc kept the version a live Draft pins");
            // discard releases the pin: the next gc may collect it
            JsonNode gone = ok(c, "POST", d(da, "/discard"), "{}", A);
            assertEquals(1, gone.get("unpinned").asInt());
            assertFalse(pinsText(c).contains(da));
            store(c, 1).gc(Duration.ZERO);
            assertFalse(Files.isDirectory(v1), "with the pin released the old version is collected");
            assertTrue(ok(c, "POST", d(da, "/discard"), "{}", A).get("alreadyDiscarded").asBoolean(), "discard is idempotent");
        }
    }

    @Test
    void aForkWithNoIndexPinsNothingAndWorks(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            JsonNode draft = ok(c, "GET", d(da, ""), null, A);
            assertEquals(0, draft.get("pins").get("index").size());
            assertEquals(0, draft.get("pins").get("indexes").size());
            assertEquals(0, draft.get("pinExpiry").size());
            assertEquals(3, ok(c, "POST", d(da, "/ops"), SEED_E, A).get("step").asInt(), "seal-at-use (D-E3) needs no index");
            assertEquals(0, pinFiles(c).size());
        }
    }

    @Test
    void aFailedForkRenameLeavesNoDirectoryAndNoPin(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: none\nindex:\n  enabled: true\n")) {
            team(c);
            build(c);
            DraftStore.mover = (from, to) -> { throw new java.io.IOException("injected rename failure"); };
            HttpResponse<String> failed = send(c, "POST", DRAFTS, "{}", A);
            assertTrue(failed.statusCode() >= 500, failed.statusCode() + " " + failed.body());
            Path drafts = invDir(c).resolve("drafts");
            try (Stream<Path> s = Files.isDirectory(drafts) ? Files.list(drafts) : Stream.<Path>empty()) {
                assertEquals(0, s.count(), "no partial or scratch directory");
            }
            assertFalse(pinsText(c).contains("draft-"), "no pin: " + pinsText(c));
            assertEquals(0, ok(c, "GET", DRAFTS, null, A).get("items").size());
            DraftStore.mover = DraftStore.ATOMIC;
            fork(c, A, "{}");   // and the member is not locked out by the failure
            assertTrue(pinsText(c).contains("draft-"));
        }
    }

    // -- concurrency and failed appends ---------------------------------------------------------------------------

    @Test
    void concurrentAppendsToOneDraftSerialiseAndNeitherLoseNorDuplicateAStep(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            int n = 8;
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(n);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String body = "{\"op\":\"seed\",\"ids\":[\"x-" + i + "\"]}";
                results.add(pool.submit(() -> {
                    go.await();
                    return status(c, "POST", d(da, "/ops"), body, A);
                }));
            }
            go.countDown();
            for (Future<Integer> f : results) assertEquals(200, f.get(60, TimeUnit.SECONDS));
            pool.shutdown();
            List<Map<String, Object>> own = lines(invDir(c).resolve("drafts").resolve(da).resolve("log.jsonl"), 99);
            assertEquals(n, own.size());
            Set<Integer> steps = new HashSet<>();
            for (Map<String, Object> e : own) steps.add(((Number) e.get("step")).intValue());
            assertEquals(Set.of(3, 4, 5, 6, 7, 8, 9, 10), steps, "contiguous, none lost, none duplicated");
            assertTrue(ok(c, "GET", d(da, "/replay"), null, A).get("equivalent").asBoolean());
            assertEquals(3 + 8, ok(c, "GET", d(da, "/working-set?of=entities"), null, A).get("total").asInt());
        }
    }

    @Test
    void aFailedAppendLeavesTheDraftsLogUnchanged(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            Path log = invDir(c).resolve("drafts").resolve(da).resolve("log.jsonl");
            String before = Files.readString(log);
            assertEquals(422, status(c, "POST", d(da, "/ops"), "{\"op\":\"hide\",\"ids\":[\"nobody\"]}", A));
            assertEquals(422, status(c, "POST", d(da, "/ops"), "{\"op\":\"nonsense\"}", A));
            assertEquals(before, Files.readString(log), "a refused op writes nothing");
            // an IO failure AFTER the log line was written (the step's set file cannot be created) takes the line back
            Path blocker = invDir(c).resolve("drafts").resolve(da).resolve("sets").resolve("4.json");
            Files.createDirectories(blocker);
            assertTrue(status(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"y\"]}", A) >= 500);
            assertEquals(before, Files.readString(log), "the log line of a failed append is taken back");
            Files.delete(blocker);
            assertEquals(4, ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"y\"]}", A).get("step").asInt(), "and the same step is still free");
        }
    }

    // -- absence, no dossier, masking, revocation, path safety, audit --------------------------------------------

    @Test
    void aStrangerGetsTheAnswerAnUnknownInvestigationGetsAndAMalformedIdIsNeverATraversal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            for (String tail : List.of("", "/log", "/working-set", "/replay")) {
                HttpResponse<String> stranger = send(c, "GET", d(da, tail), null, S);
                HttpResponse<String> unknown = send(c, "GET", "/inv/investigations/inv-nope/drafts/" + da + tail, null, S);
                assertEquals(404, stranger.statusCode());
                assertEquals(unknown.statusCode(), stranger.statusCode());
                assertEquals(message(unknown).replace("inv-nope", "X"), message(stranger).replace("inv-a", "X"), tail);
            }
            for (String bad : List.of("not-a-draft", "draft-123", "%2e%2e", "draft-..%2f..%2fx", da.toUpperCase(java.util.Locale.ROOT)))
                for (String[] route : new String[][] {{"GET", ""}, {"GET", "/log"}, {"POST", "/ops"}, {"POST", "/undo"}, {"POST", "/discard"}}) {
                    int st = status(c, route[0], DRAFTS + "/" + bad + route[1], "{}", A);
                    assertTrue(st == 422 || st == 404, route[0] + " " + bad + route[1] + " -> " + st);
                    assertEquals(404, status(c, route[0], DRAFTS + "/" + bad + route[1], "{}", S), "a stranger learns nothing from a malformed id");
                }
            assertEquals(422, status(c, "GET", DRAFTS + "/not-a-draft", null, A));
            assertEquals(404, status(c, "GET", d("draft-00000000-0000-0000-0000-000000000000", ""), null, A));
        }
    }

    @Test
    void everyMutatingDraftRouteIsRefusedForAStrangerAndAnUnknownDraftIsAbsentToAMember(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String none = "draft-00000000-0000-0000-0000-000000000000";
            // a member: the Draft does not exist (404, not 422 - the id is well-formed); a stranger: the Investigation does not exist to them
            for (String who : List.of(L, A, R, S)) {
                assertEquals(404, status(c, "POST", "/inv/investigations/inv-a/drafts/draft-00000000-0000-0000-0000-000000000000/ops", SEED_E, who), "ops as " + who);
                assertEquals(404, status(c, "POST", "/inv/investigations/inv-a/drafts/draft-00000000-0000-0000-0000-000000000000/undo", "{}", who), "undo as " + who);
                assertEquals(404, status(c, "POST", "/inv/investigations/inv-a/drafts/draft-00000000-0000-0000-0000-000000000000/discard", "{}", who), "discard as " + who);
            }
            assertEquals(404, status(c, "POST", "/inv/investigations/inv-a/drafts", "{}", S));
            assertEquals(403, status(c, "POST", "/inv/investigations/inv-a/drafts", "{}", R));
            assertEquals(0, ok(c, "GET", DRAFTS, null, L).get("items").size(), "nothing was written: " + none);
        }
    }

    @Test
    void aDraftIsNotAnInvestigationAndHasNoDossier(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            for (String tail : List.of("/dossier", "/dossier/bundle", "/dossier/verify", "/evidence", "/members", "/template", "/reorder"))
                for (String who : List.of(A, L))
                    assertEquals(404, status(c, tail.equals("/dossier/verify") || tail.equals("/template") || tail.equals("/reorder") ? "POST" : "GET",
                            d(da, tail), "{}", who), tail + " as " + who);
            assertEquals(200, status(c, "GET", INV + "/dossier", null, L), "the probe that would succeed: the Investigation's own Dossier");
            // a Draft op never reaches the Dossier source: the main log is what it is
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            assertEquals(2, lines(invDir(c).resolve("log.jsonl"), 99).size());
        }
    }

    @Test
    void masksAfterTheCacheSoNoRawIdLeavesADraft(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            team(c);
            String da = fork(c, A, "{}");
            List<String> bodies = new ArrayList<>();
            bodies.add(send(c, "POST", d(da, "/ops"), SEED_E, A).body());
            bodies.add(send(c, "POST", d(da, "/ops"), "{\"op\":\"expand\",\"ids\":[\"erin-005\"]}", A).body());
            bodies.add(send(c, "GET", d(da, ""), null, A).body());
            bodies.add(send(c, "GET", d(da, "/log"), null, A).body());
            bodies.add(send(c, "GET", d(da, "/replay"), null, L).body());
            for (String of : List.of("entities", "links", "excluded")) {
                bodies.add(send(c, "GET", d(da, "/working-set?of=" + of), null, A).body());
                bodies.add(send(c, "GET", d(da, "/working-set?of=" + of), null, A).body());   // the second is served from the cache
            }
            for (String body : bodies)
                for (String raw : List.of("alice-001", "bob-002", "carol-003", "erin-005", "frank-006"))
                    assertFalse(body.contains(raw), raw + " leaked: " + body);
            assertTrue(bodies.stream().anyMatch(b -> b.contains("masked:")), "not vacuous: masking was in force");
            assertTrue(Files.readString(invDir(c).resolve("drafts").resolve(da).resolve("log.jsonl")).contains("erin-005"),
                    "the SEALED log keeps the raw ids; only the responses are masked");
        }
    }

    @Test
    void aSensitiveExpandCannotBeHeldByADraft(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: none\nfour_eyes_budget_above: 100\n");   // after the setup expand
            String da = fork(c, A, "{}");
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            HttpResponse<String> r = send(c, "POST", d(da, "/ops"), "{\"op\":\"expand\",\"ids\":[\"erin-005\"],\"budget\":500}", A);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(message(r).contains("four-eyes"), message(r));
            assertEquals(1, ok(c, "GET", d(da, ""), null, A).get("steps").asInt());
            assertFalse(Files.isDirectory(invDir(c).resolve("pending")), "no pending request was queued on the main log");
        }
    }

    @Test
    void aRevokedActorsDraftFreezesAndALeadMayStillReadAndDiscardIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            ok(c, "POST", INV + "/members/revoke", "{\"subject\":\"analyst-2\"}", L);
            assertEquals(404, status(c, "GET", d(da, ""), null, A));
            assertEquals(404, status(c, "POST", d(da, "/ops"), SEED_E, A));
            assertEquals(403, status(c, "POST", d(da, "/ops"), SEED_E, L), "and the lead does not take over its writes");
            assertEquals(1, ok(c, "GET", d(da, ""), null, L).get("steps").asInt());
            assertEquals(200, status(c, "POST", d(da, "/discard"), "{}", L));
        }
    }

    @Test
    void aDiscardedDraftIsGoneFromTheWritePathsAndKeepsItsAuditRecord(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A, "{}");
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            ok(c, "POST", d(da, "/discard"), "{}", A);
            Path dir = invDir(c).resolve("drafts").resolve(da);
            assertTrue(Files.isRegularFile(dir.resolve("header.json")));
            assertTrue(Files.isRegularFile(dir.resolve("discarded.json")));
            assertFalse(Files.exists(dir.resolve("log.jsonl")), "the sealed rows do not outlive the discard");
            assertFalse(Files.exists(dir.resolve("sets")));
            for (String tail : List.of("/ops", "/undo")) assertEquals(409, status(c, "POST", d(da, tail), SEED_E, A), tail);
            for (String tail : List.of("/log", "/working-set", "/replay")) assertEquals(409, status(c, "GET", d(da, tail), null, A), tail);
            JsonNode draft = ok(c, "GET", d(da, ""), null, A);
            assertEquals("discarded", draft.get("state").asText());
            assertEquals("analyst-2", draft.get("discarded").get("discardedBy").asText());
            assertEquals(3, draft.get("discarded").get("headStep").asInt());
        }
    }

    @Test
    void everyDraftActIsAuditedWithStructuredAttributesOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            String da;
            try {
                da = fork(c, A, "{}");
                ok(c, "POST", d(da, "/ops"), SEED_E, A);
                ok(c, "POST", d(da, "/undo"), "{}", A);
                ok(c, "POST", d(da, "/discard"), "{}", A);
                ok(c, "POST", d(da, "/discard"), "{}", A);   // idempotent: no second event
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> mine = seen.stream().filter(e -> e.type().startsWith("LINK_DRAFT_")).toList();
            assertEquals(List.of(LinkEventTypes.LINK_DRAFT_FORKED, LinkEventTypes.LINK_DRAFT_OP_APPENDED, LinkEventTypes.LINK_DRAFT_UNDONE,
                    LinkEventTypes.LINK_DRAFT_DISCARDED), mine.stream().map(Event::type).toList(), seen.toString());
            for (Event e : mine) {
                assertEquals("inv-a", e.attributes().get("investigationId"));
                assertEquals(da, e.attributes().get("draftId"));
                assertEquals("analyst-2", e.attributes().get("actor"));
                assertEquals("2", e.attributes().get("baseStep"));
                assertFalse(e.attributes().toString().contains("erin-005"), "never row content: " + e.attributes());
            }
            assertEquals("3", mine.get(1).attributes().get("step"));
            assertEquals("4", mine.get(2).attributes().get("step"));
            assertEquals("3", mine.get(2).attributes().get("undoes"));
            assertEquals("4", mine.get(3).attributes().get("step"));
            assertEquals(0, seen.stream().filter(e -> LinkEventTypes.LINK_INVESTIGATION_STEPPED.equals(e.type())).count(),
                    "a Draft step is not a main-log step");
        }
    }
}
