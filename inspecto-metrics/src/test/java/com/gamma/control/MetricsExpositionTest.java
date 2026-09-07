package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EDG-01 cell 5, the POSITIVE half: with {@code inspecto-metrics} on the classpath, {@code GET /metrics}
 * answers the Prometheus exposition — open, unversioned, and reflecting a real run.
 *
 * <p>This test <b>moved here from {@code ControlApiTest.metricsEndpointIsOpenAndReflectsARun}</b> rather than
 * being deleted. Its assertions are the contract a scraper depends on and they are still true — just no
 * longer true of the DEFAULT (Personal) build, where the module is absent and the core's
 * {@code AbsentMetricsRoutes} answers 503 instead. The two halves are deliberately paired: this class proves
 * the exposition WITH the module, {@code NoExchangeShipsInThePersonalBuildTest} proves the 503 WITHOUT it.
 * A path that drifted out of step would fail one of them.
 *
 * <p>⚠ Lives in package {@code com.gamma.control} so it can construct the package-private {@link ControlApi},
 * the same split-package test arrangement {@code inspecto-policy} and {@code inspecto-exchange} use.
 */
class MetricsExpositionTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** No {@code /api/v1} prefix and no token: infra probes stay unversioned, and scrapers carry no credentials. */
    private HttpResponse<String> scrape(int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics")).GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void metricsEndpointIsOpenAndReflectsARun(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.svc.start();   // wires MetricsService onto the bus + runs an immediate poll cycle
            // wait for the scheduled cycle to commit at least one batch
            long deadline = System.nanoTime() + 8_000_000_000L;
            String body = "";
            while (System.nanoTime() < deadline) {
                HttpResponse<String> m = scrape(c.port);
                assertEquals(200, m.statusCode(), "the module is on the classpath, so this is the real exposition");
                assertTrue(m.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
                body = m.body();
                if (body.contains("inspecto_batches_total")) break;
                Thread.sleep(150);
            }
            assertTrue(body.contains("# TYPE inspecto_batches_total counter"), "Prometheus exposition present");
            assertTrue(body.contains("inspecto_batches_total{pipeline=\"test_etl\",status=\"SUCCESS\"}"),
                    "a committed batch was counted:\n" + body);
            assertTrue(body.contains("inspecto_poll_cycles_total"), "poll cycle counted");
            assertTrue(body.contains("inspecto_committed_batches{pipeline=\"test_etl\"}"),
                    "scrape-time gauge populated");
        }
    }

    /**
     * The shape claim {@code ControlApiVersionedSurfaceTest} can no longer make on the Personal build: the
     * exposition is RAW text in Prometheus' own content type, never the v1 JSON envelope. That exemption is
     * the whole reason {@code /metrics} is an infra route.
     */
    @Test
    void theExpositionIsRawTextNotTheV1Envelope(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> m = scrape(c.port);
            assertEquals(200, m.statusCode(), m.body());
            assertEquals("text/plain; version=0.0.4; charset=utf-8",
                    m.headers().firstValue("Content-Type").orElse(""),
                    "version=0.0.4 is the exposition format a scraper negotiates on");
            assertFalse(m.body().contains("\"data\""), "not envelope-shaped: " + m.body());
        }
    }
}
