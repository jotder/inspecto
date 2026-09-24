package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for {@code POST /space-comparisons} (space-comparison design, decided 2026-09-24) over a
 * real two-Space container — one test per fail-closed gate plus an end-to-end happy path that polls the run
 * to its result.
 *
 * <p>🔴 Every test arms an {@link Authenticator}. Without a Subject {@code withCapability} is a no-op, so a
 * test with no Subject passes against an UNGATED route — the capability gate is proven here only by a
 * capability-less Subject being refused.
 */
class ControlApiSpaceComparisonTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> "Bearer admin".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("root", Set.of("canAdminister")))
                : "Bearer plain".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("nobody", Set.of("canOperateRuns", "canAuthorWorkbench")))
                : Optional.empty());
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    /** A container hosting {@code alpha} and {@code beta}, each with a two-sample storage series. */
    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        Ctx c = new Ctx(spaces, api, api.port());
        assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"alpha\"}", "Bearer admin").statusCode());
        assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}", "Bearer admin").statusCode());
        grows(dataRoot(c, "alpha"), 100, 300);   // +20 b/day
        grows(dataRoot(c, "beta"), 100, 150);    // +5 b/day
        return c;
    }

    private static Path dataRoot(Ctx c, String id) {
        return c.spaces.space(SpaceId.of(id)).orElseThrow().service().dataRoot();
    }

    // ── gate: capability ─────────────────────────────────────────────────────────

    @Test
    void aCrossSpaceComparisonNeedsCanAdminister(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            String body = "{\"spaces\":[\"alpha\",\"beta\"]}";
            assertEquals(401, send(c.port, "POST", "/space-comparisons", body, null).statusCode(),
                    "no credential");
            HttpResponse<String> plain = send(c.port, "POST", "/space-comparisons", body, "Bearer plain");
            assertEquals(403, plain.statusCode(),
                    "run-operation and authoring capabilities are NOT enough to read across Spaces: " + plain.body());
            // ...and the refusal happened before the handler ran: the job host is created lazily by the
            // handler (jobServiceOrCreate), so a refused request leaves this job-less Space without one
            assertTrue(c.spaces.current().service().jobService().isEmpty(), "a refused request creates nothing");
        }
    }

    // ── gate: body (422) and hosted Space (404) ─────────────────────────────────

    @Test
    void aMalformedRequestIs422(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(422, admin(c, "{\"spaces\":[\"alpha\"]}").statusCode(), "one Space compares nothing");
            assertEquals(422, admin(c, "{}").statusCode(), "no spaces at all");
            assertEquals(422, admin(c, "{\"spaces\":[\"alpha\",\"alpha\"]}").statusCode(), "a duplicate");
            assertEquals(422, admin(c, "{\"spaces\":[\"alpha\",\"Bad Id\"]}").statusCode(), "an invalid id");
            assertEquals(422, admin(c, "{\"spaces\":[\"alpha\",\"beta\"],\"window_days\":0}").statusCode());
            assertEquals(422, admin(c, "{\"spaces\":[\"alpha\",\"beta\"],\"top\":\"many\"}").statusCode());
        }
    }

    @Test
    void anUnhostedSpaceIs404(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = admin(c, "{\"spaces\":[\"alpha\",\"ghost\"]}");
            assertEquals(404, r.statusCode(), r.body());
            assertTrue(r.body().contains("ghost"), r.body());
        }
    }

    // ── happy path: admitted, then the run compares both Spaces ─────────────────

    @Test
    void anAdministratorComparesTwoSpaces(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = admin(c, "{\"spaces\":[\"alpha\",\"beta\"],\"axes\":[\"data\"]}");
            assertEquals(202, r.statusCode(), r.body());
            JsonNode data = V1Body.of(r.body());
            String runId = data.get("runId").asText();
            assertEquals("/api/v1/jobs/runs/" + runId, r.headers().firstValue("Location").orElse(null));

            JsonNode run = await(c, runId);
            assertEquals("SUCCESS", run.get("status").asText(), run.toString());
            String msg = run.get("message").asText();
            assertTrue(msg.contains("2 comparable"), msg);
            assertTrue(msg.contains("data: alpha=300b(+20b/day) beta=150b(+5b/day) spread=150b fastest=alpha"), msg);
            assertEquals("space.comparison", run.get("type").asText());
            assertEquals("manual:root", run.get("trigger").asText(), "the actor is attributed");
        }
    }

    private JsonNode await(Ctx c, String runId) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> r = send(c.port, "GET", "/jobs/runs/" + runId, null, "Bearer admin");
            if (r.statusCode() == 200) {
                JsonNode run = V1Body.of(r.body());
                if (!"RUNNING".equals(run.get("status").asText())) return run;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("run " + runId + " did not finish within 20s");
    }

    private HttpResponse<String> admin(Ctx c, String body) throws Exception {
        return send(c.port, "POST", "/space-comparisons", body, "Bearer admin");
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** Two storage_report-shaped samples ten days apart ({@code data}: from → to; {@code config} flat). */
    private static void grows(Path dataRoot, long from, long to) throws Exception {
        Instant now = Instant.now();
        sample(dataRoot, now.minus(Duration.ofDays(10)), from);
        sample(dataRoot, now, to);
    }

    private static void sample(Path dataRoot, Instant created, long dataBytes) throws Exception {
        Path storeDir = dataRoot.resolve("maintenance_storage");
        Files.createDirectories(storeDir);
        Path parquet = storeDir.resolve("storage_" + created.toEpochMilli() + "_out.parquet");
        DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE s (created VARCHAR, created_ms BIGINT, axis VARCHAR, files BIGINT, bytes BIGINT)");
            st.execute("INSERT INTO s VALUES ('" + created + "', " + created.toEpochMilli() + ", 'data', 1, "
                    + dataBytes + "), ('" + created + "', " + created.toEpochMilli() + ", 'config', 1, 50)");
            st.execute("COPY s TO '" + parquet.toAbsolutePath().toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }
}
