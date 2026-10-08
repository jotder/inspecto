package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.etl.PipelineConfigBatchTest;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P7 ALERT residue retirement - a {@code SEQUENCE_GAP} / conservation-imbalance Event is an Alert on
 * EVERY edition, visible on {@code GET /alerts} and workable through {@code POST /alerts/{id}/ack}. This module's
 * test classpath carries no operational-object module, so this is the Personal shape: before the move the same
 * Events produced no ALERT at all here.
 */
class ControlApiAlertFromEventTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(int port, String method, String path) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, BodyPublishers.noBody()).build();
        return client.send(r, BodyHandlers.ofString());
    }

    private static Event gap(String pipeline, String expected) {
        return Event.builder(EventType.SEQUENCE_GAP).pipeline(pipeline)
                .message("Missing expected file in sequence: " + expected)
                .attr("expected", expected).attr("sequence", "cdr_{yyyyMMddHH}.csv").attr("unit", "HOURS").build();
    }

    @Test
    void aGapAndAnImbalanceAreAlertsOnGetAlertsAndTheGapIsAckable(@TempDir Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            assertTrue(svc.objects().isEmpty(), "the Personal shape: no operational objects");
            String pipeline = "gap_src_http";
            svc.eventLog().emit(gap(pipeline, "cdr_2026061402.csv"));
            svc.eventLog().emit(gap(pipeline, "cdr_2026061402.csv"));   // re-reported: de-duplicated
            svc.eventLog().emit(Event.builder(EventType.PIPELINE_CONSERVATION_IMBALANCE).pipeline(pipeline)
                    .message("node 'flt': 3 in, 2 out (LOSS)").attr("node", "flt").attr("kind", "LOSS")
                    .attr("recordsIn", 3L).attr("recordsOut", 2L).build());

            JsonNode list = V1Body.of(send(api.port(), "GET", "/alerts").body());
            long mine = 0;
            String gapId = null;
            for (JsonNode a : list) {
                if (!pipeline.equals(a.get("pipeline").asText())) continue;
                mine++;
                if ("sequence_gap".equals(a.get("rule").asText())) {
                    gapId = a.get("id").asText();
                    assertEquals("OPEN", a.get("state").asText());
                    assertEquals("high", a.get("severity").asText());
                }
            }
            assertEquals(2, mine, "one gap (not two) and one imbalance: " + list);
            assertNotNull(gapId);

            HttpResponse<String> ack = send(api.port(), "POST", "/alerts/" + gapId + "/ack");
            assertEquals(200, ack.statusCode(), ack.body());
            assertEquals("ACKNOWLEDGED", V1Body.of(ack.body()).get("state").asText());
        } finally {
            api.close();
            svc.close();
        }
    }
}
