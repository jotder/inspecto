package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DELETE /config/pipeline/{name}} over real HTTP — finding the file, and the data option.
 *
 * <p>🔴 Two defects drive these tests. Deleting resolved the config ONLY against the write root, so a
 * pipeline whose file lives in {@code config/<name>/} — which is every sample pipeline in this repo —
 * came back "no such config" and could not be deleted at all. And deleting a pipeline left its written
 * data behind with no way to remove it.
 */
class ControlApiPipelineDeleteTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots)
            implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
            System.clearProperty("assist.write.root");
        }
    }

    /**
     * A pipeline config written at {@code configDir/<sub>/<name>_pipeline.toon} with its own data dirs
     * under {@code dataRoot/<name>}, plus a file in each so a removal has something to remove.
     */
    private static Path writePipelineAt(Path configDir, String sub, String name, Path dataRoot) throws Exception {
        Path dir = sub.isEmpty() ? configDir : Files.createDirectories(configDir.resolve(sub));
        Path schema = dir.resolve(name + "_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Path data = dataRoot.resolve(name);
        for (String k : List.of("database", "backup", "errors", "inbox")) {
            Path d = Files.createDirectories(data.resolve(k));
            Files.writeString(d.resolve("kept.txt"), "x");
        }
        Path toon = dir.resolve(name + "_pipeline.toon");
        Files.writeString(toon, """
                name: %s
                active: false
                dirs:
                  poll: %s
                  database: %s
                  backup: %s
                  errors: %s
                processing:
                  threads: 1
                  schema_file: %s
                output:
                  format: CSV
                """.formatted(name,
                data.resolve("inbox").toString().replace('\\', '/'),
                data.resolve("database").toString().replace('\\', '/'),
                data.resolve("backup").toString().replace('\\', '/'),
                data.resolve("errors").toString().replace('\\', '/'),
                schema.getFileName().toString()));
        return toon;
    }

    private Ctx open(Path configDir, List<Path> pipelines) throws Exception {
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", configDir.getParent().toString());
        System.setProperty("assist.write.root", configDir.toString());
        CollectorService svc = new CollectorService(pipelines, 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), priorRoots);
    }

    private HttpResponse<String> delete(int port, String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/config/pipeline/" + query))
                .method("DELETE", BodyPublishers.noBody()).build();
        return client.send(req, BodyHandlers.ofString());
    }

    /**
     * 🔴 The reported bug. Every sample pipeline lives in `config/<name>/<name>_pipeline.toon`, and the
     * delete resolved only against the write root — so it 404'd "no such config" for all of them.
     */
    @Test
    void deletesAPipelineWhoseConfigLivesInASubdirectory(@TempDir Path root) throws Exception {
        Path configDir = Files.createDirectories(root.resolve("config"));
        Path toon = writePipelineAt(configDir, "orders", "orders", root.resolve("data"));

        try (Ctx c = open(configDir, List.of(toon))) {
            HttpResponse<String> r = delete(c.port, "orders");

            assertEquals(200, r.statusCode(), "a registered pipeline is found by NAME, wherever its file sits: " + r.body());
            assertFalse(Files.exists(toon), "the config is gone");
        }
    }

    /** Without the opt-in the data is untouched — deleting a config must not delete data by default. */
    @Test
    void leavesTheDataAloneUnlessAsked(@TempDir Path root) throws Exception {
        Path configDir = Files.createDirectories(root.resolve("config"));
        Path toon = writePipelineAt(configDir, "orders", "orders", root.resolve("data"));

        try (Ctx c = open(configDir, List.of(toon))) {
            assertEquals(200, delete(c.port, "orders").statusCode());
            assertTrue(Files.exists(root.resolve("data/orders/database/kept.txt")), "data survives a plain delete");
        }
    }

    /**
     * ⛔ The inbox is never removed: it holds arrived-but-unprocessed source files, and is routinely a
     * feed's drop point rather than a private working directory.
     */
    @Test
    void removesTheOwnedDataButNeverTheInbox(@TempDir Path root) throws Exception {
        Path configDir = Files.createDirectories(root.resolve("config"));
        Path toon = writePipelineAt(configDir, "orders", "orders", root.resolve("data"));

        try (Ctx c = open(configDir, List.of(toon))) {
            HttpResponse<String> r = delete(c.port, "orders?data=true");

            assertEquals(200, r.statusCode(), r.body());
            assertFalse(Files.exists(root.resolve("data/orders/database")), "the database directory goes");
            assertFalse(Files.exists(root.resolve("data/orders/backup")), "so does backup");
            assertTrue(Files.exists(root.resolve("data/orders/inbox/kept.txt")),
                    "⛔ dirs.poll is never removed — it holds source data the pipeline did not create");
        }
    }

    /**
     * 🔴 A shared data directory REFUSES the whole delete rather than skipping it, and names both the
     * directory and the pipeline that shares it, so the operator knows what to repoint first. Nothing
     * is removed — not the data, and not the config.
     */
    @Test
    void refusesWhenAnotherPipelineSharesADataDirectory(@TempDir Path root) throws Exception {
        Path configDir = Files.createDirectories(root.resolve("config"));
        Path data = root.resolve("data");
        Path first = writePipelineAt(configDir, "orders", "orders", data);
        // A second pipeline pointed at the FIRST one's database — the shared case.
        Path second = writePipelineAt(configDir, "shipments", "shipments", data);
        Files.writeString(second, Files.readString(second).replace(
                data.resolve("shipments/database").toString().replace('\\', '/'),
                data.resolve("orders/database").toString().replace('\\', '/')));

        try (Ctx c = open(configDir, List.of(first, second))) {
            HttpResponse<String> r = delete(c.port, "orders?data=true");

            assertEquals(409, r.statusCode(), "a shared directory is a refusal, not a silent skip: " + r.body());
            assertTrue(r.body().contains("shipments"), "the refusal names the pipeline to repoint: " + r.body());
            assertTrue(r.body().contains("database"), "and which directory: " + r.body());
            assertTrue(Files.exists(root.resolve("data/orders/database/kept.txt")), "nothing was removed");
            assertTrue(Files.exists(first), "and the config survives — the delete is atomic, not partial");
        }
    }

    /**
     * ⛔ {@code force} overrides a dangling-REFERENCE refusal, which an operator can repair. It must not
     * override this one, which would destroy a live pipeline's data.
     */
    @Test
    void forceDoesNotOverrideASharedDataRefusal(@TempDir Path root) throws Exception {
        Path configDir = Files.createDirectories(root.resolve("config"));
        Path data = root.resolve("data");
        Path first = writePipelineAt(configDir, "orders", "orders", data);
        Path second = writePipelineAt(configDir, "shipments", "shipments", data);
        Files.writeString(second, Files.readString(second).replace(
                data.resolve("shipments/database").toString().replace('\\', '/'),
                data.resolve("orders/database").toString().replace('\\', '/')));

        try (Ctx c = open(configDir, List.of(first, second))) {
            assertEquals(409, delete(c.port, "orders?data=true&force=true").statusCode(),
                    "force is about references, never about destroying another pipeline's data");
            assertTrue(Files.exists(root.resolve("data/orders/database/kept.txt")));
        }
    }
}
