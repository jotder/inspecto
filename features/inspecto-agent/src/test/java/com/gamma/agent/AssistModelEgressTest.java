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
        System.clearProperty("agentkernel.ollama.enabled");
        System.clearProperty("agentkernel.ollama.baseUrl");
    }

    private void enableEnvOllama(int port) {
        System.setProperty("agentkernel.ollama.enabled", "true");
        System.setProperty("agentkernel.ollama.baseUrl", "http://127.0.0.1:" + port);
    }

    @Test
    void envVarFallbackRouterIsRefusedAndNeverDialled() throws Exception {
        int port = startModel();
        enableEnvOllama(port);
        var r = com.gamma.agent.model.ModelProviderFactory.fromEnvironment(
                com.gamma.pipeline.exec.ModelEgress.Policy.EMPTY, com.gamma.util.egress.EgressPolicy.SYSTEM);
        assertFalse(r.providerFor(ModelTier.MEDIUM).available());
        assertTrue(r.providerFor(ModelTier.MEDIUM).name().contains("model endpoint refused"));
        assertEquals(0, hits.get());
    }

    @Test
    void settingsTestRouteWithNoSettingsFileUsesTheCheckedEnvironmentRouter() throws Exception {
        int port = startModel();
        enableEnvOllama(port);
        System.setProperty("assist.settings.file", dir.resolve("absent.properties").toString());
        var agent = new UccAssistAgent(ModelProviderFactory.fromPersisted());
        var out = agent.testSettings();
        assertEquals(0, hits.get(), out.toString());
        assertFalse((Boolean) ((java.util.Map<?, ?>) out.get("medium")).get("ok"), out.toString());
    }

    @Test
    void aiDescriptionProviderFromEnvironmentNeverDialsAnUnlistedEndpoint() throws Exception {
        int port = startModel();
        enableEnvOllama(port);
        var d = new com.gamma.agent.catalog.AiDescriptionProvider().describeColumn(
                new com.gamma.catalog.spi.DescriptionProvider.ColumnContext("p", "t", "c", "INT", null));
        assertEquals(com.gamma.catalog.Description.EMPTY, d);
        assertEquals(0, hits.get());
    }

    @Test
    void theClientDialsTheCheckedAddressNotASecondResolution() throws Exception {
        int port = startModel();
        var calls = new AtomicInteger();
        com.gamma.util.egress.EgressPolicy.Resolver flipping = host -> new InetAddress[]{
                calls.getAndIncrement() == 0 ? InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
                        : InetAddress.getByAddress(new byte[]{10, 9, 9, 9})};
        EnumMap<ModelTier, String> m = new EnumMap<>(ModelTier.class);
        for (ModelTier t : ModelTier.values()) m.put(t, "m");
        var r = ModelProviderFactory.create(
                new ProviderSettings("ollama", "http://model.test:" + port, null, m, 15),
                com.gamma.pipeline.exec.ModelEgress.parse(List.of("model.test", "127.0.0.1")), flipping);
        r.providerFor(ModelTier.MEDIUM).generate(
                com.gamma.agent.kernel.model.ModelRequest.text(ModelTier.MEDIUM, null, "hi"));
        assertTrue(hits.get() > 0, "the stub on the FIRST (checked) address got the call");
        assertEquals(1, calls.get(), "resolved once");
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

    @Test
    void theEnvironmentRouterDialsTheCheckedAddressNotASecondResolution() throws Exception {
        int port = startModel();
        System.setProperty("agentkernel.ollama.enabled", "true");
        System.setProperty("agentkernel.ollama.baseUrl", "http://model.test:" + port);
        var calls = new AtomicInteger();
        com.gamma.util.egress.EgressPolicy.Resolver flipping = host -> new InetAddress[]{
                calls.getAndIncrement() == 0 ? InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
                        : InetAddress.getByAddress(new byte[]{10, 9, 9, 9})};
        var r = ModelProviderFactory.fromEnvironment(
                com.gamma.pipeline.exec.ModelEgress.parse(List.of("model.test", "127.0.0.1")), flipping);
        r.providerFor(ModelTier.MEDIUM).generate(
                com.gamma.agent.kernel.model.ModelRequest.text(ModelTier.MEDIUM, null, "hi"));
        assertTrue(hits.get() > 0, "the stub on the FIRST (checked) address got the call");
        assertEquals(1, calls.get(), "resolved once");
    }

    @Test
    void disabledEnvironmentProfileDialsNothingFromTheSettingsTestRoute() throws Exception {
        startModel();
        System.setProperty("agentkernel.ollama.baseUrl", "http://127.0.0.1:" + model.getAddress().getPort());
        System.setProperty("assist.settings.file", dir.resolve("absent.properties").toString());
        var out = new UccAssistAgent(ModelProviderFactory.fromPersisted()).testSettings();
        assertEquals(0, hits.get(), out.toString());
        assertFalse((Boolean) ((java.util.Map<?, ?>) out.get("medium")).get("ok"), out.toString());
    }

    @Test
    void anthropicAndGeminiRefuseABaseUrlAtSettingsValidation() {
        System.setProperty("assist.settings.file", dir.resolve("s.properties").toString());
        var agent = new UccAssistAgent(ModelProviderFactory.fromPersisted());
        for (String p : List.of("anthropic", "gemini")) {
            var e = assertThrows(IllegalArgumentException.class, () ->
                    agent.updateSettings(java.util.Map.of("provider", p, "baseUrl", "http://169.254.169.254")));
            assertTrue(e.getMessage().contains("vendor endpoint"), e.getMessage());
        }
    }

    @Test
    void onlyTheFactoryReachesTheUncheckedEnvironmentRouter() throws Exception {
        var m = com.gamma.agent.model.OllamaModelProvider.class.getDeclaredMethod("fromEnvironment");
        assertFalse(java.lang.reflect.Modifier.isPublic(m.getModifiers()),
                "OllamaModelProvider.fromEnvironment is unchecked; it must stay package-private");
    }
}
