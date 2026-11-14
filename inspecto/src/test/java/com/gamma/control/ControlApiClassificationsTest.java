package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.entitystore.EntityTypes.EntityType;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.ClassificationVocabulary;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /classifications} (UI-CLASSIFICATION-PICKER-1) over real HTTP: the Space's column classification
 * vocabulary, what the classification picker offers. A read, so no write-root or capability gate: a caller with no
 * credential is 401, any signed-in Subject (here one holding no capability at all) reads it. The list is the one the
 * save gate judges by ({@link ClassificationVocabulary#known}), sorted, with whether each class masks.
 */
class ControlApiClassificationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> "Bearer nobody".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("nobody-1", Set.of()))
                : Optional.empty());
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private static Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(Ctx c, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api.port() + "/api/v1/classifications"));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode items(Ctx c) throws Exception {
        HttpResponse<String> r = get(c, "Bearer nobody");
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data").get("items");
    }

    private static JsonNode item(JsonNode items, String cls) {
        for (JsonNode i : items) if (cls.equals(i.get("class").asText())) return i;
        return null;
    }

    @Test
    void noCredentialIs401AndASubjectHoldingNoCapabilityReads(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, get(c, null).statusCode(), "the read is not public");
            assertEquals(401, get(c, "Bearer stranger").statusCode(), "an unknown credential is no Subject");
            assertEquals(200, get(c, "Bearer nobody").statusCode(), "a read is not capability-gated: any Subject of the Space reads it");
        }
    }

    /** The Space's own Entity Types: their ids and claims join the vocabulary, masked when the type is masked in force. */
    @Test
    void theSpacesEntityTypesJoinTheVocabularySortedWithWhetherEachClassMasks(@TempDir Path cfg, @TempDir Path root) throws Exception {
        new LinkAnalysisSettings(null, null, null, null, null, null,
                List.of(new EntityType("badge", "Staff badge", "upper-trim", true, List.of("BADGE_NO")),
                        new EntityType("desk", "Desk", "upper-trim", false, List.of(" desk_code ", "pii")),
                        new EntityType("room", "Room", "upper-trim", false, List.of("ROOM_NO"))),
                null, null, null, null, null, null, null, null).write(root.resolve(LinkAnalysisSettings.FILE));
        try (Ctx c = open(cfg, root)) {
            JsonNode items = items(c);
            List<String> classes = new ArrayList<>();
            items.forEach(i -> classes.add(i.get("class").asText()));
            assertEquals(List.of("ACCOUNT", "BADGE", "BADGE_NO", "DESK", "DESK_CODE", "IMSI", "INTERNAL", "MSISDN", "PII",
                            "ROOM", "ROOM_NO"), classes,
                    "SENSITIVE, INTERNAL, each type's id and claims, normalised, once each, sorted");

            assertEquals("{\"class\":\"MSISDN\",\"masked\":true,\"source\":\"sensitive\"}", item(items, "MSISDN").toString());
            assertEquals("{\"class\":\"INTERNAL\",\"masked\":false,\"source\":\"not-masked\"}", item(items, "INTERNAL").toString());
            assertEquals("{\"class\":\"BADGE_NO\",\"masked\":true,\"source\":\"entity-type\",\"entityType\":\"badge\",\"label\":\"Staff badge\"}",
                    item(items, "BADGE_NO").toString(), "a class a masked type claims masks");
            assertTrue(item(items, "BADGE").get("masked").asBoolean(), "the id of a masked type masks");
            assertFalse(item(items, "ROOM_NO").get("masked").asBoolean(), "a class only an unmasked type claims does not");
            assertFalse(item(items, "ROOM").get("masked").asBoolean());
            // MASKING-TWO-VOCABULARIES-1: desk claims PII, so it is masked in force whatever its flag says, and so are its id
            // and its other classes (EntityTypes.maskedInForce)
            assertTrue(item(items, "DESK_CODE").get("masked").asBoolean(), "a type claiming a SENSITIVE class masks its other classes");
            assertTrue(item(items, "DESK").get("masked").asBoolean());
            JsonNode pii = item(items, "PII");
            assertTrue(pii.get("masked").asBoolean(), "an unmasked type claiming a SENSITIVE class never unmasks it");
            assertEquals("sensitive", pii.get("source").asText());
            assertEquals("desk", pii.get("entityType").asText(), "the type that claims it is still named");

            // the route and the save gate are one list: every class it serves is known, a class it omits is refused
            List<EntityType> types = ClassificationVocabulary.typesOf(root);
            for (String cls : classes) assertTrue(ClassificationVocabulary.known(cls.toLowerCase(), types), cls);
            assertFalse(ClassificationVocabulary.known("CONFIDENTIAL", types));
            assertFalse(ClassificationVocabulary.known("SUBSCRIBER", types), "a stated list replaces the built-in types");
            assertNull(item(items, "SUBSCRIBER"));
        }
    }

    @Test
    void aSpaceThatStatesNoTypesServesTheBuiltInOnes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode items = items(c);
            JsonNode subscriber = item(items, "SUBSCRIBER");
            assertNotNull(subscriber, items.toString());
            assertTrue(subscriber.get("masked").asBoolean());
            assertEquals("subscriber", subscriber.get("entityType").asText());
            String previous = "";
            for (JsonNode i : items) {
                String cls = i.get("class").asText();
                assertTrue(previous.compareTo(cls) < 0, "sorted and unique: " + previous + " then " + cls);
                previous = cls;
            }
            assertEquals(ClassificationVocabulary.vocabulary(ClassificationVocabulary.typesOf(root)).size(), items.size());
        }
    }
}
