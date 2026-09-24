package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-19 — the evidential controls that are decided: the {@code annotate} op (per-entity annotation) and the coverage
 * indicator ({@code GET /inv/investigations/{id}/coverage}), over real HTTP.
 *
 * <p><b>Fixture.</b> {@code ts} is a NAIVE timestamp on the São Paulo wall clock (UTC−3), declared as
 * {@code timeColZone}. Rows fall on the São Paulo dates 2026-09-01 (Tue), 09-02, 09-03 and 09-05 — never on
 * 09-04 (Fri), which is the planted gap. Read in UTC instead, the 21:30 row on 09-03 would land on 09-04 and hide it,
 * so the gap is only found if the declared zone is honoured.
 */
class ControlApiInvestigationEvidentialControlsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('a','b','voice','2026-09-01 10:30:00')", "('a','c','voice','2026-09-02 12:00:00')",
            "('b','c','sms','2026-09-03 21:30:00')", "('c','d','sms','2026-09-05 08:00:00')");
    private static final String RANGE = "from=2026-09-01T00:00:00-03:00&to=2026-09-06T00:00:00-03:00";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel, CAST(ts AS TIMESTAMP) AS ts FROM (VALUES " + ROWS
                            + ") AS t(caller,callee,channel,ts)", "2026-09-23T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode post(Ctx c, String path, String body) throws Exception {
        return data(send(c.port, "POST", path, body, null));
    }

    private int status(Ctx c, String method, String path, String body) throws Exception {
        return send(c.port, method, path, body, null).statusCode();
    }

    private void create(Ctx c, String id, boolean timed) throws Exception {
        post(c, "/inv/investigations", "{\"id\":\"" + id + "\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                + "\"targetCol\":\"callee\",\"linkKindCol\":\"channel\""
                + (timed ? ",\"timeCol\":\"ts\",\"timeColZone\":\"America/Sao_Paulo\"" : "") + "}");
    }

    @Test
    void annotateAttachesANoteToAnEntityAndChangesNothingElse(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a", false);
            String ops = "/inv/investigations/case-a/ops";
            post(c, ops, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            JsonNode expanded = post(c, ops, "{\"op\":\"expand\"}");
            String before = expanded.at("/workingSet/hash").asText();

            JsonNode step = post(c, ops, "{\"op\":\"annotate\",\"ids\":[\"b\"],\"note\":\"shell entity, 98% pass-through\"}");
            assertEquals(0, step.at("/delta/admitted").size());
            assertEquals(0, step.at("/delta/removed").size());
            assertEquals(expanded.at("/workingSet/entities").asInt(), step.at("/workingSet/entities").asInt());
            assertFalse(before.equals(step.at("/workingSet/hash").asText()), "the note is part of the sealed state");

            // Refusals: not in the Working Set, no note, and the undecided confidence scale — each 422, nothing written.
            assertEquals(422, status(c, "POST", ops, "{\"op\":\"annotate\",\"ids\":[\"zz\"],\"note\":\"x\"}"));
            assertEquals(422, status(c, "POST", ops, "{\"op\":\"annotate\",\"ids\":[\"b\"]}"));
            assertEquals(422, status(c, "POST", ops, "{\"op\":\"annotate\",\"ids\":[\"b\"],\"note\":\"x\",\"confidence\":0.9}"));
            assertEquals(3, Files.readAllLines(root.resolve("audit/snapshots/investigations/case-a/log.jsonl")).size());

            // A later exclusion keeps the note: it is history, not membership.
            post(c, ops, "{\"op\":\"exclude\",\"ids\":[\"b\"],\"reason\":\"not suspect\"}");
            JsonNode replay = post(c, "/inv/investigations/case-a/replay", "{}");
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
            JsonNode notes = replay.at("/workingSet/annotations");
            assertEquals(1, notes.size());
            assertEquals("b", notes.get(0).get("id").asText());
            assertEquals(3, notes.get(0).get("step").asInt());
            assertEquals("shell entity, 98% pass-through", notes.get(0).get("note").asText());

            JsonNode log = data(send(c.port, "GET", "/inv/investigations/case-a/log", null, null));
            assertEquals("3. Annotated b: \"shell entity, 98% pass-through\"", log.at("/entries/2/text").asText());
            String steps = send(c.port, "GET", "/inv/investigations/case-a/dossier?format=steps", null, null).body();
            assertTrue(steps.contains("Annotated b: \"shell entity, 98% pass-through\""), steps);

            // D-E8: a note names one graph's entity — a template drops it and lists only its count.
            JsonNode tpl = post(c, "/inv/investigations/case-a/template", "{\"id\":\"tpl-1\"}");
            assertTrue(tpl.get("dropped").toString().contains("{\"step\":3,\"op\":\"annotate\",\"count\":1}"),
                    tpl.get("dropped").toString());
        }
    }

    @Test
    void undoingAnAnnotationRemovesIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a", false);
            String ops = "/inv/investigations/case-a/ops";
            String seeded = post(c, ops, "{\"op\":\"seed\",\"ids\":[\"a\"]}").at("/workingSet/hash").asText();
            post(c, ops, "{\"op\":\"annotate\",\"ids\":[\"a\"],\"note\":\"n\"}");
            JsonNode undone = post(c, "/inv/investigations/case-a/undo", "");
            assertEquals(seeded, undone.at("/workingSet/hash").asText());
            JsonNode replay = post(c, "/inv/investigations/case-a/replay", "{}");
            assertTrue(replay.get("equivalent").asBoolean());
            assertTrue(replay.at("/workingSet/annotations").isMissingNode());
        }
    }

    @Test
    void coverageNamesTheDayWithNoRowsInTheDeclaredZone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a", true);
            JsonNode cov = data(send(c.port, "GET", "/inv/investigations/case-a/coverage?" + RANGE
                    + "&timezone=America/Sao_Paulo", null, null));
            assertEquals(5, cov.get("expectedDays").asInt(), cov.toString());
            assertEquals(4, cov.get("coveredDays").asInt());
            assertEquals("[\"2026-09-04\"]", cov.get("missingDays").toString());
            assertFalse(cov.get("complete").asBoolean());
            assertEquals(1, cov.at("/perDay/2/rows").asInt(), "the 21:30 row stays on 09-03 in São Paulo");
            assertFalse(cov.at("/collectors/assessed").asBoolean());

            // Read in UTC, the 21:30 row lands on 09-04 — the gap disappears, which is why the zone is stated.
            JsonNode utc = data(send(c.port, "GET", "/inv/investigations/case-a/coverage?" + RANGE, null, null));
            assertEquals("UTC", utc.get("zone").asText());
            assertFalse(utc.get("missingDays").toString().contains("2026-09-04"), utc.toString());

            // No query window → the Investigation's own window, day mask included (only Fridays are expected).
            assertEquals(422, status(c, "GET", "/inv/investigations/case-a/coverage", null), "no window yet");
            post(c, "/inv/investigations/case-a/ops", "{\"op\":\"window\",\"window\":{\"from\":\"2026-09-01T00:00:00-03:00\","
                    + "\"to\":\"2026-09-06T00:00:00-03:00\",\"days\":[\"FRI\"],\"timezone\":\"America/Sao_Paulo\"}}");
            JsonNode fri = data(send(c.port, "GET", "/inv/investigations/case-a/coverage", null, null));
            assertEquals(1, fri.get("expectedDays").asInt(), fri.toString());
            assertEquals("[\"2026-09-04\"]", fri.get("missingDays").toString());
        }
    }

    @Test
    void coverageRefusesWhatItCannotAssess(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "timeless", false);
            assertEquals(422, status(c, "GET", "/inv/investigations/timeless/coverage?" + RANGE, null), "no time column");
            create(c, "case-a", true);
            assertEquals(422, status(c, "GET", "/inv/investigations/case-a/coverage?from=2026-09-01T00:00:00Z", null),
                    "an open range has no list of expected days");
            assertEquals(422, status(c, "GET", "/inv/investigations/case-a/coverage?from=2026-09-01T00:00:00&to=2026-09-02T00:00:00Z", null),
                    "a naive instant would mean the host's clock");
            assertEquals(422, status(c, "GET", "/inv/investigations/case-a/coverage?from=2000-01-01T00:00:00Z&to=2026-01-01T00:00:00Z", null),
                    "over the day cap");
            assertEquals(404, status(c, "GET", "/inv/investigations/nope/coverage?" + RANGE, null));
        }
    }

    /** With an ARMED Authenticator: annotate needs canManageIncidents; coverage is owner-only like every Investigation read. */
    @Test
    void annotateIsGatedAndCoverageIsOwnerOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            case "Bearer plain" -> Optional.of(new Subject("analyst-1", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", "{\"id\":\"case-a\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"timeCol\":\"ts\"}", "Bearer owner").statusCode());
            String ops = "/inv/investigations/case-a/ops";
            assertEquals(200, send(c.port, "POST", ops, "{\"op\":\"seed\",\"ids\":[\"a\"]}", "Bearer owner").statusCode());
            String note = "{\"op\":\"annotate\",\"ids\":[\"a\"],\"note\":\"n\"}";
            assertEquals(403, send(c.port, "POST", ops, note, "Bearer plain").statusCode());
            assertEquals(404, send(c.port, "POST", ops, note, "Bearer other").statusCode());
            assertEquals(200, send(c.port, "POST", ops, note, "Bearer owner").statusCode());

            String cov = "/inv/investigations/case-a/coverage?" + RANGE;
            assertEquals(401, send(c.port, "GET", cov, null, null).statusCode());
            assertEquals(404, send(c.port, "GET", cov, null, "Bearer other").statusCode());
            assertEquals(200, send(c.port, "GET", cov, null, "Bearer owner").statusCode());
        } finally {
            Authenticators.forTest(null);
        }
    }
}
