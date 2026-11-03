package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.EventLog;
import com.gamma.la.core.LinkEventTypes;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-PRESET-QUERIES-1 over real HTTP: {@code GET /inv/presets} offers the Industry pack's presets whose roles a Dataset
 * maps, and {@code POST /inv/presets/{id}/run} answers a capped table whose entity ids are aliases when the Space masks
 * (every response body SCANNED for the planted raw ids), takes an alias back as its {@code entity}, runs the
 * {@code timeToCashOut} Value Measure for {@code quick_cash_out}, and refuses in gate order.
 *
 * <p>Fixture: phone-number-shaped ids ({@code ControlApiExplorationMaskingTest}'s reason), timestamps two days ago so the
 * rolling {@code last} window always holds them.
 */
class ControlApiPresetQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003", D = "27820000004", E = "27820000005";
    private static final List<String> RAW = List.of(A, B, C, D, E);
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private static String row(String from, String to, String kind, int amount, int hour) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC).minusDays(2).withHour(hour).withMinute(0).withSecond(0).withNano(0);
        return "('" + from + "','" + to + "','" + kind + "'," + amount + ",TIMESTAMP '" + at.toString().replace('T', ' ') + ":00')";
    }

    private static Map<String, Object> entry(String view) {
        return Map.of("view", view, "columns", List.of(Map.of("name", "caller", "classification", "MSISDN"),
                Map.of("name", "callee", "classification", "MSISDN")));
    }

    /** {@code pays_ds}: From, To, Kind, Amount and When; {@code pairs_ds}: only From and To. */
    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            String rows = String.join(",",
                    row(A, B, "p2p", 10, 10), row(A, B, "p2p", 20, 10), row(A, C, "p2p", 5, 11),
                    row(B, D, "p2p", 7, 12), row(E, D, "p2p", 2000, 13), row(D, A, "cash_out", 1500, 14));
            ViewStore views = new ViewStore(writeRoot.resolve("views"));
            views.write(new ViewDefinition("pays_view", "flow-x", List.of(),
                    "SELECT caller, callee, link_kind, amount, ts FROM (VALUES " + rows
                            + ") AS t(caller,callee,link_kind,amount,ts)", "2026-11-03T00:00:00Z"));
            views.write(new ViewDefinition("pairs_view", "flow-x", List.of(),
                    "SELECT caller, callee FROM (VALUES ('" + A + "','" + B + "')) AS t(caller,callee)", "2026-11-03T00:00:00Z"));
            ComponentStore registry = new ComponentStore(writeRoot.resolve("registry"));
            registry.write("dataset", "pays_ds", entry("pays_view"));
            registry.write("dataset", "pairs_ds", entry("pairs_view"));
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "domain_profile: mobile-money\n" + settings);
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** A 200 answer's data; the planted raw ids must appear in the body only when {@code rawShown}. */
    private JsonNode ok(Ctx c, String method, String path, String body, boolean rawShown) throws Exception {
        HttpResponse<String> r = send(c, method, path, body);
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        if (!rawShown)
            for (String raw : RAW)
                assertFalse(r.body().contains(raw), path + " leaked the raw id " + raw + ": " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode run(Ctx c, String preset, String dataset, String params, boolean rawShown) throws Exception {
        return ok(c, "POST", "/inv/presets/" + preset + "/run", "{\"dataset\":\"" + dataset + "\",\"params\":" + params + "}", rawShown);
    }

    private static List<String> ids(JsonNode presets) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : presets) out.add(p.get("id").asText());
        return out;
    }

    @Test
    void theListOffersThePacksPresetsWhoseRolesTheDatasetMaps(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            JsonNode full = ok(c, "GET", "/inv/presets?dataset=pays_ds", null, true);
            assertEquals("mobile-money", full.get("pack").asText());
            assertEquals("caller", full.at("/roles/from").asText());
            assertEquals("callee", full.at("/roles/to").asText());
            assertEquals("link_kind", full.at("/roles/kind").asText());
            assertEquals("ts", full.at("/roles/time").asText());
            assertEquals("amount", full.at("/roles/amount").asText());
            assertEquals(List.of("quick_cash_out", "fan_in", "counterparts", "top_senders", "fan_out"), ids(full.get("presets")));

            // no Kind, When or Amount: the presets that need them are not offered (Q-5)
            JsonNode pairs = ok(c, "GET", "/inv/presets?dataset=pairs_ds", null, true);
            assertTrue(pairs.at("/roles/amount").isNull(), pairs.toString());
            assertEquals(List.of("fan_in", "counterparts", "fan_out"), ids(pairs.get("presets")));
        }
    }

    @Test
    void aRunAnswersAliasesNeverARawIdAndIsCapped(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            JsonNode fanIn = run(c, "fan_in", "pays_ds", "{\"min\":2}", false);
            assertTrue(fanIn.at("/masking/masked").asBoolean(), fanIn.toString());
            assertEquals(1, fanIn.get("count").asInt(), fanIn.toString());   // only D is reached by two distinct senders
            JsonNode d = fanIn.get("rows").get(0);
            assertTrue(d.get("entity").asText().startsWith("masked:"), d.toString());
            assertEquals(2, d.get("counterparts").asInt());
            assertTrue(fanIn.get("columns").get(0).get("entity").asBoolean());

            JsonNode capped = run(c, "fan_in", "pays_ds", "{\"min\":1,\"top\":1}", false);
            assertEquals(1, capped.get("count").asInt());
            assertTrue(capped.get("truncated").asBoolean(), capped.toString());
            assertEquals(d.get("entity").asText(), capped.get("rows").get(0).get("entity").asText());

            // an alias this server served is taken back as the entity, and its counterparts come back as aliases
            JsonNode fanOut = run(c, "fan_out", "pays_ds", "{\"min\":2}", false);
            String aliasOfA = fanOut.get("rows").get(0).get("entity").asText();
            JsonNode parts = run(c, "counterparts", "pays_ds", "{\"entity\":\"" + aliasOfA + "\"}", false);
            assertEquals(2, parts.get("count").asInt(), parts.toString());
            assertEquals(2, parts.get("rows").get(0).get("links").asInt());   // A -> B twice, 10 + 20
            assertEquals(30.0, parts.get("rows").get(0).get("amount").asDouble());
            assertEquals(1, parts.get("rows").get(1).get("links").asInt());
        }
    }

    @Test
    void theValueMeasurePresetIsMaskedAndEveryRunIsAudited(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            JsonNode r;
            try {
                r = run(c, "quick_cash_out", "pays_ds", "{}", false);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            assertEquals(1, r.get("count").asInt(), r.toString());
            assertTrue(r.get("rows").get(0).get("entity").asText().startsWith("masked:"), r.toString());
            assertTrue(seen.stream().anyMatch(e -> LinkEventTypes.LINK_PRESET_RUN.equals(e.type())
                    && "quick_cash_out".equals(e.attributes().get("preset"))), "audited");
        }
    }

    @Test
    void quickCashOutRunsTheTimeToCashOutValueMeasure(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            JsonNode r = run(c, "quick_cash_out", "pays_ds", "{\"maxHours\":24,\"minInbound\":1000}", true);
            assertEquals(1, r.get("count").asInt(), r.toString());
            JsonNode d = r.get("rows").get(0);
            assertEquals(D, d.get("entity").asText());               // in at 12:00 and 13:00, cashed out at 14:00
            assertEquals(1.5, d.get("hours").asDouble(), 1e-9);
            assertEquals(2, d.get("legs").asInt());
            assertEquals(2007.0, d.get("inbound").asDouble(), 1e-9);
            assertFalse(r.at("/masking/masked").asBoolean());

            assertEquals(0, run(c, "quick_cash_out", "pays_ds", "{\"maxHours\":0.5}", true).get("count").asInt());
        }
    }

    @Test
    void refusesInGateOrder(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            assertEquals(422, send(c, "GET", "/inv/presets", null).statusCode());
            assertEquals(422, send(c, "POST", "/inv/presets/fan_in/run", "{\"params\":{}}").statusCode());
            assertEquals(404, send(c, "GET", "/inv/presets?dataset=nope", null).statusCode());
            assertEquals(404, send(c, "POST", "/inv/presets/fan_in/run", "{\"dataset\":\"nope\"}").statusCode());
            assertEquals(404, send(c, "POST", "/inv/presets/nope/run", "{\"dataset\":\"pays_ds\"}").statusCode());

            HttpResponse<String> unmapped = send(c, "POST", "/inv/presets/top_senders/run", "{\"dataset\":\"pairs_ds\"}");
            assertEquals(422, unmapped.statusCode(), unmapped.body());
            assertTrue(unmapped.body().contains("amount"), unmapped.body());
            assertEquals(422, send(c, "POST", "/inv/presets/fan_in/run",
                    "{\"dataset\":\"pays_ds\",\"params\":{\"sql\":\"1\"}}").statusCode());
            assertEquals(422, send(c, "POST", "/inv/presets/fan_in/run",
                    "{\"dataset\":\"pays_ds\",\"params\":{\"min\":\"-1\"}}").statusCode());
            assertEquals(422, send(c, "POST", "/inv/presets/counterparts/run", "{\"dataset\":\"pays_ds\"}").statusCode());
            // a kinds filter on a Dataset that maps no kind of link is refused, never silently dropped
            HttpResponse<String> noKind = send(c, "POST", "/inv/presets/fan_in/run",
                    "{\"dataset\":\"pairs_ds\",\"params\":{\"kinds\":\"p2p\"}}");
            assertEquals(422, noKind.statusCode(), noKind.body());
            assertEquals(422, send(c, "POST", "/inv/presets/counterparts/run",
                    "{\"dataset\":\"pays_ds\",\"params\":{\"entity\":\"masked:unknown\"}}").statusCode());
        }
    }
}
