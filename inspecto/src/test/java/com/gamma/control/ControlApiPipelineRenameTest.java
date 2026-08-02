package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.etl.CommitLog;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code POST /pipelines/{name}/rename} (T3, plan §3) over real HTTP — the full
 * identity migration {@link PipelineRoutes#relabel} deliberately stops short of.
 *
 * <p>The fixture pipeline is {@code MINI_ETL} (display name) / {@code mini_etl} (derived identity), same
 * as {@link ControlApiPipelineTemplateTest}. Every write test needs the config writable in place (rename
 * jails the source path, like {@code label}), so {@code configDir == writeRoot}, and the fixture is
 * patched to {@code active: false} first — renaming an active pipeline is refused (gate 4).
 */
class ControlApiPipelineRenameTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server with the fixture pipeline at {@code root} (config == write root), deactivated unless {@code active}. */
    private Ctx open(Path root, boolean active) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(root, "");
        if (!active) {
            Map<String, Object> raw = ConfigLoader.filesystem().decode(pipe.toString());
            Map<String, Object> patched = new LinkedHashMap<>(raw);
            patched.put("active", false);
            Files.writeString(pipe, ConfigCodec.toToon(patched));
        }
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).build(),
                BodyHandlers.ofString());
    }

    // ── gates ────────────────────────────────────────────────────────────────────

    @Test
    void noWriteRootIs503() throws Exception {
        Path root = Files.createTempDirectory("rename-503");
        try {
            Path pipe = PipelineConfigBatchTest.writePipeline(root, "");
            System.clearProperty("assist.write.root");
            try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
                 ControlApi api = new ControlApi(svc, 0)) {
                api.start();
                assertEquals(503, post(api.port(), "/pipelines/mini_etl/rename", "{\"newId\":\"other\"}").statusCode());
            }
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void unknownSourcePipelineIs404() throws Exception {
        Path root = Files.createTempDirectory("rename-404");
        try (Ctx c = open(root, false)) {
            assertEquals(404, post(c.port, "/pipelines/nope/rename", "{\"newId\":\"other\"}").statusCode());
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void missingNewIdIs400AndInvalidShapeIs422() throws Exception {
        Path root = Files.createTempDirectory("rename-400");
        try (Ctx c = open(root, false)) {
            assertEquals(400, post(c.port, "/pipelines/mini_etl/rename", "{}").statusCode(), "newId is required");
            assertEquals(422, post(c.port, "/pipelines/mini_etl/rename",
                    "{\"newId\":\"Not-An-Id!\"}").statusCode(), "newId must match [a-z0-9][a-z0-9_]*");
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void activePipelineIs409() throws Exception {
        Path root = Files.createTempDirectory("rename-active-409");
        try (Ctx c = open(root, true)) {   // fixture's default active: true
            assertEquals(409, post(c.port, "/pipelines/mini_etl/rename",
                    "{\"newId\":\"mini_v2\"}").statusCode(), "an active pipeline must be deactivated first");
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void newIdAlreadyTakenOrFileExistsIs409() throws Exception {
        Path root = Files.createTempDirectory("rename-taken-409");
        try (Ctx c = open(root, false)) {
            assertEquals(409, post(c.port, "/pipelines/mini_etl/rename",
                    "{\"newId\":\"mini_etl\"}").statusCode(), "the source's own id is trivially taken");
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void relocateDirsTrueIsRefused() throws Exception {
        Path root = Files.createTempDirectory("rename-relocate-422");
        try (Ctx c = open(root, false)) {
            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/rename",
                    "{\"newId\":\"mini_v2\",\"relocateDirs\":true}");
            assertEquals(422, r.statusCode(), r.body());
        } finally {
            deleteRecursive(root);
        }
    }

    // ── the migration itself ──────────────────────────────────────────────────────

    @Test
    void renameMovesCommitLogAuditFilesAndTheConfigItself() throws Exception {
        Path root = Files.createTempDirectory("rename-migration");
        try (Ctx c = open(root, false)) {
            PipelineConfig before = PipelineConfig.load(root.resolve("mini_pipeline.toon").toString());
            Map<?, ?> dirsBefore = (Map<?, ?>) ConfigLoader.filesystem()
                    .decode(root.resolve("mini_pipeline.toon").toString()).get("dirs");

            // Seed durable run history exactly as a real batch would leave it: a commit-log line, plus
            // a run-timestamped audit CSV in the same status directory (S2/S3).
            new CommitLog(before.dirs().commitLogPath()).record(
                    "2026-08-02T00:00:00Z", "batch-1", "mini_etl", "SUCCESS", 1, 1, 10, 100);
            Path statusParent = Path.of(before.dirs().commitLogPath()).getParent();
            Path batchesCsv = statusParent.resolve("mini_etl_batches_20260802000000.csv");
            Files.writeString(batchesCsv, "batch_id,status\nbatch-1,SUCCESS\n");

            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/rename",
                    "{\"newId\":\"mini_v2\",\"newName\":\"Mini ETL v2\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean());
            assertEquals("mini_etl", out.get("oldId").asText());
            assertEquals("mini_v2", out.get("id").asText());
            assertEquals(2, out.get("auditFilesRenamed").asInt(), "the commit log + the one seeded audit CSV");

            // The old config file is gone; the new one exists with the migrated identity.
            assertFalse(Files.exists(root.resolve("mini_pipeline.toon")), "old config removed");
            assertTrue(Files.exists(root.resolve("mini_v2_pipeline.toon")), "new config written");
            Map<String, Object> newCfg = ConfigLoader.filesystem().decode(root.resolve("mini_v2_pipeline.toon").toString());
            assertEquals("mini_v2", String.valueOf(newCfg.get("id")));
            assertEquals("Mini ETL v2", String.valueOf(newCfg.get("name")));
            // dirs.* literal values (plan §1.1: not relocated) — the poll/database dirs never embedded the
            // pipeline name in the first place, so they must be byte-identical after the rename.
            assertEquals(dirsBefore, newCfg.get("dirs"), "dirs.* are left pointing where they already do");

            // The commit log and the audit CSV both moved to the new id prefix, in the SAME directory.
            assertFalse(Files.exists(statusParent.resolve("mini_etl_commits.log")), "old commit log gone");
            assertTrue(Files.exists(statusParent.resolve("mini_v2_commits.log")), "commit log renamed in place");
            assertFalse(Files.exists(batchesCsv), "old audit CSV gone");
            assertTrue(Files.exists(statusParent.resolve("mini_v2_batches_20260802000000.csv")),
                    "audit CSV renamed in place");

            // Run history survives under the new id — the whole point of S2/S3.
            JsonNode commits = V1Body.of(get(c.port, "/runs/mini_v2/commits").body());
            assertEquals(1, commits.size());
            assertEquals("batch-1", commits.get(0).asText());
            JsonNode batches = V1Body.of(get(c.port, "/runs/mini_v2/batches").body());
            assertEquals(1, batches.size());
            assertEquals("batch-1", batches.get(0).path("batch_id").asText());

            // The old id no longer resolves to anything; the new one is what /pipelines lists.
            assertEquals(404, get(c.port, "/pipelines/mini_etl/graph").statusCode());
            JsonNode list = V1Body.of(get(c.port, "/pipelines").body());
            boolean hasOld = false, hasNew = false;
            for (JsonNode n : list) {
                if ("mini_etl".equals(n.path("name").asText())) hasOld = true;
                if ("mini_v2".equals(n.path("name").asText())) hasNew = true;
            }
            assertFalse(hasOld, "old id no longer registered");
            assertTrue(hasNew, "new id is registered and listed");
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void renameRewritesDependentConfigsByDefault() throws Exception {
        Path root = Files.createTempDirectory("rename-dependents");
        try (Ctx c = open(root, false)) {
            // *_enrich.toon directly under the write root, triggered off the source pipeline.
            Map<String, Object> enrich = new LinkedHashMap<>();
            enrich.put("name", "mini_enrich");
            Map<String, Object> triggers = new LinkedHashMap<>();
            triggers.put("on_pipeline", "mini_etl");
            triggers.put("scheduleSeconds", 0);
            enrich.put("triggers", triggers);
            enrich.put("references", List.of());
            enrich.put("transform", Map.of());
            Files.writeString(root.resolve("mini_enrich.toon"), ConfigCodec.toToon(enrich));

            // jobs/*_job.toon under the write root.
            Files.createDirectories(root.resolve("jobs"));
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("name", "mini_job");
            job.put("on_pipeline", "mini_etl");
            Files.writeString(root.resolve("jobs").resolve("mini_job_job.toon"), ConfigCodec.toToon(job));

            // an expectation component targeting the pipeline.
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            Map<String, Object> expectation = new LinkedHashMap<>();
            expectation.put("targetType", "pipeline");
            expectation.put("target", "mini_etl");
            expectation.put("severity", "ERROR");
            store.write("expectation", "mini_exp", expectation);

            // a decision rule targeting the pipeline.
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("targetType", "pipeline");
            rule.put("target", "mini_etl");
            store.write("decision-rule", "mini_rule", rule);

            // a virtual dataset reading the pipeline's store by sourceName, and one by physicalRef.
            Map<String, Object> ds1 = new LinkedHashMap<>();
            ds1.put("kind", "virtual");
            ds1.put("sourceName", "mini_etl");
            store.write("dataset", "mini_ds1", ds1);
            Map<String, Object> ds2 = new LinkedHashMap<>();
            ds2.put("kind", "physical");
            ds2.put("physicalRef", "mini_etl/database");
            store.write("dataset", "mini_ds2", ds2);

            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/rename", "{\"newId\":\"mini_v2\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertEquals(6, out.get("dependentsRewritten").asInt(), "enrich + job + expectation + rule + 2 datasets");

            Map<String, Object> enrichAfter = ConfigLoader.filesystem().decode(root.resolve("mini_enrich.toon").toString());
            assertEquals("mini_v2", String.valueOf(((Map<?, ?>) enrichAfter.get("triggers")).get("on_pipeline")));

            Map<String, Object> jobAfter = ConfigLoader.filesystem().decode(
                    root.resolve("jobs").resolve("mini_job_job.toon").toString());
            assertEquals("mini_v2", String.valueOf(jobAfter.get("on_pipeline")));

            assertEquals("mini_v2", String.valueOf(store.get("expectation", "mini_exp").orElseThrow().content().get("target")));
            assertEquals("mini_v2", String.valueOf(store.get("decision-rule", "mini_rule").orElseThrow().content().get("target")));
            assertEquals("mini_v2", String.valueOf(store.get("dataset", "mini_ds1").orElseThrow().content().get("sourceName")));
            assertEquals("mini_v2/database", String.valueOf(store.get("dataset", "mini_ds2").orElseThrow().content().get("physicalRef")));
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void rewriteDependentsFalseLeavesThemPointingAtTheOldId() throws Exception {
        Path root = Files.createTempDirectory("rename-no-dependents");
        try (Ctx c = open(root, false)) {
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            Map<String, Object> expectation = new LinkedHashMap<>();
            expectation.put("targetType", "pipeline");
            expectation.put("target", "mini_etl");
            store.write("expectation", "mini_exp", expectation);

            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/rename",
                    "{\"newId\":\"mini_v2\",\"rewriteDependents\":false}");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(0, V1Body.of(r.body()).get("dependentsRewritten").asInt());
            assertEquals("mini_etl", String.valueOf(store.get("expectation", "mini_exp").orElseThrow().content().get("target")),
                    "left pointing at the old id, as asked");
        } finally {
            deleteRecursive(root);
        }
    }

    private static void deleteRecursive(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (java.io.IOException ignored) { }
            });
        }
    }
}
