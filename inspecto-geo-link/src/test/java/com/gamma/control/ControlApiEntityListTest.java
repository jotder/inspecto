package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-17 step 3 — Entity Lists over real HTTP, against the binding wire contract
 * ({@code docs/superpower/link-analysis-entity-model-design.md} §4.3.1): every gate and status, the {@code changed: 0}
 * no-op, {@code at} time travel, masking under {@code typed · all · none}, and server-side normalisation.
 *
 * <p>The property worth pinning is that the list is a FOLD over an append-only fact log: a no-op writes no fact, a
 * refusal writes no fact, and an old position still reads as it was.
 */
class ControlApiEntityListTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LISTS = "/inv/entity-lists";
    private static final String WATCH = "{\"id\":\"wl\",\"title\":\"Mule watchlist\",\"purpose\":\"watch\","
            + "\"entityType\":\"msisdn\",\"reason\":\"FR-7 referral\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
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

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return send(c.port, "POST", path, body, null);
    }

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return send(c.port, "GET", path, null, null);
    }

    /** A 2xx /api/v1 body is the ENVELOPE — the payload lives under `data`. */
    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static void status(int expected, HttpResponse<String> r, String why) {
        assertEquals(expected, r.statusCode(), why + ": " + r.body());
    }

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), toon);
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

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    // ── 503 ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void everyRouteIs503WithoutAWriteRoot(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            status(503, get(c, LISTS), "list");
            status(503, get(c, "/inv/entity-lists/wl"), "one");
            status(503, post(c, LISTS, WATCH), "create");
            status(503, post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"1\"],\"reason\":\"r\"}"), "members");
            status(503, post(c, "/inv/entity-lists/wl/retire", "{\"reason\":\"r\"}"), "retire");
        }
    }

    // ── create ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createValidatesEveryFieldThenAnswers201AndNeverReusesAnId(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            status(422, post(c, LISTS, WATCH.replace(",\"reason\":\"FR-7 referral\"", "")), "reason is required");
            status(422, post(c, LISTS, WATCH.replace("FR-7 referral", "   ")), "a blank reason is missing");
            status(422, post(c, LISTS, WATCH.replace("\"title\":\"Mule watchlist\",", "")), "title is required");
            status(422, post(c, LISTS, WATCH.replace("\"watch\"", "\"blocklist\"")), "purpose is a closed set");
            HttpResponse<String> old = post(c, LISTS, WATCH.replace("\"watch\"", "\"watchlist\""));
            status(422, old, "the pre-D-P10 name 'watchlist' is refused");
            assertTrue(old.body().contains("[allow, block, watch, exclusion]"), "names the closed set: " + old.body());
            HttpResponse<String> type = post(c, LISTS, WATCH.replace("\"msisdn\"", "\"planet\""));
            status(422, type, "entityType must be in force");
            assertTrue(type.body().contains("msisdn"), "names the types in force: " + type.body());
            status(422, post(c, LISTS, WATCH.replace("\"wl\"", "\"Watch List\"")), "id shape");
            status(422, post(c, LISTS, WATCH.replace("\"wl\"", "\"-wl\"")), "id must start alphanumeric");
            status(422, post(c, LISTS, WATCH.replace("\"wl\"", "\"" + "a".repeat(65) + "\"")), "id is bounded");
            assertEquals(List.of(), facts(c), "a refusal writes no fact");

            JsonNode created = data(post(c, LISTS, WATCH), 201);
            assertEquals("wl", created.get("id").asText());
            assertEquals("Mule watchlist", created.get("title").asText());
            assertEquals("watch", created.get("purpose").asText());
            assertEquals("msisdn", created.get("entityType").asText());
            assertEquals(0, created.get("size").asInt());
            assertFalse(created.get("retired").asBoolean());
            assertEquals("appUser", created.get("createdBy").asText());
            assertFalse(created.get("createdAt").asText().isBlank());
            assertEquals(1, created.get("lastSeq").asLong());
            assertEquals(0, created.get("members").size());
            assertEquals(1, created.get("atSeq").asLong());
            assertEquals(sha256(Files.readAllBytes(facts(c).get(0))), created.get("headHash").asText());

            JsonNode fact = JSON.readTree(Files.readString(facts(c).get(0)));
            assertEquals(List.of("seq", "at", "actor", "reason", "kind", "listId", "title", "purpose", "entityType",
                    "prevHash"), iterable(fact.fieldNames()));
            assertEquals("list.created", fact.get("kind").asText());
            assertEquals("FR-7 referral", fact.get("reason").asText());
            assertEquals("", fact.get("prevHash").asText(), "seq 1 has no predecessor");

            status(409, post(c, LISTS, WATCH), "an existing id");
            JsonNode minted = data(post(c, LISTS, WATCH.replace("\"id\":\"wl\",", "")), 201);
            assertTrue(minted.get("id").asText().matches("^[a-z0-9][a-z0-9_-]{0,63}$"), minted.toString());
            status(200, post(c, "/inv/entity-lists/wl/retire", "{\"reason\":\"closed\"}"), "retire");
            status(409, post(c, LISTS, WATCH), "a retired id is still used");
            assertEquals(3, facts(c).size());
        }
    }

    private static List<String> iterable(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    // ── members ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void membersAreNormalisedServerSideAndANoOpWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            data(post(c, LISTS, WATCH), 201);
            String members = "/inv/entity-lists/wl/members";

            JsonNode added = data(post(c, members,
                    "{\"add\":[\"+44 7700 900123\",\"0044 7700-900123\"],\"reason\":\"from the SIM register\"}"), 200);
            assertEquals(1, added.get("changed").asInt(), "two spellings of one MSISDN are one member");
            assertEquals(List.of("+447700900123"), texts(added.get("members")));
            assertEquals(1, added.get("size").asInt());
            assertEquals(2, added.get("lastSeq").asLong());
            assertEquals(2, facts(c).size());
            JsonNode fact = JSON.readTree(Files.readString(facts(c).get(1)));
            assertEquals("list.member.added", fact.get("kind").asText());
            assertEquals("[\"+447700900123\"]", fact.get("keys").toString(), "the fact holds the normalised key");
            assertEquals(sha256(Files.readAllBytes(facts(c).get(0))), fact.get("prevHash").asText(), "chained");

            JsonNode again = data(post(c, members, "{\"add\":[\"+447700900123\"],\"remove\":[\"+449999\"],"
                    + "\"reason\":\"again\"}"), 200);
            assertEquals(0, again.get("changed").asInt(), "present already / absent already: nothing changes");
            assertEquals(2, facts(c).size(), "a call that changes nothing writes nothing");

            JsonNode both = data(post(c, members, "{\"add\":[\"0044 7700 900124\"],\"remove\":[\"+44 7700 900123\"],"
                    + "\"reason\":\"swap\"}"), 200);
            assertEquals(2, both.get("changed").asInt());
            assertEquals(List.of("+447700900124"), texts(both.get("members")));
            assertEquals(4, facts(c).size(), "one fact per kind that changed");
        }
    }

    @Test
    void memberWritesRefuseBadBodiesUnknownListsAndRetiredLists(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String members = "/inv/entity-lists/wl/members";
            status(404, post(c, members, "{\"add\":[\"1\"],\"reason\":\"r\"}"), "unknown list");
            data(post(c, LISTS, WATCH), 201);
            status(422, post(c, members, "{\"add\":[\"1\"]}"), "reason is required");
            status(422, post(c, members, "{\"add\":\"+441\",\"reason\":\"r\"}"), "add must be a list");
            status(422, post(c, members, "{\"add\":[44],\"reason\":\"r\"}"), "values are strings");
            HttpResponse<String> empty = post(c, members, "{\"add\":[\"+44 1\",\"n/a\"],\"reason\":\"r\"}");
            status(422, empty, "a value empty after normalising");
            assertTrue(empty.body().contains("add[1]"), empty.body());
            status(422, post(c, members, "{\"add\":[\"+44 1\"],\"remove\":[\"00441\"],\"reason\":\"r\"}"),
                    "one key in both add and remove (after normalising)");
            String many = IntStream.range(0, 5_001).mapToObj(i -> "\"" + i + "\"").collect(Collectors.joining(","));
            status(422, post(c, members, "{\"add\":[" + many + "],\"reason\":\"r\"}"), "over 5 000 values");
            assertEquals(1, facts(c).size(), "no refusal wrote a fact");

            String five = IntStream.range(0, 5_000).mapToObj(i -> "\"" + i + "\"").collect(Collectors.joining(","));
            assertEquals(5_000, data(post(c, members, "{\"add\":[" + five + "],\"reason\":\"bulk\"}"), 200)
                    .get("changed").asInt(), "exactly 5 000 is allowed, and is ONE fact");
            assertEquals(2, facts(c).size());

            status(200, post(c, "/inv/entity-lists/wl/retire", "{\"reason\":\"closed\"}"), "retire");
            status(409, post(c, members, "{\"add\":[\"+442\"],\"reason\":\"r\"}"), "a retired list is read-only");
        }
    }

    // ── retire ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void retireFlagsTheListOnceAndKeepsItListed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String retire = "/inv/entity-lists/wl/retire";
            status(404, post(c, retire, "{\"reason\":\"r\"}"), "unknown list");
            data(post(c, LISTS, WATCH), 201);
            status(422, post(c, retire, "{}"), "reason is required");
            JsonNode retired = data(post(c, retire, "{\"reason\":\"case closed\"}"), 200);
            assertTrue(retired.get("retired").asBoolean());
            assertEquals(2, retired.get("lastSeq").asLong());
            status(409, post(c, retire, "{\"reason\":\"again\"}"), "already retired");

            JsonNode all = data(get(c, LISTS), 200);
            assertEquals(1, all.get("lists").size(), "retired lists are included");
            assertTrue(all.at("/lists/0/retired").asBoolean(), "and flagged");
            assertEquals(2, all.get("headSeq").asLong());
            assertEquals(sha256(Files.readAllBytes(facts(c).get(1))), all.get("headHash").asText());
        }
    }

    // ── reads ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void readsListSummariesAndTimeTravelWithAt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            JsonNode empty = data(get(c, LISTS), 200);
            assertEquals(0, empty.get("lists").size());
            assertEquals(0, empty.get("headSeq").asLong());
            assertEquals("", empty.get("headHash").asText());
            status(404, get(c, "/inv/entity-lists/wl"), "unknown list");

            data(post(c, LISTS, WATCH), 201);                                                           // seq 1
            data(post(c, LISTS, WATCH.replace("\"wl\"", "\"other\"").replace("msisdn", "imsi")), 201); // seq 2
            data(post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+441\",\"+442\"],\"reason\":\"r\"}"), 200); // 3
            data(post(c, "/inv/entity-lists/wl/members", "{\"remove\":[\"+441\"],\"reason\":\"r\"}"), 200);      // 4

            JsonNode now = data(get(c, "/inv/entity-lists/wl"), 200);
            assertEquals(List.of("+442"), texts(now.get("members")));
            assertEquals(4, now.get("atSeq").asLong());
            assertEquals(4, now.get("lastSeq").asLong());
            assertEquals(sha256(Files.readAllBytes(facts(c).get(3))), now.get("headHash").asText());

            JsonNode at3 = data(get(c, "/inv/entity-lists/wl?at=3"), 200);
            assertEquals(List.of("+441", "+442"), texts(at3.get("members")), "sorted, as it was at seq 3");
            assertEquals(3, at3.get("atSeq").asLong());
            assertEquals(3, at3.get("lastSeq").asLong());
            assertEquals(sha256(Files.readAllBytes(facts(c).get(2))), at3.get("headHash").asText(),
                    "the hash of the fact AT atSeq — a self-verifying pin");
            assertEquals(0, data(get(c, "/inv/entity-lists/wl?at=1"), 200).get("members").size());

            status(404, get(c, "/inv/entity-lists/other?at=1"), "a list that did not exist at 'at'");
            status(404, get(c, "/inv/entity-lists/wl?at=0"), "nothing existed before seq 1");
            status(422, get(c, "/inv/entity-lists/wl?at=5"), "'at' beyond head");
            status(422, get(c, "/inv/entity-lists/wl?at=-1"), "negative 'at'");
            status(422, get(c, "/inv/entity-lists/wl?at=head"), "non-numeric 'at'");

            JsonNode all = data(get(c, LISTS), 200);
            assertEquals(List.of("wl", "other"), all.get("lists").findValuesAsText("id"));
            assertEquals(1, all.at("/lists/0/size").asInt());
            assertEquals(4, all.get("headSeq").asLong());
            assertFalse(all.at("/lists/0").has("members"), "a summary carries no members");
        }
    }

    // ── masking ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void membersAreMaskedPerTheSpacesMaskingMode(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c, LISTS, WATCH), 201);                                                     // msisdn: masked
            data(post(c, LISTS, WATCH.replace("\"wl\"", "\"hs\"").replace("msisdn", "handset")), 201); // not masked
            data(post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+447700900123\"],\"reason\":\"r\"}"), 200);
            data(post(c, "/inv/entity-lists/hs/members", "{\"add\":[\"Nokia 3310\"],\"reason\":\"r\"}"), 200);

            // typed (the default): a masked Entity Type's list is masked; an unmasked type's is not.
            String token = data(get(c, "/inv/entity-lists/wl"), 200).at("/members/0").asText();
            assertTrue(token.matches("masked:[0-9a-f]{16}"), token);
            assertEquals(token, data(get(c, "/inv/entity-lists/wl"), 200).at("/members/0").asText(), "stable");
            assertEquals("nokia 3310", data(get(c, "/inv/entity-lists/hs"), 200).at("/members/0").asText());
            assertTrue(Files.readString(facts(c).get(2)).contains("+447700900123"), "masking is render-time only");

            settings(c, "masking_mode: all\n");
            assertTrue(data(get(c, "/inv/entity-lists/hs"), 200).at("/members/0").asText().startsWith("masked:"),
                    "all masks every list");
            assertEquals(token, data(get(c, "/inv/entity-lists/wl"), 200).at("/members/0").asText());

            settings(c, "masking_mode: none\n");
            assertEquals("+447700900123", data(get(c, "/inv/entity-lists/wl"), 200).at("/members/0").asText(),
                    "none masks nothing");

            // A write answers through the same rendering.
            settings(c, "masking_mode: typed\n");
            JsonNode written = data(post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+447700900124\"],\"reason\":\"r\"}"), 200);
            assertFalse(written.toString().contains("+44770090012"), written.toString());
        }
    }

    /** A list whose Entity Type is dropped from the Space's types: read-only (409), and still masked (fail closed). */
    @Test
    void aListWhoseEntityTypeIsNoLongerInForceIsReadOnlyAndStaysMasked(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c, LISTS, WATCH), 201);
            data(post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+447700900123\"],\"reason\":\"r\"}"), 200);
            String handsetOnly = "{\"maskingMode\":\"typed\",\"entityTypes\":[{\"id\":\"handset\",\"label\":\"Handset\","
                    + "\"normaliser\":\"default\",\"masked\":false,\"classifications\":[\"HANDSET\"]}]}";
            status(200, send(c.port, "PUT", "/settings/link-analysis", handsetOnly, null), "drop msisdn from the types");

            HttpResponse<String> refused = post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+447700900124\"],\"reason\":\"r\"}");
            status(409, refused, "a list of a type no longer in force is read-only");
            assertTrue(refused.body().contains("no longer in force"), refused.body());
            assertEquals(2, facts(c).size(), "the refusal wrote no fact");

            JsonNode read = data(get(c, "/inv/entity-lists/wl"), 200);
            assertTrue(read.at("/members/0").asText().matches("masked:[0-9a-f]{16}"), "typed fails closed: " + read);
            assertFalse(read.toString().contains("7700900123"), read.toString());
        }
    }

    // ── integrity ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aTamperedFactLogFailsLoudlyOnEveryRoute(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(post(c, LISTS, WATCH), 201);
            data(post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+441\"],\"reason\":\"r\"}"), 200);
            data(post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+442\"],\"reason\":\"r\"}"), 200);
            Path second = facts(c).get(1);
            Files.writeString(second, Files.readString(second).replace("+441", "+449"));

            for (HttpResponse<String> r : List.of(get(c, LISTS), get(c, "/inv/entity-lists/wl"),
                    post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+443\"],\"reason\":\"r\"}"),
                    post(c, "/inv/entity-lists/wl/retire", "{\"reason\":\"r\"}"))) {
                status(500, r, "a broken chain is never folded");
                assertEquals(ErrorCodes.INTEGRITY_VIOLATION, JSON.readTree(r.body()).at("/error/errorCode").asText());
            }
            assertEquals(3, facts(c).size(), "nothing was appended to a broken log");
        }
    }

    // ── audit ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void everyFactEmitsEntityListChangedWithCountsNeverKeys(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                data(post(c, LISTS, WATCH), 201);
                data(post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+447700900123\",\"+447700900124\"],"
                        + "\"reason\":\"r\"}"), 200);
                data(post(c, "/inv/entity-lists/wl/members", "{\"add\":[\"+447700900123\"],\"reason\":\"r\"}"), 200);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> changed = seen.stream().filter(e -> EventType.ENTITY_LIST_CHANGED.equals(e.type())).toList();
            assertEquals(2, changed.size(), "one per fact; the no-op wrote none: " + changed);
            Event added = changed.get(1);
            assertEquals("wl", added.attributes().get("listId"));
            assertEquals("list.member.added", added.attributes().get("kind"));
            assertEquals("2", added.attributes().get("added"));
            assertEquals("0", added.attributes().get("removed"));
            assertEquals("2", added.attributes().get("seq"));
            assertFalse(changed.toString().contains("7700900123"), "counts, never keys: " + changed);
        }
    }

    // ── capability ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void writesNeedCanManageIncidentsAndReadsNeedOnlySpaceAccess(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer analyst" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer viewer" -> Optional.of(new Subject("viewer-1", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            status(401, send(c.port, "POST", "/inv/entity-lists", WATCH, null), "no credential");
            status(403, send(c.port, "POST", "/inv/entity-lists", WATCH, "Bearer viewer"), "create needs the capability");
            JsonNode created = data(send(c.port, "POST", "/inv/entity-lists", WATCH, "Bearer analyst"), 201);
            assertEquals("analyst-1", created.get("createdBy").asText(), "the actor is the Subject");
            status(403, send(c.port, "POST", "/inv/entity-lists/wl/members", "{\"add\":[\"+441\"],\"reason\":\"r\"}",
                    "Bearer viewer"), "members needs the capability");
            status(403, send(c.port, "POST", "/inv/entity-lists/wl/retire", "{\"reason\":\"r\"}", "Bearer viewer"),
                    "retire needs the capability");
            assertEquals(1, facts(c).size(), "no refused caller wrote a fact");

            status(200, send(c.port, "GET", "/inv/entity-lists", null, "Bearer viewer"), "reads are open to the Space");
            status(200, send(c.port, "GET", "/inv/entity-lists/wl", null, "Bearer viewer"), "reads are open to the Space");
            status(200, send(c.port, "POST", "/inv/entity-lists/wl/members", "{\"add\":[\"+441\"],\"reason\":\"r\"}",
                    "Bearer analyst"), "members with the capability");
            status(200, send(c.port, "POST", "/inv/entity-lists/wl/retire", "{\"reason\":\"r\"}", "Bearer analyst"),
                    "retire with the capability");
        } finally {
            Authenticators.forTest(null);
        }
    }
}
