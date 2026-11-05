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
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LA-COLUMN-ROLES-2 (operator decision D-3 b): Filter by role over a read of several Datasets has one section per Dataset,
 * each pushed as THAT edge mapping's own {@code filter} on {@code POST /inv/projection/multi}. Proved here: a filter on one
 * Dataset narrows only it - the other Dataset's links all come back, even one naming a column only the filtered Dataset
 * has. Synthetic data only.
 */
class ControlApiMultiDatasetFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private static Ctx open(Path cfg, Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
            ViewStore views = new ViewStore(root.resolve("views"));
            views.write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('A','B','call'),('A','C','sms'),('B','C','call')) AS v(s,t,kind)", "2026-11-05T00:00:00Z"));
            // the other Dataset has no `kind` at all, and a channel instead
            views.write(new ViewDefinition("pays_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('A','D','app'),('D','E','agent')) AS v(payer,payee,channel)", "2026-11-05T00:00:00Z"));
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            store.write("dataset", "calls_ds", Map.of("view", "calls_view"));
            store.write("dataset", "pays_ds", Map.of("view", "pays_view"));
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static JsonNode multi(Ctx c, String body) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/inv/projection/multi"))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static TreeSet<String> links(JsonNode d) {
        TreeSet<String> out = new TreeSet<>();
        for (JsonNode e : d.get("edges")) out.add(e.get("__provenance_dataset").asText() + ":" + e.get("source").asText() + ">" + e.get("target").asText());
        return out;
    }

    private static String cond(String field, String op, String value) {
        return "{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"" + field + "\",\"operator\":\"" + op
                + "\",\"value\":\"" + value + "\"}]}";
    }

    @Test
    void aFilterOnOneDatasetNarrowsOnlyThatDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String calls = "{\"dataset\":\"calls_ds\",\"sourceColumn\":\"s\",\"targetColumn\":\"t\"";
            String pays = "{\"dataset\":\"pays_ds\",\"sourceColumn\":\"payer\",\"targetColumn\":\"payee\"";
            TreeSet<String> all = links(multi(c, "{\"edges\":[" + calls + "}," + pays + "}]}"));
            assertEquals(new TreeSet<>(List.of("calls_ds:A>B", "calls_ds:A>C", "calls_ds:B>C", "pays_ds:A>D", "pays_ds:D>E")), all);

            // calls_ds keeps its calls only; pays_ds - which has no `kind` column - is untouched
            TreeSet<String> callsOnly = links(multi(c, "{\"edges\":[" + calls + ",\"filter\":" + cond("kind", "=", "call") + "}," + pays + "}]}"));
            assertEquals(new TreeSet<>(List.of("calls_ds:A>B", "calls_ds:B>C", "pays_ds:A>D", "pays_ds:D>E")), callsOnly);

            // and the other way round, each Dataset with its own section's filter
            TreeSet<String> both = links(multi(c, "{\"edges\":[" + calls + ",\"filter\":" + cond("kind", "=", "sms") + "},"
                    + pays + ",\"filter\":" + cond("channel", "=", "agent") + "}]}"));
            assertEquals(new TreeSet<>(List.of("calls_ds:A>C", "pays_ds:D>E")), both);
        }
    }
}
