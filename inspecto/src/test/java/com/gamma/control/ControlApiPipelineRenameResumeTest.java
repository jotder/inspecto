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
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code POST /pipelines/rename/resume} and the journal begin/completed bracket the
 * resume mechanism reads back. Same fixture and harness as {@link ControlApiPipelineRenameTest}: the
 * {@code mini_etl} pipeline with {@code configDir == writeRoot}, deactivated unless a test needs the gate.
 *
 * <p>The failure states are staged by hand (journal lines + file moves) rather than by injecting faults
 * into the route — the journal contract IS the interface resume consumes, so writing it directly tests
 * exactly what a crashed migration leaves behind.
 */
class ControlApiPipelineRenameResumeTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

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

    /** Append one journal line as a crashed migration would have left it. */
    private static void journal(Path root, String line) throws Exception {
        Files.writeString(root.resolve("rename.journal"), line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ── the bracket a fresh rename writes ────────────────────────────────────────

    @Test
    void freshRenameBracketsTheJournalWithBeginAndCompleted() throws Exception {
        Path root = Files.createTempDirectory("resume-bracket");
        try (Ctx c = open(root, false)) {
            HttpResponse<String> r = post(c.port, "/pipelines/mini_etl/rename",
                    "{\"newId\":\"mini_v2\",\"newName\":\"Mini ETL v2\"}");
            assertEquals(200, r.statusCode(), r.body());

            List<String> lines = Files.readAllLines(root.resolve("rename.journal"));
            assertTrue(lines.get(0).contains(
                            "mini_etl -> mini_v2 : begin src=mini_pipeline.toon rewriteDependents=true newName=Mini ETL v2"),
                    "begin records the source file and the request parameters: " + lines.get(0));
            assertTrue(lines.get(lines.size() - 1).endsWith("mini_etl -> mini_v2 : completed"),
                    "completed closes the bracket: " + lines.get(lines.size() - 1));

            // A closed bracket is nothing to resume.
            assertEquals(404, post(c.port, "/pipelines/rename/resume", "{}").statusCode());
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void resumeWithNoJournalIs404() throws Exception {
        Path root = Files.createTempDirectory("resume-nothing");
        try (Ctx c = open(root, false)) {
            assertEquals(404, post(c.port, "/pipelines/rename/resume", "{}").statusCode());
        } finally {
            deleteRecursive(root);
        }
    }

    // ── the three interrupted states ─────────────────────────────────────────────

    /** Failure before the config write (the state a plain retry could also heal): the source config is
     *  still on disk and registered — resume runs the whole migration, honouring the recorded newName. */
    @Test
    void resumeAfterEarlyFailureCompletesTheMigration() throws Exception {
        Path root = Files.createTempDirectory("resume-early");
        try (Ctx c = open(root, false)) {
            PipelineConfig before = PipelineConfig.load(root.resolve("mini_pipeline.toon").toString());
            new CommitLog(before.dirs().commitLogPath()).record(
                    "2026-08-13T00:00:00Z", "batch-1", "mini_etl", "SUCCESS", 1, 1, 10, 100);

            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : begin src=mini_pipeline.toon"
                    + " rewriteDependents=true newName=Mini V2");
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : unregistered source");

            HttpResponse<String> r = post(c.port, "/pipelines/rename/resume", "{}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean());
            assertTrue(out.get("resumed").asBoolean());
            assertEquals("mini_etl", out.get("oldId").asText());
            assertEquals("mini_v2", out.get("id").asText());
            assertEquals("Mini V2", out.get("name").asText(), "the begin line's newName survives the crash");
            assertEquals(1, out.get("auditFilesRenamed").asInt(), "the seeded commit log");

            assertFalse(Files.exists(root.resolve("mini_pipeline.toon")), "old config removed");
            assertTrue(Files.exists(root.resolve("mini_v2_pipeline.toon")), "new config written");
            Path statusParent = Path.of(before.dirs().commitLogPath()).getParent();
            assertTrue(Files.exists(statusParent.resolve("mini_v2_commits.log")), "commit log renamed");

            List<String> lines = Files.readAllLines(root.resolve("rename.journal"));
            assertTrue(lines.get(lines.size() - 1).endsWith("mini_etl -> mini_v2 : completed"));
            assertEquals(404, post(c.port, "/pipelines/rename/resume", "{}").statusCode(),
                    "the bracket is closed — nothing left to resume");
        } finally {
            deleteRecursive(root);
        }
    }

    /** Failure after the config write + source delete (a plain retry 404s — the pipeline is registered
     *  under neither id): resume registers the new config, finishes the dependents and closes the bracket.
     *  The audit rename must work with only the NEW config to read dirs from. */
    @Test
    void resumeAfterPostWriteCrashRegistersAndFinishes() throws Exception {
        Path root = Files.createTempDirectory("resume-postwrite");
        try (Ctx c = open(root, false)) {
            Path pipe = root.resolve("mini_pipeline.toon");
            PipelineConfig before = PipelineConfig.load(pipe.toString());
            new CommitLog(before.dirs().commitLogPath()).record(
                    "2026-08-13T00:00:00Z", "batch-1", "mini_etl", "SUCCESS", 1, 1, 10, 100);

            ComponentStore store = new ComponentStore(root.resolve("registry"));
            Map<String, Object> expectation = new LinkedHashMap<>();
            expectation.put("targetType", "pipeline");
            expectation.put("target", "mini_etl");
            store.write("expectation", "mini_exp", expectation);

            // Stage the crash state exactly as rename's step 6 leaves it: new config written with the
            // migrated identity, source deleted, pipeline unregistered, bracket open past "wrote".
            Map<String, Object> src = ConfigLoader.filesystem().decode(pipe.toString());
            Map<String, Object> outCfg = new LinkedHashMap<>();
            outCfg.put("name", "Mini V2");
            outCfg.put("id", "mini_v2");
            src.forEach((k, v) -> {
                if (!"name".equals(k) && !"id".equals(k)) outCfg.put(k, v);
            });
            Files.writeString(root.resolve("mini_v2_pipeline.toon"), ConfigCodec.toToon(outCfg));
            c.svc.unregisterPipeline(pipe);
            Files.delete(pipe);
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : begin src=mini_pipeline.toon"
                    + " rewriteDependents=true newName=Mini V2");
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : unregistered source");
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : ledger rows moved: 0");
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : audit files renamed: 0");
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : wrote mini_v2_pipeline.toon; removed source config");

            HttpResponse<String> r = post(c.port, "/pipelines/rename/resume", "{}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("resumed").asBoolean());
            assertEquals(1, out.get("auditFilesRenamed").asInt(),
                    "the commit log moves even though only the new config supplies dirs");
            assertEquals(1, out.get("dependentsRewritten").asInt());
            assertEquals("mini_v2", String.valueOf(
                    store.get("expectation", "mini_exp").orElseThrow().content().get("target")));

            Path statusParent = Path.of(before.dirs().commitLogPath()).getParent();
            assertTrue(Files.exists(statusParent.resolve("mini_v2_commits.log")));
            JsonNode list = V1Body.of(get(c.port, "/pipelines").body());
            boolean hasNew = false;
            for (JsonNode n : list) if ("mini_v2".equals(n.path("name").asText())) hasNew = true;
            assertTrue(hasNew, "the orphaned new config is registered again");
        } finally {
            deleteRecursive(root);
        }
    }

    /** The crash window between writing the new config and deleting the source (a plain retry 409s on
     *  file-exists): resume verifies the new file is this migration's product, then removes the source. */
    @Test
    void resumeAfterWriteButBeforeDeleteRemovesTheSource() throws Exception {
        Path root = Files.createTempDirectory("resume-bothfiles");
        try (Ctx c = open(root, false)) {
            Path pipe = root.resolve("mini_pipeline.toon");
            Map<String, Object> src = ConfigLoader.filesystem().decode(pipe.toString());
            Map<String, Object> outCfg = new LinkedHashMap<>();
            outCfg.put("name", "Mini V2");
            outCfg.put("id", "mini_v2");
            src.forEach((k, v) -> {
                if (!"name".equals(k) && !"id".equals(k)) outCfg.put(k, v);
            });
            Files.writeString(root.resolve("mini_v2_pipeline.toon"), ConfigCodec.toToon(outCfg));
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : begin src=mini_pipeline.toon"
                    + " rewriteDependents=true newName=Mini V2");

            HttpResponse<String> r = post(c.port, "/pipelines/rename/resume", "{}");
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(Files.exists(pipe), "the owed source delete happened");
            assertTrue(Files.exists(root.resolve("mini_v2_pipeline.toon")));
            assertEquals(404, get(c.port, "/pipelines/mini_etl/graph").statusCode(), "old id gone");
        } finally {
            deleteRecursive(root);
        }
    }

    // ── fail-closed gates ────────────────────────────────────────────────────────

    @Test
    void resumeRechecksTheActiveGate() throws Exception {
        Path root = Files.createTempDirectory("resume-active");
        try (Ctx c = open(root, true)) {   // fixture's default active: true
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : begin src=mini_pipeline.toon"
                    + " rewriteDependents=true");
            assertEquals(409, post(c.port, "/pipelines/rename/resume", "{}").statusCode(),
                    "a source reactivated since the failed attempt must be deactivated again first");
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void resumeRefusesASquatterOnTheNewId() throws Exception {
        Path root = Files.createTempDirectory("resume-squatter");
        try (Ctx c = open(root, false)) {
            Map<String, Object> squatter = new LinkedHashMap<>();
            squatter.put("name", "someone else's config");
            squatter.put("id", "other");
            Files.writeString(root.resolve("mini_v2_pipeline.toon"), ConfigCodec.toToon(squatter));
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : begin src=mini_pipeline.toon"
                    + " rewriteDependents=true");
            assertEquals(409, post(c.port, "/pipelines/rename/resume", "{}").statusCode(),
                    "mini_v2_pipeline.toon exists but carries a different id — never delete the source");
            assertTrue(Files.exists(root.resolve("mini_pipeline.toon")), "source untouched");
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void severalIncompleteRenamesNeedSelectors() throws Exception {
        Path root = Files.createTempDirectory("resume-several");
        try (Ctx c = open(root, false)) {
            journal(root, "2026-08-13T00:00:00Z mini_etl -> mini_v2 : begin src=mini_pipeline.toon"
                    + " rewriteDependents=true newName=Mini V2");
            journal(root, "2026-08-13T00:00:00Z ghost -> ghost2 : begin src=ghost_pipeline.toon"
                    + " rewriteDependents=true");
            assertEquals(409, post(c.port, "/pipelines/rename/resume", "{}").statusCode(),
                    "ambiguous without {oldId, newId}");
            HttpResponse<String> r = post(c.port, "/pipelines/rename/resume",
                    "{\"oldId\":\"mini_etl\",\"newId\":\"mini_v2\"}");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("mini_v2", V1Body.of(r.body()).get("id").asText());
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void resumeWithNeitherFileOnDiskIs409() throws Exception {
        Path root = Files.createTempDirectory("resume-neither");
        try (Ctx c = open(root, false)) {
            journal(root, "2026-08-13T00:00:00Z ghost -> ghost2 : begin src=ghost_pipeline.toon"
                    + " rewriteDependents=true");
            assertEquals(409, post(c.port, "/pipelines/rename/resume", "{}").statusCode(),
                    "neither config file survives — manual reconciliation, never a guess");
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
