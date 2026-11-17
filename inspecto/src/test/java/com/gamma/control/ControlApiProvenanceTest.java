package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobType;
import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.pipeline.exec.ProvenanceRow;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data-plane provenance query routes (T22) over real HTTP: param validation and the default-off gate.
 * (The stored counts themselves are exercised by {@code FlowJobRunnerTest} against a real {@code DbProvenanceStore};
 * here we only confirm the routes are wired, validated, and 404 when no provenance backend is configured.)
 */
class ControlApiProvenanceTest {

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

    @Test
    void validatesParamsAndGatesOnTheBackend(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // missing required params → 400 (checked before the store is consulted)
            HttpResponse<String> missing = get(c.port, "/provenance");
            assertEquals(400, missing.statusCode());
            assertTrue(missing.body().contains("'pipeline'") && !missing.body().contains("'flow'"),
                    "the 400 names the canonical param, not the pre-rename one: " + missing.body());
            assertEquals(400, get(c.port, "/provenance?flow=f").statusCode());
            assertEquals(400, get(c.port, "/provenance/batches").statusCode());

            // params present but no provenance backend configured → 404 (default-off)
            assertEquals(404, get(c.port, "/provenance?flow=f&batch=b").statusCode());
            assertEquals(404, get(c.port, "/provenance/batches?flow=f").statusCode());

            // Tier 3 dual-read (vocabulary plan §4): the canonical `pipeline=` param works the same way
            assertEquals(404, get(c.port, "/provenance?pipeline=f&batch=b").statusCode());
            assertEquals(404, get(c.port, "/provenance/batches?pipeline=f").statusCode());

            // D-12 (MoSCoW S4): `consignment=` is the canonical run param; `batch=` (above) its deprecated alias
            assertTrue(missing.body().contains("'consignment'") && !missing.body().contains("'batch'"),
                    "the 400 names the canonical run param: " + missing.body());
            assertEquals(400, get(c.port, "/provenance?pipeline=f").statusCode());
            assertEquals(404, get(c.port, "/provenance?pipeline=f&consignment=b").statusCode());
        }
    }

    /**
     * X5-a read alias: a provenance row written before 4.0.0 under a bare reject spelling ({@code dropped}) answers
     * {@code GET /provenance} as {@code reject:dropped}, the spelling the graph's edges now carry — so the Sankey
     * still lands its count on the filter's reject edge. The stored row is not rewritten.
     */
    @Test
    void aPre400RejectRowAnswersUnderItsCanonicalSpelling(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("provenance.duckdb").toString().replace('\\', '/');
        try (DbProvenanceStore seed = DbProvenanceStore.open(url)) {
            seed.record(List.of(
                    new ProvenanceRow("p", "c-1", "flt", "data", 2, "2026-08-01T10:00:00Z"),
                    new ProvenanceRow("p", "c-1", "flt", "dropped", 1, "2026-08-01T10:00:00Z")));
        }
        List<String> touched = List.of("provenance.backend", "provenance.db.url", "jobs.audit.dir");
        Map<String, String> before = new HashMap<>();
        touched.forEach(k -> before.put(k, System.getProperty(k)));
        System.setProperty("provenance.backend", "duckdb");
        System.setProperty("provenance.db.url", url);
        System.setProperty("jobs.audit.dir", dir.resolve("jobs_audit").toString());
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        CollectorService svc = new CollectorService(List.of(toon), List.of(), List.of(hb), 3600, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        try (Ctx c = new Ctx(svc, api, startAndPort(api))) {
            HttpResponse<String> r = get(c.port, "/provenance?pipeline=p&consignment=c-1");
            assertEquals(200, r.statusCode(), r.body());
            List<String> rels = new ArrayList<>();
            for (JsonNode row : V1Body.envelope(r.body()).get("data")) rels.add(row.get("rel").asText());
            assertTrue(rels.contains("reject:dropped"), "the legacy row reads canonical: " + rels);
            assertFalse(rels.contains("dropped"), "no row answers under the retired spelling: " + rels);
            assertTrue(rels.contains("data"), "a non-legacy rel passes through: " + rels);
        } finally {
            touched.forEach(k -> {
                String v = before.get(k);
                if (v == null) System.clearProperty(k); else System.setProperty(k, v);
            });
        }
    }

    private static int startAndPort(ControlApi api) {
        api.start();
        return api.port();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method("GET", BodyPublishers.noBody()).build();
        return client.send(req, BodyHandlers.ofString());
    }
}
