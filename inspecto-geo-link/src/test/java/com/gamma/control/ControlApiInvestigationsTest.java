package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
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
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-10 — the Investigation object over real HTTP: every gate of the six {@code /inv/investigations} routes, the
 * op semantics that make a log non-commutative (G-E1, G-E4), real undo, deterministic replay with its
 * equivalence check, the D-E3 seal (G-E11 holds, G-E3 reports drift) and the D-E4 fork.
 *
 * <p>Fixture: a call graph {@code alice–bob, alice–carol, bob–dave, bob–erin, carol–frank}. {@code dave} and
 * {@code erin} are reachable from {@code alice} ONLY through {@code bob}, which is what the ordering tests pivot on.
 */
class ControlApiInvestigationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE_ROWS = "('alice','bob','sms'),('alice','bob','sms'),('alice','carol','call'),"
            + "('bob','dave','call'),('bob','erin','sms'),('carol','frank','call')";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Ctx c = new Ctx(svc, api, api.port(), writeRoot);
            writeCalls(c, BASE_ROWS);
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return c;
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static void writeCalls(Ctx c, String rows) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES " + rows + ") AS t(caller,callee,channel)", "2026-09-23T00:00:00Z"));
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return send(port, "POST", path, body, null);
    }

    /** A 2xx /api/v1 body is the ENVELOPE — the payload lives under `data`. */
    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static final String CREATE =
            "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";

    private JsonNode op(Ctx c, String id, String body) throws Exception {
        return data(post(c.port, "/inv/investigations/" + id + "/ops", body));
    }

    private JsonNode replay(Ctx c, String id, String body) throws Exception {
        return data(post(c.port, "/inv/investigations/" + id + "/replay", body));
    }

    private static Set<String> entityIds(JsonNode workingSet) {
        Set<String> out = new TreeSet<>();
        for (JsonNode e : workingSet.get("entities")) out.add(e.get("id").asText());
        return out;
    }

    /** Every file under one Investigation's directory, by relative path → bytes-as-text. */
    private static Map<String, String> filesUnder(Path dir) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (var s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) out.put(dir.relativize(p).toString(), Files.readString(p));
        }
        return out;
    }

    private static Path invDir(Path root, String id) {
        return root.resolve("audit").resolve("snapshots").resolve("investigations").resolve(id);
    }

    // ── create ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createsAnInvestigationAndRendersItsLogAsPlainSteps(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode header = data(post(c.port, "/inv/investigations", CREATE));
            assertEquals("case-a", header.get("id").asText());
            assertEquals("calls_ds", header.get("dataset").asText());
            assertTrue(header.get("datasetVersion").isNull(), "D-E3: nothing is pinned — reads are sealed at use");
            assertTrue(header.get("parent").isNull());
            assertTrue(Files.isRegularFile(invDir(root, "case-a").resolve("header.json")),
                    "D-E2: the Investigation lives in the snapshot store");

            JsonNode seeded = op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"],\"entityType\":\"subscriber\"}");
            assertEquals(1, seeded.get("step").asInt());
            assertEquals("alice", seeded.at("/delta/admitted/0").asText());

            JsonNode expanded = op(c, "case-a", "{\"op\":\"expand\"}");
            assertEquals(List.of("bob", "carol"), texts(expanded.at("/delta/admitted")));
            assertEquals(2, expanded.at("/delta/linksAdded").asInt());
            assertFalse(expanded.get("truncated").asBoolean());
            assertTrue(expanded.at("/read/fingerprint").asText().startsWith("sha256:"));

            op(c, "case-a", "{\"op\":\"exclude\",\"ids\":[\"bob\"],\"reason\":\"marketing number\"}");

            JsonNode log = data(send(c.port, "GET", "/inv/investigations/case-a/log", null, null));
            assertEquals(3, log.get("total").asInt());
            assertFalse(log.get("truncated").asBoolean());
            assertTrue(log.at("/entries/0/text").asText().startsWith("1. Seeded 1 entity of type subscriber"));
            assertTrue(log.at("/entries/1/text").asText().contains("over calls_ds"), log.toString());
            // G-E10's clause for this rendering: an exclusion names its entities AND its reason.
            assertTrue(log.at("/entries/2/text").asText().contains("reason: marketing number"), log.toString());
            assertTrue(log.at("/entries/2/text").asText().contains("bob"), log.toString());
            assertFalse(log.at("/entries/1/read").has("rows"), "the sealed rows stay out of the log view");
        }
    }

    @Test
    void createRefusesEachBadInputInGateOrder(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(422, post(c.port, "/inv/investigations", "{\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}").statusCode());
            assertEquals(422, post(c.port, "/inv/investigations",
                    "{\"purpose\":\"test\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller;drop\",\"targetCol\":\"callee\"}").statusCode());
            assertEquals(404, post(c.port, "/inv/investigations",
                    "{\"purpose\":\"test\",\"dataset\":\"nope\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}").statusCode());
            HttpResponse<String> col = post(c.port, "/inv/investigations",
                    "{\"purpose\":\"test\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"ghost\"}");
            assertEquals(422, col.statusCode(), col.body());
            assertTrue(col.body().contains("ghost"));
            assertEquals(422, post(c.port, "/inv/investigations",
                    "{\"purpose\":\"test\",\"id\":\"../escape\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}").statusCode());

            assertEquals(200, post(c.port, "/inv/investigations", CREATE).statusCode());
            assertEquals(409, post(c.port, "/inv/investigations", CREATE).statusCode(),
                    "an existing Investigation is never replaced");
        }
    }

    @Test
    void opsRefuseWhatTheVocabularyAndTheWorkingSetDoNotAllow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, post(c.port, "/inv/investigations/nope/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}").statusCode());
            data(post(c.port, "/inv/investigations", CREATE));
            String ops = "/inv/investigations/case-a/ops";
            assertEquals(422, post(c.port, ops, "{\"op\":\"expand\"}").statusCode(), "nothing to expand yet");
            HttpResponse<String> deferred = post(c.port, ops, "{\"op\":\"threshold\"}");
            assertEquals(422, deferred.statusCode());
            assertTrue(deferred.body().contains("not implemented yet"), deferred.body());
            HttpResponse<String> unknown = post(c.port, ops, "{\"op\":\"sql\",\"ids\":[\"x\"]}");
            assertEquals(422, unknown.statusCode());
            assertTrue(unknown.body().contains("closed op vocabulary"), unknown.body());
            assertEquals(422, post(c.port, ops, "{\"op\":\"seed\"}").statusCode(), "seed needs ids");
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            assertEquals(422, post(c.port, ops, "{\"op\":\"exclude\",\"ids\":[\"alice\"]}").statusCode(),
                    "an exclusion without a reason cannot be challenged");
            assertEquals(422, post(c.port, ops, "{\"op\":\"hide\",\"ids\":[\"zed\"]}").statusCode(),
                    "hide names a Working Set member");
            assertEquals(422, post(c.port, ops, "{\"op\":\"expand\",\"ids\":[\"zed\"]}").statusCode());
            assertEquals(1, Files.readAllLines(invDir(root, "case-a").resolve("log.jsonl")).size(),
                    "no refused op reached the log");
        }
    }

    // ── semantics ──────────────────────────────────────────────────────────────────────────────────────

    /** G-E1 — order is respected, and the difference is exactly what was reachable only through the exclusion. */
    @Test
    void excludeThenExpandDiffersFromExpandThenExclude(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c.port, "/inv/investigations", CREATE));
            data(post(c.port, "/inv/investigations", CREATE.replace("case-a", "case-b")));
            String excl = "{\"op\":\"exclude\",\"ids\":[\"bob\"],\"reason\":\"marketing\"}";
            for (String s : List.of("{\"op\":\"seed\",\"ids\":[\"alice\"]}", "{\"op\":\"expand\"}", excl, "{\"op\":\"expand\"}"))
                op(c, "case-a", s);
            for (String s : List.of("{\"op\":\"seed\",\"ids\":[\"alice\"]}", "{\"op\":\"expand\"}", "{\"op\":\"expand\"}", excl))
                op(c, "case-b", s);

            Set<String> pruneFirst = entityIds(replay(c, "case-a", "{}").get("workingSet"));
            Set<String> pruneLast = entityIds(replay(c, "case-b", "{}").get("workingSet"));
            assertEquals(Set.of("alice", "carol", "frank"), pruneFirst);
            assertEquals(Set.of("alice", "carol", "dave", "erin", "frank"), pruneLast);
            Set<String> diff = new TreeSet<>(pruneLast);
            diff.removeAll(pruneFirst);
            assertEquals(Set.of("dave", "erin"), diff, "exactly the entities reachable only through bob");
        }
    }

    /** G-E4 — a hidden hub still yields downstream entities; keep protects against exclude. */
    @Test
    void hideIsNotExcludeAndKeepProtects(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c.port, "/inv/investigations", CREATE));
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            op(c, "case-a", "{\"op\":\"expand\"}");
            op(c, "case-a", "{\"op\":\"hide\",\"ids\":[\"bob\"]}");
            JsonNode grown = op(c, "case-a", "{\"op\":\"expand\",\"ids\":[\"bob\"]}");
            assertEquals(List.of("dave", "erin"), texts(grown.at("/delta/admitted")), "hidden bob is still traversed");

            op(c, "case-a", "{\"op\":\"keep\",\"ids\":[\"carol\"]}");
            JsonNode refused = op(c, "case-a", "{\"op\":\"exclude\",\"ids\":[\"carol\"],\"reason\":\"noise\"}");
            assertEquals(List.of("carol"), texts(refused.get("protected")));
            assertEquals(0, refused.at("/delta/removed").size(), "a kept entity is not removed");

            JsonNode ws = replay(c, "case-a", "{}").get("workingSet");
            for (JsonNode e : ws.get("entities")) {
                if (e.get("id").asText().equals("bob")) assertTrue(e.get("hidden").asBoolean());
                if (e.get("id").asText().equals("dave")) {
                    // G-E8: every entity traces to the step that admitted it and the seed it descends from.
                    assertEquals(4, e.get("admittedBy").asInt());
                    assertEquals("alice", e.get("seed").asText());
                    assertEquals(2, e.get("hop").asInt());
                }
            }
        }
    }

    @Test
    void undoRestoresTheExactPriorWorkingSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c.port, "/inv/investigations", CREATE));
            assertEquals(409, post(c.port, "/inv/investigations/case-a/undo", "").statusCode(), "nothing to undo");
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            String before = op(c, "case-a", "{\"op\":\"expand\"}").at("/workingSet/hash").asText();
            op(c, "case-a", "{\"op\":\"exclude\",\"ids\":[\"bob\"],\"reason\":\"marketing\"}");

            JsonNode undone = data(post(c.port, "/inv/investigations/case-a/undo", ""));
            assertEquals(3, undone.get("undoes").asInt());
            assertEquals(List.of("bob"), texts(undone.at("/delta/admitted")));
            assertEquals(before, undone.at("/workingSet/hash").asText(), "undo is REAL: byte-identical state");

            // A second undo reverts the next op back, never the undo itself.
            assertEquals(2, data(post(c.port, "/inv/investigations/case-a/undo", "")).get("undoes").asInt());
            JsonNode log = data(send(c.port, "GET", "/inv/investigations/case-a/log", null, null));
            assertEquals(5, log.get("total").asInt(), "the log stays append-only: undo is recorded, not erased");
            assertTrue(log.at("/entries/2/text").asText().endsWith("(undone by step 4)"), log.toString());
        }
    }

    // ── replay + seal ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void replayIsDeterministicAndEquivalentToTheIncrementalSteps(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c.port, "/inv/investigations", CREATE));
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            String afterExpand = op(c, "case-a", "{\"op\":\"expand\"}").at("/workingSet/hash").asText();
            op(c, "case-a", "{\"op\":\"exclude\",\"ids\":[\"bob\"],\"reason\":\"marketing\"}");
            String head = op(c, "case-a", "{\"op\":\"expand\"}").at("/workingSet/hash").asText();

            JsonNode first = replay(c, "case-a", "{}");
            JsonNode second = replay(c, "case-a", "{}");
            assertTrue(first.get("equivalent").asBoolean(), first.toString());
            assertEquals(0, first.get("mismatches").size());
            assertEquals(head, first.at("/workingSet/hash").asText(), "full replay == the incremental head");
            assertEquals(first.get("workingSet"), second.get("workingSet"), "replay is deterministic");
            assertEquals(afterExpand, replay(c, "case-a", "{\"at\":2}").at("/workingSet/hash").asText(),
                    "a replay to a position reproduces that position");

            // ⛔ The equivalence check must be able to FAIL: tamper with a recorded hash and it reports it.
            Path log = invDir(root, "case-a").resolve("log.jsonl");
            List<String> lines = new ArrayList<>(Files.readAllLines(log));
            lines.set(1, lines.get(1).replace(afterExpand, "sha256:tampered"));
            Files.write(log, lines);
            JsonNode tampered = replay(c, "case-a", "{}");
            assertFalse(tampered.get("equivalent").asBoolean());
            assertEquals(2, tampered.at("/mismatches/0").asInt());
        }
    }

    /**
     * An undo on an UNTAMPERED log must still replay as equivalent. 🔴 Regression (found by the LA-12 dossier lane):
     * replay resolved the undone steps across the WHOLE log before folding, so replaying prefix 4 skipped the hide
     * that step 5 undoes — while the hash recorded at step 4 includes it — and reported {@code mismatches:[4]}.
     * Prefix k must honour only the undo entries at positions ≤ k.
     */
    @Test
    void replayAfterAnUndoIsEquivalentBecauseAPrefixIgnoresLaterUndos(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c.port, "/inv/investigations", CREATE));
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            op(c, "case-a", "{\"op\":\"expand\"}");
            op(c, "case-a", "{\"op\":\"exclude\",\"ids\":[\"bob\"],\"reason\":\"marketing\"}");
            String hidden = op(c, "case-a", "{\"op\":\"hide\",\"ids\":[\"carol\"]}").at("/workingSet/hash").asText();
            String head = data(post(c.port, "/inv/investigations/case-a/undo", "")).at("/workingSet/hash").asText();

            JsonNode full = replay(c, "case-a", "{}");
            assertTrue(full.get("equivalent").asBoolean(), full.toString());
            assertEquals(0, full.get("mismatches").size(), full.toString());
            assertEquals(head, full.at("/workingSet/hash").asText());
            assertEquals(hidden, replay(c, "case-a", "{\"at\":4}").at("/workingSet/hash").asText(),
                    "a replay to step 4 reproduces step 4 — the hide the LATER undo reverts is still in it");
        }
    }

    /** D-E3: the sealed read does not move when the data grows (G-E11), and a re-read reports it (G-E3). */
    @Test
    void theSealHoldsWhenDataGrowsAndRereadReportsTheDrift(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c.port, "/inv/investigations", CREATE));
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            JsonNode expanded = op(c, "case-a", "{\"op\":\"expand\"}");
            String sealedHash = expanded.at("/workingSet/hash").asText();
            String sealedPrint = expanded.at("/read/fingerprint").asText();

            JsonNode clean = replay(c, "case-a", "{\"reread\":true}");
            assertFalse(clean.get("diverged").asBoolean(), "unchanged data re-reads to the same fingerprint");
            assertEquals(sealedPrint, clean.at("/drift/0/currentFingerprint").asText());

            writeCalls(c, BASE_ROWS + ",('alice','zoe','call')");

            JsonNode sealed = replay(c, "case-a", "{}");
            assertEquals(sealedHash, sealed.at("/workingSet/hash").asText(), "an Evidence read does not move");
            assertFalse(entityIds(sealed.get("workingSet")).contains("zoe"));

            JsonNode reread = replay(c, "case-a", "{\"reread\":true}");
            assertTrue(reread.get("diverged").asBoolean(), "drift is reported, never silently served");
            JsonNode d = reread.at("/drift/0");
            assertEquals(2, d.get("step").asInt());
            assertTrue(d.get("diverged").asBoolean());
            assertEquals(sealedPrint, d.get("sealedFingerprint").asText());
            assertNotEquals(sealedPrint, d.get("currentFingerprint").asText());
            assertEquals(2, d.get("sealedRows").asInt());
            assertEquals(3, d.get("currentRows").asInt());
            assertEquals(sealedHash, reread.at("/workingSet/hash").asText(), "a re-read reports; it does not rewrite");
        }
    }

    // ── fork ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * D-E4 — re-ordering FORKS. 🔴 The property is not only that the fork evaluates the new order, but that the
     * ORIGINAL — its log, every per-step Working Set, and an Artifact sealed against it — is byte-identical after.
     */
    @Test
    void reorderForksAndLeavesTheOriginalAndItsArtifactsUntouched(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c.port, "/inv/investigations", CREATE));
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            op(c, "case-a", "{\"op\":\"expand\"}");
            op(c, "case-a", "{\"op\":\"exclude\",\"ids\":[\"bob\"],\"reason\":\"marketing\"}");
            String origHead = op(c, "case-a", "{\"op\":\"expand\"}").at("/workingSet/hash").asText();
            // An Artifact anchored to the original log at step 4.
            assertEquals(200, post(c.port, "/inv/snapshots", "{\"id\":\"snap-a4\",\"manifestHash\":\"fnv1a64:1\","
                    + "\"investigationId\":\"case-a\",\"opSeq\":4,\"nodes\":[],\"edges\":[]}").statusCode());
            Path snap = root.resolve("audit").resolve("snapshots").resolve("snap-a4.json");
            String snapBefore = Files.readString(snap);
            Map<String, String> origBefore = filesUnder(invDir(root, "case-a"));

            assertEquals(422, post(c.port, "/inv/investigations/case-a/reorder", "{\"order\":[1,2,3]}").statusCode(),
                    "a reorder must be a permutation of every effective step");
            assertEquals(409, post(c.port, "/inv/investigations/case-a/reorder",
                    "{\"order\":[1,2,4,3],\"id\":\"case-a\"}").statusCode());

            JsonNode fork = data(post(c.port, "/inv/investigations/case-a/reorder", "{\"order\":[1,2,4,3],\"id\":\"fork-1\"}"));
            assertEquals("fork-1", fork.get("id").asText());
            assertEquals("case-a", fork.at("/parent/id").asText());
            assertEquals("[1,2,4,3]", fork.at("/parent/order").toString());

            JsonNode forkWs = replay(c, "fork-1", "{}");
            assertTrue(forkWs.get("equivalent").asBoolean(), forkWs.toString());
            assertEquals(Set.of("alice", "carol", "dave", "erin", "frank"), entityIds(forkWs.get("workingSet")),
                    "the fork evaluates the NEW order (expand before exclude)");
            JsonNode forkLog = data(send(c.port, "GET", "/inv/investigations/fork-1/log", null, null));
            assertEquals("case-a", forkLog.at("/header/parent/id").asText());
            assertEquals(4, forkLog.at("/entries/2/derivedFrom/step").asInt());

            assertEquals(origBefore, filesUnder(invDir(root, "case-a")), "the original log and its Working Sets are unchanged");
            assertEquals(snapBefore, Files.readString(snap), "the Artifact sealed against the original is unchanged");
            JsonNode orig = replay(c, "case-a", "{}");
            assertEquals(origHead, orig.at("/workingSet/hash").asText());
            assertTrue(orig.get("equivalent").asBoolean());
        }
    }

    // ── access ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The capability gate AND owner-only access, with an ARMED Authenticator. 🔴 Every other test here runs with no
     * Subject, where {@code withCapability} is a no-op and ownership is unenforced — so without this test those
     * gates are asserted by nothing.
     */
    @Test
    void mutatingRoutesRequireCanManageIncidentsAndOnlyTheOwnerGetsIn(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            case "Bearer plain" -> Optional.of(new Subject("nobody", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, send(c.port, "POST", "/inv/investigations", CREATE, null).statusCode());
            HttpResponse<String> denied = send(c.port, "POST", "/inv/investigations", CREATE, "Bearer plain");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canManageIncidents"));
            assertFalse(Files.exists(invDir(root, "case-a")), "a refused create writes nothing");

            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "Bearer owner").statusCode());

            String seed = "{\"op\":\"seed\",\"ids\":[\"bob\"]}";
            assertEquals(403, send(c.port, "POST", "/inv/investigations/case-a/ops", seed, "Bearer plain").statusCode());
            assertEquals(403, send(c.port, "POST", "/inv/investigations/case-a/undo", "", "Bearer plain").statusCode());
            assertEquals(403, send(c.port, "POST", "/inv/investigations/case-a/reorder",
                    "{\"order\":[1]}", "Bearer plain").statusCode());

            // A non-owner WITH the capability: indistinguishable from absence on every route.
            assertEquals(404, send(c.port, "POST", "/inv/investigations/case-a/ops", seed, "Bearer other").statusCode());
            assertEquals(404, send(c.port, "POST", "/inv/investigations/case-a/undo", "", "Bearer other").statusCode());
            assertEquals(404, send(c.port, "POST", "/inv/investigations/case-a/reorder",
                    "{\"order\":[1],\"id\":\"stolen\"}", "Bearer other").statusCode());
            assertEquals(404, send(c.port, "POST", "/inv/investigations/case-a/replay", "{}", "Bearer other").statusCode());
            assertEquals(404, send(c.port, "GET", "/inv/investigations/case-a/log", null, "Bearer other").statusCode());
            assertFalse(Files.exists(invDir(root, "stolen")));
            assertEquals(1, Files.readAllLines(invDir(root, "case-a").resolve("log.jsonl")).size(),
                    "no refused call reached the log");

            assertEquals(200, send(c.port, "GET", "/inv/investigations/case-a/log", null, "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/replay", "{}", "Bearer owner").statusCode());
        } finally {
            Authenticators.forTest(null);
        }
    }

    /** R3: once the bound Dataset is shared away from the owner, the Investigation reads as absent too. */
    @Test
    void aSharedAwayDatasetMakesTheInvestigationReadAsAbsent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> "Bearer owner".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("analyst-1", Set.of("canManageIncidents"))) : Optional.empty());
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "Bearer owner").statusCode());

            new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds",
                    Map.of("view", "calls_view", "owner", "someone-else", "shares", List.of()));

            HttpResponse<String> expand = send(c.port, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"expand\"}", "Bearer owner");
            assertEquals(404, expand.statusCode(), expand.body());
            assertTrue(expand.body().contains("no dataset"), expand.body());
            assertEquals(404, send(c.port, "GET", "/inv/investigations/case-a/log", null, "Bearer owner").statusCode());
            assertEquals(404, send(c.port, "POST", "/inv/investigations", CREATE.replace("case-a", "case-b"),
                    "Bearer owner").statusCode(), "and a new Investigation cannot bind it");
        } finally {
            Authenticators.forTest(null);
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }
}
