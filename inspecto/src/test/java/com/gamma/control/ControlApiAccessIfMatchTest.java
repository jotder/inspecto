package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
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
 * Optimistic concurrency on {@code PUT /access/roles}, {@code /access/policies} and
 * {@code /access/catalog} — {@code IFMATCH-COVERAGE-GAP-1}.
 *
 * <p>Each of these is a full-replace settings/registry doc that {@code GET} already serves behind a
 * strong content {@link ETags} ETag ({@code ETags.respond}), edited by one operator at a time through
 * the Access admin UI — a genuine read-modify-write two concurrent admins can silently clobber. The
 * writes now honour an optional {@code If-Match} against that same ETag, mirroring
 * {@code ComponentRoutes}/{@code PipelineGraphRoutes}.
 */
class ControlApiAccessIfMatchTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Files.createDirectories(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @AfterEach
    void clearWriteRoot() {
        System.clearProperty("assist.write.root");
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String ifMatch)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (ifMatch != null) b.header("If-Match", ifMatch);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private String etagOf(int port, String path) throws Exception {
        HttpResponse<String> r = send(port, "GET", path, null, null);
        assertEquals(200, r.statusCode());
        return r.headers().firstValue("ETag").orElseThrow(
                () -> new AssertionError("GET " + path + " served no ETag"));
    }

    @Test
    void rolesStalePreconditionIsRefusedWith409(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String held = etagOf(c.port, "/access/roles");

            assertEquals(200, send(c.port, "PUT", "/access/roles", """
                    {"roles":[{"name":"analyst","capabilities":["canOperateRuns"]}]}""", held)
                    .statusCode());

            HttpResponse<String> stale = send(c.port, "PUT", "/access/roles", """
                    {"roles":[{"name":"analyst","capabilities":["canAuthorWorkbench"]}]}""", held);
            assertEquals(409, stale.statusCode(), stale.body());
            assertTrue(stale.body().contains("CONFLICT_STALE_VERSION"), stale.body());
        }
    }

    @Test
    void rolesFreshPreconditionAndNoPreconditionBothSucceed(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "PUT", "/access/roles", """
                    {"roles":[{"name":"analyst","capabilities":["canOperateRuns"]}]}""", null)
                    .statusCode(), "no precondition still works");

            String fresh = etagOf(c.port, "/access/roles");
            assertEquals(200, send(c.port, "PUT", "/access/roles", """
                    {"roles":[{"name":"analyst","capabilities":["canAuthorWorkbench"]}]}""", fresh)
                    .statusCode(), "fresh precondition works");
        }
    }

    @Test
    void policiesStalePreconditionIsRefusedWith409(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String held = etagOf(c.port, "/access/policies");

            assertEquals(200, send(c.port, "PUT", "/access/policies", """
                    {"policies":[{"name":"deny-all","effect":"deny"}]}""", held).statusCode());

            HttpResponse<String> stale = send(c.port, "PUT", "/access/policies", """
                    {"policies":[{"name":"allow-all","effect":"allow"}]}""", held);
            assertEquals(409, stale.statusCode(), stale.body());
            assertTrue(stale.body().contains("CONFLICT_STALE_VERSION"), stale.body());
        }
    }

    @Test
    void catalogStalePreconditionIsRefusedWith409(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String held = etagOf(c.port, "/access/catalog");

            assertEquals(200, send(c.port, "PUT", "/access/catalog", """
                    {"version":1,"nodes":[{"id":"n1","label":"Node 1","kind":"menu"}]}""", held)
                    .statusCode());

            HttpResponse<String> stale = send(c.port, "PUT", "/access/catalog", """
                    {"version":1,"nodes":[{"id":"n2","label":"Node 2","kind":"menu"}]}""", held);
            assertEquals(409, stale.statusCode(), stale.body());
            assertTrue(stale.body().contains("CONFLICT_STALE_VERSION"), stale.body());
        }
    }
}
