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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comparison mode (LA-INVESTIGATION-OPS-DEFERRED-1), over real HTTP: {@code GET /inv/investigations/{id}/compare} and the
 * Dossier's optional, unsealed {@code comparison} section.
 *
 * <p>Fixture ({@code ts} naive São Paulo wall clock). Window A = 09-01..09-03, window B = 09-03..09-06:
 * a-b only in A; a-c in both (one event each); c-d only in B. {@code e-f} (09-05) is in the Dataset but never admitted to the
 * Working Set, and an excluded entity must not reappear: both are negative probes that WOULD succeed if the diff read the
 * Dataset directly rather than the Working Set.
 */
class ControlApiInvestigationComparisonTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('a','b','voice','2026-09-01 10:30:00')", "('a','c','voice','2026-09-02 12:00:00')",
            "('a','c','voice','2026-09-05 09:00:00')", "('c','d','sms','2026-09-05 08:00:00')",
            "('e','f','sms','2026-09-05 10:00:00')");
    private static final String AB = "aFrom=2026-09-01T00:00:00-03:00&aTo=2026-09-03T00:00:00-03:00"
            + "&bFrom=2026-09-03T00:00:00-03:00&bTo=2026-09-06T00:00:00-03:00&timezone=America/Sao_Paulo";
    private static final String CMP = "/inv/investigations/case-a/compare?" + AB;
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

    private int status(Ctx c, String path) throws Exception {
        return send(c.port, "GET", path, null, null).statusCode();
    }

    private void build(Ctx c) throws Exception {
        post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                + "\"targetCol\":\"callee\",\"linkKindCol\":\"channel\",\"timeCol\":\"ts\",\"timeColZone\":\"America/Sao_Paulo\"}");
        String ops = "/inv/investigations/case-a/ops";
        post(c, ops, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
        post(c, ops, "{\"op\":\"expand\"}");
        post(c, ops, "{\"op\":\"expand\"}");
    }

    private static List<String> ids(JsonNode section) {
        List<String> out = new ArrayList<>();
        section.get("items").forEach(n -> out.add(n.isTextual() ? n.asText() : n.get("source").asText() + ">" + n.get("target").asText()));
        return out;
    }

    @Test
    void activityModeOnTheReadRouteMatchesTheSealedOpSemantics(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode presence = data(send(c.port, "GET", CMP, null, null));
            assertFalse(presence.has("activity"), "presence stays the default");
            JsonNode d = data(send(c.port, "GET", CMP + "&mode=activity", null, null));
            assertEquals("activity", d.get("mode").asText());
            assertFalse(d.get("sealed").asBoolean());
            // a>b 1 -> 0, a>c 1 -> 1, c>d 0 -> 1; a 2 -> 1, b 1 -> 0, c 1 -> 2, d 0 -> 1 (e-f was never admitted).
            assertEquals(2, d.at("/activity/links/changed/count").asInt(), d.toString());
            assertEquals(1, d.at("/activity/links/unchanged").asInt());
            assertEquals("a", d.at("/activity/links/changed/items/0/source").asText());
            assertEquals(-1, d.at("/activity/links/changed/items/0/delta").asInt());
            assertEquals(4, d.at("/activity/entities/changed/count").asInt());
            assertEquals("a", d.at("/activity/entities/changed/items/0/id").asText());
            assertFalse(d.at("/activity").has("minAbsDelta"));
            assertEquals(presence.get("links").get("onlyA").toString(), d.get("links").get("onlyA").toString(), "presence sections are unchanged");

            JsonNode floor = data(send(c.port, "GET", CMP + "&mode=activity&minAbsDelta=2", null, null));
            assertEquals(0, floor.at("/activity/links/changed/count").asInt());
            assertEquals(2, floor.at("/activity/links/belowMinDelta").asInt());
            assertEquals(2, floor.at("/activity/minAbsDelta").asInt());

            assertEquals(422, status(c, CMP + "&mode=bogus"));
            assertEquals(422, status(c, CMP + "&minAbsDelta=2"), "a floor needs mode=activity");
            assertEquals(422, status(c, CMP + "&mode=activity&minAbsDelta=0"));
            assertEquals(422, status(c, CMP + "&mode=activity&minAbsDelta=abc"));
        }
    }

    @Test
    void diffsTheWorkingSetAcrossTwoWindowsAndOnlyTheWorkingSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode d = data(send(c.port, "GET", CMP, null, null));
            assertEquals(List.of("a>b"), ids(d.at("/links/onlyA")), d.toString());
            assertEquals(List.of("c>d"), ids(d.at("/links/onlyB")));
            assertEquals(List.of("a>c"), ids(d.at("/links/both")));
            assertEquals(1, d.at("/links/both/items/0/countA").asInt());
            assertEquals(1, d.at("/links/both/items/0/countB").asInt());
            assertEquals(List.of("b"), ids(d.at("/entities/onlyA")));
            assertEquals(List.of("d"), ids(d.at("/entities/onlyB")));
            assertEquals(List.of("a", "c"), ids(d.at("/entities/both")));
            assertEquals(0, d.at("/links/neither").asInt());
            assertFalse(d.get("sealed").asBoolean(), "a live read, never sealed into the log");
            // Negative probe: e-f is in the Dataset inside window B but was never admitted - a Dataset-wide diff would list it.
            assertFalse(d.toString().contains("\"e\"") || d.toString().contains("\"f\""), d.toString());

            // Deterministic: the same ask answers the same content.
            JsonNode again = data(send(c.port, "GET", CMP, null, null));
            assertEquals(d.get("links").toString(), again.get("links").toString());

            // `at` diffs an earlier Working Set: after the seed only, the graph is {a} with no links.
            JsonNode early = data(send(c.port, "GET", CMP + "&at=1", null, null));
            assertEquals(0, early.at("/links/onlyA/count").asInt());
            assertEquals(1, early.at("/entities/neither").asInt());

            // Negative probe: an excluded entity leaves the diff although the Dataset still has its A-window event.
            post(c, "/inv/investigations/case-a/ops", "{\"op\":\"exclude\",\"ids\":[\"b\"],\"reason\":\"not suspect\"}");
            JsonNode after = data(send(c.port, "GET", CMP, null, null));
            assertEquals(0, after.at("/links/onlyA/count").asInt(), after.toString());
            assertFalse(after.at("/entities").toString().contains("\"b\""), after.toString());

            // The comparison persists nothing: the log still holds the four ops it had (seed, expand, expand, exclude).
            assertEquals(4, Files.readAllLines(root.resolve("audit/snapshots/investigations/case-a/log.jsonl")).size());
        }
    }

    @Test
    void theDossierCarriesTheComparisonOnlyWhenAskedAndOutsideItsManifest(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode plain = data(send(c.port, "GET", "/inv/investigations/case-a/dossier", null, null));
            assertTrue(plain.path("comparison").isMissingNode(), "no windows asked, no live read");
            JsonNode with = data(send(c.port, "GET", "/inv/investigations/case-a/dossier?" + AB, null, null));
            assertEquals(List.of("a>c"), ids(with.at("/comparison/links/both")));
            assertFalse(with.at("/comparison/sealed").asBoolean());
            assertEquals(plain.at("/manifest/root").asText(), with.at("/manifest/root").asText(),
                    "the unsealed section is outside the manifest");
        }
    }

    @Test
    void masksLikeTheWorkingSetAndNeverWidensIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode d = data(send(c.port, "GET", CMP, null, null));
            String body = d.toString();
            for (String raw : List.of("\"a\"", "\"b\"", "\"c\"", "\"d\"", "\"a>"))
                assertFalse(body.contains(raw), "a raw id leaked under masking_mode all: " + body);
            assertTrue(d.at("/entities/onlyA/items/0").asText().startsWith("masked:"), body);
            assertEquals(1, d.at("/entities/onlyA/count").asInt(), "counts survive masking");
        }
    }

    @Test
    void refusesWhatItCannotCompare(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"timeless\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                    + "\"targetCol\":\"callee\"}");
            assertEquals(422, status(c, "/inv/investigations/timeless/compare?" + AB), "no time column");
            assertEquals(422, status(c, "/inv/investigations/case-a/compare"), "no windows");
            assertEquals(422, status(c, "/inv/investigations/case-a/compare?aFrom=2026-09-01T00:00:00Z"), "window B missing");
            assertEquals(422, status(c, "/inv/investigations/case-a/compare?aFrom=2026-09-01T00:00:00&bFrom=2026-09-02T00:00:00Z"),
                    "a naive instant would mean the host's clock");
            assertEquals(422, status(c, CMP + "&at=99"));
            assertEquals(404, status(c, "/inv/investigations/nope/compare?" + AB));
        }
    }

    /** With an ARMED Authenticator the route is owner-only like every Investigation read. */
    @Test
    void isOwnerOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"timeCol\":\"ts\"}", "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", "Bearer owner").statusCode());
            assertEquals(401, send(c.port, "GET", CMP, null, null).statusCode());
            assertEquals(404, send(c.port, "GET", CMP, null, "Bearer other").statusCode());
            assertEquals(200, send(c.port, "GET", CMP, null, "Bearer owner").statusCode());
        } finally {
            Authenticators.forTest(null);
        }
    }
}
