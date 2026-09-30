package com.gamma.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.model.AssistModelSettings;
import com.gamma.agent.model.ModelProviderFactory;
import com.gamma.agent.model.ProviderSettings;
import com.gamma.control.ControlApi;
import com.gamma.service.CollectorService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSIST-MODEL-EGRESS-1: {@code POST /assist/settings/test} (and every provider the assist agent builds) goes
 * through the Space's model endpoint allowlist ({@code models} in {@code egress.toon}) before dialling. Real HTTP:
 * a live ControlApi with the real {@link UccAssistAgent}, and a live loopback "model server" that counts hits.
 */
class AssistModelEgressTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;
    private HttpServer model;
    private final AtomicInteger hits = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (model != null) model.stop(0);
        System.clearProperty("assist.settings.file");
        System.clearProperty("assist.write.root");
    }

    private int startModel() throws Exception {
        model = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        model.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] b = "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        model.start();
        return model.getAddress().getPort();
    }

    private JsonNode testRoute(String baseUrl, String... models) throws Exception {
        Path cfg = Files.createDirectories(dir.resolve("cfg"));
        StringBuilder toon = new StringBuilder("models[" + models.length + "]:");
        for (int i = 0; i < models.length; i++) toon.append(i == 0 ? " " : ",").append(models[i]);
        Files.writeString(cfg.resolve("egress.toon"), toon + "\n");
        System.setProperty("assist.write.root", cfg.toString());
        System.setProperty("assist.settings.file", dir.resolve("assist-settings.properties").toString());
        EnumMap<ModelTier, String> m = new EnumMap<>(ModelTier.class);
        for (ModelTier t : ModelTier.values()) m.put(t, "m");
        AssistModelSettings.save(new ProviderSettings("ollama", baseUrl, null, m, 15));

        Path pipe = AgentTestConfigs.writePipeline(dir);
        CollectorService svc = new CollectorService(List.of(pipe), 60, 1);
        svc.registerAgent(new UccAssistAgent(ModelProviderFactory.fromPersisted()));
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + api.port() + "/api/v1/assist/settings/test"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            return JSON.readTree(r.body()).get("data");
        } finally {
            api.close();
            svc.close();
        }
    }

    @Test
    void metadataAddressOffTheAllowlistIsRefusedWithoutDialling() throws Exception {
        JsonNode out = testRoute("http://169.254.169.254:80", "models.example.com");
        for (ModelTier t : ModelTier.values()) {
            JsonNode r = out.get(t.name().toLowerCase());
            assertNotNull(r, out.toString());
            assertFalse(r.get("ok").asBoolean(), out.toString());
            assertTrue(r.get("provider").asText().contains("model endpoint refused"), out.toString());
            assertFalse(r.has("latencyMs"), "refused before any call: " + out);
        }
    }

    @Test
    void loopbackNotNamedByTheAllowlistIsNeverDialled() throws Exception {
        int port = startModel();
        JsonNode out = testRoute("http://127.0.0.1:" + port, "models.example.com");
        assertEquals(0, hits.get(), "no outbound connection: " + out);
        assertFalse(out.get("small").get("ok").asBoolean(), out.toString());
    }

    @Test
    void allowlistedLoopbackWorks() throws Exception {
        int port = startModel();
        JsonNode out = testRoute("http://127.0.0.1:" + port, "127.0.0.1");
        assertTrue(hits.get() > 0, out.toString());
        assertTrue(out.get("small").get("ok").asBoolean(), out.toString());
    }
}
