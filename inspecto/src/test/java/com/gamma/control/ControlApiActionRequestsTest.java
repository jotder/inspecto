package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.objects.ObjectType;
import com.gamma.pipeline.exec.WebhookSinkTransport;
import com.gamma.service.CollectorService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Action Requests over real HTTP ({@code ASSURE-ACTION-REQUESTS-1}): every create / decide / retry gate WITH an
 * armed Authenticator (with no Subject {@code withCapability} is a no-op), delivery to an in-test
 * {@link HttpServer} through the dispatcher, retries under one idempotency key, tamper refusal, and the Decision
 * Rule {@code invoke-api} consequence.
 *
 * <p>The dispatcher runs INLINE here ({@link ActionDispatcher#executor} swapped for a direct executor), so an
 * approve returns after the dispatch finished. The wire is {@link ActionDispatcherTest.LoopbackWire} behind a
 * scheme rewrite: the Connection resolves to {@code https://127.0.0.1:<port>/api} under the real egress rules,
 * and the stub server speaks plain http.
 */
class ControlApiActionRequestsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTHOR = "Bearer author", CHECKER = "Bearer checker", SELF = "Bearer self",
            ANALYST2 = "Bearer analyst2", DEV = "Bearer dev";
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpServer target;
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile int failFirst;
    private Executor priorExecutor;
    private Supplier<WebhookSinkTransport> priorTransport;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** AUTHOR/ANALYST2 = operations (canWorkIncidents, no canApproveChanges); CHECKER = admin; SELF = the author's id as admin. */
    @BeforeEach
    void arm() throws Exception {
        Authenticators.forTest(ex -> {
            String[] who = switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
                case AUTHOR -> new String[] {"author-1", "operations"};
                case ANALYST2 -> new String[] {"analyst-2", "operations"};
                case CHECKER -> new String[] {"checker-1", "admin"};
                case SELF -> new String[] {"author-1", "admin"};
                case DEV -> new String[] {"dev-1", "pipeline-developer"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
        System.setProperty(ActionDispatcher.PROP_BACKOFF_MS, "0");
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/api", ex -> {
            keys.add(String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = keys.size() <= failFirst ? 500 : 200;
            if (status == 200) accepted.incrementAndGet();
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        target.start();
        ConnectionRegistry.register(new ConnectionProfile("hook", "https", "127.0.0.1", target.getAddress().getPort(),
                null, "api", null, null, Map.of(), null, null));
        priorExecutor = ActionDispatcher.executor;
        priorTransport = ActionDispatcher.transport;
        ActionDispatcher.executor = Runnable::run;
        ActionDispatcher.transport = () -> new ActionDispatcherTest.LoopbackWire() {
            @Override public Response exchange(String method, URI url, java.net.InetAddress to, String token,
                                               Duration timeout, String json, Map<String, String> headers, int cap)
                    throws Exception {
                return super.exchange(method, URI.create(url.toString().replaceFirst("^https:", "http:")), to, token,
                        timeout, json, headers, cap);
            }
        };
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        ActionDispatcher.executor = priorExecutor;
        ActionDispatcher.transport = priorTransport;
        ConnectionRegistry.clear();
        System.clearProperty(ActionDispatcher.PROP_BACKOFF_MS);
        target.stop(0);
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        ClassLoader outer = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(ControlApiReconPromoteTest.fakeObjectEngineClassLoader(outer));
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            Thread.currentThread().setContextClassLoader(outer);
            System.clearProperty("assist.write.root");
        }
    }

    private Ctx open(Path cfg, Path tmp, boolean writable) throws Exception {
        return open(cfg, writable ? Files.createDirectories(tmp.resolve("config")) : null);
    }

    /** The stub target is on loopback, which the egress policy denies unless the Space allowlists its range. */
    private void allowLoopback(Ctx c) throws Exception {
        data(send(c, "PUT", "/settings/egress", "{\"allow\":[\"127.0.0.1/32\"]}", CHECKER), 200);
    }

    private static String incident(Ctx c) {
        return c.svc.objects().orElseThrow().open(ObjectType.INCIDENT, "Leak", "d", "error", "t", Map.of());
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

    private static String body(String incidentId) {
        return "{\"connection\":\"hook\",\"method\":\"POST\",\"incidentId\":\"" + incidentId + "\","
                + "\"payloadTemplate\":{\"ticket\":\"{{incident.id}}\",\"by\":\"{{author}}\"}}";
    }

    private String propose(Ctx c, String incidentId) throws Exception {
        allowLoopback(c);
        JsonNode rec = data(send(c, "POST", "/action-requests", body(incidentId), AUTHOR), 200);
        assertEquals("pending", rec.get("status").asText());
        return rec.get("id").asText();
    }

    // ── delivery ────────────────────────────────────────────────────────────────────────────────

    @Test
    void anApprovedRequestIsDeliveredExactlyOnceAcrossRetries(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            String id = propose(c, inc);
            failFirst = 2;
            JsonNode done = data(send(c, "POST", "/action-requests/" + id + "/approve", "{\"reason\":\"ok\"}", CHECKER), 200);
            assertEquals("succeeded", done.get("status").asText(), done.toString());
            assertEquals(3, done.get("attempts").asInt());
            assertEquals("checker-1", done.get("approver").asText());
            assertEquals(List.of(id, id, id), keys, "one idempotency key on every attempt");
            assertEquals(1, accepted.get(), "exactly one delivery accepted");
            assertEquals(Map.of("ticket", inc, "by", "author-1"), JSON.readValue(bodies.get(0), Map.class),
                    "the payload rendered at creation");
            assertEquals(409, send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER).statusCode(),
                    "a second approve cannot send it again");
            assertEquals(3, keys.size());
            JsonNode history = done.get("history");
            assertEquals(List.of("draft", "pending", "approved", "dispatched", "succeeded"),
                    java.util.stream.StreamSupport.stream(history.spliterator(), false).map(h -> h.get("status").asText()).toList());
        }
    }

    @Test
    void aFailingTargetEndsFailedOnTheIncidentAndARetrySucceedsUnderTheSameKey(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            String id = propose(c, inc);
            failFirst = 3;   // every attempt of the first dispatch
            JsonNode failed = data(send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER), 200);
            assertEquals("failed", failed.get("status").asText());
            assertEquals(3, failed.get("attempts").asInt());
            assertEquals(500, failed.at("/lastResponse/status").asInt());

            JsonNode onIncident = data(send(c, "GET", "/action-requests?incidentId=" + inc, null, AUTHOR), 200);
            assertEquals(1, onIncident.get("total").asInt());
            assertEquals("failed", onIncident.at("/items/0/status").asText());
            assertFalse(onIncident.at("/items/0").has("payload"), "the list view carries no payload");

            assertEquals(422, send(c, "POST", "/action-requests/" + id + "/retry", "{\"payloadTemplate\":{}}", CHECKER)
                    .statusCode(), "a retry body cannot carry content");
            JsonNode retried = data(send(c, "POST", "/action-requests/" + id + "/retry", "{}", CHECKER), 200);
            assertEquals("succeeded", retried.get("status").asText(), retried.toString());
            assertEquals(4, retried.get("attempts").asInt());
            assertEquals(List.of(id, id, id, id), keys, "a retry re-sends under the SAME idempotency key");
            assertEquals(1, accepted.get());
            assertEquals(409, send(c, "POST", "/action-requests/" + id + "/retry", "{}", CHECKER).statusCode(),
                    "only a failed request can be retried");
        }
    }

    // ── decide gates ────────────────────────────────────────────────────────────────────────────

    @Test
    void theAuthorCannotApproveOrDeclineTheirOwnRequest(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String id = propose(c, incident(c));
            HttpResponse<String> self = send(c, "POST", "/action-requests/" + id + "/approve", "{}", SELF);
            assertEquals(403, self.statusCode(), self.body());
            assertTrue(self.body().contains("four-eyes"), self.body());
            assertEquals(403, send(c, "POST", "/action-requests/" + id + "/decline", "{}", SELF).statusCode());
            assertEquals("pending", data(send(c, "GET", "/action-requests/" + id, null, AUTHOR), 200).get("status").asText());
            assertTrue(keys.isEmpty(), "nothing sent");
        }
    }

    @Test
    void decidingNeedsCanApproveChangesAndProposingNeedsCanWorkIncidents(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            assertEquals(403, send(c, "POST", "/action-requests", body(inc), DEV).statusCode(), "no canWorkIncidents");
            String id = propose(c, inc);
            for (String route : List.of("/approve", "/decline", "/retry"))
                assertEquals(403, send(c, "POST", "/action-requests/" + id + route, "{}", ANALYST2).statusCode(),
                        route + " without canApproveChanges");
            JsonNode declined = data(send(c, "POST", "/action-requests/" + id + "/decline", "{\"reason\":\"no\"}", CHECKER), 200);
            assertEquals("declined", declined.get("status").asText());
            assertEquals(409, send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER).statusCode());
            assertTrue(keys.isEmpty());
        }
    }

    /** The capability gate runs before any lookup: a missing capability is 403 even for an unknown id. */
    @Test
    void theDecideRoutesAreGatedBeforeAnyLookup(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            for (String path : List.of("/action-requests/ar-20260101000000-abcdef/approve",
                    "/action-requests/ar-20260101000000-abcdef/decline",
                    "/action-requests/ar-20260101000000-abcdef/retry")) {
                assertEquals(403, send(c, "POST", path, "{}", ANALYST2).statusCode(), path);
                assertEquals(404, send(c, "POST", path, "{}", CHECKER).statusCode(), path);
            }
            assertEquals(422, send(c, "POST", "/action-requests/not-an-id/approve", "{}", CHECKER).statusCode(),
                    "an unsafe id");
        }
    }

    @Test
    void anApproveBodyCannotChangeTheTargetOrThePayload(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            String id = propose(c, inc);
            HttpResponse<String> smuggle = send(c, "POST", "/action-requests/" + id + "/approve",
                    "{\"connection\":\"evil\",\"method\":\"PUT\",\"payload\":{\"ticket\":\"x\"}}", CHECKER);
            assertEquals(422, smuggle.statusCode(), smuggle.body());
            assertEquals("pending", data(send(c, "GET", "/action-requests/" + id, null, CHECKER), 200).get("status").asText());
            data(send(c, "POST", "/action-requests/" + id + "/approve", "{\"reason\":\"fine\"}", CHECKER), 200);
            assertEquals(1, bodies.size());
            assertEquals(Map.of("ticket", inc, "by", "author-1"), JSON.readValue(bodies.get(0), Map.class));
        }
    }

    @Test
    void aTamperedRecordIsInvalidAndCanBeNeitherApprovedNorRetried(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            String id = propose(c, inc);
            Path f = c.root.resolve(ActionRequests.DIR).resolve(id + ".json");
            Files.writeString(f, Files.readString(f).replace("\"ticket\" : \"" + inc + "\"", "\"ticket\" : \"forged\""));
            assertTrue(Files.readString(f).contains("forged"), "the tamper landed");
            assertEquals("invalid", data(send(c, "GET", "/action-requests/" + id, null, CHECKER), 200).get("status").asText());
            HttpResponse<String> approve = send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER);
            assertEquals(409, approve.statusCode(), approve.body());
            assertTrue(approve.body().contains("integrity"), approve.body());
            assertEquals(409, send(c, "POST", "/action-requests/" + id + "/retry", "{}", CHECKER).statusCode());
            assertTrue(keys.isEmpty(), "a tampered record is never dispatched");
        }
    }

    // ── the Decision Rule invoke-api consequence ────────────────────────────────────────────────

    @Test
    void theInvokeApiConsequenceProposesAPendingRequestOnItsIncident(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            data(send(c, "POST", "/decision-rules", "{\"name\":\"leak\",\"consequences\":[{\"action\":\"invoke-api\","
                    + "\"params\":{\"connection\":\"hook\"}}]}", DEV), 200);
            JsonNode applied = data(send(c, "POST", "/decision-rules/leak/apply", "{}", AUTHOR), 200);
            JsonNode one = applied.at("/executed/0");
            assertEquals("executed", one.get("status").asText(), applied.toString());
            String id = one.get("actionRequestId").asText();
            assertTrue(keys.isEmpty(), "not a direct call — nothing is sent before approval");

            JsonNode rec = data(send(c, "GET", "/action-requests/" + id, null, CHECKER), 200);
            assertEquals("pending", rec.get("status").asText());
            assertEquals("decision-rule:leak", rec.get("origin").asText());
            assertEquals("author-1", rec.get("author").asText(), "the person who applied the rule");
            String inc = rec.get("incidentId").asText();
            Map<String, Object> incident = c.svc.objects().orElseThrow().summary(inc).orElseThrow();
            assertEquals("incident", incident.get("kind"));
            assertEquals("decision-rule:leak", incident.get("correlationId"));
            assertEquals(Map.of("incident", inc, "rule", "leak"), JSON.convertValue(rec.get("payload"), Map.class));

            assertEquals(id, data(send(c, "POST", "/decision-rules/leak/apply", "{}", AUTHOR), 200)
                    .at("/executed/0/actionRequestId").asText(), "deduped while one is pending on that Incident");
            assertEquals(1, data(send(c, "GET", "/action-requests?incidentId=" + inc, null, AUTHOR), 200).get("total").asInt());

            assertEquals("succeeded", data(send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER), 200)
                    .get("status").asText());
            assertEquals(1, accepted.get());
        }
    }

    // ── egress (verification finding 1) ─────────────────────────────────────────────────────────

    @Test
    void withoutAnAllowlistEntryTheLoopbackTargetIsNeverDialled(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String id = propose(c, incident(c));
            data(send(c, "PUT", "/settings/egress", "{\"allow\":[]}", CHECKER), 200);
            JsonNode done = data(send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER), 200);
            assertEquals("failed", done.get("status").asText());
            assertTrue(done.at("/lastResponse/error").asText().contains("loopback"), done.toString());
            assertTrue(keys.isEmpty(), "deny by default: nothing sent");
        }
    }

    @Test
    void theApproverSeesTheParsedHostPortPathAndWhetherItIsAllowlisted(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String id = propose(c, incident(c));
            JsonNode e = data(send(c, "GET", "/action-requests/" + id, null, CHECKER), 200).get("egress");
            assertEquals("127.0.0.1", e.get("host").asText());
            assertEquals(target.getAddress().getPort(), e.get("port").asInt());
            assertEquals("/api", e.get("path").asText());
            assertTrue(e.get("allowlisted").asBoolean());
            JsonNode done = data(send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER), 200);
            assertEquals("127.0.0.1", done.at("/attemptLog/0/address").asText(), "the checked address is recorded");
        }
    }

    @Test
    void theEgressAllowlistIsAdministratorOnlyAndValidatedFailClosed(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            assertEquals(403, send(c, "PUT", "/settings/egress", "{\"allow\":[\"10.0.0.0/8\"]}", ANALYST2).statusCode());
            for (String bad : List.of("0x0a000000/8", "2130706433", "a@b.example", "10.0.0.0/99"))
                assertEquals(422, send(c, "PUT", "/settings/egress", "{\"allow\":[\"" + bad + "\"]}", CHECKER)
                        .statusCode(), bad);
            assertEquals(422, send(c, "PUT", "/settings/egress", "{\"deny\":[]}", CHECKER).statusCode());
            data(send(c, "PUT", "/settings/egress", "{\"allow\":[\"pcrf.internal\",\"10.9.0.0/16\"]}", CHECKER), 200);
            assertEquals(2, data(send(c, "GET", "/settings/egress", null, AUTHOR), 200).get("allow").size());
        }
    }

    @Test
    void anHttpsConnectionWithUserinfoOrANumericTrickHostIsRefusedAtSave(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            for (String host : List.of("trusted.example.com@attacker.example", "2130706433", "0x7f000001", "127.1")) {
                HttpResponse<String> r = send(c, "POST", "/connections", "{\"id\":\"t1\",\"connector\":\"https\","
                        + "\"host\":\"" + host + "\",\"port\":443}", CHECKER);
                assertEquals(422, r.statusCode(), host + " -> " + r.body());
            }
            ConnectionRegistry.register(new ConnectionProfile("trick", "https", "trusted.example.com@attacker.example",
                    443, null, "api", null, null, Map.of(), null, null));
            HttpResponse<String> r = send(c, "POST", "/action-requests", body(incident(c)).replace("\"hook\"", "\"trick\""), AUTHOR);
            assertEquals(422, r.statusCode(), "a Connection that reached the registry some other way is refused at create: " + r.body());
        }
    }

    // ── create gates ────────────────────────────────────────────────────────────────────────────

    @Test
    void createIsRefusedWithoutAWriteRoot(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, false)) {
            assertEquals(503, send(c, "POST", "/action-requests", body("x"), AUTHOR).statusCode());
        }
    }

    @Test
    void createValidatesFailClosed(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            assertEquals(422, send(c, "POST", "/action-requests", body(inc).replace("\"method\"", "\"url\""), AUTHOR)
                    .statusCode(), "unknown key");
            assertEquals(422, send(c, "POST", "/action-requests", body(inc).replace("\"POST\"", "\"GET\""), AUTHOR)
                    .statusCode(), "method");
            assertEquals(422, send(c, "POST", "/action-requests", body(inc).replace("\"hook\"", "\"nope\""), AUTHOR)
                    .statusCode(), "an unregistered Connection");
            assertEquals(422, send(c, "POST", "/action-requests",
                    "{\"connection\":\"hook\",\"payloadTemplate\":{}}", AUTHOR).statusCode(), "no linked Incident / Case");
            assertEquals(404, send(c, "POST", "/action-requests", body("INCIDENT-missing"), AUTHOR).statusCode());
            ActionDispatcher.transport = () -> null;
            assertEquals(503, send(c, "POST", "/action-requests", body(inc), AUTHOR).statusCode(), "no transport (Personal)");
            assertEquals(0, data(send(c, "GET", "/action-requests", null, AUTHOR), 200).get("total").asInt());
        }
    }
}
