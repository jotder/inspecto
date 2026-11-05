package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.PipelineTrigger;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Operator 2026-11-05: on a real Standard bundle - this module on the class path, NO test override - an import can be kept up
 * to date and given a retention policy. {@code inspecto-ops} declares {@code pipeline.automation}; before, nothing did, so
 * Standard refused <i>Keep this up to date</i> as Basic does. Synthetic fixture.
 */
class ControlApiKeepUpToDateStandardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final String id = "kept_" + UUID.randomUUID().toString().substring(0, 8);

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Path data = Path.of("database").toAbsolutePath();
        OnboardRoutes.deleteTree(data.resolve(id));
        OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    private JsonNode call(Ctx c, String method, String path, String body, int expected) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path));
        b = body == null ? b.GET() : b.method(method, BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> r = client.send(b.build(), BodyHandlers.ofString());
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void onAStandardBundleAnImportCanBeKeptUpToDateAndGivenARetentionPolicy(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(null);   // the real class path: inspecto-ops is what Standard adds
        try (Ctx c = open(cfg, root)) {
            String csv = "transfer_id,from_account,to_account,amount\nT1,ACC-1,ACC-2,10\nT2,ACC-2,ACC-3,20\n";
            JsonNode d = call(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", csv, 200);
            call(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", 200);

            assertTrue(call(c, "GET", "/datasets/" + id + "/feed", null, 200).get("automatic").asBoolean(), "the screens offer Keep");
            JsonNode kept = call(c, "PUT", "/datasets/" + id + "/feed", "{\"kind\":\"folder\",\"every\":\"15m\"}", 200);
            assertEquals("folder", kept.at("/feed/kind").asText(), kept.toString());
            assertEquals(PipelineTrigger.Kind.SCHEDULE_INTERVAL, PipelineTrigger.of(c.svc().configFor(id).orElseThrow().triggerConfig()).kind(),
                    "the import Pipeline now runs on the scheduler");

            assertTrue(call(c, "GET", "/datasets/" + id + "/storage", null, 200).get("retentionAvailable").asBoolean());
            JsonNode kept7 = call(c, "PUT", "/datasets/" + id + "/storage", "{\"retention\":{\"days\":7}}", 200);
            assertEquals(7, kept7.at("/retention/days").asInt(), kept7.toString());
        }
    }
}
