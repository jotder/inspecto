package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.Idempotency;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.metrics.MetricRegistry;
import com.gamma.pipeline.exec.WebhookSinkTransport;
import com.gamma.service.SpaceManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ASSURE-ACTION-REQUESTS-RESIDUALS-1} (2), over real HTTP with an ARMED Authenticator: a new Space created from
 * a bundle ({@code POST /spaces/import}) carrying a Decision Rule with an {@code invoke-api} consequence gets the same
 * treatment as one saved through {@code /decision-rules} — the import needs {@code canWorkIncidents}, the bundle
 * stamps become the importer, the importer AND the bundle's recorded makers are co-authors four-eyes refuses, and the NEW
 * Space's empty Egress Allowlist denies the target until an administrator of that Space lifts it.
 *
 * <p>The dispatcher runs inline over {@link ActionDispatcherTest.LoopbackWire} behind a scheme rewrite, as in
 * {@link ControlApiActionRequestsTest}; {@code tickets.test} resolves to a private address.
 */
class ControlApiSpaceBundleActionRequestsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMPORTER = "Bearer importer", NO_INCIDENTS = "Bearer no-incidents",
            OPERATOR = "Bearer operator", CHECKER = "Bearer checker", ORIGINAL = "Bearer original",
            RESTORED = "Bearer restored";
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpServer target;
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private Executor priorExecutor;
    private Supplier<WebhookSinkTransport> priorTransport;
    private com.gamma.util.egress.EgressPolicy.Resolver priorResolver;

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @BeforeEach
    void arm() throws Exception {
        ClassLoader fakeObjects = FakeObjectEngineProvider.fakeObjectEngineClassLoader(
                Thread.currentThread().getContextClassLoader());
        // A Space created through POST /spaces/import boots ON the request thread, so the fake object engine must
        // be discoverable there: every request thread gets the fake engine's classloader before its route runs.
        Authenticators.forTest(ex -> {
            Thread.currentThread().setContextClassLoader(fakeObjects);
            return subject(ex);
        });
        System.setProperty(ActionDispatcher.PROP_BACKOFF_MS, "0");
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/api", ex -> {
            keys.add(String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
            ex.getRequestBody().readAllBytes();
            byte[] out = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        target.start();
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

    private static Optional<Subject> subject(com.sun.net.httpserver.HttpExchange ex) {
        return switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case IMPORTER -> Optional.of(new Subject("importer-1", Set.of("canAdminister", "canOnboardConnections",
                    "canAuthorWorkbench", "canWorkIncidents", "canApproveChanges")));
            case NO_INCIDENTS -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canOnboardConnections",
                    "canAuthorWorkbench")));
            case OPERATOR -> Optional.of(new Subject("operator-1", Set.of("canOperateRuns", "canWorkIncidents")));
            case CHECKER -> Optional.of(new Subject("checker-1", Set.of("canAdminister", "canWorkIncidents",
                    "canApproveChanges")));
            case ORIGINAL -> Optional.of(new Subject("author-9", Set.of("canWorkIncidents", "canApproveChanges")));
            case RESTORED -> Optional.of(new Subject("author-0", Set.of("canWorkIncidents", "canApproveChanges")));
            default -> Optional.empty();
        };
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        ActionDispatcher.executor = priorExecutor;
        ActionDispatcher.transport = priorTransport;
        ActionDispatcher.resolver = priorResolver;
        com.gamma.acquire.ConnectionRegistry.clear();
        System.clearProperty(ActionDispatcher.PROP_BACKOFF_MS);
        target.stop(0);
    }

    /** One hosted Space ({@code alpha}); {@code beta} is created by the test. */
    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("alpha");
        Path config = base.resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(base.resolve("data").resolve("etl"), PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Files.writeString(base.resolve("space.toon"), "display_name: \"Alpha\"\ndescription: \"x\"\ncreated_at: \"2026-09-28\"\n");
        SpaceManager spaces = SpaceManager.discover(root);
        seedApproverRoster(config);   // OIDC-shaped Authenticator: the Space's approver roster decides
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    /** A new-Space bundle: an https Connection to the stub target and an invoke-api rule with its recorded makers. */
    private byte[] bundle() throws Exception {
        return bundle(List.of("author-0"));
    }

    private byte[] bundle(List<String> restoredMakers) throws Exception {
        return bundle(Map.of("name", "leak", "enabled", true,
                "createdBy", "author-9", "updatedBy", "author-9", "restoredMakers", restoredMakers,
                "consequences", List.of(Map.of("action", "invoke-api", "params", Map.of("connection", "hook")))));
    }

    private byte[] bundle(Map<String, Object> rule) throws Exception {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("bundle.toon", "kind: datasource\n");
        all.put("connections/hook_connection.toon", "connection:\n  id: hook\n  connector: https\n  host: tickets.test\n"
                + "  port: " + target.getAddress().getPort() + "\n  base_path: api\n");
        all.put("registry/decision-rules/leak.toon", ConfigCodec.toToon(rule));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : all.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private HttpResponse<String> importSpace(Ctx c, byte[] zip, String auth) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/import?id=beta"))
                .header("Authorization", auth).POST(BodyPublishers.ofByteArray(zip)).build(), BodyHandlers.ofString());
    }

    /** A request against the NEW Space. */
    private HttpResponse<String> beta(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/beta" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void aBundleCreatedSpacesInvokeApiRuleIsUnderFourEyesAndItsOwnEgressPolicy(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] zip = bundle();
            HttpResponse<String> refused = importSpace(c, zip, NO_INCIDENTS);
            assertEquals(403, refused.statusCode(), "an invoke-api rule needs canWorkIncidents: " + refused.body());
            assertTrue(Files.notExists(root.resolve("beta")), "no Space directory is created");
            data(importSpace(c, zip, IMPORTER), 200);

            JsonNode stored = data(beta(c, "GET", "/components/decision-rule/leak", null, IMPORTER), 200);
            assertEquals("importer-1", stored.at("/content/updatedBy").asText(), stored.toString());
            assertEquals("importer-1", stored.at("/content/createdBy").asText(), "the stamps are the importer's");

            JsonNode applied = data(beta(c, "POST", "/decision-rules/leak/apply", "{}", OPERATOR), 200).at("/executed/0");
            String id = applied.path("actionRequestId").asText();
            assertFalse(id.isBlank(), "the bundle-created rule raised an Action Request: " + applied);
            JsonNode rec = data(beta(c, "GET", "/action-requests/" + id, null, CHECKER), 200);
            assertEquals(List.of("importer-1", "author-9", "author-0"), JSON.convertValue(rec.get("coAuthors"), List.class), rec.toString());
            assertEquals("pending", rec.get("status").asText());

            HttpResponse<String> maker = beta(c, "POST", "/action-requests/" + id + "/approve", "{}", IMPORTER);
            assertEquals(403, maker.statusCode(), "the importer made the rule: " + maker.body());
            assertEquals(403, beta(c, "POST", "/action-requests/" + id + "/decline", "{}", IMPORTER).statusCode());
            // No history travels with a bundle: the rule's recorded makers stay co-authors in the new Space.
            assertEquals(403, beta(c, "POST", "/action-requests/" + id + "/approve", "{}", ORIGINAL).statusCode(),
                    "the bundled rule's author");
            assertEquals(403, beta(c, "POST", "/action-requests/" + id + "/approve", "{}", RESTORED).statusCode(),
                    "the bundled rule's restored-version author");

            // A new Space starts with an EMPTY approver roster: nobody may approve until an administrator lists them.
            assertEquals(403, beta(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER).statusCode(),
                    "the bundle carries no roster (approvers.toon is reserved from import)");
            data(beta(c, "PUT", "/settings/approvers", "{\"users\":[\"checker-1\"]}", CHECKER), 200);

            // A new Space starts with an EMPTY allowlist: the private target is denied, nothing dialled.
            JsonNode done = data(beta(c, "POST", "/action-requests/" + id + "/approve", "{}", CHECKER), 200);
            assertEquals("failed", done.get("status").asText(), done.toString());
            assertTrue(done.at("/lastResponse/error").asText().contains("egress refused"), done.toString());
            assertTrue(keys.isEmpty(), "deny by default in the new Space: nothing sent");

            // That Space's own allowlist is what lifts it — then the approved call goes out once.
            data(beta(c, "PUT", "/settings/egress", "{\"allow\":[\"tickets.test\"]}", CHECKER), 200);
            assertEquals("succeeded", data(beta(c, "POST", "/action-requests/" + id + "/retry", "{}", CHECKER), 200)
                    .get("status").asText());
            assertEquals(List.of(id), keys);
        }
    }

    /** restoredMakers is copied forward into every later version, so an imported one is bounded: over the cap is 422. */
    @Test
    void aBundledRestoredMakersListOverTheCapIsRefused(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            List<String> tooMany = java.util.stream.IntStream.range(0, DecisionRuleGuard.MAX_MAKERS)
                    .mapToObj(i -> "maker-" + i).toList();   // + the bundled updatedBy = one over
            HttpResponse<String> many = importSpace(c, bundle(tooMany), IMPORTER);
            assertEquals(422, many.statusCode(), many.body());
            assertTrue(many.body().contains("more than " + DecisionRuleGuard.MAX_MAKERS), many.body());
            HttpResponse<String> longId = importSpace(c, bundle(List.of("x".repeat(DecisionRuleGuard.MAX_MAKER_ID + 1))),
                    IMPORTER);
            assertEquals(422, longId.statusCode(), longId.body());
            assertTrue(Files.notExists(root.resolve("beta")), "no Space directory is created");
            data(importSpace(c, bundle(tooMany.subList(1, tooMany.size())), IMPORTER), 200);   // exactly the cap
        }
    }

    /**
     * RESIDUALS-1 (3) option C: a rule exported from a Space whose history was pruned carries its makers only as the
     * stamped {@code makers}; a new Space built from it keeps them (an imported value can only ADD makers) and stamps
     * them on the rule, so no later pruning in the new Space erases them.
     */
    @Test
    void aBundledRulesStampedMakersStayMakersInTheNewSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            data(importSpace(c, bundle(Map.of("name", "leak", "enabled", true, "updatedBy", "author-9",
                    "makers", List.of("author-9", "author-0"),
                    "consequences", List.of(Map.of("action", "invoke-api", "params", Map.of("connection", "hook"))))),
                    IMPORTER), 200);
            JsonNode stored = data(beta(c, "GET", "/components/decision-rule/leak", null, IMPORTER), 200);
            assertEquals(List.of("importer-1", "author-9", "author-0"),
                    JSON.convertValue(stored.at("/content/makers"), List.class), stored.toString());
            String id = data(beta(c, "POST", "/decision-rules/leak/apply", "{}", OPERATOR), 200)
                    .at("/executed/0/actionRequestId").asText();
            assertEquals(403, beta(c, "POST", "/action-requests/" + id + "/approve", "{}", RESTORED).statusCode(),
                    "the bundled stamp's maker cannot approve");
            HttpResponse<String> bad = importSpace(c, bundle(Map.of("name", "leak", "makers", List.of(""),
                    "consequences", List.of(Map.of("action", "invoke-api", "params", Map.of("connection", "hook"))))),
                    IMPORTER);
            assertEquals(422, bad.statusCode(), "a malformed bundled stamp is refused like restoredMakers: " + bad.body());
        }
    }

    /** The Space's approver roster ({@link ApproverRoster}): every id this class's Authenticator mints. */
    private static void seedApproverRoster(Path root) throws java.io.IOException {
        java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                java.util.Map.of("users", java.util.List.of("admin-1", "author-0", "author-9", "checker-1", "importer-1", "operator-1"))));
    }
}
