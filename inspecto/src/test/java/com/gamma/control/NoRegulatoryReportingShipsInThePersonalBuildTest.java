package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
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
 * REGULATORY-REPORTING-1 - on the DEFAULT (Personal) build, which never compiles the optional
 * inspecto-regulatory-reporting module, every Regulatory Report path answers <b>503 naming the module</b> (synthesised
 * from its manifest), never 404 (the stub lost a path) and never 200 (the routes crept into the core). The sibling of
 * {@link NoActionRequestsShipsInThePersonalBuildTest}.
 */
class NoRegulatoryReportingShipsInThePersonalBuildTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void everyRegulatoryReportPathAnswers503NotInstalledOnThePersonalBuild(@TempDir Path dir, @TempDir Path wr) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        System.setProperty("assist.write.root", wr.toString());
        CollectorService svc;
        try {
            svc = new CollectorService(List.of(toon), 3600, 1);
        } finally {
            System.clearProperty("assist.write.root");
        }
        ControlApi api = new ControlApi(svc, 0);
        try {
            api.start();
            List<String[]> surface = AbsentModuleRoutes.surface("regulatory-reporting");
            assertEquals(8, surface.size(), "the manifest names the eight Regulatory Report routes");
            for (String[] r : surface) {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1"
                        + r[1].replace("([^/]+)", "probe"))).header("Content-Type", "application/json");
                b = "GET".equals(r[0]) ? b.GET() : b.method(r[0], BodyPublishers.ofString("{}"));
                HttpResponse<String> res = client.send(b.build(), BodyHandlers.ofString());
                assertEquals(503, res.statusCode(), r[0] + " " + r[1] + " -> " + res.body());
                JsonNode err = V1Body.of(res.body()).get("error");
                assertNotNull(err, r[1] + " must carry the v1 error object: " + res.body());
                assertTrue(err.get("message").asText().contains("inspecto-regulatory-reporting"),
                        "the refusal names the module that would fix it: " + err);
            }
        } finally {
            api.close();
            svc.close();
        }
    }
}
