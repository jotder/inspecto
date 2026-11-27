package com.gamma.control;

import com.gamma.alert.AlertRule;
import com.gamma.config.spec.FindingCodes;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * ALERT-RULE-ROW-FILTER-1: the save gate of a Dataset measure rule's row filter ({@link AlertRoutes#requireRowFilter}),
 * run inside {@link AlertRoutes#parseContent} - so every door that writes an Alert Rule. Each 422 carries
 * {@code ERR_ALERT_ROW_FILTER_INVALID} and is paired with the accepted shape one key away: a field outside the Dataset's
 * Schema (another Dataset's column included) and a masked field are refused; a clean field is accepted.
 */
class AlertRowFilterSaveGateTest {

    @BeforeEach
    void standardBuild() {
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
    }

    @AfterEach
    void restoreEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    private record Space(Path root, Path data) {}

    /** {@code shipments} (msisdn classified MSISDN, masked under {@code masking_mode: typed}) and {@code ledger} (owner). */
    private static Space space(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("config")), data = Files.createDirectories(dir.resolve("data"));
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: typed\n");
        Path ship = Files.createDirectories(data.resolve("shipments")), led = Files.createDirectories(data.resolve("ledger"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT 's1' AS shipment_id, '491700000001' AS msisdn, 'in_transit' AS status) TO '"
                    + ship.resolve("s.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            st.execute("COPY (SELECT 1 AS id, 'bob' AS owner) TO '"
                    + led.resolve("l.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        ComponentStore reg = new ComponentStore(root.resolve("registry"));
        reg.write("dataset", "shipments", new HashMap<>(Map.of("physicalRef", "shipments", "columns", List.of(
                Map.of("name", "shipment_id"), Map.of("name", "msisdn", "classification", "MSISDN"),
                Map.of("name", "status")))));
        reg.write("dataset", "ledger", new HashMap<>(Map.of("physicalRef", "ledger")));
        return new Space(root, data);
    }

    private static Map<String, Object> rule(String field, String value) {
        Map<String, Object> m = new HashMap<>(Map.of("name", "undelivered-shipments", "dataset", "shipments",
                "measure", "count", "comparator", "gt", "threshold", 5, "severity", "WARNING"));
        m.put("when", Map.of("kind", "group", "op", "AND", "items", List.of(
                Map.of("kind", "condition", "field", field, "operator", "!=", "value", value))));
        return m;
    }

    private static AlertRule save(Space s, Map<String, Object> body) {
        return AlertRoutes.parseContent(s.root(), s::data, body);
    }

    private static void refused(Space s, Map<String, Object> body, String fragment) {
        ApiException e = assertThrows(ApiException.class, () -> save(s, body));
        assertEquals(422, e.status, e.getMessage());
        assertTrue(e.getMessage().startsWith(FindingCodes.ERR_ALERT_ROW_FILTER_INVALID + ": "), e.getMessage());
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void aFilterOnAColumnOfTheDatasetIsAccepted(@TempDir Path dir) throws Exception {
        AlertRule r = save(space(dir), rule("status", "delivered"));
        assertEquals(List.of("status"), r.rowFilterColumns());
        assertNotNull(r.toMap().get("when"), "the filter is persisted");
    }

    @Test
    void aFieldOutsideTheSchemaIsRefusedAnotherDatasetsColumnIncluded(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        refused(s, rule("statuss", "delivered"), "reads column(s) [statuss] that are not in the Schema of dataset 'shipments'");
        refused(s, rule("owner", "bob"), "[owner]");             // a column of `ledger`, not of `shipments`
        refused(s, rule("ledger.owner", "bob"), "[ledger.owner]");
    }

    @Test
    void aFilterOnAMaskedColumnIsRefusedForEveryAuthorAnAlertNeverReveals(@TempDir Path dir) throws Exception {
        refused(space(dir), rule("msisdn", "491700000001"), "msisdn");
    }

    @Test
    void anIncompleteFilterIsA422WithTheCode(@TempDir Path dir) throws Exception {
        Map<String, Object> body = rule("status", "delivered");
        body.put("when", Map.of("kind", "group", "op", "AND", "items", List.of(
                Map.of("kind", "condition", "field", "status", "operator", "!="))));
        refused(space(dir), body, "incomplete condition");
    }

    @Test
    void anUnknownDatasetIsRefusedFailClosed(@TempDir Path dir) throws Exception {
        Map<String, Object> body = rule("status", "delivered");
        body.put("dataset", "nope");
        refused(space(dir), body, "cannot be checked against the Schema of dataset 'nope'");
    }
}
