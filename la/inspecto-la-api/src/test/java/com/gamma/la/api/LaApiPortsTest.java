package com.gamma.la.api;

import com.gamma.control.ApiException;
import com.gamma.control.ApiExceptionPeek;
import com.gamma.control.ErrorCodes;
import com.gamma.la.core.CasePort;
import com.gamma.la.core.CasePorts;
import com.gamma.la.core.DatasetProviders;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA separation D-1 step 5b/6 — Link Analysis routes run on the PORTS ALONE. This module's test classpath has no engine, no
 * ETL and no control plane (asserted below), and no bridge: the only Dataset provider is the fake, the only Case port the
 * test's own. With no provider bound, a route that needs a Dataset answers a clean 503 (an {@link ApiException}, which the
 * control plane serialises as the error envelope) - never a stack trace.
 */
class LaApiPortsTest {

    @TempDir Path root;
    private PortHarness h;

    @BeforeEach
    void register() {
        h = new PortHarness(root);
        new InvRoutes().register(h.api);
        new InvestigationCaseRoutes().register(h.api);
    }

    @AfterEach
    void restorePorts() {
        DatasetProviders.forTest(null);
        DatasetProviders.forTestAbsent(false);
        CasePorts.forTest(null);
        CasePorts.forTestAbsent(false);
    }

    @Test
    void theEngineIsNotOnThisClasspath() {
        for (String engine : List.of("com.gamma.query.QueryExecutor", "com.gamma.query.DatasetRead",
                "com.gamma.pipeline.ComponentRegistry", "com.gamma.alert.AlertService", "com.gamma.service.CollectorService"))
            assertThrows(ClassNotFoundException.class, () -> Class.forName(engine), engine + " must not be reachable from la-api");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaRelationshipsRunAgainstTheDatasetPortAlone() throws Exception {
        PortHarness.FakeDatasets fake = new PortHarness.FakeDatasets(new java.util.LinkedHashMap<>(Map.of(
                "customers", List.of("id", "name"),
                "orders", List.of("id", "customer_id"))));
        DatasetProviders.forTest(fake);

        Map<String, Object> out = (Map<String, Object>) h.call("GET", "/inv/schema/relationships", null);

        assertEquals(2, out.get("datasetsScanned"));
        List<Map<String, Object>> rels = (List<Map<String, Object>>) out.get("relationships");
        assertEquals(1, rels.size(), rels.toString());
        assertEquals("orders", rels.get(0).get("fromDataset"));
        assertEquals("customer_id", rels.get(0).get("fromColumn"));
        assertEquals("customers", rels.get(0).get("toDataset"));
        assertEquals("id", rels.get(0).get("toColumn"));
        assertEquals("high", rels.get(0).get("confidence"));
        assertEquals(2, fake.ran.size(), "each Dataset was probed through the port: " + fake.ran);
    }

    @Test
    void anUnboundDatasetPortAnswersACleanServiceUnavailable() {
        DatasetProviders.forTestAbsent(true);
        assertFalse(DatasetProviders.active().isPresent());

        ApiException schema = assertThrows(ApiException.class, () -> h.call("GET", "/inv/schema/relationships", null));
        assertEquals(503, ApiExceptionPeek.status(schema));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, ApiExceptionPeek.code(schema));
        assertTrue(schema.getMessage().contains("no Dataset provider"), schema.getMessage());

        ApiException projection = assertThrows(ApiException.class, () -> h.call("POST", "/inv/projection",
                Map.of("dataset", "calls", "sourceCol", "caller", "targetCol", "callee")));
        assertEquals(503, ApiExceptionPeek.status(projection));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, ApiExceptionPeek.code(projection));
    }

    // ── the Case port ──────────────────────────────────────────────────────────────────────────────────

    private static CasePort cases(Map<String, Map<String, Object>> byRef, boolean visible) {
        return new CasePort() {
            @Override public boolean available(com.gamma.control.ApiContext api) { return true; }
            @Override public Optional<Map<String, Object>> summary(com.gamma.control.ApiContext api, String ref) {
                return Optional.ofNullable(byRef.get(ref));
            }
            @Override public boolean visibleTo(HttpExchange ex, Map<String, Object> summary) { return visible; }
        };
    }

    @Test
    void withNoCasePortALinkIsStoredUnverifiedAndGrantsNothing() {
        CasePorts.forTestAbsent(true);
        Map<String, Object> link = InvestigationCaseRoutes.linkRecord(h.api, new PortHarness.StubExchange("PUT", "/x", null), "CASE-1");
        assertNotNull(link);
        assertEquals("CASE-1", link.get("caseRef"));
        assertEquals(false, link.get("verified"), "ops not installed: the link cannot be checked, so it is stored unverified");
    }

    @Test
    void aBoundCasePortVerifiesTheCaseTheCallerCanSee() {
        CasePorts.forTest(cases(Map.of(
                "OPEN-1", Map.of("kind", "case", "closed", false),
                "SHUT-1", Map.of("kind", "case", "closed", true)), true));
        HttpExchange ex = new PortHarness.StubExchange("PUT", "/x", null);

        assertEquals(true, InvestigationCaseRoutes.linkRecord(h.api, ex, "OPEN-1").get("verified"));
        assertEquals(409, ApiExceptionPeek.status(assertThrows(ApiException.class, () -> InvestigationCaseRoutes.linkRecord(h.api, ex, "SHUT-1"))));
        assertEquals(404, ApiExceptionPeek.status(assertThrows(ApiException.class, () -> InvestigationCaseRoutes.linkRecord(h.api, ex, "NONE-1"))));
    }

    @Test
    void aCaseTheCallerCannotSeeReadsAsAbsent() {
        CasePorts.forTest(cases(Map.of("OPEN-1", Map.of("kind", "case", "closed", false)), false));
        ApiException e = assertThrows(ApiException.class,
                () -> InvestigationCaseRoutes.linkRecord(h.api, new PortHarness.StubExchange("PUT", "/x", null), "OPEN-1"));
        assertEquals(404, ApiExceptionPeek.status(e));
    }
}
