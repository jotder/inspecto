package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DR-T8 with a REAL file append: the bound Dataset reads a directory of CSV files, a second file lands after the
 * Investigation sealed its read. Replay (no re-read) is unchanged and does not show the new rows; {@code reread:true}
 * reports drift (G-E3). The sibling {@code ControlApiInvestigationsTest#theSealHoldsWhenDataGrowsAndRereadReportsTheDrift}
 * does the same by rewriting a VALUES view; this one is the demo's actual move (copy the next day's file in).
 */
class ControlApiReplayDriftOnFileAppendTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private JsonNode post(int port, String path, String body) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void replayIsUnchangedAndRereadReportsDriftWhenAFileLandsInTheBoundDataset(@TempDir Path cfg, @TempDir Path root, @TempDir Path inbox)
            throws Exception {
        Files.writeString(inbox.resolve("DAY1.csv"), "caller,callee,channel\nalice,bob,sms\nalice,carol,call\nbob,dave,call\n");
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                String glob = inbox.toAbsolutePath().toString().replace('\\', '/') + "/*.csv";
                new ViewStore(root.resolve("views")).write(new ViewDefinition("files_view", "flow-x", List.of(),
                        "SELECT caller, callee, channel FROM read_csv('" + glob + "', header=true)", "2026-09-23T00:00:00Z"));
                new ComponentStore(root.resolve("registry")).write("dataset", "files_ds", Map.of("view", "files_view"));
                int port = api.port();
                post(port, "/inv/investigations", "{\"purpose\":\"DR-T8\",\"id\":\"drift-a\",\"dataset\":\"files_ds\","
                        + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}");
                post(port, "/inv/investigations/drift-a/ops", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
                JsonNode expanded = post(port, "/inv/investigations/drift-a/ops", "{\"op\":\"expand\"}");
                String hash = expanded.at("/workingSet/hash").asText();
                String sealed = expanded.at("/read/fingerprint").asText();

                assertFalse(post(port, "/inv/investigations/drift-a/replay", "{\"reread\":true}").get("diverged").asBoolean(),
                        "before the new file: a re-read agrees");

                Files.writeString(inbox.resolve("DAY2.csv"), "caller,callee,channel\nalice,zoe,call\n");

                JsonNode replay = post(port, "/inv/investigations/drift-a/replay", "{}");
                assertEquals(hash, replay.at("/workingSet/hash").asText(), "Replay hash unchanged after the file landed");
                assertFalse(replay.at("/workingSet").toString().contains("zoe"), "the sealed read does not show the new rows");

                JsonNode reread = post(port, "/inv/investigations/drift-a/replay", "{\"reread\":true}");
                assertTrue(reread.get("diverged").asBoolean(), "Re-read reports the drift");
                assertNotEquals(sealed, reread.at("/drift/0/currentFingerprint").asText());
                assertEquals(hash, reread.at("/workingSet/hash").asText(), "a re-read reports; it does not rewrite");
            } finally {
                api.close();
                svc.close();
            }
        } finally {
            System.clearProperty("assist.write.root");
        }
    }
}
