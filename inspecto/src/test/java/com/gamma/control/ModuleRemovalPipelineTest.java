package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.util.ToonHelper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-1 P4a} — removal semantics (plan §2.5) for the Pipeline surface: config that names a
 * capability no installed module provides. Characterisation first; every test pins what the product does
 * TODAY, and the verdicts are recorded in {@code docs/superpower/module-architecture-reorg-plan.md} §6.
 *
 * <p>The node type {@code zz.absent-module-node} is registered nowhere, standing in for a node type that a
 * removed module used to contribute.
 */
class ModuleRemovalPipelineTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String ABSENT = "zz.absent-module-node";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    /** Boot over the pipelines in {@code files}; {@code dir} is the safety + write root. */
    private Ctx open(Path dir, List<Path> files) throws Exception {
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", dir.toString());
        try {
            CollectorService svc = new CollectorService(files, 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** The mini pipeline ({@code mini_etl}) with {@code mutate} applied to its decoded config, rewritten as TOON. */
    @SuppressWarnings("unchecked")
    private Path miniWith(Path dir, java.util.function.Consumer<Map<String, Object>> mutate) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "", false);
        Map<String, Object> m = new LinkedHashMap<>(ToonHelper.load(p.toString()));
        mutate.accept(m);
        Files.writeString(p, ConfigCodec.toToon(m));
        return p;
    }

    // ── (i-a) a flat config naming a step kind no installed module provides ─────────────────────

    /**
     * VERDICT: the Pipeline loads INERT — listed with a {@code loadError} that names the missing module, never run,
     * the repair view refuses loudly (422), and the file is never rewritten by any read.
     */
    @Test
    void aStepKindNoModuleProvidesLoadsInertWithAModuleNamingDiagnostic(@TempDir Path dir) throws Exception {
        Path p = miniWith(dir, m -> {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("zzabsent", new LinkedHashMap<>(Map.of("k", "v")));
            m.put("steps", List.of(step));
        });
        byte[] before = Files.readAllBytes(p);
        try (Ctx c = open(dir, List.of(p))) {
            JsonNode listed = V1Body.of(send(c.port, "GET", "/pipelines", null).body());
            assertEquals(1, listed.size(), "the file stays LISTED: " + listed);
            String why = listed.get(0).path("loadError").path("message").asText();
            assertTrue(why.contains("zzabsent") && why.contains("module"),
                    "the diagnostic names the kind AND says a module is missing: " + why);

            HttpResponse<String> repair = send(c.port, "GET", "/pipelines/mini/graph/raw", null);
            assertEquals(422, repair.statusCode(), "no half graph is served: " + repair.body());
            assertTrue(repair.body().contains("zzabsent"), repair.body());
            assertArrayEquals(before, Files.readAllBytes(p), "no read rewrites the file");
        }
    }

    // ── (i-b) a PUT whose graph holds an unregistered node type ─────────────────────────────────

    /**
     * VERDICT: REFUSED-LOUDLY. The save is a 422 {@code UNSUPPORTED_NODE} that names the node, the type and the
     * missing module; nothing is written. The candidate dry run answers 200 and WARNS naming the type.
     */
    @Test
    void aNodeTypeNoModuleRegistersIsRefusedAtSaveNamingTheTypeAndWarnedAtDryRun(@TempDir Path dir) throws Exception {
        Path p = miniWith(dir, m -> { });
        byte[] before = Files.readAllBytes(p);
        try (Ctx c = open(dir, List.of(p))) {
            JsonNode g = V1Body.of(send(c.port, "GET", "/pipelines/mini_etl/graph/raw", null).body());
            ((ArrayNode) g.get("nodes")).addObject().put("id", "zz1").put("type", ABSENT);
            ((ArrayNode) g.get("edges")).addObject().put("from", "parse").put("rel", "data").put("to", "zz1");

            HttpResponse<String> put = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));
            assertEquals(422, put.statusCode(), put.body());
            JsonNode refusal = V1Body.envelope(put.body()).get("error").get("details").get("refusals").get(0);
            assertEquals("UNSUPPORTED_NODE", refusal.get("code").asText());
            assertEquals("zz1", refusal.get("nodeId").asText());
            String msg = refusal.get("message").asText();
            assertTrue(msg.contains(ABSENT) && msg.contains("module"), "names the type and the module: " + msg);
            assertArrayEquals(before, Files.readAllBytes(p), "a refused save writes nothing");

            ObjectNode body = M.createObjectNode();
            body.set("pipeline", g);
            body.putArray("sampleRows").addObject().put("ID", "1").put("AMT", "2.5").put("EVENT_DATE", "2026-09-24");
            HttpResponse<String> dry = send(c.port, "POST", "/pipelines/authored/mini_etl/dry-run",
                    M.writeValueAsString(body));
            assertEquals(200, dry.statusCode(), dry.body());
            String warnings = V1Body.of(dry.body()).get("warnings").toString();
            assertTrue(warnings.contains(ABSENT) && warnings.contains("NOT previewed"), warnings);
        }
    }

    // ── (i-c) unmodelled keys through GET /graph/raw -> PUT /graph ──────────────────────────────

    /**
     * VERDICT: a plain unknown top-level key is REFUSED at save by the unknown-key gate
     * ({@code ERR_UNKNOWN_CONFIG_KEY}) with nothing written — fail-closed, but it does not say a module is
     * missing (filed: MODULE-REORG-P4-1). The author-owned {@code x-} spelling the gate itself recommends is
     * PRESERVED through the save.
     */
    @Test
    void anUnknownKeyIsRefusedAtSaveButAnXKeySurvivesIt(@TempDir Path dir) throws Exception {
        Path p = miniWith(dir, m -> m.put("zz_module", new LinkedHashMap<>(Map.of("a", "1"))));
        byte[] before = Files.readAllBytes(p);
        try (Ctx c = open(dir, List.of(p))) {
            JsonNode g = V1Body.of(send(c.port, "GET", "/pipelines/mini_etl/graph/raw", null).body());
            HttpResponse<String> put = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));
            assertEquals(422, put.statusCode(), put.body());
            assertTrue(put.body().contains("ERR_UNKNOWN_CONFIG_KEY") && put.body().contains("zz_module"), put.body());
            assertArrayEquals(before, Files.readAllBytes(p), "the refused save left the file as it was");
        }
    }

    @Test
    void anAuthorOwnedXKeyIsPreservedByAGraphSave(@TempDir Path dir) throws Exception {
        Path p = miniWith(dir, m -> m.put("x-zz_module", new LinkedHashMap<>(Map.of("a", "1", "b", "two"))));
        try (Ctx c = open(dir, List.of(p))) {
            JsonNode g = V1Body.of(send(c.port, "GET", "/pipelines/mini_etl/graph/raw", null).body());
            HttpResponse<String> put = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));
            assertEquals(200, put.statusCode(), put.body());
        }
        Map<String, Object> after = ToonHelper.load(p.toString());
        assertEquals(Map.of("a", "1", "b", "two"), after.get("x-zz_module"),
                "an annotation the editor does not model must survive a save: " + after.keySet());
        assertFalse(after.isEmpty());
    }
}
