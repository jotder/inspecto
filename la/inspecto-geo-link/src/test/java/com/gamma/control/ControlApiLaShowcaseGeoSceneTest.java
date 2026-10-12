package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.util.ToonHelper;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-showcase} GEO scene (operator 2026-10-12): the saved Geo Views the template ships are read with the
 * real TOON loader, the CSV landing files they sit over are loaded as VALUES-backed Datasets (the pattern of
 * {@link ControlApiLaShowcaseFiguresTest}), and the real {@code POST /geo/projection} / {@code POST /geo/routes} answer
 * for the ring accounts. SYNTHETIC data: the point is that every account has a place and the stories draw routes.
 */
class ControlApiLaShowcaseGeoSceneTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path TPL = Path.of("..", "..", "spaces", "_templates", "la-showcase");
    private static final Path INBOX = TPL.resolve("data").resolve("inbox");
    private static final Path VIEWS = TPL.resolve("config").resolve("registry").resolve("geo-map-views");
    private final HttpClient client = HttpClient.newHttpClient();

    /** Every row of one template CSV as a VALUES select over its header's columns, all VARCHAR except the named numerics. */
    static String valuesSql(Path csv, Set<String> numeric, java.util.function.Predicate<Map<String, String>> keep) throws Exception {
        List<String> lines = Files.readAllLines(csv);
        String[] head = lines.get(0).split(",", -1);
        List<String> rows = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] c = l.split(",", -1);
            Map<String, String> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < head.length; i++) m.put(head[i], c[i]);
            if (!keep.test(m)) continue;
            List<String> cells = new ArrayList<>();
            for (String h : head) cells.add(numeric.contains(h) ? m.get(h) : "'" + m.get(h).replace("'", "''") + "'");
            rows.add("(" + String.join(",", cells) + ")");
        }
        return "SELECT * FROM (VALUES " + String.join(",", rows) + ") AS t(" + String.join(",", head) + ")";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> routes(String view) throws Exception {
        Map<String, Object> v = ToonHelper.load(VIEWS.resolve(view + ".toon").toString());
        assertEquals("od-routes", v.get("sourceId"), view);
        return (Map<String, Object>) ((Map<String, Object>) v.get("query")).get("routes");
    }

    private JsonNode post(int port, String route, Map<String, Object> body) throws Exception {
        Map<String, Object> wire = new java.util.LinkedHashMap<>(body);   // the SPA's GeoService sends datasetId as 'dataset'
        wire.put("dataset", wire.remove("datasetId"));
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/" + route))
                .method("POST", BodyPublishers.ofString(JSON.writeValueAsString(wire))).build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static final Set<String> LEG_NUM = Set.of("FROM_LAT", "FROM_LON", "TO_LAT", "TO_LON", "TRANSFERS", "TOTAL_AMOUNT");
    private static final Set<String> LOC_NUM = Set.of("LAT", "LON");

    private interface Check { void run(int port) throws Exception; }

    private void withApi(Path cfg, Path root, String locationsSql, Check check) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                ViewStore views = new ViewStore(root.resolve("views"));
                ComponentStore store = new ComponentStore(root.resolve("registry"));
                views.write(new ViewDefinition("loc_view", "flow-x", List.of(), locationsSql, "2026-10-12T00:00:00Z"));
                store.write("dataset", "mule_account_locations_dataset", Map.of("view", "loc_view"));
                for (String scene : List.of("ring", "cashout")) {
                    views.write(new ViewDefinition(scene + "_view", "flow-x", List.of(),
                            valuesSql(INBOX.resolve("mule_geo_legs").resolve("MULE_GEO_LEGS_20260901.csv"), LEG_NUM,
                                    m -> scene.equals(m.get("SCENE"))), "2026-10-12T00:00:00Z"));
                    store.write("dataset", "mule_" + scene + "_legs_dataset", Map.of("view", scene + "_view"));
                }
                check.run(api.port());
            } finally {
                api.close();
                svc.close();
            }
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static String locationsSql() throws Exception {
        return valuesSql(INBOX.resolve("mule_account_locations").resolve("MULE_ACCOUNT_LOCATIONS_20260901.csv"), LOC_NUM, m -> true);
    }

    @Test
    void theRingRoutesDrawTheStoryAcrossEurope(@TempDir Path cfg, @TempDir Path root) throws Exception {
        withApi(cfg, root, locationsSql(), port -> {
            JsonNode d = post(port, "geo/routes", routes("mule_ring_route_map"));
            assertEquals(0, d.get("skipped").asInt(), "every ring leg has both ends placed");
            Set<String> pairs = new HashSet<>();
            for (JsonNode r : d.get("routes")) pairs.add(r.get("from").asText() + ">" + r.get("to").asText());
            for (String p : List.of("ep:SMURF-01>ep:MULE-HUB-01", "ep:MULE-HUB-01>ep:RELAY-01", "ep:RELAY-02>ep:OFFSHORE-77",
                    "ep:SHELL-A>ep:SHELL-B", "ep:SHELL-D>ep:SHELL-A"))
                assertTrue(pairs.contains(p), p + " is on the ring map: " + pairs);
            assertEquals(12 + 2 + 2 + 4, d.get("routes").size(), "12 structuring + 2 pass-through + 2 offshore + 4 loop legs");
            double offshoreLon = -999;
            for (JsonNode p : d.get("points")) if (p.get("label").asText().equals("OFFSHORE-77")) offshoreLon = p.get("lon").asDouble();
            assertEquals(-81.37, offshoreLon, 0.05, "the offshore end sits at George Town");
        });
    }

    @Test
    void theCashOutRoutesConvergeOnTill06(@TempDir Path cfg, @TempDir Path root) throws Exception {
        withApi(cfg, root, locationsSql(), port -> {
            JsonNode d = post(port, "geo/routes", routes("mule_cashout_till_map"));
            assertEquals(0, d.get("skipped").asInt());
            int intoTill06 = 0;
            for (JsonNode r : d.get("routes"))
                if (r.get("to").asText().equals("ep:TILL-06") && r.get("kind").asText().equals("till_06")) intoTill06++;
            assertEquals(8, intoTill06, "all eight CASHER accounts cash out through TILL-06");
            for (JsonNode p : d.get("points"))
                if (p.get("label").asText().equals("TILL-06")) assertEquals(48.86, p.get("lat").asDouble(), 0.05, "TILL-06 is in Paris");
        });
    }

    @Test
    void everyAccountOfTheCorpusHasAPlaceOnTheAccountMap(@TempDir Path cfg, @TempDir Path root) throws Exception {
        withApi(cfg, root, locationsSql(), port -> {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> projection = (Map<String, Object>) ((Map<String, Object>) ToonHelper
                    .load(VIEWS.resolve("mule_account_locations_map.toon").toString()).get("query")).get("projection");
            body.putAll(projection);
            JsonNode d = post(port, "geo/projection", body);
            Set<String> placed = new HashSet<>();
            for (JsonNode p : d.get("points")) placed.add(p.get("label").asText());
            assertEquals(0, d.get("skipped").asInt());
            Set<String> accounts = new HashSet<>();
            for (String f : List.of("TRANSFERS_20260901.csv", "TRANSFERS_20260902.csv", "TRANSFERS_20260903.csv", "TRANSFERS_20260904.csv",
                    "TRANSFERS_20260906.csv")) {
                Path p = Files.exists(INBOX.resolve("mule_transfers").resolve(f)) ? INBOX.resolve("mule_transfers").resolve(f)
                        : TPL.resolve("data").resolve("staged").resolve("mule_transfers").resolve(f);
                List<String> lines = Files.readAllLines(p);
                for (String l : lines.subList(1, lines.size())) {
                    String[] c = l.split(",", -1);
                    accounts.add(c[1]);
                    accounts.add(c[2]);
                }
            }
            Set<String> missing = new HashSet<>(accounts);
            missing.removeAll(placed);
            assertTrue(missing.isEmpty(), "accounts with no location: " + missing);
            assertTrue(placed.containsAll(List.of("SMURF-12", "MULE-HUB-01", "TILL-06", "SHELL-C")));
        });
    }
}
