package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG-1 Open 2 Stage 1 over real HTTP: {@code GET /system/operational-db} reports what the deployment is
 * actually using (and where each value came from), and {@code POST /system/operational-db/test} refuses
 * what it will not dial before it dials anything.
 *
 * <p>⚠ Verified over HTTP deliberately: the report's whole purpose is to be trustworthy about live
 * process state, and the per-family precedence it reports is a property of `-D` resolution that a unit
 * test on a hand-built fixture would not exercise.
 */
class ControlApiSystemRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** Every property these tests set, cleared after each so one test cannot colour the next. */
    private static final List<String> TOUCHED = List.of(
            "inspecto.db", "inspecto.db.url", "inspecto.db.user", "jobs.backend", "jobs.db.url",
            "objects.backend", "objects.db.url", "status.backend");

    @AfterEach
    void clearProperties() {
        TOUCHED.forEach(System::clearProperty);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        CollectorService svc = new CollectorService(
                List.of(TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write()), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    // ── GET /system/operational-db ──────────────────────────────────────────────

    /** The default deployment: DuckDB, every family reporting the SOURCE of its URL, not just a value. */
    @Test
    void theReportNamesTheEngineAndEveryFamilysSource(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode body = json(get(c.port, "/system/operational-db"));
            assertEquals("duckdb", body.get("engine").asText());
            JsonNode families = body.get("families");
            assertEquals(11, families.size(), "the roster is eleven families — " + families);
            for (JsonNode f : families) {
                assertNotNull(f.get("source"), "every family reports where its value came from");
                assertTrue(f.has("backendProperty") && f.has("urlProperty"),
                        "and the properties to set, so the operator can act on it: " + f);
            }
        }
    }

    /**
     * The precedence the report exists to make visible: a per-family URL beats the shared one, and a
     * family with neither falls to the space default. ⚠ Asserting the SOURCE, not just the URL — the
     * two can agree by accident, and it is the source that tells the operator which flag is in charge.
     */
    @Test
    void aPerFamilyUrlOutranksTheSharedOneAndBothOutrankTheSpaceDefault(@TempDir Path dir) throws Exception {
        System.setProperty("jobs.backend", "duckdb");
        System.setProperty("objects.backend", "db");
        System.setProperty("inspecto.db", "postgres");
        System.setProperty("inspecto.db.url", "jdbc:postgresql://shared:5432/ops");
        System.setProperty("jobs.db.url", "jdbc:postgresql://jobs-only:5432/jobs");
        try (Ctx c = open(dir)) {
            JsonNode body = json(get(c.port, "/system/operational-db"));
            assertEquals("postgres", body.get("engine").asText());
            JsonNode jobs = family(body, "JOB_RUNS");
            assertEquals("FAMILY_PROPERTY", jobs.get("source").asText());
            assertTrue(jobs.get("url").asText().contains("jobs-only"), jobs.toString());
            JsonNode objects = family(body, "OBJECTS");
            assertEquals("SHARED_PROPERTY", objects.get("source").asText());
            assertTrue(objects.get("url").asText().contains("shared"), objects.toString());
        }
    }

    /** A family whose own backend leaves it off reports DISABLED with no URL — not a URL nothing opens. */
    @Test
    void aFamilyItsBackendLeavesOffIsReportedDisabled(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode body = json(get(c.port, "/system/operational-db"));
            JsonNode jobs = family(body, "JOB_RUNS");   // jobs.backend defaults to `none`
            assertEquals("DISABLED", jobs.get("source").asText());
            assertFalse(jobs.get("enabled").asBoolean());
            assertTrue(jobs.get("url").isNull(), "a disabled family has no URL: " + jobs);
        }
    }

    /**
     * ⚠ A {@code *.backend} that starts with {@code jdbc:} IS the URL and bypasses `OperationalDb`
     * entirely — a third source. A report that missed it would show the shared URL while the store used
     * something else, which is worse than showing nothing.
     */
    @Test
    void aJdbcBackendValueIsItselfTheUrl(@TempDir Path dir) throws Exception {
        System.setProperty("inspecto.db", "postgres");
        System.setProperty("inspecto.db.url", "jdbc:postgresql://shared:5432/ops");
        System.setProperty("jobs.backend", "jdbc:duckdb:from_the_backend_property.db");
        try (Ctx c = open(dir)) {
            JsonNode jobs = family(json(get(c.port, "/system/operational-db")), "JOB_RUNS");
            assertEquals("BACKEND_PROPERTY", jobs.get("source").asText());
            assertTrue(jobs.get("url").asText().contains("from_the_backend_property"), jobs.toString());
        }
    }

    /**
     * ⚠ **URL grain ≠ credential grain.** A family whose {@code userProperty} is null opens with
     * {@code open(url)} and sends <b>no credentials at all</b>, so it must report no user — even though
     * a shared {@code -Dinspecto.db.user} is set and a credentialed family does inherit it. Reporting
     * the shared user for both would name a credential {@code DbJobRunStore} never sends, and this
     * report's only value is being trustworthy about live process state.
     */
    @Test
    void aFamilyThatSendsNoCredentialsReportsNoUserEvenWhenASharedOneIsSet(@TempDir Path dir) throws Exception {
        System.setProperty("jobs.backend", "duckdb");
        System.setProperty("objects.backend", "db");
        System.setProperty("inspecto.db", "postgres");
        System.setProperty("inspecto.db.url", "jdbc:postgresql://shared:5432/ops");
        System.setProperty("inspecto.db.user", "ops_user");
        try (Ctx c = open(dir)) {
            JsonNode body = json(get(c.port, "/system/operational-db"));
            JsonNode jobs = family(body, "JOB_RUNS");
            assertTrue(jobs.get("userProperty").isNull(), "job runs has no user property: " + jobs);
            assertTrue(jobs.get("user").isNull(),
                    "…so it must not claim the shared user it never sends: " + jobs);
            JsonNode objects = family(body, "OBJECTS");
            assertEquals("ops_user", objects.get("user").asText(),
                    "while a credentialed family DOES inherit the shared user: " + objects);
        }
    }

    /**
     * ⛔ The redaction falsification. A JDBC URL may legally embed credentials, so asserting that the
     * {@code password} FIELD is absent proves nothing — this asserts the secret appears NOWHERE in the
     * whole response body.
     */
    @Test
    void aPasswordEmbeddedInAUrlIsNotEchoedAnywhereInTheBody(@TempDir Path dir) throws Exception {
        System.setProperty("objects.backend", "db");
        System.setProperty("inspecto.db", "postgres");
        System.setProperty("inspecto.db.url", "jdbc:postgresql://ops_user:sup3rs3cret@pg:5432/ops");
        try (Ctx c = open(dir)) {
            String body = get(c.port, "/system/operational-db").body();
            assertFalse(body.contains("sup3rs3cret"),
                    "a credential embedded in the URL must not survive into the report: " + body);
            assertTrue(body.contains("pg:5432/ops"), "…while the host and database still show: " + body);
        }
    }

    // ── POST /system/operational-db/test ────────────────────────────────────────

    /** The scheme gate: an admin-gated endpoint that opens connections must not dial anything asked of it. */
    @Test
    void aUrlWithAnUndialledSchemeIs422BeforeAnythingIsOpened(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = post(c.port, "/system/operational-db/test",
                    "{\"url\":\"jdbc:mysql://internal-host:3306/x\"}");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("jdbc:postgresql:"), r.body());
        }
    }

    @Test
    void aMissingUrlIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(422, post(c.port, "/system/operational-db/test", "{}").statusCode());
        }
    }

    /** ⛔ A literal password is refused — it would be a credential in transit and in every access log. */
    @Test
    void aLiteralPasswordIsRefusedInFavourOfASecretReference(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = post(c.port, "/system/operational-db/test",
                    "{\"url\":\"jdbc:duckdb:x.db\",\"user\":\"u\",\"password\":\"hunter2\"}");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("${ENV:"), r.body());
            assertFalse(r.body().contains("hunter2"), "and the refusal does not echo it back: " + r.body());
        }
    }

    /** The happy path is a REAL connection: an embedded DuckDB opens and answers SELECT 1. */
    @Test
    void aReachableDatabaseAnswersOk(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String url = "jdbc:duckdb:" + dir.resolve("probe.db").toString().replace('\\', '/');
            HttpResponse<String> r = post(c.port, "/system/operational-db/test",
                    "{\"url\":\"" + url + "\"}");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("OK", json(r).get("outcome").asText(), r.body());
        }
    }

    /** An unreachable target is a NAMED outcome, not a 500 and not a stack trace. */
    @Test
    void anUnreachableTargetIsANamedOutcomeNotAServerError(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = post(c.port, "/system/operational-db/test",
                    "{\"url\":\"jdbc:postgresql://127.0.0.1:1/nothing_here\"}");
            assertEquals(200, r.statusCode(), r.body());
            String outcome = json(r).get("outcome").asText();
            assertTrue(List.of("UNREACHABLE", "AUTH_FAILED", "DRIVER_MISSING").contains(outcome),
                    "a named outcome, got: " + r.body());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static JsonNode family(JsonNode body, String name) {
        for (JsonNode f : body.get("families")) if (name.equals(f.get("family").asText())) return f;
        throw new AssertionError("no family '" + name + "' in " + body);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(port) + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(port) + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** ⚠ Routes are served under {@code /api/v1}; without it every request 404s with a routing hint. */
    private static String base(int port) { return "http://localhost:" + port + "/api/v1"; }

    /**
     * ⚠ A 2xx {@code /api/v1} body is the ENVELOPE {@code {data, metadata, links, diagnostics}} — the
     * payload lives under {@code data}. A non-2xx uses {@code {error: {…}}} instead, which is a different
     * shape and is NOT unwrapped here, so status-code assertions read the raw body as they should.
     */
    private static JsonNode json(HttpResponse<String> r) throws Exception {
        JsonNode root = JSON.readTree(r.body());
        return root.has("data") ? root.get("data") : root;
    }
}
