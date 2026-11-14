package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.spec.FindingCodes;
import com.gamma.entitystore.EntityTypes.EntityType;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLASSIFICATION-UNMASKED-CLASS-1 over real HTTP: every door that saves a column classification refuses one the Space
 * does not know (422, {@code ERR_UNKNOWN_CLASSIFICATION}, nothing written) and saves one it does. The doors:
 * {@code POST /config/write} and {@code POST /config/patch} (through {@link SaveGate}) for a {@code schema} and a
 * {@code pipeline}'s {@code sql} Step rows; {@code POST}/{@code PUT /components/{dataset|schema}}
 * ({@link ComponentRoutes#validateKind}); {@code PUT /datasets/{id}/columns/{column}} ({@link OnboardImport#setColumn},
 * which the same {@code validateKind} judges). A component keeps an unknown class it already stores; only one a save
 * introduces is refused. The vocabulary itself is unit-tested in {@link ClassificationVocabularyTest}.
 */
class ControlApiClassificationVocabularyTest {

    private static final String CODE = FindingCodes.ERR_UNKNOWN_CLASSIFICATION;

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

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        return client.send(b.method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** The {@code ERR_UNKNOWN_CLASSIFICATION} finding at {@code fieldPath} in a refused save, or null. */
    private static JsonNode finding(String body, String fieldPath) throws Exception {
        for (JsonNode f : V1Body.envelope(body).path("error").path("details").path("findings"))
            if (CODE.equals(f.path("code").asText()) && fieldPath.equals(f.path("fieldPath").asText())) return f;
        return null;
    }

    private static String schemaDraft(String name, String cellClass) {
        return """
                {"type":"schema","config":{"raw":{"name":"%s","format":"CSV","fields":%s}}}""".formatted(name, fields(cellClass));
    }

    private static String fields(String cellClass) {
        return """
                [{"name":"A_NUMBER","selector":"0","type":"VARCHAR","classification":"MSISDN"},
                 {"name":"CELL","selector":"1","type":"VARCHAR","classification":"%s"}]""".formatted(cellClass);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rawFields(Path schemaFile) throws Exception {
        // a schema is split storage: its fields live in the sibling _structure.csv, merged back here as a read does
        Map<String, Object> stored = ConfigFileSupport.storedContent(schemaFile, "schema");
        return (List<Map<String, Object>>) ((Map<String, Object>) stored.get("raw")).get("fields");
    }

    // ── POST /config/write {type: schema} ────────────────────────────────────────────────────

    @Test
    void configWriteRefusesASchemaFieldOfAnUnknownClassAndSavesAKnownOne(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> refused = send(c.port, "POST", "/config/write", schemaDraft("cv_calls_schema", "CONFIDENTIAL"));
            assertEquals(422, refused.statusCode(), refused.body());
            JsonNode f = finding(refused.body(), "raw.fields[1].classification");
            assertNotNull(f, refused.body());
            assertTrue(f.path("message").asText().contains("'CELL'"), "the refusal names the column: " + f);
            assertFalse(Files.exists(root.resolve("cv_calls_schema.toon")), "a refused write persists nothing");
            assertFalse(Files.exists(root.resolve("cv_calls_structure.csv")), "nor the sibling that holds the fields");

            HttpResponse<String> saved = send(c.port, "POST", "/config/write", schemaDraft("cv_calls_schema", "INTERNAL"));
            assertEquals(200, saved.statusCode(), saved.body());
            List<Map<String, Object>> stored = rawFields(root.resolve("cv_calls_schema.toon"));
            assertEquals("MSISDN", stored.get(0).get("classification"));
            assertEquals("INTERNAL", stored.get(1).get("classification"), "the stated 'needs no masking' is written as given");
        }
    }

    // ── POST /config/patch {type: schema} ────────────────────────────────────────────────────

    @Test
    void configPatchRefusesASchemaFieldOfAnUnknownClassAndLeavesTheFileAsItWas(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/config/write", schemaDraft("cv_calls_schema", "INTERNAL")).statusCode());
            Path file = root.resolve("cv_calls_schema.toon");
            List<Map<String, Object>> before = rawFields(file);
            assertEquals("INTERNAL", before.get(1).get("classification"));
            String patch = """
                    {"type":"schema","name":"cv_calls_schema","patch":{"raw":{"fields":%s}}}""";

            HttpResponse<String> refused = send(c.port, "POST", "/config/patch", patch.formatted(fields("CONFIDENTIAL")));
            assertEquals(422, refused.statusCode(), refused.body());
            assertNotNull(finding(refused.body(), "raw.fields[1].classification"), refused.body());
            assertEquals(before, rawFields(file), "a refused patch leaves the stored fields as they were");

            HttpResponse<String> saved = send(c.port, "POST", "/config/patch", patch.formatted(fields("CELL_ID")));
            assertEquals(200, saved.statusCode(), saved.body());
            assertEquals("CELL_ID", rawFields(file).get(1).get("classification"), "a class an Entity Type claims is known");
        }
    }

    // ── POST /config/write {type: pipeline}: a sql Step's output columns ─────────────────────────

    private static String pipelineDraft(String msisdnClass) {
        return """
                {"type":"pipeline","config":{
                   "name":"orders",
                   "dirs":{"poll":"in","database":"out"},
                   "steps":[{"sql":{"sql":"SELECT ID AS ID, MSISDN AS MSISDN FROM input","fields":[
                      {"id":"a","name":"ID","from":"ID","fn":"keep","args":{}},
                      {"id":"b","name":"MSISDN","from":"MSISDN","fn":"keep","args":{},"classification":"%s"}]}}]}}"""
                .formatted(msisdnClass);
    }

    @Test
    void configWriteRefusesAStepOutputColumnOfAnUnknownClassAndSavesAKnownOne(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> refused = send(c.port, "POST", "/config/write", pipelineDraft("CONFIDENTIAL"));
            assertEquals(422, refused.statusCode(), refused.body());
            assertNotNull(finding(refused.body(), "steps[0].sql.fields[1].classification"), refused.body());
            assertFalse(Files.exists(root.resolve("orders_pipeline.toon")), "a refused write persists nothing");

            HttpResponse<String> saved = send(c.port, "POST", "/config/write", pipelineDraft("msisdn"));
            assertEquals(200, saved.statusCode(), saved.body());
            assertTrue(Files.exists(root.resolve("orders_pipeline.toon")));
        }
    }

    // ── /components/dataset ──────────────────────────────────────────────────────────────

    private static String dataset(String id, String cellClass) {
        return (id == null ? "{" : "{\"id\":\"" + id + "\",") + "\"physicalRef\":\"calls/database\",\"columns\":["
                + "{\"name\":\"a_party\",\"type\":\"string\",\"classification\":\"MSISDN\"},"
                + "{\"name\":\"cell\",\"type\":\"string\",\"classification\":\"" + cellClass + "\"}]}";
    }

    @Test
    void aDatasetComponentOfAnUnknownClassIsRefusedOnCreateAndOnUpdate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Path file = root.resolve("registry/datasets/cv_calls.toon");
            HttpResponse<String> refused = send(c.port, "POST", "/components/dataset", dataset("cv_calls", "CONFIDENTIAL"));
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains(CODE) && refused.body().contains("'cell'"), refused.body());
            assertFalse(Files.exists(file), "a refused Dataset is not registered");

            HttpResponse<String> created = send(c.port, "POST", "/components/dataset", dataset("cv_calls", "INTERNAL"));
            assertEquals(200, created.statusCode(), created.body());
            String before = Files.readString(file);
            assertTrue(before.contains("INTERNAL"), before);

            HttpResponse<String> badUpdate = send(c.port, "PUT", "/components/dataset/cv_calls", dataset(null, "CONFIDENTIAL"));
            assertEquals(422, badUpdate.statusCode(), badUpdate.body());
            assertTrue(badUpdate.body().contains(CODE), badUpdate.body());
            assertEquals(before, Files.readString(file), "the gate is on the update too, and a refused one changes nothing");

            assertEquals(200, send(c.port, "PUT", "/components/dataset/cv_calls", dataset(null, "cell")).statusCode(),
                    "the id of an Entity Type is known");
        }
    }

    /** The vocabulary is the Space's: a class only its own Entity Type claims is refused until the Space states that type. */
    @Test
    void aClassOfTheSpacesOwnEntityTypeIsKnownOnceTheSpaceStatesIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> refused = send(c.port, "POST", "/components/dataset", dataset("cv_staff", "BADGE_NO"));
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains(CODE), refused.body());

            new LinkAnalysisSettings(null, null, null, null, null, null,
                    List.of(new EntityType("badge", "Staff badge", "upper-trim", true, List.of("BADGE_NO"))),
                    null, null, null, null, null, null, null, null).write(root.resolve(LinkAnalysisSettings.FILE));

            HttpResponse<String> saved = send(c.port, "POST", "/components/dataset", dataset("cv_staff", "BADGE_NO"));
            assertEquals(200, saved.statusCode(), saved.body());
            assertTrue(Files.exists(root.resolve("registry/datasets/cv_staff.toon")));
        }
    }

    // ── /components/schema ───────────────────────────────────────────────────────────────

    @Test
    void aSchemaComponentOfAnUnknownClassIsRefusedOnCreateAndOnUpdate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Path file = root.resolve("registry/schemas/cv_calls.toon");
            String body = "{%s\"raw\":{\"name\":\"cv_calls\",\"format\":\"CSV\",\"fields\":%s}}";
            HttpResponse<String> refused = send(c.port, "POST", "/components/schema",
                    body.formatted("\"id\":\"cv_calls\",", fields("CONFIDENTIAL")));
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains(CODE) && refused.body().contains("'CELL'"), refused.body());
            assertFalse(Files.exists(file), "a refused schema must not reach the file the engine parses");

            HttpResponse<String> created = send(c.port, "POST", "/components/schema",
                    body.formatted("\"id\":\"cv_calls\",", fields("INTERNAL")));
            assertEquals(200, created.statusCode(), created.body());
            String before = Files.readString(file);

            HttpResponse<String> badUpdate = send(c.port, "PUT", "/components/schema/cv_calls",
                    body.formatted("", fields("CONFIDENTIAL")));
            assertEquals(422, badUpdate.statusCode(), badUpdate.body());
            assertTrue(badUpdate.body().contains(CODE), badUpdate.body());
            assertEquals(before, Files.readString(file), "a refused update changes nothing");
        }
    }

    // ── PUT /datasets/{id}/columns/{column} ────────────────────────────────────────────────────

    @Test
    void aColumnCannotBeSetToAnUnknownClassOnAnExistingDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "calls", Map.of("physicalRef", "calls/database",
                    "columns", List.of(Map.of("name", "a_party", "type", "string"), Map.of("name", "cell", "type", "string"))));
            Path file = root.resolve("registry/datasets/calls.toon");
            String before = Files.readString(file);

            HttpResponse<String> refused = send(c.port, "PUT", "/datasets/calls/columns/cell", "{\"classification\":\"CONFIDENTIAL\"}");
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains(CODE) && refused.body().contains("'cell'"), refused.body());
            assertEquals(before, Files.readString(file), "a refused change leaves the Dataset as it was");

            HttpResponse<String> internal = send(c.port, "PUT", "/datasets/calls/columns/cell", "{\"classification\":\"INTERNAL\"}");
            assertEquals(200, internal.statusCode(), internal.body());
            assertEquals("INTERNAL", V1Body.of(internal.body()).get("classification").asText());
            HttpResponse<String> masked = send(c.port, "PUT", "/datasets/calls/columns/a_party", "{\"classification\":\"MSISDN\"}");
            assertEquals(200, masked.statusCode(), masked.body());
            String after = Files.readString(file);
            assertTrue(after.contains("MSISDN") && after.contains("INTERNAL"), after);
        }
    }

    // ── a class the stored component already carries ─────────────────────────────────────────────

    /** A Dataset as an older build (or a retired Entity Type) left it: the file planted in the registry, past every route. */
    private static final String LEGACY_DATASET = """
            physicalRef: calls/database
            columns[3]{name,type,classification}:
              a_party,string,MSISDN
              cell,string,CONFIDENTIAL
              amount,number,
            """;

    private static String legacyDataset(String description, String aPartyClass) {
        return "{\"physicalRef\":\"calls/database\",\"description\":\"" + description + "\",\"columns\":["
                + "{\"name\":\"a_party\",\"type\":\"string\",\"classification\":\"" + aPartyClass + "\"},"
                + "{\"name\":\"cell\",\"type\":\"string\",\"classification\":\"CONFIDENTIAL\"},"
                + "{\"name\":\"amount\",\"type\":\"number\"}]}";
    }

    /**
     * The gate refuses what a save INTRODUCES. A Dataset already holding an unknown class takes an edit that leaves it
     * there, through both of its doors, and is still refused a second unknown class; the tolerance is that Dataset's own.
     */
    @Test
    void aDatasetKeepsAnUnknownClassItAlreadyStoresAndIsRefusedANewOne(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Path file = Files.createDirectories(root.resolve("registry/datasets")).resolve("legacy.toon");
            Files.writeString(file, LEGACY_DATASET);

            HttpResponse<String> edited = send(c.port, "PUT", "/components/dataset/legacy", legacyDataset("call records", "MSISDN"));
            assertEquals(200, edited.statusCode(), edited.body());
            assertTrue(Files.readString(file).contains("call records") && Files.readString(file).contains("CONFIDENTIAL"),
                    Files.readString(file));
            HttpResponse<String> role = send(c.port, "PUT", "/datasets/legacy/columns/amount", "{\"role\":\"measure\",\"unit\":\"USD\"}");
            assertEquals(200, role.statusCode(), "a role and unit change on another column: " + role.body());
            String before = Files.readString(file);
            assertTrue(before.contains("USD") && before.contains("CONFIDENTIAL"), before);

            HttpResponse<String> second = send(c.port, "PUT", "/components/dataset/legacy", legacyDataset("call records", "SECRETISH"));
            assertEquals(422, second.statusCode(), second.body());
            assertTrue(second.body().contains(CODE) && second.body().contains("'SECRETISH'"), second.body());
            assertFalse(second.body().contains("'CONFIDENTIAL'"), "the class it already stores is not what is refused: " + second.body());
            HttpResponse<String> byColumn = send(c.port, "PUT", "/datasets/legacy/columns/a_party", "{\"classification\":\"SECRETISH\"}");
            assertEquals(422, byColumn.statusCode(), byColumn.body());
            assertTrue(byColumn.body().contains(CODE) && byColumn.body().contains("'SECRETISH'"), byColumn.body());
            assertEquals(before, Files.readString(file), "the refused edits changed nothing");

            HttpResponse<String> fresh = send(c.port, "POST", "/components/dataset", dataset("fresh", "CONFIDENTIAL"));
            assertEquals(422, fresh.statusCode(), "a new Dataset inherits nothing from the one that stores the class: " + fresh.body());
            assertFalse(Files.exists(root.resolve("registry/datasets/fresh.toon")));
        }
    }

    @Test
    void aSchemaComponentKeepsAnUnknownClassItAlreadyStoresAndIsRefusedANewOne(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Path file = Files.createDirectories(root.resolve("registry/schemas")).resolve("legacy.toon");
            Files.writeString(file, """
                    raw:
                      name: legacy
                      format: CSV
                      fields[2]{name,selector,type,classification}:
                        A_NUMBER,"0",VARCHAR,MSISDN
                        CELL,"1",VARCHAR,CONFIDENTIAL
                    """);
            String body = """
                    {"raw":{"name":"legacy","format":"CSV","fields":[
                       {"name":"A_NUMBER","selector":"0","type":"VARCHAR","classification":"%s","description":"Calling number"},
                       {"name":"CELL","selector":"1","type":"VARCHAR","classification":"CONFIDENTIAL"}]}}""";

            HttpResponse<String> edited = send(c.port, "PUT", "/components/schema/legacy", body.formatted("MSISDN"));
            assertEquals(200, edited.statusCode(), edited.body());
            String before = Files.readString(file);
            assertTrue(before.contains("Calling number") && before.contains("CONFIDENTIAL"), before);

            HttpResponse<String> second = send(c.port, "PUT", "/components/schema/legacy", body.formatted("SECRETISH"));
            assertEquals(422, second.statusCode(), second.body());
            assertTrue(second.body().contains(CODE) && second.body().contains("'SECRETISH'"), second.body());
            assertEquals(before, Files.readString(file), "the refused edit changed nothing");
        }
    }
}
