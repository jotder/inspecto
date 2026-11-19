package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} BE-3 slice 2, the import half of "Add data follows the sniff" (operator ruling 2026-11-19):
 * the Pipeline Add data writes reads the file the way its draft did - no quote ({@code quote: ""}) when the sniff found
 * none and the file holds a {@code "} the default quote would read otherwise, the lines the sniff skipped before the
 * header skipped ({@code skip_header_lines}) - so the run that
 * commits the import, and every later run, lands what the draft showed. The draft half is {@code DialectSniffGoldenTest}.
 * Real HTTP, synthetic fixtures only.
 */
class ControlApiOnboardDialectTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private JsonNode post(Ctx c, String path, byte[] body) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .POST(BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    /** Draft {@code content} as {@code name}, commit it as a new import, and return the import's id. */
    private String addData(Ctx c, String name, String content) throws Exception {
        JsonNode d = post(c, "/onboard/files?name=" + name, content.getBytes(StandardCharsets.UTF_8));
        String id = name.replace(".csv", "") + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        post(c, "/onboard/files/" + d.get("draftId").asText() + "/commit", ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8));
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> csvSettings(Ctx c, String id) throws Exception {
        Map<String, Object> p = ToonHelper.load(c.root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon").toString());
        return (Map<String, Object>) ((Map<String, Object>) p.get("processing")).get("csv_settings");
    }

    /** The import's rows as {@code a|b} lines, in file order. */
    private static List<String> rows(String id, String a, String b) throws Exception {
        com.gamma.util.DuckDbUtil.loadDriver();
        Path db = Path.of("database", id).toAbsolutePath();
        List<String> out = new ArrayList<>();
        try (java.sql.Connection conn = com.gamma.util.DuckDbUtil.openInMemory(null, List.of(db), null);
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT CAST(" + a + " AS VARCHAR), CAST(" + b + " AS VARCHAR) FROM read_parquet('"
                     + db.toString().replace('\\', '/') + "/database/**/*.parquet') ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1) + "|" + rs.getString(2));
        }
        return out;
    }

    /**
     * No quote found in a file that holds no {@code "}: the engine's default quote reads it exactly as no quote does, so
     * none is written - a later file of the import may quote its fields ({@code ControlApiDecimalColumnTest}).
     */
    @Test
    void aQuoteFreeFileKeepsTheEngineDefaultQuote(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = addData(c, "plain.csv", "id,name\n1,Ann\n2,Bob\n");
            Map<String, Object> csv = csvSettings(c, id);
            assertFalse(csv.containsKey("quote"), csv.toString());
            assertFalse(csv.containsKey("skip_header_lines"), csv.toString());
            assertEquals(List.of("1|Ann", "2|Bob"), rows(id, "id", "name"));
        }
    }

    @Test
    void aStrayQuoteIsImportedAsText(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = addData(c, "stray.csv", "a,b\n1,\"x\n2,3\n4,5\n");
            assertEquals("", csvSettings(c, id).get("quote"));
            assertEquals(List.of("1|\"x", "2|3", "4|5"), rows(id, "a", "b"));
        }
    }

    @Test
    void theLinesTheSniffSkipsAreSkippedByTheImport(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = addData(c, "skip.csv", "# exported\na,b\n1,2\n3,4\n");
            assertEquals(List.of("1|2", "3|4"), rows(id, "a", "b"));
            assertEquals(1, ((Number) csvSettings(c, id).get("skip_header_lines")).intValue());
        }
    }

    @Test
    void aQuoteTheSniffFoundIsWritten(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = addData(c, "quoted.csv", "a,b\n1,\"Smith, Ann\"\n2,\"Lee, Bob\"\n");
            assertEquals("\"", csvSettings(c, id).get("quote"));
            assertEquals(List.of("1|Smith, Ann", "2|Lee, Bob"), rows(id, "a", "b"));
        }
    }
}
