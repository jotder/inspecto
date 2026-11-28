package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SIGNAL-BOARD-1 SB-2 over real HTTP: the Signal checks of a Job save. An undeclared {@code $signal.<field>} and a chain
 * of Jobs that closes on itself are refused (422) for an agent Subject and a warning for a person (D-CO1, through
 * {@link AgentStrict}); an {@code on_signal} type nothing posts warns everyone, the agent included, and refuses no one
 * (D-SB3). A finding the stored Job already had does not refuse an agent's edit.
 */
class ControlApiJobSignalChecksTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = author-1 (pipeline-developer + canOperateRuns) through agent client claude-code; AUTHOR = the same person, own token. */
    private static final String AGENT = "Bearer agent", AUTHOR = "Bearer author";
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    static Path auditDir;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        System.setProperty("jobs.audit.dir", auditDir.resolve("jobs_audit").toString());
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (!AGENT.equals(h) && !AUTHOR.equals(h)) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get("pipeline-developer");
            ComponentAccess.heldRoles(ex, Set.of("pipeline-developer"));
            String agent = AGENT.equals(h) ? "claude-code" : null;
            Set<String> caps = new java.util.HashSet<>(def.capabilities());
            caps.add(Roles.CAN_OPERATE_RUNS);   // POST /jobs/{name}/enable is an operation
            return Optional.of(new Subject("author-1", caps, def.dataScopes(), Map.of(), null, null, null, agent));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        Path root = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        return client.send(b.method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Ctx c, String body, String auth) throws Exception {
        return send(c, "POST", "/jobs", body, auth);
    }

    /** The {@code code} of every warning in a response body (a saved Job, or a held change). */
    private static List<String> warningCodes(String body) throws Exception {
        JsonNode n = JSON.readTree(body);
        JsonNode w = n.has("data") && n.get("data").has("warnings") ? n.get("data").get("warnings") : n.get("warnings");
        List<String> out = new ArrayList<>();
        if (w != null) w.forEach(x -> out.add(x.get("code").asText()));
        return out;
    }

    private static boolean saved(Ctx c, String name) {
        return Files.isRegularFile(c.root.resolve("jobs").resolve(name + "_job.toon"));
    }

    private static final String FIELD = """
            {"name":"%s","type":"sample.hello","on_signal":"sample.hello.completed","when":"$signal.nope == 1"}""";
    /** ping: a sample.hello Job fired by any Run's completion; pong: one fired by sample.hello.completed. */
    private static final String PING = """
            {"name":"ping","type":"sample.hello","on_signal":"job.run.completed"}""";
    private static final String PONG = """
            {"name":"pong","type":"sample.hello","on_signal":"sample.hello.completed"}""";

    @Test
    void anUndeclaredSignalFieldIsRefusedForAnAgentAndAWarningForAPerson(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> agent = post(c, FIELD.formatted("f_agent"), AGENT);
            assertEquals(422, agent.statusCode(), agent.body());
            assertTrue(agent.body().contains("SIGNAL_FIELD_UNDECLARED") && agent.body().contains("$signal.nope"), agent.body());
            assertFalse(saved(c, "f_agent"));

            HttpResponse<String> person = post(c, FIELD.formatted("f_person"), AUTHOR);
            assertEquals(200, person.statusCode(), person.body());
            assertEquals(List.of("SIGNAL_FIELD_UNDECLARED"), warningCodes(person.body()));
            assertTrue(saved(c, "f_person"));
        }
    }

    @Test
    void aChainThatClosesOnItselfIsRefusedForAnAgentAndAWarningForAPerson(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> first = post(c, PONG, AUTHOR);
            assertEquals(200, first.statusCode(), first.body());
            assertEquals(List.of(), warningCodes(first.body()), "pong alone closes nothing");

            HttpResponse<String> agent = post(c, PING, AGENT);
            assertEquals(422, agent.statusCode(), agent.body());
            assertTrue(agent.body().contains("JOB_CHAIN_LOOP") && agent.body().contains("ping → pong → ping"), agent.body());
            assertFalse(saved(c, "ping"));

            HttpResponse<String> person = post(c, PING, AUTHOR);
            assertEquals(200, person.statusCode(), person.body());
            assertEquals(List.of("JOB_CHAIN_LOOP"), warningCodes(person.body()));
            assertTrue(saved(c, "ping"));
        }
    }

    @Test
    void enablingAJobThatClosesALoopIsRefusedForAnAgent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, post(c, PONG, AUTHOR).statusCode());
            HttpResponse<String> off = post(c, PING.replace("}", ",\"enabled\":\"false\"}"), AUTHOR);
            assertEquals(200, off.statusCode(), off.body());
            assertEquals(List.of(), warningCodes(off.body()), "a disabled Job joins no chain");

            HttpResponse<String> agent = send(c, "POST", "/jobs/ping/enable", "{}", AGENT);
            assertEquals(422, agent.statusCode(), agent.body());
            assertTrue(agent.body().contains("JOB_CHAIN_LOOP"), agent.body());
        }
    }

    @Test
    void aTypeNothingPostsWarnsAnAgentAndAPersonAndRefusesNeither(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String body = """
                    {"name":"%s","type":"sample.hello","on_signal":"nobody.posts.this"}""";
            HttpResponse<String> agent = post(c, body.formatted("u_agent"), AGENT);
            assertEquals(202, agent.statusCode(), "an agent's write is held, not refused: " + agent.body());
            assertEquals(List.of("SIGNAL_TYPE_UNPOSTED"), warningCodes(agent.body()), agent.body());

            HttpResponse<String> person = post(c, body.formatted("u_person"), AUTHOR);
            assertEquals(200, person.statusCode(), person.body());
            assertEquals(List.of("SIGNAL_TYPE_UNPOSTED"), warningCodes(person.body()));
            assertTrue(saved(c, "u_person"));
        }
    }

    @Test
    void aFindingTheStoredJobAlreadyHadDoesNotRefuseAnAgentsEdit(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, post(c, FIELD.formatted("legacy"), AUTHOR).statusCode());
            HttpResponse<String> edit = send(c, "PUT", "/jobs/legacy",
                    FIELD.formatted("legacy").replace("}", ",\"greeting\":\"Hi\"}"), AGENT);
            assertEquals(202, edit.statusCode(), edit.body());
            assertEquals(List.of("SIGNAL_FIELD_UNDECLARED"), warningCodes(edit.body()), "still named, not refused");

            HttpResponse<String> worse = send(c, "PUT", "/jobs/legacy",
                    FIELD.formatted("legacy").replace("$signal.nope == 1", "$signal.nope == 1 && $signal.worse == 2"), AGENT);
            assertEquals(422, worse.statusCode(), "a new undeclared field is refused: " + worse.body());
            assertTrue(worse.body().contains("$signal.worse") && !worse.body().contains("reads $signal.nope"), worse.body());
        }
    }
}
