package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobService;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Job Type provenance on {@code GET /jobs/types[/{id}]} and {@code secret} masking on
 * {@code GET /jobs/{name}} (job-parameter-contract §7.2/§7.3, step 9), over real HTTP.
 */
class ControlApiJobProvenanceTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        svc.jobServiceOrCreate();
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void everyTypeReportsWhereItCameFrom(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode one = json(get(c.port, "/jobs/types/sql.template"));

            assertEquals("builtin", one.get("source").asText());
            assertEquals("", one.get("version").asText(), "only a pack type carries a version");
            assertFalse(one.get("implClass").asText().isBlank());
            assertFalse(one.get("implClass").asText().contains("$$Lambda"),
                    "the lambda's synthetic name is stripped — it is noise, not provenance");
            assertEquals("sql.template", one.get("id").asText(), "the descriptor's own fields still travel");

            for (JsonNode t : json(get(c.port, "/jobs/types")))
                assertEquals("builtin", t.get("source").asText(),
                        t.get("id").asText() + " is registered by the engine in this build");
        }
    }

    @Test
    void typeViewCarriesItsDeclaredServiceGrants(@TempDir Path dir) throws Exception {
        // platform-services plan S1-2: the operator sees a type's reach before arming anything.
        try (Ctx c = open(dir)) {
            JsonNode one = json(get(c.port, "/jobs/types/sql.template"));
            assertTrue(one.get("requires").isArray(), "requires: is part of the served contract");
            assertTrue(one.get("requires").isEmpty(), "sql.template needs no Platform Service");

            // S1-7: sample.hello is the migrated reference — it reaches the feed only through its grant.
            // Its presence here also proves the real boot registry satisfied that grant: registration
            // fails closed, so an unsatisfiable declaration would have failed the service's construction.
            JsonNode hello = json(get(c.port, "/jobs/types/sample.hello"));
            assertEquals("notifications", hello.get("requires").get(0).asText());

            for (JsonNode t : json(get(c.port, "/jobs/types")))
                assertTrue(t.has("requires"), t.get("id").asText() + " serves its grants row");
        }
    }

    @Test
    void unknownTypeStill404s(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, get(c.port, "/jobs/types/no.such.type").statusCode());
        }
    }

    @Test
    void anOrdinaryJobsDetailReadIsUnchangedByTheContract(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JobService jobs = c.svc.jobService().orElseThrow();
            JobConfig cfg = JobConfig.fromMap(Map.of("job", Map.of(
                    "name", "nightly", "type", "maintenance", "task", "cleanup", "dir", "/tmp/x")));
            jobs.upsertJob(cfg);

            // No built-in declares a secret today, so the masking pass must be a no-op end to end.
            // The detail body is the FLAT job section — params sit beside name/type, not under a "job" key.
            assertTrue(jobs.secretParams(cfg).isEmpty(), "maintenance declares no secret parameter");
            JsonNode detail = json(get(c.port, "/jobs/nightly"));
            assertEquals("nightly", detail.get("name").asText());
            assertEquals("cleanup", detail.get("task").asText());
            assertEquals("/tmp/x", detail.get("dir").asText());
        }
    }

    @Test
    void maskingHidesLiteralSecretsButKeepsEnvReferencesVisible() {
        // The rule itself, exercised without inventing a built-in that declares a secret. Mirrors
        // ConnectionProfile: a literal is ***, a ${ENV:…} reference stays readable, because the reference
        // is not sensitive and hiding it would leave an operator unable to see how the secret is wired.
        // JobConfig.toMap() is flat: params are flattened in beside name/type/enabled.
        Map<String, Object> view = new java.util.LinkedHashMap<>(Map.of(
                "name", "mailer", "token", "hunter2", "fallback_token", "${ENV:MAIL_TOKEN}", "to", "ops@x.com"));

        Map<String, Object> job = JobRoutes.maskSecrets(view, Set.of("token", "fallback_token"));

        assertEquals("***", job.get("token"));
        assertEquals("${ENV:MAIL_TOKEN}", job.get("fallback_token"));
        assertEquals("ops@x.com", job.get("to"), "a non-secret parameter is untouched");
        assertEquals("mailer", job.get("name"));
    }

    @Test
    void maskingNeverTouchesTheExportPath() {
        // §7.2: JobConfig.toMap() also feeds bundle export/import, so masking must live at the response
        // boundary. Same map, no declared secrets ⇒ returned as-is.
        Map<String, Object> view = Map.of("name", "mailer", "token", "hunter2");
        assertSame(view, JobRoutes.maskSecrets(view, Set.of()));
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.envelope(r.body()).get("data");
    }
}
