package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.workflow.ObjectType;
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
import com.gamma.access.Roles;
import com.gamma.access.ComponentAccess;

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
            ANALYST2 = "Bearer analyst2", DEV = "Bearer dev", POWER = "Bearer power", EDITOR = "Bearer editor";
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpServer target;
    private Authenticator base;
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile int failFirst;
    private Executor priorExecutor;
    private Supplier<WebhookSinkTransport> priorTransport;
    private com.gamma.util.egress.EgressPolicy.Resolver priorResolver;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** AUTHOR/ANALYST2 = operations (canWorkIncidents, no canApproveChanges); CHECKER = admin; SELF = the author's id as admin. */
    @BeforeEach
    void arm() throws Exception {
        Authenticators.forTest(base = ex -> {
            String[] who = switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
                case AUTHOR -> new String[] {"author-1", "operations"};
                case ANALYST2 -> new String[] {"analyst-2", "operations"};
                case CHECKER -> new String[] {"checker-1", "admin"};
                case SELF -> new String[] {"author-1", "admin"};
                case DEV -> new String[] {"dev-1", "pipeline-developer"};
                case POWER -> new String[] {"power-1", "power"};
                case EDITOR -> new String[] {"editor-1", "super"};
                default -> null;
            };
            if ("Bearer grouped".equals(ex.getRequestHeaders().getFirst("Authorization")))   // an IdP group claim
                return Optional.of(new Subject("grp-1", Roles.effective(ex).get("admin").capabilities(), null,
                        Map.of("groups", "ra-approvers")));
            if ("Bearer scoped".equals(ex.getRequestHeaders().getFirst("Authorization")))
                return Optional.of(new Subject("scoped-1", Roles.effective(ex).get("operations").capabilities(),
                        Set.of("billing")));
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
            byte[] out = ("answer-" + status).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        target.start();
        ConnectionRegistry.register(new ConnectionProfile("hook", "https", "tickets.test", target.getAddress().getPort(),
                null, "api", null, null, Map.of(), null, null));
        priorExecutor = ActionDispatcher.executor;
        priorTransport = ActionDispatcher.transport;
        priorResolver = ActionDispatcher.resolver;
        ActionDispatcher.resolver = ActionDispatcherTest.NET;   // tickets.test → a private address
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
        ActionDispatcher.resolver = priorResolver;
        ConnectionRegistry.clear();
        System.clearProperty(ActionDispatcher.PROP_BACKOFF_MS);
        target.stop(0);
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        ClassLoader outer = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(FakeObjectEngineProvider.fakeObjectEngineClassLoader(outer));
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            if (writeRoot != null) seedApproverRoster(writeRoot);   // OIDC-shaped Authenticator: the Space's approver roster decides
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

    /** The target (tickets.test → a private address) is denied by default; the Space allowlists the name. */
    private void allowLoopback(Ctx c) throws Exception {
        data(send(c, "PUT", "/settings/egress", "{\"allow\":[\"tickets.test\"]}", CHECKER), 200);
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

    @Test
    void aDirectoryThatFailsToEnumerateReadsUnknownAndNeverFailsTheRequest(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            Authenticator b = base;
            Authenticators.forTest(new Authenticator() {
                @Override public Optional<Subject> authenticate(com.sun.net.httpserver.HttpExchange ex) { return b.authenticate(ex); }
                @Override public Optional<Map<String, List<String>>> principals(Path configRoot) {
                    throw new IllegalStateException("unreadable demo-users.toon");
                }
            });
            allowLoopback(c);
            JsonNode rec = data(send(c, "POST", "/action-requests", body(inc), AUTHOR), 200);
            assertEquals("unknown", rec.get("approverCheck").asText());
            String id = rec.get("id").asText();
            assertEquals("unknown", data(send(c, "GET", "/action-requests/" + id, null, AUTHOR), 200).get("approverCheck").asText());
            JsonNode list = data(send(c, "GET", "/action-requests", null, AUTHOR), 200);
            assertEquals(1, list.get("total").asInt(), "one saved request, no duplicate");
            assertEquals("unknown", list.get("items").get(0).get("approverCheck").asText());
        }
    }

    @Test
    void withoutAnAuthenticatorNoOneCanDecideSoItReadsNoneEligible(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            allowLoopback(c);
            Authenticators.forTest(null);
            JsonNode rec = data(send(c, "POST", "/action-requests", body(inc), null), 200);
            assertEquals("none-eligible", rec.get("approverCheck").asText());
            assertEquals(403, send(c, "POST", "/action-requests/" + rec.get("id").asText() + "/approve", "{}", null).statusCode(),
                    "Personal: deciding always needs a Subject");
        }
    }

    @Test
    void aNonMakerApproverWhoseRoleIsDataScopedReadsUnknownNotOk(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            data(send(c, "PUT", "/access/roles", "{\"roles\":[{\"name\":\"scoped-approver\",\"capabilities\":"
                    + "[\"canApproveChanges\"],\"dataScopes\":[\"billing\"]}]}", CHECKER), 200);
            enumerating(Map.of("author-1", List.of("operations"), "checker-2", List.of("scoped-approver")));
            String id = propose(c, inc);
            assertEquals("unknown", data(send(c, "GET", "/action-requests/" + id, null, AUTHOR), 200)
                    .get("approverCheck").asText(), "the scope may hide the Incident from them: decide needs visible()");
        }
    }

    /** Re-arm with an Authenticator that, like Demo sign-in, can enumerate its principals (id → roles). */
    private void enumerating(Map<String, List<String>> principals) {
        Authenticator b = base;
        Authenticators.forTest(new Authenticator() {
            @Override public Optional<Subject> authenticate(com.sun.net.httpserver.HttpExchange ex) { return b.authenticate(ex); }
            @Override public Optional<Map<String, List<String>>> principals(Path configRoot) { return Optional.of(principals); }
        });
    }

    private static List<com.gamma.audit.Event> noApproverEvents(List<com.gamma.audit.Event> seen, String id) {
        return seen.stream().filter(e -> e.attributes().containsValue("action-request.no-eligible-approver")
                && id.equals(e.attributes().get("actionRequest"))).toList();
    }

    // ── approver check (RESIDUALS-1 (6)) ────────────────────────────────────────────────────────

    @Test
    void anIdpThatCannotEnumerateItsPrincipalsReadsTheApproverRoster(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        List<com.gamma.audit.Event> seen = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<com.gamma.audit.Event> sub = seen::add;
        com.gamma.audit.EventLog.global().addSubscriber(sub);
        try (Ctx c = open(cfg, tmp, true)) {
            String id = propose(c, incident(c));
            assertEquals("ok", data(send(c, "GET", "/action-requests/" + id, null, AUTHOR), 200).get("approverCheck").asText(),
                    "the seeded roster lists non-makers");
            assertTrue(noApproverEvents(seen, id).isEmpty());

            data(send(c, "PUT", "/settings/approvers", "{\"users\":[]}", CHECKER), 200);
            String none = propose(c, incident(c));
            assertEquals("none-eligible", data(send(c, "GET", "/action-requests/" + none, null, AUTHOR), 200)
                    .get("approverCheck").asText(), "an empty roster: nobody can approve, never anyone");
            assertEquals(1, noApproverEvents(seen, none).size(), "warned once, at raise");
            HttpResponse<String> refused = send(c, "POST", "/action-requests/" + none + "/approve", "{}", CHECKER);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("approver roster"), refused.body());
            assertEquals(0, accepted.get(), "nothing dispatched");

            data(send(c, "PUT", "/settings/approvers", "{\"users\":[\"author-1\"],\"groups\":[\"ra-approvers\"]}", CHECKER), 200);
            HttpResponse<String> self = send(c, "POST", "/action-requests/" + none + "/approve", "{}", SELF);
            assertEquals(403, self.statusCode());
            assertTrue(self.body().contains("four-eyes"), "listed, a maker still never approves their own");
            JsonNode done = data(send(c, "POST", "/action-requests/" + none + "/approve", "{}", "Bearer grouped"), 200);
            assertEquals("grp-1", done.get("approver").asText(), "a group claim match decides");
        } finally {
            com.gamma.audit.EventLog.global().removeSubscriber(sub);
        }
    }

    @Test
    void anEnumeratedDirectoryWhoseOnlyApproverIsTheMakerReadsNoneEligibleAndWarnsOnce(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        List<com.gamma.audit.Event> seen = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<com.gamma.audit.Event> sub = seen::add;
        com.gamma.audit.EventLog.global().addSubscriber(sub);
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            enumerating(Map.of("author-1", List.of("admin"), "analyst-2", List.of("operations")));
            String id = propose(c, inc);
            assertEquals("none-eligible", data(send(c, "GET", "/action-requests/" + id, null, AUTHOR), 200)
                    .get("approverCheck").asText());
            data(send(c, "GET", "/action-requests", null, AUTHOR), 200);
            List<com.gamma.audit.Event> warn = noApproverEvents(seen, id);
            assertEquals(1, warn.size(), "one warning, at raise — reads never re-emit");
            assertEquals(com.gamma.audit.EventLevel.WARN, warn.get(0).level());
            assertEquals("pending", data(send(c, "GET", "/action-requests/" + id, null, AUTHOR), 200).get("status").asText(),
                    "never auto-declined");

            enumerating(Map.of("author-1", List.of("admin"), "checker-1", List.of("admin")));
            assertEquals("ok", data(send(c, "GET", "/action-requests/" + id, null, AUTHOR), 200).get("approverCheck").asText(),
                    "computed live: a non-maker approver now exists");
            String ok = propose(c, inc);
            assertTrue(noApproverEvents(seen, ok).isEmpty());
        } finally {
            com.gamma.audit.EventLog.global().removeSubscriber(sub);
        }
    }

    @Test
    void aRoleTableThatGrantsNoOneCanApproveChangesReadsNoneEligibleEvenUnderAnIdp(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            allowLoopback(c);
            data(send(c, "PUT", "/access/roles", "{\"roles\":[{\"name\":\"admin\",\"capabilities\":"
                    + "[\"canWorkIncidents\",\"canConfigureAccess\"]},{\"name\":\"super\",\"capabilities\":"
                    + "[\"canWorkIncidents\",\"canConfigureAccess\"]}]}", CHECKER), 200);
            JsonNode rec = data(send(c, "POST", "/action-requests", body(inc), AUTHOR), 200);
            assertEquals("none-eligible", rec.get("approverCheck").asText());
        }
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
                    "/action-requests/ar-20260101000000-abcdef/retry",
                    "/action-requests/ar-20260101000000-abcdef/mark-failed")) {
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
            JsonNode bad = data(send(c, "GET", "/action-requests/" + id, null, CHECKER), 200);
            assertEquals("invalid", bad.get("status").asText());
            assertTrue(!bad.has("approverCheck") || "unknown".equals(bad.get("approverCheck").asText()),
                    "a record failing its MAC never reads ok or none-eligible");
            HttpResponse<String> approve = send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER);
            assertEquals(409, approve.statusCode(), approve.body());
            assertTrue(approve.body().contains("integrity"), approve.body());
            assertEquals(409, send(c, "POST", "/action-requests/" + id + "/retry", "{}", CHECKER).statusCode());
            assertTrue(keys.isEmpty(), "a tampered record is never dispatched");
        }
    }

    /** Verification finding 7: a request stuck in dispatched can be marked failed — by an operator, never at boot. */
    @Test
    void aRequestStuckInDispatchedCanBeMarkedFailedAndRetriedUnderTheSameKey(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String id = propose(c, incident(c));
            assertEquals(409, send(c, "POST", "/action-requests/" + id + "/mark-failed", "{}", CHECKER).statusCode(),
                    "a pending request is not stuck");
            synchronized (ActionRequests.lock()) {   // as a process stop leaves it: dispatched, never finished
                Map<String, Object> rec = ActionRequests.read(c.root, id);
                ActionRequests.transition(rec, ActionRequests.APPROVED, "checker-1");
                ActionRequests.transition(rec, ActionRequests.DISPATCHED, "system");
                ActionRequests.save(c.root, rec);
            }
            HttpResponse<String> early = send(c, "POST", "/action-requests/" + id + "/mark-failed", "{}", CHECKER);
            assertEquals(409, early.statusCode(), "not idle long enough under the default: " + early.body());
            assertEquals(403, send(c, "POST", "/action-requests/" + id + "/mark-failed", "{}", ANALYST2).statusCode());
            System.setProperty(ActionRequestRoutes.PROP_STUCK_AFTER_MINUTES, "0");
            try {
                JsonNode marked = data(send(c, "POST", "/action-requests/" + id + "/mark-failed", "{}", SELF), 200);
                assertEquals("failed", marked.get("status").asText(), "not four-eyes: the author may do it");
                assertTrue(marked.at("/lastResponse/error").asText().contains("stuck in dispatched"));
            } finally {
                System.clearProperty(ActionRequestRoutes.PROP_STUCK_AFTER_MINUTES);
            }
            assertTrue(keys.isEmpty(), "marking sends nothing");
            JsonNode retried = data(send(c, "POST", "/action-requests/" + id + "/retry", "{}", CHECKER), 200);
            assertEquals("succeeded", retried.get("status").asText());
            assertEquals(List.of(id), keys, "re-sent under the same idempotency key");
        }
    }

    /** Verification finding 6: a whole-Space export never carries the records (payloads, response excerpts). */
    @Test
    void aSpaceExportSkipsTheActionRequestStore(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            propose(c, incident(c));
            assertTrue(Files.isDirectory(c.root.resolve(ActionRequests.DIR)), "a record exists to be skipped");
            HttpResponse<byte[]> zip = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                    + "/api/v1/export")).header("Authorization", CHECKER).GET().build(), BodyHandlers.ofByteArray());
            assertEquals(200, zip.statusCode());
            List<String> names = new java.util.ArrayList<>();
            try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip.body()))) {
                for (var e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) names.add(e.getName());
            }
            assertFalse(names.isEmpty());
            assertFalse(names.stream().anyMatch(n -> n.contains("action-requests")), names.toString());
        }
    }

    // ── the Decision Rule invoke-api consequence ────────────────────────────────────────────────

    @Test
    void theInvokeApiConsequenceProposesAPendingRequestOnItsIncident(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            data(send(c, "POST", "/decision-rules", "{\"name\":\"leak\",\"consequences\":[{\"action\":\"invoke-api\","
                    + "\"params\":{\"connection\":\"hook\"}}]}", POWER), 200);
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

    // ── reads (verification finding 2) ──────────────────────────────────────────────────────────

    @Test
    void readingNeedsCanWorkIncidentsOrCanApproveChanges(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String id = propose(c, incident(c));
            assertEquals(403, send(c, "GET", "/action-requests", null, DEV).statusCode());
            assertEquals(403, send(c, "GET", "/action-requests/ar-20260101000000-abcdef", null, DEV).statusCode());
            assertEquals(200, send(c, "GET", "/action-requests/" + id, null, AUTHOR).statusCode());
            assertEquals(200, send(c, "GET", "/action-requests/" + id, null, CHECKER).statusCode());
        }
    }

    @Test
    void aRequestOnAnObjectOutsideTheCallersScopeIsInvisible(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String fraud = c.svc.objects().orElseThrow().open(ObjectType.INCIDENT, "Fraud", "d", "error", "f",
                    Map.of("caseType", "fraud"));
            String id = propose(c, fraud);
            assertEquals(0, data(send(c, "GET", "/action-requests", null, "Bearer scoped"), 200).get("total").asInt());
            assertEquals(404, send(c, "GET", "/action-requests/" + id, null, "Bearer scoped").statusCode(),
                    "absent, not forbidden");
            assertEquals(1, data(send(c, "GET", "/action-requests", null, AUTHOR), 200).get("total").asInt(),
                    "an unscoped reader sees it");
        }
    }

    @Test
    void theResponseBodyIsWithheldFromReadersWhoCannotApprove(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String inc = incident(c);
            String id = propose(c, inc);
            failFirst = 3;
            data(send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER), 200);
            JsonNode asAuthor = data(send(c, "GET", "/action-requests/" + id, null, AUTHOR), 200);
            assertTrue(asAuthor.at("/lastResponse/bodyExcerpt").isNull(), asAuthor.toString());
            assertTrue(asAuthor.at("/lastResponse/bodyRedacted").asBoolean());
            assertEquals(500, asAuthor.at("/lastResponse/status").asInt(), "the status is still shown");
            assertTrue(data(send(c, "GET", "/action-requests?incidentId=" + inc, null, AUTHOR), 200)
                    .at("/items/0/lastResponse/bodyExcerpt").isNull(), "the list view withholds it too");
            assertEquals("answer-500", data(send(c, "GET", "/action-requests/" + id, null, CHECKER), 200)
                    .at("/lastResponse/bodyExcerpt").asText());
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
            assertTrue(done.at("/lastResponse/error").asText().contains("private"), done.toString());
            assertTrue(keys.isEmpty(), "deny by default: nothing sent");
        }
    }

    @Test
    void theApproverSeesTheParsedHostPortPathAndWhetherItIsAllowlisted(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String id = propose(c, incident(c));
            JsonNode e = data(send(c, "GET", "/action-requests/" + id, null, CHECKER), 200).get("egress");
            assertEquals("tickets.test", e.get("host").asText());
            assertEquals(target.getAddress().getPort(), e.get("port").asInt());
            assertEquals("/api", e.get("path").asText());
            assertTrue(e.get("allowlisted").asBoolean());
            JsonNode done = data(send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER), 200);
            assertEquals("10.9.0.5", done.at("/attemptLog/0/address").asText(), "the checked address is recorded");
        }
    }

    /**
     * Verification finding 5: the approved targetUrl is pinned. After an approval, the Connection is re-pointed at
     * another host; a retry must fail naming the change, and send nothing.
     */
    @Test
    void aRetryAfterTheConnectionMovedFailsAndSendsNothing(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String id = propose(c, incident(c));
            failFirst = 3;
            assertEquals("failed", data(send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER), 200)
                    .get("status").asText());
            int sent = keys.size();
            ConnectionRegistry.register(new ConnectionProfile("hook", "https", "moved.test", target.getAddress().getPort(),
                    null, "api", null, null, Map.of(), null, null));
            data(send(c, "PUT", "/settings/egress", "{\"allow\":[\"tickets.test\",\"moved.test\"]}", CHECKER), 200);
            JsonNode retried = data(send(c, "POST", "/action-requests/" + id + "/retry", "{}", CHECKER), 200);
            assertEquals("failed", retried.get("status").asText(), retried.toString());
            assertTrue(retried.at("/lastResponse/error").asText().contains("now resolves to"), retried.toString());
            assertEquals(sent, keys.size(), "nothing sent to the re-pointed Connection");
        }
    }

    @Test
    void theEgressAllowlistIsAdministratorOnlyAndValidatedFailClosed(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            assertEquals(403, send(c, "PUT", "/settings/egress", "{\"allow\":[\"10.0.0.0/8\"]}", ANALYST2).statusCode());
            for (String bad : List.of("0x0a000000/8", "2130706433", "a@b.example", "10.0.0.0/99",
                    "127.0.0.1/32", "169.254.169.254", "0.0.0.0/1", "128.0.0.0/1", "::1", "fe80::/10"))
                assertEquals(422, send(c, "PUT", "/settings/egress", "{\"allow\":[\"" + bad + "\"]}", CHECKER)
                        .statusCode(), bad);
            assertEquals(422, send(c, "PUT", "/settings/egress", "{\"deny\":[]}", CHECKER).statusCode());
            data(send(c, "PUT", "/settings/egress", "{\"allow\":[\"pcrf.internal\",\"10.9.0.0/16\"]}", CHECKER), 200);
            assertEquals(2, data(send(c, "GET", "/settings/egress", null, AUTHOR), 200).get("allow").size());
        }
    }

    /**
     * Round-2 verification 2026-09-29: the model endpoint allowlist rides egress.toon as {@code models}. It is
     * administrator-only, validated fail closed, and a PUT of one list keeps the other.
     */
    @Test
    void theModelEndpointAllowlistIsAdministratorOnlyValidatedAndKeptApartFromAllow(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            assertEquals(403, send(c, "PUT", "/settings/egress", "{\"models\":[\"localhost\"]}", ANALYST2).statusCode());
            assertEquals(422, send(c, "PUT", "/settings/egress", "{\"models\":[\"169.254.169.254\"]}", CHECKER).statusCode());
            assertEquals(422, send(c, "PUT", "/settings/egress", "{\"models\":\"localhost\"}", CHECKER).statusCode());
            data(send(c, "PUT", "/settings/egress", "{\"allow\":[\"pcrf.internal\"]}", CHECKER), 200);
            data(send(c, "PUT", "/settings/egress", "{\"models\":[\"localhost\",\"ollama.lan\"]}", CHECKER), 200);
            JsonNode both = data(send(c, "GET", "/settings/egress", null, AUTHOR), 200);
            assertEquals(2, both.get("models").size(), both.toString());
            assertEquals(1, both.get("allow").size(), "a models-only PUT must keep allow: " + both);
            data(send(c, "PUT", "/settings/egress", "{\"allow\":[]}", CHECKER), 200);
            assertEquals(2, data(send(c, "GET", "/settings/egress", null, AUTHOR), 200).get("models").size(),
                    "an allow-only PUT must keep models");
        }
    }

    /** ASSURE-XLSX-ATTACHMENTS-1: the attachment domain allowlist is EMPTY by default, admin-only, validated fail closed. */
    @Test
    void theMailAttachmentDomainAllowlistIsEmptyByDefaultAdminOnlyAndValidated(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            assertEquals(0, data(send(c, "GET", "/settings/mail-attachments", null, AUTHOR), 200).get("allow").size(),
                    "empty by default: attachments are off until an admin names a domain");
            assertEquals(403, send(c, "PUT", "/settings/mail-attachments", "{\"allow\":[\"example.com\"]}", ANALYST2)
                    .statusCode());
            for (String bad : List.of("a@b.example", "*.example.com", "localhost", "http://x.example", "a b.example"))
                assertEquals(422, send(c, "PUT", "/settings/mail-attachments", "{\"allow\":[\"" + bad + "\"]}", CHECKER)
                        .statusCode(), bad);
            assertEquals(422, send(c, "PUT", "/settings/mail-attachments", "{\"deny\":[]}", CHECKER).statusCode());
            JsonNode put = data(send(c, "PUT", "/settings/mail-attachments",
                    "{\"allow\":[\"Example.COM\",\"bücher.example\"]}", CHECKER), 200);
            assertEquals("example.com", put.get("allow").get(0).asText());
            assertEquals("xn--bcher-kva.example", put.get("allow").get(1).asText(), "stored in punycode");
            assertEquals(2, data(send(c, "GET", "/settings/mail-attachments", null, AUTHOR), 200).get("allow").size());
        }
    }

    /** WEBHOOK-EGRESS-POLICY-1: BOOT seeds a pre-existing Space from its webhook Step targets; a removal sticks. */
    @Test
    void bootSeedsTheAllowlistFromWebhookTargetsAndARemovalIsNeverReSeeded(@TempDir Path cfg, @TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        Files.writeString(root.resolve("cbs_connection.toon"), dev.toonformat.jtoon.JToon.encode(Map.of("connection",
                Map.of("id", "cbs", "connector", "https", "host", "cbs.internal"))));
        Files.writeString(root.resolve("orders_hook.toon"), dev.toonformat.jtoon.JToon.encode(Map.of("webhook",
                Map.of("connection", "cbs"))));
        try (Ctx c = open(cfg, tmp, true)) {
            assertEquals("[\"cbs.internal\"]",
                    data(send(c, "GET", "/settings/egress", null, AUTHOR), 200).get("allow").toString());
            data(send(c, "PUT", "/settings/egress", "{\"allow\":[]}", CHECKER), 200);
        }
        try (Ctx c = open(cfg, tmp, true)) {   // the next boot
            assertEquals(0, data(send(c, "GET", "/settings/egress", null, AUTHOR), 200).get("allow").size(),
                    "a removed seed entry is not re-seeded");
        }
    }

    /** A Space whose allowlist file is missing after boot is never seeded by a read. */
    @Test
    void aReadNeverSeedsTheAllowlist(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            Path root = c.root();
            Files.deleteIfExists(root.resolve(EgressRoutes.FILE));
            Files.writeString(root.resolve("cbs_connection.toon"), dev.toonformat.jtoon.JToon.encode(Map.of("connection",
                    Map.of("id", "cbs", "connector", "https", "host", "10.0.0.5"))));
            Files.writeString(root.resolve("orders_hook.toon"), dev.toonformat.jtoon.JToon.encode(Map.of("webhook",
                    Map.of("connection", "cbs"))));
            assertEquals(0, data(send(c, "GET", "/settings/egress", null, AUTHOR), 200).get("allow").size());
            assertFalse(Files.exists(root.resolve(EgressRoutes.FILE)));
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

    private static final String INVOKE_RULE = "{\"name\":\"leak\",\"consequences\":[{\"action\":\"invoke-api\","
            + "\"params\":{\"connection\":\"hook\"}}]}";

    /** Verification finding 3: saving an invoke-api rule is proposing outbound calls by proxy. */
    @Test
    void savingAnInvokeApiRuleNeedsCanWorkIncidents(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            HttpResponse<String> dev = send(c, "POST", "/decision-rules", INVOKE_RULE, DEV);
            assertEquals(403, dev.statusCode(), dev.body());
            data(send(c, "POST", "/decision-rules", INVOKE_RULE, POWER), 200);
            assertEquals(403, send(c, "PUT", "/decision-rules/leak", INVOKE_RULE, DEV).statusCode(), "the update path too");
            data(send(c, "POST", "/decision-rules", "{\"name\":\"plain\",\"consequences\":[{\"action\":"
                    + "\"emit-signal\"}]}", DEV), 200);
        }
    }

    /** Verification finding 3: the rule's makers are co-authors of what it raises, so neither may approve it. */
    @Test
    void theRulesEditorCannotApproveTheRequestItRaised(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            data(send(c, "POST", "/decision-rules", INVOKE_RULE, EDITOR), 200);
            String id = data(send(c, "POST", "/decision-rules/leak/apply", "{}", AUTHOR), 200)
                    .at("/executed/0/actionRequestId").asText();
            JsonNode rec = data(send(c, "GET", "/action-requests/" + id, null, CHECKER), 200);
            assertEquals("editor-1", rec.at("/coAuthors/0").asText(), rec.toString());
            HttpResponse<String> editor = send(c, "POST", "/action-requests/" + id + "/approve", "{}", EDITOR);
            assertEquals(403, editor.statusCode(), editor.body());
            assertTrue(editor.body().contains("edited the Decision Rule"), editor.body());
            assertEquals(403, send(c, "POST", "/action-requests/" + id + "/decline", "{}", EDITOR).statusCode());
            assertTrue(keys.isEmpty());
            assertEquals("succeeded", data(send(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER), 200)
                    .get("status").asText());
        }
    }

    /** Verification finding 4: invoke-api params are validated when the rule is saved, not when it is applied. */
    @Test
    void invokeApiParamsAreValidatedAtSave(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            ConnectionRegistry.register(new ConnectionProfile("files", "sftp", "files.example.test", 22, null, "in",
                    null, null, Map.of(), null, null));
            HttpResponse<String> url = send(c, "POST", "/decision-rules", INVOKE_RULE.replace(
                    "\"connection\":\"hook\"", "\"url\":\"https://x.example/api\""), POWER);
            assertEquals(422, url.statusCode(), url.body());
            assertTrue(url.body().contains("not params.url"), url.body());
            for (String params : List.of("{}", "{\"connection\":\"nope\"}", "{\"connection\":\"files\"}"))
                assertEquals(422, send(c, "POST", "/decision-rules", INVOKE_RULE.replace(
                        "{\"connection\":\"hook\"}", params), POWER).statusCode(), params);
            data(send(c, "POST", "/decision-rules", INVOKE_RULE, POWER), 200);
        }
    }

    @Test
    void aRuleWithNoRecordedEditorRaisesNothing(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            new com.gamma.pipeline.ComponentStore(c.root.resolve("registry")).write("decision-rule", "legacy",
                    Map.of("name", "legacy", "enabled", true, "consequences",
                            List.of(Map.of("action", "invoke-api", "params", Map.of("connection", "hook")))));
            JsonNode one = data(send(c, "POST", "/decision-rules/legacy/apply", "{}", AUTHOR), 200).at("/executed/0");
            assertEquals("skipped", one.get("status").asText(), one.toString());
            assertTrue(one.get("detail").asText().contains("no recorded editor"), one.toString());
            assertEquals(0, data(send(c, "GET", "/action-requests", null, CHECKER), 200).get("total").asInt());
        }
    }

    /** RESIDUALS-1 (3): the fail-closed skip is audited once per skip (WARN, rule name only); it still raises nothing. */
    @Test
    void aSkipForUnknownMakersEmitsOneWarnAuditAndStillFailsClosed(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        List<com.gamma.audit.Event> seen = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<com.gamma.audit.Event> sub = seen::add;
        com.gamma.audit.EventLog.global().addSubscriber(sub);
        try (Ctx c = open(cfg, tmp, true)) {
            new com.gamma.pipeline.ComponentStore(c.root.resolve("registry")).write("decision-rule", "legacy",
                    Map.of("name", "legacy", "enabled", true, "consequences",
                            List.of(Map.of("action", "invoke-api", "params", Map.of("connection", "hook")))));
            JsonNode one = data(send(c, "POST", "/decision-rules/legacy/apply", "{}", AUTHOR), 200).at("/executed/0");
            assertEquals("skipped", one.get("status").asText(), one.toString());
            List<com.gamma.audit.Event> warn = seen.stream().filter(e -> e.attributes()
                    .containsValue("action-request.skipped-unknown-makers")).toList();
            assertEquals(1, warn.size(), "exactly one audit for the one skip");
            assertEquals(com.gamma.audit.EventLevel.WARN, warn.get(0).level());
            assertEquals("legacy", warn.get(0).attributes().get("decisionRule"));
            assertEquals(0, data(send(c, "GET", "/action-requests", null, CHECKER), 200).get("total").asInt(),
                    "still fails closed — no request raised");
        } finally {
            com.gamma.audit.EventLog.global().removeSubscriber(sub);
        }
    }

    // ── round-2 finding 1: every writer of a decision-rule runs the ONE guard ─────────────────────

    private static final String FORGED_RULE = "{\"name\":\"leak\",\"createdBy\":\"someone-else\",\"updatedBy\":\"someone-else\","
            + "\"consequences\":[{\"action\":\"invoke-api\",\"params\":{\"connection\":\"hook\"}}]}";

    /** The reproduced side door: /components/decision-rule, a forged editor, then the maker approving. */
    @Test
    void theGenericComponentDoorRunsTheGuardAndStampsTheMakers(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            assertEquals(403, send(c, "POST", "/components/decision-rule", FORGED_RULE, DEV).statusCode(),
                    "no canWorkIncidents: no invoke-api rule through this door either");
            assertEquals(422, send(c, "POST", "/components/decision-rule", FORGED_RULE.replace(
                    "\"connection\":\"hook\"", "\"url\":\"https://x.example\""), POWER).statusCode(), "params validated too");
            data(send(c, "POST", "/components/decision-rule", FORGED_RULE, POWER), 200);
            JsonNode stored = data(send(c, "GET", "/components/decision-rule/leak", null, POWER), 200);
            assertEquals("power-1", stored.at("/content/updatedBy").asText(stored.toString()), stored.toString());
            assertEquals("power-1", stored.at("/content/createdBy").asText(), "the body's editor is never trusted");
            String id = data(send(c, "POST", "/decision-rules/leak/apply", "{}", AUTHOR), 200)
                    .at("/executed/0/actionRequestId").asText();
            HttpResponse<String> maker = send(c, "POST", "/action-requests/" + id + "/approve", "{}", POWER);
            assertEquals(403, maker.statusCode(), maker.body());
            assertTrue(keys.isEmpty());
        }
    }

    /** Makers come from the version history: every editor since the invoke-api consequence last changed. */
    @Test
    void makersAreEveryEditorSinceTheInvokeApiConsequenceLastChanged(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            data(send(c, "POST", "/decision-rules", INVOKE_RULE, EDITOR), 200);
            data(send(c, "PUT", "/decision-rules/leak", INVOKE_RULE.replace("\"name\":\"leak\",",
                    "\"name\":\"leak\",\"description\":\"reworded\","), POWER), 200);   // same invoke-api
            String first = data(send(c, "POST", "/decision-rules/leak/apply", "{}", AUTHOR), 200)
                    .at("/executed/0/actionRequestId").asText();
            JsonNode rec = data(send(c, "GET", "/action-requests/" + first, null, CHECKER), 200);
            assertEquals(List.of("power-1", "editor-1"), JSON.convertValue(rec.get("coAuthors"), List.class));
            assertEquals(403, send(c, "POST", "/action-requests/" + first + "/approve", "{}", EDITOR).statusCode(),
                    "the maker of the consequence, two versions back");
            data(send(c, "POST", "/action-requests/" + first + "/decline", "{}", CHECKER), 200);

            data(send(c, "PUT", "/decision-rules/leak", INVOKE_RULE.replace("\"params\":{\"connection\":\"hook\"}",
                    "\"params\":{\"connection\":\"hook\",\"payload\":{\"n\":\"2\"}}"), POWER), 200);   // changed
            String second = data(send(c, "POST", "/decision-rules/leak/apply", "{}", AUTHOR), 200)
                    .at("/executed/0/actionRequestId").asText();
            assertEquals(List.of("power-1"), JSON.convertValue(data(send(c, "GET", "/action-requests/" + second, null,
                    CHECKER), 200).get("coAuthors"), List.class), "the change resets who made it");
            assertEquals("succeeded", data(send(c, "POST", "/action-requests/" + second + "/approve", "{}", EDITOR), 200)
                    .get("status").asText(), "an earlier editor of a consequence that has since changed may approve");
        }
    }

    @Test
    void aVersionRestoreOfAnInvokeApiRuleIsGuarded(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            data(send(c, "POST", "/decision-rules", INVOKE_RULE, POWER), 200);
            data(send(c, "PUT", "/decision-rules/leak", "{\"name\":\"leak\",\"consequences\":[{\"action\":"
                    + "\"emit-signal\"}]}", POWER), 200);
            assertEquals(403, send(c, "POST", "/components/decision-rule/leak/versions/1/restore", "{}", DEV).statusCode(),
                    "restoring the invoke-api version is writing one");
            data(send(c, "POST", "/components/decision-rule/leak/versions/1/restore", "{}", POWER), 200);
        }
    }

    /**
     * ASSURE-ACTION-REQUESTS-RESIDUALS-1 (1): restoring an old invoke-api version makes the RESTORER its editor, but
     * the restored consequence's author is still a maker — neither may approve what it raises, and a later save that
     * keeps the consequence (even one forging {@code restoredMakers}) does not forget them.
     */
    @Test
    void aRestoredInvokeApiVersionKeepsItsAuthorAsACoAuthor(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            data(send(c, "POST", "/decision-rules", INVOKE_RULE, EDITOR), 200);   // v1: editor-1's target
            data(send(c, "PUT", "/decision-rules/leak", INVOKE_RULE.replace("\"params\":{\"connection\":\"hook\"}",
                    "\"params\":{\"connection\":\"hook\",\"payload\":{\"n\":\"2\"}}"), POWER), 200);   // changed
            data(send(c, "POST", "/components/decision-rule/leak/versions/1/restore", "{}", POWER), 200);
            String first = data(send(c, "POST", "/decision-rules/leak/apply", "{}", AUTHOR), 200)
                    .at("/executed/0/actionRequestId").asText();
            JsonNode rec = data(send(c, "GET", "/action-requests/" + first, null, CHECKER), 200);
            assertEquals(List.of("power-1", "editor-1"), JSON.convertValue(rec.get("coAuthors"), List.class), rec.toString());
            HttpResponse<String> author = send(c, "POST", "/action-requests/" + first + "/approve", "{}", EDITOR);
            assertEquals(403, author.statusCode(), "the restored version's author: " + author.body());
            assertEquals(403, send(c, "POST", "/action-requests/" + first + "/approve", "{}", POWER).statusCode(),
                    "the restorer");
            data(send(c, "POST", "/action-requests/" + first + "/decline", "{}", CHECKER), 200);

            data(send(c, "PUT", "/decision-rules/leak", INVOKE_RULE.replace("\"name\":\"leak\",",
                    "\"name\":\"leak\",\"description\":\"reworded\",\"restoredMakers\":[\"forged\"],"), POWER), 200);
            String second = data(send(c, "POST", "/decision-rules/leak/apply", "{}", AUTHOR), 200)
                    .at("/executed/0/actionRequestId").asText();
            assertEquals(List.of("power-1", "editor-1"), JSON.convertValue(data(send(c, "GET",
                    "/action-requests/" + second, null, CHECKER), 200).get("coAuthors"), List.class),
                    "the restored stamp is carried down the chain; a body value is discarded");
            assertEquals(403, send(c, "POST", "/action-requests/" + second + "/approve", "{}", EDITOR).statusCode());
            assertTrue(keys.isEmpty());
        }
    }

    /** Fail closed: a version whose invoke-api author the history cannot say is not restored at all. */
    @Test
    void restoringAVersionWithNoRecordedEditorIsRefused(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            new com.gamma.pipeline.ComponentStore(c.root.resolve("registry")).write("decision-rule", "leak",
                    Map.of("name", "leak", "consequences",
                            List.of(Map.of("action", "invoke-api", "params", Map.of("connection", "hook")))));
            data(send(c, "PUT", "/decision-rules/leak", "{\"name\":\"leak\",\"consequences\":[{\"action\":"
                    + "\"emit-signal\"}]}", POWER), 200);
            HttpResponse<String> r = send(c, "POST", "/components/decision-rule/leak/versions/1/restore", "{}", POWER);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("no recorded editor"), r.body());
            assertEquals("emit-signal", data(send(c, "GET", "/components/decision-rule/leak", null, POWER), 200)
                    .at("/content/consequences/0/action").asText(), "nothing was written");
        }
    }

    // ── RESIDUALS-1 (3) option C: the makers are stamped on the rule at save, so pruning cannot erase them ───────

    /**
     * Ten stored versions straight to the store (no stamp): v1 = editor-1's invoke-api consequence, v2..v10 =
     * power-1 rewording it. The history walk can still name the makers (9 archived < keep), but the NEXT two writes
     * prune v1 — after which only a stamp carried on the rule says editor-1 made the consequence.
     */
    private static void seedTenUnstampedVersions(Ctx c) throws Exception {
        com.gamma.pipeline.ComponentStore store = new com.gamma.pipeline.ComponentStore(c.root.resolve("registry"));
        for (int v = 1; v <= 10; v++)
            store.write("decision-rule", "leak", Map.of("name", "leak", "enabled", true, "description", "v" + v,
                    "updatedBy", v == 1 ? "editor-1" : "power-1",
                    "consequences", List.of(Map.of("action", "invoke-api", "params", Map.of("connection", "hook")))));
        assertEquals(9, store.versions("decision-rule", "leak").size());
        assertEquals(10, com.gamma.pipeline.ComponentStore.historyKeep(), "the scenario assumes the default keep");
    }

    private static final String REWORDED = INVOKE_RULE.replace("\"name\":\"leak\",", "\"name\":\"leak\",\"description\":\"%s\",");

    /** What the write under test stamped on the stored rule. */
    private List<?> storedMakers(Ctx c) throws Exception {
        return JSON.convertValue(data(send(c, "GET", "/components/decision-rule/leak", null, POWER), 200)
                .at("/content/makers"), List.class);
    }

    /** After the write under test: one more save prunes v1; the rule must still raise with editor-1 a co-author. */
    private void pruneThenAssertEditorStillAMaker(Ctx c) throws Exception {
        // an import replaces the head without archiving it, so it can take two saves to push v1 out
        com.gamma.pipeline.ComponentStore store = new com.gamma.pipeline.ComponentStore(c.root.resolve("registry"));
        for (int i = 0; i < 3 && store.versions("decision-rule", "leak").stream()
                .anyMatch(v -> "editor-1".equals(v.content().get("updatedBy"))); i++)
            data(send(c, "PUT", "/decision-rules/leak", REWORDED.formatted("prunes v1 #" + i), POWER), 200);
        assertTrue(store.versions("decision-rule", "leak").stream()
                .noneMatch(v -> "editor-1".equals(v.content().get("updatedBy"))), "editor-1's version is pruned");
        JsonNode one = data(send(c, "POST", "/decision-rules/leak/apply", "{}", AUTHOR), 200).at("/executed/0");
        String id = one.path("actionRequestId").asText();
        assertFalse(id.isBlank(), "a pruned history still raises: " + one);
        JsonNode rec = data(send(c, "GET", "/action-requests/" + id, null, CHECKER), 200);
        assertTrue(JSON.convertValue(rec.get("coAuthors"), List.class).containsAll(List.of("power-1", "editor-1")),
                rec.toString());
        assertEquals(403, send(c, "POST", "/action-requests/" + id + "/approve", "{}", EDITOR).statusCode(),
                "four-eyes still sees the pruned version's author");
        assertTrue(keys.isEmpty());
    }

    @Test
    void theSaveRouteStampsTheMakersSoAPrunedHistoryStillRaises(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            seedTenUnstampedVersions(c);
            data(send(c, "PUT", "/decision-rules/leak", REWORDED.formatted("saved"), POWER), 200);
            pruneThenAssertEditorStillAMaker(c);
        }
    }

    @Test
    void aBundleImportStampsTheMakersSoAPrunedHistoryStillRaises(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            seedTenUnstampedVersions(c);
            data(send(c, "POST", "/bundle/import", "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,"
                    + "\"actions\":{\"decision-rule/leak\":\"overwrite\"},\"items\":["
                    + "{\"kind\":\"decision-rule\",\"id\":\"leak\",\"content\":" + REWORDED.formatted("imported")
                    .replace("\"name\":\"leak\",", "\"name\":\"leak\",\"makers\":[\"nobody\"],") + "}]}", POWER), 200);
            assertEquals(List.of("power-1", "editor-1"), storedMakers(c), "stamped server-side; the item's value dropped");
            pruneThenAssertEditorStillAMaker(c);
        }
    }

    @Test
    void aRawImportStampsTheMakersSoAPrunedHistoryStillRaises(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            seedTenUnstampedVersions(c);
            String rule = com.gamma.config.io.ConfigCodec.toToon(JSON.readValue(REWORDED.formatted("raw import"), Map.class));
            HttpResponse<String> r = sendBytes(c, "/import", importZip(Map.of("registry/decision-rules/leak.toon", rule)), POWER);
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(List.of("power-1", "editor-1"), storedMakers(c));
            pruneThenAssertEditorStillAMaker(c);
        }
    }

    @Test
    void aVersionRestoreStampsTheMakersSoAPrunedHistoryStillRaises(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            allowLoopback(c);
            seedTenUnstampedVersions(c);
            data(send(c, "POST", "/components/decision-rule/leak/versions/5/restore", "{}", POWER), 200);
            pruneThenAssertEditorStillAMaker(c);
        }
    }

    /** The stamp is server-set: a body's {@code makers} is discarded and recomputed, never trusted. */
    @Test
    void aClientCannotForgeTheStampedMakers(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            data(send(c, "POST", "/decision-rules", INVOKE_RULE.replace("\"name\":\"leak\",",
                    "\"name\":\"leak\",\"makers\":[\"nobody\"],"), EDITOR), 200);
            assertEquals(List.of("editor-1"), JSON.convertValue(data(send(c, "GET", "/components/decision-rule/leak",
                    null, POWER), 200).at("/content/makers"), List.class), "a create's makers are the writer alone");
            data(send(c, "PUT", "/decision-rules/leak", REWORDED.formatted("x").replace("\"name\":\"leak\",",
                    "\"name\":\"leak\",\"makers\":[],"), POWER), 200);
            assertEquals(List.of("power-1", "editor-1"), JSON.convertValue(data(send(c, "GET",
                    "/components/decision-rule/leak", null, POWER), 200).at("/content/makers"), List.class),
                    "an empty body list cannot drop the earlier maker");
            data(send(c, "POST", "/components/decision-rule", FORGED_RULE.replace("\"name\":\"leak\"",
                    "\"name\":\"leak2\",\"makers\":[\"nobody\"]"), POWER), 200);
            assertEquals(List.of("power-1"), JSON.convertValue(data(send(c, "GET", "/components/decision-rule/leak2",
                    null, POWER), 200).at("/content/makers"), List.class), "the generic door too");
        }
    }

    private HttpResponse<String> sendBytes(Ctx c, String path, byte[] body, String auth) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", auth).POST(BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString());
    }

    private static byte[] importZip(Map<String, String> entries) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bytes)) {
            Map<String, String> all = new java.util.LinkedHashMap<>();
            all.put("bundle.toon", "kind: datasource\n");
            all.putAll(entries);
            for (Map.Entry<String, String> e : all.entrySet()) {
                z.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** The raw /import: an invoke-api rule needs canWorkIncidents, all-or-nothing, and its editor is stamped. */
    @Test
    void aRawImportCarryingAnInvokeApiRuleIsGuardedAllOrNothing(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String rule = com.gamma.config.io.ConfigCodec.toToon(JSON.readValue(FORGED_RULE, Map.class));
            String dataset = com.gamma.config.io.ConfigCodec.toToon(Map.of("name", "side_ds", "title", "Side"));
            byte[] zip = importZip(Map.of("registry/decision-rules/leak.toon", rule, "registry/datasets/side_ds.toon", dataset));
            HttpResponse<String> dev = sendBytes(c, "/import", zip, DEV);
            assertEquals(403, dev.statusCode(), dev.body());
            assertFalse(Files.exists(c.root.resolve("registry/decision-rules/leak.toon")));
            assertFalse(Files.exists(c.root.resolve("registry/datasets/side_ds.toon")), "all-or-nothing");
            HttpResponse<String> power = sendBytes(c, "/import", zip, POWER);
            assertEquals(200, power.statusCode(), power.body());
            assertEquals("power-1", data(send(c, "GET", "/components/decision-rule/leak", null, POWER), 200)
                    .at("/content/updatedBy").asText(), "the file's forged editor was replaced");
        }
    }

    /** /bundle/import: the same guard before the first item is written. */
    @Test
    void aBundleImportCarryingAnInvokeApiRuleIsGuardedAllOrNothing(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, tmp, true)) {
            String bundle = "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"dataset\",\"id\":\"side_ds\",\"content\":{\"title\":\"Side\"}},"
                    + "{\"kind\":\"decision-rule\",\"id\":\"leak\",\"content\":" + FORGED_RULE + "}]}";
            HttpResponse<String> dev = send(c, "POST", "/bundle/import", bundle, DEV);
            assertEquals(403, dev.statusCode(), dev.body());
            assertEquals(404, send(c, "GET", "/components/dataset/side_ds", null, POWER).statusCode(), "all-or-nothing");
            data(send(c, "POST", "/bundle/import", bundle, POWER), 200);
            assertEquals("power-1", data(send(c, "GET", "/components/decision-rule/leak", null, POWER), 200)
                    .at("/content/updatedBy").asText());
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

    /** The Space's approver roster ({@link ApproverRoster}): every id this class's Authenticator mints. */
    private static void seedApproverRoster(Path root) throws java.io.IOException {
        java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                java.util.Map.of("users", java.util.List.of("author-1", "analyst-2", "checker-1", "checker-2", "dev-1", "editor-1", "power-1", "scoped-1"))));
    }
}
