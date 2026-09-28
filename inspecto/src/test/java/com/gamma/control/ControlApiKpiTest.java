package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `ASSURE-KPI-DEFINITIONS-1` over real HTTP, every test WITH an armed Authenticator (with no Subject
 * {@code withCapability} and the R3 sharing check are no-ops, so a test would pass against an ungated route):
 * the {@code kpi} component's save gate on {@code /components/kpi}, {@code GET /kpis/{id}/value}, the Dataset
 * sharing boundary, the maker-checker hold, and {@code POST /requirements/{id}/kpi}.
 */
class ControlApiKpiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALICE = "Bearer alice", BOB = "Bearer bob", ADMIN = "Bearer admin",
            BUSINESS = "Bearer business";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** alice / bob: pipeline developers (author, no access admin); admin: approver + triager; business: nothing. */
    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (BUSINESS.equals(h)) return Optional.of(new Subject("biz-1", Set.of()));
            String[] who = switch (h) {
                case ALICE -> new String[] {"alice", "pipeline-developer"};
                case BOB -> new String[] {"bob", "pipeline-developer"};
                case ADMIN -> new String[] {"admin-1", "admin"};
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

    /** Orders in July and August 2026; {@code extra} is merged into the Dataset (e.g. an R3 envelope). */
    private static void seedOrders(Ctx c, String datasetId, Map<String, Object> extra) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("orders_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES (DATE '2026-07-02', 10.0), (DATE '2026-08-01', 20.0),"
                        + " (DATE '2026-08-10', 30.0)) AS t(order_date, amount)", "2026-08-01T00:00:00Z"));
        Map<String, Object> ds = new java.util.LinkedHashMap<>(Map.of("view", "orders_view"));
        ds.putAll(extra);
        new ComponentStore(c.root.resolve("registry")).write("dataset", datasetId, ds);
    }

    private static final String KPI = """
            {"id":"revenue","title":"Revenue","dataset":"orders","measure":"sum(amount)","timeField":"order_date",
             "grain":"month","comparison":"previous","direction":"up","target":60,
             "bands":{"green":60,"amber":40},"unit":"SAR","format":{"style":"currency","currency":"SAR"}}""";

    // ── save gate ────────────────────────────────────────────────────────────────

    @Test
    void aValidKpiSavesThroughTheComponentsDoorAndEvaluates(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            JsonNode saved = data(send(c, "POST", "/components/kpi", KPI, ALICE), 200);
            assertEquals("alice", saved.at("/content/owner").asText(), "the author is stamped as the KPI's owner");

            JsonNode v = data(send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13", null, BOB), 200);
            assertEquals(50.0, v.get("value").asDouble(), 1e-9);
            assertEquals(10.0, v.get("comparisonValue").asDouble(), 1e-9);
            assertEquals(40.0, v.get("delta").asDouble(), 1e-9);
            assertEquals(400.0, v.get("deltaPct").asDouble(), 1e-9);
            assertEquals("AMBER", v.get("band").asText());
            assertEquals("warning", v.get("tone").asText());
            assertEquals("2026-08-01", v.at("/period/from").asText());
            assertEquals("2026-08-14", v.at("/period/to").asText());
            assertEquals("2026-07-01", v.at("/comparisonPeriod/from").asText());
            assertEquals(60.0, v.get("target").asDouble(), 1e-9);
            assertEquals("currency", v.at("/format/style").asText());
            assertEquals("alice", v.get("owner").asText());

            // history comes free with the component store: an edit archives the prior copy
            data(send(c, "PUT", "/components/kpi/revenue", KPI.replace("\"target\":60", "\"target\":45"), ALICE), 200);
            assertEquals(1, data(send(c, "GET", "/components/kpi/revenue/versions", null, ALICE), 200).size());
            JsonNode edited = data(send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13", null, ALICE), 200);
            assertEquals(45.0, edited.get("target").asDouble(), 1e-9, "the tile reads the edited definition");
            assertEquals("AMBER", edited.get("band").asText(), "with bands, the bands decide — not the target");
        }
    }

    @Test
    void aMissingMeasureIsRefusedAtSave(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            assertTrue(error(send(c, "POST", "/components/kpi", KPI.replace("\"orders\"", "\"nope\""), ALICE), 422)
                    .contains("kpi dataset 'nope' does not exist"));
            assertTrue(error(send(c, "POST", "/components/kpi", KPI.replace("sum(amount)", "sum(price)"), ALICE), 422)
                    .contains("measure field 'price' is not in the Schema"));
            assertTrue(error(send(c, "POST", "/components/kpi", KPI.replace("\"order_date\"", "\"shipped\""), ALICE), 422)
                    .contains("timeField 'shipped' is not in the Schema"));
            assertTrue(error(send(c, "POST", "/components/kpi", KPI.replace("\"month\"", "\"fortnight\""), ALICE), 422)
                    .contains("unknown kpi grain"));
            assertTrue(error(send(c, "POST", "/components/kpi",
                    KPI.replace("\"green\":60,\"amber\":40", "\"green\":40,\"amber\":60"), ALICE), 422)
                    .contains("not ordered"));
            assertFalse(new ComponentStore(root.resolve("registry")).exists("kpi", "revenue"), "nothing written");
        }
    }

    // ── the value route's gates ──────────────────────────────────────────────────

    @Test
    void theValueRouteFailsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            data(send(c, "POST", "/components/kpi", KPI, ALICE), 200);
            assertEquals(404, send(c, "GET", "/kpis/ghost/value", null, ALICE).statusCode());
            assertEquals(400, send(c, "GET", "/kpis/..x/value", null, ALICE).statusCode());
            assertEquals(400, send(c, "GET", "/kpis/revenue/value?asOf=13-08-2026", null, ALICE).statusCode());
            assertEquals(200, send(c, "GET", "/kpis/revenue/value", null, ALICE).statusCode(), "asOf defaults to today");
            // a stored KPI that no longer validates (a hand edit) → 422, not a wrong number
            new ComponentStore(root.resolve("registry")).write("kpi", "broken",
                    Map.of("dataset", "orders", "measure", "sum(amount)", "timeField", "order_date", "grain", "hour"));
            assertTrue(error(send(c, "GET", "/kpis/broken/value", null, ALICE), 422).contains("is invalid"));
        }
    }

    @Test
    void aTimeFieldThatIsNotADateOrTimestampIsRefusedAtSave(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            String refused = error(send(c, "POST", "/components/kpi", KPI.replace("\"order_date\"", "\"amount\""), ALICE), 422);
            assertTrue(refused.contains("'amount' is DECIMAL") && refused.contains("DATE, TIMESTAMP or TIMESTAMPTZ"), refused);
            assertEquals(200, send(c, "POST", "/components/kpi", KPI, ALICE).statusCode(), "a DATE column saves");
        }
    }

    @Test
    void aFutureOrUnplaceableAsOfIsA400AndTheZoneIsReported(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            data(send(c, "POST", "/components/kpi", KPI, ALICE), 200);
            String tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("UTC")).plusDays(1).toString();
            assertTrue(error(send(c, "GET", "/kpis/revenue/value?asOf=" + tomorrow, null, ALICE), 400).contains("in the future"));
            assertEquals(400, send(c, "GET", "/kpis/revenue/value?asOf=%2B999999999-12-31T23:59:59Z", null, ALICE).statusCode());
            assertEquals(400, send(c, "GET", "/kpis/revenue/value?asOf=-999999999-01-01T00:00:00Z", null, ALICE).statusCode());
            assertEquals(400, send(c, "GET", "/kpis/revenue/value?asOf=%2B1000000001-01-01T00:00:00Z", null, ALICE).statusCode());
            JsonNode today = data(send(c, "GET", "/kpis/revenue/value", null, ALICE), 200);
            assertEquals("UTC", today.get("timezone").asText(), "no timezone on the KPI and no Space setting ⇒ UTC");
            assertEquals(java.time.LocalDate.now(java.time.ZoneId.of("UTC")).toString(), today.get("asOf").asText());
        }
    }

    @Test
    void theSpaceDefaultTimezoneAppliesAndAKpisOwnZoneWins(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            data(send(c, "POST", "/components/kpi", KPI, ALICE), 200);
            data(send(c, "POST", "/components/kpi", KPI.replace("\"revenue\"", "\"revenue_ny\"")
                    .replace("\"grain\"", "\"timezone\":\"America/New_York\",\"grain\""), ALICE), 200);
            String instant = "2026-08-13T20:00:00Z";   // 14 Aug in Kolkata, 13 Aug in UTC and New York

            assertEquals("UTC", data(send(c, "GET", "/settings/timezone", null, ALICE), 200).get("effectiveTimezone").asText());
            assertTrue(error(send(c, "PUT", "/settings/timezone", "{\"timezone\":\"Mars/Olympus\"}", ALICE), 422)
                    .contains("IANA zone name"));
            assertEquals(422, send(c, "PUT", "/settings/timezone", "{\"timezone\":\"+05:30\"}", ALICE).statusCode(),
                    "an offset form is refused, as on a KPI");
            assertEquals(403, send(c, "PUT", "/settings/timezone", "{\"timezone\":\"Asia/Kolkata\"}", BUSINESS).statusCode());
            JsonNode set = data(send(c, "PUT", "/settings/timezone", "{\"timezone\":\"Asia/Kolkata\"}", ALICE), 200);
            assertEquals("Asia/Kolkata", set.get("timezone").asText());

            JsonNode v = data(send(c, "GET", "/kpis/revenue/value?asOf=" + instant, null, ALICE), 200);
            assertEquals("Asia/Kolkata", v.get("timezone").asText(), "no zone on the KPI ⇒ the Space default");
            assertEquals("2026-08-14", v.get("asOf").asText());
            JsonNode ny = data(send(c, "GET", "/kpis/revenue_ny/value?asOf=" + instant, null, ALICE), 200);
            assertEquals("America/New_York", ny.get("timezone").asText(), "the KPI's own zone wins");
            assertEquals("2026-08-13", ny.get("asOf").asText());

            data(send(c, "PUT", "/settings/timezone", "{\"timezone\":null}", ALICE), 200);
            assertEquals("UTC", data(send(c, "GET", "/kpis/revenue/value?asOf=" + instant, null, ALICE), 200)
                    .get("timezone").asText(), "cleared ⇒ UTC again");
        }
    }

    // ── the Dataset sharing boundary ─────────────────────────────────────────────

    @Test
    void aKpiNeverReadsADatasetItsCallerCannotRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // alice's Dataset, shared with nobody: bob cannot read it directly…
            seedOrders(c, "orders", Map.of("owner", "alice", "shares", List.of()));
            assertEquals(404, send(c, "GET", "/datasets/orders/rows", null, BOB).statusCode());
            // …so he cannot save a KPI over it — refused exactly as a Dataset that does not exist…
            String hidden = error(send(c, "POST", "/components/kpi", KPI, BOB), 422);
            String absent = error(send(c, "POST", "/components/kpi", KPI.replace("\"orders\"", "\"ghost\""), BOB), 422);
            assertEquals(absent.replace("ghost", "orders"), hidden, "hidden reads as absent");
            // …and alice's KPI over it answers him as though the Dataset were not there, with no value.
            data(send(c, "POST", "/components/kpi", KPI, ALICE), 200);
            HttpResponse<String> r = send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13", null, BOB);
            assertEquals("kpi 'revenue': no dataset 'orders'", error(r, 404));
            // Assert on the KEY, not the digits: the error body carries a random correlationId that may contain "50".
            assertFalse(r.body().contains("\"value\""), "no value leaks: " + r.body());
            assertEquals(50.0, data(send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13", null, ALICE), 200)
                    .get("value").asDouble(), 1e-9);
        }
    }

    @Test
    void aKpiSharedAwayIsAbsent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            data(send(c, "POST", "/components/kpi", KPI.replace("\"id\":\"revenue\",", "\"id\":\"revenue\",\"shares\":[],"),
                    ALICE), 200);
            assertEquals(404, send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13", null, BOB).statusCode());
            assertEquals(200, send(c, "GET", "/kpis/revenue/value?asOf=2026-08-13", null, ALICE).statusCode());
        }
    }

    // ── maker-checker ────────────────────────────────────────────────────────────

    @Test
    void aKpiWriteReachesTheMakerCheckerHold(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            data(send(c, "PUT", "/settings/approval", "{\"approval\":{\"kpi\":{\"required\":true}}}", ADMIN), 200);
            JsonNode held = data(send(c, "POST", "/components/kpi", KPI, ALICE), 202);
            assertEquals("pending", held.get("status").asText(), held.toString());
            assertFalse(new ComponentStore(root.resolve("registry")).exists("kpi", "revenue"), "held, not written");
        }
    }

    // ── a delivered kpi Requirement creates a KPI ────────────────────────────────

    private void deliveredRequirement(Ctx c, String id, String kind) throws Exception {
        data(send(c, "POST", "/requirements", "{\"id\":\"" + id + "\",\"title\":\"Refund exposure\",\"kind\":\"" + kind
                + "\",\"target\":35,\"comparator\":\"<=\",\"unit\":\"SAR\"}", BUSINESS), 200);
        data(send(c, "POST", "/requirements/" + id + "/decision", "{\"accept\":true}", ADMIN), 200);
        data(send(c, "POST", "/requirements/" + id + "/deliver", "{}", ADMIN), 200);
    }

    private static final String FROM_REQ = """
            {"dataset":"orders","measure":"sum(amount)","timeField":"order_date","grain":"month"}""";

    @Test
    void aDeliveredKpiRequirementCreatesAKpiOnlyByAnExplicitAuthoringAction(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            deliveredRequirement(c, "refunds", "kpi");
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            assertFalse(store.exists("kpi", "refunds"), "delivering does not create a KPI by itself");

            assertEquals(403, send(c, "POST", "/requirements/refunds/kpi", FROM_REQ, BUSINESS).statusCode(),
                    "creating a KPI authors a component — canAuthorWorkbench");
            JsonNode kpi = data(send(c, "POST", "/requirements/refunds/kpi", FROM_REQ, ALICE), 200);
            assertEquals("down", kpi.get("direction").asText(), "'<=' on the Requirement ⇒ lower is better");
            assertEquals(35, kpi.get("target").asInt());
            assertEquals("SAR", kpi.get("unit").asText());
            assertEquals("Refund exposure", kpi.get("title").asText());
            assertEquals("refunds", kpi.get("requirement").asText());
            assertEquals("refunds", store.get("requirement", "refunds").orElseThrow().content().get("kpi"));

            JsonNode v = data(send(c, "GET", "/kpis/refunds/value?asOf=2026-08-13", null, ALICE), 200);
            assertEquals("RED", v.get("band").asText(), "50 > target 35, lower is better");

            assertTrue(error(send(c, "POST", "/requirements/refunds/kpi", FROM_REQ, ALICE), 409).contains("already created"));
        }
    }

    @Test
    void theRequirementActionFailsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrders(c, "orders", Map.of());
            assertEquals(404, send(c, "POST", "/requirements/ghost/kpi", FROM_REQ, ALICE).statusCode());
            deliveredRequirement(c, "report1", "report");
            assertTrue(error(send(c, "POST", "/requirements/report1/kpi", FROM_REQ, ALICE), 422).contains("not a kpi one"));
            data(send(c, "POST", "/requirements", "{\"id\":\"open1\",\"title\":\"t\",\"kind\":\"kpi\"}", BUSINESS), 200);
            assertTrue(error(send(c, "POST", "/requirements/open1/kpi", FROM_REQ, ALICE), 409).contains("only a delivered"));
            deliveredRequirement(c, "r2", "kpi");
            assertTrue(error(send(c, "POST", "/requirements/r2/kpi", FROM_REQ.replace("orders", "nope"), ALICE), 422)
                    .contains("does not exist"));
            data(send(c, "POST", "/components/kpi", KPI, ALICE), 200);
            assertTrue(error(send(c, "POST", "/requirements/r2/kpi",
                    FROM_REQ.replace("{", "{\"id\":\"revenue\","), ALICE), 409).contains("already exists"));
        }
    }
}
