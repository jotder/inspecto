package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.gamma.control.ControlApiDatasetDiscoverTest.A;
import static com.gamma.control.ControlApiDatasetDiscoverTest.B;
import static com.gamma.control.ControlApiDatasetDiscoverTest.C;
import static com.gamma.control.ControlApiDatasetDiscoverTest.D;
import static com.gamma.control.ControlApiDatasetDiscoverTest.E;
import static com.gamma.control.ControlApiDatasetDiscoverTest.RAW;
import static com.gamma.control.ControlApiDatasetDiscoverTest.get;
import static com.gamma.control.ControlApiDatasetDiscoverTest.open;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-QUERY-BUILDER-1 over real HTTP: {@code POST /inv/datasets/{id}/query} answers a builder question over the Dataset's role
 * columns (rank by links, counterparts or a Measure's total; From / To / kinds / a window on any Time column / a range on any
 * Measure / a minimum / top), masks entity ids like exploration (bodies SCANNED for the planted raw ids) and takes an alias back;
 * refuses any key, column or value the Dataset's roles do not support; and {@code GET /inv/presets} lists the role columns and
 * the Space presets saved from the builder that the Dataset can answer.
 *
 * <p>Fixture ({@link ControlApiDatasetDiscoverTest#open}): A pays B 10 and 20 (10-01) and C 5 (10-02); B pays D 7 (10-03); E pays
 * D 2000 (10-04); D pays A 1500 (cash_out, 10-05). Measures amount (USD) and fee; Time ts.
 */
class ControlApiQueryBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpResponse<String> post(ControlApiDatasetDiscoverTest.Ctx c, String dataset, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/inv/datasets/" + dataset + "/query"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode ask(ControlApiDatasetDiscoverTest.Ctx c, String dataset, String query, boolean rawShown) throws Exception {
        HttpResponse<String> r = post(c, dataset, "{\"query\":" + query + "}");
        assertEquals(200, r.statusCode(), r.body());
        if (!rawShown) for (String raw : RAW) assertFalse(r.body().contains(raw), "leaked the raw id " + raw + ": " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static List<String> col(JsonNode answer, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : answer.get("rows")) out.add(r.get(field).asText());
        return out;
    }

    @Test
    void ranksFiltersAndTotalsOverTheRoleColumns(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: none\n")) {
            JsonNode links = ask(c, "pays_ds", "{\"by\":\"from\"}", true);
            assertEquals(A, links.at("/rows/0/entity").asText(), links.toString());
            assertEquals(3, links.at("/rows/0/links").asInt());
            assertEquals(2, links.at("/rows/0/counterparts").asInt());

            JsonNode sum = ask(c, "pays_ds", "{\"by\":\"from\",\"rank\":\"sum\",\"sumColumn\":\"amount\"}", true);
            assertEquals(List.of(E, D, A, B), col(sum, "entity"));
            assertEquals(2000.0, sum.at("/rows/0/total").asDouble());
            assertEquals("Total amount", sum.at("/columns/3/label").asText());

            assertEquals(List.of(E, A, B), col(ask(c, "pays_ds",
                    "{\"by\":\"from\",\"rank\":\"sum\",\"sumColumn\":\"amount\",\"kinds\":[\"p2p\"]}", true), "entity"));
            // a half-open window ON the row times (10:00): the start row is in, the end row is out
            JsonNode window = ask(c, "pays_ds",
                    "{\"by\":\"from\",\"timeColumn\":\"ts\",\"after\":\"2026-10-02T10:00:00Z\",\"before\":\"2026-10-04T10:00:00Z\"}", true);
            assertEquals(List.of(A, B), col(window, "entity"), "A->C at the start is in, E->D at the end is out: " + window);
            assertEquals("ts", window.at("/query/timeColumn").asText());
            // an inclusive range ON link values: 7 and 1500 are in, 5 and 2000 are out
            JsonNode range = ask(c, "pays_ds", "{\"by\":\"from\",\"measureColumn\":\"amount\",\"measureMin\":7,\"measureMax\":1500}", true);
            assertEquals(List.of(A, B, D), col(range, "entity"), range.toString());
            assertEquals(2, range.at("/rows/0/links").asInt(), "A's 10 and 20, not its 5");
            assertEquals(List.of(D, E), col(ask(c, "pays_ds", "{\"by\":\"from\",\"measureColumn\":\"fee\",\"measureMin\":10}", true), "entity"));
            // a minimum on a total (inclusive) and on distinct counterparts
            assertEquals(List.of(E, D), col(ask(c, "pays_ds",
                    "{\"by\":\"from\",\"rank\":\"sum\",\"sumColumn\":\"amount\",\"min\":1500}", true), "entity"));
            assertEquals(List.of(A), col(ask(c, "pays_ds", "{\"by\":\"from\",\"rank\":\"counterparts\",\"min\":2}", true), "entity"));
            // one sender's counterparts, by To
            JsonNode ofA = ask(c, "pays_ds", "{\"by\":\"to\",\"from\":\"" + A + "\"}", true);
            assertEquals(List.of(B, C), col(ofA, "entity"));
            // a minimum on the ranked value, and the row cap
            assertEquals(List.of(A), col(ask(c, "pays_ds", "{\"by\":\"from\",\"min\":2}", true), "entity"));
            JsonNode top = ask(c, "pays_ds", "{\"by\":\"from\",\"top\":1}", true);
            assertEquals(1, top.get("count").asInt());
            assertTrue(top.get("truncated").asBoolean(), top.toString());
        }
    }

    @Test
    void entityIdsLeaveAsAliasesAndAnAliasIsTakenBack(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: all\n")) {
            JsonNode links = ask(c, "pays_ds", "{\"by\":\"from\"}", false);
            String aliasOfA = links.at("/rows/0/entity").asText();
            assertTrue(aliasOfA.startsWith("masked:"), links.toString());
            JsonNode ofA = ask(c, "pays_ds", "{\"by\":\"to\",\"from\":\"" + aliasOfA + "\"}", false);
            assertEquals(2, ofA.get("count").asInt(), ofA.toString());
            assertEquals(aliasOfA, ofA.at("/query/from").asText(), "the echo carries the alias, never the raw id");
            HttpResponse<String> unknown = post(c, "pays_ds", "{\"query\":{\"from\":\"masked:0000000000000000\"}}");
            assertEquals(422, unknown.statusCode(), unknown.body());
        }
    }

    @Test
    void refusesWhatTheDatasetsRolesDoNotSupport(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: none\n")) {
            assertEquals(422, post(c, "pays_ds", "{}").statusCode(), "no query");
            assertEquals(404, post(c, "nope", "{\"query\":{}}").statusCode());
            for (String bad : List.of(
                    "{\"sql\":\"1\"}",                                               // never free SQL: an unknown key
                    "{\"by\":\"kind\"}",
                    "{\"rank\":\"sum\"}",                                             // sum without its Measure
                    "{\"rank\":\"sum\",\"sumColumn\":\"ts\"}",                        // a Time is no Measure
                    "{\"rank\":\"sum\",\"sumColumn\":\"caller\"}",                    // an Entity is no Measure
                    "{\"timeColumn\":\"amount\",\"after\":\"2026-10-01\"}",           // a Measure is no Time
                    "{\"after\":\"yesterday\"}",
                    "{\"after\":\"2026-10-05\",\"before\":\"2026-10-01\"}",
                    "{\"measureMin\":1}",                                             // a range without its Measure
                    "{\"measureColumn\":\"amount\",\"measureMin\":5,\"measureMax\":1}",
                    "{\"min\":-1}", "{\"top\":0}", "{\"top\":501}", "{\"kinds\":\"p2p\"}")) {
                HttpResponse<String> r = post(c, "pays_ds", "{\"query\":" + bad + "}");
                assertEquals(422, r.statusCode(), bad + " -> " + r.body());
            }
            HttpResponse<String> noKind = post(c, "pairs_ds", "{\"query\":{\"kinds\":[\"p2p\"]}}");
            assertEquals(422, noKind.statusCode(), noKind.body());
            assertTrue(noKind.body().contains("kind of link"), noKind.body());
            HttpResponse<String> noTime = post(c, "pairs_ds", "{\"query\":{\"after\":\"2026-10-01\"}}");
            assertEquals(422, noTime.statusCode(), noTime.body());
        }
    }

    @Test
    void thePresetListOffersTheRoleColumnsAndTheSavedPresetsTheDatasetCanAnswer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: none\n")) {
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            registry.write("preset-query", "busiest_payers", Map.of("label", "Busiest payers", "description", "Who sends the most",
                    "query", Map.of("by", "from", "rank", "sum", "sumColumn", "amount")));
            registry.write("preset-query", "busiest", Map.of("label", "Busiest", "query", Map.of("by", "from")));
            registry.write("preset-query", "broken", Map.of("label", "Broken", "query", Map.of("sql", "1")));

            JsonNode pays = JSON.readTree(get(c, "/inv/presets?dataset=pays_ds").body()).get("data");
            assertEquals("ts", pays.at("/columns/time/0").asText(), pays.toString());
            assertEquals("amount", pays.at("/columns/measure/0/column").asText());
            assertEquals("USD", pays.at("/columns/measure/0/unit").asText());
            assertEquals("fee", pays.at("/columns/measure/1/column").asText());
            List<String> saved = new ArrayList<>();
            for (JsonNode s : pays.get("saved")) saved.add(s.get("id").asText());
            assertEquals(List.of("busiest", "busiest_payers"), saved.stream().sorted().toList(), "a stored query that does not parse is not offered");

            // pairs_ds has no Measure: the saved sum is not offered there (Q-5)
            JsonNode pairs = JSON.readTree(get(c, "/inv/presets?dataset=pairs_ds").body()).get("data");
            assertEquals(1, pairs.get("saved").size(), pairs.toString());
            assertEquals("busiest", pairs.at("/saved/0/id").asText());
            assertEquals(0, pairs.at("/columns/measure").size());
        }
    }

    @Test
    void theSavedListHidesWhatTheCallerMayNotViewAndNeverEchoesARawId(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: all\n")) {
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            registry.write("preset-query", "mine", Map.of("label", "Mine", "owner", "analyst-1", "shares", List.of(),
                    "query", Map.of("by", "to", "from", A)));   // a raw From, as if saved while masking was off
            registry.write("preset-query", "theirs", Map.of("label", "Theirs", "owner", "analyst-2", "shares", List.of(),
                    "query", Map.of("by", "from")));
            Authenticators.forTest(ex -> Optional.of(new Subject("analyst-1", Set.of())));
            try {
                HttpResponse<String> r = get(c, "/inv/presets?dataset=pays_ds");
                assertEquals(200, r.statusCode(), r.body());
                for (String raw : RAW) assertFalse(r.body().contains(raw), "leaked the raw id " + raw + ": " + r.body());
                JsonNode saved = JSON.readTree(r.body()).at("/data/saved");
                assertEquals(1, saved.size(), "a preset shared with someone else is not there for this caller: " + saved);
                assertEquals("mine", saved.at("/0/id").asText());
                assertTrue(saved.at("/0/query/from").asText().startsWith("masked:"), saved.toString());
            } finally {
                Authenticators.forTest(null);
            }
        }
    }

    @Test
    void aTimeoutIsASentenceAndEveryRunIsAuditedWithoutEntityValues(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root, "masking_mode: none\n")) {
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                ask(c, "pays_ds", "{\"by\":\"to\",\"from\":\"" + A + "\"}", true);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            Event e = seen.stream().filter(x -> LinkEventTypes.LINK_QUERY_RUN.equals(x.type())).findFirst().orElseThrow();
            assertEquals("pays_ds", e.attributes().get("dataset"));
            for (String raw : RAW) assertFalse(String.valueOf(e).contains(raw), "no entity value in the audit: " + e);

            ControlApiEntityProfileScaleTest.Faulty f = new ControlApiEntityProfileScaleTest.Faulty();
            f.error = "INTERRUPT Error: Interrupted!";
            f.fail = sql -> sql.contains("COUNT(DISTINCT");
            com.gamma.la.core.DatasetProviders.forTest(f);
            try {
                HttpResponse<String> r = post(c, "pays_ds", "{\"query\":{\"by\":\"from\"}}");
                assertEquals(422, r.statusCode(), r.body());
                assertTrue(r.body().contains("took longer than this Space allows"), r.body());
                assertFalse(r.body().contains("INTERRUPT"), r.body());
            } finally {
                com.gamma.la.core.DatasetProviders.forTest(null);
            }
        }
    }
}
