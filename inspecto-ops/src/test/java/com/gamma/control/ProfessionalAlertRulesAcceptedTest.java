package com.gamma.control;

import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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
 * The PROFESSIONAL half of `PROCESSOR-RELEASE-READINESS-1` G9 for Alert Rules: with this module on the
 * classpath its real {@code OpsEditionFeatures} provider is discovered through
 * {@code META-INF/services/com.gamma.etl.EditionFeatureProvider} — no test override — and the authoring
 * doors that core's {@code EditionFeatureGateTest} proves refused on Personal accept the rule.
 */
class ProfessionalAlertRulesAcceptedTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
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

    @Test
    void theModuleDeclaresAlertRulesAndTheRoutesAcceptThem(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        assertTrue(EditionFeatures.present(EditionFeatures.ALERT_DISPATCH), "declared by inspecto-ops");
        try (Ctx c = open(cfg, wr)) {
            HttpResponse<String> r = send(c.port, "POST", "/alerts/rules", """
                    {"name":"pro_rule","metric":"error_rate","comparator":"gt","threshold":0.1,"window":"1h","severity":"WARNING"}""");
            assertTrue(r.statusCode() / 100 == 2, r.statusCode() + " " + r.body());
            assertFalse(r.body().contains("ERR_EDITION_FEATURE"), r.body());
            assertTrue(send(c.port, "GET", "/alerts/rules", null).body().contains("pro_rule"), "armed");
        }
    }
}
