package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-17 slice 2, part 2 — the {@code resolve} op over real HTTP ({@code docs/archived-documents/plans-archive/link-analysis-entity-model-design.md}
 * §8.1, §8.2): the Space's identity resolution applied INSIDE an Investigation at a pinned fact seq, sealed into the
 * entry so replay, {@code ?at}, a fork and the Dossier never re-read the fact log; merged nodes carrying every member
 * key and the assertions that joined them; masking per member key; the template carry (D-E8).
 *
 * <p>Fixture: {@code "0044 7700-900123"–"+447700900555"}, {@code "+447700900555"–"07700900999"} over
 * {@code (caller, callee, channel)}, both endpoint columns classified {@code MSISDN}, so every id's typed key is
 * {@code msisdn:<e164>}. Assertions: fact 1 {@code msisdn:+447700900123 ≡ imsi:234150000000001} (cross-type — the IMSI
 * is never in the Working Set), fact 2 {@code msisdn:+447700900555 ≡ msisdn:07700900999}.
 */
class ControlApiInvestigationResolveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "0044 7700-900123", B = "+447700900555", C = "07700900999";
    private static final String IMSI = "imsi:234150000000001", KEY_A = "msisdn:+447700900123";
    private static final String KEY_B = "msisdn:+447700900555", KEY_C = "msisdn:07700900999";
    private static final String ROWS = "('" + A + "','" + B + "','call'),('" + B + "','" + C + "','sms')";
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String RESOLVE = "{\"op\":\"resolve\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void noSubjects() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view",
                    "columns", List.of(Map.of("name", "caller", "classification", "MSISDN"),
                            Map.of("name", "callee", "classification", "MSISDN"))));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return send(c, "POST", path, body, null);
    }

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return send(c, "GET", path, null, null);
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode op(Ctx c, String id, String body) throws Exception {
        return data(post(c, "/inv/investigations/" + id + "/ops", body), 200);
    }

    private JsonNode replay(Ctx c, String id) throws Exception {
        return data(post(c, "/inv/investigations/" + id + "/replay", "{}"), 200);
    }

    private void assertIdentity(Ctx c, String a, String b) throws Exception {
        data(post(c, "/inv/entity-identities", "{\"a\":\"" + a + "\",\"b\":\"" + b + "\",\"reason\":\"same SIM swap\"}"), 201);
    }

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), toon);
    }

    /** Two types, msisdn UNMASKED and imsi MASKED, both e164/digits as the defaults — for the per-member tests. */
    private void typed(Ctx c, boolean msisdnMasked) throws Exception {
        data(send(c, "PUT", "/settings/link-analysis", "{\"maskingMode\":\"typed\",\"entityTypes\":["
                + "{\"id\":\"msisdn\",\"label\":\"MSISDN\",\"normaliser\":\"e164\",\"masked\":" + msisdnMasked
                + ",\"classifications\":[\"MSISDN\"]},"
                + "{\"id\":\"imsi\",\"label\":\"IMSI\",\"normaliser\":\"digits\",\"masked\":true,\"classifications\":[\"IMSI\"]}]}",
                null), 200);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }

    private static JsonNode group(JsonNode groups, String id) {
        for (JsonNode g : groups) if (id.equals(g.get("id").asText())) return g;
        throw new AssertionError("no group " + id + " in " + groups);
    }

    private static Path logFile(Path root, String id) {
        return root.resolve("audit/snapshots/investigations").resolve(id).resolve("log.jsonl");
    }

    /** seed A, expand (admits B), resolve — the base every test builds on. */
    private void base(Ctx c) throws Exception {
        assertIdentity(c, KEY_A, IMSI);
        assertIdentity(c, KEY_B, KEY_C);
        data(post(c, "/inv/investigations", CREATE), 200);
        op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}");
        op(c, "case-a", "{\"op\":\"expand\"}");
    }

    // ── refusals ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void appendRefusesEachBadResolveAndNoneReachesTheLog(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            String ops = "/inv/investigations/case-a/ops";
            HttpResponse<String> past = post(c, ops, "{\"op\":\"resolve\",\"atSeq\":3}");
            assertEquals(422, past.statusCode(), past.body());
            assertTrue(past.body().contains("beyond the identity fact log head 2"), past.body());
            assertEquals(422, post(c, ops, "{\"op\":\"resolve\",\"atSeq\":-1}").statusCode(), "a seq is >= 0");
            assertEquals(422, post(c, ops, "{\"op\":\"resolve\",\"atSeq\":\"2\"}").statusCode(), "a seq is a number");
            assertEquals(422, post(c, ops, "{\"op\":\"resolve\",\"atSeq\":1.5}").statusCode(), "a seq is an integer");
            assertEquals(422, post(c, ops, "{\"op\":\"resolve\",\"ids\":[\"" + B + "\"]}").statusCode(),
                    "resolve applies the Space's resolution, never ids");
            assertEquals(2, Files.readAllLines(logFile(root, "case-a")).size(), "no refused resolve reached the log");
        }
    }

    /** The op rides {@code /ops}: a Subject without {@code canManageIncidents} is refused before anything is sealed. */
    @Test
    void aSubjectWithoutTheCapabilityCannotResolve(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            Authenticators.forTest(ex -> "Bearer viewer".equals(ex.getRequestHeaders().getFirst("Authorization"))
                    ? Optional.of(new Subject("viewer-1", Set.of())) : Optional.empty());
            HttpResponse<String> r = send(c, "POST", "/inv/investigations/case-a/ops", RESOLVE, "Bearer viewer");
            assertEquals(403, r.statusCode(), r.body());
            Authenticators.forTest(null);
            assertEquals(2, Files.readAllLines(logFile(root, "case-a")).size());
        }
    }

    // ── the merged node, sealed ────────────────────────────────────────────────────────────────────────

    @Test
    void resolveShowsEveryMemberKeyAndTheJoiningAssertionsAndResolvesLaterAdmissionsToo(@TempDir Path cfg,
                                                                                        @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            String before = replay(c, "case-a").at("/workingSet/hash").asText();
            JsonNode r = op(c, "case-a", RESOLVE);
            assertEquals(0, r.at("/delta/admitted").size(), "a resolve admits nothing");
            assertEquals(2, r.at("/workingSet/entities").asInt(), "...and removes nothing: traversal and counts are unchanged");
            assertNotEquals(before, r.at("/workingSet/hash").asText(), "the resolution is part of the Working Set hash");
            assertEquals(2, r.at("/resolution/atSeq").asLong(), "pinned at the head when no atSeq is given");

            JsonNode x = group(r.at("/resolution/groups"), IMSI);
            assertEquals(List.of(IMSI, KEY_A), texts(x.get("members")), "EVERY member key — the IMSI is not in the Working Set");
            assertEquals(List.of(A), texts(x.get("entities")), "the raw id it covers");
            assertEquals(1, x.at("/assertions/0/seq").asLong());
            assertEquals("same SIM swap", x.at("/assertions/0/reason").asText(), "the assertion that joined it, with its reason");
            assertEquals(List.of(B), texts(group(r.at("/resolution/groups"), KEY_B).get("entities")));

            // An entity admitted AFTER the resolve resolves too, under the sealed rule.
            JsonNode e = op(c, "case-a", "{\"op\":\"expand\"}");
            assertEquals(List.of(C), texts(e.at("/delta/admitted")));
            assertEquals(List.of(B, C), texts(group(e.at("/resolution/groups"), KEY_B).get("entities")));

            JsonNode ws = data(get(c, "/inv/investigations/case-a/working-set?of=entities"), 200);
            Map<String, String> identity = new java.util.TreeMap<>();
            for (JsonNode row : ws.get("rows")) identity.put(row.get("entityId").asText(), row.get("identity").asText());
            assertEquals(Map.of(A, IMSI, B, KEY_B, C, KEY_B), identity);
            JsonNode m = data(get(c, "/inv/investigations/case-a/measures"), 200);
            Map<String, Double> measures = new java.util.TreeMap<>();
            for (JsonNode n : m.get("measures"))
                measures.put(n.get("name").asText(), n.get("value").asDouble());
            assertEquals(3.0, measures.get("entities"));
            assertEquals(2.0, measures.get("identities"), "a merged node counts once: " + m);

            JsonNode logView = data(get(c, "/inv/investigations/case-a/log"), 200);
            String line = logView.at("/entries/2/text").asText();
            assertTrue(line.contains("Applied identity resolution as of fact 2 (2 groups, 4 member keys)"), line);
            assertFalse(logView.at("/entries/2/resolution").has("types") || logView.toString().contains(IMSI),
                    "the log view summarises the seal, it does not list keys: " + logView.at("/entries/2"));
        }
    }

    /**
     * Pinned: facts appended after the resolve — a retraction and a new assertion — change nothing it shows, at the head,
     * in replay or at any {@code ?at} prefix; a SECOND resolve picks them up, and undoing it is byte-identical.
     */
    @Test
    void theSealIsPinnedReplayIsEquivalentAndAtPrefixesAreStable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            String sealed = op(c, "case-a", RESOLVE).at("/workingSet/hash").asText();
            data(post(c, "/inv/entity-identities/1/retract", "{\"reason\":\"wrong SIM\"}"), 200);   // fact 3
            assertIdentity(c, KEY_A, "imsi:999");                                                   // fact 4

            JsonNode rep = replay(c, "case-a");
            assertTrue(rep.get("equivalent").asBoolean(), rep.toString());
            assertEquals(sealed, rep.at("/workingSet/hash").asText(), "replay never re-reads the fact log");
            assertEquals(List.of(IMSI, KEY_A), texts(group(rep.at("/workingSet/resolution/groups"), IMSI).get("members")));

            JsonNode g2 = data(get(c, "/inv/investigations/case-a/working-set?at=2"), 200);
            assertFalse(g2.has("groups"), "before the resolve step nothing is resolved");
            Set<String> identities = new TreeSet<>();
            for (JsonNode row : g2.get("rows")) identities.add(row.get("identity").asText());
            assertEquals(Set.of(A, B), identities, "unresolved, an entity's identity is itself");
            JsonNode g3 = data(get(c, "/inv/investigations/case-a/working-set?at=3"), 200);
            assertEquals(2, g3.get("groups").size());
            assertEquals(3, g3.at("/groups/0/opSeq").asInt(), "the step whose seal it is");
            assertEquals(sealed, g3.at("/head/workingSetHash").asText());

            JsonNode again = op(c, "case-a", RESOLVE);
            assertEquals(4, again.at("/resolution/atSeq").asLong(), "a later resolve re-pins at the head");
            assertEquals(List.of(KEY_A, "imsi:999").stream().sorted().toList(),
                    texts(group(again.at("/resolution/groups"), "imsi:999").get("members")));
            JsonNode pinnedEarlier = op(c, "case-a", "{\"op\":\"resolve\",\"atSeq\":1}");
            assertEquals(1, pinnedEarlier.at("/resolution/groups").size(), "fact 2 is past the pin");
            JsonNode undone = data(post(c, "/inv/investigations/case-a/undo", "{}"), 200);
            assertEquals(again.at("/workingSet/hash").asText(), undone.at("/workingSet/hash").asText(),
                    "undo is byte-identical to the state before the undone resolve");
            assertTrue(replay(c, "case-a").get("equivalent").asBoolean());
        }
    }

    /** A tampered seal is caught: the groups are inside the hash replay and the Dossier check. */
    @Test
    void tamperingWithTheSealedGroupsBreaksReplayEquivalence(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            op(c, "case-a", RESOLVE);
            Path log = logFile(root, "case-a");
            String raw = Files.readString(log);
            assertTrue(raw.contains("same SIM swap"));
            Files.writeString(log, raw.replace("same SIM swap", "same SIM swop"));
            JsonNode rep = replay(c, "case-a");
            assertFalse(rep.get("equivalent").asBoolean(), "a rewritten assertion reason is a different Working Set");
            assertEquals(List.of(3), texts(rep.get("mismatches")).stream().map(Integer::valueOf).toList());
            JsonNode d = data(get(c, "/inv/investigations/case-a/dossier"), 200);
            assertFalse(d.at("/integrity/intact").asBoolean(), d.get("integrity").toString());
        }
    }

    // ── fork and template ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aForkKeepsTheSealedResolutionAndATemplateReSealsAtItsOwnHead(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            // pinned EXPLICITLY at fact 2, so a template that carried the pin verbatim would be caught re-sealing at 2
            op(c, "case-a", "{\"op\":\"resolve\",\"atSeq\":2}");
            assertIdentity(c, KEY_B, "imsi:555");   // fact 3 — after the seal

            data(post(c, "/inv/investigations/case-a/reorder", "{\"id\":\"case-f\",\"order\":[1,3,2]}"), 200);
            JsonNode fork = replay(c, "case-f");
            assertTrue(fork.get("equivalent").asBoolean(), fork.toString());
            assertEquals(2, fork.at("/workingSet/resolution/atSeq").asLong(), "a fork re-orders the method, it does not re-read");
            assertEquals(List.of(KEY_B, KEY_C), texts(group(fork.at("/workingSet/resolution/groups"), KEY_B).get("members")));

            HttpResponse<String> saved = post(c, "/inv/investigations/case-a/template", "{\"id\":\"tpl-r\"}");
            JsonNode tpl = data(saved, 200);
            JsonNode carried = tpl.at("/ops/2");
            assertEquals("resolve", carried.get("op").asText());
            Set<String> fields = new TreeSet<>();
            carried.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("op", "step"), fields, "{op} only — no atSeq, no groups: " + carried);
            assertFalse(saved.body().contains("7700900") || saved.body().contains("imsi:"), saved.body());

            data(post(c, "/inv/investigation-templates/tpl-r/instantiate",
                    "{\"id\":\"case-t\",\"purpose\":\"rerun\",\"params\":{\"seed1\":[\"" + A + "\"]}}"), 200);
            JsonNode t = replay(c, "case-t");
            assertEquals(3, t.at("/workingSet/resolution/atSeq").asLong(), "re-sealed at the head AT INSTANTIATION");
            assertEquals(List.of("imsi:555", KEY_B, KEY_C),
                    texts(group(t.at("/workingSet/resolution/groups"), "imsi:555").get("members")));
        }
    }

    // ── masking per member (D-U6) ──────────────────────────────────────────────────────────────────────

    @Test
    void typedMaskingIsPerMemberKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            typed(c, false);
            base(c);
            JsonNode r = op(c, "case-a", RESOLVE);
            JsonNode groups = r.at("/resolution/groups");
            JsonNode x = null;
            for (JsonNode g : groups) if (texts(g.get("members")).contains(KEY_A)) x = g;
            assertTrue(x != null, groups.toString());
            List<String> members = texts(x.get("members"));
            assertTrue(members.get(0).startsWith("masked:"), "the IMSI member (a masked type) is masked: " + members);
            assertEquals(KEY_A, members.get(1), "the MSISDN member (an unmasked type) stays readable in the same group");
            assertEquals(members.get(0), x.get("id").asText(), "the group id is rendered as the member it is");
            assertEquals(List.of(A), texts(x.get("entities")));
            assertFalse(r.toString().contains("234150000000001"), "no raw IMSI anywhere: " + r);

            JsonNode d = data(get(c, "/inv/investigations/case-a/dossier"), 200);
            assertTrue(d.at("/integrity/intact").asBoolean());
            assertFalse(d.toString().contains("234150000000001"), "the Dossier masks the member too");
            assertTrue(d.at("/manifest/content").has("resolution"), d.at("/manifest").toString());

            JsonNode revealed = data(post(c, "/inv/investigations/case-a/reveal",
                    "{\"tokens\":[\"" + members.get(0) + "\"]}"), 200);
            assertTrue(revealed.toString().contains(IMSI), "a masked member reveals to its key: " + revealed);
        }
    }

    /** A type tightened after the seal masks its members NOW (fail closed), and {@code all} masks every key. */
    @Test
    void aTypeMaskedTodayAndModeAllMaskEveryAffectedKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            typed(c, false);
            base(c);
            op(c, "case-a", RESOLVE);
            typed(c, true);
            String now = replay(c, "case-a").toString();
            for (String raw : List.of(KEY_A, KEY_B, KEY_C, A, B, "234150000000001"))
                assertFalse(now.contains(raw), raw + " leaked after msisdn was masked: " + now);

            settings(c, "masking_mode: all\n");
            String all = data(get(c, "/inv/investigations/case-a/working-set"), 200).toString();
            for (String raw : List.of(KEY_A, KEY_B, IMSI, A, B))
                assertFalse(all.contains(raw), raw + " leaked under maskingMode all: " + all);

            settings(c, "masking_mode: none\n");
            assertTrue(data(get(c, "/inv/investigations/case-a/working-set"), 200).toString().contains(IMSI));
        }
    }
}
