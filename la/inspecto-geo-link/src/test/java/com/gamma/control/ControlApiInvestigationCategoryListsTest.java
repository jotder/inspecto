package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-GROW-EXCLUDE-1 phase 4 over real HTTP — admin category exclusion lists in Link Analysis: an {@code autoApply} list
 * becomes a NEW Investigation's first recorded {@code excludeBy} step (replay reproduces it), {@code GET /inv/exclusion-lists}
 * for the graph's chips (Basic included), and a restricted list's members hidden — with removed counts rounded — from a
 * caller holding neither {@code canManageExclusionCategories} nor {@code canRevealLinkEntities}.
 *
 * <p>Fixture as {@code ControlApiInvestigationEntityListOpsTest}: {@code "0044 7700-900123"–alice}, {@code alice–bob},
 * {@code alice–carol}, {@code bob–"+447700900555"}, {@code carol–dave} over {@code (caller, callee, channel)}.
 */
class ControlApiInvestigationCategoryListsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MULE = "0044 7700-900123";
    private static final String ROWS = "('" + MULE + "','alice','call'),('alice','bob','sms'),('alice','carol','call'),"
            + "('bob','+447700900555','call'),('carol','dave','sms')";
    private static final String ADMIN = "Bearer admin", ANALYST = "Bearer analyst", LEAD = "Bearer lead";
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
        EditionFeatures.overrideForTest(null);
    }

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canManageIncidents", "canManageExclusionCategories")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case LEAD -> Optional.of(new Subject("lead-1", Set.of("canManageIncidents", "canRevealLinkEntities")));
            default -> Optional.empty();
        });
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
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: none\n");
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

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String create(String id) {
        return "{\"purpose\":\"test\",\"id\":\"" + id + "\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                + "\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    }

    private JsonNode op(Ctx c, String id, String body, String auth) throws Exception {
        return data(send(c, "POST", "/inv/investigations/" + id + "/ops", body, auth), 200);
    }

    /** An msisdn exclusion list with {@code attrs} (a JSON fragment) and {@code members}, written by {@code auth}. */
    private void list(Ctx c, String id, String attrs, String auth, String... members) throws Exception {
        data(send(c, "POST", "/entity-lists", "{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"purpose\":\"exclusion\","
                + "\"entityType\":\"msisdn\",\"reason\":\"policy\"" + attrs + "}", auth), 201);
        if (members.length == 0) return;
        String vs = java.util.Arrays.stream(members).map(v -> "\"" + v + "\"").collect(Collectors.joining(","));
        data(send(c, "POST", "/entity-lists/" + id + "/members", "{\"add\":[" + vs + "],\"reason\":\"r\"}", auth), 200);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }

    private static Set<String> entityIds(JsonNode workingSet) {
        Set<String> out = new TreeSet<>();
        for (JsonNode e : workingSet.get("entities")) out.add(e.get("id").asText());
        return out;
    }

    // ── auto-apply (GE-9) ──────────────────────────────────────────────────────────────────────────────

    @Test
    void anAutoApplyCategoryListIsANewInvestigationsFirstRecordedStep(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            list(c, "vips", ",\"ownerKind\":\"admin\",\"autoApply\":true", null, "+447700900123", "+447700900555");
            list(c, "optional", ",\"ownerKind\":\"admin\"", null, "+447700900999");     // admin, not auto-applied
            list(c, "shops", "", null, "+447700900888");                                // analyst list
            list(c, "old", ",\"ownerKind\":\"admin\",\"autoApply\":true", null, "+447700900777");
            data(send(c, "POST", "/entity-lists/old/retire", "{\"reason\":\"superseded\"}", null), 200);

            JsonNode created = data(send(c, "POST", "/inv/investigations", create("case-a"), null), 200);
            assertEquals(List.of("vips"), texts(created.get("autoApplied")), created.toString());

            JsonNode log = data(send(c, "GET", "/inv/investigations/case-a/log", null, null), 200);
            assertEquals(1, log.get("entries").size(), log.toString());
            JsonNode first = log.at("/entries/0");
            assertEquals("excludeBy", first.get("op").asText());
            assertEquals("vips", first.at("/params/listId").asText());
            assertEquals("auto-applied category list", first.at("/params/reason").asText());
            assertEquals(2, first.at("/list/size").asInt(), "sealed like any excludeBy: " + first);

            op(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}", null);
            JsonNode grown = op(c, "case-a", "{\"op\":\"expand\"}", null);
            assertEquals(List.of("bob", "carol"), texts(grown.at("/delta/admitted")), "the category list keeps the mule out");
            JsonNode next = op(c, "case-a", "{\"op\":\"expand\"}", null);
            assertEquals(List.of("dave"), texts(next.at("/delta/admitted")), "and +447700900555 too");

            JsonNode replay = data(send(c, "POST", "/inv/investigations/case-a/replay", "{}", null), 200);
            assertTrue(replay.get("equivalent").asBoolean(), "replay reproduces the auto-applied step: " + replay);
            assertEquals(Set.of("alice", "bob", "carol", "dave"), entityIds(replay.get("workingSet")));

            // the list changes later: the Investigation keeps what it sealed, a new one applies the list as it is now
            data(send(c, "POST", "/entity-lists/vips/members", "{\"remove\":[\"+447700900123\"],\"reason\":\"r\"}", null), 200);
            assertTrue(data(send(c, "POST", "/inv/investigations/case-a/replay", "{}", null), 200).get("equivalent").asBoolean());
            data(send(c, "PUT", "/entity-lists/vips/attributes", "{\"autoApply\":false,\"reason\":\"optional now\"}", null), 200);
            JsonNode plain = data(send(c, "POST", "/inv/investigations", create("case-b"), null), 200);
            assertFalse(plain.has("autoApplied"), "no list auto-applies any more: " + plain);
            assertEquals(0, data(send(c, "GET", "/inv/investigations/case-b/log", null, null), 200).get("entries").size());
        }
    }

    @Test
    void aCategoryListThatCannotBeSealedRefusesTheCreateAndWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            list(c, "vips", ",\"ownerKind\":\"admin\",\"autoApply\":true", null, "+447700900123");
            String handsetOnly = "{\"maskingMode\":\"none\",\"entityTypes\":[{\"id\":\"handset\",\"label\":\"Handset\","
                    + "\"normaliser\":\"default\",\"masked\":false,\"classifications\":[\"HANDSET\"]}]}";
            data(send(c, "PUT", "/settings/link-analysis", handsetOnly, null), 200);
            HttpResponse<String> refused = send(c, "POST", "/inv/investigations", create("case-a"), null);
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("vips"), refused.body());
            assertFalse(Files.exists(root.resolve("audit/snapshots/investigations/case-a")), "nothing was written");
        }
    }

    @Test
    void anAutoApplyListThatCannotBeSealedSaysSoOnItsChipBeforeAnyCreateIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            list(c, "vips", ",\"ownerKind\":\"admin\",\"autoApply\":true", null, "+447700900123");
            list(c, "optional", ",\"ownerKind\":\"admin\"", null, "+447700900124");
            JsonNode before = data(send(c, "GET", "/inv/exclusion-lists", null, null), 200).get("lists");
            for (JsonNode l : before) assertFalse(l.has("problem"), "a sealable list has no problem: " + l);
            String handsetOnly = "{\"maskingMode\":\"none\",\"entityTypes\":[{\"id\":\"handset\",\"label\":\"Handset\","
                    + "\"normaliser\":\"default\",\"masked\":false,\"classifications\":[\"HANDSET\"]}]}";
            data(send(c, "PUT", "/settings/link-analysis", handsetOnly, null), 200);
            JsonNode after = data(send(c, "GET", "/inv/exclusion-lists", null, null), 200).get("lists");
            for (JsonNode l : after) {
                if ("vips".equals(l.get("id").asText()))
                    assertTrue(l.get("problem").asText().contains("no longer in force"), l.toString());
                else assertFalse(l.has("problem"), "only an auto-apply list can refuse a create: " + l);
            }
        }
    }

    // ── GET /inv/exclusion-lists ───────────────────────────────────────────────────────────────────────

    @Test
    void exclusionListsAnswersTheChipsWithoutMembersInBasicToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            list(c, "shops", "", null, "+447700900888");
            list(c, "vips", ",\"ownerKind\":\"admin\",\"autoApply\":true,\"visibility\":\"restricted\"", null,
                    "+447700900123", "+447700900555");
            list(c, "gone", "", null);
            data(send(c, "POST", "/entity-lists/gone/retire", "{\"reason\":\"r\"}", null), 200);
            data(send(c, "POST", "/entity-lists", "{\"id\":\"wl\",\"title\":\"wl\",\"purpose\":\"watch\",\"entityType\":\"msisdn\","
                    + "\"reason\":\"r\"}", null), 201);

            EditionFeatures.overrideForTest(Set.of());   // Basic: no Investigations, the chips still read
            assertEquals(503, send(c, "POST", "/inv/investigations", create("case-a"), null).statusCode(), "Basic has no Investigations");
            JsonNode lists = data(send(c, "GET", "/inv/exclusion-lists", null, null), 200).get("lists");
            assertEquals(2, lists.size(), "live exclusion lists only: " + lists);
            JsonNode vips = lists.get(0);
            assertEquals("vips", vips.get("id").asText(), "category lists first");
            assertEquals("admin", vips.get("ownerKind").asText());
            assertTrue(vips.get("autoApply").asBoolean());
            assertEquals("restricted", vips.get("visibility").asText());
            assertEquals("msisdn", vips.get("entityType").asText());
            assertEquals(2, vips.get("size").asInt());
            assertEquals(Set.of("id", "title", "entityType", "ownerKind", "autoApply", "visibility", "size"),
                    fieldNames(vips));
            assertEquals("shops", lists.get(1).get("id").asText());
            assertFalse(lists.toString().contains("7700900"), "never a member: " + lists);
        }
    }

    @Test
    void exclusionListsIs503WithoutAWriteRoot(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try (Ctx c = new Ctx(svc, api, api.port(), null)) {
            assertEquals(503, send(c, "GET", "/inv/exclusion-lists", null, null).statusCode());
        }
    }

    private static Set<String> fieldNames(JsonNode n) {
        Set<String> out = new TreeSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ── restricted members and rounded counts (GE-8, plan §7) ──────────────────────────────────────────

    @Test
    void aRestrictedListNeverEchoesItsMembersAndRoundsWhatItRemoved(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            list(c, "intel", ",\"ownerKind\":\"admin\",\"visibility\":\"restricted\"", ADMIN,
                    "+447700900123", "+447700900555", "+447700900999");
            for (String[] who : new String[][]{{ANALYST, "case-an"}, {LEAD, "case-ld"}}) {
                data(send(c, "POST", "/inv/investigations", create(who[1]), who[0]), 200);
                op(c, who[1], "{\"op\":\"seed\",\"ids\":[\"alice\"]}", who[0]);
                op(c, who[1], "{\"op\":\"expand\"}", who[0]);
                op(c, who[1], "{\"op\":\"keep\",\"ids\":[\"bob\"]}", who[0]);
            }
            String excludeBy = "{\"op\":\"excludeBy\",\"listId\":\"intel\",\"reason\":\"policy\"}";

            JsonNode hidden = op(c, "case-an", excludeBy, ANALYST);
            assertTrue(hidden.at("/list/unmatched").isNull(), "member keys never echo: " + hidden);
            assertTrue(hidden.at("/list/membersHidden").asBoolean());
            assertEquals(5, hidden.at("/list/removed").asInt(), "1 removed reads as 5 (never below 5)");
            assertEquals(3, hidden.at("/list/members").asInt(), "the size stays");
            assertFalse(hidden.toString().contains("+447700900"), hidden.toString());

            JsonNode shown = op(c, "case-ld", excludeBy, LEAD);
            assertEquals(1, shown.at("/list/removed").asInt(), "canRevealLinkEntities reads the true count: " + shown);
            assertEquals(List.of("+447700900555", "+447700900999"), texts(shown.at("/list/unmatched")));
            assertFalse(shown.at("/list").has("membersHidden"));

            JsonNode anLog = data(send(c, "GET", "/inv/investigations/case-an/log", null, ANALYST), 200).at("/entries/3");
            assertTrue(anLog.get("text").asText().startsWith("4. Excluded 5 entities on Entity List `intel`"), anLog.toString());
            JsonNode ldLog = data(send(c, "GET", "/inv/investigations/case-ld/log", null, LEAD), 200).at("/entries/3");
            assertTrue(ldLog.get("text").asText().startsWith("4. Excluded 1 entity on Entity List `intel`"), ldLog.toString());

            JsonNode anWs = data(send(c, "POST", "/inv/investigations/case-an/replay", "{}", ANALYST), 200).get("workingSet");
            assertFalse(anWs.has("excludedKeys"), "the remembered keys are members: " + anWs);
            assertTrue(anWs.get("excludedKeysHidden").asBoolean());
            assertFalse(anWs.toString().contains("+447700900"), anWs.toString());
            JsonNode ldWs = data(send(c, "POST", "/inv/investigations/case-ld/replay", "{}", LEAD), 200).get("workingSet");
            assertEquals(3, ldWs.get("excludedKeys").size(), ldWs.toString());
            assertEquals(anWs.get("hash").asText(), ldWs.get("hash").asText(), "hiding is render-time: the state is the same");

            String seedBy = "{\"op\":\"seedBy\",\"listId\":\"intel\"}";
            HttpResponse<String> refused = send(c, "POST", "/inv/investigations/case-an/ops", seedBy, ANALYST);
            assertEquals(403, refused.statusCode(), "seeding would admit the members as entities: " + refused.body());
            assertEquals(4, data(send(c, "GET", "/inv/investigations/case-an/log", null, ANALYST), 200).get("entries").size(),
                    "the refused seedBy reached no log");
            op(c, "case-ld", seedBy, LEAD);   // a holder of canRevealLinkEntities may
        }
    }

    // ── the Dossier over a restricted list (LA-GROW-EXCLUDE-DOSSIER-RESTRICTED-1) ─────────────────────────

    @Test
    void theDossierNamesNoRemovedIdOfARestrictedListAndRoundsItsCounts(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            list(c, "intel", ",\"ownerKind\":\"admin\",\"visibility\":\"restricted\"", ADMIN,
                    "+447700900123", "+447700900555", "+447700900999");
            for (String[] who : new String[][]{{ANALYST, "case-an"}, {LEAD, "case-ld"}}) {
                data(send(c, "POST", "/inv/investigations", create(who[1]), who[0]), 200);
                op(c, who[1], "{\"op\":\"seed\",\"ids\":[\"alice\"]}", who[0]);
                op(c, who[1], "{\"op\":\"expand\"}", who[0]);
                op(c, who[1], "{\"op\":\"excludeBy\",\"listId\":\"intel\",\"reason\":\"policy\"}", who[0]);
            }

            HttpResponse<String> anRaw = send(c, "GET", "/inv/investigations/case-an/dossier", null, ANALYST);
            JsonNode an = data(anRaw, 200);
            assertFalse(anRaw.body().contains(MULE), "the removed member is named nowhere in the analyst's dossier: " + anRaw.body());
            JsonNode row = an.at("/ledger/2");
            assertTrue(row.get("text").asText().startsWith("Excluded about 5 entities on Entity List `intel`"), row.toString());
            assertTrue(row.get("membersHidden").asBoolean(), row.toString());
            JsonNode neg = an.get("negativeSpace");
            assertEquals(0, neg.get("excluded").size(), "no id: " + neg);
            assertEquals(5, neg.get("excludedCount").asInt(), "1 removed reads as 5: " + neg);
            assertEquals(5, neg.at("/excludedHidden/0/count").asInt(), neg.toString());
            assertEquals(3, neg.at("/excludedHidden/0/step").asInt(), neg.toString());
            assertEquals(5, an.at("/summary/excluded").asInt(), an.get("summary").toString());
            assertTrue(an.at("/renderings/method").asText().contains("about 5 entities"), an.at("/renderings/method").asText());
            for (String format : List.of("steps", "method", "html")) {
                HttpResponse<String> r = send(c, "GET", "/inv/investigations/case-an/dossier?format=" + format, null, ANALYST);
                assertEquals(200, r.statusCode(), r.body());
                assertFalse(r.body().contains(MULE), format + ": " + r.body());
            }
            // render time only: the manifest is over the store, so the analyst's copy still verifies
            JsonNode verified = data(send(c, "POST", "/inv/investigations/case-an/dossier/verify",
                    "{\"manifest\":" + an.get("manifest") + "}", ANALYST), 200);
            assertTrue(verified.get("verified").asBoolean(), verified.toString());

            JsonNode ld = data(send(c, "GET", "/inv/investigations/case-ld/dossier", null, LEAD), 200);
            assertTrue(ld.at("/ledger/2/text").asText().startsWith("Excluded 1 entity on Entity List `intel`"), ld.at("/ledger/2").toString());
            assertTrue(ld.at("/ledger/2/text").asText().contains(MULE), "canRevealLinkEntities reads the ids: " + ld.at("/ledger/2"));
            assertFalse(ld.at("/ledger/2").has("membersHidden"));
            assertEquals(1, ld.at("/negativeSpace/excludedCount").asInt());
            assertEquals(MULE, ld.at("/negativeSpace/excluded/0/id").asText());
            assertFalse(ld.get("negativeSpace").has("excludedHidden"));
        }
    }

    // ── Undo names the step it means (LA-GROW-EXCLUDE-UNDO-RACE-1) ─────────────────────────────────────

    @Test
    void anUndoNamingAStepThatIsNoLongerTheNewestIs409AndUndoesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            String inv = "/inv/investigations/case-u";
            data(send(c, "POST", "/inv/investigations", create("case-u"), ANALYST), 200);
            data(send(c, "POST", inv + "/members", "{\"subject\":\"lead-1\",\"role\":\"lead\"}", ANALYST), 200);
            op(c, "case-u", "{\"op\":\"seed\",\"ids\":[\"alice\"]}", ANALYST);
            op(c, "case-u", "{\"op\":\"expand\"}", ANALYST);                                // the analyst's Grow: step 2
            op(c, "case-u", "{\"op\":\"keep\",\"ids\":[\"bob\"]}", LEAD);                // a team member's step 3

            HttpResponse<String> stale = send(c, "POST", inv + "/undo", "{\"undoes\":2}", ANALYST);
            assertEquals(409, stale.statusCode(), stale.body());
            assertTrue(stale.body().contains("CONFLICT_STALE_VERSION") && stale.body().contains("step 3"), stale.body());
            assertEquals(3, data(send(c, "GET", inv + "/log", null, ANALYST), 200).get("entries").size(), "nothing was undone");

            JsonNode mine = data(send(c, "POST", inv + "/undo", "{\"undoes\":3}", LEAD), 200);
            assertEquals(3, mine.get("undoes").asInt(), "the step named is the newest: " + mine);
            JsonNode grow = data(send(c, "POST", inv + "/undo", "{\"undoes\":2}", ANALYST), 200);
            assertEquals(2, grow.get("undoes").asInt(), grow.toString());
            JsonNode plain = data(send(c, "POST", inv + "/undo", "", ANALYST), 200);
            assertEquals(1, plain.get("undoes").asInt(), "without undoes the newest step is undone, as before: " + plain);

            for (String bad : List.of("\"2\"", "0", "-1", "1.5", "true"))
                assertEquals(422, send(c, "POST", inv + "/undo", "{\"undoes\":" + bad + "}", ANALYST).statusCode(), bad);

            // a Draft's undo takes the same guard
            op(c, "case-u", "{\"op\":\"seed\",\"ids\":[\"alice\"]}", ANALYST);
            String draft = data(send(c, "POST", inv + "/drafts", "{}", ANALYST), 201).get("draftId").asText();
            String drafts = inv + "/drafts/" + draft;
            int own = data(send(c, "POST", drafts + "/ops", "{\"op\":\"expand\"}", ANALYST), 200).get("step").asInt();
            assertEquals(409, send(c, "POST", drafts + "/undo", "{\"undoes\":" + (own - 1) + "}", ANALYST).statusCode());
            assertEquals(own, data(send(c, "POST", drafts + "/undo", "{\"undoes\":" + own + "}", ANALYST), 200).get("undoes").asInt());
        }
    }
}
