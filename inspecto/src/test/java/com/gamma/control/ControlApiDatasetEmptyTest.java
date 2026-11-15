package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-EMPTY-DATASET-WORDING-1, real HTTP: the Dataset list tells the Data page which Datasets' stores hold nothing yet
 * ({@code empty}: true / false / null when it cannot tell), and a read of such a Dataset refuses in an analyst's words,
 * naming the Pipeline that fills it.
 */
class ControlApiDatasetEmptyTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private static JsonNode named(JsonNode list, String name) {
        for (JsonNode d : list) if (d.get("name").asText().equals(name)) return d;
        fail("no '" + name + "' in " + list);
        return null;
    }

    @Test
    void theDatasetListSaysWhichStoresHoldNothingYet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // a legacy Space's data root is the relative directory "database": uniquely named stores, removed at the end
        String never = "never_run_" + System.nanoTime(), ran = "ran_" + System.nanoTime();
        Path ranDir = Path.of("database").resolve(ran);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root)) {
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            store.write("dataset", "never_ds", Map.of("physicalRef", never + "/database"));
            store.write("dataset", "ran_ds", Map.of("physicalRef", ran + "/database"));
            store.write("dataset", "view_ds", Map.of("view", "some_view"));
            store.write("grammar", "g1", Map.of("format", "csv"));
            Files.createDirectories(ranDir.resolve("database").resolve("day=2026-11-15"));
            Files.writeString(ranDir.resolve("database").resolve("day=2026-11-15").resolve("p1.parquet"), "a file is present");

            HttpResponse<String> r = get(c, "/components/dataset");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode list = V1Body.of(r.body());
            assertTrue(named(list, "never_ds").get("empty").asBoolean(), "nothing written yet: " + list);
            JsonNode ranDoc = named(list, "ran_ds");
            assertTrue(ranDoc.has("empty") && !ranDoc.get("empty").asBoolean(), "a file is there: " + list);
            JsonNode view = named(list, "view_ds");
            assertTrue(view.has("empty") && view.get("empty").isNull(), "a view cannot be told: " + list);
            // stored content is untouched: the field rides beside it, never in it
            assertFalse(named(list, "never_ds").get("content").has("empty"), list.toString());

            // only the Dataset list carries it: not another kind's list, not the one-component read (its ETag is the content hash)
            JsonNode grammars = V1Body.of(get(c, "/components/grammar").body());
            assertFalse(named(grammars, "g1").has("empty"), grammars.toString());
            HttpResponse<String> one = get(c, "/components/dataset/never_ds");
            assertEquals(200, one.statusCode(), one.body());
            assertFalse(V1Body.of(one.body()).has("empty"), one.body());
        } finally {
            try (Stream<Path> w = Files.walk(ranDir)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } catch (Exception ignore) { }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void aReadOfAPipelineFedEmptyDatasetNamesItsPipeline(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String pipeline = "never_run_" + System.nanoTime();
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "fed_ds", Map.of("physicalRef", pipeline + "/database"));
            HttpResponse<String> r = get(c, "/datasets/fed_ds/rows");
            assertEquals(409, r.statusCode(), r.body());
            JsonNode error = V1Body.envelope(r.body()).get("error");
            assertEquals("CONFLICT", error.get("errorCode").asText(), r.body());
            assertEquals("Dataset 'fed_ds' has no rows yet: its Pipeline '" + pipeline + "' has not run.", error.get("message").asText());
            for (String dev : List.of("physicalRef", "store", "/database"))
                assertFalse(r.body().contains(dev), "no '" + dev + "' in " + r.body());
        }
    }
}
