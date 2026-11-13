package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceLock;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-1b (D-SE12) over real HTTP: the activation holds, end to end — the Space lock reports, {@link ActivationHolds}
 * decides, the Job scheduler skips a held Job's cron fires and the Pipeline scheduler a held Pipeline
 * ({@code POST /trigger} counts the Pipelines a cycle selected), every other object runs, and {@code GET /integrity},
 * {@code GET /pipelines} and {@code GET /jobs} say why.
 *
 * <p>Hold 1 is shown on a Job: no Pipeline-held link is judged DANGLING by the link census today (every one is
 * UNCHECKED), so a Pipeline cannot carry one; hold 2 is shown on a Pipeline. Each negative (no opt-in, Basic, the kill
 * switch) is falsified by the same files being held where the opt-in applies.
 */
class ControlApiActivationHoldsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer admin} authors, operates runs and administers. */
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        return Optional.of(new Subject(a.substring(7),
                Set.of("canAuthorWorkbench", "canAdminister", "canOperateRuns", "canApproveChanges")));
    };

    @BeforeEach
    void isolate() {
        DiscoveredRoots.clear();
        ActivationHolds.resetForTest();
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty(ActivationHolds.PROPERTY);
        DiscoveredRoots.clear();
        MetricRegistry.global().reset();
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    private static void pipeline(Path config, Path data, String name, String collector) throws Exception {
        String d = data.resolve(name).toAbsolutePath().toString().replace('\\', '/');
        String schema = config.resolve("mini.toon").toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(config.resolve(name + "_pipeline.toon"), """
                name: %1$s
                active: true
                dirs:
                  poll: %2$s/inbox
                  database: %2$s/db
                  backup: %2$s/backup
                  temp: %2$s/temp
                  errors: %2$s/errors
                  quarantine: %2$s/quarantine
                  markers: %2$s/markers
                  status_dir: %2$s/status
                  log_dir: %2$s/logs
                output:
                  format: CSV
                %3$sprocessing:
                  threads: 1
                  file_pattern: "glob:**/*"
                  schema_file: "%4$s"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                """.formatted(name, d, collector, schema));
        Files.createDirectories(data.resolve(name).resolve("inbox"));
    }

    /** One Space {@code id} with the two Pipelines; {@code approval} is written when set. */
    private static void pipelineSpace(Path root, String id, String approval) throws Exception {
        Path config = Files.createDirectories(root.resolve(id + "/config"));
        Path data = Files.createDirectories(root.resolve(id + "/data"));
        Files.writeString(config.resolve("mini.toon"), PipelineConfigBatchTest.miniSchema());
        pipeline(config, data, "p_ok", "");
        pipeline(config, data, "p_two", "");
        if (approval != null) Files.writeString(config.resolve("approval.toon"), approval);
    }

    /** A noop Job firing every second; {@code onPipeline} adds an {@code on_pipeline} link when set. */
    private static String job(String name, String onPipeline) {
        return "job:\n  name: " + name + "\n  type: maintenance\n  task: noop\n  cron: \"* * * * * *\"\n"
                + (onPipeline == null ? "" : "  on_pipeline: " + onPipeline + "\n");
    }

    /** One Space {@code id} with {@code j_ok} and {@code j_held}, whose {@code on_pipeline} names no Pipeline. */
    private static void jobSpace(Path root, String id, String policy) throws Exception {
        Path jobs = Files.createDirectories(root.resolve(id + "/config/jobs"));
        Files.createDirectories(root.resolve(id + "/data"));
        Files.writeString(jobs.resolve("j_ok_job.toon"), job("j_ok", null));
        Files.writeString(jobs.resolve("j_held_job.toon"), job("j_held", "gone"));
        if (policy != null) Files.writeString(root.resolve(id + "/config/safety-policy.toon"), policy);
    }

    /** {@code start}: start the Spaces, so the Job crons fire (the Pipeline tests drive cycles by hand instead). */
    private Ctx open(Path root, boolean signIn, boolean start) throws Exception {
        if (signIn) Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        if (start) spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        b.header("Authorization", "Bearer admin");
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode awaitReport(Ctx c, String space, Predicate<JsonNode> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> r = send(c, "GET", "/spaces/" + space + "/integrity", null);
            assertEquals(200, r.statusCode(), r.body());
            last = V1Body.of(r.body());
            if ("ready".equals(last.get("status").asText()) && !last.has("recomputing") && ok.test(last)) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("no matching report in time: " + last);
    }

    /** How many Pipelines one cycle of {@code space} selected and ran. */
    private int cycle(Ctx c, String space) throws Exception {
        HttpResponse<String> r = send(c, "POST", "/spaces/" + space + "/trigger", null);
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body()).get("total").asInt();
    }

    private static List<String> held(JsonNode report) {
        List<String> out = new ArrayList<>();
        for (JsonNode h : report.get("held")) out.add(h.get("kind").asText() + ":" + h.get("name").asText() + ":" + h.get("hold").asText());
        out.sort(null);
        return out;
    }

    private JsonNode row(Ctx c, String space, String list, String name) throws Exception {
        HttpResponse<String> r = send(c, "GET", "/spaces/" + space + "/" + list, null);
        assertEquals(200, r.statusCode(), r.body());
        for (JsonNode n : V1Body.of(r.body())) if (name.equals(n.get("name").asText())) return n;
        throw new AssertionError("no " + name + " in " + r.body());
    }

    // ── hold 1: links: refuse-activation ────────────────────────────────────────────────────────

    /** The first run of {@code job} matching {@code ok}, newest first, polled until it appears. */
    private JsonNode awaitRun(Ctx c, String space, String job, Predicate<JsonNode> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> r = send(c, "GET", "/spaces/" + space + "/jobs/" + job + "/runs", null);
            assertEquals(200, r.statusCode(), r.body());
            last = r.body();
            for (JsonNode run : V1Body.of(r.body())) if (ok.test(run)) return run;
            Thread.sleep(200);
        }
        throw new AssertionError("no matching run of " + job + " in time: " + last);
    }

    private static boolean scheduled(JsonNode run, String status) {
        return "schedule".equals(run.get("trigger").asText()) && status.equals(run.get("status").asText());
    }

    /**
     * A Job whose {@code on_pipeline} names no Pipeline (DANGLING) is held: its cron fires are recorded SKIPPED with
     * the reason while the other Job runs; the same files in a Space without the opt-in run both; a server write
     * that fixes the link releases it with no restart.
     */
    @Test
    void aDanglingLinkHoldsThatJobOnlyAndAFixReleasesItWithoutARestart(@TempDir Path root) throws Exception {
        jobSpace(root, "alpha", "links: refuse-activation\n");
        jobSpace(root, "beta", null);
        try (Ctx c = open(root, true, true)) {
            JsonNode a = awaitReport(c, "alpha", n -> true);
            assertEquals(List.of("job:j_held:links"), held(a), a.toString());
            assertTrue(a.get("activationHolds").get("links").asBoolean(), a.toString());
            JsonNode hold = a.get("held").get(0);
            assertEquals("on_pipeline", hold.get("links").get(0).get("key").asText(), hold.toString());
            assertTrue(hold.get("reason").asText().contains("on_pipeline -> gone"), hold.toString());

            // a fire before the first report is not held (D-SE1) and may skip as "previous run still in flight"
            JsonNode skipped = awaitRun(c, "alpha", "j_held",
                    r -> scheduled(r, "SKIPPED") && r.get("message").asText().startsWith("held: "));
            assertTrue(skipped.get("message").asText().startsWith("held: this Space sets links: refuse-activation"),
                    skipped.toString());
            awaitRun(c, "alpha", "j_ok", r -> scheduled(r, "SUCCESS"));
            assertTrue(row(c, "alpha", "jobs", "j_held").get("held").asText().contains("gone"));
            assertFalse(row(c, "alpha", "jobs", "j_ok").has("held"));

            // falsifies the hold: the same files without the opt-in run the Job and hold nothing
            JsonNode b = awaitReport(c, "beta", n -> true);
            assertEquals(List.of(), held(b), b.toString());
            awaitRun(c, "beta", "j_held", r -> scheduled(r, "SUCCESS"));
            assertFalse(row(c, "beta", "jobs", "j_held").has("held"));

            // the fix is a server write: the lock is recomputed and the next fire runs, no restart
            HttpResponse<String> fix = send(c, "PUT", "/spaces/alpha/jobs/j_held",
                    "{\"name\":\"j_held\",\"type\":\"maintenance\",\"task\":\"noop\",\"cron\":\"* * * * * *\"}");
            assertEquals(200, fix.statusCode(), fix.body());
            awaitReport(c, "alpha", n -> held(n).isEmpty());
            awaitRun(c, "alpha", "j_held", r -> scheduled(r, "SUCCESS"));
            assertFalse(row(c, "alpha", "jobs", "j_held").has("held"));
        }
    }

    @Test
    void onBasicNothingIsHeldAndTheReportSaysWhy(@TempDir Path root) throws Exception {
        jobSpace(root, "alpha", "links: refuse-activation\n");
        try (Ctx c = open(root, false, true)) {
            JsonNode a = awaitReport(c, "alpha", n -> true);
            assertEquals(List.of(), held(a), a.toString());
            assertTrue(a.get("activationHolds").get("note").asText().contains("Standard or Advanced"), a.toString());
            awaitRun(c, "alpha", "j_held", r -> scheduled(r, "SUCCESS"));   // D-LC5: no hold on Basic
            assertFalse(row(c, "alpha", "jobs", "j_held").has("held"));
        }
    }

    @Test
    void theKillSwitchHoldsNothing(@TempDir Path root) throws Exception {
        System.setProperty(ActivationHolds.PROPERTY, "off");
        jobSpace(root, "alpha", "links: refuse-activation\n");
        try (Ctx c = open(root, true, true)) {
            JsonNode a = awaitReport(c, "alpha", n -> true);
            assertEquals("disabled", a.get("activationHolds").get("status").asText(), a.toString());
            assertFalse(a.has("held"), a.toString());
            awaitRun(c, "alpha", "j_held", r -> scheduled(r, "SUCCESS"));   // -Dspace.activation.holds=off
            assertFalse(row(c, "alpha", "jobs", "j_held").has("held"));
        }
    }

    // ── hold 2: changed outside, in a Space whose approval policy governs the kind ───────────────

    @Test
    void aGovernedPipelineEditedOnDiskIsHeldUntilAccepted(@TempDir Path root) throws Exception {
        String approval = "approval:\n  pipeline:\n    required: true\n";
        pipelineSpace(root, "gamma", approval);
        pipelineSpace(root, "delta", null);
        pipelineSpace(root, "epsilon", "approval:\n  job:\n    required: true\n");   // governs another kind only
        try (Ctx c = open(root, true, false)) {
            JsonNode first = awaitReport(c, "gamma", n -> true);
            assertEquals(List.of(), held(first), "no baseline yet: nothing is changed outside " + first);
            assertEquals(List.of("pipeline"), mapper(first.get("activationHolds").get("changedOutside")));
            awaitReport(c, "delta", n -> true);
            awaitReport(c, "epsilon", n -> true);

            for (String space : List.of("gamma", "delta", "epsilon")) {
                Path p = root.resolve(space + "/config/p_ok_pipeline.toon");
                Files.writeString(p, Files.readString(p) + "description: edited by hand\n");
                Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
                SpaceLock.refresh(root.resolve(space + "/config"));
            }
            JsonNode g = awaitReport(c, "gamma", n -> n.get("changedOutside").get("total").asInt() == 1);
            assertEquals(List.of("pipeline:p_ok:changed-outside"), held(g), g.toString());
            assertEquals("p_ok_pipeline.toon", g.get("held").get(0).get("path").asText());
            assertEquals(1, cycle(c, "gamma"), "the hand-edited Pipeline is held, the other runs");
            assertTrue(row(c, "gamma", "pipelines", "p_ok").get("held").asText().contains("integrity/accept"));
            HttpResponse<String> refused = push(c, "gamma", "p_ok");
            assertEquals(409, refused.statusCode(), "a held Pipeline takes no push (its inbox would strand): " + refused.body());
            assertTrue(refused.body().contains("pipeline held: 'p_ok'") && refused.body().contains("integrity/accept"),
                    refused.body());

            // falsifies the hold: the same hand edit in a Space with no approval policy is reported and runs
            JsonNode d = awaitReport(c, "delta", n -> n.get("changedOutside").get("total").asInt() == 1);
            assertEquals(List.of(), held(d), d.toString());
            assertEquals(2, cycle(c, "delta"), "D-LC4: without a policy the edit takes effect");
            JsonNode e = awaitReport(c, "epsilon", n -> n.get("changedOutside").get("total").asInt() == 1);
            assertEquals(List.of(), held(e), "a policy governing job only does not hold a Pipeline: " + e);
            assertEquals(List.of("job"), mapper(e.get("activationHolds").get("changedOutside")));
            assertEquals(2, cycle(c, "epsilon"), "a policy for another kind leaves the edited Pipeline running");

            HttpResponse<String> accept = send(c, "POST", "/spaces/gamma/integrity/accept",
                    "{\"paths\":[\"p_ok_pipeline.toon\"]}");
            assertEquals(200, accept.statusCode(), accept.body());
            assertEquals(2, cycle(c, "gamma"), "accepted: released at once, no restart");
            assertFalse(row(c, "gamma", "pipelines", "p_ok").has("held"));
            HttpResponse<String> pushed = push(c, "gamma", "p_ok");
            assertEquals(201, pushed.statusCode(), "accepted: the push lands again " + pushed.body());
        }
    }

    /** A push of two CSV rows to the Stream of {@code pipeline} in {@code space}. */
    private HttpResponse<String> push(Ctx c, String space, String pipeline) throws Exception {
        String stream = null;
        for (java.util.Map<String, Object> m : c.spaces().space(com.gamma.service.SpaceId.of(space)).orElseThrow()
                .service().collectors())
            if (pipeline.equals(m.get("pipeline"))) stream = String.valueOf(m.get("id"));
        assertNotNull(stream, "no Stream for " + pipeline);
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/" + space
                        + "/streams/" + stream + "/records"))
                .header("Content-Type", "text/csv").header("Authorization", "Bearer admin")
                .POST(HttpRequest.BodyPublishers.ofString("ID,AMT,EVENT_DATE\nr1,1.5,2026-01-01\nr2,2.5,2026-01-02\n")).build();
        return client.send(r, HttpResponse.BodyHandlers.ofString());
    }

    /** Fail open at the seam: a check that throws holds nothing, so every Pipeline still runs and no row says held. */
    @Test
    void aCheckThatThrowsHoldsNothingAndEveryPipelineRuns(@TempDir Path root) throws Exception {
        pipelineSpace(root, "gamma", null);
        try (Ctx c = open(root, true, false)) {
            awaitReport(c, "gamma", n -> true);
            com.gamma.pipeline.ActivationGate.install((space, kind, name) -> { throw new IllegalStateException("boom"); });
            try {
                assertEquals(2, cycle(c, "gamma"));
                assertFalse(row(c, "gamma", "pipelines", "p_ok").has("held"));
                assertEquals(201, push(c, "gamma", "p_ok").statusCode());
            } finally {
                com.gamma.pipeline.ActivationGate.install(null);
            }
        }
    }

    private static List<String> mapper(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
