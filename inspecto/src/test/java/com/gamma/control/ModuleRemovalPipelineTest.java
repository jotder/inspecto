package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import com.gamma.pipeline.StepKindModules;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void restoreOwners() {
        StepKindModules.absentOwnersForTest(null);
    }

    /** A known-but-uninstalled module declaring {@code kinds} as {@code provides.stepKinds} (a stand-in for a class path without it). */
    private static void absentModule(String id, String kinds) {
        ModuleManifest m = ModuleManifests.parse("id: " + id + "\nbuildRole: implementation\nofferingRole: provider\n"
                + "bindingTime: boot\nprovides:\n  stepKinds[1]: " + kinds + "\n");
        StepKindModules.absentOwnersForTest(StepKindModules.ownersOf(List.of(m)));
    }

    /** A Pipeline authored with {@code frontend: asn1}: it loads on any class path - only a run needs the decoder module. */
    private Path asnPipeline(Path dir) throws Exception {
        Path seg = Files.writeString(dir.resolve("seg_rec.toon"), """
                partitionKey: TXN_DATE
                raw:
                  name: t
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID,"0",VARCHAR
                mapping:
                  canonicalName: t
                  rawName: t
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                """);
        Path p = dir.resolve("asn_pipeline.toon");
        String d = dir.toString().replace('\\', '/');
        Files.writeString(p, """
                name: ASN_ETL
                active: false
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  errors: %1$s/errors
                  quarantine: %1$s/quarantine
                  markers: %1$s/markers
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                processing:
                  threads: 2
                  file_pattern: "glob:**/*.dat"
                parsing:
                  frontend: asn1
                  asn1:
                    grammar: "CDR DEFINITIONS ::= BEGIN Record ::= SEQUENCE { id [0] IA5String } END"
                    root_type: Record
                    segments:
                      Record: %2$s
                """.formatted(d, seg.toString().replace('\\', '/')));
        return p;
    }

    private static JsonNode row(JsonNode list, String name) {
        for (JsonNode r : list) if (name.equalsIgnoreCase(r.path("name").asText())) return r;
        throw new AssertionError("no pipeline '" + name + "' in " + list);
    }

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

    // -- MODULE-REORG-P4-1: attribution to a module through provides.stepKinds ------------------

    /** The asn1 frontend maps to the step kind parser.asn1.ber of telecom-asn1: on an install without it the row says so. */
    @Test
    void anAsnPipelineOnAnInstallWithoutTheTelecomModuleNamesItOnItsListRow(@TempDir Path dir) throws Exception {
        Path p = asnPipeline(dir);
        absentModule("telecom-asn1", "parser.asn1.ber");
        try (Ctx c = open(dir, List.of(p))) {
            JsonNode r = row(V1Body.of(send(c.port, "GET", "/pipelines", null).body()), "asn_etl");
            assertTrue(r.path("loadError").isMissingNode(), "it LOADS: only a run needs the decoder: " + r);
            assertFalse(r.get("hosted").asBoolean(), r.toString());
            assertEquals("telecom-asn1", r.get("missingModule").asText(), r.toString());
            assertTrue(r.get("reason").asText().contains("provided by the module 'telecom-asn1'")
                    && r.get("reason").asText().contains("not installed"), r.toString());
        }
    }

    /** Installed (this class path carries telecom-asn1) or a plain pipeline: the row carries no module fields. */
    @Test
    void anInstalledModuleOrAnOrdinaryPipelineRowCarriesNoModuleFields(@TempDir Path dir) throws Exception {
        Path asn = asnPipeline(dir);
        Path mini = miniWith(dir, m -> { });
        absentModule("zz-pack", "zz.unrelated-kind");
        try (Ctx c = open(dir, List.of(asn, mini))) {
            JsonNode list = V1Body.of(send(c.port, "GET", "/pipelines", null).body());
            for (String n : List.of("asn_etl", "mini_etl")) {
                JsonNode r = row(list, n);
                assertTrue(r.path("hosted").isMissingNode() && r.path("missingModule").isMissingNode()
                        && r.path("reason").isMissingNode(), r.toString());
            }
        }
    }

    /** A kind NO module declares is unknown, not absent: the loadError stays and no module is invented. */
    @Test
    void aLoadErrorForAKindNobodyDeclaresNamesNoModule(@TempDir Path dir) throws Exception {
        Path p = miniWith(dir, m -> {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("zzabsent", new LinkedHashMap<>(Map.of("k", "v")));
            m.put("steps", List.of(step));
        });
        absentModule("zz-pack", "zz.unrelated-kind");
        try (Ctx c = open(dir, List.of(p))) {
            JsonNode r = V1Body.of(send(c.port, "GET", "/pipelines", null).body()).get(0);
            assertTrue(r.path("loadError").path("message").asText().contains("zzabsent"), r.toString());
            assertTrue(r.path("missingModule").isMissingNode() && r.path("hosted").isMissingNode(), r.toString());
        }
    }

    /** The same load error for a kind a known-but-uninstalled module DOES declare names that module. */
    @Test
    void aLoadErrorForAKindAnAbsentModuleDeclaresNamesThatModule(@TempDir Path dir) throws Exception {
        Path p = miniWith(dir, m -> {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("zzabsent", new LinkedHashMap<>(Map.of("k", "v")));
            m.put("steps", List.of(step));
        });
        absentModule("zz-pack", "zzabsent");
        try (Ctx c = open(dir, List.of(p))) {
            JsonNode r = V1Body.of(send(c.port, "GET", "/pipelines", null).body()).get(0);
            assertEquals("zz-pack", r.get("missingModule").asText(), r.toString());
            assertFalse(r.get("hosted").asBoolean(), r.toString());
            assertTrue(r.get("reason").asText().contains("zzabsent"), r.toString());
        }
    }

    /** UNSUPPORTED_NODE reads the manifest too: the refusal names the module that declares the node type. */
    @Test
    void anUnsupportedNodeRefusalNamesTheDeclaringModule(@TempDir Path dir) throws Exception {
        Path p = miniWith(dir, m -> { });
        absentModule("zz-pack", ABSENT);
        try (Ctx c = open(dir, List.of(p))) {
            JsonNode g = V1Body.of(send(c.port, "GET", "/pipelines/mini_etl/graph/raw", null).body());
            ((ArrayNode) g.get("nodes")).addObject().put("id", "zz1").put("type", ABSENT);
            ((ArrayNode) g.get("edges")).addObject().put("from", "parse").put("rel", "data").put("to", "zz1");
            HttpResponse<String> put = send(c.port, "PUT", "/pipelines/mini_etl/graph", M.writeValueAsString(g));
            assertEquals(422, put.statusCode(), put.body());
            JsonNode refusal = V1Body.envelope(put.body()).get("error").get("details").get("refusals").get(0);
            assertEquals("UNSUPPORTED_NODE", refusal.get("code").asText());
            assertTrue(refusal.get("message").asText().contains("module 'zz-pack', which is not installed"), refusal.toString());
        }
    }
}
