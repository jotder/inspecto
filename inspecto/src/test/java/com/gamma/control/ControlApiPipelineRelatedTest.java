package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@code GET /pipelines/{name}/related} (pipeline spec §12 gap 5, decision D9)
 * over real HTTP: the read's gate order, both halves of the closure, and the bound on the inward list.
 *
 * <p>⚠ Unlike {@code /config/pipeline/{name}/impact}, which reads a FILE under the write root, this
 * route answers for a <b>registered</b> pipeline — the outward half is
 * {@code PipelineConfig.referencedFiles()}, which only exists once the parser has read the config. A
 * file sitting under the write root that no {@code CollectorService} loaded is therefore a 404 here and
 * a 200 there, which is not an inconsistency but the difference between "what is deployed" and "what
 * is on disk". {@link #anUnregisteredPipelineIs404} pins it so the distinction stays deliberate.
 */
class ControlApiPipelineRelatedTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /**
     * Register the mini pipeline out of {@code dir} (id {@code mini_etl}, referencing
     * {@code mini_schema.toon} beside it). ⚠ Tests pass the SAME dir as config dir and write root, so a
     * referenced companion actually lands under the root and the reported path relativizes — the
     * deployed shape, and the one where {@code references[].path} is meaningful.
     */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .GET().build(), BodyHandlers.ofString());
    }

    private static List<String> kinds(JsonNode references) {
        List<String> out = new ArrayList<>();
        references.forEach(r -> out.add(r.get("kind").asText()));
        return out;
    }

    private static JsonNode refWithKind(JsonNode references, String kind) {
        for (JsonNode r : references) if (kind.equals(r.get("kind").asText())) return r;
        return null;
    }

    // ── gates ────────────────────────────────────────────────────────────────────

    /**
     * ⚠ Not a write gate on a read route: the inward half must walk the configs under the write root,
     * so without one there is no corpus to scan, and half a closure presented as a whole is a trap for
     * a caller asking "what does an import need".
     */
    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, get(c.port, "/pipelines/mini_etl/related").statusCode());
        }
    }

    @Test
    void unsafeNameIs422(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, cfg)) {
            assertEquals(422, get(c.port, "/pipelines/a..b/related").statusCode());
        }
    }

    @Test
    void anUnregisteredPipelineIs404(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, cfg)) {
            Files.writeString(cfg.resolve("ghost_pipeline.toon"), "name: Ghost\nactive: false\n");
            assertEquals(404, get(c.port, "/pipelines/ghost/related").statusCode(),
                    "a file on disk that no CollectorService loaded has no parsed config, so no closure");
        }
    }

    @Test
    void limitMustBeAPositiveInteger(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, cfg)) {
            assertEquals(400, get(c.port, "/pipelines/mini_etl/related?limit=abc").statusCode());
            assertEquals(400, get(c.port, "/pipelines/mini_etl/related?limit=0").statusCode());
            assertEquals(200, get(c.port, "/pipelines/mini_etl/related?limit=1").statusCode());
        }
    }

    // ── the outward half ─────────────────────────────────────────────────────────

    /**
     * The schema the pipeline names is reported with a path relative to the write root — never the
     * absolute server path, which is neither portable nor a caller's business.
     */
    @Test
    void reportsTheCompanionFilesTheParserActuallyRead(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, cfg)) {
            JsonNode out = V1Body.of(get(c.port, "/pipelines/mini_etl/related").body());
            assertEquals("mini_etl", out.get("pipeline").asText());

            JsonNode refs = out.get("references");
            assertTrue(refs.size() >= 1, "the mini pipeline names a schema_file: " + out);
            JsonNode schema = refWithKind(refs, "file");
            assertNotNull(schema, "a plain path is reported as kind 'file': " + refs);
            assertEquals("mini_schema.toon", schema.get("path").asText(),
                    "relative to the write root, / separated");
            assertFalse(schema.has("ref"), "a plain path is not a shared component, so it carries no ref");
        }
    }

    /**
     * A companion living in the registry IS that component type — the directory says so — and carries
     * the canonical {@code <type>/<id>} ref an import would apply. ⚠ The config spells the ref
     * {@code grammar/<id>} (singular) while the file lives under {@code registry/grammars/}; the
     * reported ref is always the canonical singular type, so a caller never normalises.
     */
    @Test
    void aRegistryComponentIsNamedByTypeAndId(@TempDir Path cfg) throws Exception {
        Path grammars = cfg.resolve("registry").resolve("grammars");
        Files.createDirectories(grammars);
        Files.writeString(grammars.resolve("cdr.toon"), "delimiter: \"|\"\n");

        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        String toon = Files.readString(pipe).replace("processing:", "parsing:\n  grammar: grammar/cdr\nprocessing:");
        Files.writeString(pipe, toon);

        System.setProperty("assist.write.root", cfg.toString());
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
             ControlApi api = new ControlApi(svc, 0)) {
            System.clearProperty("assist.write.root");
            api.start();
            JsonNode refs = V1Body.of(get(api.port(), "/pipelines/mini_etl/related").body()).get("references");
            JsonNode grammar = refWithKind(refs, "grammar");
            assertNotNull(grammar, "the referenced grammar component must be reported: " + refs);
            assertEquals("grammar/cdr", grammar.get("ref").asText());
            assertEquals("registry/grammars/cdr.toon", grammar.get("path").asText());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    // ── the inward half ──────────────────────────────────────────────────────────

    /** The same scan {@code /impact} serves, reported alongside the outward half. */
    @Test
    void reportsWhatPointsAtThePipeline(@TempDir Path cfg) throws Exception {
        Files.writeString(cfg.resolve("daily_enrich.toon"), """
                name: daily
                transform: SELECT 1
                triggers:
                  on_pipeline: mini_etl
                """);
        Files.createDirectories(cfg.resolve("jobs"));
        Files.writeString(cfg.resolve("jobs").resolve("nightly_job.toon"), """
                job:
                  name: nightly
                on_pipeline: mini_etl
                """);
        Files.createDirectories(cfg.resolve("registry").resolve("datasets"));
        Files.writeString(cfg.resolve("registry").resolve("datasets").resolve("mini_ds.toon"),
                "name: mini_ds\nphysicalRef: mini_etl/database\n");

        try (Ctx c = open(cfg, cfg)) {
            JsonNode out = V1Body.of(get(c.port, "/pipelines/mini_etl/related").body());
            assertEquals(3, out.get("total").asInt(), out.toString());
            assertFalse(out.get("truncated").asBoolean());
            JsonNode d = out.get("dependents");
            assertEquals("triggers.on_pipeline", d.get("enrichment").get(0).get("via").asText());
            assertEquals("nightly_job", d.get("job").get(0).get("name").asText());
            assertEquals("mini_ds", d.get("dataset").get(0).get("name").asText());
        }
    }

    /** ⚠ A bounded list must still report the TRUE total, or a caller under-counts what a delete breaks. */
    @Test
    void theInwardListIsBoundedButTheTotalIsTrue(@TempDir Path cfg) throws Exception {
        for (int i = 0; i < 4; i++) {
            Files.writeString(cfg.resolve("e" + i + "_enrich.toon"), """
                    name: e%d
                    transform: SELECT 1
                    triggers:
                      on_pipeline: mini_etl
                    """.formatted(i));
        }
        try (Ctx c = open(cfg, cfg)) {
            JsonNode out = V1Body.of(get(c.port, "/pipelines/mini_etl/related?limit=2").body());
            assertEquals(4, out.get("total").asInt(), "the true count, not the page size");
            assertTrue(out.get("truncated").asBoolean());
            assertEquals(2, out.get("dependents").get("enrichment").size());
        }
    }

    /**
     * ⛔ Connections are excluded (D9) — they carry environment and credentials, and a bundle that
     * moved them would move a deployment's identity between spaces.
     *
     * <p>⚠ <b>Measured, so nobody mistakes this green for more than it is:</b> adding {@code connections}
     * to {@code PipelineRelated}'s registry-dir map does NOT fail this test. A Connection is resolved at
     * run time and never enters {@code referencedFiles()}, so the closure could not report one however
     * the map is written. This therefore pins the <b>observable guarantee</b> — no connection in the
     * response — while the map's omission is defence in depth that is deliberately unfalsifiable from
     * here. If a Connection ever does become a parsed reference, THIS test starts carrying the weight.
     */
    @Test
    void neverReportsAConnection(@TempDir Path cfg) throws Exception {
        Path connections = cfg.resolve("registry").resolve("connections");
        Files.createDirectories(connections);
        Files.writeString(connections.resolve("prod_sftp.toon"), "name: prod_sftp\ntype: sftp\nhost: h\n");

        try (Ctx c = open(cfg, cfg)) {
            JsonNode out = V1Body.of(get(c.port, "/pipelines/mini_etl/related").body());
            assertFalse(kinds(out.get("references")).contains("connection"), out.toString());
            assertFalse(out.toString().contains("prod_sftp"),
                    "a Connection must not appear anywhere in the closure");
        }
    }
}
