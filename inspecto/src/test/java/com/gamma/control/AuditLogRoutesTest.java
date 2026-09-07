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
 * {@code /audit/*} — the audit projection that stayed in CORE when the {@code /events*} feed moved to the
 * optional {@code inspecto-events} module (EDG-01 cell 6, 2026-09-08).
 *
 * <p>🔴 <b>The refusals are the point of this class, not the reads.</b> This route only earns its place if it
 * cannot be used as the feed: were the type parameter permissive, {@code /audit/search} would serve exactly
 * what the gated {@code /events/search} serves and cell 6 would gate nothing at all. So every widening is
 * probed — a foreign type, a blank type, an absent type — and each must be a 400.
 *
 * <p>Runs in the DEFAULT (Personal) build, which is where it matters: this is the bundle where the feed is
 * absent and this route is the only audit read there is.
 */
class AuditLogRoutesTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .GET().build(), BodyHandlers.ofString());
    }

    /** The compliance read Personal keeps: EDITIONS §Audit promises it "local append-only logs". */
    @Test
    void bothAuditableTypesAreReadableOnThePersonalBuild(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // generate one ACCESS_DENIED by attempting a mutation on an unknown route
            client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/nope"))
                    .method("DELETE", BodyPublishers.noBody()).build(), BodyHandlers.ofString());

            for (String type : List.of("AUDIT", "ACCESS_DENIED")) {
                HttpResponse<String> res = get(c.port, "/audit/search?type=" + type + "&limit=50");
                assertEquals(200, res.statusCode(), type + " -> " + res.body());
                assertTrue(V1Body.of(res.body()).isArray(), "a row list, not an object: " + res.body());
            }
        }
    }

    /**
     * ⛔ The route must not be a back door onto the gated feed. A blank or absent type means "every type" on
     * the feed; here it must be refused, or this route IS the feed.
     */
    @Test
    void aTypeOutsideTheAuditProjectionIsRefused(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String query : List.of(
                    "/audit/search?type=BATCH_COMMITTED",   // a real, non-audit event type
                    "/audit/search?type=ERROR",
                    "/audit/search?type=",                  // blank = "everything" on the feed
                    "/audit/search")) {                     // absent entirely
                HttpResponse<String> res = get(c.port, query);
                assertEquals(400, res.statusCode(), query + " must be refused, got: " + res.body());
                JsonNode err = V1Body.of(res.body()).get("error");
                assertNotNull(err, query + " must carry the v1 error object: " + res.body());
            }
        }
    }

    /**
     * The auditor's evidence export (AUDIT-CSV-1 / compliance G10) keeps the audit-shaped columns — the
     * whole reason that CSV exists is that the plain projection dropped actor/action/target/ip/policy.
     */
    @Test
    void theAuditCsvCarriesTheAuditColumns(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> res = get(c.port, "/audit/export?format=csv&type=AUDIT");
            assertEquals(200, res.statusCode(), res.body());
            String header = res.body().lines().findFirst().orElse("");
            assertTrue(header.startsWith("timestamp,level,type,source,pipeline,correlationId,message"), header);
            for (String col : com.gamma.event.AuditAttrs.ALL) {
                assertTrue(header.contains(col), "audit column '" + col + "' missing from: " + header);
            }
        }
    }

    /** The export is gated by the same closed set — a CSV must not become the widening the search refuses. */
    @Test
    void theExportRefusesANonAuditableTypeToo(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(400, get(c.port, "/audit/export?format=csv&type=BATCH_COMMITTED").statusCode());
            assertEquals(400, get(c.port, "/audit/export?format=csv").statusCode());
        }
    }
}
