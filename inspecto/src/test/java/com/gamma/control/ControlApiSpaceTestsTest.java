package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-6a — {@code GET /tests} and {@code POST /tests/run} (reached as {@code /spaces/{id}/tests…}) over real HTTP
 * under an ARMED authenticator: every gate, and the shipped demo cases passing through the real route.
 *
 * <p>The demo's {@code orders} and {@code payments} Pipelines are copied into a temp Space ({@code <tmp>/demo/config})
 * so booting the service resolves their {@code data/} under the temp root, never the repo. The shipped
 * {@code spaces/demo/tests} cases are copied beside it.
 */
class ControlApiSpaceTestsTest {

    private static final Path DEMO = Path.of("..", "spaces", "demo").toAbsolutePath().normalize();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer author} holds canAuthorWorkbench; {@code Bearer viewer} holds nothing. */
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String user = a.substring(7);
        return Optional.of(new Subject(user, "author".equals(user) ? Set.of("canAuthorWorkbench") : Set.of()));
    };

    @AfterEach void disarm() { Authenticators.forTest(null); }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path space) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, boolean writeRoot) throws Exception {
        Authenticators.forTest(FAKE);
        Path space = dir.resolve("demo");
        copy(DEMO.resolve("config/orders"), space.resolve("config/orders"));
        copy(DEMO.resolve("config/payments"), space.resolve("config/payments"));
        for (String c : List.of("orders_ingest", "payments_ingest"))
            copy(DEMO.resolve("tests").resolve(c), space.resolve("tests").resolve(c));
        if (writeRoot) System.setProperty("assist.write.root", space.resolve("config").toString());
        try {
            CollectorService svc = new CollectorService(List.of(
                    space.resolve("config/orders/orders_pipeline.toon"),
                    space.resolve("config/payments/payments_pipeline.toon")), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), space);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(Ctx c, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + "/spaces/default/tests")).GET();
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> run(Ctx c, String user, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + "/spaces/default/tests/run"))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ── gates ────────────────────────────────────────────────────────────────────

    @Test
    void noCredentialIs401(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(401, run(c, null, null).statusCode());
        }
    }

    @Test
    void aSubjectWithoutCanAuthorWorkbenchIs403(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(403, run(c, "viewer", null).statusCode());
            assertEquals(200, get(c, "viewer").statusCode(), "the listing is a read and carries no capability");
        }
    }

    @Test
    void noConfigRootIs503(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            assertEquals(503, run(c, "author", null).statusCode());
            assertEquals(503, get(c, "author").statusCode());
        }
    }

    @Test
    void aBodyThatIsNotAListOfNamesIs400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(400, run(c, "author", "{\"cases\":\"orders_ingest\"}").statusCode());
            assertEquals(400, run(c, "author", "{\"cases\":[1]}").statusCode());
        }
    }

    @Test
    void aCaseNameThatIsNotAPlainNameIs403(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            for (String bad : List.of("..", "../config", "orders_ingest/../../config"))
                assertEquals(403, run(c, "author", "{\"cases\":[\"" + bad + "\"]}").statusCode(), bad);
        }
    }

    @Test
    void anUnknownCaseIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(404, run(c, "author", "{\"cases\":[\"nope\"]}").statusCode());
        }
    }

    @Test
    void aMalformedCaseIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            Path bad = Files.createDirectories(c.space.resolve("tests/escape"));
            Files.writeString(bad.resolve("case.toon"),
                    "pipeline: orders\ninputs[1]: \"../orders_ingest/ORDERS_20260701.csv\"\nexpect:\n  rows: 10\n");
            HttpResponse<String> r = run(c, "author", "{\"cases\":[\"escape\"]}");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("refused"), r.body());
        }
    }

    // ── happy path ───────────────────────────────────────────────────────────────

    @Test
    void listsTheCases(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            JsonNode cases = V1Body.of(get(c, "author").body()).get("cases");
            assertEquals(2, cases.size(), cases.toString());
            assertEquals("orders_ingest", cases.get(0).get("name").asText());
            assertEquals("orders", cases.get(0).get("pipeline").asText());
        }
    }

    @Test
    void theDemoCasesPassThroughTheRouteAndTouchNoLiveStore(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            List<String> before = tree(c.space);
            HttpResponse<String> r = run(c, "author", null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("passed").asBoolean(), out.toString());
            assertEquals(2, out.get("total").asInt());
            assertEquals(0, out.get("failed").asInt());
            assertEquals(before, tree(c.space), "a run must not write into the Space (data/, tests/, anything)");
        }
    }

    @Test
    void aFailingCaseIs200WithItsDiff(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            Files.writeString(c.space.resolve("tests/orders_ingest/case.toon"),
                    "pipeline: orders\ninputs[1]: ORDERS_20260701.csv\nexpect:\n  rows: 1\n");
            HttpResponse<String> r = run(c, "author", "{\"cases\":[\"orders_ingest\"]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertFalse(out.get("passed").asBoolean());
            JsonNode line = out.get("cases").get(0).get("expectations").get(0);
            assertEquals("rows", line.get("expectation").asText());
            assertEquals("1", line.get("expected").asText());
            assertFalse(line.get("passed").asBoolean());
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> s = Files.list(from)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) Files.copy(f, to.resolve(f.getFileName()));
        }
    }

    private static List<String> tree(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.map(p -> root.relativize(p) + "|" + (Files.isRegularFile(p) ? p.toFile().length() : -1)).sorted().toList();
        }
    }
}
