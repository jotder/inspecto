package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `BI-INSIGHTS-1` I-12 + I-17 over real HTTP, with an armed Authenticator: {@code GET /kpis/{id}/value?projection=true}
 * (where the month lands, or why not), and an Alert Rule on a KPI or its projection — the save gate run as the rule's
 * owner, and the sweep that evaluates it.
 */
class ControlApiKpiProjectionAlertTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALICE = "Bearer alice", BOB = "Bearer bob", OPS = "Bearer ops", BUSINESS = "Bearer business";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (BUSINESS.equals(h)) return Optional.of(new Subject("biz-1", Set.of()));
            String[] who = switch (h) {
                case ALICE -> new String[] {"alice", "pipeline-developer"};
                case BOB -> new String[] {"bob", "pipeline-developer"};
                case OPS -> new String[] {"ops-1", "operations"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
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

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String error(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).at("/error/message").asText();
    }

    /** Sales every day from 2026-01-01 to 2026-08-13: 100 on a weekday, 20 on a weekend. */
    private static void seedSales(Ctx c, Map<String, Object> extra) throws Exception {
        StringBuilder values = new StringBuilder();
        for (LocalDate d = LocalDate.parse("2026-01-01"); !d.isAfter(LocalDate.parse("2026-08-13")); d = d.plusDays(1)) {
            if (!values.isEmpty()) values.append(", ");
            values.append("(DATE '").append(d).append("', ").append(d.getDayOfWeek().getValue() >= 6 ? "20.0" : "100.0").append(")");
        }
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES " + values + ") AS t(sale_date, amount)", "2026-08-01T00:00:00Z"));
        Map<String, Object> ds = new LinkedHashMap<>(Map.of("view", "sales_view"));
        ds.putAll(extra);
        new ComponentStore(c.root.resolve("registry")).write("dataset", "sales", ds);
    }

    private static String kpi(String id, String measure, String more) {
        return "{\"id\":\"" + id + "\",\"dataset\":\"sales\",\"measure\":\"" + measure + "\",\"timeField\":\"sale_date\","
                + "\"grain\":\"month\",\"asOf\":\"latest\"" + more + "}";
    }

    @Test
    void theValueRouteAnswersWhereTheMonthLandsAndARangeIsNeverProjected(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedSales(c, Map.of());
            data(send(c, "POST", "/components/kpi", kpi("revenue", "sum(amount)",
                    ",\"target\":2000,\"bands\":{\"green\":2400,\"amber\":2200}"), ALICE), 200);
            assertEquals(401, send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13&projection=true", null, null).statusCode());

            JsonNode v = data(send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13&projection=true", null, BUSINESS), 200);
            assertEquals(980.0, v.get("value").asDouble(), 1e-9, "Aug 1–13: 9 weekdays and 4 weekend days");
            assertEquals("RED", v.get("band").asText(), "the actual so far is red");
            JsonNode p = v.get("projection");
            assertEquals("projected", p.get("status").asText(), p.toString());
            assertEquals(2300.0, p.get("value").asDouble(), 15.0, "980 + 12 weekdays and 6 weekend days left");
            assertTrue(p.get("lower").asDouble() <= p.get("value").asDouble() && p.get("value").asDouble() <= p.get("upper").asDouble());
            assertEquals(0.8, p.get("level").asDouble());
            assertTrue(p.get("probability").asDouble() > 0.99, p.toString());
            assertEquals("AMBER", p.get("band").asText(), "RAG on the projection beside RAG on the actual");
            assertEquals(18, p.get("daysLeft").asInt());
            assertEquals(7, p.at("/season/days").asInt());

            assertNull(data(send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13", null, BUSINESS), 200).get("projection"),
                    "asked only with projection=true");
            JsonNode ranged = data(send(c, "GET", "/kpis/revenue/value?from=2026-08-01&to=2026-08-13&projection=true", null,
                    BUSINESS), 200);
            assertEquals("none", ranged.at("/projection/status").asText());

            data(send(c, "POST", "/components/kpi", kpi("avg_sale", "avg(amount)", ""), ALICE), 200);
            JsonNode avg = data(send(c, "GET", "/kpis/avg_sale/value?asOf=2026-08-13&projection=true", null, BUSINESS), 200);
            assertEquals("none", avg.at("/projection/status").asText());
            assertTrue(avg.at("/projection/reason").asText().contains("does not add up"));
        }
    }

    @Test
    void aProjectionOverADatasetTheCallerCannotReadIsTheSame404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedSales(c, Map.of("owner", "alice", "shares", List.of()));
            data(send(c, "POST", "/components/kpi", kpi("revenue", "sum(amount)", ",\"target\":2000"), ALICE), 200);
            assertEquals(404, send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13&projection=true", null, BOB).statusCode());
            assertEquals(200, send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13&projection=true", null, ALICE).statusCode());
        }
    }

    private static String rule(String name, String more) {
        return "{\"name\":\"" + name + "\",\"kpi\":\"revenue\",\"severity\":\"CRITICAL\"" + more + "}";
    }

    @Test
    void aKpiAlertRuleSavesOnlyWhenItsOwnerCanReadTheKpiAndItCanBeComputed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedSales(c, Map.of());
            data(send(c, "POST", "/components/kpi", kpi("revenue", "sum(amount)", ",\"target\":2000"), ALICE), 200);
            data(send(c, "POST", "/components/kpi", kpi("bare", "sum(amount)", ""), ALICE), 200);
            data(send(c, "POST", "/components/kpi", kpi("avg_sale", "avg(amount)", ",\"target\":50"), ALICE), 200);
            data(send(c, "POST", "/components/kpi", kpi("private", "sum(amount)",
                    ",\"target\":1,\"owner\":\"bob\",\"shares\":[]"), BOB), 200);

            String chance = rule("off-track", ",\"projection\":\"probability\",\"comparator\":\"lt\",\"threshold\":0.5");
            assertEquals(401, send(c, "POST", "/alerts/rules", chance, null).statusCode());
            assertEquals(403, send(c, "POST", "/alerts/rules", chance, BUSINESS).statusCode(), "needs canAuthorAlertRules");
            JsonNode saved = data(send(c, "POST", "/alerts/rules", chance, ALICE), 200);
            assertEquals("revenue", saved.get("kpi").asText());
            assertEquals("probability", saved.get("projection").asText());
            assertEquals("alice", saved.get("owner").asText());

            assertTrue(error(send(c, "POST", "/alerts/rules", rule("ghost", ",\"threshold\":1").replace("\"revenue\"", "\"nope\""),
                    ALICE), 422).contains("does not exist"));
            assertTrue(error(send(c, "POST", "/alerts/rules", rule("bare-red", ",\"rag\":\"red\"").replace("\"revenue\"", "\"bare\""),
                    ALICE), 422).contains("no target and no bands"));
            assertTrue(error(send(c, "POST", "/alerts/rules", rule("avg-proj",
                    ",\"projection\":\"value\",\"comparator\":\"lt\",\"threshold\":1").replace("\"revenue\"", "\"avg_sale\""),
                    ALICE), 422).contains("cannot be projected"));
            assertTrue(error(send(c, "POST", "/alerts/rules", rule("not-mine", ",\"threshold\":1").replace("\"revenue\"", "\"private\""),
                    ALICE), 422).contains("may not view"), "the rule's owner may not view bob's KPI");
            assertTrue(error(send(c, "POST", "/alerts/rules", rule("bad", ",\"dataset\":\"sales\",\"threshold\":1"), ALICE), 422)
                    .contains("no dataset"));
        }
    }

    @Test
    void theSweepEvaluatesAKpiRuleOnItsProjectionAndHealsIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedSales(c, Map.of());
            data(send(c, "POST", "/components/kpi", kpi("revenue", "sum(amount)",
                    ",\"target\":2000,\"bands\":{\"green\":2400,\"amber\":2200}"), ALICE), 200);
            // asOf: latest — the KPI follows the newest day in the data (Aug 13), so the sweep reads the same month
            data(send(c, "POST", "/alerts/rules", rule("projected-amber", ",\"projection\":\"value\",\"rag\":\"amber\""), ALICE), 200);
            data(send(c, "POST", "/alerts/rules", rule("on-track", ",\"projection\":\"probability\",\"comparator\":\"lt\",\"threshold\":0.5")
                    , ALICE), 200);
            // The sweep's probe resolves its roots per call, as a running server's do (the harness cleared them after boot).
            System.setProperty("assist.write.root", root.toString());
            System.setProperty("data.dir", root.resolve("data").toString());
            try {
                JsonNode fired = data(send(c, "POST", "/alerts/evaluate", "{}", OPS), 200);
                List<String> rules = new java.util.ArrayList<>();
                fired.forEach(a -> rules.add(a.get("rule").asText()));
                assertEquals(List.of("projected-amber"), rules, "AMBER on the projection fires; a 99 % chance does not: " + fired);
                assertTrue(fired.get(0).get("message").asText().contains("RAG of the projected landing of KPI revenue is AMBER"),
                        fired.get(0).toString());
                assertEquals("kpi:revenue", fired.get(0).get("pipeline").asText());
                assertEquals(0, data(send(c, "POST", "/alerts/evaluate", "{}", OPS), 200).size(), "edge-triggered: no re-fire");
                JsonNode open = data(send(c, "GET", "/alerts", null, OPS), 200);
                assertTrue(open.toString().contains("projected-amber"), "the Alert is on the feed: " + open);
            } finally {
                System.clearProperty("assist.write.root");
                System.clearProperty("data.dir");
            }
        }
    }
}
