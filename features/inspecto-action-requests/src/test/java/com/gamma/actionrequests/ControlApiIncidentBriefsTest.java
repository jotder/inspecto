package com.gamma.actionrequests;

import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.control.ApproverRoster;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.exec.WebhookSinkTransport;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.workflow.ObjectType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Incident Briefs over real HTTP ({@code AGENTIC-INVESTIGATION-1} S1): every create gate with an armed Authenticator,
 * the deterministic why / evidence / value-at-risk content, scope-filtered evidence, and — the safety invariant — a
 * proposed next action is a {@code pending} Action Request that nothing sends until a person OTHER than the one who
 * started the Brief approves it. No model is involved anywhere: the Brief is a fixed playbook.
 */
class ControlApiIncidentBriefsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTHOR = "Bearer author", CHECKER = "Bearer checker", SELF = "Bearer self",
            DEV = "Bearer dev", SCOPED = "Bearer scoped";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    private HttpServer target;
    private Executor priorExecutor;
    private Supplier<WebhookSinkTransport> priorTransport;
    private com.gamma.util.egress.EgressPolicy.Resolver priorResolver;

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** AUTHOR = operations (canWorkIncidents); CHECKER = admin; SELF = the author's id as admin; DEV lacks canWorkIncidents. */
    @BeforeEach
    void arm() throws Exception {
        Authenticators.forTest(ex -> {
            String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (SCOPED.equals(auth))
                return Optional.of(new Subject("scoped-1", Roles.effective(ex).get("operations").capabilities(),
                        Set.of("billing")));
            String[] who = switch (auth) {
                case AUTHOR -> new String[] {"author-1", "operations"};
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
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        target.start();
        ConnectionRegistry.register(new ConnectionProfile("hook", "https", "tickets.test", target.getAddress().getPort(),
                null, "api", null, null, Map.of(), null, null));
        priorExecutor = ActionDispatcher.executor;
        priorTransport = ActionDispatcher.transport;
        priorResolver = ActionDispatcher.resolver;
        ActionDispatcher.resolver = ActionDispatcherTest.NET;
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
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(cfg), "");
        if (writeRoot != null) {
            System.setProperty("assist.write.root", writeRoot.toString());
            Files.createDirectories(writeRoot);
            Files.writeString(writeRoot.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("author-1", "checker-1"))));
        }
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static String incident(Ctx c, String title, Map<String, String> attrs) {
        return c.svc.objects().orElseThrow().open(ObjectType.INCIDENT, title, title,
                "error", "t", attrs);
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

    private static final String PROPOSE = "{\"propose\":{\"connection\":\"hook\",\"method\":\"POST\","
            + "\"payloadTemplate\":{\"ticket\":\"{{incident.id}}\"}}}";

    @Test
    void theBriefExplainsCitesAndSaysValueAtRiskIsNotDeclaredWhenNoImpactIsRecorded(@TempDir Path tmp) throws Exception {
        try (Ctx c = open(tmp.resolve("cfg"), tmp.resolve("root"))) {
            String id = incident(c, "Billing leakage on voice CDRs", Map.of("alertRule", "voice-leak", "threshold", "100",
                    "observed", "412"));
            String past = incident(c, "Billing leakage on SMS CDRs", Map.of());
            incident(c, "Unrelated disk alarm", Map.of());

            assertEquals(401, send(c, "POST", "/incidents/" + id + "/briefs", "{}", null).statusCode());
            HttpResponse<String> denied = send(c, "POST", "/incidents/" + id + "/briefs", "{}", DEV);
            assertEquals(403, denied.statusCode(), denied.body());
            assertEquals(403, send(c, "GET", "/incidents/" + id + "/briefs", null, DEV).statusCode());

            JsonNode b = data(send(c, "POST", "/incidents/" + id + "/briefs", "{}", AUTHOR), 200);
            assertTrue(b.get("id").asText().matches("ib-\\d{14}-[0-9a-f]{6}"));
            assertEquals("author-1", b.get("startedBy").asText());
            assertTrue(b.get("author").asText().startsWith("agent:incident-brief:"));
            assertEquals("voice-leak", b.at("/why/trigger/alertRule").asText());
            assertEquals("412", b.at("/why/trigger/observed").asText());
            assertEquals("e1", b.at("/why/cites/0").asText());
            assertFalse(b.at("/valueAtRisk/declared").asBoolean(), "no impact recorded: never a guessed number");
            assertTrue(b.at("/valueAtRisk/amount").isMissingNode());
            assertEquals(past, b.at("/similar/0/id").asText(), "the SMS leakage Incident is the most similar");
            assertEquals(1, b.get("similar").size(), "the unrelated Incident shares no token and is not cited");
            assertEquals("similar-incident", b.at("/evidence/1/kind").asText());
            assertTrue(b.get("proposal").isNull(), "no proposal asked for, none filed");

            JsonNode list = data(send(c, "GET", "/incidents/" + id + "/briefs", null, AUTHOR), 200);
            assertEquals(1, list.get("total").asInt());
            assertEquals(b.get("id").asText(), list.at("/items/0/id").asText());
        }
    }

    @Test
    void valueAtRiskIsTheDeclaredImpactWithItsFormula(@TempDir Path tmp) throws Exception {
        try (Ctx c = open(tmp.resolve("cfg"), tmp.resolve("root"))) {
            String id = incident(c, "Roaming leakage", Map.of());
            data(send(c, "PUT", "/objects/" + id + "/impact",
                    "{\"impact\":{\"suspected\":\"900\",\"confirmed\":\"750.50\",\"recovered\":\"50\",\"currency\":\"EUR\"}}", AUTHOR), 200);
            JsonNode b = data(send(c, "POST", "/incidents/" + id + "/briefs", "{}", AUTHOR), 200);
            assertTrue(b.at("/valueAtRisk/declared").asBoolean());
            assertEquals("700.5", b.at("/valueAtRisk/amount").asText(), "outstanding = confirmed − recovered");
            assertEquals("EUR", b.at("/valueAtRisk/currency").asText());
            assertTrue(b.at("/valueAtRisk/formula").asText().contains("confirmed 750.5 "));
            assertEquals("e2", b.at("/valueAtRisk/cites/0").asText());
            assertEquals("impact", b.at("/evidence/1/kind").asText());
        }
    }

    @Test
    void createGatesRefuseInOrder(@TempDir Path tmp) throws Exception {
        try (Ctx noRoot = open(tmp.resolve("cfg0"), null)) {
            String id = incident(noRoot, "Leak", Map.of());
            assertEquals(503, send(noRoot, "POST", "/incidents/" + id + "/briefs", "{}", AUTHOR).statusCode());
        }
        try (Ctx c = open(tmp.resolve("cfg"), tmp.resolve("root"))) {
            String id = incident(c, "Leak", Map.of());
            assertEquals(422, send(c, "POST", "/incidents/" + id + "/briefs", "{\"model\":\"x\"}", AUTHOR).statusCode());
            assertEquals(422, send(c, "POST", "/incidents/" + id + "/briefs", "{\"propose\":\"run it\"}", AUTHOR).statusCode());
            assertEquals(422, send(c, "POST", "/incidents/" + id + "/briefs",
                    "{\"propose\":{\"connection\":\"hook\",\"approve\":true}}", AUTHOR).statusCode());
            assertEquals(404, send(c, "POST", "/incidents/nope/briefs", "{}", AUTHOR).statusCode());
            assertEquals(404, send(c, "GET", "/incidents/nope/briefs", null, AUTHOR).statusCode());
            String kase = c.svc.objects().orElseThrow().open(ObjectType.CASE, "A case", "d", "error", "t", Map.of());
            assertEquals(422, send(c, "POST", "/incidents/" + kase + "/briefs", "{}", AUTHOR).statusCode());
            assertTrue(Files.notExists(tmp.resolve("root").resolve(IncidentBriefs.DIR)), "a refused create saves nothing");
        }
    }

    @Test
    void evidenceNeverCitesAnIncidentOutsideTheCallersScope(@TempDir Path tmp) throws Exception {
        try (Ctx c = open(tmp.resolve("cfg"), tmp.resolve("root"))) {
            String mine = incident(c, "Billing leakage on voice", Map.of("caseType", "billing"));
            String hidden = incident(c, "Billing leakage on voice too", Map.of("caseType", "fraud"));
            String visible = incident(c, "Billing leakage on data", Map.of("caseType", "billing"));
            JsonNode b = data(send(c, "POST", "/incidents/" + mine + "/briefs", "{}", SCOPED), 200);
            assertFalse(b.toString().contains(hidden), "an out-of-scope Incident is never cited: " + b.get("similar"));
            assertTrue(b.toString().contains(visible));
            assertEquals(404, send(c, "POST", "/incidents/" + hidden + "/briefs", "{}", SCOPED).statusCode(),
                    "an invisible Incident reads as absent");
            // the unscoped analyst does see it — the filter above is the scope, not the similarity
            JsonNode all = data(send(c, "POST", "/incidents/" + mine + "/briefs", "{}", AUTHOR), 200);
            assertTrue(all.toString().contains(hidden));
            // ...and a Brief the unscoped analyst saved is re-filtered for the scoped reader (verification finding)
            JsonNode list = data(send(c, "GET", "/incidents/" + mine + "/briefs", null, SCOPED), 200);
            assertEquals(2, list.get("total").asInt());
            assertFalse(list.toString().contains(hidden), "a stored Brief never leaks an out-of-scope Incident: " + list);
            assertTrue(list.toString().contains(visible));
            assertTrue(data(send(c, "GET", "/incidents/" + mine + "/briefs", null, AUTHOR), 200).toString().contains(hidden));
        }
    }

    /**
     * The safety invariant: the proposal is filed, never sent; the person who started the Brief cannot approve it;
     * only a different person's approval makes the call. Falsified: making the starting human NOT a maker of the
     * proposal (author = the agent identity only) turns the SELF approve below into 200 and this test red.
     */
    @Test
    void theProposedActionDoesNothingUntilADifferentPersonApprovesIt(@TempDir Path tmp) throws Exception {
        try (Ctx c = open(tmp.resolve("cfg"), tmp.resolve("root"))) {
            data(send(c, "PUT", "/settings/egress", "{\"allow\":[\"tickets.test\"]}", CHECKER), 200);
            String id = incident(c, "Leak", Map.of());
            JsonNode b = data(send(c, "POST", "/incidents/" + id + "/briefs", PROPOSE, AUTHOR), 200);
            String ar = b.at("/proposal/actionRequestId").asText();
            assertEquals("pending", b.at("/proposal/status").asText());
            assertTrue(bodies.isEmpty(), "filing the proposal sent nothing");

            JsonNode rec = data(send(c, "GET", "/action-requests/" + ar, null, CHECKER), 200);
            assertEquals("author-1", rec.get("author").asText(), "the human who asked is the maker");
            assertEquals("incident-brief", rec.get("origin").asText());
            assertEquals("pending", rec.get("status").asText());

            HttpResponse<String> self = send(c, "POST", "/action-requests/" + ar + "/approve", "{}", SELF);
            assertEquals(403, self.statusCode(), "the starter may not approve the Brief's proposal: " + self.body());
            assertTrue(bodies.isEmpty(), "a refused approval sent nothing");

            JsonNode approved = data(send(c, "POST", "/action-requests/" + ar + "/approve", "{}", CHECKER), 200);
            assertEquals("succeeded", approved.get("status").asText(), approved.toString());
            assertEquals(1, bodies.size(), "sent exactly once, after the four-eyes approval");
            assertTrue(bodies.get(0).contains(id));
        }
    }
}
