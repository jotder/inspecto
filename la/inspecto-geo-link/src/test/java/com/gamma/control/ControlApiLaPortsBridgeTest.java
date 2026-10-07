package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.geolink.EngineDatasetProvider;
import com.gamma.geolink.HostCasePort;
import com.gamma.la.core.CasePorts;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.ConditionSql;
import com.gamma.query.DatasetRead;
import com.gamma.query.QueryExecutor;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA separation D-1 step 5b/6 — the BRIDGE. (1) With the module on the classpath both Link Analysis ports are bound through
 * {@code META-INF/services}; (2) the engine-backed {@link DatasetProvider} returns exactly what {@code DatasetRead} /
 * {@code QueryExecutor} / {@code ConditionSql} return for a known Dataset; (3) with each port UNBOUND (forced absent, as a
 * bundle without the bridge would be) the routes that need it report it - a clean 503 / "not installed", never a 500 or a
 * stack trace - over the real HTTP dispatcher.
 *
 * <p>Package com.gamma.control, test scope: the same split-package technique as the sibling HTTP tests, so
 * {@code new ControlApi(svc, 0)} is reachable.
 */
class ControlApiLaPortsBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        DatasetProviders.forTestAbsent(false);
        CasePorts.forTestAbsent(false);
        CaseTeamObjectEngine.CASES = null;
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('a','b','voice'),('a','c','sms'),('b','d','voice'))"
                            + " AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void theBridgeBindsBothPortsThroughServiceLoader() {
        assertInstanceOf(EngineDatasetProvider.class, DatasetProviders.require());
        assertInstanceOf(HostCasePort.class, CasePorts.active().orElseThrow());
    }

    /** The adapter contract: same Dataset, same answers as the engine it wraps. */
    @Test
    void theEngineProviderReturnsWhatTheEngineReturns(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            DatasetProvider p = DatasetProviders.require();

            Map<String, Object> viaEngine = DatasetRead.dataset(root, "calls_ds").orElseThrow();
            assertEquals(viaEngine, p.dataset(root, "calls_ds").orElseThrow());
            assertTrue(p.dataset(root, "nope").isEmpty());
            assertEquals(DatasetRead.datasets(root).stream().map(d -> d.name()).toList(),
                    p.datasets(root).stream().map(DatasetProvider.Entry::name).toList());
            assertEquals(List.of("calls_ds"), p.datasets(root).stream().map(DatasetProvider.Entry::name).toList());

            String relation = DatasetRead.relationSql(viaEngine, root, root);
            assertEquals(relation, p.relationSql(viaEngine, root, root));

            String sql = "SELECT caller, callee FROM calls_ds ORDER BY caller, callee";
            QueryExecutor.Result want = QueryExecutor.run(new QueryExecutor.Request("calls_ds", relation, sql, 2, 0, List.of(), List.of()));
            DatasetProvider.Result got = p.run(new DatasetProvider.Request("calls_ds", relation, sql, 2, 0, List.of(), List.of()));
            assertEquals(want.rows(), got.rows());
            assertEquals(want.truncated(), got.truncated());
            assertTrue(got.truncated(), "limit 2 of 3 rows");
            assertEquals(want.rowCount(), got.rowCount());
            assertEquals(want.columns().stream().map(col -> col.name() + ":" + col.type()).toList(),
                    got.columns().stream().map(col -> col.name() + ":" + col.type()).toList());

            // the planned form sees the REAL column names before it builds the statement
            List<String>[] seen = new List[1];
            DatasetProvider.Result planned = p.runPlanned("calls_ds", relation, com.gamma.sql.SqlSandboxPolicy.defaultPolicy(), cols -> {
                seen[0] = cols;
                return new DatasetProvider.Request("calls_ds", relation, "SELECT count(*) AS n FROM calls_ds", 10, 0, List.of(), List.of());
            });
            assertTrue(seen[0].containsAll(List.of("caller", "callee", "channel")), String.valueOf(seen[0]));
            assertEquals(3L, ((Number) planned.rows().get(0).get("n")).longValue());

            Map<String, Object> filter = Map.of("kind", "group", "op", "and", "children", List.of(
                    Map.of("kind", "cond", "field", "channel", "op", "eq", "value", "voice")));
            assertEquals(ConditionSql.predicate(filter), p.predicate(filter));
        }
    }

    @Test
    void anUnboundDatasetPortIsACleanServiceUnavailableOverHttp(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            DatasetProviders.forTestAbsent(true);
            for (String[] r : new String[][]{
                    {"POST", "/inv/projection", "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}"},
                    {"GET", "/inv/schema/relationships", null},
                    {"POST", "/geo/projection", "{\"dataset\":\"calls_ds\",\"latCol\":\"caller\",\"lonCol\":\"callee\"}"}}) {
                HttpResponse<String> res = send(c, r[0], r[1], r[2], null);
                assertEquals(503, res.statusCode(), r[0] + " " + r[1] + " -> " + res.body());
                JsonNode err = JSON.readTree(res.body()).get("error");
                assertEquals("CAPABILITY_UNAVAILABLE", err.get("errorCode").asText(), res.body());
                assertTrue(err.get("message").asText().contains("no Dataset provider"), res.body());
                assertFalse(res.body().contains("Exception") || res.body().contains("\tat "), "no stack trace: " + res.body());
            }
            // the other routes of the module are untouched: snapshots need no Dataset
            assertEquals(200, send(c, "GET", "/inv/snapshots", null, null).statusCode());
        }
    }

    @Test
    void anUnboundCasePortMeansCaseManagementIsNotInstalled(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Set<String> all = Set.of("canManageIncidents", "canAuthorAlertRules");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", all));
            case "Bearer member" -> Optional.of(new Subject("analyst-2", all));
            default -> Optional.empty();
        });
        // Case management IS present in the host - but the port is unbound, so Link Analysis must behave as if it were not.
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root)) {
            CasePorts.forTestAbsent(true);
            String create = "{\"id\":\"inv-a\",\"purpose\":\"p\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                    + "\"targetCol\":\"callee\",\"caseRef\":\"CASE-1\"}";
            assertEquals(200, send(c, "POST", "/inv/investigations", create, "Bearer owner").statusCode());
            JsonNode s = JSON.readTree(send(c, "GET", "/inv/investigations/inv-a/case", null, "Bearer owner").body()).get("data");
            assertEquals("CASE-1", s.get("caseRef").asText());
            assertFalse(s.get("sharing").asBoolean(), s.toString());
            assertTrue(s.get("reason").asText().contains("not installed"), s.toString());
            assertEquals(404, send(c, "GET", "/inv/investigations/inv-a/log", null, "Bearer member").statusCode(),
                    "an unbound port grants nothing, even to the Case's assignee");

            CasePorts.forTestAbsent(false);   // bound again: the same link now shares with the assignee
            assertEquals(200, send(c, "GET", "/inv/investigations/inv-a/log", null, "Bearer member").statusCode());
        }
    }
}
