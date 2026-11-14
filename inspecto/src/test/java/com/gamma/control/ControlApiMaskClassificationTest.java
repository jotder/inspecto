package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.spec.FindingCodes;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MASKING-UNION-RESIDUALS-1 over real HTTP ({@code POST /validate}, the save gate every save path runs): a {@code mask} step may
 * select any class THIS Space masks - here a class its own masked Entity Type claims - and is refused a class that masks nothing
 * in it, judged against the Space's own Entity Types, not the built-in ones.
 */
class ControlApiMaskClassificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** The {@code mask} findings of a validated pipeline draft that masks by {@code cls}. */
    private List<String> maskFindings(Ctx c, String cls) throws Exception {
        String body = """
                {"type":"pipeline","config":{"name":"masking","active":false,
                  "dirs":{"poll":"in","database":"out"},"processing":{"threads":1,"schema_file":"cdr.toon"},
                  "steps":[{"mask":{"classifications":["%s"],"dataset":"d"}}]}}""".formatted(cls);
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/validate"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        JsonNode findings = JSON.readTree(r.body()).path("data").path("findings");
        return java.util.stream.StreamSupport.stream(findings.spliterator(), false)
                // an inactive draft: the step refusal is a WARNING (it would refuse once active), as every save path reports it
                .filter(f -> FindingCodes.WARN_STEP_CONFIG_INVALID.equals(f.path("code").asText()))
                .map(f -> f.path("fieldPath").asText() + ": " + f.path("message").asText()).toList();
    }

    @Test
    void aMaskSelectsAClassTheSpacesOwnMaskedTypeClaimsAndIsRefusedOneThatMasksNothingThere(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), """
                entity_types[1]:
                  - id: sweep
                    label: Sweep
                    normaliser: default
                    masked: true
                    classifications[1]: SWEEP_REF
                """);
        try (Ctx c = open(cfg, root)) {
            assertEquals(List.of(), maskFindings(c, "SWEEP_REF"), "claimed by the Space's masked type: it masks");
            assertEquals(List.of(), maskFindings(c, "sweep"), "the masked type's id masks too");
            assertEquals(List.of(), maskFindings(c, "PII"), "a sensitive class masks in every Space");
            List<String> wallet = maskFindings(c, "WALLET");
            assertEquals(1, wallet.size(), "the built-in wallet type is not in force in this Space: " + wallet);
            assertTrue(wallet.get(0).startsWith("steps[0].mask: ") && wallet.get(0).contains("'WALLET' masks nothing in this Space"),
                    wallet.get(0));
        }
    }
}
