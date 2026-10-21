package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-GROW-EXCLUDE-1 phase 4 over real HTTP — admin category exclusion lists on the Entity List routes: the three
 * attributes ({@code ownerKind}, {@code autoApply}, {@code visibility}) recorded in the fact log and folded,
 * {@code PUT /entity-lists/{id}/attributes} with every gate, {@code canManageExclusionCategories} on admin lists (403 for
 * an analyst), and a restricted list's members hidden from a caller without the category or reveal grant.
 */
class ControlApiEntityListCategoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "Bearer admin", ANALYST = "Bearer analyst", REVEALER = "Bearer revealer",
            VIEWER = "Bearer viewer", AUTHOR = "Bearer author";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void standardEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));
    }

    @AfterEach
    void reset() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        Authenticators.forTest(null);
    }

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canManageIncidents", "canManageExclusionCategories")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case REVEALER -> Optional.of(new Subject("revealer-1", Set.of("canRevealLinkEntities")));
            case VIEWER -> Optional.of(new Subject("viewer-1", Set.of()));
            case AUTHOR -> Optional.of(new Subject("author-1", Set.of("canAuthorWorkbench")));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: none\n");
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

    private static void status(int expected, HttpResponse<String> r, String why) {
        assertEquals(expected, r.statusCode(), why + ": " + r.body());
    }

    private static String list(String id, String purpose, String extra) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"purpose\":\"" + purpose + "\",\"entityType\":\"msisdn\","
                + "\"reason\":\"policy\"" + extra + "}";
    }

    private static List<Path> facts(Ctx c) throws Exception {
        Path dir = c.root().resolve("audit/entity-facts");
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("\\d{12}\\.json")).sorted().toList();
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ── the attributes: defaults, recorded, folded ─────────────────────────────────────────────────────

    @Test
    void anExclusionListRecordsItsAttributesAndOtherPurposesRefuseThem(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode plain = data(send(c, "POST", "/entity-lists", list("shops", "exclusion", ""), null), 201);
            assertEquals("analyst", plain.get("ownerKind").asText(), plain.toString());
            assertFalse(plain.get("autoApply").asBoolean());
            assertEquals("open", plain.get("visibility").asText());
            JsonNode created = JSON.readTree(Files.readString(facts(c).get(0)));
            assertEquals("analyst", created.get("ownerKind").asText(), "list.created records the defaults: " + created);
            assertFalse(created.get("autoApply").asBoolean());
            assertEquals("open", created.get("visibility").asText());

            JsonNode cat = data(send(c, "POST", "/entity-lists", list("vips", "exclusion",
                    ",\"ownerKind\":\"admin\",\"autoApply\":true,\"visibility\":\"restricted\""), null), 201);
            assertEquals("admin", cat.get("ownerKind").asText());
            assertTrue(cat.get("autoApply").asBoolean());
            assertEquals("restricted", cat.get("visibility").asText());

            status(422, send(c, "POST", "/entity-lists", list("w", "watch", ",\"ownerKind\":\"admin\""), null),
                    "a watch list carries no ownership");
            status(422, send(c, "POST", "/entity-lists", list("a1", "exclusion", ",\"autoApply\":true"), null),
                    "autoApply is a category-list attribute");
            status(422, send(c, "POST", "/entity-lists", list("a2", "exclusion", ",\"visibility\":\"restricted\""), null),
                    "restricted is a category-list attribute");
            status(422, send(c, "POST", "/entity-lists", list("a3", "exclusion", ",\"ownerKind\":\"boss\""), null), "ownerKind");
            status(422, send(c, "POST", "/entity-lists", list("a4", "exclusion", ",\"autoApply\":\"yes\""), null), "autoApply type");
            assertEquals(2, facts(c).size(), "no refused create wrote a fact");

            JsonNode watch = data(send(c, "POST", "/entity-lists", list("wl", "watch", ""), null), 201);
            assertFalse(watch.has("ownerKind"), "a non-exclusion list shows no attributes: " + watch);
            JsonNode all = data(send(c, "GET", "/entity-lists", null, null), 200);
            assertEquals("admin", all.get("lists").get(1).get("ownerKind").asText(), all.toString());
        }
    }

    @Test
    void attributesRouteChangesOnlyWhatDiffersAndTheFoldTimeTravels(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(send(c, "POST", "/entity-lists", list("pols", "exclusion", ""), null), 201);
            data(send(c, "POST", "/entity-lists/pols/members", "{\"add\":[\"+441\"],\"reason\":\"r\"}", null), 200);
            JsonNode up = data(send(c, "PUT", "/entity-lists/pols/attributes",
                    "{\"ownerKind\":\"admin\",\"visibility\":\"restricted\",\"autoApply\":false,\"reason\":\"category\"}", null), 200);
            assertEquals(2, up.get("changed").asInt(), "autoApply was already false: " + up);
            assertEquals("admin", up.get("ownerKind").asText());
            assertEquals("restricted", up.get("visibility").asText());
            JsonNode fact = JSON.readTree(Files.readString(facts(c).get(2)));
            assertEquals("list.attributes-changed", fact.get("kind").asText());
            assertFalse(fact.has("autoApply"), "only the keys that change are recorded: " + fact);

            JsonNode same = data(send(c, "PUT", "/entity-lists/pols/attributes", "{\"visibility\":\"restricted\",\"reason\":\"r\"}", null), 200);
            assertEquals(0, same.get("changed").asInt());
            assertEquals(3, facts(c).size(), "a no-op writes no fact");

            JsonNode before = data(send(c, "GET", "/entity-lists/pols?at=2", null, null), 200);
            assertEquals("analyst", before.get("ownerKind").asText(), "as of seq 2 it was an analyst list: " + before);
            assertEquals("open", before.get("visibility").asText());

            status(422, send(c, "PUT", "/entity-lists/pols/attributes", "{\"reason\":\"r\"}", null), "no attribute");
            status(422, send(c, "PUT", "/entity-lists/pols/attributes", "{\"ownerKind\":\"admin\"}", null), "no reason");
            status(422, send(c, "PUT", "/entity-lists/pols/attributes", "{\"ownerKind\":\"analyst\",\"reason\":\"r\"}", null),
                    "an analyst list cannot stay restricted");
            status(404, send(c, "PUT", "/entity-lists/nope/attributes", "{\"autoApply\":true,\"reason\":\"r\"}", null), "unknown");
            data(send(c, "POST", "/entity-lists", list("wl", "watch", ""), null), 201);
            status(422, send(c, "PUT", "/entity-lists/wl/attributes", "{\"ownerKind\":\"admin\",\"reason\":\"r\"}", null),
                    "a watch list carries no attributes");
            data(send(c, "POST", "/entity-lists/pols/retire", "{\"reason\":\"r\"}", null), 200);
            status(409, send(c, "PUT", "/entity-lists/pols/attributes", "{\"autoApply\":true,\"reason\":\"r\"}", null), "retired");
        }
    }

    @Test
    void theAttributesRouteIs503WithoutAWriteRoot(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            status(503, send(c, "PUT", "/entity-lists/x/attributes", "{\"autoApply\":true,\"reason\":\"r\"}", null), "no root");
        }
    }

    // ── canManageExclusionCategories (armed) ───────────────────────────────────────────────────────────

    @Test
    void categoryListsNeedTheCategoryCapabilityAnalystListsKeepCanManageIncidents(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            String admin = list("vips", "exclusion", ",\"ownerKind\":\"admin\"");
            status(401, send(c, "PUT", "/entity-lists/vips/attributes", "{\"autoApply\":true,\"reason\":\"r\"}", null),
                    "no credential");
            status(403, send(c, "POST", "/entity-lists", admin, ANALYST), "an analyst cannot create a category list");
            assertEquals(0, facts(c).size(), "the refused create wrote nothing");
            data(send(c, "POST", "/entity-lists", admin, ADMIN), 201);
            data(send(c, "POST", "/entity-lists", list("shops", "exclusion", ""), ANALYST), 201);

            status(403, send(c, "POST", "/entity-lists/vips/members", "{\"add\":[\"+441\"],\"reason\":\"r\"}", ANALYST),
                    "an analyst cannot edit a category list's members");
            status(403, send(c, "POST", "/entity-lists/vips/retire", "{\"reason\":\"r\"}", ANALYST),
                    "an analyst cannot retire a category list");
            status(403, send(c, "PUT", "/entity-lists/vips/attributes", "{\"autoApply\":true,\"reason\":\"r\"}", ANALYST),
                    "an analyst cannot change a category list's attributes");
            status(403, send(c, "PUT", "/entity-lists/shops/attributes", "{\"ownerKind\":\"admin\",\"reason\":\"r\"}", ANALYST),
                    "nor promote an analyst list to a category list");
            assertEquals(2, facts(c).size(), "no refused write reached the log");

            data(send(c, "POST", "/entity-lists/shops/members", "{\"add\":[\"+442\"],\"reason\":\"r\"}", ANALYST), 200);
            data(send(c, "POST", "/entity-lists/vips/members", "{\"add\":[\"+441\"],\"reason\":\"r\"}", ADMIN), 200);
            JsonNode on = data(send(c, "PUT", "/entity-lists/vips/attributes", "{\"autoApply\":true,\"reason\":\"r\"}", ADMIN), 200);
            assertTrue(on.get("autoApply").asBoolean(), on.toString());
            data(send(c, "POST", "/entity-lists/vips/retire", "{\"reason\":\"r\"}", ADMIN), 200);
        }
    }

    // ── restricted members (GE-8) ──────────────────────────────────────────────────────────────────────

    @Test
    void aRestrictedListHidesItsMembersFromACallerWithoutTheGrant(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            data(send(c, "POST", "/entity-lists", list("intel", "exclusion",
                    ",\"ownerKind\":\"admin\",\"visibility\":\"restricted\""), ADMIN), 201);
            JsonNode added = data(send(c, "POST", "/entity-lists/intel/members",
                    "{\"add\":[\"+441\",\"+442\",\"+443\"],\"addRanges\":[{\"prefix\":\"+4477\"}],\"reason\":\"r\"}", ADMIN), 200);
            assertEquals(List.of("+441", "+442", "+443"), texts(added.get("members")), "the manager sees them: " + added);

            JsonNode hidden = data(send(c, "GET", "/entity-lists/intel", null, ANALYST), 200);
            assertTrue(hidden.get("members").isNull(), "members: null - " + hidden);
            assertTrue(hidden.get("membersHidden").asBoolean());
            assertTrue(hidden.get("ranges").isNull() && hidden.get("expiring").isNull(), hidden.toString());
            assertEquals(3, hidden.get("size").asInt(), "the size stays");
            assertEquals("intel", hidden.get("title").asText(), "the name stays");
            assertFalse(hidden.toString().contains("+441"), hidden.toString());

            JsonNode revealed = data(send(c, "GET", "/entity-lists/intel", null, REVEALER), 200);
            assertEquals(3, revealed.get("members").size(), "canRevealLinkEntities sees the members: " + revealed);
            assertFalse(revealed.has("membersHidden"));

            status(403, send(c, "POST", "/entity-lists/intel/match", "{\"values\":[\"+441\"]}", ANALYST),
                    "a match would answer membership");
            JsonNode m = data(send(c, "POST", "/entity-lists/intel/match", "{\"values\":[\"+441\"]}", REVEALER), 200);
            assertTrue(m.get("matches").get(0).get("matched").asBoolean(), m.toString());
            status(403, send(c, "POST", "/entity-lists/intel/register-dataset", "{}", AUTHOR),
                    "a Dataset over a restricted list would serve every member");

            data(send(c, "PUT", "/entity-lists/intel/attributes", "{\"visibility\":\"open\",\"reason\":\"declassified\"}", ADMIN), 200);
            JsonNode open = data(send(c, "GET", "/entity-lists/intel", null, ANALYST), 200);
            assertEquals(3, open.get("members").size(), "an open list shows its members to any reader: " + open);
            long openSeq = open.get("atSeq").asLong();
            data(send(c, "PUT", "/entity-lists/intel/attributes", "{\"visibility\":\"restricted\",\"reason\":\"again\"}", ADMIN), 200);
            JsonNode past = data(send(c, "GET", "/entity-lists/intel?at=" + openSeq, null, ANALYST), 200);
            assertTrue(past.get("members").isNull() && past.get("membersHidden").asBoolean(),
                    "restricted NOW hides the members of an older, open position too: " + past);
        }
    }
}
