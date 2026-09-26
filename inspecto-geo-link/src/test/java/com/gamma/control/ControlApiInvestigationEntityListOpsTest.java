package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-17 step 4 — the Entity List ops {@code excludeBy} and {@code seedBy} over real HTTP, against the op contract
 * ({@code docs/superpower/link-analysis-entity-model-design.md} §4.4.1): every append-time refusal, the sealed list
 * (replay never re-reads it), normalised matching of raw ids, keep protection, byte-identical undo, the template
 * carry, the Dossier, the audit line and masking.
 *
 * <p>Fixture: {@code "0044 7700-900123"–alice}, {@code alice–bob}, {@code alice–carol}, {@code bob–"+447700900555"},
 * {@code carol–dave} over {@code (caller, callee, channel)}. The two phone-number ids are written in RAW forms that
 * differ from their msisdn keys ({@code +447700900123}, {@code +447700900555}), which is what normalisation pivots on.
 */
class ControlApiInvestigationEntityListOpsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MULE = "0044 7700-900123";
    private static final String ROWS = "('" + MULE + "','alice','call'),('alice','bob','sms'),('alice','carol','call'),"
            + "('bob','+447700900555','call'),('carol','dave','sms')";
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String EXCLUDE_BY = "{\"op\":\"excludeBy\",\"listId\":\"mules\",\"reason\":\"known mules\"}";
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
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)", "2026-09-26T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return send(c, "POST", path, body);
    }

    /** A 2xx /api/v1 body is the ENVELOPE — the payload lives under `data`. */
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

    private JsonNode log(Ctx c, String id) throws Exception {
        return data(send(c, "GET", "/inv/investigations/" + id + "/log", null), 200);
    }

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), toon);
    }

    /** An msisdn Entity List {@code id} of {@code purpose}, with {@code members} added in one call (two facts). */
    private void list(Ctx c, String id, String purpose, String... members) throws Exception {
        data(post(c, "/inv/entity-lists", "{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"purpose\":\"" + purpose
                + "\",\"entityType\":\"msisdn\",\"reason\":\"referral\"}"), 201);
        if (members.length > 0) members(c, id, "add", members);
    }

    private void members(Ctx c, String id, String verb, String... values) throws Exception {
        String vs = java.util.Arrays.stream(values).map(v -> "\"" + v + "\"").collect(Collectors.joining(","));
        data(post(c, "/inv/entity-lists/" + id + "/members", "{\"" + verb + "\":[" + vs + "],\"reason\":\"r\"}"), 200);
    }

    private static Set<String> entityIds(JsonNode workingSet) {
        Set<String> out = new TreeSet<>();
        for (JsonNode e : workingSet.get("entities")) out.add(e.get("id").asText());
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }

    private static int logLines(Path root, String id) throws Exception {
        return Files.readAllLines(root.resolve("audit/snapshots/investigations").resolve(id).resolve("log.jsonl")).size();
    }

    // ── refusals (§4.4.1) ──────────────────────────────────────────────────────────────────────────────

    @Test
    void appendRefusesEachBadListOpAndNoneReachesTheLog(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            String ops = "/inv/investigations/case-a/ops";

            assertEquals(422, post(c, ops, "{\"op\":\"excludeBy\",\"reason\":\"r\"}").statusCode(), "listId is required");
            assertEquals(422, post(c, ops, "{\"op\":\"seedBy\",\"listId\":\"Not A List\"}").statusCode(), "listId shape");
            HttpResponse<String> noReason = post(c, ops, "{\"op\":\"excludeBy\",\"listId\":\"mules\"}");
            assertEquals(422, noReason.statusCode(), "an exclusion without a reason cannot be challenged");
            assertTrue(noReason.body().contains("reason"), noReason.body());
            assertEquals(422, post(c, ops, "{\"op\":\"seedBy\",\"listId\":\"mules\",\"ids\":[\"alice\"]}").statusCode(),
                    "a list op names a list, never ids");

            HttpResponse<String> unknown = post(c, ops, EXCLUDE_BY);
            assertEquals(404, unknown.statusCode(), unknown.body());
            assertTrue(unknown.body().contains("mules"), unknown.body());

            list(c, "old", "block", "+447700900123");
            data(post(c, "/inv/entity-lists/old/retire", "{\"reason\":\"superseded\"}"), 200);
            HttpResponse<String> retired = post(c, ops, "{\"op\":\"seedBy\",\"listId\":\"old\"}");
            assertEquals(409, retired.statusCode(), retired.body());
            assertTrue(retired.body().contains("retired"), retired.body());

            list(c, "big", "exclusion");
            members(c, "big", "add", IntStream.range(0, 5_000).mapToObj(i -> "+44" + i).toArray(String[]::new));
            members(c, "big", "add", "+449999999");
            HttpResponse<String> big = post(c, ops, "{\"op\":\"excludeBy\",\"listId\":\"big\",\"reason\":\"r\"}");
            assertEquals(422, big.statusCode(), "a list op seals a bounded payload");
            assertTrue(big.body().contains("5001") && big.body().contains("5000"), big.body());

            list(c, "mules", "exclusion", "+447700900123");
            String handsetOnly = "{\"maskingMode\":\"none\",\"entityTypes\":[{\"id\":\"handset\",\"label\":\"Handset\","
                    + "\"normaliser\":\"default\",\"masked\":false,\"classifications\":[\"HANDSET\"]}]}";
            data(send(c, "PUT", "/settings/link-analysis", handsetOnly), 200);
            HttpResponse<String> notInForce = post(c, ops, EXCLUDE_BY);
            assertEquals(409, notInForce.statusCode(), notInForce.body());
            assertTrue(notInForce.body().contains("no longer in force"), notInForce.body());

            assertEquals(1, logLines(root, "case-a"), "no refused list op reached the log");
        }
    }

    // ── excludeBy ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * The list excludes by NORMALISED key: the member {@code +447700900123} excludes the raw id {@code 0044 7700-900123}
     * now, and the member {@code +447700900555} — not yet admitted — keeps a later expand from admitting its raw id.
     */
    @Test
    void excludeByRemovesMatchingEntitiesNowAndBlocksALaterExpand(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            list(c, "mules", "exclusion", "+447700900123", "0044 7700 900555", "+447700900999");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            assertEquals(List.of(MULE, "bob", "carol"), texts(op(c, "case-a", "{\"op\":\"expand\"}").at("/delta/admitted")));

            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            JsonNode excluded;
            try {
                excluded = op(c, "case-a", EXCLUDE_BY);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            assertEquals(List.of(MULE), texts(excluded.at("/delta/removed")), "normalise(e164, raw id) is a member");
            assertEquals(List.of(MULE), texts(excluded.at("/delta/excluded")));
            assertEquals(1, excluded.at("/list/removed").asInt());
            assertEquals(List.of("+447700900555", "+447700900999"), texts(excluded.at("/list/unmatched")),
                    "members matching no admitted entity are reported, not errors");
            assertEquals(0, excluded.get("protected").size());
            assertEquals(2, excluded.at("/list/atSeq").asLong(), "resolved at the fact log's head");
            assertEquals(3, excluded.at("/list/members").asInt());

            Event stepped = seen.stream().filter(e -> EventType.LINK_INVESTIGATION_STEPPED.equals(e.type()))
                    .findFirst().orElseThrow();
            assertEquals("mules", stepped.attributes().get("listId"));
            assertEquals("2", stepped.attributes().get("atSeq"));
            assertFalse(stepped.toString().contains("7700900"), "the audit names the list, never its members: " + stepped);

            JsonNode grown = op(c, "case-a", "{\"op\":\"expand\"}");
            assertEquals(List.of("dave"), texts(grown.at("/delta/admitted")),
                    "the remembered key keeps +447700900555 out, as exclude keeps an id out");
            JsonNode ws = replay(c, "case-a").get("workingSet");
            assertEquals(Set.of("alice", "bob", "carol", "dave"), entityIds(ws));
            assertEquals(MULE, ws.at("/excluded/0/id").asText());
            assertEquals("known mules", ws.at("/excluded/0/reason").asText());

            JsonNode entry = log(c, "case-a").at("/entries/2");
            assertTrue(entry.get("text").asText().startsWith(
                    "3. Excluded 1 entity on Entity List `mules` (exclusion, 3 members, as of fact 2)"), entry.toString());
            assertTrue(entry.get("text").asText().contains("reason: known mules"), entry.toString());
            assertEquals(3, entry.at("/list/size").asInt());
            assertFalse(entry.get("list").has("members"), "the sealed members stay out of the log view, as rows do");
        }
    }

    @Test
    void keepProtectsAnEntityTheListMatches(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            list(c, "mules", "exclusion", "+447700900123", "+447700900999");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            op(c, "case-a", "{\"op\":\"expand\"}");
            op(c, "case-a", "{\"op\":\"keep\",\"ids\":[\"" + MULE + "\"]}");

            JsonNode refused = op(c, "case-a", EXCLUDE_BY);
            assertEquals(List.of(MULE), texts(refused.get("protected")));
            assertEquals(0, refused.at("/delta/removed").size(), "a kept entity is not removed");
            assertEquals(0, refused.at("/list/removed").asInt());
            assertEquals(List.of("+447700900999"), texts(refused.at("/list/unmatched")), "a protected match still matched");
            assertTrue(entityIds(replay(c, "case-a").get("workingSet")).contains(MULE));
        }
    }

    /** Undo is REAL for a list op too: the state after the undo is byte-identical to the state before it. */
    @Test
    void undoOfAnExcludeByRestoresTheExactPriorWorkingSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            list(c, "mules", "exclusion", "+447700900123", "+447700900555");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            String before = op(c, "case-a", "{\"op\":\"expand\"}").at("/workingSet/hash").asText();
            String excludedHash = op(c, "case-a", EXCLUDE_BY).at("/workingSet/hash").asText();

            JsonNode undone = data(post(c, "/inv/investigations/case-a/undo", ""), 200);
            assertEquals(3, undone.get("undoes").asInt());
            assertEquals(List.of(MULE), texts(undone.at("/delta/admitted")));
            assertEquals(before, undone.at("/workingSet/hash").asText(), "the remembered keys are gone too");
            JsonNode grown = op(c, "case-a", "{\"op\":\"expand\"}");
            assertTrue(texts(grown.at("/delta/admitted")).contains("+447700900555"), "no key blocks the expand any more");

            JsonNode replayed = replay(c, "case-a");
            assertTrue(replayed.get("equivalent").asBoolean(), replayed.toString());
            assertEquals(excludedHash, data(post(c, "/inv/investigations/case-a/replay", "{\"at\":3}"), 200)
                    .at("/workingSet/hash").asText(), "a prefix still holds the excludeBy the later undo reverts");
        }
    }

    /** The list is SEALED at append: changing or retiring it afterwards moves neither replay nor a later expand. */
    @Test
    void replayAfterTheListChangesStillGivesTheSealedResult(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            list(c, "mules", "exclusion", "+447700900123", "+447700900555");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            op(c, "case-a", "{\"op\":\"expand\"}");
            String sealed = op(c, "case-a", EXCLUDE_BY).at("/workingSet/hash").asText();

            members(c, "mules", "remove", "+447700900123", "+447700900555");
            data(post(c, "/inv/entity-lists/mules/retire", "{\"reason\":\"closed\"}"), 200);

            JsonNode replayed = replay(c, "case-a");
            assertTrue(replayed.get("equivalent").asBoolean(), replayed.toString());
            assertEquals(sealed, replayed.at("/workingSet/hash").asText(), "replay never re-reads the list");
            assertFalse(texts(op(c, "case-a", "{\"op\":\"expand\"}").at("/delta/admitted")).contains("+447700900555"),
                    "the sealed keys still block, whatever the list says now");
        }
    }

    /** A fork (D-E4) re-orders the method; a list op keeps the list it sealed, whatever the list says now. */
    @Test
    void aForkKeepsTheSealedListOfItsListOps(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            list(c, "watch", "watch", "+447700900123");
            list(c, "mules", "exclusion", "+447700900555");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seedBy\",\"listId\":\"watch\"}");
            op(c, "case-a", EXCLUDE_BY);
            for (int i = 0; i < 3; i++) op(c, "case-a", "{\"op\":\"expand\"}");
            members(c, "mules", "remove", "+447700900555");   // the list moves on; the sealed op does not

            data(post(c, "/inv/investigations/case-a/reorder", "{\"order\":[1,3,4,5,2],\"id\":\"fork-1\"}"), 200);
            JsonNode fork = replay(c, "fork-1");
            assertTrue(fork.get("equivalent").asBoolean(), fork.toString());
            assertEquals(Set.of(MULE, "alice", "bob", "carol", "dave"), entityIds(fork.get("workingSet")),
                    "the excludeBy now runs LAST and still removes +447700900555 by its sealed member");
            JsonNode forkLog = log(c, "fork-1");
            assertEquals(4, forkLog.at("/entries/4/list/atSeq").asLong(), "the parent's seal, not the list's head (5)");
            assertEquals(1, forkLog.at("/entries/0/list/size").asInt());
        }
    }

    // ── seedBy ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void seedBySeedsTheRawIdsWhoseKeyIsAMemberAndReportsTheUnmatched(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            list(c, "watch", "watch", "+447700900123", "+447700900555", "+447700900999");
            data(post(c, "/inv/investigations", CREATE), 200);

            JsonNode seeded = op(c, "case-a", "{\"op\":\"seedBy\",\"listId\":\"watch\"}");
            assertEquals(List.of("+447700900555", MULE), texts(seeded.at("/delta/admitted")),
                    "raw values from BOTH bound columns, matched by key");
            assertEquals(2, seeded.at("/list/seeded").asInt());
            assertEquals(List.of("+447700900999"), texts(seeded.at("/list/unmatched")));
            assertTrue(seeded.at("/read/fingerprint").asText().startsWith("sha256:"), seeded.toString());

            JsonNode ws = replay(c, "case-a").get("workingSet");
            for (JsonNode e : ws.get("entities")) {
                assertEquals("msisdn", e.get("type").asText(), "seeded with the list's Entity Type");
                assertEquals(0, e.get("hop").asInt());
                assertEquals(e.get("id").asText(), e.get("seed").asText());
            }
            assertTrue(texts(op(c, "case-a", "{\"op\":\"expand\"}").at("/delta/admitted")).containsAll(List.of("alice", "bob")));
            assertTrue(replay(c, "case-a").get("equivalent").asBoolean());

            JsonNode entry = log(c, "case-a").at("/entries/0");
            assertTrue(entry.get("text").asText().startsWith("1. Seeded 2 entities of type msisdn from Entity List "
                    + "`watch` (watch, 3 members, as of fact 2)"), entry.toString());
            assertFalse(entry.get("read").has("ids"), "the sealed ids stay out of the log view");
        }
    }

    /**
     * Under {@code typed} (the default) an msisdn list masks its members and the ids it seeded — and an id it MATCHED,
     * even one an expand admitted untyped: {@code 0044 7700-900123} is the member {@code +447700900123} in another form.
     */
    @Test
    void listOpsMaskMembersAndTheIdsTheyMatchUnderTyped(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            list(c, "watch", "watch", "+447700900555", "+447700900999");
            list(c, "mules", "exclusion", "+447700900123");
            data(post(c, "/inv/investigations", CREATE), 200);
            HttpResponse<String> seeded = post(c, "/inv/investigations/case-a/ops", "{\"op\":\"seedBy\",\"listId\":\"watch\"}");
            JsonNode s = data(seeded, 200);
            assertFalse(seeded.body().contains("900555") || seeded.body().contains("900999"),
                    "neither a seeded id nor an unmatched member leaks: " + seeded.body());
            assertTrue(s.at("/delta/admitted/0").asText().matches("masked:[0-9a-f]{16}"), s.toString());
            assertTrue(s.at("/list/unmatched/0").asText().matches("masked:[0-9a-f]{16}"), s.toString());
            assertFalse(send(c, "GET", "/inv/investigations/case-a/log", null).body().contains("900555"));

            data(post(c, "/inv/investigations", CREATE.replace("case-a", "case-b")), 200);
            op(c, "case-b", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            op(c, "case-b", "{\"op\":\"expand\"}");   // admits the raw 0044 7700-900123 untyped (masking step 5)
            HttpResponse<String> excluded = post(c, "/inv/investigations/case-b/ops", EXCLUDE_BY);
            JsonNode x = data(excluded, 200);
            assertEquals(1, x.at("/delta/removed").size());
            assertTrue(x.at("/delta/removed/0").asText().matches("masked:[0-9a-f]{16}"), x.toString());
            assertFalse(excluded.body().contains("900123"), "the id a typed list matched is masked: " + excluded.body());
            HttpResponse<String> log = send(c, "GET", "/inv/investigations/case-b/log", null);
            assertEquals(200, log.statusCode());
            assertFalse(log.body().contains("900123"), log.body());
            assertTrue(Files.readString(root.resolve("audit/snapshots/investigations/case-b/log.jsonl")).contains(MULE),
                    "masking is render-time: the sealed log keeps the raw id");
        }
    }

    /**
     * 🔴 Review fix: under {@code typed}, EVERY Entity Type flagged {@code masked} masks its lists' keys and the ids they
     * seed or match — not only the D-U6 identifiers (MSISDN/IMSI/ACCOUNT). Wallet, subscriber and a custom type
     * {@code badge} (all masked) must not leak a raw value or key anywhere: the op answers ({@code list.unmatched},
     * the seedBy delta), {@code /replay} ({@code excludedKeys}), {@code /log} and every Dossier rendering. A
     * {@code masked: false} type ({@code handset}) stays raw.
     */
    @Test
    void everyMaskedEntityTypeIsMaskedUnderTypedAndAnUnmaskedOneStaysRaw(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ViewStore(root.resolve("views")).write(new ViewDefinition("tok_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('wal-zq71','sub-qx42','x'),('sub-qx42','bdg-kk93','x'),('bdg-kk93','hs-pp55','x'))"
                            + " AS t(src,dst,kind)", "2026-09-26T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "tok_ds", Map.of("view", "tok_view"));
            String types = "{\"maskingMode\":\"typed\",\"entityTypes\":["
                    + "{\"id\":\"wallet\",\"label\":\"Wallet\",\"normaliser\":\"upper-trim\",\"masked\":true,\"classifications\":[]},"
                    + "{\"id\":\"subscriber\",\"label\":\"Subscriber\",\"normaliser\":\"default\",\"masked\":true,\"classifications\":[]},"
                    + "{\"id\":\"badge\",\"label\":\"Badge\",\"normaliser\":\"upper-trim\",\"masked\":true,\"classifications\":[]},"
                    + "{\"id\":\"handset\",\"label\":\"Handset\",\"normaliser\":\"default\",\"masked\":false,\"classifications\":[]}]}";
            data(send(c, "PUT", "/settings/link-analysis", types), 200);

            // type → {raw value in the Dataset, an unmatched member}; fragments are checked case-insensitively.
            Map<String, List<String>> cases = Map.of("wallet", List.of("wal-zq71", "unm-wal-81"),
                    "subscriber", List.of("sub-qx42", "unm-sub-82"), "badge", List.of("bdg-kk93", "unm-bdg-83"),
                    "handset", List.of("hs-pp55", "unm-hs-84"));
            for (var t : cases.entrySet()) {
                String type = t.getKey(), value = t.getValue().get(0), unmatched = t.getValue().get(1);
                data(post(c, "/inv/entity-lists", "{\"id\":\"l-" + type + "\",\"title\":\"t\",\"purpose\":\"watch\","
                        + "\"entityType\":\"" + type + "\",\"reason\":\"r\"}"), 201);
                members(c, "l-" + type, "add", value, unmatched);
                String inv = "case-" + type;
                data(post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + inv + "\",\"dataset\":\"tok_ds\","
                        + "\"sourceCol\":\"src\",\"targetCol\":\"dst\"}"), 200);
                List<String> bodies = new ArrayList<>();
                String ops = "/inv/investigations/" + inv + "/ops";
                HttpResponse<String> seeded = post(c, ops, "{\"op\":\"seedBy\",\"listId\":\"l-" + type + "\"}");
                assertEquals(1, data(seeded, 200).at("/list/seeded").asInt(), seeded.body());
                bodies.add(seeded.body());
                bodies.add(post(c, ops, "{\"op\":\"excludeBy\",\"listId\":\"l-" + type + "\",\"reason\":\"r\"}").body());
                bodies.add(post(c, "/inv/investigations/" + inv + "/replay", "{}").body());
                bodies.add(send(c, "GET", "/inv/investigations/" + inv + "/log", null).body());
                bodies.add(send(c, "GET", "/inv/investigations/" + inv + "/dossier", null).body());
                bodies.add(send(c, "GET", "/inv/investigations/" + inv + "/dossier?format=steps", null).body());
                bodies.add(send(c, "GET", "/inv/investigations/" + inv + "/dossier?format=method", null).body());
                String all = String.join("\n", bodies).toLowerCase(java.util.Locale.ROOT);
                assertTrue(all.contains("excludedkeys"), "the replay serves the remembered keys: " + type);
                boolean raw = type.equals("handset");
                for (String fragment : List.of(value, unmatched))
                    assertEquals(raw, all.contains(fragment),
                            type + (raw ? " is masked:false and stays raw" : " is masked and must not leak") + ": " + fragment);
            }
        }
    }

    /** LA-17 step 5 (e): under {@code all} a list op masks its members, the remembered keys and the unmatched members. */
    @Test
    void listOpsUnderAllMaskMembersExcludedKeysAndUnmatched(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: all\n");
            list(c, "mules", "exclusion", "+447700900123", "+447700900555");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            op(c, "case-a", "{\"op\":\"expand\"}");
            HttpResponse<String> excluded = post(c, "/inv/investigations/case-a/ops", EXCLUDE_BY);
            JsonNode x = data(excluded, 200);
            assertTrue(x.at("/delta/removed/0").asText().matches("masked:[0-9a-f]{16}"), x.toString());
            assertTrue(x.at("/list/unmatched/0").asText().matches("masked:[0-9a-f]{16}"), x.toString());
            HttpResponse<String> replayed = post(c, "/inv/investigations/case-a/replay", "{}");
            JsonNode keys = data(replayed, 200).at("/workingSet/excludedKeys");
            assertEquals(2, keys.size(), replayed.body());
            for (JsonNode k : keys) assertTrue(k.get("key").asText().startsWith("masked:"), replayed.body());
            HttpResponse<String> listed = send(c, "GET", "/inv/entity-lists/mules", null);
            for (JsonNode m : data(listed, 200).get("members")) assertTrue(m.asText().startsWith("masked:"), listed.body());
            String all = String.join("\n", excluded.body(), replayed.body(), listed.body(),
                    send(c, "GET", "/inv/investigations/case-a/log", null).body());
            assertFalse(all.contains("900123") || all.contains("900555"), all);
        }
    }

    /**
     * LA-17 step 5 (b): the Entity Type's {@code masked} flag is the one truth. A Space that redefines {@code msisdn} as
     * {@code masked: false} sees its ids RAW under {@code typed} — in the Investigation (a seed typed MSISDN, an
     * excludeBy over an msisdn list) exactly as on the Entity List route. (Before step 5 the Investigation forced
     * msisdn / imsi / account masked whatever the type said.)
     */
    @Test
    void anMsisdnTypeRedefinedAsUnmaskedStaysRawInTheInvestigationAndOnTheListRoute(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(send(c, "PUT", "/settings/link-analysis", "{\"maskingMode\":\"typed\",\"entityTypes\":[{\"id\":\"msisdn\","
                    + "\"label\":\"MSISDN\",\"normaliser\":\"e164\",\"masked\":false,\"classifications\":[\"MSISDN\"]}]}"), 200);
            list(c, "mules", "exclusion", "+447700900123", "+447700900555");
            assertEquals(List.of("+447700900123", "+447700900555"),
                    texts(data(send(c, "GET", "/inv/entity-lists/mules", null), 200).get("members")));
            data(post(c, "/inv/investigations", CREATE), 200);
            JsonNode seeded = op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"],\"entityType\":\"MSISDN\"}");
            assertEquals("alice", seeded.at("/delta/admitted/0").asText(), seeded.toString());
            assertEquals(0, seeded.at("/masking/masked").asInt(), seeded.toString());
            op(c, "case-a", "{\"op\":\"expand\"}");
            JsonNode x = op(c, "case-a", EXCLUDE_BY);
            assertEquals(List.of(MULE), texts(x.at("/delta/removed")));
            assertEquals(List.of("+447700900555"), texts(x.at("/list/unmatched")));
            assertEquals(0, x.at("/masking/masked").asInt(), x.toString());
        }
    }

    /** Review nit: an excludeBy over an EMPTY list leaves no {@code excludedKeys} entry, so the state hashes as without it. */
    @Test
    void anExcludeByOverAnEmptyListRemembersNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            list(c, "mules", "exclusion");
            data(post(c, "/inv/investigations", CREATE), 200);
            String seeded = op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}").at("/workingSet/hash").asText();
            assertEquals(seeded, op(c, "case-a", EXCLUDE_BY).at("/workingSet/hash").asText());
            assertFalse(replay(c, "case-a").get("workingSet").has("excludedKeys"));
        }
    }

    /** Review fix: the reason a template generates for an excludeBy stays inside the 200-char reason cap. */
    @Test
    void instantiateWithMaximumLengthIdsStaysInsideTheReasonCap(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            String listId = "m" + "x".repeat(63), tplId = "t" + "y".repeat(127);
            list(c, listId, "exclusion", "+447700900123");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            op(c, "case-a", "{\"op\":\"excludeBy\",\"listId\":\"" + listId + "\",\"reason\":\"r\"}");
            data(post(c, "/inv/investigations/case-a/template", "{\"id\":\"" + tplId + "\"}"), 200);
            data(post(c, "/inv/investigation-templates/" + tplId + "/instantiate",
                    "{\"id\":\"case-t\",\"purpose\":\"rerun\",\"params\":{\"seed1\":[\"alice\"]}}"), 200);
            String reason = log(c, "case-t").at("/entries/1/params/reason").asText();
            assertTrue(reason.length() <= 200 && reason.contains(listId) && reason.contains("…"), reason);
        }
    }

    // ── template carry (D-E8) ──────────────────────────────────────────────────────────────────────────

    @Test
    void aTemplateCarriesOnlyTheListIdAndInstantiateReResolvesAtThatHead(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            list(c, "mules", "exclusion", "+447700900123", "+447700900555");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            op(c, "case-a", "{\"op\":\"expand\"}");
            op(c, "case-a", EXCLUDE_BY);

            HttpResponse<String> saved = post(c, "/inv/investigations/case-a/template", "{\"id\":\"tpl-1\"}");
            JsonNode tpl = data(saved, 200);
            JsonNode carried = tpl.at("/ops/2");
            assertEquals("excludeBy", carried.get("op").asText());
            assertEquals("mules", carried.get("listId").asText());
            assertEquals(Set.of("op", "step", "listId"), fieldNames(carried), "{op, listId} only: " + carried);
            assertFalse(saved.body().contains("7700900") || saved.body().contains("known mules"),
                    "neither the sealed membership nor the case's reason travels: " + saved.body());

            members(c, "mules", "remove", "+447700900123");   // fact 3: the list moves on after the template was saved
            JsonNode inst = data(post(c, "/inv/investigation-templates/tpl-1/instantiate",
                    "{\"id\":\"case-t\",\"purpose\":\"rerun\",\"params\":{\"seed1\":[\"alice\"]}}"), 200);
            assertEquals("case-t", inst.get("id").asText());
            JsonNode step3 = log(c, "case-t").at("/entries/2");
            assertEquals(3, step3.at("/list/atSeq").asLong(), "re-resolved at the head AT INSTANTIATION");
            assertEquals(1, step3.at("/list/size").asInt());
            assertTrue(step3.at("/params/reason").asText().contains("tpl-1"), step3.toString());
            assertTrue(entityIds(replay(c, "case-t").get("workingSet")).contains(MULE),
                    "the old membership did not travel: the removed member no longer excludes");

            data(post(c, "/inv/entity-lists/mules/retire", "{\"reason\":\"closed\"}"), 200);
            HttpResponse<String> refused = post(c, "/inv/investigation-templates/tpl-1/instantiate",
                    "{\"id\":\"case-u\",\"purpose\":\"rerun\",\"params\":{\"seed1\":[\"alice\"]}}");
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("template step 3") && refused.body().contains("retired"), refused.body());
        }
    }

    private static Set<String> fieldNames(JsonNode n) {
        Set<String> out = new TreeSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ── Dossier ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aDossierOverListOpsBuildsVerifiesAndNamesTheRule(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            list(c, "watch", "watch", "+447700900123");
            list(c, "mules", "exclusion", "+447700900555");
            data(post(c, "/inv/investigations", CREATE), 200);
            op(c, "case-a", "{\"op\":\"seedBy\",\"listId\":\"watch\"}");
            for (int i = 0; i < 3; i++) op(c, "case-a", "{\"op\":\"expand\"}");   // the third reaches +447700900555
            op(c, "case-a", EXCLUDE_BY);

            JsonNode d = data(send(c, "GET", "/inv/investigations/case-a/dossier", null), 200);
            assertTrue(d.at("/integrity/intact").asBoolean(), d.get("integrity").toString());
            assertTrue(d.at("/ledger/4/text").asText().contains("Excluded 1 entity on Entity List `mules`"), d.at("/ledger").toString());
            assertTrue(d.at("/ledger/4/text").asText().contains("+447700900555"), "G-E10: every excluded id is named");
            assertTrue(d.at("/negativeSpace/excluded/0/basis").asText().startsWith("stated rule (Entity List `mules`"),
                    d.at("/negativeSpace").toString());
            assertFalse(d.at("/renderings/json/log/0/list").has("members"));

            JsonNode verified = data(post(c, "/inv/investigations/case-a/dossier/verify",
                    "{\"manifest\":" + d.get("manifest") + "}"), 200);
            assertTrue(verified.get("verified").asBoolean(), verified.toString());
        }
    }
}
