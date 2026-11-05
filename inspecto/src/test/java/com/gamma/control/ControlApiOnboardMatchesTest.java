package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ONBOARDING-FIRST-RUN-1 C6a over real HTTP: the Add data draft answers {@code matches}, the columns the new file shares
 * with Datasets already in the Space and how much of each matches ("`store_id` matches 98% of `sales.store_id`"), for
 * *Use as a lookup*. Counts only; never over a column masked for the caller. Synthetic fixtures; the Datasets read views of
 * VALUES, so no import runs.
 */
class ControlApiOnboardMatchesTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final java.util.List<String> committed = new java.util.ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Authenticators.forTest(null);
        Path data = Path.of("database").toAbsolutePath();
        for (String id : committed) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        try {
            Files.deleteIfExists(data.resolve("inbox"));
        } catch (java.io.IOException notEmpty) {
            // another fixture's inbox
        }
        try {
            Files.deleteIfExists(data);
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

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (user != null) b.header("Authorization", "Bearer " + user);
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode draft(Ctx c, String name, String kind, String csv, String user) throws Exception {
        HttpResponse<String> r = send(c, "POST", "/onboard/files?name=" + name + "&kind=" + kind, csv, user);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    /** A Dataset over a view of VALUES: {@code id} with {@code column} holding {@code keys} (one row each, plus a repeat). */
    private static void dataset(Ctx c, String id, String column, String classification, List<String> keys) throws Exception {
        StringBuilder values = new StringBuilder();
        for (String k : keys) values.append(values.isEmpty() ? "" : ",").append("('").append(k).append("', 1)");
        values.append(",('").append(keys.get(0)).append("', 2)");   // a repeated key counts once
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition(id + "_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES " + values + ") AS t(" + column + ", qty)", "2026-11-05T00:00:00Z"));
        Map<String, Object> col = classification == null ? Map.of("name", column, "type", "string")
                : Map.of("name", column, "type", "string", "classification", classification);
        new ComponentStore(c.root.resolve("registry")).write("dataset", id, Map.of("view", id + "_view",
                "columns", List.of(col, Map.of("name", "qty", "type", "number"))));
    }

    private static String stores(String... ids) {
        StringBuilder sb = new StringBuilder("store_id,store_name,region\n");
        for (String id : ids) sb.append(id).append(",Store ").append(id).append(",North\n");
        return sb.toString();
    }

    private static JsonNode only(JsonNode matches, String dataset) {
        JsonNode found = null;
        for (JsonNode m : matches) if (m.get("dataset").asText().equals(dataset)) {
            assertNull(found, "one match per Dataset column: " + matches);
            found = m;
        }
        return found;
    }

    @Test
    void aSharedColumnIsMatchedWithTheShareOfTheDatasetsKeysFound(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            dataset(c, "sales", "store_id", null, List.of("S01", "S02", "S03", "S04", "S05"));
            dataset(c, "visits", "visitor", null, List.of("V1", "V2"));   // shares no column with the file

            // every store of `sales` is in the file (one more is not used yet): 100%, matched by the column's name
            JsonNode m = only(draft(c, "stores.csv", "reference", stores("S01", "S02", "S03", "S04", "S05", "S06"), null)
                    .get("matches"), "sales");
            assertNotNull(m);
            assertEquals("store_id", m.get("column").asText());
            assertEquals("store_id", m.get("datasetColumn").asText());
            assertEquals(1.0, m.get("share").asDouble());
            assertEquals(5, m.get("keys").asLong());
            assertEquals(5, m.get("found").asLong());
            assertEquals("name", m.get("by").asText());

            // two of the five: 40%; the name matches whatever its case
            JsonNode part = only(draft(c, "few.csv", "reference", stores("S01", "S02", "S09").replace("store_id", "STORE_ID"), null)
                    .get("matches"), "sales");
            assertEquals(0.4, part.get("share").asDouble());
            assertEquals(2, part.get("found").asLong());

            // no key in common: a shared name alone is not a match
            assertNull(only(draft(c, "other.csv", "reference", stores("X1", "X2"), null).get("matches"), "sales"));
            assertNull(only(draft(c, "stores2.csv", "reference", stores("S01"), null).get("matches"), "visits"));
        }
    }

    @Test
    void aColumnHoldingTheSameEntityTypeMatchesUnderAnotherName(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            StringBuilder sb = new StringBuilder("phone,plan\n");
            for (int i = 0; i < 240; i++) sb.append("+3361234").append(String.format("%04d", i)).append(",basic\n");
            // what the draft proposes the phone column holds is the Entity Type the Dataset column is classified as
            JsonNode first = draft(c, "plans.csv", "reference", sb.toString(), null);
            String type = null, classification = null;
            for (JsonNode r : first.get("entityTypes").get("columns"))
                if (r.get("column").asText().equals("phone") && r.path("entityType").path("value").isTextual()) {
                    type = r.get("entityType").get("value").asText();
                    classification = r.get("entityType").path("classification").asText(null);
                }
            assertNotNull(type, "a phone column is proposed as an Entity Type: " + first.get("entityTypes"));
            // the Dataset column carries the type's CLASSIFICATION, as an import writes it - not the type's id
            assertNotNull(classification);
            assertNotEquals(type, classification, "the fixture must tell the classification from the id");
            dataset(c, "calls", "caller", classification, List.of("+33612340001", "+33612340002", "+33699999999", "+33612340003"));

            JsonNode m = only(draft(c, "plans.csv", "reference", sb.toString(), null).get("matches"), "calls");
            assertNotNull(m);
            assertEquals("phone", m.get("column").asText());
            assertEquals("caller", m.get("datasetColumn").asText());
            assertEquals("entityType", m.get("by").asText());
            assertEquals(type, m.get("entityType").asText(), "the match names the Entity Type id the commit accepts");
            assertTrue(m.get("entityTypeLabel").isTextual(), m.toString());
            assertEquals(0.75, m.get("share").asDouble());

            // Use as a lookup commits the file as details identified by that column, holding that Entity Type
            String id = "plans_" + java.util.UUID.randomUUID().toString().substring(0, 8);
            committed.add(id);
            JsonNode ref = draft(c, "plans.csv", "reference", sb.toString(), null);
            HttpResponse<String> made = send(c, "POST", "/onboard/files/" + ref.get("draftId").asText() + "/commit",
                    "{\"name\":\"" + id + "\",\"identifyingColumn\":\"phone\",\"primaryEntityType\":\"" + type
                            + "\",\"columns\":[{\"name\":\"phone\",\"entityType\":\"" + type + "\"}]}", null);
            assertEquals(200, made.statusCode(), made.body());
        }
    }

    @Test
    void noCountIsGivenOverAColumnMaskedForTheCaller(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer admin" -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canUploadData")));
            case "Bearer viewer" -> Optional.of(new Subject("viewer-1", Set.of("canUploadData")));
            case "Bearer revealer" -> Optional.of(new Subject("revealer-1", Set.of("canUploadData", BiResultMasking.REVEAL)));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c, "PUT", "/settings/data", "{\"selfImport\":true}", "admin").statusCode());
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
            dataset(c, "subs", "msisdn", "MSISDN", List.of("44170001", "44170002"));
            String file = "msisdn,plan\n44170001,basic\n44170002,gold\n";

            // a viewer who may not see the numbers learns nothing of which exist
            assertNull(only(draft(c, "subs.csv", "reference", file, "viewer").get("matches"), "subs"));
            // one who may see them gets the count
            assertEquals(1.0, only(draft(c, "subs.csv", "reference", file, "revealer").get("matches"), "subs").get("share").asDouble());
        }
    }
}
