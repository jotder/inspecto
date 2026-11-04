package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.gamma.control.ControlApiDatasetDiscoverTest.A;
import static com.gamma.control.ControlApiDatasetDiscoverTest.B;
import static com.gamma.control.ControlApiDatasetDiscoverTest.C;
import static com.gamma.control.ControlApiDatasetDiscoverTest.D;
import static com.gamma.control.ControlApiDatasetDiscoverTest.RAW;
import static com.gamma.control.ControlApiDatasetDiscoverTest.get;
import static com.gamma.control.ControlApiDatasetDiscoverTest.open;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-ENTITY-SUMMARY-1 over real HTTP: {@code GET /inv/entities/{type}/{key}/summary?dataset=} answers one entity's links in and
 * out, first and last seen, counterparts by links and by the primary Measure, kinds and activity per day over one Dataset;
 * takes only the alias in the URL for a masked type and masks every counterpart (bodies SCANNED for the planted raw ids); says
 * why when the read ran out of time; refuses in gate order; and is audited without the key.
 *
 * <p>Fixture ({@link ControlApiDatasetDiscoverTest#open}): A pays B twice (10, 20) and C once (5); D pays A 1500 (cash_out).
 */
class ControlApiEntitySummaryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode ok(ControlApiDatasetDiscoverTest.Ctx c, String path, boolean rawShown) throws Exception {
        HttpResponse<String> r = get(c, path);
        assertEquals(200, r.statusCode(), r.body());
        if (!rawShown) for (String raw : RAW) assertFalse(r.body().contains(raw), "leaked the raw id " + raw + ": " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static List<String> list(JsonNode rows, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : rows) out.add(r.get(field).asText());
        return out;
    }

    @Test
    void oneEntitysLinksCounterpartsKindsAndDays(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: none\n")) {
            JsonNode s = ok(c, "/inv/entities/msisdn/" + A + "/summary?dataset=pays_ds", true);
            assertTrue(s.get("assessed").asBoolean(), s.toString());
            assertEquals(4, s.get("links").asLong());
            assertEquals(3, s.get("linksOut").asLong());
            assertEquals(1, s.get("linksIn").asLong());
            assertEquals(3, s.get("counterparts").asLong());
            assertEquals("2026-10-01T10:00:00Z", s.get("firstSeen").asText());
            assertEquals("2026-10-05T10:00:00Z", s.get("lastSeen").asText());

            assertEquals(List.of(B, C, D), list(s.get("counterpartsByLinks"), "entity"), "most links first, then by id");
            JsonNode b = s.get("counterpartsByLinks").get(0);
            assertEquals(2, b.get("out").asLong());
            assertEquals(0, b.get("in").asLong());
            assertEquals(30.0, b.get("amount").asDouble());
            assertEquals(List.of(D, B, C), list(s.get("counterpartsByAmount"), "entity"), "the most money first, either way");

            assertEquals("amount", s.at("/measure/column").asText());
            assertEquals("USD", s.at("/measure/unit").asText());
            assertEquals(35.0, s.at("/measure/out").asDouble());
            assertEquals(1500.0, s.at("/measure/in").asDouble());

            assertEquals(List.of("p2p", "cash_out"), list(s.get("kinds"), "kind"));
            assertEquals(3, s.get("kinds").get(0).get("out").asLong());
            assertEquals(1, s.get("kinds").get(1).get("in").asLong());
            assertEquals(List.of("2026-10-01", "2026-10-02", "2026-10-05"), list(s.get("activity"), "day"));
            assertEquals(List.of("2", "1", "1"), list(s.get("activity"), "links"));

            // a Dataset with no kind, time or measure: those parts are null (not measured), never empty lists or zeros
            JsonNode pairs = ok(c, "/inv/entities/msisdn/" + A + "/summary?dataset=pairs_ds", true);
            assertEquals(2, pairs.get("links").asLong());
            assertTrue(pairs.get("kinds").isNull() && pairs.get("activity").isNull() && pairs.get("measure").isNull(), pairs.toString());
            assertTrue(pairs.get("counterpartsByAmount").isNull());

            // an entity this Dataset never names: measured, and zero
            JsonNode none = ok(c, "/inv/entities/msisdn/27829999999/summary?dataset=pays_ds", true);
            assertTrue(none.get("assessed").asBoolean());
            assertEquals(0, none.get("links").asLong());
            assertTrue(none.get("firstSeen").isNull());
        }
    }

    @Test
    void aMaskedTypeTakesTheAliasAndAnswersAliases(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: typed\n")) {   // msisdn is a masked type
            HttpResponse<String> raw = get(c, "/inv/entities/msisdn/" + A + "/summary?dataset=pays_ds");
            assertEquals(422, raw.statusCode(), raw.body());
            assertFalse(raw.body().contains(A), "the refusal never repeats the key");

            String alias = ok(c, "/inv/datasets/pays_ds/discover", false).at("/busiest/from/0/entity").asText();
            assertTrue(alias.startsWith("masked:"), alias);
            JsonNode s = ok(c, "/inv/entities/msisdn/" + alias + "/summary?dataset=pays_ds", false);
            assertEquals(alias, s.at("/entity/key").asText());
            assertTrue(s.at("/entity/masked").asBoolean());
            assertEquals(4, s.get("links").asLong(), "the alias resolves to A: " + s);
            for (JsonNode cp : s.get("counterpartsByLinks")) assertTrue(cp.get("entity").asText().startsWith("masked:"), s.toString());
        }
    }

    @Test
    void aMaskedTypeMasksEveryCounterpartEvenOverUnclassifiedColumnsAndIsNeverItsOwnCounterpart(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: typed\n")) {
            // the verifier's probe: caller / callee carry no classification, so the column rule masks nothing - the queried
            // type's masking must; and a self-link A -> A must not hand A back as its own counterpart
            new ViewStore(root.resolve("views")).write(new ViewDefinition("plain_view", "flow-x", List.of(),
                    "SELECT caller, callee FROM (VALUES ('" + A + "','" + A + "'),('" + A + "','" + B + "'),('" + A + "',NULL),('" + A + "',''))"
                            + " AS t(caller,callee)", "2026-11-05T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "plain_ds", Map.of("view", "plain_view",
                    "defaultLinkMapping", Map.of("from", "caller", "to", "callee")));
            HttpResponse<String> resolved = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port()
                    + "/api/v1/inv/entities/msisdn/resolve")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"" + A + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resolved.statusCode(), resolved.body());
            String alias = JSON.readTree(resolved.body()).at("/data/key").asText();
            assertTrue(alias.startsWith("masked:"), alias);

            JsonNode s = ok(c, "/inv/entities/msisdn/" + alias + "/summary?dataset=plain_ds", false);
            assertTrue(s.at("/masking/masked").asBoolean(), s.toString());
            assertEquals(4, s.get("links").asLong(), "the self-link and the empty ends still count as links: " + s);
            assertEquals(1, s.get("counterparts").asLong(), "only B: never itself, never an empty end: " + s);
            assertEquals(1, s.get("counterpartsByLinks").size(), s.toString());
            assertTrue(s.at("/counterpartsByLinks/0/entity").asText().startsWith("masked:"), s.toString());
        }
    }

    @Test
    void aReadPastTheTimeoutSaysSo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: none\n")) {
            ControlApiEntityProfileScaleTest.Faulty f = new ControlApiEntityProfileScaleTest.Faulty();
            f.error = "INTERRUPT Error: Interrupted!";
            f.fail = sql -> sql.contains("GROUPING SETS");
            com.gamma.la.core.DatasetProviders.forTest(f);
            try {
                JsonNode s = ok(c, "/inv/entities/msisdn/" + A + "/summary?dataset=pays_ds", true);
                assertFalse(s.get("assessed").asBoolean(), s.toString());
                assertTrue(s.get("reason").asText().contains("longer than this Space allows"), s.toString());
                assertFalse(s.has("links"), "nothing unmeasured is answered as a count: " + s);
            } finally {
                com.gamma.la.core.DatasetProviders.forTest(null);
            }
        }
    }

    @Test
    void refusesInGateOrderAndAuditsWithoutTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: none\n")) {
            assertEquals(422, get(c, "/inv/entities/msisdn/" + A + "/summary").statusCode(), "no dataset");
            assertEquals(404, get(c, "/inv/entities/nope/" + A + "/summary?dataset=pays_ds").statusCode());
            assertEquals(422, get(c, "/inv/entities/msisdn/masked:0000000000000000/summary?dataset=pays_ds").statusCode(),
                    "an alias this server never minted");
            assertEquals(404, get(c, "/inv/entities/msisdn/" + A + "/summary?dataset=nope").statusCode());
            new ComponentStore(root.resolve("registry")).write("dataset", "unlinked_ds", Map.of("view", "pays_view",
                    "columns", List.of(Map.of("name", "caller"))));
            HttpResponse<String> unlinked = get(c, "/inv/entities/msisdn/" + A + "/summary?dataset=unlinked_ds");
            assertEquals(422, unlinked.statusCode(), unlinked.body());
            assertTrue(unlinked.body().contains("From and To"), unlinked.body());

            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                ok(c, "/inv/entities/msisdn/" + A + "/summary?dataset=pays_ds", true);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            Event e = seen.stream().filter(x -> LinkEventTypes.LINK_ENTITY_SUMMARIZED.equals(x.type())).findFirst().orElseThrow();
            assertEquals("pays_ds", e.attributes().get("dataset"));
            assertEquals("true", String.valueOf(e.attributes().get("found")));
            for (String raw : RAW) assertFalse(String.valueOf(e).contains(raw), "no entity id in the audit: " + e);
        }
    }
}
