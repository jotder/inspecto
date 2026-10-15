package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.gamma.control.EntityProfileFixture.A;
import static com.gamma.control.EntityProfileFixture.JSON;
import static com.gamma.control.EntityProfileFixture.OWNER;
import static com.gamma.control.EntityProfileFixture.ROWS;
import static com.gamma.control.EntityProfileFixture.STRANGER;
import static com.gamma.control.EntityProfileFixture.data;
import static com.gamma.control.EntityProfileFixture.enc;
import static com.gamma.control.EntityProfileFixture.get;
import static com.gamma.control.EntityProfileFixture.noRaw;
import static com.gamma.control.EntityProfileFixture.open;
import static com.gamma.control.EntityProfileFixture.send;
import static com.gamma.control.EntityProfileFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan D-EP4 "index first" over REAL HTTP: once a fresh index covers the type's one link mapping, the Links sections of
 * {@code GET /inv/entity-types/{id}/profile} and {@code GET /inv/entities/{type}/{key}/profile} answer {@code from:"index"} with
 * the SAME numbers the Dataset fallback gives (the build keys and folds links as the fallback does); with no index, or a stale
 * one, the Dataset answers.
 */
class ControlApiEntityProfileIndexLinksTest {

    private static final String TYPE_LINKS = "/inv/entity-types/msisdn/profile?sections=links";
    /** A's Entity page path: msisdn is masked, so the key is resolved through the body first, never put raw in a URL. */
    private static String entityA(EntityProfileFixture.Ctx c) throws Exception {
        String key = noRaw(send(c, "POST", "/inv/entities/msisdn/resolve", "{\"key\":\"" + A + "\"}", OWNER)).get("key").asText();
        return "/inv/entities/msisdn/" + enc(key) + "/profile";
    }
    private static final String BUILD = "{\"dataset\":\"calls_ds\",\"sourceCol\":\"a_msisdn\",\"targetCol\":\"b_msisdn\",\"kindCol\":\"kind\"}";

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        InputFingerprintCache.forTest(null, 0);
    }

    /** The fixture's subjects, the owner also holding {@code canBuildLinkIndex}. */
    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex")));
            case STRANGER -> Optional.of(new Subject("analyst-3", Set.of()));
            default -> Optional.empty();
        });
    }

    private static void buildIndex(EntityProfileFixture.Ctx c) throws Exception {
        HttpResponse<String> r = send(c, "POST", "/inv/index/builds", BUILD, OWNER);
        assertEquals(202, r.statusCode(), r.body());
        String id = JSON.readTree(r.body()).at("/data/buildId").asText();
        long end = System.nanoTime() + 30_000_000_000L;
        String status;
        do {
            status = data(get(c, "/inv/index/builds/" + id, OWNER)).get("status").asText();
            if (System.nanoTime() > end) throw new AssertionError("build " + id + " is still " + status);
            Thread.sleep(10);
        } while (!status.equals("COMPLETED") && !status.equals("FAILED") && !status.equals("CANCELLED"));
        assertEquals("COMPLETED", status);
    }

    /** The section minus what names its origin, to compare an index answer with a Dataset answer. */
    private static JsonNode numbers(JsonNode links) {
        ObjectNode n = links.deepCopy();
        n.remove(List.of("from", "indexVersion"));
        return n;
    }

    @Test
    void aFreshIndexAnswersTheTypesAndTheEntitysLinksWithTheDatasetsNumbers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            settings(c, "hub_threshold: 3\n");                               // so hubs is not trivially zero: A has 4 neighbours
            JsonNode flatType = noRaw(get(c, TYPE_LINKS, OWNER)).get("links");
            JsonNode flatA = noRaw(get(c, entityA(c), OWNER)).get("links");
            assertEquals("dataset", flatType.get("from").asText(), "no index yet: " + flatType);
            assertEquals("dataset", flatA.get("from").asText());

            buildIndex(c);
            JsonNode type = noRaw(get(c, TYPE_LINKS, OWNER)).get("links");
            assertEquals("index", type.get("from").asText(), type.toString());
            assertEquals(numbers(flatType), numbers(type), "the index answers what the Dataset answers");
            assertEquals(4, type.at("/kinds/0/edges").asLong(), "call");
            assertEquals(1, type.get("hubs").asLong());
            assertEquals(2, type.get("singleLink").asLong(), "D and 99");

            JsonNode a = noRaw(get(c, entityA(c), OWNER)).get("links");
            assertEquals("index", a.get("from").asText(), a.toString());
            assertEquals(numbers(flatA), numbers(a));
            assertEquals(4, a.get("degree").asLong(), "B, C, D and 99 - the raw -99 is keyed 99, as the fallback keys it");
            assertEquals(90, a.get("percentile").asInt());
            assertEquals(3, a.at("/kinds/0/count").asLong());
        }
    }

    @Test
    void aStaleIndexIsNotReadAndTheDatasetAnswers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            buildIndex(c);
            assertEquals("index", noRaw(get(c, TYPE_LINKS, OWNER)).at("/links/from").asText());

            // the Dataset's relation changes under the index: its answer is no longer the Dataset's
            new ViewStore(root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT a_msisdn, b_msisdn, imei, kind, ts FROM (VALUES " + ROWS + ") AS t(a_msisdn,b_msisdn,imei,kind,ts) WHERE kind = 'call'",
                    "2026-10-15T01:00:00Z"));
            JsonNode type = noRaw(get(c, TYPE_LINKS, OWNER)).get("links");
            assertEquals("dataset", type.get("from").asText(), type.toString());
            assertEquals(1, type.get("kinds").size(), "the Dataset now holds calls only");
            assertEquals("dataset", noRaw(get(c, entityA(c), OWNER)).at("/links/from").asText());
        }
    }

    @Test
    void anIndexOverAnotherKindColumnDoesNotCoverTheMappingAndTheDatasetAnswers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            // no kind column: the index is a SECOND link mapping (a, b, no kind) beside the saved view's (a, b, kind)
            HttpResponse<String> r = send(c, "POST", "/inv/index/builds",
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"a_msisdn\",\"targetCol\":\"b_msisdn\"}", OWNER);
            assertEquals(202, r.statusCode(), r.body());
            String id = JSON.readTree(r.body()).at("/data/buildId").asText();
            long end = System.nanoTime() + 30_000_000_000L;
            while (!"COMPLETED".equals(data(get(c, "/inv/index/builds/" + id, OWNER)).get("status").asText())) {
                if (System.nanoTime() > end) throw new AssertionError("build did not complete");
                Thread.sleep(10);
            }
            JsonNode type = noRaw(get(c, TYPE_LINKS, OWNER)).get("links");
            assertEquals("dataset", type.get("from").asText(), type.toString());
            assertTrue(type.get("assessed").asBoolean());
        }
    }
}
