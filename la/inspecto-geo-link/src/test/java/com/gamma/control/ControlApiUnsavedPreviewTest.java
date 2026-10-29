package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-UNSAVED-INVESTIGATION-1 over real HTTP: {@code POST /inv/expand/preview}, the unsaved Investigation's dry run. Its
 * Working Set equals the one the same steps record in a created Investigation (PARITY, incl. the hub threshold, link
 * kinds, direction and a truncating budget), it stores nothing, it stops at a four-eyes-sensitive expand, it speaks the
 * Space's aliases in and out, it is read-shaped (any authenticated Subject) and refused on Basic; every gate is exercised.
 *
 * <p>Fixture over {@code (caller, callee, channel)}: {@code A1-HUB9}, {@code A1-B2}, {@code HUB9-X1..X5} (voice) and
 * {@code B2-C3} (sms). Synthetic values only.
 */
class ControlApiUnsavedPreviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('A1','HUB9','voice')", "('A1','B2','voice')", "('HUB9','X1','voice')", "('HUB9','X2','voice')", "('HUB9','X3','voice')",
            "('HUB9','X4','voice')", "('HUB9','X5','voice')", "('B2','C3','sms')");
    private static final String BINDING = "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void restore() {
        Authenticators.forTest(null);
        EditionFeatures.overrideForTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)", "2026-10-22T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(Ctx c, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return post(c, path, body, null);
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode preview(Ctx c, String steps) throws Exception {
        return data(post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":" + steps + "}"), 200);
    }

    private static Set<String> ids(JsonNode ws) {
        Set<String> out = new TreeSet<>();
        for (JsonNode e : ws.get("entities")) out.add(e.get("id").asText());
        return out;
    }

    private static Set<String> links(JsonNode ws) {
        Set<String> out = new TreeSet<>();
        for (JsonNode l : ws.get("links")) out.add(l.get("source").asText() + ">" + l.get("target").asText() + ":" + l.get("kind").asText());
        return out;
    }

    /** Parity: the preview of {@code steps} and the Working Set the same steps record in a created Investigation. */
    private void parity(Ctx c, String id, List<String> steps) throws Exception {
        JsonNode p = preview(c, "[" + String.join(",", steps) + "]");
        data(post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\"," + BINDING + "}"), 200);
        for (String s : steps) data(post(c, "/inv/investigations/" + id + "/ops", s), 200);
        JsonNode ws = data(post(c, "/inv/investigations/" + id + "/replay", "{}"), 200).get("workingSet");
        assertEquals(ids(ws), ids(p.get("workingSet")), "nodes: the preview equals what the recorded steps admit");
        assertEquals(links(ws), links(p.get("workingSet")), "links: the preview equals what the recorded steps admit");
    }

    @Test
    void theWorkingSetEqualsWhatTheSameStepsRecord(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            String seed = "{\"op\":\"seed\",\"ids\":[\"A1\"]}";
            parity(c, "p1", List.of(seed, "{\"op\":\"expand\",\"ids\":[\"A1\"],\"hubThreshold\":3}", "{\"op\":\"expand\",\"ids\":[\"B2\"]}"));
            parity(c, "p2", List.of(seed, "{\"op\":\"expand\",\"ids\":[\"A1\"],\"direction\":\"out\",\"linkKinds\":[\"voice\"],\"budget\":1}"));
            parity(c, "p3", List.of(seed, "{\"op\":\"expand\",\"ids\":[\"A1\"],\"hubThreshold\":10}", "{\"op\":\"expand\",\"ids\":[\"HUB9\"],\"maxFanOut\":2}"));
            JsonNode p = preview(c, "[" + seed + ",{\"op\":\"expand\",\"ids\":[\"A1\"],\"hubThreshold\":3}]");
            assertEquals("[\"A1\"]", p.at("/steps/0/admitted").toString());
            assertEquals(Set.of("B2", "HUB9"), new TreeSet<>(List.of(p.at("/steps/1/admitted/0").asText(), p.at("/steps/1/admitted/1").asText())));
            assertFalse(Files.exists(root.resolve("audit/snapshots/investigations/unsaved")), "nothing is stored");
        }
    }

    @Test
    void aSensitiveExpandStopsTheWalkUnread(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\nfour_eyes_budget_above: 100\n")) {
            JsonNode p = preview(c, "[{\"op\":\"seed\",\"ids\":[\"A1\"]},{\"op\":\"expand\",\"ids\":[\"A1\"],\"budget\":500},{\"op\":\"expand\"}]");
            assertTrue(p.at("/steps/1/needsApproval").asBoolean(), p.toString());
            assertEquals("[\"budget 500 > 100\"]", p.at("/steps/1/exceeded").toString());
            assertEquals(2, p.get("steps").size(), "the walk stops at the held step");
            assertEquals(Set.of("A1"), ids(p.get("workingSet")), "nothing was read for it");
        }
    }

    @Test
    void aMaskedSpaceSpeaksItsAliasesInAndOut(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            HttpResponse<String> raw = post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"A1\"]},"
                    + "{\"op\":\"expand\",\"ids\":[\"A1\"],\"hubThreshold\":3}]}");
            JsonNode p = data(raw, 200);
            assertTrue(p.get("masked").asBoolean());
            for (String forbidden : List.of("HUB9", "B2", "\"A1\"")) assertFalse(raw.body().contains(forbidden), raw.body());
            String alias = p.at("/steps/1/admitted/0").asText();
            assertTrue(alias.startsWith("masked:"), alias);
            // an alias it served is taken back in: expand from it
            JsonNode next = preview(c, "[{\"op\":\"seed\",\"ids\":[\"A1\"]},{\"op\":\"expand\",\"ids\":[\"A1\"],\"hubThreshold\":3},"
                    + "{\"op\":\"expand\",\"ids\":[\"" + alias + "\"],\"expandHubs\":[\"" + alias + "\"]}]");
            assertFalse(next.at("/steps/2/needsApproval").asBoolean(), next.toString());
            assertEquals(422, post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"masked:0000000000000000\"]}]}")
                    .statusCode(), "an alias this server never served is refused");
        }
    }

    @Test
    void everyGate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            String ok = "[{\"op\":\"seed\",\"ids\":[\"A1\"]}]";
            assertEquals(422, post(c, "/inv/expand/preview", "{\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"steps\":" + ok + "}").statusCode(), "no dataset");
            assertEquals(422, post(c, "/inv/expand/preview", "{" + BINDING + "}").statusCode(), "no steps");
            assertEquals(422, post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"exclude\",\"ids\":[\"A1\"],\"reason\":\"r\"}]}").statusCode(),
                    "only seed / expand");
            assertEquals(422, post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"A1\"]},{\"op\":\"expand\",\"ids\":[\"C3\"]}]}")
                    .statusCode(), "an expand from an entity not in the Working Set, as the append refuses it");
            assertEquals(422, post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"A1\"]},{\"op\":\"expand\",\"direction\":\"sideways\"}]}")
                    .statusCode(), "the append's validation");
            assertEquals(422, post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"A1\"]},"
                    + "{\"op\":\"expand\",\"minDistinctDays\":2}]}").statusCode(), "a window needs a time column, as the append");
            assertEquals(422, post(c, "/inv/expand/preview", "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"nope\",\"steps\":" + ok + "}")
                    .statusCode(), "an unknown column");
            assertEquals(422, post(c, "/inv/expand/preview", "{\"dataset\":\"calls_ds\",\"sourceCol\":\"cal ler\",\"targetCol\":\"callee\",\"steps\":" + ok + "}")
                    .statusCode(), "an unsafe identifier");
            assertEquals(404, post(c, "/inv/expand/preview", "{\"dataset\":\"nope\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"steps\":" + ok + "}")
                    .statusCode(), "an unknown Dataset");
        }
    }

    @Test
    void readShapedForAnyAuthenticatedSubjectAndRefusedWithoutOne(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer plain" -> Optional.of(new Subject("explorer-1", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            String body = "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"A1\"]}]}";
            assertEquals(401, post(c, "/inv/expand/preview", body).statusCode(), "no credential");
            assertEquals(200, post(c, "/inv/expand/preview", body, "Bearer plain").statusCode(),
                    "no capability needed: read-shaped, like the explorer's neighbours reads");
        }
    }

    @Test
    void refusedOnBasic(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            assertEquals(503, post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"A1\"]}]}").statusCode());
        }
    }
}
