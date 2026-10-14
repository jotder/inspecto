package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.core.CollectorCoveragePort;
import com.gamma.la.core.CollectorCoveragePorts;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.signal.DeliveryAnomalySignal;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DATA-TRUST-1 S5 — {@code GET /inv/investigations/{id}/data-changes} over real HTTP with an ARMED Authenticator (with no
 * Subject the gates are no-ops and nothing would be proven): gated exactly as {@code …/coverage} (a stranger answers 404), the
 * window edge, the "newer than the last look" edge, the Pipeline attribution, and {@code assessed:false} without lineage.
 */
class ControlApiInvestigationDataChangesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\",\"timeCol\":\"ts\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String LEAD = "Bearer lead", STRANGER = "Bearer stranger";
    private static final Set<String> ALL = Set.of("canManageIncidents", "canRunLinkGraphAnalysis", "canApproveLinkExpansions",
            "canRevealLinkEntities", "canAuthorAlertRules");
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        CollectorCoveragePorts.forTest(null);
    }

    private HttpResponse<String> send(ControlApi api, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(ControlApi api, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(api, method, path, body, auth);
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static Signal anomaly(String pipeline, String day, String kind, Instant at) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pipeline", pipeline);
        p.put("batchId", "b-" + day);
        p.put("kind", kind);
        p.put("day", day);
        p.put("file", "calls_" + day + "_v2.csv");
        p.put("previousFile", "calls_" + day + ".csv");
        p.put("detail", "d");
        return new Signal(null, DeliveryAnomalySignal.TYPE, at, Severity.WARN, Ref.of("pipeline", pipeline),
                Ref.of("pipeline", pipeline), "b-" + day, null, null, null, "anomaly", p, 1);
    }

    @Test
    void aCorrectedDayInsideTheWindowIsReportedUntilTheAnalystLooksAgain(@TempDir Path tmp) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case LEAD -> Optional.of(new Subject("lead-1", ALL));
            case STRANGER -> Optional.of(new Subject("stranger-4", ALL));
            default -> Optional.empty();
        });
        Path config = java.nio.file.Files.createDirectories(tmp.resolve("config")), root = java.nio.file.Files.createDirectories(tmp.resolve("root"));
        Path pipe = PipelineConfigBatchTest.writePipeline(config, "");
        System.setProperty("assist.write.root", root.toString());
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1)) {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                new ViewStore(root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                        "SELECT caller, callee, channel, ts FROM (VALUES ('a','b','voice',TIMESTAMPTZ '2026-09-04 10:00:00+00'),('a','c','sms',TIMESTAMPTZ '2026-09-05 10:00:00+00')) AS t(caller,callee,channel,ts)",
                        "2026-09-30T00:00:00Z"));
                new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
                String pipeline = svc.pipelines().get(0).name();
                String collector = svc.configFor(pipeline).orElseThrow().collector().id();

                ok(api, "POST", "/inv/investigations", CREATE, LEAD);
                ok(api, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", LEAD);
                ok(api, "POST", INV + "/ops", "{\"op\":\"window\",\"window\":{\"from\":\"2026-09-03T00:00:00Z\","
                        + "\"to\":\"2026-09-06T00:00:00Z\",\"timezone\":\"UTC\"}}", LEAD);

                // No lineage attributes the Dataset yet: not assessed, never "no changes".
                JsonNode none = ok(api, "GET", INV + "/data-changes", null, LEAD);
                assertFalse(none.get("assessed").asBoolean());
                assertEquals(0, none.get("changes").size());

                CollectorCoveragePorts.forTest((a, ds) -> Optional.of(List.of(new CollectorCoveragePort.Delivery(collector, "2026-09-04", 2))));
                Thread.sleep(25);
                Instant after = Instant.now();
                svc.eventLog().emit(anomaly(pipeline, "2026-09-04", "RENAMED_CORRECTION", after).toEvent());      // inside, newer
                svc.eventLog().emit(anomaly(pipeline, "2026-09-05", "REDELIVERED", after.plusMillis(5)).toEvent());   // last window day
                svc.eventLog().emit(anomaly(pipeline, "2026-09-06", "REDELIVERED", after).toEvent());                  // the day 'to' falls on
                svc.eventLog().emit(anomaly(pipeline, "2026-09-02", "REDELIVERED", after).toEvent());                  // before the window
                svc.eventLog().emit(anomaly("other_pipeline", "2026-09-04", "REDELIVERED", after).toEvent());          // not this Dataset's
                svc.eventLog().emit(anomaly(pipeline, "2026-09-04", "REDELIVERED", Instant.now().minusSeconds(3600)).toEvent()); // older than the look

                JsonNode hit = ok(api, "GET", INV + "/data-changes", null, LEAD);
                assertTrue(hit.get("assessed").asBoolean());
                assertEquals(List.of("2026-09-04", "2026-09-05"), List.of(hit.at("/changedDays/0").asText(), hit.at("/changedDays/1").asText()));
                assertEquals(2, hit.get("changes").size());
                assertEquals("RENAMED_CORRECTION", hit.at("/changes/0/kind").asText());
                assertEquals("calls_2026-09-04_v2.csv", hit.at("/changes/0/file").asText());
                assertEquals("calls_2026-09-04.csv", hit.at("/changes/0/previousFile").asText());
                assertEquals(pipeline, hit.at("/changes/0/pipeline").asText());
                assertFalse(hit.get("capped").asBoolean());
                assertTrue(hit.has("lastLookAt") && hit.has("readAt") && hit.has("window"));

                // Same gate as coverage: a non-owner answers 404.
                assertEquals(404, send(api, "GET", INV + "/data-changes", null, STRANGER).statusCode());

                // A step that reads nothing (seed) and an undo of it do not count as looking again: the warning stays.
                Thread.sleep(25);
                ok(api, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"b\"]}", LEAD);
                assertEquals(2, ok(api, "GET", INV + "/data-changes", null, LEAD).get("changes").size(), "a seed reads no data");
                Thread.sleep(25);
                ok(api, "POST", INV + "/undo", "{}", LEAD);
                assertEquals(2, ok(api, "GET", INV + "/data-changes", null, LEAD).get("changes").size(), "an undo reads no data");

                // An expand re-reads the Dataset: now it is no longer "after you last looked".
                Thread.sleep(25);
                ok(api, "POST", INV + "/ops", "{\"op\":\"expand\",\"ids\":[\"a\"]}", LEAD);
                assertEquals(0, ok(api, "GET", INV + "/data-changes", null, LEAD).get("changes").size());
            } finally {
                api.close();
            }
        } finally {
            System.clearProperty("assist.write.root");
        }
    }
}
