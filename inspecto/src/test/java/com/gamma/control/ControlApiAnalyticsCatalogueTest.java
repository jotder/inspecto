package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /analytics/functions} (ANALYTICS-CORE-1 C8) over real HTTP: a read-only, ungated description of the Analytics
 * Kernel. There is no write-root gate to test (a read has none), so the gates are the ones a read has: an unknown
 * {@code kernel} is 422 and names the valid ones, any signed-in role may read, a write verb is refused, and the data is
 * the envelope's {@code data}. Every Step a function names must be a real Pipeline node type.
 */
class ControlApiAnalyticsCatalogueTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROUTE = "/analytics/functions";
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String[] who = switch (h) {
                case "Bearer dev" -> new String[] {"dev-1", "developer"};
                case "Bearer admin" -> new String[] {"admin-1", "admin"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private HttpResponse<String> send(ControlApi api, String method, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1" + path))
                .header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString("{}"));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private interface Body {
        void accept(ControlApi api) throws Exception;
    }

    private static void withApi(Path cfg, Body body) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1)) {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                body.accept(api);
            } finally {
                api.close();
            }
        }
    }

    @Test
    void theCatalogueListsEveryKernelWithParametersCostAndResultKind(@TempDir Path cfg) throws Exception {
        withApi(cfg, api -> {
            HttpResponse<String> r = send(api, "GET", ROUTE, "Bearer dev");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = JSON.readTree(r.body()).get("data");
            assertEquals(7, d.get("kernels").size(), "statistics, profiler, time series, forecast, similarity, grouping, graph");
            assertEquals(d.get("functions").size(), d.get("total").asInt());
            JsonNode quantile = null;
            for (JsonNode f : d.get("functions")) if (f.get("id").asText().equals("stats.quantile")) quantile = f;
            assertTrue(quantile != null, "stats.quantile is catalogued");
            assertEquals("statistics", quantile.get("kernel").asText());
            assertEquals("linearithmic", quantile.get("cost").asText());
            assertEquals("scalar", quantile.get("result").asText());
            assertEquals("q", quantile.get("params").get(0).get("name").asText());
            assertEquals(1.0, quantile.get("params").get(0).get("max").asDouble());
            assertEquals(3, quantile.get("params").get(1).get("choices").size());
        });
    }

    @Test
    void everyStepNamedIsARealPipelineNodeType(@TempDir Path cfg) throws Exception {
        Set<String> real = new HashSet<>();
        for (BuiltinNodeType t : BuiltinNodeType.values()) real.add(t.type());
        withApi(cfg, api -> {
            JsonNode d = JSON.readTree(send(api, "GET", ROUTE, "Bearer dev").body()).get("data");
            int named = 0;
            for (JsonNode f : d.get("functions")) {
                if (f.get("step").isNull()) continue;
                named++;
                assertTrue(real.contains(f.get("step").asText()), f.get("id").asText() + " names unknown Step " + f.get("step"));
            }
            assertTrue(named >= 8, "the Step-backed functions are linked, got " + named);
        });
    }

    @Test
    void theKernelFilterNarrowsTheListAndKeepsTheCounts(@TempDir Path cfg) throws Exception {
        withApi(cfg, api -> {
            JsonNode d = JSON.readTree(send(api, "GET", ROUTE + "?kernel=similarity", "Bearer dev").body()).get("data");
            assertTrue(d.get("total").asInt() >= 1);
            for (JsonNode f : d.get("functions")) assertEquals("similarity", f.get("kernel").asText());
            assertEquals(7, d.get("kernels").size(), "the kernel counts stay whole");
        });
    }

    @Test
    void anUnknownKernelIs422NamingTheValidOnes(@TempDir Path cfg) throws Exception {
        withApi(cfg, api -> {
            HttpResponse<String> r = send(api, "GET", ROUTE + "?kernel=astrology", "Bearer dev");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("time_series"), r.body());
        });
    }

    @Test
    void anyRoleMayReadButNothingMayWrite(@TempDir Path cfg) throws Exception {
        withApi(cfg, api -> {
            assertEquals(200, send(api, "GET", ROUTE, "Bearer admin").statusCode(), "ungated read");
            for (String verb : List.of("POST", "PUT", "DELETE")) {
                int code = send(api, verb, ROUTE, "Bearer admin").statusCode();
                assertNotEquals(200, code, verb + " must not succeed");
                assertTrue(code >= 400 && code < 500, verb + " -> " + code);
            }
        });
    }
}
