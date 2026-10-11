package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.OperationalObject;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /objects/by-entity} over real HTTP — an Entity's case history. One test per gate: 401 with no
 * credential, 422 on every bad input, SEC-7d existence-hiding (a hidden Incident and a Case reached only through
 * it are absent, and not counted), the empty result, and a match via each {@code matchedOn} path
 * ({@code key.<column>}, {@code key.entity_key}, {@code entityKey}, {@code member}) under the e164 fold.
 * There is no 403 gate: the route is ungated exactly like {@code GET /objects}, whose visibility it shares.
 */
class ControlApiObjectsByEntityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer fraud".equals(auth)) return Optional.of(new Subject("ana", Set.of(), Set.of("fraud")));
        if ("Bearer all".equals(auth)) return Optional.of(new Subject("root", Set.of()));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private static OperationalObject incident(Ctx c, String title, Map<String, String> attrs) {
        return TestOpsEngine.of(c.svc).open(ObjectType.INCIDENT, title, "d", "HIGH", null, null, null, null, attrs);
    }

    private static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void unauthenticatedIs401(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(401, get(c.port, "/objects/by-entity?type=msisdn&id=256700123456", null).statusCode());
        }
    }

    @Test
    void badInputsAre422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String q : List.of("type=planet&id=1", "id=256700123456", "type=msisdn", "type=msisdn&id=%2B",
                    "type=msisdn&id=1&status=pending", "type=msisdn&id=1&limit=0", "type=msisdn&id=1&limit=abc",
                    "type=msisdn&id=1&limit=201", "type=msisdn&id=" + "9".repeat(257),
                    "type=msisdn&id=1234", "type=msisdn&id=%2B12-34", "type=cell&id=a",
                    "type=msisdn&id=imei:356938035643809", "type=msisdn&id=entity:imei:356938035643809")) {
                HttpResponse<String> r = get(c.port, "/objects/by-entity?" + q, "all");
                assertEquals(422, r.statusCode(), q + " → " + r.body());
            }
        }
    }

    @Test
    void noReferenceIsAnEmptyList(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            incident(c, "other", attrs("key.msisdn", "256799999999"));
            JsonNode body = JSON.readTree(get(c.port, "/objects/by-entity?type=msisdn&id=256700123456", "all").body());
            assertEquals(0, body.get("data").size());
        }
    }

    @Test
    void matchesViaEveryPathNewestFirst(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var engine = TestOpsEngine.of(c.svc);
            OperationalObject keyed = incident(c, "calls spike", attrs("key.caller_msisdn", "256700123456", "key", "x"));
            OperationalObject scored = incident(c, "risk", attrs("key.entity_key", "+256700123456", "key.model", "m"));
            OperationalObject minted = incident(c, "from LA", attrs("entityKey", "entity:msisdn:+256700123456", "entityDataset", "cdr"));
            incident(c, "other type", attrs("entityKey", "entity:imsi:256700123456", "entityDataset", "cdr"));
            incident(c, "column names no msisdn", attrs("key.cell", "256700123456"));
            Thread.sleep(5);   // a distinct createdAt, so "newest first" is not decided by the id tie-break
            OperationalObject kase = engine.open(ObjectType.CASE, "fraud ring", "d", "HIGH", null, null, null, null, Map.of());
            engine.link(kase.id(), minted.id(), "CONTAINS", "sys");

            // the typed node-id spelling folds to the same key as the raw digits
            for (String id : List.of("256700123456", "msisdn:+256700123456", "entity:msisdn:+256-700-123456")) {
                JsonNode data = JSON.readTree(get(c.port, "/objects/by-entity?type=msisdn&id="
                        + URLEncoder.encode(id, StandardCharsets.UTF_8), "all").body()).get("data");
                assertEquals(4, data.size(), id + " → " + data);
                Map<String, String> on = new HashMap<>();
                data.forEach(r -> on.put(r.get("id").asText(), r.get("matchedOn").asText()));
                assertEquals("key.caller_msisdn", on.get(keyed.id()));
                assertEquals("key.entity_key", on.get(scored.id()));
                assertEquals("entityKey", on.get(minted.id()));
                assertEquals("member", on.get(kase.id()));
                assertEquals(kase.id(), data.get(0).get("id").asText(), "newest first");
                assertEquals("case", data.get(0).get("kind").asText());
                assertEquals(minted.id(), data.get(0).get("via").asText());
                assertTrue(data.get(0).has("createdAt") && data.get(0).has("status") && data.get(0).has("title"));
            }
            JsonNode capped = JSON.readTree(get(c.port, "/objects/by-entity?type=msisdn&id=256700123456&limit=1", "all").body());
            assertEquals(1, capped.get("data").size());

            engine.transition(kase.id(), "investigate", "t");
            engine.transition(kase.id(), "resolve", "t");
            engine.transition(kase.id(), "close", "t");
            JsonNode closed = JSON.readTree(get(c.port, "/objects/by-entity?type=msisdn&id=256700123456&status=closed", "all").body()).get("data");
            assertEquals(1, closed.size());
            assertEquals(kase.id(), closed.get(0).get("id").asText());
            assertEquals(3, JSON.readTree(get(c.port, "/objects/by-entity?type=msisdn&id=256700123456&status=open", "all")
                    .body()).get("data").size());
        }
    }

    @Test
    void hiddenObjectsAndCasesReachedOnlyThroughThemAreAbsent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var engine = TestOpsEngine.of(c.svc);
            OperationalObject billing = incident(c, "billing", attrs("key.msisdn", "256700123456",
                    com.gamma.opsapi.ObjectRoutes.ATTR_CASE_TYPE, "billing"));
            OperationalObject fraud = incident(c, "fraud", attrs("key.msisdn", "256700123456",
                    com.gamma.opsapi.ObjectRoutes.ATTR_CASE_TYPE, "fraud"));
            OperationalObject kase = engine.open(ObjectType.CASE, "untyped case", "d", "HIGH", null, null, null, null, Map.of());
            engine.link(kase.id(), billing.id(), "CONTAINS", "sys");

            JsonNode scoped = JSON.readTree(get(c.port, "/objects/by-entity?type=msisdn&id=256700123456", "fraud").body());
            assertEquals(List.of(fraud.id()), scoped.get("data").findValuesAsText("id"),
                    "the hidden Incident, and the Case reached only through it, are absent");
            assertEquals(1, scoped.get("metadata").get("pagination").get("total").asInt(), "and not counted");

            assertEquals(3, JSON.readTree(get(c.port, "/objects/by-entity?type=msisdn&id=256700123456", "all")
                    .body()).get("data").size(), "the unscoped caller sees all three");
        }
    }

    @Test
    void columnTokensAndUnknownPrefixesMatchExactly(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject token = incident(c, "token", attrs("key.cell_id", "ab-12"));
            incident(c, "substring only", attrs("key.excellent", "AB-12"));
            OperationalObject host = incident(c, "untyped", attrs("entityKey", "entity:host:8080", "entityDataset", "d"));
            JsonNode cell = JSON.readTree(get(c.port, "/objects/by-entity?type=cell&id=AB-12", "all").body()).get("data");
            assertEquals(List.of(token.id()), cell.findValuesAsText("id"), "cell_id names cell; excellent does not");
            JsonNode h = JSON.readTree(get(c.port, "/objects/by-entity?type=handset&id=host:8080", "all").body()).get("data");
            assertEquals(List.of(host.id()), h.findValuesAsText("id"), "an unknown prefix is part of the value");
        }
    }

    @Test
    void aScanThatHitsItsCapSaysSo(@TempDir Path dir) throws Exception {
        java.lang.reflect.Field cap = com.gamma.opsapi.ObjectRoutes.class.getClassLoader()
                .loadClass("com.gamma.opsapi.EntityHistory").getDeclaredField("SCAN_CAP");
        cap.setAccessible(true);
        int was = cap.getInt(null);
        try (Ctx c = open(dir)) {
            for (int i = 0; i < 3; i++) incident(c, "n" + i, attrs("key.msisdn", "256700123456"));
            HttpResponse<String> full = get(c.port, "/objects/by-entity?type=msisdn&id=256700123456", "all");
            assertFalse(JSON.readTree(full.body()).get("metadata").get("pagination").has("truncated"));
            assertTrue(full.headers().firstValue("X-Inspecto-Truncated").isEmpty());
            cap.setInt(null, 2);
            HttpResponse<String> r = get(c.port, "/objects/by-entity?type=msisdn&id=256700123456", "all");
            JsonNode p = JSON.readTree(r.body()).get("metadata").get("pagination");
            assertTrue(p.get("truncated").asBoolean());
            assertTrue(p.get("message").asText().contains("older references"));
            assertEquals("true", r.headers().firstValue("X-Inspecto-Truncated").orElse(null));
        } finally {
            cap.setInt(null, was);
        }
    }

    @Test
    void aBurstIsRateLimited(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            boolean throttled = false;
            for (int i = 0; i < 30 && !throttled; i++)
                throttled = get(c.port, "/objects/by-entity?type=msisdn&id=256700123456", "all").statusCode() == 429;
            assertTrue(throttled, "the standard scan-route bucket answers 429 past its burst");
        }
    }

    private HttpResponse<String> get(int port, String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
