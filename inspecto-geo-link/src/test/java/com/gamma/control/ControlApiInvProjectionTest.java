package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INV-1 Entity Projection over a real Dataset ({@code POST /inv/projection}): distinct (source, target,
 * kind) triples with folded counts, typed link kinds, NULL-endpoint exclusion, and the fail-closed gates.
 * Also covers the ComponentStore widening: a {@code link-analysis-view} component persists via /components.
 */
/*
 * ⚠ Package com.gamma.control, in the inspecto-geo-link module — a TEST-SCOPE split package, on purpose, and the
 * same technique inspecto-policy's ControlApiPolicyEnforcementTest uses: this test constructs `new ControlApi(svc, 0)`
 * (package-private) so it drives the real dispatcher, and with the module on this classpath the routes are
 * discovered via META-INF/services exactly as in a Standard bundle. Moved verbatim from inspecto on 2026-09-07
 * (EDG-01 cell 3b); the only edit is V1Body → the local json() helper below.
 */
class ControlApiInvProjectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** A call-record dataset: caller→callee links, one duplicated pair, one NULL endpoint. */
    private void seedCalls(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES "
                        + "('alice','bob','sms'),"
                        + "('alice','bob','sms'),"
                        + "('alice','carol','call'),"
                        + "('dave',NULL,'call')"
                        + ") AS t(caller,callee,channel)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
    }

    private HttpResponse<String> project(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/projection"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> neighbors(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/projection/neighbors"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> schemaRelationships(int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/schema/relationships"))
                .GET().build(), BodyHandlers.ofString());
    }

    @Test
    void projectsTypedFoldedTriples(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","linkKindCol":"channel"}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = json(r.body());
            JsonNode rows = data.get("rows");
            assertEquals(2, rows.size(), "duplicates fold, the NULL-endpoint row is excluded: " + rows);
            // Heaviest first: alice→bob (sms) folded to count 2.
            assertEquals("alice", rows.get(0).get("source").asText());
            assertEquals("bob", rows.get(0).get("target").asText());
            assertEquals("sms", rows.get(0).get("kind").asText());
            assertEquals(2, rows.get(0).get("count").asInt());
            assertEquals(1, rows.get(1).get("count").asInt());
            assertFalse(data.get("truncated").asBoolean());
        }
    }

    @Test
    void untypedProjectionFoldsAcrossKinds(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = project(c.port,
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = json(r.body()).at("/rows");
            assertEquals(2, rows.size());
            assertTrue(rows.get(0).get("kind").isNull(), "no linkKindCol → kind is null");
        }
    }

    @Test
    void limitTruncatesHeaviestFirst(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","limit":1}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = json(r.body());
            assertEquals(1, data.get("rows").size());
            assertEquals(2, data.get("rows").get(0).get("count").asInt(), "the folded pair survives the cut");
            assertTrue(data.get("truncated").asBoolean());
        }
    }

    @Test
    void attrColsJoinTheFoldKeyAndRoundTripInOutput(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","attrCols":["channel"]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = json(r.body()).at("/rows");
            assertEquals(2, rows.size(), "channel is uniform per pair here, so the fold is unchanged: " + rows);
            JsonNode aliceBob = rows.get(0);
            assertEquals("alice", aliceBob.get("source").asText());
            assertEquals(2, aliceBob.get("count").asInt());
            assertEquals("sms", aliceBob.get("attrs").get("channel").asText());
        }
    }

    @Test
    void attrColsSplitAFoldedPairWhenTheAttributeValueDiffers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("mixed_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES "
                            + "('alice','bob','sms'),"
                            + "('alice','bob','call')"
                            + ") AS t(caller,callee,channel)",
                    "2026-07-08T00:00:00Z"));
            new ComponentStore(c.root.resolve("registry")).write("dataset", "mixed_ds", Map.of("view", "mixed_view"));

            HttpResponse<String> r = project(c.port, """
                    {"dataset":"mixed_ds","sourceCol":"caller","targetCol":"callee","attrCols":["channel"]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = json(r.body()).at("/rows");
            assertEquals(2, rows.size(), "differing attr values fold into separate rows, not one merged row: " + rows);
            for (JsonNode row : rows) assertEquals(1, row.get("count").asInt());
        }
    }

    @Test
    void neighborsReturnsOnlyRowsTouchingTheGivenValue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = neighbors(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","value":"bob"}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = json(r.body()).at("/rows");
            assertEquals(1, rows.size(), "only alice->bob touches 'bob': " + rows);
            assertEquals("alice", rows.get(0).get("source").asText());
            assertEquals("bob", rows.get(0).get("target").asText());
            assertEquals(2, rows.get(0).get("count").asInt());
        }
    }

    /**
     * A value carrying a single quote matches as both endpoints. Written against the hand-rolled
     * quote-doubling this route used to do; it now pins the bind that replaced it — the value never
     * reaches the statement text, so it cannot alter its shape whatever it contains.
     */
    @Test
    void neighborsMatchesEitherEndpointWithAQuotedValue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("names_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('a''b','x'),('y','a''b')) AS t(caller,callee)",
                    "2026-07-08T00:00:00Z"));
            new ComponentStore(c.root.resolve("registry")).write("dataset", "names_ds", Map.of("view", "names_view"));

            HttpResponse<String> r = neighbors(c.port, """
                    {"dataset":"names_ds","sourceCol":"caller","targetCol":"callee","value":"a'b"}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = json(r.body()).at("/rows");
            assertEquals(2, rows.size(), "matches as both source and target: " + rows);
        }
    }

    @Test
    void neighborsRequiresValue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            assertEquals(422, neighbors(c.port,
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}").statusCode());
        }
    }

    @Test
    void failsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            assertEquals(404, project(c.port,
                    "{\"dataset\":\"ghost\",\"sourceCol\":\"a\",\"targetCol\":\"b\"}").statusCode());
            assertEquals(422, project(c.port,
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"a b\",\"targetCol\":\"callee\"}").statusCode(),
                    "non-identifier column");
            assertEquals(422, project(c.port,
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\"}").statusCode(), "missing targetCol");
            assertEquals(422, project(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","attrCols":["a b"]}""")
                    .statusCode(), "non-identifier attrCols entry");
        }
    }

    /** Two datasets whose naming convention should be inferrable: orders.customer_id -> customers.id. */
    private void seedOrdersAndCustomers(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("customers_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES (1,'Alice'),(2,'Bob')) AS t(id,name)", "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "customers", Map.of("view", "customers_view"));
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("orders_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES (100,1),(101,2)) AS t(order_id,customer_id)", "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "orders", Map.of("view", "orders_view"));
    }

    @Test
    void schemaRelationshipsInfersFkToIdColumnByNamingConvention(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrdersAndCustomers(c);
            HttpResponse<String> r = schemaRelationships(c.port);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = json(r.body());
            assertEquals(2, data.get("datasetsScanned").asInt());
            JsonNode rels = data.get("relationships");
            boolean found = false;
            for (JsonNode rel : rels) {
                if (rel.get("fromDataset").asText().equals("orders")
                        && rel.get("fromColumn").asText().equals("customer_id")) {
                    assertEquals("customers", rel.get("toDataset").asText());
                    assertEquals("id", rel.get("toColumn").asText());
                    assertEquals("high", rel.get("confidence").asText());
                    found = true;
                }
            }
            assertTrue(found, "expected orders.customer_id -> customers.id: " + rels);
        }
    }

    @Test
    void schemaRelationshipsSkipsUnusableDatasetsWithoutFailing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrdersAndCustomers(c);
            new ComponentStore(c.root.resolve("registry")).write("dataset", "ghost_ds", Map.of());   // unbound
            HttpResponse<String> r = schemaRelationships(c.port);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = json(r.body());
            assertEquals(2, data.get("datasetsScanned").asInt());
            assertEquals(1, data.get("datasetsSkipped").asInt());
        }
    }

    private HttpResponse<String> overlapProfile(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/inv/schema/overlap-profile"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /**
     * LA-15 fixture. Two Datasets sharing BOTH column names, but only one column actually shares values:
     * {@code acct} overlaps on 2 of 4 distinct values (Jaccard 0.5), while {@code code} is a pure name
     * collision with disjoint values (Jaccard 0).
     */
    private void seedOverlap(Ctx c) throws Exception {
        ViewStore views = new ViewStore(c.root.resolve("views"));
        ComponentStore store = new ComponentStore(c.root.resolve("registry"));
        views.write(new ViewDefinition("left_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('a1','z'),('a2','z'),('a3','z')) AS t(acct,code)",
                "2026-07-08T00:00:00Z"));
        store.write("dataset", "left_ds", Map.of("view", "left_view"));
        views.write(new ViewDefinition("right_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('a2','p'),('a3','p'),('a9','p')) AS t(acct,code)",
                "2026-07-08T00:00:00Z"));
        store.write("dataset", "right_ds", Map.of("view", "right_view"));
    }

    /** LA-15: a real implicit join is measured, not guessed — |A ∩ B| = 2 over |A ∪ B| = 4. */
    @Test
    void overlapProfileMeasuresARealValueOverlap(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOverlap(c);
            HttpResponse<String> r = overlapProfile(c.port, """
                    {"datasets":["left_ds","right_ds"],"columns":["acct"]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = json(r.body());
            assertEquals(2, data.get("datasetsScanned").asInt());
            assertEquals(2, data.get("columns").size(), "only the requested column is profiled: " + data);
            assertEquals(3, data.get("columns").get(0).get("rows").asInt());
            assertEquals(3, data.get("columns").get(0).get("distinct").asInt());
            assertEquals(0, data.get("columns").get(0).get("nulls").asInt());
            JsonNode pairs = data.get("pairs");
            assertEquals(1, pairs.size(), "one cross-dataset pair: " + pairs);
            JsonNode p = pairs.get(0);
            assertEquals("acct", p.get("fromColumn").asText());
            assertEquals("acct", p.get("toColumn").asText());
            assertEquals(2, p.get("intersection").asInt(), "a2 and a3 are shared: " + p);
            assertEquals(0.5, p.get("jaccard").asDouble(), 0.01, "2 shared over 4 distinct: " + p);
            assertFalse(data.get("truncated").asBoolean());
        }
    }

    /**
     * LA-15's whole point: {@code code} matches by NAME on both Datasets — exactly what
     * {@code /inv/schema/relationships} reasons from — but the values are disjoint, so the empirical
     * profile scores it 0 and the analyst can discount it.
     */
    @Test
    void overlapProfileScoresANameMatchWithNoSharedValuesAtZero(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOverlap(c);
            HttpResponse<String> r = overlapProfile(c.port, """
                    {"datasets":["left_ds","right_ds"],"columns":["code"]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode p = json(r.body()).get("pairs").get(0);
            assertEquals("code", p.get("fromColumn").asText());
            assertEquals("code", p.get("toColumn").asText());
            assertEquals(1, p.get("distinctFrom").asInt());
            assertEquals(1, p.get("distinctTo").asInt());
            assertEquals(0, p.get("intersection").asInt(), "'z' and 'p' never meet: " + p);
            assertEquals(0.0, p.get("jaccard").asDouble(), 0.0001, "a pure name collision scores 0: " + p);
        }
    }

    /** Strongest overlap first, so the name collision sorts below the real join in one unfiltered sweep. */
    @Test
    void overlapProfileRanksTheRealJoinAboveTheNameCollision(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOverlap(c);
            JsonNode data = json(overlapProfile(c.port, "{}").body());
            JsonNode pairs = data.get("pairs");
            assertEquals(4, pairs.size(), "every cross-dataset pair, no same-dataset pair: " + pairs);
            assertEquals(4, data.get("pairsConsidered").asInt());
            assertEquals("acct", pairs.get(0).get("fromColumn").asText(), "ranked by jaccard: " + pairs);
            assertEquals("acct", pairs.get(0).get("toColumn").asText());
            assertEquals(0.0, pairs.get(3).get("jaccard").asDouble(), 0.0001);
        }
    }

    /** Fail closed on identifiers: neither an unknown column nor an unsafe one reaches SQL. */
    @Test
    void overlapProfileRefusesAnUnknownOrUnsafeColumn(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOverlap(c);
            HttpResponse<String> unknown = overlapProfile(c.port, """
                    {"columns":["nope"]}""");
            assertEquals(422, unknown.statusCode(), unknown.body());
            assertTrue(unknown.body().contains("nope"), unknown.body());

            HttpResponse<String> unsafe = overlapProfile(c.port, """
                    {"columns":["acct\\"; DROP TABLE t; --"]}""");
            assertEquals(422, unsafe.statusCode(), unsafe.body());
        }
    }

    /** A Dataset that cannot be probed is skipped and counted, never a 500 — as schemaRelationships does. */
    @Test
    void overlapProfileSkipsUnusableDatasetsWithoutFailing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOverlap(c);
            new ComponentStore(c.root.resolve("registry")).write("dataset", "ghost_ds", Map.of());   // unbound
            HttpResponse<String> r = overlapProfile(c.port, "{}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = json(r.body());
            assertEquals(2, data.get("datasetsScanned").asInt());
            assertEquals(1, data.get("datasetsSkipped").asInt());
        }
    }

    /** LA-15 audit: profiling reads VALUES, so it is its own act — not the schema read's event type. */
    @Test
    void overlapProfileEmitsItsOwnAuditEvent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOverlap(c);
            List<Event> events = captureEvents(() ->
                    assertEquals(200, overlapProfile(c.port, "{}").statusCode()));
            Event e = ofType(events, EventType.LINK_OVERLAP_PROFILED);
            assertEquals("link.overlap.profiled", e.attributes().get("action"));
            assertEquals("4", e.attributes().get("columnsProfiled"));
            assertEquals("4", e.attributes().get("pairsProfiled"));
            assertEquals("false", e.attributes().get("truncated"));
            assertTrue(events.stream().noneMatch(x -> EventType.LINK_SCHEMA_INSPECTED.equals(x.type())),
                    "a value profile is not a schema read: " + events);
        }
    }

    @Test
    void savedLinkAnalysisViewsPersistViaComponents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String view = """
                    {"id":"fraud-ring","name":"Fraud ring","sourceId":"entity-projection",
                     "query":{"projection":{"datasetId":"calls_ds","sourceCol":"caller","targetCol":"callee"}}}""";
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + c.port + "/api/v1/components/link-analysis-view"))
                    .method("POST", BodyPublishers.ofString(view)).build(), BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());

            ComponentStore store = new ComponentStore(c.root.resolve("registry"));
            assertTrue(store.get("link-analysis-view", "fraud-ring").isPresent(),
                    "saved view lands in the real component store (INV-1 mock-store retirement)");
        }
    }

    /** Collect every event emitted while {@code body} runs (LA-04 audit assertions). */
    private static List<Event> captureEvents(ThrowingRunnable body) throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try {
            body.run();
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
        return seen;
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static Event ofType(List<Event> events, String type) {
        return events.stream().filter(e -> type.equals(e.type())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " event in " + events));
    }

    /**
     * LA-04: a projection is audited, and the audit carries the partial-result flag — an analyst looking
     * at a truncated graph must be visible as such in the trail.
     */
    @Test
    void projectionEmitsAnAuditedAnalyticEvent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            List<Event> events = captureEvents(() -> assertEquals(200, project(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","limit":1}""").statusCode()));
            Event e = ofType(events, EventType.LINK_PROJECTED);
            assertEquals("link.projected", e.attributes().get("action"));
            assertEquals("calls_ds", e.attributes().get("dataset"));
            assertEquals("1", e.attributes().get("rows"));
            assertEquals("true", e.attributes().get("truncated"), "the partial result is carried: " + e.attributes());
        }
    }

    /** LA-04: an expansion is a different analytic act from the projection it grew from, so it gets its own type. */
    @Test
    void neighborsEmitsAnExpansionEventDistinctFromProjection(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            List<Event> events = captureEvents(() -> assertEquals(200, neighbors(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","value":"bob"}""").statusCode()));
            Event e = ofType(events, EventType.LINK_EXPANDED);
            assertEquals("link.expanded", e.attributes().get("action"));
            assertEquals("calls_ds", e.attributes().get("dataset"));
            assertEquals("bob", e.attributes().get("value"));
            assertEquals("false", e.attributes().get("truncated"));
            assertTrue(events.stream().noneMatch(x -> EventType.LINK_PROJECTED.equals(x.type())),
                    "an expansion is not logged as a fresh projection: " + events);
        }
    }

    /** LA-04: the cross-Dataset schema sweep is audited too, with the reach it actually had. */
    @Test
    void schemaRelationshipsEmitsAnInspectionEvent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrdersAndCustomers(c);
            List<Event> events = captureEvents(
                    () -> assertEquals(200, schemaRelationships(c.port).statusCode()));
            Event e = ofType(events, EventType.LINK_SCHEMA_INSPECTED);
            assertEquals("link.schema.inspected", e.attributes().get("action"));
            assertEquals("2", e.attributes().get("datasetsScanned"));
        }
    }

    // ── LA-01: the pushed-down `filter` (contract §5.1) ─────────────────────────────

    /**
     * Five transactions over the contract's shape: two channels for one pair so a filter can change the
     * fold's {@code count}, and {@code alice} present as payer AND payee so the either-endpoint OR group
     * has something to prove. Unfiltered this projects 3 rows: alice→bob (3), erin→alice (1), carol→dave (1).
     */
    private void seedTxns(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("txns_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES "
                        + "('alice','bob','sms','2026-01-05'),"
                        + "('alice','bob','sms','2026-02-05'),"
                        + "('alice','bob','wire','2026-03-05'),"
                        + "('erin','alice','wire','2026-06-05'),"
                        + "('carol','dave','crypto','2026-05-05')"
                        + ") AS t(payer,payee,channel,booked_at)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "txns_ds", Map.of("view", "txns_view"));
    }

    /**
     * The filter narrows the projection AND the surviving edge's {@code count} drops from 3 to 2 — proof
     * the predicate lands ahead of the GROUP BY, not after it. A post-fold filter would have kept count 3.
     */
    @Test
    void filterNarrowsRowsBeforeTheFold(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedTxns(c);
            assertEquals(3, json(project(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee"}""").body())
                    .at("/rows").size(), "baseline, unfiltered");

            HttpResponse<String> r = project(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee",
                     "filter":{"kind":"group","op":"AND","items":[
                       {"kind":"condition","field":"channel","operator":"in","value":"sms,crypto"}]}}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = json(r.body()).at("/rows");
            assertEquals(2, rows.size(), "the wire-only erin->alice edge is gone: " + rows);
            assertEquals("alice", rows.get(0).get("source").asText());
            assertEquals("bob", rows.get(0).get("target").asText());
            assertEquals(2, rows.get(0).get("count").asInt(), "count folds over the FILTERED rows: " + rows);
            assertEquals("carol", rows.get(1).get("source").asText());
        }
    }

    /** G-R1's time window: a `between` on a date column, the contract's own example operator. */
    @Test
    void aTimeWindowFilterChangesTheFoldedCount(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedTxns(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee",
                     "filter":{"kind":"group","op":"AND","items":[
                       {"kind":"condition","field":"booked_at","operator":"between",
                        "value":"2026-01-01","value2":"2026-02-28"}]}}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = json(r.body()).at("/rows");
            assertEquals(1, rows.size(), "only the two January/February transactions survive: " + rows);
            assertEquals(2, rows.get(0).get("count").asInt());
        }
    }

    /** The contract's canonical "node present as either endpoint": a nested OR group inside the AND. */
    @Test
    void aNestedOrGroupSelectsANodeAtEitherEndpoint(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedTxns(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee",
                     "filter":{"kind":"group","op":"AND","items":[
                       {"kind":"group","op":"OR","items":[
                         {"kind":"condition","field":"payer","operator":"=","value":"alice"},
                         {"kind":"condition","field":"payee","operator":"=","value":"alice"}]}]}}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = json(r.body()).at("/rows");
            assertEquals(2, rows.size(), "alice as payer and as payee, carol->dave excluded: " + rows);
            assertEquals(3, rows.get(0).get("count").asInt());
            assertEquals("erin", rows.get(1).get("source").asText());
            assertEquals("alice", rows.get(1).get("target").asText());
        }
    }

    /**
     * The security property of LA-01: a leaf naming a column the relation has not got is refused before
     * anything is rendered, and the refusal names the offending field. Nothing from the tree reaches SQL.
     */
    @Test
    void anUnknownFilterFieldIs422NamingTheField(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedTxns(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee",
                     "filter":{"kind":"group","op":"AND","items":[
                       {"kind":"condition","field":"ghost_col","operator":"=","value":"x"}]}}""");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("ghost_col"), "the refusal names the field: " + r.body());
            assertEquals("CONFIG_VALIDATION_FAILED",
                    JSON.readTree(r.body()).at("/error/errorCode").asText(), r.body());

            HttpResponse<String> nested = project(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee",
                     "filter":{"kind":"group","op":"AND","items":[
                       {"kind":"condition","field":"channel","operator":"=","value":"sms"},
                       {"kind":"group","op":"OR","items":[
                         {"kind":"condition","field":"payer","operator":"=","value":"alice"},
                         {"kind":"condition","field":"1=1) OR (1","operator":"=","value":"x"}]}]}}""");
            assertEquals(422, nested.statusCode(), "a nested leaf is validated too: " + nested.body());
            assertTrue(nested.body().contains("1=1"), nested.body());
        }
    }

    /** An empty group constrains nothing — a no-op, not an error (parity with ConditionSql/ConditionTree). */
    @Test
    void anEmptyFilterGroupIsANoOp(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedTxns(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee",
                     "filter":{"kind":"group","op":"AND","items":[]}}""");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(3, json(r.body()).at("/rows").size(), "same as the unfiltered projection");
        }
    }

    /**
     * A bare condition at the top level (no group around it) used to render as TRUE: the call returned 200
     * with EVERY row while the analyst believed it filtered. It is now a 422 on /projection and /neighbors;
     * the twin — the same leaf wrapped in a group — filters.
     */
    @Test
    void aBareConditionAtTheTopLevelIs422AndItsGroupedTwinFilters(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedTxns(c);
            for (String bare : List.of(
                    "{\"kind\":\"condition\",\"field\":\"channel\",\"operator\":\"=\",\"value\":\"sms\"}",
                    "{\"field\":\"channel\",\"operator\":\"=\",\"value\":\"sms\"}",
                    "{\"field\":\"channel\",\"op\":\"=\",\"value\":\"sms\"}")) {
                HttpResponse<String> r = project(c.port,
                        "{\"dataset\":\"txns_ds\",\"sourceCol\":\"payer\",\"targetCol\":\"payee\",\"filter\":" + bare + "}");
                assertEquals(422, r.statusCode(), "bare " + bare + " -> " + r.body());
                assertTrue(r.body().contains("group"), r.body());
                HttpResponse<String> n = neighbors(c.port, "{\"dataset\":\"txns_ds\",\"sourceCol\":\"payer\","
                        + "\"targetCol\":\"payee\",\"value\":\"alice\",\"filter\":" + bare + "}");
                assertEquals(422, n.statusCode(), "neighbors, bare " + bare + " -> " + n.body());
            }

            HttpResponse<String> twin = project(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee",
                     "filter":{"kind":"group","op":"AND","items":[
                       {"kind":"condition","field":"channel","operator":"=","value":"sms"}]}}""");
            assertEquals(200, twin.statusCode(), twin.body());
            JsonNode rows = json(twin.body()).at("/rows");
            assertEquals(1, rows.size(), "only the sms pair survives: " + rows);
            assertEquals(2, rows.get(0).get("count").asInt());
        }
    }

    /** /neighbors delegates to the projection, so it inherits the filter with no separate code path. */
    @Test
    void theFilterSurvivesThroughNeighbors(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedTxns(c);
            assertEquals(2, json(neighbors(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee","value":"alice"}""").body())
                    .at("/rows").size(), "baseline: both alice edges");

            HttpResponse<String> r = neighbors(c.port, """
                    {"dataset":"txns_ds","sourceCol":"payer","targetCol":"payee","value":"alice",
                     "filter":{"kind":"group","op":"AND","items":[
                       {"kind":"condition","field":"channel","operator":"=","value":"sms"}]}}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = json(r.body()).at("/rows");
            assertEquals(1, rows.size(), "the wire edge erin->alice is filtered out: " + rows);
            assertEquals("bob", rows.get(0).get("target").asText());
            assertEquals(2, rows.get(0).get("count").asInt(), "the neighbor bind and the filter compose");
        }
    }

    /** Parse a v1 response and peel the envelope's {@code data} (mirrors the control module's V1Body, which
     *  lives in inspecto's TEST tree and is not visible from another module — the inspecto-policy precedent). */
    private static com.fasterxml.jackson.databind.JsonNode json(String raw) throws Exception {
        com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        return n.has("data") ? n.get("data") : n;
    }
}
