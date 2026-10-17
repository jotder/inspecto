package com.gamma.geolink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.audit.Event;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.pipeline.exec.WebhookSinkTransport;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.egress.EgressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-EDITIONS-1 phase 6 — third-party integration, outbound: the settings routes, the case hand-off and the signed event
 * webhooks, over real HTTP with an ARMED Authenticator (with no Subject {@code withCapability} is a no-op), against a local
 * stub HTTP server. No network: the targets' names resolve to private addresses through a test resolver, the Space
 * allowlists them, and the test wire (the shape of the Standard transport, redirects never followed) connects every
 * checked address to the loopback stub.
 */
class ControlApiLaIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner", REVEALER = "Bearer revealer", ADMIN = "Bearer admin", NOCAP = "Bearer nocap";
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"inv-a\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String TOKEN_PROP = "la.test.cm.token", SECRET_PROP = "la.test.hook.secret";   // secret-allow: system-property NAMES of a test fixture, not values
    private static final String SECRET = "s3cr3t-signing-key";   // secret-allow: a throwaway HMAC key for the local stub server, never a real credential
    private final HttpClient client = HttpClient.newHttpClient();

    /** The test DNS: the targets answer private addresses; anything else resolves for real. */
    static final EgressPolicy.Resolver NET = h -> switch (h) {
        case "cases.test" -> new InetAddress[] {InetAddress.ofLiteral("10.9.0.5")};
        case "hooks.test" -> new InetAddress[] {InetAddress.ofLiteral("10.9.0.6")};
        default -> EgressPolicy.SYSTEM.resolve(h);
    };

    /** JDK HttpClient, redirects NEVER, over plain http to the loopback stub on the URL's port; records the checked address. */
    static final class LoopbackWire implements WebhookSinkTransport {
        private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        final List<String> addresses = new CopyOnWriteArrayList<>();

        @Override public void post(URI url, String t, Duration d, String b, Map<String, String> h) {
            throw new UnsupportedOperationException();
        }

        @Override public Response exchange(String method, URI url, InetAddress to, String token, Duration timeout, String json,
                                           Map<String, String> headers, int cap) throws Exception {
            addresses.add(to.getHostAddress());
            URI pinned = new URI("http", null, "127.0.0.1", url.getPort(), url.getPath(), null, null);
            HttpRequest.Builder b = HttpRequest.newBuilder(pinned).timeout(timeout).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
            if (token != null) b.header("Authorization", "Bearer " + token);
            headers.forEach(b::header);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String body = r.body() == null ? "" : r.body();
            return new Response(r.statusCode(), body.length() > cap ? body.substring(0, cap) : body);
        }
    }

    private record Hit(Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                    .map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private HttpServer stub;
    private final List<Hit> caseHits = new CopyOnWriteArrayList<>(), hookHits = new CopyOnWriteArrayList<>();
    private volatile int caseStatus = 201;
    private volatile String caseAnswer = "{\"case\":{\"id\":\"CM-42\",\"url\":\"https://cases.test/CM-42\"}}";
    private Supplier<WebhookSinkTransport> priorTransport;
    private EgressPolicy.Resolver priorResolver;
    private java.util.concurrent.Executor priorExecutor;

    @BeforeEach
    void start() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/cases", ex -> {
            caseHits.add(new Hit(Map.copyOf(ex.getRequestHeaders()), new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = caseAnswer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(caseStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        stub.createContext("/hooks", ex -> {
            hookHits.add(new Hit(Map.copyOf(ex.getRequestHeaders()), new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        stub.start();
        priorTransport = HostOutboundPort.transport;
        priorResolver = HostOutboundPort.resolver;
        priorExecutor = IntegrationWebhooks.executor;
        HostOutboundPort.transport = LoopbackWire::new;
        HostOutboundPort.resolver = NET;
        HostOutboundPort.sleeper = ms -> { };
        IntegrationWebhooks.executor = Runnable::run;
        System.setProperty(TOKEN_PROP, "cm-api-token");
        System.setProperty(SECRET_PROP, SECRET);
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case REVEALER -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canRevealLinkEntities")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister")));
            case NOCAP -> Optional.of(new Subject("analyst-1", Set.of()));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void stop() {
        Authenticators.forTest(null);
        HostOutboundPort.transport = priorTransport;
        HostOutboundPort.resolver = priorResolver;
        HostOutboundPort.sleeper = null;
        IntegrationWebhooks.executor = priorExecutor;
        ConnectionRegistry.clear();
        System.clearProperty(TOKEN_PROP);
        System.clearProperty(SECRET_PROP);
        stub.stop(0);
    }

    private Ctx open(Path cfg, Path writeRoot, String maskingMode) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                        "SELECT caller, callee, channel FROM (VALUES ('alice','bob','voice'),('alice','carol','sms'),"
                                + "('bob','dave','voice')) AS t(caller,callee,channel)", "2026-10-03T00:00:00Z"));
                new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
                Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: " + maskingMode + "\n");
            }
            int port = stub.getAddress().getPort();
            ConnectionRegistry.register(new ConnectionProfile("cm", "https", "cases.test", port, null, "/api/cases", null,
                    "${SYS:" + TOKEN_PROP + "}", Map.of(), null));
            ConnectionRegistry.register(new ConnectionProfile("hub", "https", "hooks.test", port, null, "/hooks", null,
                    null, Map.of(), null));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void allow(Ctx c, String... hosts) throws Exception {
        ok(c, "PUT", "/settings/egress", JSON.writeValueAsString(Map.of("allow", List.of(hosts))), ADMIN);
    }

    private static String handoffSettings(String extra) {
        return "{\"handoff\":{\"connection\":\"cm\",\"system\":\"case-manager\",\"authHeader\":\"X-Api-Key\","
                + "\"caseIdPointer\":\"/case/id\",\"caseUrlPointer\":\"/case/url\"" + extra + "},"
                + "\"retry\":{\"count\":2,\"initialDelayMillis\":1,\"maxDelayMillis\":1}}";
    }

    private void investigation(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"alice\"]}", OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", OWNER);
    }

    // ── settings: GET / PUT /inv/integration ───────────────────────────────────────────────────────────

    @Test
    void settingsAreAdminOnlyValidatedFailClosedAndNeverHoldASecret(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "none")) {
            assertEquals(403, send(c, "GET", "/inv/integration", null, OWNER).statusCode(), "reading is administration");
            assertEquals(403, send(c, "PUT", "/inv/integration", handoffSettings(""), OWNER).statusCode());
            assertFalse(ok(c, "GET", "/inv/integration", null, ADMIN).get("handoffConfigured").asBoolean());

            HttpResponse<String> literal = send(c, "PUT", "/inv/integration",
                    "{\"webhooks\":{\"connection\":\"hub\",\"signingSecret\":\"plain-text\",\"events\":[\"investigation.created\"]}}", ADMIN);
            assertEquals(422, literal.statusCode(), literal.body());
            assertTrue(literal.body().contains("literal secret"), literal.body());
            assertEquals(422, send(c, "PUT", "/inv/integration",
                    handoffSettings(",\"template\":{\"x\":\"{{investigation.secretField}}\"}"), ADMIN).statusCode(), "unknown placeholder");
            assertEquals(422, send(c, "PUT", "/inv/integration", "{\"handoff\":{\"connection\":\"cm\",\"authHeader\":\"Host\"}}", ADMIN)
                    .statusCode(), "a header the transport sets");
            assertEquals(422, send(c, "PUT", "/inv/integration", "{\"handoff\":{\"connection\":\"cm\"},\"extra\":1}", ADMIN).statusCode());
            assertFalse(Files.exists(root.resolve("la-integration.json")), "a refused write writes nothing");

            JsonNode put = ok(c, "PUT", "/inv/integration", handoffSettings(""), ADMIN);
            assertTrue(put.get("handoffConfigured").asBoolean());
            assertEquals("cm", put.at("/settings/handoff/connection").asText());
            assertEquals("/case/id", put.at("/settings/handoff/caseIdPointer").asText());
        }
    }

    @Test
    void settingsWriteWithoutAWriteRootIs503(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null, "none")) {
            assertEquals(503, send(c, "PUT", "/inv/integration", handoffSettings(""), ADMIN).statusCode());
        }
    }

    // ── case hand-off ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSuccessfulHandoffSendsTheBundleAndLinksTheReturnedCaseId(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            allow(c, "cases.test");
            ok(c, "PUT", "/inv/integration", handoffSettings(",\"template\":{\"title\":\"LA {{investigation.id}}\","
                    + "\"evidence\":\"{{bundle}}\",\"masking\":\"{{masking}}\"}"), ADMIN);
            List<Event> events = new CopyOnWriteArrayList<>();
            Consumer<Event> listen = events::add;
            c.svc().eventLog().addSubscriber(listen);
            try {
                assertEquals(403, send(c, "POST", INV + "/handoff", "{}", NOCAP).statusCode(), "canManageIncidents");
                JsonNode out = ok(c, "POST", INV + "/handoff", "{}", OWNER);
                assertEquals("CM-42", out.get("externalCaseId").asText());
                assertEquals("added", out.get("linked").asText());
                assertEquals(1, out.get("attempts").asInt());

                assertEquals(1, caseHits.size());
                Hit hit = caseHits.get(0);
                assertEquals("cm-api-token", hit.header("X-Api-Key"), "the Connection's token, from its secret reference");
                assertEquals(null, hit.header("Authorization"), "authHeader replaces the bearer header");
                assertTrue(hit.header("Idempotency-Key").startsWith("handoff:inv-a:"));
                JsonNode sent = JSON.readTree(hit.body());
                assertEquals("LA inv-a", sent.get("title").asText(), "the field-mapping template");
                assertEquals("inspecto-dossier-bundle/1", sent.at("/evidence/format").asText(), "an exact placeholder keeps its object");
                assertEquals("none", sent.at("/masking/mode").asText());
                assertTrue(sent.at("/masking/note").asText().contains("masking mode"), "the payload says how masking applied");

                JsonNode refs = ok(c, "GET", INV + "/references", null, OWNER);
                JsonNode ref = refs.at("/references/0");
                assertEquals("case-manager", ref.get("system").asText());
                assertEquals("case", ref.get("type").asText());
                assertEquals("CM-42", ref.get("id").asText());
                assertEquals("https://cases.test/CM-42", ref.get("url").asText());
                assertTrue(events.stream().anyMatch(e -> LinkEventTypes.LINK_INVESTIGATION_HANDED_OFF.equals(e.type())
                        && "CM-42".equals(e.attributes().get("externalCaseId"))), "audited");

                JsonNode again = ok(c, "POST", INV + "/handoff", "{}", OWNER);
                assertEquals("already linked", again.get("linked").asText(), "the same case id is linked once");
            } finally {
                c.svc().eventLog().removeSubscriber(listen);
            }
        }
    }

    @Test
    void aServerErrorIsRetriedThenFailsVisiblyAndLinksNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            allow(c, "cases.test");
            ok(c, "PUT", "/inv/integration", handoffSettings(""), ADMIN);
            caseStatus = 503;
            List<Event> events = new CopyOnWriteArrayList<>();
            Consumer<Event> listen = events::add;
            c.svc().eventLog().addSubscriber(listen);
            try {
                HttpResponse<String> r = send(c, "POST", INV + "/handoff", "{}", OWNER);
                assertEquals(502, r.statusCode(), r.body());
                assertTrue(r.body().contains("HTTP 503 after 3 attempt(s)"), r.body());
                assertEquals(3, caseHits.size(), "the first attempt and two retries");
                assertEquals(1, caseHits.stream().map(h -> h.header("Idempotency-Key")).distinct().count(),
                        "one idempotency key across every retry");
                assertEquals(0, ok(c, "GET", INV + "/references", null, OWNER).get("count").asInt());
                assertTrue(events.stream().anyMatch(e -> LinkEventTypes.LINK_INVESTIGATION_HANDOFF_FAILED.equals(e.type())));
            } finally {
                c.svc().eventLog().removeSubscriber(listen);
            }

            caseStatus = 400;
            caseHits.clear();
            assertEquals(502, send(c, "POST", INV + "/handoff", "{}", OWNER).statusCode());
            assertEquals(1, caseHits.size(), "a 4xx is not retried");

            caseStatus = 201;
            caseAnswer = "{\"nothing\":true}";
            HttpResponse<String> noId = send(c, "POST", INV + "/handoff", "{}", OWNER);
            assertEquals(502, noId.statusCode());
            assertTrue(noId.body().contains("no usable case id"), noId.body());
        }
    }

    @Test
    void anEgressDeniedTargetIsRefusedWithNothingSent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c);
            assertEquals(409, send(c, "POST", INV + "/handoff", "{}", OWNER).statusCode(), "nothing configured");
            ok(c, "PUT", "/inv/integration", handoffSettings(""), ADMIN);   // cases.test (a private address) is NOT allowlisted
            HttpResponse<String> r = send(c, "POST", INV + "/handoff", "{}", OWNER);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("egress refused"), r.body());
            assertEquals(0, caseHits.size(), "nothing was dialled");

            allow(c, "cases.test");
            ConnectionRegistry.register(new ConnectionProfile("cm", "https", "cases.test", stub.getAddress().getPort(), null,
                    "/api/cases", null, "literal-token", Map.of(), null));
            HttpResponse<String> literal = send(c, "POST", INV + "/handoff", "{}", OWNER);
            assertEquals(422, literal.statusCode(), literal.body());
            assertTrue(literal.body().contains("literal token"), literal.body());
            assertEquals(0, caseHits.size());
        }
    }

    @Test
    void whatLeavesFollowsTheSpacesMaskingUnlessTheCallerHoldsReveal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "all")) {
            investigation(c);
            allow(c, "cases.test");
            ok(c, "PUT", "/inv/integration", handoffSettings(""), ADMIN);

            JsonNode masked = ok(c, "POST", INV + "/handoff", "{}", OWNER);
            assertEquals("all", masked.at("/masking/mode").asText());
            String sent = caseHits.get(0).body();
            assertFalse(sent.contains("\"alice\""), "a masked Space never sends a raw id");
            assertTrue(sent.contains("masked:"), "pseudonyms leave instead");
            assertEquals("all", JSON.readTree(sent).at("/masking/mode").asText(), "the payload says so");

            assertEquals(403, send(c, "POST", INV + "/handoff", "{\"reveal\":true}", OWNER).statusCode(),
                    "reveal needs canRevealLinkEntities");
            JsonNode revealed = ok(c, "POST", INV + "/handoff", "{\"reveal\":true}", REVEALER);
            assertEquals("revealed", revealed.at("/masking/mode").asText());
            String raw = caseHits.get(1).body();
            assertTrue(raw.contains("\"alice\""), "a holder of reveal hands off raw ids");
            assertEquals("revealed", JSON.readTree(raw).at("/masking/mode").asText());
        }
    }

    // ── webhooks ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSubscribedEventIsSentSignedAndAudited(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "none")) {
            allow(c, "hooks.test");
            ok(c, "PUT", "/inv/integration", "{\"webhooks\":{\"connection\":\"hub\",\"signingSecret\":\"${SYS:" + SECRET_PROP
                    + "}\",\"events\":[\"investigation.created\"]},\"retry\":{\"count\":0}}", ADMIN);
            List<Event> events = new CopyOnWriteArrayList<>();
            Consumer<Event> listen = events::add;
            c.svc().eventLog().addSubscriber(listen);
            try {
                investigation(c);
            } finally {
                c.svc().eventLog().removeSubscriber(listen);
            }
            assertEquals(1, hookHits.size(), "only the subscribed event: " + hookHits);
            Hit hit = hookHits.get(0);
            String ts = hit.header("X-Inspecto-Timestamp");
            String expected = "sha256=" + IntegrationWebhooks.sign(SECRET, ts, hit.body());
            assertEquals(expected, hit.header("X-Inspecto-Signature"), "the signature verifies under the shared secret");
            assertNotEquals(expected, "sha256=" + IntegrationWebhooks.sign(SECRET, ts, hit.body().replace("inv-a", "inv-b")),
                    "an edited body does not verify");
            JsonNode body = JSON.readTree(hit.body());
            assertEquals("investigation.created", body.get("event").asText());
            assertEquals("inv-a", body.get("investigationId").asText());
            assertEquals(body.get("id").asText(), hit.header("X-Inspecto-Delivery-Id"));
            assertEquals(body.get("id").asText(), hit.header("Idempotency-Key"));
            assertTrue(body.get("masking").asText().contains("no entity ids"));
            assertFalse(hit.body().contains("alice"), "a webhook carries no entity id");
            assertTrue(events.stream().anyMatch(e -> LinkEventTypes.LINK_WEBHOOK_DELIVERED.equals(e.type())
                    && "investigation.created".equals(e.attributes().get("event"))), "audited");
        }
    }

    @Test
    void anUnresolvableSigningSecretSendsNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "none")) {
            allow(c, "hooks.test");
            ok(c, "PUT", "/inv/integration", "{\"webhooks\":{\"connection\":\"hub\",\"signingSecret\":\"${SYS:la.test.absent}\","
                    + "\"events\":[\"investigation.created\"]}}", ADMIN);
            List<Event> events = new CopyOnWriteArrayList<>();
            Consumer<Event> listen = events::add;
            c.svc().eventLog().addSubscriber(listen);
            try {
                ok(c, "POST", "/inv/investigations", CREATE, OWNER);
            } finally {
                c.svc().eventLog().removeSubscriber(listen);
            }
            assertEquals(0, hookHits.size(), "a webhook is never sent unsigned");
            assertTrue(events.stream().anyMatch(e -> LinkEventTypes.LINK_WEBHOOK_FAILED.equals(e.type())));
        }
    }
}
