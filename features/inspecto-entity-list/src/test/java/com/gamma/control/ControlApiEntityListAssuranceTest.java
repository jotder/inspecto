package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.gamma.access.Roles;
import com.gamma.access.ComponentAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASSURE-ENTITY-LISTS-1 (WS-12) over real HTTP, ARMED: range / CIDR entries and expiry through
 * {@code POST /entity-lists/{id}/members}, matching through {@code POST /entity-lists/{id}/match}, and the
 * maker-checker hold on list changes: under an approval policy for kind {@code entity-list} a change is held (202,
 * no fact) and applied only when a SECOND person approves it; an add-only change whose every entry expires within
 * 24 h applies at once and says it is to be reviewed after (D-P5).
 */
class ControlApiEntityListAssuranceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MAKER = "Bearer maker", CHECKER = "Bearer checker", SELF = "Bearer self",
            ADMIN = "Bearer admin", VIEWER = "Bearer viewer";
    private static final String BLOCK = "{\"id\":\"bl\",\"title\":\"Fraud blocks\",\"purpose\":\"block\","
            + "\"entityType\":\"msisdn\",\"reason\":\"FR-9\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** One seeded role per caller, recognised against the Space's role table (the OIDC shape the hold needs). */
    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (VIEWER.equals(h)) return Optional.of(new Subject("viewer-1", Set.of()));
            String[] who = switch (h) {
                case MAKER -> new String[] {"maker-1", "operations"};
                case CHECKER -> new String[] {"checker-1", "admin"};
                case SELF -> new String[] {"maker-1", "admin"};
                case ADMIN -> new String[] {"admin-1", "admin"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            if (writeRoot != null) seedApproverRoster(writeRoot);   // OIDC-shaped Authenticator: the Space's approver roster decides
            ControlApi api = new ControlApi(svc, 0);
            api.start();
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

    private static int facts(Ctx c) throws Exception {
        Path dir = c.root().resolve("audit/entity-facts");
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().matches("\\d{12}\\.json")).count();
        }
    }

    private JsonNode match(Ctx c, String... values) throws Exception {
        return data(send(c, "POST", "/entity-lists/bl/match", JSON.writeValueAsString(
                java.util.Map.of("values", List.of(values))), VIEWER), 200).get("matches");
    }

    private static List<Boolean> matched(JsonNode matches) {
        List<Boolean> out = new ArrayList<>();
        matches.forEach(m -> out.add(m.get("matched").asBoolean()));
        return out;
    }

    private static void noMasking(Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
    }

    // ── ranges, CIDR, expiry, match ────────────────────────────────────────────────────────────────────

    @Test
    void rangesAndCidrBlocksMatchOverHttpAndAnExpiredEntryDoesNot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            noMasking(root);
            data(send(c, "POST", "/entity-lists", BLOCK, MAKER), 201);
            String soon = Instant.now().plusMillis(1500).toString();
            JsonNode r = data(send(c, "POST", "/entity-lists/bl/members", "{\"add\":[\"+44 7700 900001\"],"
                    + "\"addRanges\":[{\"prefix\":\"+4479\"},{\"from\":\"+447800000000\",\"to\":\"+447800000999\"},"
                    + "{\"cidr\":\"10.1.0.0/16\"},{\"cidr\":\"2001:db8::/32\"}],\"reason\":\"FR-9\"}", MAKER), 200);
            assertEquals(5, r.get("changed").asInt());
            assertEquals(4, r.get("rangeCount").asInt());
            assertEquals("none", r.get("sidecar").asText(), "no data root on this harness: nothing to write, and it says so");
            data(send(c, "POST", "/entity-lists/bl/members", "{\"add\":[\"+447700900002\"],\"expiresAt\":\""
                    + soon + "\",\"reason\":\"24h hold\"}", MAKER), 200);

            JsonNode m = match(c, "0044 7700 900001", "+447912345678", "+447800000500", "+447800001000", "10.1.200.3",
                    "::ffff:10.1.0.9", "2001:DB8:1::9", "2001:db9::1", "10.2.0.1", "+447700900002");
            assertEquals(List.of(true, true, true, false, true, true, true, false, false, true), matched(m));
            assertEquals("key", m.get(0).get("match").asText());
            assertEquals("prefix", m.get(1).get("match").asText());
            assertEquals("range", m.get(2).get("match").asText());
            assertEquals("cidr:10.1.0.0/16", m.get(4).get("entry").asText());
            assertEquals("cidr", m.get(6).get("match").asText());

            Thread.sleep(Math.max(0, Instant.parse(soon).toEpochMilli() - System.currentTimeMillis()) + 300);
            assertEquals(List.of(false, true), matched(match(c, "+447700900002", "+447700900001")),
                    "the expired key no longer matches");
            JsonNode list = data(send(c, "GET", "/entity-lists/bl", null, VIEWER), 200);
            assertTrue(texts(list.get("members")).contains("+447700900002"), "the as-of read still shows it");
            assertTrue(list.at("/expiring/0/expired").asBoolean(), list.toString());

            // Refusals write nothing.
            int before = facts(c);
            for (String bad : List.of("{\"addRanges\":[{\"cidr\":\"10.1.2.3/16\"}],\"reason\":\"r\"}",
                    "{\"addRanges\":[{\"from\":\"+4478\",\"to\":\"+44781\"}],\"reason\":\"r\"}",
                    "{\"addRanges\":[{\"prefix\":\"+44\",\"cidr\":\"10.0.0.0/8\"}],\"reason\":\"r\"}",
                    "{\"addRanges\":\"10.0.0.0/8\",\"reason\":\"r\"}",
                    "{\"add\":[\"+441\"],\"expiresAt\":\"2020-01-01T00:00:00Z\",\"reason\":\"r\"}",
                    "{\"add\":[\"+441\"],\"expiresAt\":\"tomorrow\",\"reason\":\"r\"}",
                    "{\"addRanges\":[{\"prefix\":\"+4479\"}],\"removeRanges\":[\"prefix:+4479\"],\"reason\":\"r\"}"))
                assertEquals(422, send(c, "POST", "/entity-lists/bl/members", bad, MAKER).statusCode(), bad);
            assertEquals(before, facts(c), "no refusal wrote a fact");
            assertEquals(422, send(c, "POST", "/entity-lists/bl/match", "{\"values\":[]}", VIEWER).statusCode());
            assertEquals(404, send(c, "POST", "/entity-lists/nope/match", "{\"values\":[\"1\"]}", VIEWER).statusCode());

            data(send(c, "POST", "/entity-lists/bl/members", "{\"removeRanges\":[\"cidr:10.1.0.0/16\"],\"reason\":\"r\"}",
                    MAKER), 200);
            assertEquals(List.of(false), matched(match(c, "10.1.200.3")), "a removed block no longer matches");
            data(send(c, "POST", "/entity-lists/bl/retire", "{\"reason\":\"done\"}", MAKER), 200);
            assertEquals(List.of(false), matched(match(c, "+447700900001")), "a retired list matches nothing");
        }
    }

    @Test
    void anExpiringAddNeverShortensAPermanentEntry(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            noMasking(root);
            data(send(c, "POST", "/entity-lists", BLOCK, MAKER), 201);
            data(send(c, "POST", "/entity-lists/bl/members", "{\"add\":[\"+441\"],\"reason\":\"r\"}", MAKER), 200);
            JsonNode r = data(send(c, "POST", "/entity-lists/bl/members", "{\"add\":[\"+441\"],\"expiresAt\":\""
                    + Instant.now().plusSeconds(60) + "\",\"reason\":\"r\"}", MAKER), 200);
            assertEquals(0, r.get("changed").asInt(), "a no-op");
            assertEquals(0, r.get("expiring").size());
        }
    }

    @Test
    void theMatchRouteIs503WithoutAWriteRootAndMasksAMaskedList(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx none = open(cfg, null)) {
            assertEquals(503, send(none, "POST", "/entity-lists/bl/match", "{\"values\":[\"1\"]}", VIEWER).statusCode());
        }
        try (Ctx c = open(cfg, root)) {
            data(send(c, "POST", "/entity-lists", BLOCK, MAKER), 201);   // msisdn is a masked type by default
            data(send(c, "POST", "/entity-lists/bl/members", "{\"add\":[\"+447700900001\"],"
                    + "\"addRanges\":[{\"prefix\":\"+4479\"}],\"reason\":\"r\"}", MAKER), 200);
            HttpResponse<String> r = send(c, "POST", "/entity-lists/bl/match",
                    "{\"values\":[\"+447700900001\",\"+447912\"]}", VIEWER);
            JsonNode m = data(r, 200).get("matches");
            assertEquals(List.of(true, true), matched(m));
            assertFalse(m.get(0).get("entry").asText().contains("7700"), "the matched entry is masked: " + r.body());
            assertFalse(m.get(1).get("entry").asText().contains("4479"), "a matched range too: " + r.body());
            JsonNode list = data(send(c, "GET", "/entity-lists/bl", null, VIEWER), 200);
            assertFalse(list.get("ranges").toString().contains("4479"), "ranges are masked like members: " + list);
        }
    }

    // ── four-eyes ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aListChangeIsHeldUnderFourEyesAndAppliedByASecondApprover(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Ctx c = open(cfg, root)) {
            noMasking(root);
            data(send(c, "POST", "/entity-lists", BLOCK, MAKER), 201);
            assertEquals(422, send(c, "PUT", "/settings/approval", "{\"approval\":{\"entity-lists\":{\"required\":true}}}",
                    ADMIN).statusCode(), "the kind is named exactly");
            data(send(c, "PUT", "/settings/approval", "{\"approval\":{\"entity-list\":{\"required\":true}}}", ADMIN), 200);
            int before = facts(c);

            // A permanent block: held, nothing written.
            JsonNode held = data(send(c, "POST", "/entity-lists/bl/members",
                    "{\"add\":[\"+447700900001\"],\"addRanges\":[{\"cidr\":\"10.1.0.0/16\"}],\"reason\":\"FR-9\"}", MAKER), 202);
            assertEquals("pending", held.get("status").asText());
            String id = held.at("/pendingChange/id").asText();
            assertEquals(before, facts(c), "a held change writes no fact");
            assertEquals(List.of(false), matched(match(c, "+447700900001")), "and matches nothing yet");
            JsonNode pc = data(send(c, "GET", "/pending-changes/" + id, null, CHECKER), 200);
            assertEquals("entity-list", pc.get("kind").asText());
            assertEquals("bl", pc.get("name").asText());
            JsonNode diff = data(send(c, "GET", "/pending-changes/" + id + "/diff", null, CHECKER), 200);
            assertTrue(diff.toString().contains("cidr:10.1.0.0/16") && diff.toString().contains("+447700900001"),
                    "the approver reads what they approve: " + diff);
            assertEquals(409, send(c, "POST", "/entity-lists/bl/members", "{\"add\":[\"+442\"],\"reason\":\"r\"}",
                    MAKER).statusCode(), "one pending change per list");

            assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", MAKER).statusCode(),
                    "the maker lacks canApproveChanges");
            assertEquals(403, send(c, "POST", "/pending-changes/" + id + "/approve", "{}", SELF).statusCode(),
                    "four-eyes: never the maker, whatever role they hold");
            assertEquals(before, facts(c));
            JsonNode ok = data(send(c, "POST", "/pending-changes/" + id + "/approve", "{\"reason\":\"verified\"}", CHECKER), 200);
            assertTrue(ok.get("applied").asBoolean(), ok.toString());
            assertEquals(before + 2, facts(c), "applied: one fact per effective change");
            assertEquals(List.of(true, true), matched(match(c, "+447700900001", "10.1.9.9")));

            // D-P5: an expiring (<= 24 h) add applies at once and is flagged for review after.
            JsonNode quick = data(send(c, "POST", "/entity-lists/bl/members", "{\"add\":[\"+447700900002\"],"
                    + "\"expiresAt\":\"" + Instant.now().plusSeconds(6 * 3600) + "\",\"reason\":\"hot\"}", MAKER), 200);
            assertTrue(quick.get("reviewAfter").asBoolean(), quick.toString());
            assertEquals(List.of(true), matched(match(c, "+447700900002")));
            // ... but not longer than 24 h, and never a removal.
            data(send(c, "POST", "/entity-lists/bl/members", "{\"add\":[\"+447700900003\"],"
                    + "\"expiresAt\":\"" + Instant.now().plusSeconds(25 * 3600) + "\",\"reason\":\"long\"}", MAKER), 202);
            String longId = data(send(c, "GET", "/pending-changes?status=pending", null, CHECKER), 200).at("/items/0/id").asText();
            data(send(c, "POST", "/pending-changes/" + longId + "/decline", "{\"reason\":\"no\"}", CHECKER), 200);

            // A stale proposal is never applied: the list moves under it.
            String stale = data(send(c, "POST", "/entity-lists/bl/members",
                    "{\"remove\":[\"+447700900001\"],\"reason\":\"cleared\"}", MAKER), 202).at("/pendingChange/id").asText();
            data(send(c, "PUT", "/settings/approval", "{\"approval\":{}}", ADMIN), 200);
            data(send(c, "POST", "/entity-lists/bl/members", "{\"add\":[\"+449\"],\"reason\":\"moved\"}", MAKER), 200);
            data(send(c, "PUT", "/settings/approval", "{\"approval\":{\"entity-list\":{\"required\":true}}}", ADMIN), 200);
            assertEquals(409, send(c, "POST", "/pending-changes/" + stale + "/approve", "{}", CHECKER).statusCode());
            assertEquals(List.of(true), matched(match(c, "+447700900001")), "the stale removal was not applied");

            // Retire is held too: retiring a block list loosens control.
            String retire = data(send(c, "POST", "/entity-lists/bl/retire", "{\"reason\":\"done\"}", MAKER), 202)
                    .at("/pendingChange/id").asText();
            assertFalse(data(send(c, "GET", "/entity-lists/bl", null, VIEWER), 200).get("retired").asBoolean());
            data(send(c, "POST", "/pending-changes/" + retire + "/approve", "{}", CHECKER), 200);
            assertTrue(data(send(c, "GET", "/entity-lists/bl", null, VIEWER), 200).get("retired").asBoolean());
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** The Space's approver roster ({@link ApproverRoster}): every id this class's Authenticator mints. */
    private static void seedApproverRoster(Path root) throws java.io.IOException {
        java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                java.util.Map.of("users", java.util.List.of("admin-1", "checker-1", "maker-1", "viewer-1"))));
    }
}
