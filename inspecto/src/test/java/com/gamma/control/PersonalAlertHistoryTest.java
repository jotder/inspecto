package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.service.CollectorService;
import com.gamma.service.ServiceBootstrap;
import com.gamma.service.SpaceRoot;
import com.gamma.alert.AlertRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P7-INCIDENTS slice 2 (operator decision 2026-10-07): <b>Personal keeps Alert history and
 * restart-safe de-duplication</b>. This module's test classpath carries no {@code inspecto-ops}, so a service built
 * here IS the Personal shape — no operational objects, no Incidents, no Cases — and its Alerts must still survive a
 * restart: the second service over the same Space directory shows the first one's Alert on {@code GET /alerts} and
 * does NOT re-fire it. The reactor pins {@code -Dalerts.backend=memory}; this test selects the durable default
 * explicitly (it is what every shipped edition runs).
 */
class PersonalAlertHistoryTest {

    private static final AlertRule RULE = new AlertRule("low-revenue", null, "lt", 1000, null, "WARNING", null,
            "sales_ds", "sum(amount)");

    private String savedBackend;

    @BeforeEach
    void durableDefault() {
        savedBackend = System.getProperty("alerts.backend");
        System.clearProperty("alerts.backend");   // the shipped default: db
    }

    @AfterEach
    void restore() {
        if (savedBackend == null) System.clearProperty("alerts.backend");
        else System.setProperty("alerts.backend", savedBackend);
    }

    private CollectorService boot(SpaceRoot root) throws Exception {
        CollectorService svc = ServiceBootstrap.buildFrom(root, new String[0], false);
        assertTrue(svc.objects().isEmpty(), "the Personal shape: no operational objects on this classpath");
        svc.alertService().orElseThrow().measureProbe((d, m) -> OptionalDouble.of(750));
        return svc;
    }

    private String getAlerts(CollectorService svc) throws Exception {
        try (ControlApi api = new ControlApi(svc, 0)) {
            api.start();
            HttpResponse<String> res = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1/alerts")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode(), res.body());
            return res.body();
        }
    }

    @Test
    void anAlertFiredOnPersonalSurvivesARestartAndIsNotReFired(@TempDir Path dir) throws Exception {
        SpaceRoot root = SpaceRoot.under(dir);
        String first;
        try (CollectorService svc = boot(root)) {
            svc.alertService().orElseThrow().upsert(RULE);
            assertEquals(1, svc.alertService().orElseThrow().evaluateAll().size(), "the breach fires");
            first = getAlerts(svc);
        }
        assertTrue(Files.isRegularFile(dir.resolve("duckdb").resolve("inspecto-alerts.db")),
                "the Alert store is the Space's own DuckDB file, on Personal too");

        // "restart": a brand-new service over the same Space directory.
        try (CollectorService svc = boot(root)) {
            svc.alertService().orElseThrow().upsert(RULE);
            JsonNode history = V1Body.of(getAlerts(svc));
            assertEquals(1, history.size(), "GET /alerts shows the history from the store: " + history);
            assertEquals("low-revenue", history.get(0).get("rule").asText());
            assertEquals(V1Body.of(first).get(0), history.get(0), "byte-compatible with what the first process served");
            assertEquals(0, svc.alertService().orElseThrow().evaluateAll().size(),
                    "the open Alert is remembered: a restart does not announce it again");
            assertEquals(1, V1Body.of(getAlerts(svc)).size(), "and the history did not grow");
        }
    }

    @Test
    void aSpaceWithoutAHistoryOfItsOwnStartsEmpty(@TempDir Path dir) throws Exception {
        // The negative twin: the history above came from THIS Space's store, not from process-wide state.
        try (CollectorService svc = boot(SpaceRoot.under(dir.resolve("other")))) {
            assertEquals(0, V1Body.of(getAlerts(svc)).size());
        }
    }
}
