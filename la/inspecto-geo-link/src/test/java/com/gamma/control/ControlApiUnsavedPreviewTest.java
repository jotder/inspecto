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
            "('A1','HUB9','voice',TIMESTAMP '2026-09-01 10:00:00')", "('A1','B2','voice',TIMESTAMP '2026-09-05 10:00:00')",
            "('HUB9','X1','voice',TIMESTAMP '2026-09-01 11:00:00')", "('HUB9','X2','voice',TIMESTAMP '2026-09-01 12:00:00')",
            "('HUB9','X3','voice',TIMESTAMP '2026-09-02 10:00:00')", "('HUB9','X4','voice',TIMESTAMP '2026-09-06 10:00:00')",
            "('HUB9','X5','voice',TIMESTAMP '2026-09-06 11:00:00')", "('B2','C3','sms',TIMESTAMP '2026-09-07 10:00:00')");
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
                    "SELECT caller, callee, channel, ts FROM (VALUES " + ROWS + ") AS t(caller,callee,channel,ts)", "2026-10-22T00:00:00Z"));
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            store.write("dataset", "calls_ds", Map.of("view", "calls_view"));
            store.write("dataset", "private_ds", Map.of("view", "calls_view", "owner", "analyst-1", "shares", List.of()));
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
        return preview(c, BINDING, steps);
    }

    private JsonNode preview(Ctx c, String binding, String steps) throws Exception {
        return data(post(c, "/inv/expand/preview", "{" + binding + ",\"steps\":" + steps + "}"), 200);
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
        parity(c, id, BINDING, steps);
    }

    private void parity(Ctx c, String id, String binding, List<String> steps) throws Exception {
        JsonNode p = preview(c, binding, "[" + String.join(",", steps) + "]");
        assertTrue(p.get("workingSet").path("hash").isMissingNode(), "no hash: the Studio does not need one");
        data(post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\"," + binding + "}"), 200);
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
            String window = "\"window\":{\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-03T00:00:00Z\"}";
            parity(c, "p4", BINDING + ",\"timeCol\":\"ts\"", List.of("{\"op\":\"seed\",\"ids\":[\"HUB9\"]}",
                    "{\"op\":\"expand\",\"ids\":[\"HUB9\"],\"hubThreshold\":10," + window + "}"));
            parity(c, "p5", List.of("{\"op\":\"seed\",\"ids\":[\"HUB9\"]}", "{\"op\":\"expand\",\"ids\":[\"HUB9\"],\"hubThreshold\":10,\"only\":[\"X2\",\"X4\"]}"));
            JsonNode w = preview(c, BINDING + ",\"timeCol\":\"ts\"", "[{\"op\":\"seed\",\"ids\":[\"HUB9\"]},{\"op\":\"expand\",\"ids\":[\"HUB9\"],\"hubThreshold\":10," + window + "}]");
            assertEquals(Set.of("A1", "HUB9", "X1", "X2", "X3"), ids(w.get("workingSet")), "the window leaves X4 and X5 out");
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
    void aFanOutAboveTheFourEyesThresholdStopsTheWalkToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\nfour_eyes_fan_out_above: 2\n")) {
            JsonNode p = preview(c, "[{\"op\":\"seed\",\"ids\":[\"HUB9\"]},{\"op\":\"expand\",\"ids\":[\"HUB9\"],\"maxFanOut\":5}]");
            assertTrue(p.at("/steps/1/needsApproval").asBoolean(), p.toString());
            assertEquals("[\"maxFanOut 5 > 2\"]", p.at("/steps/1/exceeded").toString());
            assertFalse(preview(c, "[{\"op\":\"seed\",\"ids\":[\"HUB9\"]},{\"op\":\"expand\",\"ids\":[\"HUB9\"],\"maxFanOut\":2}]")
                    .at("/steps/1/needsApproval").asBoolean());
        }
    }

    /** The aliases a masked Space's explorer neighbours read served around {@code value}. */
    private List<String> aliasesNear(Ctx c, String value) throws Exception {
        String body = post(c, "/inv/projection/neighbors", "{" + BINDING + ",\"value\":\"" + value + "\"}").body();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("masked:[0-9a-f]{16}").matcher(body);
        Set<String> out = new TreeSet<>();
        while (m.find()) out.add(m.group());
        return List.copyOf(out);
    }

    private static long bookSize(Path root) throws Exception {
        Path f = com.gamma.util.SpaceSecretKeys.keyFile(root, ".exploration-alias-book");
        return Files.exists(f) ? Files.size(f) : 0;
    }

    @Test
    void aMaskedSpaceRefusesRawIdsAndTakesOnlyTheAliasesItServed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            HttpResponse<String> raw = post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"A1\"]}]}");
            assertEquals(422, raw.statusCode(), raw.body());
            assertTrue(raw.body().contains("takes only the aliases its reads served"), raw.body());
            List<String> near = aliasesNear(c, "A1");   // A1, HUB9 and B2 as the explorer served them
            assertEquals(3, near.size(), near.toString());
            String seeds = "[\"" + String.join("\",\"", near) + "\"]";
            HttpResponse<String> ok = post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":" + seeds
                    + "},{\"op\":\"expand\",\"ids\":" + seeds + ",\"hubThreshold\":10}]}");
            JsonNode p = data(ok, 200);
            assertTrue(p.get("masked").asBoolean());
            for (String forbidden : List.of("HUB9", "B2", "\"A1\"", "X1", "C3")) assertFalse(ok.body().contains(forbidden), ok.body());
            // parity on the masked Space: the same recorded steps admit as many entities and links
            data(post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"pm\"," + BINDING + "}"), 200);
            data(post(c, "/inv/investigations/pm/ops", "{\"op\":\"seed\",\"ids\":[\"A1\",\"HUB9\",\"B2\"]}"), 200);
            data(post(c, "/inv/investigations/pm/ops", "{\"op\":\"expand\",\"ids\":[\"A1\",\"HUB9\",\"B2\"],\"hubThreshold\":10}"), 200);
            JsonNode ws = data(post(c, "/inv/investigations/pm/replay", "{}"), 200).get("workingSet");
            assertEquals(ws.get("entities").size(), p.at("/workingSet/entities").size());
            assertEquals(ws.get("links").size(), p.at("/workingSet/links").size());
        }
    }

    @Test
    void guessingRawValuesOnAMaskedSpaceMintsNoAliasAndGrowsNoBook(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            aliasesNear(c, "A1");
            long book = bookSize(root);
            StringBuilder guesses = new StringBuilder();
            for (int i = 0; i < 500; i++) guesses.append(i == 0 ? "" : ",").append("\"G").append(i).append("\"");
            for (String ids : List.of("[" + guesses + "]", "[\"X1\"]", "[\"NOT-THERE\"]")) {
                HttpResponse<String> r = post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":" + ids + "}]}");
                assertEquals(422, r.statusCode(), r.body());
                assertFalse(r.body().contains("masked:"), "no alias comes back for a guess: " + r.body());
            }
            assertEquals(422, post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"masked:0000000000000000\"]}]}")
                    .statusCode(), "an alias this server never served is refused");
            assertEquals(book, bookSize(root), "no guess was written to the alias book");
        }
    }

    @Test
    void eachPreviewReadIsAuditedAsTheNeighboursReadIs(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            List<com.gamma.audit.Event> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.function.Consumer<com.gamma.audit.Event> sub = seen::add;
            com.gamma.event.EventLog.current().addSubscriber(sub);
            try {
                preview(c, "[{\"op\":\"seed\",\"ids\":[\"A1\"]},{\"op\":\"expand\",\"ids\":[\"A1\"],\"hubThreshold\":3}]");
            } finally {
                com.gamma.event.EventLog.current().removeSubscriber(sub);
            }
            List<com.gamma.audit.Event> ex = seen.stream().filter(x -> com.gamma.la.core.LinkEventTypes.LINK_EXPANDED.equals(x.type())).toList();
            assertEquals(1, ex.size(), "one event per expand read: " + seen);
            assertEquals("calls_ds", String.valueOf(ex.get(0).attributes().get("dataset")));
            assertEquals("A1", String.valueOf(ex.get(0).attributes().get("value")));
        }
    }

    @Test
    void oneRequestIsCappedInStepsAndInRows(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            String seed = "{\"op\":\"seed\",\"ids\":[\"A1\"]}", expand = "{\"op\":\"expand\",\"ids\":[\"A1\"]}";
            HttpResponse<String> many = post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[" + String.join(",", seed, expand, expand, expand, expand) + "]}");
            assertEquals(422, many.statusCode(), "at most four steps: " + many.body());
            assertTrue(many.body().contains("not the whole history"), many.body());
            String big = "{\"op\":\"expand\",\"budget\":40000}";
            HttpResponse<String> rows = post(c, "/inv/expand/preview", "{" + BINDING + ",\"steps\":[" + seed + "," + big + "," + big + "]}");
            assertEquals(422, rows.statusCode(), rows.body());
            assertTrue(rows.body().contains("one preview reads at most"), rows.body());
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
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            String body = "{" + BINDING + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"A1\"]}]}";
            assertEquals(401, post(c, "/inv/expand/preview", body).statusCode(), "no credential");
            assertEquals(200, post(c, "/inv/expand/preview", body, "Bearer plain").statusCode(),
                    "no capability needed: read-shaped, like the explorer's neighbours reads");
            String priv = body.replace("calls_ds", "private_ds");
            assertEquals(404, post(c, "/inv/expand/preview", priv, "Bearer plain").statusCode(), "a Dataset the caller cannot view");
            assertEquals(200, post(c, "/inv/expand/preview", priv, "Bearer owner").statusCode(), "its owner can");
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
