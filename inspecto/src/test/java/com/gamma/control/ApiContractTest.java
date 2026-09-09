package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@code docs/api/openapi-v1.json} (worklog W2,
 * docs/superpower/api-contract-design.md §9 "offline testability"): the OpenAPI document, the
 * canonical examples under {@code docs/api/examples/}, the {@link ErrorCodes} catalog and the live
 * {@code /api/v1} surface must all agree — this class fails when any of them drifts.
 */
class ApiContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** OpenAPI operation keys, so a path's `parameters`/`summary` siblings are not counted as operations. */
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /**
     * The documented-surface RATCHET — measured 2026-09-09 at 19 paths / 24 operations. ⚠ These are floors
     * to be RAISED as more is documented, never lowered to make a red build green: their whole job is that
     * a documented operation cannot quietly disappear. See
     * {@link #openApiCoverageOfTheLiveSurfaceIsMeasuredAndRatcheted}.
     */
    private static final int MIN_DOCUMENTED_PATHS = 19;
    private static final int MIN_DOCUMENTED_OPERATIONS = 24;

    /**
     * Emptiness floor for the live route table. Measured 2026-09-09 on the default reactor: **266**
     * registrations plus 73 absent-module stubs. Set well below that on purpose, twice over — route
     * modules arrive by {@code ServiceLoader}, so the real number moves with the {@code -Pedition-*}
     * profile, and a floor sitting a few percent under one profile's count would go red the first time a
     * module was legitimately retired. ⚠ This floor answers only "did the measurement see the table at
     * all"; it is not a coverage target, and raising it toward the real count would make it a brittle pin.
     */
    private static final int MIN_LIVE_ROUTES = 200;

    /** Example file → the component schema it must satisfy (README authoring step 3). */
    private static final Map<String, String> EXAMPLES = Map.of(
            "envelope-health.json", "Envelope",
            "error-not-found.json", "ErrorResponse",
            "error-write-root-503.json", "ErrorResponse",
            "error-validation-422.json", "ErrorResponse",
            "error-stale-version-409.json", "ErrorResponse",
            "signal.json", "Signal");

    // ── contract loading ─────────────────────────────────────────────────────────

    /** The repo's docs/api dir, found by walking up from the module CWD (works from repo root too). */
    private static Path docsApi() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs").resolve("api");
            if (Files.isRegularFile(candidate.resolve("openapi-v1.json"))) return candidate;
        }
        throw new IllegalStateException("docs/api/openapi-v1.json not found above " + Path.of("").toAbsolutePath());
    }

    private static JsonNode contract() throws Exception {
        return JSON.readTree(docsApi().resolve("openapi-v1.json").toFile());
    }

    // ── doc ↔ live serving (HARD-4) ─────────────────────────────────────────────

    /**
     * The runtime {@code GET /api/v1/openapi.json} must serve the contract byte-for-byte —
     * a consumer that validates against the served document sees exactly what the repo tests.
     * Skipped when the docs tree is absent from the checkout (deploy-only environments).
     */
    @Test
    void serverServesTheContractByteForByte(@TempDir Path cfg) throws Exception {
        if (!Files.isRegularFile(docsApi().resolve("openapi-v1.json"))) {
            return;   // no contract on disk → nothing to compare against
        }
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.clearProperty("assist.write.root");
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
             ControlApi api = new ControlApi(svc, 0)) {
            api.start();
            int port = api.port();
            HttpResponse<byte[]> res = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/openapi.json")).build(),
                    BodyHandlers.ofByteArray());
            assertEquals(200, res.statusCode());
            assertArrayEquals(Files.readAllBytes(docsApi().resolve("openapi-v1.json")), res.body(),
                    "served /api/v1/openapi.json must be byte-equal to docs/api/openapi-v1.json");
        }
    }

    // ── doc ↔ code ───────────────────────────────────────────────────────────────

    @Test
    void errorCodeCatalogMatchesContractEnum() throws Exception {
        Set<String> code = new TreeSet<>();
        for (Field f : ErrorCodes.class.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                code.add((String) f.get(null));

        Set<String> doc = new TreeSet<>();
        contract().path("components").path("schemas").path("ErrorCode").path("enum")
                .forEach(n -> doc.add(n.asText()));

        assertEquals(code, doc, "ErrorCodes.java and the contract's ErrorCode enum must stay in lockstep");
    }

    // ── doc ↔ examples ───────────────────────────────────────────────────────────

    @Test
    void examplesSatisfyTheirDeclaredSchemas() throws Exception {
        JsonNode contract = contract();
        Path examples = docsApi().resolve("examples");
        for (Map.Entry<String, String> e : EXAMPLES.entrySet()) {
            JsonNode instance = JSON.readTree(examples.resolve(e.getKey()).toFile());
            assertSatisfies(contract, e.getValue(), instance, e.getKey());
        }
        // Every example file present is registered (a new shape must join the manifest above).
        try (var files = Files.list(examples)) {
            Set<String> onDisk = new HashSet<>();
            files.forEach(p -> onDisk.add(p.getFileName().toString()));
            assertEquals(EXAMPLES.keySet(), onDisk, "examples/ and the EXAMPLES manifest must match");
        }
    }

    // ── doc ↔ live surface ───────────────────────────────────────────────────────

    @Test
    void probedOperationsMatchLiveServer(@TempDir Path cfg) throws Exception {
        JsonNode contract = contract();
        List<String> probed = new ArrayList<>();
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.clearProperty("assist.write.root");
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
             ControlApi api = new ControlApi(svc, 0)) {
            api.start();
            var paths = contract.path("paths");
            for (var pathIt = paths.fields(); pathIt.hasNext(); ) {
                var pathEntry = pathIt.next();
                for (var opIt = pathEntry.getValue().fields(); opIt.hasNext(); ) {
                    var opEntry = opIt.next();
                    JsonNode probe = opEntry.getValue().get("x-probe");
                    if (probe == null) continue;
                    assertEquals("get", opEntry.getKey(), "x-probe is only supported on GET operations");

                    String probePath = probe.get("path").asText();
                    int expected = probe.get("status").asInt();
                    // x-probe paths are absolute and already carry the /api/v1 prefix — see the contract's
                    // description. Do not prefix again.
                    HttpResponse<String> r = client.send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + probePath))
                                    .GET().build(),
                            BodyHandlers.ofString());
                    assertEquals(expected, r.statusCode(),
                            "probe " + probePath + " (documented on " + pathEntry.getKey() + ")");

                    // Envelope/ErrorResponse are the *transport* shapes, so check the un-peeled body.
                    JsonNode body = V1Body.envelope(r.body());
                    assertSatisfies(contract, expected < 400 ? "Envelope" : "ErrorResponse", body,
                            "live " + probePath);
                    probed.add(probePath);
                }
            }
        }
        assertFalse(probed.isEmpty(), "the contract should declare at least one x-probe");
    }

    /**
     * How much of the live surface the contract documents — <b>measured, floored, and PRINTED</b>.
     *
     * <p><b>The gap this closes.</b> Every other assertion in this class runs doc → live: it checks that
     * what the contract says is true. Nothing ran live → doc, so the contract documenting **19 paths of
     * roughly 332 registrations** was invisible to the very test suite named as its enforcement. A gateway
     * or an external consumer reading `openapi-v1.json` sees about 6 % of the surface, and "OpenAPI-first"
     * is true only of what was written after W2.
     *
     * <p>⛔ <b>This test does not decide whether that is acceptable.</b> Documenting 300-odd routes and
     * keeping deliberate exemplar coverage are both defensible, and the choice is the operator's — it is
     * recorded as owed in {@code okf/capabilities/control-api/control-api.md} §5. What is NOT defensible
     * is the number being unknown, so this measures it, prints it on every run, and ratchets it.
     *
     * <p>⚠ <b>Why the assertions are floors and not equalities.</b> A per-route live → doc comparison is
     * not available: {@code registeredRoutes} holds compiled patterns ({@code /config/pipeline/([^/]+)})
     * while OpenAPI holds templates ({@code /config/pipeline/{name}}), with no translation between them.
     * So the documented counts are a ratchet to be RAISED when more is documented, and the live count
     * carries an emptiness floor — this repo's recurring failure is a measurement that quietly covered
     * nothing, and a floor a few percent under the real count would instead go red the first time a
     * module was legitimately retired.
     *
     * <p>🔴 <b>What this measures is the CORE surface, and that is structural, not a profile setting.</b>
     * The first draft of this javadoc said the live count was edition-dependent because route modules
     * arrive by {@code ServiceLoader}. Measured under {@code -Pedition-enterprise}: <b>266, identical to
     * the default reactor.</b> The reason is the dependency direction — every optional module
     * ({@code inspecto-ops}, {@code inspecto-notify-channels}, {@code inspecto-geo-link}) depends on
     * {@code inspecto-processor} and the core declares none of them, so the arrow points module → core and
     * a test living IN the core can never have one on its classpath, whatever profile built the reactor.
     * The 73 absent-module stubs are precisely those modules answering 503. It is the same structural fact
     * {@code MaintenanceTaskContractTest} records for maintenance-task providers.
     *
     * <p>⚠ <b>So the printed percentage is an UPPER BOUND on the shipped Enterprise surface.</b> A full
     * Enterprise bundle registers these 266 plus whatever the optional modules contribute, against the
     * same 24 documented operations — real coverage there is lower than the figure this test prints. Do
     * not quote it as the Enterprise number.
     */
    @Test
    void openApiCoverageOfTheLiveSurfaceIsMeasuredAndRatcheted(@TempDir Path cfg) throws Exception {
        JsonNode contract = contract();
        int documentedPaths = 0;
        int documentedOperations = 0;
        for (var it = contract.path("paths").fields(); it.hasNext(); ) {
            var entry = it.next();
            documentedPaths++;
            for (var opIt = entry.getValue().fieldNames(); opIt.hasNext(); )
                if (HTTP_METHODS.contains(opIt.next())) documentedOperations++;
        }

        int liveRoutes;
        int stubbed;
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.clearProperty("assist.write.root");
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
             ControlApi api = new ControlApi(svc, 0)) {
            liveRoutes = routeSetSize(api, "registeredRoutes");
            stubbed = routeSetSize(api, "stubbedRoutes");
        }

        assertTrue(liveRoutes >= MIN_LIVE_ROUTES,
                "only " + liveRoutes + " live route registration(s), below the floor of " + MIN_LIVE_ROUTES
                        + ". Either a route module failed to register or this measurement stopped seeing "
                        + "the table — fix that rather than the floor; a coverage figure over nothing is "
                        + "the bug this floor exists for");

        assertTrue(documentedPaths >= MIN_DOCUMENTED_PATHS && documentedOperations >= MIN_DOCUMENTED_OPERATIONS,
                "the contract documents " + documentedPaths + " path(s) / " + documentedOperations
                        + " operation(s), below the ratchet of " + MIN_DOCUMENTED_PATHS + " / "
                        + MIN_DOCUMENTED_OPERATIONS + ". Documented surface must never SHRINK: if an "
                        + "operation was genuinely retired, lower this floor deliberately and say why in "
                        + "control-api.md §2 in the same commit");

        // Printed pass or fail. An unstated coverage figure is an unaudited one, which is how 6% survived.
        System.out.printf(
                "ApiContractTest coverage: OpenAPI documents %d path(s) / %d operation(s) against %d live "
                        + "route registration(s) (+%d absent-module stubs) — %.1f%% of the CORE surface, "
                        + "which is an UPPER BOUND: a full Enterprise bundle adds the optional modules' "
                        + "routes against the same documented set. Exemplar coverage is a DECISION still "
                        + "owed to the operator (control-api.md §5); raise MIN_DOCUMENTED_* when you "
                        + "document more.%n",
                documentedPaths, documentedOperations, liveRoutes, stubbed,
                100.0 * documentedOperations / liveRoutes);
    }

    /** Read one of {@link ControlApi}'s private route sets. Same package, plain classpath, no module-info. */
    private static int routeSetSize(ControlApi api, String field) throws Exception {
        Field f = ControlApi.class.getDeclaredField(field);
        f.setAccessible(true);
        Object value = f.get(api);
        assertTrue(value instanceof Set, field + " is no longer a Set — re-anchor this measurement");
        return ((Set<?>) value).size();
    }

    // ── a minimal structural checker (required-tree + enums; no schema-validator dependency) ──

    /** Assert {@code instance} satisfies the named component schema's required/enum tree. */
    private static void assertSatisfies(JsonNode contract, String schemaName, JsonNode instance, String at) {
        JsonNode schema = contract.path("components").path("schemas").path(schemaName);
        assertFalse(schema.isMissingNode(), "contract has no schema '" + schemaName + "'");
        check(contract, schema, instance, at + " ~ " + schemaName, 0);
    }

    private static void check(JsonNode contract, JsonNode schema, JsonNode instance, String at, int depth) {
        if (depth > 6 || schema == null || schema.isMissingNode() || instance == null) return;
        if (schema.has("$ref")) {
            check(contract, resolve(contract, schema.get("$ref").asText()), instance, at, depth + 1);
            return;
        }
        if (schema.has("enum") && instance.isTextual()) {
            Set<String> allowed = new HashSet<>();
            schema.get("enum").forEach(n -> allowed.add(n.asText()));
            assertTrue(allowed.contains(instance.asText()),
                    at + ": '" + instance.asText() + "' is not in the documented enum " + allowed);
        }
        if (instance.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null)
                for (JsonNode r : required)
                    assertTrue(instance.has(r.asText()), at + ": missing required field '" + r.asText() + "'");
            JsonNode props = schema.get("properties");
            if (props != null)
                for (var it = props.fields(); it.hasNext(); ) {
                    var prop = it.next();
                    JsonNode child = instance.get(prop.getKey());
                    if (child != null) check(contract, prop.getValue(), child, at + "." + prop.getKey(), depth + 1);
                }
        }
    }

    /** Resolve a local {@code #/components/…} JSON pointer within the contract document. */
    private static JsonNode resolve(JsonNode contract, String ref) {
        assertTrue(ref.startsWith("#/"), "only local $refs are supported: " + ref);
        JsonNode node = contract;
        for (String seg : ref.substring(2).split("/")) node = node.path(seg);
        assertFalse(node.isMissingNode(), "dangling $ref " + ref);
        return node;
    }
}
