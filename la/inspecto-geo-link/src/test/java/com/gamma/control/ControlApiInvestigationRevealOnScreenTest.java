package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.EventLog;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-FULL-CYCLE-TEST-1 phase 2 (decisions D3 and D4), over real HTTP with an ARMED Authenticator:
 * <ul>
 *   <li><b>D3</b> - a lead holding {@code canRevealLinkEntities} reveals every node on screen in ONE call, with a required
 *       reason, and the audit trail gains ONE {@code LINK_ENTITY_REVEALED} row per entity (LA-19), naming the alias and the
 *       reason, never the raw id. A reviewer and an outsider holding the same capability still get aliases.</li>
 *   <li><b>D4</b> - inside one Investigation an entity reads as ONE alias on every surface: the Working Set, the step log,
 *       and its details in a Reference (whose key column used to read the Space's alias). Personal-detail columns beside a
 *       masked id are masked with it, and a reveal of the entity reads them raw (with the reason, audited per entity).</li>
 * </ul>
 * Ids are phone-number shaped so a body scan for them cannot match ordinary words.
 */
class ControlApiInvestigationRevealOnScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003", D = "27820000004";
    private static final List<String> RAW = List.of(A, B, C, D);
    private static final String LEAD = "Bearer lead", NOCAP = "Bearer nocap", REVIEWER = "Bearer reviewer",
            OUTSIDER = "Bearer outsider";
    private static final String INV = "/inv/investigations/case-r";
    private static final String CREATE = "{\"id\":\"case-r\",\"purpose\":\"Mule referral MR-3\","
            + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String REASON = "MR-3 warrant: identify the ring";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void people() {
        Set<String> all = Set.of("canManageIncidents", "canRevealLinkEntities");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case LEAD -> Optional.of(new Subject("lead-1", all));
            case NOCAP -> Optional.of(new Subject("lead-1", Set.of("canManageIncidents")));
            case REVIEWER -> Optional.of(new Subject("reviewer-3", all));
            case OUTSIDER -> Optional.of(new Subject("outsider-4", all));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    /** Typed masking; the link ends and the Reference's key are classified MSISDN (a masked Entity Type), its details are not. */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: typed\n");
            ViewStore views = new ViewStore(writeRoot.resolve("views"));
            views.write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('" + A + "','" + B + "','voice'),('" + A + "','" + C
                            + "','sms'),('" + B + "','" + D + "','voice')) AS t(caller,callee,channel)", "2026-11-16T00:00:00Z"));
            views.write(new ViewDefinition("acct_view", "flow-x", List.of(),
                    "SELECT msisdn, holder_name, city, score FROM (VALUES ('" + A + "','Thandi Mokoena','Durban',0.82),('"
                            + B + "','Sipho Dlamini','Soweto',0.10)) AS t(msisdn,holder_name,city,score)", "2026-11-16T00:00:00Z"));
            ComponentStore reg = new ComponentStore(writeRoot.resolve("registry"));
            reg.write("dataset", "calls_ds", Map.of("view", "calls_view", "columns", List.of(
                    Map.of("name", "caller", "classification", "MSISDN"), Map.of("name", "callee", "classification", "MSISDN"))));
            reg.write("dataset", "acct_ds", Map.of("view", "acct_view",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"))));
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

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertEquals(200, r.statusCode(), method + " " + path + " as " + auth + " -> " + r.body());
        return EdgeWireTestRows.decoded(JSON.readTree(r.body()).get("data"));
    }

    private static void noRawIn(String what, String text) {
        for (String raw : RAW) assertFalse(text.contains(raw), what + " carries the raw id " + raw + ": " + text);
    }

    /** The lead's Investigation: A seeded, one expand (A, B, C on screen), the reviewer granted. Returns the on-screen aliases. */
    private List<String> onScreen(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, LEAD);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}", LEAD);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", LEAD);
        ok(c, "POST", INV + "/members", "{\"subject\":\"reviewer-3\",\"role\":\"reviewer\"}", LEAD);
        return entityIds(c, LEAD);
    }

    private List<String> entityIds(Ctx c, String auth) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode r : ok(c, "GET", INV + "/working-set", null, auth).get("rows")) out.add(r.get("entityId").asText());
        return out;
    }

    private static String reveal(List<String> tokens, String reasonJson) throws Exception {
        return "{\"tokens\":" + JSON.writeValueAsString(tokens) + (reasonJson == null ? "" : ",\"reason\":" + reasonJson) + "}";
    }

    @Test
    void aLeadRevealsEveryNodeOnScreenInOneCallWithOneAuditRowPerEntityAndAReason(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<String> tokens = onScreen(c);
            assertEquals(3, tokens.size(), tokens.toString());
            assertTrue(tokens.stream().allMatch(t -> t.startsWith("masked:")), "masked is the default: " + tokens);

            assertEquals(422, send(c, "POST", INV + "/reveal", reveal(tokens, null), LEAD).statusCode(), "a reveal needs a reason");
            assertEquals(422, send(c, "POST", INV + "/reveal", reveal(tokens, "\"  \""), LEAD).statusCode(), "a blank reason is none");
            assertEquals(422, send(c, "POST", INV + "/reveal", reveal(tokens, JSON.writeValueAsString("x".repeat(201))), LEAD)
                    .statusCode(), "a reason is at most 200 chars");
            assertEquals(403, send(c, "POST", INV + "/reveal", reveal(tokens, JSON.writeValueAsString(REASON)), NOCAP).statusCode(),
                    "leading the Investigation is not enough without See unmasked ids");

            List<Event> events = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = events::add;
            EventLog.current().addSubscriber(sub);
            JsonNode out;
            try {
                out = ok(c, "POST", INV + "/reveal", reveal(tokens, JSON.writeValueAsString(REASON)), LEAD);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            assertEquals(3, out.get("revealed").size(), "every node on screen, in one call: " + out);
            Set<String> ids = new TreeSet<>();
            for (JsonNode r : out.get("revealed")) ids.add(r.get("id").asText());
            assertEquals(Set.of(A, B, C), ids);
            assertEquals(REASON, out.get("reason").asText());

            List<Event> rows = events.stream().filter(e -> "LINK_ENTITY_REVEALED".equals(e.type())).toList();
            assertEquals(3, rows.size(), "one audit row per entity revealed: " + rows);
            Set<String> audited = new TreeSet<>();
            for (Event e : rows) {
                assertEquals(REASON, e.attributes().get("reason"), e.toString());
                assertEquals("case-r", e.attributes().get("investigationId"), e.toString());
                assertEquals("lead-1", e.attributes().get("actor"), e.toString());
                audited.add(e.attributes().get("alias"));
                assertEquals(e.attributes().get("alias"), e.attributes().get("target_id"), "the row's target is the entity: " + e);
                noRawIn("the audit row", e.toString());
            }
            assertEquals(new TreeSet<>(tokens), audited, "each row names the alias it revealed");

            assertEquals(tokens, entityIds(c, LEAD), "a reveal changes no later response: masked stays the default");
        }
    }

    @Test
    void aReviewerAndAnOutsiderHoldingTheCapabilityKeepTheAliases(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<String> tokens = onScreen(c);
            String body = reveal(tokens, JSON.writeValueAsString(REASON));
            assertEquals(403, send(c, "POST", INV + "/reveal", body, REVIEWER).statusCode(), "a reviewer reads, never reveals");
            assertEquals(404, send(c, "POST", INV + "/reveal", body, OUTSIDER).statusCode(), "an outsider does not see it at all");

            assertEquals(tokens, entityIds(c, REVIEWER), "the reviewer reads the same aliases");
            noRawIn("the reviewer's Working Set", send(c, "GET", INV + "/working-set", null, REVIEWER).body());
            noRawIn("the reviewer's step log", send(c, "GET", INV + "/log", null, REVIEWER).body());
            assertEquals(404, send(c, "GET", INV + "/working-set", null, OUTSIDER).statusCode());

            String details = "{\"dataset\":\"acct_ds\",\"keyCol\":\"msisdn\",\"investigationId\":\"case-r\",\"keys\":"
                    + JSON.writeValueAsString(tokens) + ",\"reveal\":true,\"reason\":" + JSON.writeValueAsString(REASON) + "}";
            assertEquals(403, send(c, "POST", "/inv/reference-rows", details, REVIEWER).statusCode(),
                    "nor does a reviewer read an entity's details raw");
            assertEquals(404, send(c, "POST", "/inv/reference-rows", details, OUTSIDER).statusCode());
            HttpResponse<String> masked = send(c, "POST", "/inv/reference-rows", details.replace(",\"reveal\":true", ""), REVIEWER);
            assertEquals(200, masked.statusCode(), masked.body());
            noRawIn("the reviewer's details", masked.body());
        }
    }

    @Test
    void insideOneInvestigationAnEntityReadsAsOneAliasAndItsDetailsAreMaskedWithIt(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<String> tokens = onScreen(c);
            JsonNode log = ok(c, "GET", INV + "/log", null, LEAD);
            String seeded = log.at("/entries/0/text").asText();
            String alias = tokens.stream().filter(seeded::contains).findFirst()
                    .orElseThrow(() -> new AssertionError("the step log names a Working Set alias: " + seeded + " / " + tokens));

            HttpResponse<String> r = send(c, "POST", "/inv/reference-rows", "{\"dataset\":\"acct_ds\",\"keyCol\":\"msisdn\","
                    + "\"investigationId\":\"case-r\",\"keys\":" + JSON.writeValueAsString(List.of(alias)) + "}", LEAD);
            assertEquals(200, r.statusCode(), r.body());
            noRawIn("the details", r.body());
            JsonNode row = EdgeWireTestRows.decoded(JSON.readTree(r.body()).get("data")).at("/rows/0/row");
            assertEquals(alias, row.get("msisdn").asText(),
                    "the details' key column reads this Investigation's alias, not the Space's second one: " + row);
            assertTrue(row.get("holder_name").asText().startsWith("masked:"), "a name beside a masked id is masked with it: " + row);
            assertTrue(row.get("city").asText().startsWith("masked:"), "so is the city: " + row);
            assertTrue(row.get("score").isNumber(), "an indicator score is not an identifier and stays a number: " + row);

            // the same rule for a node mapping's attributes on the exploration canvas
            JsonNode multi = ok(c, "POST", "/inv/projection/multi", "{\"nodes\":[{\"dataset\":\"acct_ds\",\"idColumn\":\"msisdn\","
                    + "\"attributes\":[\"holder_name\",\"city\"]}]}", LEAD);
            for (JsonNode n : multi.get("nodes")) {
                assertTrue(n.get("id").asText().startsWith("masked:"), n.toString());
                assertTrue(n.at("/attrs/holder_name").asText().startsWith("masked:"), "masked with its id: " + n);
                assertTrue(n.at("/attrs/city").asText().startsWith("masked:"), n.toString());
            }
        }
    }

    @Test
    void aRevealedEntitysDetailsReadRawWithTheReasonAndOneAuditRowPerEntity(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            List<String> tokens = onScreen(c);
            String keys = "{\"dataset\":\"acct_ds\",\"keyCol\":\"msisdn\",\"investigationId\":\"case-r\",\"keys\":"
                    + JSON.writeValueAsString(tokens) + ",\"reveal\":true";
            assertEquals(422, send(c, "POST", "/inv/reference-rows", keys + "}", LEAD).statusCode(), "a reveal needs a reason");
            assertEquals(403, send(c, "POST", "/inv/reference-rows", keys + ",\"reason\":\"r\"}", NOCAP).statusCode());
            assertEquals(422, send(c, "POST", "/inv/reference-rows", "{\"dataset\":\"acct_ds\",\"keyCol\":\"msisdn\",\"keys\":"
                    + JSON.writeValueAsString(tokens) + ",\"reveal\":true,\"reason\":\"r\"}", LEAD).statusCode(),
                    "a reveal is of an Investigation's entities: it names the Investigation");

            List<Event> events = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = events::add;
            EventLog.current().addSubscriber(sub);
            JsonNode d;
            try {
                d = ok(c, "POST", "/inv/reference-rows", keys + ",\"reason\":" + JSON.writeValueAsString(REASON) + "}", LEAD);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            assertEquals(2, d.get("rows").size(), "A and B have details, C has none: " + d);
            Set<String> names = new TreeSet<>();
            for (JsonNode r : d.get("rows")) {
                assertTrue(tokens.contains(r.get("key").asText()), "rows keep the key as asked: " + r);
                names.add(r.at("/row/holder_name").asText());
                assertFalse(r.at("/row/msisdn").asText().startsWith("masked:"), "unmasked with its id: " + r);
            }
            assertEquals(Set.of("Thandi Mokoena", "Sipho Dlamini"), names);
            List<Event> rows = events.stream().filter(e -> "LINK_ENTITY_REVEALED".equals(e.type())).toList();
            assertEquals(2, rows.size(), "one audit row per entity whose details were read raw: " + rows);
            for (Event e : rows) {
                assertEquals(REASON, e.attributes().get("reason"));
                assertTrue(tokens.contains(e.attributes().get("alias")), e.toString());
                assertEquals("details", e.attributes().get("via"), e.toString());
                noRawIn("the audit row", e.toString());
            }
            assertNotEquals(0, d.at("/masking/mode").asText().length());

            // under `all` the Space masks every value that may be an id whatever the key, and a revealed row may hold
            // another entity's: a reveal there lifts the key alone (fail closed); fractional scores still pass
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
            JsonNode all = ok(c, "POST", "/inv/reference-rows", keys + ",\"reason\":" + JSON.writeValueAsString(REASON) + "}", LEAD);
            assertEquals(2, all.get("rows").size(), all.toString());
            for (JsonNode r : all.get("rows")) {
                assertTrue(Set.of(A, B).contains(r.at("/row/msisdn").asText()), "the key is revealed: " + r);
                assertTrue(r.at("/row/holder_name").asText().startsWith("masked:"), "the rest stays masked under all: " + r);
                assertTrue(r.at("/row/score").isNumber(), r.toString());
            }
            assertTrue(all.at("/masking/masked").asBoolean(), all.toString());
        }
    }
}
