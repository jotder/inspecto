package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.audit.EventType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-17 slice 2 (design §8.1) — analyst identity resolution over real HTTP, every gate in house order, with a REAL
 * Subject on every request so {@code withCapability} is enforced, not a no-op: 401 · 403 · 503 · 422 · 409 · 201/200,
 * time travel with {@code at}, masking per member key, and one {@code ENTITY_IDENTITY_CHANGED} per fact.
 */
class ControlApiEntityIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IDS = "/inv/entity-identities";
    private static final String ANALYST = "Bearer analyst";
    private static final String VIEWER = "Bearer viewer";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case VIEWER -> Optional.of(new Subject("viewer-1", Set.of()));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void noSubjects() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
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

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return send(c, "POST", path, body, ANALYST);
    }

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return send(c, "GET", path, null, ANALYST);
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static void status(int expected, HttpResponse<String> r, String why) {
        assertEquals(expected, r.statusCode(), why + ": " + r.body());
    }

    private static String assertion(String a, String b) {
        return "{\"a\":\"" + a + "\",\"b\":\"" + b + "\",\"reason\":\"same SIM in the KYC file\"}";
    }

    private static void unmasked(Ctx c) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: none\n");
    }

    private static int facts(Ctx c) throws Exception {
        Path dir = c.root().resolve("audit/entity-facts");
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().matches("\\d{12}\\.json")).count();
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void everyRouteIs503WithoutAWriteRoot(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            status(503, get(c, IDS), "groups");
            status(503, get(c, IDS + "/group?key=imsi:1"), "group");
            status(503, post(c, IDS, assertion("imsi:1", "msisdn:+441")), "assert");
            status(503, post(c, IDS + "/1/retract", "{\"reason\":\"r\"}"), "retract");
        }
    }

    @Test
    void writesNeedCanManageIncidents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            status(401, send(c, "POST", IDS, assertion("imsi:1", "msisdn:+441"), null), "no credential");
            status(403, send(c, "POST", IDS, assertion("imsi:1", "msisdn:+441"), VIEWER), "assert needs the capability");
            status(403, send(c, "POST", "/inv/entity-identities/1/retract", "{\"reason\":\"r\"}", VIEWER),
                    "retract needs the capability");   // literal path: check-authgate-coverage reads literals only
            assertEquals(0, facts(c), "no refused caller wrote a fact");
            JsonNode made = data(post(c, IDS, assertion("imsi:1", "msisdn:+441")), 201);
            assertEquals("analyst-1", made.at("/assertion/actor").asText(), "the actor is the Subject");
            status(200, get(c, IDS), "the capability holder reads groups");
            status(200, get(c, IDS + "/group?key=imsi:1"), "the capability holder reads a group");
        }
    }

    /**
     * Membership-oracle probe: under masking a Space-only caller could send a GUESSED raw key to the group read and
     * learn from the member count whether it sits in a multi-member identity group (and read its token, to match
     * against Entity List members). Both identity reads therefore need the write capability, always (fail closed).
     */
    @Test
    void identityReadsNeedCanManageIncidentsSoARawKeyCannotBeProbed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c, IDS, assertion("msisdn:+447700900123", "handset:H-1")), 201);
            HttpResponse<String> hit = send(c, "GET", IDS + "/group?key=msisdn:%2B447700900123", null, VIEWER);
            HttpResponse<String> miss = send(c, "GET", IDS + "/group?key=msisdn:%2B447700900999", null, VIEWER);
            status(403, hit, "a guessed key that IS grouped");
            status(403, miss, "a guessed key that is not");
            status(403, send(c, "GET", IDS, null, VIEWER), "the list-groups read");
            status(403, send(c, "GET", "/inv/entity-identities/group", null, VIEWER), "the group read, before any key check");
            status(401, send(c, "GET", IDS + "/group?key=msisdn:%2B447700900123", null, null), "no credential");
            assertEquals(2, data(get(c, IDS + "/group?key=msisdn:%2B447700900123"), 200).at("/group/members").size());
        }
    }

    @Test
    void assertValidatesEveryFieldAndWritesNothingOnRefusal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            status(422, post(c, IDS, "{\"a\":\"imsi:1\",\"b\":\"msisdn:+441\"}"), "missing reason");
            status(422, post(c, IDS, "{\"a\":\"imsi:1\",\"b\":\"msisdn:+441\",\"reason\":\"  \"}"), "blank reason");
            status(422, post(c, IDS, assertion("imsi:1", "447700900123")), "untyped key");
            status(422, post(c, IDS, assertion("imsi:1", ":447700900123")), "empty type");
            status(422, post(c, IDS, "{\"b\":\"imsi:1\",\"reason\":\"r\"}"), "missing a");
            status(422, post(c, IDS, "{\"a\":7,\"b\":\"imsi:1\",\"reason\":\"r\"}"), "non-string a");
            HttpResponse<String> notInForce = post(c, IDS, assertion("imsi:1", "vehicle:AB12"));
            status(422, notInForce, "type not in force");
            assertTrue(notInForce.body().contains("not in force"), notInForce.body());
            status(422, post(c, IDS, assertion("imsi:1", "imsi:-")), "empty after the digits normaliser");
            status(422, post(c, IDS, assertion("msisdn:+44 7700 900123", "msisdn:0044 7700-900123")),
                    "self-assertion after normalising");
            assertEquals(0, facts(c), "no refusal wrote a fact");
        }
    }

    @Test
    void assertNormalisesAndSealsKeysAndMergesTransitively(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            unmasked(c);
            JsonNode first = data(post(c, IDS, assertion("msisdn:+44 7700 900123", "imsi:2340-1")), 201);
            assertEquals("msisdn:+447700900123", first.at("/assertion/a").asText(), "normalised by the type's rule");
            assertEquals("imsi:23401", first.at("/assertion/b").asText());
            assertEquals("analyst", first.at("/assertion/via").asText());
            assertEquals(1, first.at("/assertion/seq").asLong());
            JsonNode fact = JSON.readTree(Files.readString(root.resolve("audit/entity-facts/000000000001.json")));
            assertEquals("identity.asserted", fact.get("kind").asText());
            assertEquals("msisdn:+447700900123", fact.get("a").asText(), "the fact holds the sealed key");
            assertFalse(fact.has("listId"), "an identity fact names no list");

            data(post(c, IDS, assertion("imsi:23401", "wallet:w-9")), 201);
            JsonNode groups = data(get(c, IDS), 200);
            assertEquals(1, groups.get("groups").size(), groups.toString());
            JsonNode g = groups.at("/groups/0");
            assertEquals("imsi:23401", g.get("id").asText(), "id = smallest member key");
            assertEquals(List.of("imsi:23401", "msisdn:+447700900123", "wallet:W-9"), texts(g.get("members")));
            assertEquals(2, g.get("assertions").size(), "both joining assertions are listed");
            assertEquals(2, groups.get("headSeq").asLong());

            JsonNode one = data(get(c, IDS + "/group?key=msisdn:%2B447700900123"), 200);
            assertEquals("imsi:23401", one.at("/group/id").asText());
            JsonNode alone = data(get(c, IDS + "/group?key=imsi:777"), 200);
            assertEquals(List.of("imsi:777"), texts(alone.at("/group/members")), "an unasserted key resolves to itself");
            assertEquals(0, alone.at("/group/assertions").size());
            status(422, get(c, IDS + "/group?key=777"), "the lookup key must be typed");
        }
    }

    @Test
    void retractSplitsTheGroupAndRefusesUnknownOrRepeatedRetraction(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            unmasked(c);
            data(post(c, IDS, assertion("imsi:1", "msisdn:+441")), 201);   // 1
            data(post(c, IDS, assertion("msisdn:+441", "wallet:W")), 201); // 2
            status(422, post(c, IDS + "/2/retract", "{}"), "missing reason");
            status(422, post(c, IDS + "/two/retract", "{\"reason\":\"r\"}"), "not a seq");
            status(409, post(c, IDS + "/9/retract", "{\"reason\":\"r\"}"), "unknown assertion");
            data(post(c, "/entity-lists", "{\"id\":\"wl\",\"title\":\"T\",\"purpose\":\"watch\",\"entityType\":\"imsi\","
                    + "\"reason\":\"r\"}"), 201);                               // 3 — a list fact, not an assertion
            status(409, post(c, IDS + "/3/retract", "{\"reason\":\"r\"}"), "a list fact is not an assertion");
            assertEquals(3, facts(c));

            JsonNode split = data(post(c, IDS + "/1/retract", "{\"reason\":\"wrong SIM\"}"), 200);   // 4
            assertEquals(1, split.get("retracted").asLong());
            assertEquals(2, split.get("groups").size(), "imsi:1 now stands alone: " + split);
            assertEquals(List.of("imsi:1"), texts(split.at("/groups/0/members")));
            assertEquals(List.of("msisdn:+441", "wallet:W"), texts(split.at("/groups/1/members")));
            HttpResponse<String> again = post(c, IDS + "/1/retract", "{\"reason\":\"r\"}");
            status(409, again, "already retracted");
            assertTrue(again.body().contains("already retracted"), again.body());
            assertEquals(4, facts(c), "the refusals wrote nothing");

            JsonNode before = data(get(c, IDS + "?at=2"), 200);
            assertEquals(3, before.at("/groups/0/members").size(), "at=2 reads the merge as it was");
            assertEquals(2, before.get("atSeq").asLong());
            status(422, get(c, IDS + "?at=99"), "beyond the head");
            status(422, get(c, IDS + "?at=x"), "not a seq");
        }
    }

    @Test
    void keysAreMaskedPerTheirEntityType(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // defaults: maskingMode typed; msisdn masked, handset not
            JsonNode made = data(post(c, IDS, assertion("msisdn:+447700900123", "handset:H-1")), 201);
            assertTrue(made.at("/assertion/a").asText().matches("masked:[0-9a-f]{16}"), made.toString());
            assertEquals("handset:h-1", made.at("/assertion/b").asText(), "an unmasked type stays readable");
            JsonNode groups = data(get(c, IDS), 200);
            assertFalse(groups.toString().contains("7700900123"), groups.toString());
            assertTrue(texts(groups.at("/groups/0/members")).contains("handset:h-1"));
        }
    }

    @Test
    void everyFactEmitsEntityIdentityChangedWithSeqsNeverKeys(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                data(post(c, IDS, assertion("imsi:1", "msisdn:+447700900123")), 201);
                data(post(c, IDS + "/1/retract", "{\"reason\":\"r\"}"), 200);
                status(409, post(c, IDS + "/1/retract", "{\"reason\":\"r\"}"), "refused");
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> changed = seen.stream().filter(e -> EventType.ENTITY_IDENTITY_CHANGED.equals(e.type())).toList();
            assertEquals(2, changed.size(), "one per fact; the refusal emitted none: " + changed);
            assertEquals("identity.asserted", changed.get(0).attributes().get("kind"));
            assertEquals("2", changed.get(0).attributes().get("groupSize"));
            assertEquals("identity.retracted", changed.get(1).attributes().get("kind"));
            assertEquals("1", changed.get(1).attributes().get("assertionSeq"));
            assertEquals("2", changed.get(1).attributes().get("seq"));
            assertFalse(changed.toString().contains("7700900123"), "seqs, never keys: " + changed);
        }
    }
}
