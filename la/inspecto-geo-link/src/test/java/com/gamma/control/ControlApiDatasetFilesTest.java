package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.core.CollectorCoveragePort;
import com.gamma.la.core.CollectorCoveragePorts;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import com.gamma.la.storage.IndexStore;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DATA-TRUST-1 S1 - {@code GET /inv/datasets/{id}/files} over REAL HTTP with an ARMED Authenticator throughout (with no
 * Subject {@code withCapability} is a no-op and a gate test would pass against an ungated route). The audit-trail port is a
 * fixed list (the join over real CSVs is {@link HostCollectorCoveragePortFilesTest}); the live index manifest is written to
 * the Space's {@code la-index} directly so the INDEXED / WAITING / UNKNOWN matching is deterministic.
 */
class ControlApiDatasetFilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner", STRANGER = "Bearer stranger";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        DatasetProviders.forTest(null);
        CollectorCoveragePorts.forTest(null);
    }

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of()));
            case STRANGER -> Optional.of(new Subject("analyst-3", Set.of()));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sights_view", "flow-x", List.of(),
                        "SELECT * FROM (VALUES ('a','b')) AS t(who,other)", "2026-10-02T00:00:00Z"));
                ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
                store.write("dataset", "private_ds", Map.of("view", "sights_view", "owner", "analyst-1", "shares", List.of()));
            }
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(Ctx c, String path, String auth) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", auth).GET().build(), BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static CollectorCoveragePort.FileEntry file(String name, String ingestedAt, long rows, String outcome, String... outputs) {
        return new CollectorCoveragePort.FileEntry(name, outputs.length == 0 ? null : "2026-09-01", rows, rows == 0 ? 0 : -1, ingestedAt, outcome,
                "c1", "p1", "b-" + name, List.of(outputs));
    }

    private static void port(CollectorCoveragePort.FileEntry... files) {
        CollectorCoveragePorts.forTest(new CollectorCoveragePort() {
            @Override
            public Optional<List<Delivery>> deliveries(com.gamma.spi.http.ApiContext api, Map<String, Object> dataset) {
                return Optional.empty();
            }

            @Override
            public Optional<List<FileEntry>> files(com.gamma.spi.http.ApiContext api, Map<String, Object> dataset) {
                return Optional.of(List.of(files));
            }
        });
    }

    /** Publishes v1 of an index over {@code private_ds} whose manifest lists {@code inputs} (null = no list recorded). */
    private static void publishIndex(Path writeRoot, List<String> inputs) throws Exception {
        IndexMapping mp = new IndexMapping("who", "other", null, null, null, null, List.of());
        List<IndexManifest.InputFile> files = inputs == null ? null : inputs.stream().map(p -> new IndexManifest.InputFile(p, 1, 1)).toList();
        IndexManifest m = new IndexManifest(1, "2026-09-05T10:00:00Z", IndexManifest.Builder.FULL, "1.4", "hash_mod", 16, 1, mp, mp.hash(),
                "private_ds", "sql", "fp", null, Map.of(), 0, List.of(), null, files);
        IndexStore store = new IndexStore(writeRoot.resolve("la-index"), "private_ds", mp.hash());
        Path staged = store.stage();
        m.write(staged);
        store.publish(staged);
    }

    @Test
    void filesCarryOutcomeIndexStateAndAZeroRowFileIsDistinctFromNoFile(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        port(file("a.csv", "2026-09-02T10:00:00Z", 100, "SUCCESS", "/srv/data/calls/day=2026-09-01/a.parquet"),
                file("b.csv", "2026-09-03T10:00:00Z", 50, "SUCCESS", "D:\\srv\\data\\calls\\day=2026-09-02\\b.parquet"),
                file("empty.csv", "2026-09-04T10:00:00Z", 0, "QUARANTINED"),
                file("c.csv", "2026-09-05T10:00:00Z", 7, "PARTIAL", "/srv/data/calls/day=2026-09-03/c.parquet"));
        try (Ctx c = open(cfg, root)) {
            publishIndex(root, List.of("calls/day=2026-09-01/a.parquet", "calls/day=2026-09-02/b.parquet"));
            JsonNode d = data(get(c, "/inv/datasets/private_ds/files", OWNER));
            assertTrue(d.get("assessed").asBoolean());
            assertEquals(4, d.get("total").asInt());
            JsonNode items = d.get("items");
            assertEquals("c.csv", items.get(0).get("name").asText(), "newest ingested-at first");
            assertEquals("WAITING", items.get(0).get("indexState").asText());
            assertFalse(items.get(0).has("outputFiles"), "server paths are not served to a view-only caller");
            assertTrue(items.get(0).get("indexVersion").isNull());
            assertEquals("INDEXED", items.get(2).get("indexState").asText());
            assertEquals(1, items.get(2).get("indexVersion").asInt());
            JsonNode empty = items.get(1);
            assertEquals("empty.csv", empty.get("name").asText());
            assertEquals(0, empty.get("rows").asLong(), "a File with no rows is a row with 0, not an absent row");
            assertTrue(empty.get("indexState").isNull(), "it wrote no partition file");
            assertEquals("QUARANTINED", empty.get("outcome").asText());
            assertTrue(items.get(0).get("errorRows").isNull(), "-1 (not measured) is null, never 0");
            assertEquals(2, d.get("summary").get("indexed").asInt());
            assertEquals(1, d.get("summary").get("waiting").asInt());
            assertEquals(2, d.get("summary").get("problem").asInt(), "the quarantined and the partial File");
            assertEquals("2026-09-05T10:00:00Z", d.get("summary").get("latestIngestedAt").asText());
            assertEquals(1, d.get("index").get("version").asInt());
            assertEquals(1, d.get("days").size(), "the fixture gives every File that wrote a partition the same event day; the File with none has no day");
            assertEquals(3, d.get("days").get(0).get("files").asInt());

            JsonNode page = data(get(c, "/inv/datasets/private_ds/files?limit=1&offset=1", OWNER));
            assertEquals(4, page.get("total").asInt());
            assertEquals(1, page.get("items").size());
            assertEquals("empty.csv", page.get("items").get(0).get("name").asText());

            JsonNode filtered = data(get(c, "/inv/datasets/private_ds/files?name=B.C", OWNER));
            assertEquals(1, filtered.get("total").asInt());
            assertEquals("b.csv", filtered.get("items").get(0).get("name").asText());
        }
    }

    @Test
    void noIndexAndAnUnrecordedFileListAreNamedNotGuessed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        port(file("a.csv", "2026-09-02T10:00:00Z", 100, "SUCCESS", "/srv/data/calls/day=2026-09-01/a.parquet"));
        try (Ctx c = open(cfg, root)) {
            JsonNode none = data(get(c, "/inv/datasets/private_ds/files", OWNER));
            assertEquals("NO_INDEX", none.get("items").get(0).get("indexState").asText());
            assertTrue(none.get("index").get("version").isNull());
            publishIndex(root, null);
            JsonNode unknown = data(get(c, "/inv/datasets/private_ds/files", OWNER));
            assertEquals("UNKNOWN", unknown.get("items").get(0).get("indexState").asText(), "past the cap / older manifest: never guess from timestamps");
        }
    }

    @Test
    void aListThatSharesNothingWithAnyFileIsUnknownNotWaiting(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        port(file("a.csv", "2026-09-02T10:00:00Z", 100, "SUCCESS", "/srv/data/calls/day=2026-09-01/a.parquet"));
        try (Ctx c = open(cfg, root)) {
            publishIndex(root, List.of("other/part-0.parquet"));
            assertEquals("UNKNOWN", data(get(c, "/inv/datasets/private_ds/files", OWNER)).get("items").get(0).get("indexState").asText());
        }
    }

    @Test
    void withoutAuditEvidenceTheAnswerIsNotAssessedNeverAnEmptyListOfFiles(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        CollectorCoveragePorts.forTest(null);
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(get(c, "/inv/datasets/private_ds/files", OWNER));
            assertEquals(false, d.get("assessed").asBoolean());
            assertEquals(0, d.get("items").size());
        }
    }

    @Test
    void everyGateRefuses(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        subjects();
        port(file("a.csv", "2026-09-02T10:00:00Z", 1, "SUCCESS"));
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, get(c, "/inv/datasets/private_ds/files", "Bearer nobody").statusCode());
            assertEquals(404, get(c, "/inv/datasets/private_ds/files", STRANGER).statusCode(), "R3: not viewable");
            assertEquals(404, get(c, "/inv/datasets/no_such/files", OWNER).statusCode());
            assertEquals(get(c, "/inv/datasets/private_ds/files", STRANGER).body().replace("private_ds", "x").replaceAll("\"correlationId\":\"[^\"]*\"", ""),
                    get(c, "/inv/datasets/no_such/files", STRANGER).body().replace("no_such", "x").replaceAll("\"correlationId\":\"[^\"]*\"", ""), "not viewable reads exactly as absent");
            assertEquals(422, get(c, "/inv/datasets/private_ds/files?limit=0", OWNER).statusCode());
            assertEquals(422, get(c, "/inv/datasets/private_ds/files?limit=1001", OWNER).statusCode());
            assertEquals(422, get(c, "/inv/datasets/private_ds/files?offset=-1", OWNER).statusCode());
            assertEquals(422, get(c, "/inv/datasets/private_ds/files?limit=abc", OWNER).statusCode());
        }
        try (Ctx c = open(cfg2, null)) {
            assertEquals(503, get(c, "/inv/datasets/private_ds/files", OWNER).statusCode(), "no write root");
        }
    }
}
