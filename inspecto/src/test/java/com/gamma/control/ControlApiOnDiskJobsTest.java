package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.event.EventLog;
import com.gamma.metrics.MetricRegistry;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SB-2c of {@code SIGNAL-BOARD-1} over real HTTP: a Job file added, changed, renamed or removed on disk takes effect in
 * the running Space with no restart, through the API save path ({@link OnDiskJobs}); a file that fails a save check
 * (a half-written one) is not applied, the running Job stays as it was, and its {@code CONFIG_CHANGED_OUTSIDE} event
 * says why. The refusal is falsified by the valid edits of the same files being applied.
 */
class ControlApiOnDiskJobsTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Event> outside = new CopyOnWriteArrayList<>();
    private final BiConsumer<EventLog, Event> tap = (log, e) -> {
        if (EventType.CONFIG_CHANGED_OUTSIDE.equals(e.type())) outside.add(e);
    };

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        return Optional.of(new Subject(a.substring(7), Set.of("canAuthorWorkbench", "canAdminister", "canOperateRuns")));
    };

    @BeforeEach
    void arm() {
        DiscoveredRoots.clear();
        System.setProperty(OnDiskJobs.POLL_PROPERTY, "1");
        EventLog.addTap(tap);
    }

    @AfterEach
    void disarm() {
        EventLog.removeTap(tap);
        System.clearProperty(OnDiskJobs.POLL_PROPERTY);
        Authenticators.forTest(null);
        DiscoveredRoots.clear();
        MetricRegistry.global().reset();
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path jobs) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    private static String job(String name, String extra) {
        return "job:\n  name: " + name + "\n  type: maintenance\n  task: noop\n" + extra;
    }

    /** Space {@code alpha} with Job {@code j_a} (manual, enabled), started, its first lock ready. */
    private Ctx open(Path root) throws Exception {
        Path jobs = Files.createDirectories(root.resolve("alpha/config/jobs"));
        Files.createDirectories(root.resolve("alpha/data"));
        Files.writeString(jobs.resolve("j_a_job.toon"), job("j_a", ""));
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        Ctx c = new Ctx(spaces, api, api.port(), jobs);
        awaitReady(c);
        return c;
    }

    private HttpResponse<String> send(Ctx c, String method, String path) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer admin").method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(r, HttpResponse.BodyHandlers.ofString());
    }

    /** The first lock is the baseline: a change is "outside" only against it. */
    private void awaitReady(Ctx c) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> r = send(c, "GET", "/spaces/alpha/integrity");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode n = V1Body.of(r.body());
            if ("ready".equals(n.get("status").asText()) && !n.has("recomputing")) return;
            Thread.sleep(100);
        }
        throw new AssertionError("no lock in time");
    }

    /** The {@code /jobs} row of {@code name}, or null. */
    private JsonNode row(Ctx c, String name) throws Exception {
        HttpResponse<String> r = send(c, "GET", "/spaces/alpha/jobs");
        assertEquals(200, r.statusCode(), r.body());
        for (JsonNode n : V1Body.of(r.body())) if (name.equals(n.get("name").asText())) return n;
        return null;
    }

    /** Waits until the Job list satisfies {@code ok}, with no request that writes and no restart. */
    private void awaitJobs(Ctx c, String what, Predicate<Ctx> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (ok.test(c)) return;
            Thread.sleep(200);
        }
        throw new AssertionError("not applied in time: " + what + "; events " + outside);
    }

    private Event awaitEvent(String path, Predicate<String> applied) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (Event e : outside)
                if (path.equals(e.attributes().get("path")) && e.attributes().get("applied") != null
                        && applied.test(e.attributes().get("applied"))) return e;
            Thread.sleep(200);
        }
        throw new AssertionError("no CONFIG_CHANGED_OUTSIDE for " + path + " in time: " + outside);
    }

    private boolean enabled(Ctx c, String name) {
        try {
            JsonNode n = row(c, name);
            return n != null && n.get("enabled").asBoolean();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private boolean present(Ctx c, String name) {
        try {
            return row(c, name) != null;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aValidEditAnAddedFileAndARenameApplyWithNoRestart(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertTrue(enabled(c, "j_a"));

            Files.writeString(c.jobs.resolve("j_a_job.toon"), job("j_a", "  enabled: false\n"));
            awaitJobs(c, "j_a disabled", x -> !enabled(x, "j_a"));
            assertTrue(awaitEvent("jobs/j_a_job.toon", a -> true).attributes().get("applied").startsWith("applied"));

            Files.writeString(c.jobs.resolve("j_b_job.toon"), job("j_b", ""));
            awaitJobs(c, "j_b added", x -> enabled(x, "j_b"));

            Files.writeString(c.jobs.resolve("j_b_job.toon"), job("j_c", ""));   // the same file now names j_c
            awaitJobs(c, "j_b renamed to j_c", x -> present(x, "j_c") && !present(x, "j_b"));
        }
    }

    @Test
    void aRemovedFileRemovesTheJob(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertTrue(present(c, "j_a"));
            Files.delete(c.jobs.resolve("j_a_job.toon"));
            awaitJobs(c, "j_a removed", x -> !present(x, "j_a"));
            assertTrue(awaitEvent("jobs/j_a_job.toon", a -> true).attributes().get("applied").startsWith("applied"));
        }
    }

    @Test
    void aHalfWrittenFileIsNotAppliedAndIsReported(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // cut mid-word: the type no module registers, and the disabling line never arrived
            Files.writeString(c.jobs.resolve("j_a_job.toon"), "job:\n  name: j_a\n  type: mainten");
            Event e = awaitEvent("jobs/j_a_job.toon", a -> a.startsWith("not applied"));
            assertEquals(EventLevel.WARN, e.level(), e.toString());
            assertTrue(e.attributes().get("applied").contains("mainten"), e.toString());
            JsonNode j = row(c, "j_a");
            assertNotNull(j, "the working Job is not replaced");
            assertEquals("maintenance", j.get("type").asText(), j.toString());
            assertTrue(j.get("enabled").asBoolean(), j.toString());

            // a file that does not parse at all is refused the same way
            outside.clear();
            Files.writeString(c.jobs.resolve("j_a_job.toon"), "job:\n  type: maintenance\n  task: noop\n");
            awaitEvent("jobs/j_a_job.toon", a -> a.startsWith("not applied"));
            assertTrue(enabled(c, "j_a"));

            // falsifies the refusal: the finished write of the same file is applied
            Files.writeString(c.jobs.resolve("j_a_job.toon"), job("j_a", "  enabled: false\n"));
            awaitJobs(c, "j_a fixed and disabled", x -> !enabled(x, "j_a"));
        }
    }

    /** A refused version never counts as what the file ran: a later rename removes the Job the file really ran. */
    @Test
    void aRenameAfterARefusedWriteLeavesNoOrphanJob(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Files.writeString(c.jobs.resolve("j_a_job.toon"), "job:\n  name: j_c\n  type: mainten");
            awaitEvent("jobs/j_a_job.toon", a -> a.startsWith("not applied"));
            Files.writeString(c.jobs.resolve("j_a_job.toon"), job("j_c", ""));
            awaitJobs(c, "j_a renamed to j_c", x -> present(x, "j_c") && !present(x, "j_a"));
        }
    }

    /** A half-written name is not the file's Job: writing the original back is not refused as a duplicate. */
    @Test
    void aCutNameThenTheFinishedWriteIsNotADuplicate(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            Files.writeString(c.jobs.resolve("j_a_job.toon"), "job:\n  name: j_");
            awaitEvent("jobs/j_a_job.toon", a -> a.startsWith("not applied"));
            outside.clear();
            Files.writeString(c.jobs.resolve("j_a_job.toon"), job("j_a", "  enabled: false\n"));
            awaitJobs(c, "j_a disabled", x -> !enabled(x, "j_a"));
            assertFalse(present(c, "j_"));
            assertTrue(awaitEvent("jobs/j_a_job.toon", a -> true).attributes().get("applied").startsWith("applied"));
        }
    }
}
