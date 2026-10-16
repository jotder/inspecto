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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-claims} GEO scene: the saved Geo Views the template ships are read with the real TOON loader, the shipped accident
 * extract is loaded as a VALUES-backed {@code accident_location} (the pattern of {@link ControlApiLaShowcaseGeoSceneTest}), the
 * template's OWN virtual-Dataset SQL runs over it, and the real {@code POST /geo/projection} answers. SYNTHETIC: eleven accidents
 * stack within 400 m of one invented junction, between 02:00 and 03:59, none with a police report.
 */
class ControlApiLaClaimsGeoSceneTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path TPL = Path.of("..", "..", "spaces", "_templates", "la-claims");
    private static final Path CFG = TPL.resolve("config").resolve("registry");
    private static final double JUNCTION_LAT = 52.9225 + 0.031, JUNCTION_LON = -1.4746 - 0.047;
    private final HttpClient client = HttpClient.newHttpClient();

    private static double metres(double lat, double lon) {
        double r = 6_371_000, p1 = Math.toRadians(lat), p2 = Math.toRadians(JUNCTION_LAT), dp = p2 - p1, dl = Math.toRadians(JUNCTION_LON - lon);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** The template's virtual Dataset SQL, run over the shipped accident extract standing in for the accident_location table. */
    @SuppressWarnings("unchecked")
    private static String datasetSql(String dataset) throws Exception {
        Path csv;
        try (var f = Files.list(TPL.resolve("data/inbox/accident_location"))) {
            csv = f.filter(p -> p.toString().endsWith(".csv")).findFirst().orElseThrow();
        }
        String values = ControlApiLaShowcaseGeoSceneTest.valuesSql(csv, Set.of("lat", "lon"), m -> true);
        Map<String, Object> d = ToonHelper.load(CFG.resolve("datasets/" + dataset + ".toon").toString());
        assertEquals("accident_location", d.get("sourceName"));
        return "WITH accident_location AS (SELECT accident_id, city, lat, lon, CAST(accident_at AS TIMESTAMP) AS accident_at, police_report_ref FROM ("
                + values + ")) " + d.get("sql");   // the Pipeline types accident_at TIMESTAMP
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> projection(String view, String dataset) throws Exception {
        Map<String, Object> v = ToonHelper.load(CFG.resolve("geo-map-views/" + view + ".toon").toString());
        Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) ((Map<String, Object>) v.get("query")).get("projection"));
        assertEquals(dataset, p.get("datasetId"));
        p.put("dataset", p.remove("datasetId"));   // the SPA's GeoService sends datasetId as 'dataset'
        return p;
    }

    private JsonNode project(int port, Map<String, Object> body) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/geo/projection"))
                .method("POST", BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void theAccidentMapsPlaceEveryAccidentAndTheNightCleanSubsetStacksElevenOnTheJunction(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                ViewStore views = new ViewStore(root.resolve("views"));
                ComponentStore store = new ComponentStore(root.resolve("registry"));
                for (String ds : List.of("claims_accidents_dataset", "claims_night_unreported_dataset")) {
                    views.write(new ViewDefinition(ds + "_view", "flow-x", List.of(), datasetSql(ds), "2026-10-16T00:00:00Z"));
                    store.write("dataset", ds, Map.of("view", ds + "_view"));
                }
                Path directory;
                try (var f = Files.list(TPL.resolve("data/inbox/provider_directory"))) {
                    directory = f.filter(p -> p.toString().endsWith(".csv")).findFirst().orElseThrow();
                }
                views.write(new ViewDefinition("provider_directory_view", "flow-x", List.of(),
                        ControlApiLaShowcaseGeoSceneTest.valuesSql(directory, Set.of("lat", "lon"), m -> true), "2026-10-16T00:00:00Z"));
                store.write("dataset", "provider_directory_dataset", Map.of("view", "provider_directory_view"));
                long accidents;
                try (var lines = Files.lines(Files.list(TPL.resolve("data/inbox/accident_location")).findFirst().orElseThrow())) {
                    accidents = lines.count() - 1;
                }

                JsonNode all = project(api.port(), projection("claims_accidents_map", "claims_accidents_dataset"));
                assertEquals(0, all.get("skipped").asInt(), "every accident has a place");
                assertEquals(accidents, all.get("points").size(), "one point per accident");
                int stacked = 0;
                for (JsonNode p : all.get("points")) if (metres(p.get("lat").asDouble(), p.get("lon").asDouble()) <= 400) stacked++;
                assertEquals(11, stacked, "eleven accidents within 400 m of the junction");

                JsonNode night = project(api.port(), projection("claims_junction_cluster_map", "claims_night_unreported_dataset"));
                int nightStacked = 0;
                for (JsonNode p : night.get("points")) if (metres(p.get("lat").asDouble(), p.get("lon").asDouble()) <= 400) nightStacked++;
                assertEquals(11, nightStacked, "all eleven are in the 02:00-03:59 no-police-report subset");
                JsonNode providers = project(api.port(), projection("claims_providers_map", "provider_directory_dataset"));
                assertEquals(25, providers.get("points").size(), "all 25 providers are placed");
                assertEquals(0, providers.get("skipped").asInt());
                assertTrue(night.get("points").size() >= 11 && night.get("points").size() < accidents / 4, "the subset is small: " + night.get("points").size());
            } finally {
                api.close();
                svc.close();
            }
        } finally {
            System.clearProperty("assist.write.root");
        }
    }
}
