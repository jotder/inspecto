package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.service.ConfigRegistry;
import com.gamma.service.ServiceBootstrap;
import com.gamma.service.SpaceRoot;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The PERSONAL half of `PROCESSOR-RELEASE-READINESS-1` G9 (operator decision 2026-09-24, "gate the code"):
 * this module's test classpath carries no Professional module, so it IS the Personal build, and each of the
 * three Professional+ features is refused with {@code ERR_EDITION_FEATURE} at every door it can come through.
 * The Professional half — the same configs accepted because the real module declares the feature — lives
 * where those modules are: {@code ProfessionalAlertRulesAcceptedTest} (inspecto-ops) and
 * {@code ProfessionalSinkFeaturesAcceptedTest} (inspecto-backup).
 */
class EditionFeatureGateTest {

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

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private static final String RULE = """
            {"name":"%s","metric":"error_rate","comparator":"gt","threshold":0.1,"window":"1h","severity":"WARNING"}""";

    private static void assertEditionRefused(HttpResponse<String> r, String feature) throws Exception {
        assertEquals(422, r.statusCode(), r.body());
        // The save gate's 422 shape: the v1 catalog code, and the finding code in details.findings.
        JsonNode err = V1Body.envelope(r.body()).get("error");
        assertEquals("CONFIG_VALIDATION_FAILED", err.get("errorCode").asText(), r.body());
        JsonNode finding = err.get("details").get("findings").get(0);
        assertEquals(FindingCodes.ERR_EDITION_FEATURE, finding.get("code").asText(), r.body());
        assertTrue(finding.get("message").asText().contains("Professional+"), r.body());
        assertTrue(finding.get("message").asText().contains(feature), r.body());
    }

    @Test
    void thisModuleIsThePersonalBuild() {
        for (String f : List.of(EditionFeatures.ALERT_DISPATCH, EditionFeatures.SINK_ARCHIVE, EditionFeatures.SINK_DUCKLAKE))
            assertFalse(EditionFeatures.present(f), f + " must be absent without a Professional module");
    }

    @Test
    void everyAlertRuleAuthoringDoorRefuses(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            assertEditionRefused(send(c.port, "POST", "/alerts/rules", RULE.formatted("r1")), "Alert Rules");
            assertEditionRefused(send(c.port, "POST", "/components/alert-rule", RULE.formatted("r2")), "Alert Rules");
            // A Decision Rule's create-alert consequence records the refusal instead of authoring the rule.
            send(c.port, "POST", "/decision-rules", "{\"name\":\"breach\",\"targetType\":\"pipeline\",\"target\":\"x\","
                    + "\"consequences\":[{\"action\":\"create-alert\",\"params\":{\"rule\":\"r3\",\"metric\":\"error_rate\","
                    + "\"comparator\":\"gt\",\"threshold\":0.1,\"window\":\"1h\"}}]}");
            String detail = V1Body.of(send(c.port, "POST", "/decision-rules/breach/apply", null).body())
                    .get("executed").get(0).get("detail").asText();
            assertTrue(detail.contains("could not author") && detail.contains("Professional+"), detail);
            assertEquals("[]", V1Body.of(send(c.port, "GET", "/alerts/rules", null).body()).toString(),
                    "nothing armed");
            assertFalse(Files.exists(wr.resolve("registry/alert-rules")), "nothing persisted");
        }
    }

    private static Map<String, Object> pipeline(String key, Object value) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "P");
        d.put("active", false);
        d.put(key, value);
        return d;
    }

    private static List<Finding> editionFindings(String type, Map<String, Object> draft) {
        return SaveGate.check(null, type, draft, null, null, SaveGate.Referents.MUST_EXIST).stream()
                .filter(f -> FindingCodes.ERR_EDITION_FEATURE.equals(f.code())).toList();
    }

    @Test
    void theSaveGateRefusesTheArchiveAndDuckLakeEvenOnAnInactiveDraft() {
        List<Finding> archive = editionFindings("pipeline", pipeline("collector", Map.of("connector", "sftp",
                "post_action", Map.of("on_success", "move", "archive_path", "archive/yyyy"))));
        assertEquals(1, archive.size(), archive.toString());
        assertEquals(Severity.ERROR, archive.get(0).severity());
        assertEquals("collector.post_action.on_success", archive.get(0).fieldPath());
        assertTrue(archive.get(0).message().contains("sink.archive"), archive.get(0).message());

        Map<String, Object> lake = Map.of("enabled", true, "catalog_url", "lake.ducklake", "table", "t");
        List<Finding> ducklake = editionFindings("pipeline", pipeline("output", Map.of("format", "PARQUET", "ducklake", lake)));
        assertEquals(1, ducklake.size(), ducklake.toString());
        assertEquals("output.ducklake", ducklake.get(0).fieldPath());
        assertEquals("sinks[1].ducklake", editionFindings("pipeline",
                pipeline("sinks", List.of(Map.of("database", "a"), Map.of("database", "b", "ducklake", lake))))
                .get(0).fieldPath());

        assertEquals(1, editionFindings("alert", Map.of("name", "a")).size(), "the legacy alert type too");
        // What stays Personal: every other post-action, and a lake block that is not enabled.
        assertEquals(List.of(), editionFindings("pipeline", pipeline("collector", Map.of("connector", "sftp",
                "post_action", Map.of("on_success", "DELETE")))));
        assertEquals(List.of(), editionFindings("pipeline", pipeline("output",
                Map.of("ducklake", Map.of("enabled", false)))));
    }

    @Test
    void configWriteRefusesTheArchiveOverHttp(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            HttpResponse<String> r = send(c.port, "POST", "/config/write", """
                    {"type":"pipeline","config":{"name":"arch","active":false,
                      "dirs":{"poll":"in","database":"out"},"processing":{"threads":1,"schema_file":"cdr.toon"},
                      "collector":{"connector":"sftp","post_action":{"on_success":"MOVE","archive_path":"archive"}}}}""");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("\"code\":\"ERR_EDITION_FEATURE\""), r.body());
            assertFalse(Files.exists(wr.resolve("arch_pipeline.toon")));
        }
    }

    @Test
    void aDuckLakePipelineOnDiskIsALoadFailureNotARegisteredPipeline(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        Files.writeString(p, Files.readString(p).replace("output:\n  format: CSV", """
                output:
                  format: PARQUET
                  ducklake:
                    enabled: true
                    catalog_url: lake.ducklake
                    data_path: lake-data
                    table: t"""));
        assertTrue(Files.readString(p).contains("ducklake"), "fixture rewritten");
        ConfigRegistry reg = new ConfigRegistry(null);
        reg.rebuild(List.of(p));
        assertTrue(reg.all().isEmpty(), "never registered");
        assertEquals(1, reg.failures().size());
        String msg = reg.failures().get(0).message();
        assertTrue(msg.contains(FindingCodes.ERR_EDITION_FEATURE) && msg.contains("sink.ducklake"), msg);
    }

    @Test
    void anAlertRuleCopiedOntoDiskIsNotArmedAtBoot(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("space");
        Files.createDirectories(base.resolve("config"));
        new ComponentStore(base.resolve("config/registry")).write("alert-rule", "r1", Map.of(
                "name", "r1", "metric", "error_rate", "comparator", "gt", "threshold", 0.1, "window", "1h"));
        SpaceRoot root = SpaceRoot.under(base);
        try (CollectorService svc = ServiceBootstrap.buildFrom(root, new String[]{root.config().toString()}, false)) {
            assertTrue(svc.alertService().map(a -> a.rules().isEmpty()).orElse(true), "no rule armed");
        }
    }
}
