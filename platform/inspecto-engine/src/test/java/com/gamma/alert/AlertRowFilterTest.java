package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.config.spec.FindingCodes;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.query.DatasetMeasureProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ALERT-RULE-ROW-FILTER-1 — a Dataset measure Alert Rule's {@code when} row filter, over REAL DuckDB data through the
 * production {@link DatasetMeasureProbe}: "more than 5 shipments are undelivered" is one rule; the same rule without the
 * filter counts every row; an unwired filtered probe leaves the rule inert (never the unfiltered count); a field that
 * tries to leave the Dataset's relation computes nothing; and an incomplete tree is refused with its finding code.
 */
class AlertRowFilterTest {

    private static ConfigSource noPipelines() {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    /**
     * {@code shipments}: 9 rows - 6 undelivered (4 EU, 2 US) and 3 delivered. {@code ledger}: a second Dataset of 50 rows,
     * which no filter on {@code shipments} may reach.
     */
    private static Path plant(Path root) throws Exception {
        Path reg = Files.createDirectories(root.resolve("config").resolve("registry").resolve("datasets"));
        Files.writeString(reg.resolve("shipments.toon"), "name: shipments\nphysicalRef: shipments\n");
        Files.writeString(reg.resolve("ledger.toon"), "name: ledger\nphysicalRef: ledger\n");
        Path ship = Files.createDirectories(root.resolve("data").resolve("shipments"));
        Path led = Files.createDirectories(root.resolve("data").resolve("ledger"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('s1','EU','in_transit'), ('s2','EU','in_transit'), ('s3','EU','lost'),"
                    + " ('s4','EU','in_transit'), ('s5','US','in_transit'), ('s6','US','held'), ('s7','EU','delivered'),"
                    + " ('s8','US','delivered'), ('s9','US','delivered')) AS t(shipment_id, region, status)) TO '"
                    + ship.resolve("s.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            st.execute("COPY (SELECT range AS id, 'x' AS status FROM range(50)) TO '"
                    + led.resolve("l.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        return root;
    }

    private static Map<String, Object> notDelivered() {
        return group(Map.of("kind", "condition", "field", "status", "operator", "!=", "value", "delivered"));
    }

    @SafeVarargs
    private static Map<String, Object> group(Map<String, Object>... items) {
        return Map.of("kind", "group", "op", "AND", "items", List.of(items));
    }

    private static Map<String, Object> rule(Object when) {
        Map<String, Object> m = new HashMap<>(Map.of("name", "undelivered-shipments", "dataset", "shipments",
                "measure", "count", "comparator", "gt", "threshold", 5, "severity", "WARNING"));
        if (when != null) m.put("when", when);
        return m;
    }

    private static DatasetMeasureProbe probe(Path root) {
        return new DatasetMeasureProbe(() -> root.resolve("config"), () -> root.resolve("data"));
    }

    private static AlertService service(AlertRule rule, DatasetMeasureProbe probe, boolean wireFiltered) {
        AlertService svc = new AlertService(List.of(rule), noPipelines(), emptyStore());
        svc.measureProbe(probe::value);
        if (wireFiltered) svc.rowFilterProbe(r -> probe.value(r.dataset(), r.measure(), r.rowFilter()));
        return svc;
    }

    @Test
    void moreThanFiveUndeliveredShipmentsIsOneRuleAndCountsOnlyTheMatchingRows(@TempDir Path dir) throws Exception {
        Path root = plant(dir);
        AlertRule filtered = AlertRule.fromMap(rule(notDelivered()));
        assertNotNull(filtered.rowFilter());
        assertEquals(List.of("status"), filtered.rowFilterColumns());

        List<Alert> raised = service(filtered, probe(root), true).evaluateRules();
        assertEquals(1, raised.size(), "6 undelivered > 5");
        assertEquals(6, raised.get(0).value(), 1e-9, "the Measure counted the undelivered rows only");

        // the same rule over delivered rows only: 3, not above 5 - no Alert
        AlertRule delivered = AlertRule.fromMap(rule(group(
                Map.of("kind", "condition", "field", "status", "operator", "=", "value", "delivered"))));
        assertTrue(service(delivered, probe(root), true).evaluateRules().isEmpty(), "3 delivered is not above 5");
    }

    @Test
    void withoutAFilterTheRuleCountsEveryRowAsBefore(@TempDir Path dir) throws Exception {
        Path root = plant(dir);
        AlertRule plain = AlertRule.fromMap(rule(null));
        assertNull(plain.rowFilter());
        assertEquals(List.of(), plain.rowFilterColumns());
        List<Alert> raised = service(plain, probe(root), true).evaluateRules();
        assertEquals(1, raised.size());
        assertEquals(9, raised.get(0).value(), 1e-9, "no filter: all 9 rows");
        assertEquals(OptionalDouble.of(9), probe(root).value("shipments", "count", null));
    }

    @Test
    void aFilteredRuleIsInertWhenNoFilteredProbeIsWiredNeverTheUnfilteredCount(@TempDir Path dir) throws Exception {
        Path root = plant(dir);
        // filter keeps 3 rows (not above 5); the unfiltered count (9) WOULD fire - it must never be used instead
        AlertRule delivered = AlertRule.fromMap(rule(group(
                Map.of("kind", "condition", "field", "status", "operator", "=", "value", "delivered"))));
        assertTrue(service(delivered, probe(root), false).evaluateRules().isEmpty());
    }

    @Test
    void aGroupedRuleCountsOnlyTheMatchingRowsPerKey(@TempDir Path dir) throws Exception {
        Path root = plant(dir);
        // undelivered per region: EU 4, US 2 -> only EU breaches gt 3
        var breaches = probe(root).breaches("shipments", "count", List.of("region"), "gt", 3, 10, notDelivered())
                .orElseThrow();
        assertEquals(1, breaches.total());
        assertEquals("EU", breaches.keys().get(0).key().get("region"));
        assertEquals(4, breaches.keys().get(0).value(), 1e-9);
        // unfiltered: EU 5, US 4 -> both breach gt 3
        assertEquals(2, probe(root).breaches("shipments", "count", List.of("region"), "gt", 3, 10).orElseThrow().total());
    }

    @Test
    void aFieldThatTriesToLeaveTheDatasetComputesNothing(@TempDir Path dir) throws Exception {
        Path root = plant(dir);
        // a field is always a quoted identifier of the ONE Dataset's relation: another Dataset's name, a qualified
        // column or SQL text in a field name is an unknown column, so the value is unknown - never the other table's
        // count and never an unfiltered one
        for (String field : List.of("ledger.status", "ledger", "status\" IS NOT NULL OR \"1\"=\"1",
                "status = status) OR (SELECT count(*) FROM ledger) > 0 OR (status")) {
            Map<String, Object> when = group(Map.of("kind", "condition", "field", field, "operator", "!=", "value", "x"));
            assertTrue(probe(root).value("shipments", "count", when).isEmpty(), field);
        }
        // a value is a bound parameter, never text: SQL in it matches nothing
        Map<String, Object> injected = group(Map.of("kind", "condition", "field", "status", "operator", "=",
                "value", "delivered' OR '1'='1"));
        assertEquals(OptionalDouble.of(0), probe(root).value("shipments", "count", injected));
    }

    @Test
    void anIncompleteOrUnknownFilterIsRefusedWithItsFindingCode() {
        for (Map<String, Object> bad : List.of(
                group(Map.of("kind", "condition", "field", "status", "operator", "!=")),            // no value
                group(Map.of("kind", "condition", "field", "status", "operator", "~~", "value", "x")),  // unknown operator
                Map.<String, Object>of("kind", "group", "op", "AND", "items", List.of()),             // empty group
                Map.<String, Object>of("kind", "group", "op", "XOR", "items", List.of(
                        Map.of("kind", "condition", "field", "status", "operator", "=", "value", "x"))))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(rule(bad)));
            assertTrue(e.getMessage().startsWith(FindingCodes.ERR_ALERT_ROW_FILTER_INVALID + ": alert.when"), e.getMessage());
        }
        // a bare condition root was already refused for every rule
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(rule(
                Map.of("kind", "condition", "field", "status", "operator", "=", "value", "x"))));
    }

    @Test
    void theFilterRoundTripsAndStaysRefusedWhereNoRowsAreCounted() {
        AlertRule r = AlertRule.fromMap(rule(notDelivered()));
        assertEquals(notDelivered(), AlertRule.fromMap(r.toMap()).when(), "the filter survives a save/load round trip");
        // a freshness rule has no rows to filter
        Map<String, Object> fresh = new HashMap<>(Map.of("name", "f", "dataset", "shipments", "maximumAge", "6h",
                "when", notDelivered()));
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(fresh));
        // a KPI rule watches the KPI's own Measure
        Map<String, Object> kpi = new HashMap<>(Map.of("name", "k", "kpi", "revenue", "threshold", 1,
                "when", notDelivered()));
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(kpi));
    }
}
