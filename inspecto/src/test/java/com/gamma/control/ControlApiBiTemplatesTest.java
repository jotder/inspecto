package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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

import static org.junit.jupiter.api.Assertions.*;

/** BI-8 template gallery: listing, parameterized apply, all-or-nothing conflict handling. */
class ControlApiBiTemplatesTest {

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

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
                .build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void galleryListsAndAppliesParameterized(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            ComponentStore reg = new ComponentStore(root.resolve("registry"));
            reg.write("dataset", "sales_ds", Map.of("physicalRef", "sales"));

            HttpResponse<String> gallery = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/api/v1/bi/templates")).GET().build(),
                    BodyHandlers.ofString());
            assertEquals(200, gallery.statusCode());
            JsonNode list = V1Body.of(gallery.body());
            assertTrue(list.size() >= 3, "curated gallery ships at least three templates");
            assertTrue(gallery.body().contains("\"trend-monitor\""), "the temporal starter is listed: " + gallery.body());

            HttpResponse<String> applied = post(c.port, "/bi/templates/kpi-overview/apply",
                    "{\"dataset\":\"sales_ds\"}");
            assertEquals(200, applied.statusCode(), applied.body());
            assertEquals(4, V1Body.of(applied.body()).at("/created").size(),
                    "3 widgets + 1 dashboard");

            // The applied dashboard is a real, editable component bound to the caller's dataset.
            Map<String, Object> widget = reg.get("widget", "sum_by_dim").orElseThrow().content();
            assertEquals("sales_ds", widget.get("datasetId"));
            assertTrue(reg.get("dashboard", "kpi_board").isPresent());

            // Re-apply without a prefix → 409 (all-or-nothing); with a prefix → fresh ids.
            assertEquals(409, post(c.port, "/bi/templates/kpi-overview/apply",
                    "{\"dataset\":\"sales_ds\"}").statusCode());
            assertEquals(200, post(c.port, "/bi/templates/kpi-overview/apply",
                    "{\"dataset\":\"sales_ds\",\"prefix\":\"q3\"}").statusCode());
            assertTrue(reg.get("dashboard", "q3_kpi_board").isPresent());
        }
    }

    /**
     * COMPONENT-BULK-WRITERS-UNGATED-1: {@code BiTemplates.apply} writes through {@code ComponentStore}
     * directly, so no {@code ComponentRoutes.validateKind} gate — and therefore no {@code widget}/
     * {@code dashboard} top-level key census — ever runs over a curated template.
     *
     * <p>⚠ CORRECTED 2026-09-17. This javadoc used to justify the build-time-only shape with "the gate
     * cannot be called from here ({@code validateKind} is private)". That is FALSE and was already false
     * when written: {@code validateKind} is package-private (widened for {@code BundleRoutes}), and
     * {@code BiTemplates} is in this very package. {@code BiTemplates.apply} now calls it in its resolve
     * loop, so the row is closed at run time too.
     *
     * <p>The javadoc's OTHER argument still holds and is why this test stays: the runtime gate cannot
     * fire today, because template bodies are hardcoded and {@code substituteTree} substitutes VALUES
     * only — the only operator input is the {@code dataset}/{@code prefix} values, and the census refuses
     * KEYS. So this build-time pin is the guard that can actually go red for a badly authored template;
     * every component a template writes must be one the authoring route would accept, or the gallery
     * ships boards the Studio cannot re-save.
     */
    @Test
    void everyTemplateWritesABodyTheAuthoringRouteAccepts(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "sales_ds", Map.of("physicalRef", "sales"));

            JsonNode gallery = V1Body.of(send(c.port, "GET", "/bi/templates", null).body());
            assertTrue(gallery.size() >= 3, "gallery is non-empty, or this test proves nothing");

            int checked = 0;
            for (JsonNode t : gallery) {
                String templateId = t.get("id").asText();
                HttpResponse<String> applied = post(c.port, "/bi/templates/" + templateId + "/apply",
                        "{\"dataset\":\"sales_ds\",\"prefix\":\"" + templateId.replace('-', '_') + "\"}");
                assertEquals(200, applied.statusCode(), templateId + " apply: " + applied.body());

                for (JsonNode created : V1Body.of(applied.body()).at("/created")) {
                    String kind = created.get("kind").asText();
                    String id = created.get("id").asText();
                    JsonNode stored = V1Body.of(send(c.port, "GET", "/components/" + kind + "/" + id, null).body());
                    HttpResponse<String> resave = send(c.port, "PUT", "/components/" + kind + "/" + id,
                            JSON.writeValueAsString(stored.get("content")));
                    assertEquals(200, resave.statusCode(),
                            templateId + " wrote a " + kind + " '" + id + "' the authoring route refuses: "
                                    + resave.body());
                    checked++;
                }
            }
            assertTrue(checked >= 9, "every curated template's components were re-saved, got " + checked);
        }
    }

    @Test
    void applyFailsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, post(c.port, "/bi/templates/ghost/apply",
                    "{\"dataset\":\"x\"}").statusCode());
            assertEquals(422, post(c.port, "/bi/templates/kpi-overview/apply", "{}").statusCode());
            assertEquals(404, post(c.port, "/bi/templates/kpi-overview/apply",
                    "{\"dataset\":\"missing_ds\"}").statusCode(), "unknown dataset");
        }
    }
}
