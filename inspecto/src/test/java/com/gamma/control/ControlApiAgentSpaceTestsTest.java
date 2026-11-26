package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.Roles;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-KIT-1 widening plan W6 group C over real HTTP (D-AK10, signed 2026-11-25: the Space tests for a person's agent
 * only, as a run under D-AK2): a person's agent runs the Space's cases and gets the route's own 200, the same answer its
 * person gets, and a before-and-after read of the Space shows no file and no Pending Change changed; a machine agent
 * holding the right is refused by the agent gate; a person's agent without {@code canAuthorWorkbench} gets the route's
 * own refusal, not the gate's.
 *
 * <p>The Space is {@link ControlApiSpaceTestsTest}'s: the demo's {@code orders} and {@code payments} Pipelines and their
 * two shipped cases, copied into a temp Space.
 */
class ControlApiAgentSpaceTestsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DEMO = Path.of("..", "spaces", "demo").toAbsolutePath().normalize();
    /**
     * AGENT = dev-1 through agent client claude-code; DEV = the same person, own token; BOT = a machine agent; all three
     * hold canAuthorWorkbench. READER = a person's agent without it; ADMIN reads the before-and-after state.
     */
    private static final String AGENT = "Bearer agent", DEV = "Bearer dev", BOT = "Bearer bot", READER = "Bearer reader",
            ADMIN = "Bearer admin";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path dir) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            Set<String> author = Set.of(Roles.CAN_AUTHOR_WORKBENCH);
            return Optional.ofNullable(switch (h) {
                case AGENT -> new Subject("dev-1", author, null, Map.of(), null, null, null, "claude-code");
                case DEV -> new Subject("dev-1", author);
                case BOT -> new Subject("client:ops-bot", author, null, Map.of(), null, null, "svc-ops", "ops-bot");
                case READER -> new Subject("viewer-1", Set.of(), null, Map.of(), null, null, null, "claude-code");
                case ADMIN -> new Subject("admin-1", Roles.effective(ex).get("admin").capabilities());
                default -> null;
            });
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path dir) throws Exception {
        Path space = dir.resolve("demo");
        copy(DEMO.resolve("config/orders"), space.resolve("config/orders"));
        copy(DEMO.resolve("config/payments"), space.resolve("config/payments"));
        for (String c : List.of("orders_ingest", "payments_ingest"))
            copy(DEMO.resolve("tests").resolve(c), space.resolve("tests").resolve(c));
        Path root = space.resolve("config");
        System.setProperty("assist.write.root", root.toString());
        try {
            Roles.write(root, Map.of(), List.of(), List.of("ops-bot"));   // the Space admits the machine client
            CollectorService svc = new CollectorService(List.of(
                    root.resolve("orders/orders_pipeline.toon"), root.resolve("payments/payments_pipeline.toon")), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), dir);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/default" + path))
                .header("Authorization", auth).header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> run(Ctx c, String body, String auth) throws Exception {
        return send(c, "POST", "/tests/run", body, auth);
    }

    /** Every file under the temp root (config, tests, data, registry, audit) with its size and modification time. */
    private static Map<String, String> files(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList())
                out.put(dir.relativize(p).toString(), Files.size(p) + "@" + Files.getLastModifiedTime(p).toMillis());
        }
        return out;
    }

    /** The before-and-after read: the Space's Pending Changes as an administrator reads them, then every file. */
    private String state(Ctx c) throws Exception {
        HttpResponse<String> pending = send(c, "GET", "/pending-changes", null, ADMIN);
        assertEquals(200, pending.statusCode(), pending.body());
        return JSON.readTree(pending.body()).get("data") + " | " + files(c.dir);
    }

    @Test
    void aPersonsAgentRunsTheSpaceTestsAndNothingChanges(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String before = state(c);
            HttpResponse<String> r = run(c, "{}", AGENT);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = JSON.readTree(r.body()).get("data");
            assertTrue(d.get("passed").asBoolean(), d.toString());
            assertEquals(2, d.get("total").asInt(), d.toString());
            // the answer is verdicts, never rows: each expectation is its name, expected, actual and passed
            for (JsonNode k : d.get("cases"))
                for (JsonNode e : k.get("expectations")) {
                    Set<String> keys = new TreeSet<>();
                    e.fieldNames().forEachRemaining(keys::add);
                    assertEquals(Set.of("actual", "expectation", "expected", "passed"), keys, e.toString());
                }
            HttpResponse<String> one = run(c, "{\"cases\":[\"orders_ingest\"]}", AGENT);
            assertEquals(200, one.statusCode(), one.body());
            assertEquals(1, JSON.readTree(one.body()).at("/data/total").asInt(), one.body());
            assertEquals(before, state(c), "no file, Dataset, registry entry or Pending Change changed");

            HttpResponse<String> person = run(c, "{}", DEV);
            assertEquals(200, person.statusCode(), person.body());
            assertEquals(d, JSON.readTree(person.body()).get("data"), "the agent gets what its person gets");
        }
    }

    @Test
    void aMachineAgentIsRefusedTheSpaceTestsEvenHoldingTheRight(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String before = state(c);
            HttpResponse<String> r = run(c, "{}", BOT);
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("may not run a Pipeline or the Space tests"), r.body());
            assertEquals(before, state(c));
        }
    }

    @Test
    void aPersonsAgentWithoutCanAuthorWorkbenchGetsTheRoutesOwnRefusal(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = run(c, "{}", READER);
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains(Roles.CAN_AUTHOR_WORKBENCH), "the route's capability gate: " + r.body());
            assertFalse(r.body().contains("agent client"), "not the agent gate's refusal: " + r.body());
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }
}
