package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.event.Event;
import com.gamma.event.EventQuery;
import com.gamma.event.EventType;
import com.gamma.inspector.ConcurrencyBroker;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;

import java.util.List;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SchedulerRoutes} over real HTTP: provenance-reporting GETs, the PUT round-trip persisting
 * {@code scheduler.toon} (server-wide at the spaces root, per-space in the space's config tree), the
 * 422 bounds gate, and — the point of the routes — <b>hot-apply</b>: a PUT is visible on
 * {@link ConcurrencyBroker#shared()} without any restart, and a boot with the file already present
 * installs the caps without any request.
 */
class ControlApiSchedulerSettingsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void resetBroker() {
        ConcurrencyBroker.use(null);
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    @Test
    void systemCapRoundTripsWithProvenanceAndHotApplies(@TempDir Path root) throws Exception {
        ConcurrencyBroker.use(null);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            // Before any save: default provenance, unbounded, live snapshot present.
            JsonNode def = json(send(c.port, "GET", "/system/scheduler", null));
            assertEquals("default", def.get("system").get("source").asText());
            assertEquals(0, def.get("system").get("maxConcurrentConsignments").asInt());
            assertTrue(def.get("cores").asInt() >= 1);
            assertTrue(def.has("live"), "live broker snapshot missing");

            // PUT round-trip: persisted at the spaces container root, provenance flips to file,
            // and the cap is live on the shared broker with no restart.
            HttpResponse<String> put = send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":16}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("file", json(put).get("system").get("source").asText());
            assertEquals(16, json(put).get("system").get("maxConcurrentConsignments").asInt());
            assertTrue(Files.exists(root.resolve("scheduler.toon")), "system doc not at the spaces root");
            assertEquals(16, ConcurrencyBroker.shared().systemCap(), "hot-apply failed");
        }
    }

    @Test
    void spaceCapRoundTripsPerSpaceAndHotApplies(@TempDir Path root) throws Exception {
        ConcurrencyBroker.use(null);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());

            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/scheduler", null));
            assertEquals("default", def.get("source").asText());

            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":12}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("file", json(put).get("source").asText());
            assertEquals("acme", json(put).get("id").asText());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("scheduler.toon")));
            assertEquals(12, ConcurrencyBroker.shared().spaceCap("acme"), "hot-apply failed");

            // 0 removes the tier (back to unbounded).
            assertEquals(200, send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":0}").statusCode());
            assertEquals(0, ConcurrencyBroker.shared().spaceCap("acme"));
        }
    }

    @Test
    void pollCadenceHotAppliesToTheRunningServiceAndPersists(@TempDir Path root) throws Exception {
        ConcurrencyBroker.use(null);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());

            // Hot-apply: the PUT retargets the space's running timers, visible on the service itself
            // and as the effective values in the response — no restart.
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":0,\"pollSeconds\":7,\"acquirePollSeconds\":9}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals(7, json(put).get("effectivePollSeconds").asInt());
            assertEquals(9, json(put).get("effectiveAcquirePollSeconds").asInt());
            assertEquals(7, c.spaces().space(com.gamma.service.SpaceId.of("acme")).orElseThrow()
                    .service().pollSeconds(), "hot-apply did not reach the running service");

            // A cap-only PUT must MERGE, not destroy the stored cadence (the data-loss trap).
            assertEquals(200, send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":3}").statusCode());
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/scheduler", null));
            assertEquals(7, got.get("pollSeconds").asInt(), "cap-only PUT destroyed the stored cadence");
            assertEquals(7, got.get("effectivePollSeconds").asInt());

            // An EXPLICIT null clears: stored key removed, live timer reverts to the -D default (60).
            assertEquals(200, send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":3,\"pollSeconds\":null}").statusCode());
            got = json(send(c.port, "GET", "/spaces/acme/settings/scheduler", null));
            assertTrue(got.get("pollSeconds").isNull(), "explicit null must clear the stored cadence");
            assertEquals(60, got.get("effectivePollSeconds").asInt(), "clear must revert the live timer");

            // Bounds gate.
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":0,\"pollSeconds\":0}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":0,\"pollSeconds\":\"fast\"}").statusCode());
        }
    }

    @Test
    void boundsGateRefusesBadValuesWith422(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // Gate order: with NO space bound, the write gate fires first — 503, never a 500.
            assertEquals(503, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":1}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler", "{}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":-1}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":\"lots\"}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":8589934592}").statusCode());
        }
    }

    @Test
    void intakeGlobalsRoundTripMergeAndHotApplyOntoTheGovernor(@TempDir Path root) throws Exception {
        com.gamma.acquire.IntakeGovernor.use(null);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());

            // PUT the trio: persisted, and live on the running governor with no restart.
            HttpResponse<String> put = send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":0,\"intakeMaxFilesPerCycle\":500,"
                            + "\"intakeMinFilesPerCycle\":10,\"intakeAdaptive\":false}");
            assertEquals(200, put.statusCode(), put.body());
            JsonNode sys = json(put).get("system");
            assertEquals("file", sys.get("intakeSource").asText());
            assertEquals(500, sys.get("effectiveIntake").get("maxFilesPerCycle").asInt());
            assertTrue(sys.get("effectiveIntake").get("active").asBoolean());
            var live = com.gamma.acquire.IntakeGovernor.shared().policy();
            assertEquals(500, live.baseCap(), "hot-apply did not reach the governor");
            assertEquals(10, live.minCap());
            assertFalse(live.adaptive());

            // A cap-only PUT MERGES — it must not destroy the stored intake globals.
            assertEquals(200, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":4}").statusCode());
            sys = json(send(c.port, "GET", "/system/scheduler", null)).get("system");
            assertEquals(500, sys.get("intakeMaxFilesPerCycle").asInt(), "cap-only PUT wiped intake globals");

            // Explicit null clears: back to the -Dingest.* bootstrap default (off in tests).
            assertEquals(200, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":4,\"intakeMaxFilesPerCycle\":null,"
                            + "\"intakeMinFilesPerCycle\":null,\"intakeAdaptive\":null}").statusCode());
            assertEquals(0, com.gamma.acquire.IntakeGovernor.shared().policy().baseCap(),
                    "clear must revert the live governor to the bootstrap default");

            // Bounds gates.
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":0,\"intakeMaxFilesPerCycle\":-1}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":0,\"intakeMinFilesPerCycle\":0}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":0,\"intakeAdaptive\":\"maybe\"}").statusCode());
        } finally {
            com.gamma.acquire.IntakeGovernor.use(null);
        }
    }

    /** S8: the live block reports free slots and the governor's throttle state. An untouched fleet
     *  must report an EMPTY throttled list — "nothing to see" is the useful answer, not a wall of
     *  rows saying normal. */
    @Test
    void liveBlockReportsFreeSlotsAndAnEmptyThrottleListOnAnUntouchedFleet(@TempDir Path root) throws Exception {
        ConcurrencyBroker.use(null);
        com.gamma.acquire.IntakeGovernor.use(null);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());

            // Unbounded ⇒ free slots is null, never a fake 0 (which reads as "wedged").
            JsonNode live = json(send(c.port, "GET", "/system/scheduler", null)).get("live");
            assertTrue(live.get("system_free").isNull(), "unbounded must report null, not 0");
            assertEquals(0, live.get("throttled").get("total").asInt());
            assertFalse(live.get("throttled").get("truncated").asBoolean());

            // Bounded ⇒ a real count of what is available right now.
            assertEquals(200, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":16}").statusCode());
            live = json(send(c.port, "GET", "/system/scheduler", null)).get("live");
            assertEquals(16, live.get("system_free").asInt(), "nothing in flight ⇒ every slot free");
        } finally {
            com.gamma.acquire.IntakeGovernor.use(null);
        }
    }

    /**
     * BACKLOG §4 (a), operator decision 2026-08-26: a scheduler change is journalled with its DELTAS.
     * The generic audit trail already records who/when/status; this event carries what the numbers
     * became. ⚠ A no-op PUT must journal NOTHING — a trail that logs unchanged re-saves teaches an
     * investigator to skim past it.
     */
    @Test
    void aChangedSettingIsJournalledWithItsDeltaAndANoOpIsNot(@TempDir Path root) throws Exception {
        ConcurrencyBroker.use(null);
        com.gamma.acquire.IntakeGovernor.use(null);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());

            assertEquals(200, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":16,\"intakeMaxFilesPerCycle\":500}").statusCode());
            Event first = latestSchedulerEvent(c);
            assertNotNull(first, "a changed setting must be journalled");
            assertEquals("0 -> 16", first.attributes().get("max_concurrent_consignments"));
            assertEquals("inherit -> 500", first.attributes().get("intake_max_files_per_cycle"),
                        "an unset old value must read as 'inherit', not 'null'");
            assertEquals("server-wide", first.attributes().get("tier"));

            // Re-saving the SAME values changes nothing, so it must add no entry.
            int before = schedulerEvents(c).size();
            assertEquals(200, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":16,\"intakeMaxFilesPerCycle\":500}").statusCode());
            assertEquals(before, schedulerEvents(c).size(), "a no-op PUT must not be journalled");

            // A partial change journals ONLY the key that moved.
            assertEquals(200, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":8}").statusCode());
            Event partial = latestSchedulerEvent(c);
            assertEquals("16 -> 8", partial.attributes().get("max_concurrent_consignments"));
            assertNull(partial.attributes().get("intake_max_files_per_cycle"),
                    "an unchanged key must not appear in the delta");

            // The space tier journals its own scope.
            assertEquals(200, send(c.port, "PUT", "/spaces/acme/settings/scheduler",
                    "{\"maxConcurrentConsignments\":4,\"pollSeconds\":7}").statusCode());
            Event spaceEvent = latestSchedulerEvent(c);
            assertEquals("space", spaceEvent.attributes().get("tier"));
            assertEquals("acme", spaceEvent.attributes().get("scope"));
            assertEquals("inherit -> 7", spaceEvent.attributes().get("poll_seconds"));
        } finally {
            com.gamma.acquire.IntakeGovernor.use(null);
        }
    }

    private static List<Event> schedulerEvents(Ctx c) {
        return c.spaces().current().service().events().query(EventQuery.recent(EventQuery.MAX_LIMIT))
                .stream().filter(e -> EventType.SCHEDULER_SETTINGS_CHANGED.equals(e.type())).toList();
    }

    /** Newest scheduler event, or null — the store answers newest-first. */
    private static Event latestSchedulerEvent(Ctx c) {
        List<Event> all = schedulerEvents(c);
        return all.isEmpty() ? null : all.get(0);
    }

    @Test
    void configuredFilesInstallAtBootWithoutAnyRequest(@TempDir Path root) throws Exception {
        // Seed both documents on disk, then boot: caps + intake globals must be live before any HTTP call.
        Files.writeString(root.resolve("scheduler.toon"),
                "max_concurrent_consignments: 5\nintake_max_files_per_cycle: 44\n");
        Files.createDirectories(root.resolve("acme").resolve("config"));
        Files.writeString(root.resolve("acme").resolve("config").resolve("scheduler.toon"),
                "max_concurrent_consignments: 3\n");
        ConcurrencyBroker.use(null);
        com.gamma.acquire.IntakeGovernor.use(null);
        try (Ctx c = open(root)) {
            assertEquals(5, ConcurrencyBroker.shared().systemCap(), "system cap not installed at boot");
            assertEquals(3, ConcurrencyBroker.shared().spaceCap("acme"), "space cap not installed at boot");
            assertEquals(44, com.gamma.acquire.IntakeGovernor.shared().policy().baseCap(),
                    "intake globals not installed at boot");
        } finally {
            com.gamma.acquire.IntakeGovernor.use(null);
        }
    }

    // ── BACKLOG D11: the resource pair, served with provenance ───────────────────────

    @Test
    void resourceCapsRoundTripWithProvenanceAndHotApply(@TempDir Path root) throws Exception {
        ConcurrencyBroker.use(null);
        String priorMem = System.getProperty(com.gamma.util.DuckDbUtil.PROP_MEMORY_LIMIT);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());

            // Before any save: the on-by-default pair, reported as `default` — an operator must be able
            // to see that a cap is in force even though nothing was configured.
            JsonNode sys = json(send(c.port, "GET", "/system/scheduler", null)).get("system");
            assertEquals("default", sys.get("maxConcurrentJobRunsSource").asText());
            assertEquals(4, sys.get("maxConcurrentJobRuns").asInt(), "D11's default bound");
            assertEquals("default", sys.get("duckdbMemoryLimitSource").asText());
            assertTrue(sys.get("duckdbMemoryLimit").isNull(),
                    "no stored value and no -D → null, so the UI shows DuckDB's own default");

            // PUT the pair; both persist and the memory_limit becomes the use-time owner.
            HttpResponse<String> put = send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":8,\"duckdbMemoryLimit\":\"2GB\",\"maxConcurrentJobRuns\":4}");
            assertEquals(200, put.statusCode(), put.body());
            JsonNode saved = json(put).get("system");
            assertEquals("file", saved.get("duckdbMemoryLimitSource").asText());
            assertEquals("2GB", saved.get("duckdbMemoryLimit").asText());
            assertEquals("file", saved.get("maxConcurrentJobRunsSource").asText());
            assertEquals("2GB", com.gamma.util.DuckDbUtil.memoryLimit(null), "hot-apply failed");
            assertTrue(Files.readString(root.resolve("scheduler.toon")).contains("duckdb_memory_limit"),
                    "the pair was not persisted");

            // A -D flag is only a bootstrap default: it must NOT override a stored file value.
            System.setProperty(com.gamma.util.DuckDbUtil.PROP_MEMORY_LIMIT, "512MB");
            assertEquals("file", json(send(c.port, "GET", "/system/scheduler", null))
                    .get("system").get("duckdbMemoryLimitSource").asText(),
                    "a -D flag must not take ownership back from the stored document");

            // A nonsense size string is a 422, not a value DuckDB will choke on at run time.
            assertEquals(422, send(c.port, "PUT", "/system/scheduler",
                    "{\"maxConcurrentConsignments\":8,\"duckdbMemoryLimit\":\"lots\"}").statusCode());
        } finally {
            com.gamma.util.DuckDbUtil.installMemoryLimit(null);
            if (priorMem == null) System.clearProperty(com.gamma.util.DuckDbUtil.PROP_MEMORY_LIMIT);
            else System.setProperty(com.gamma.util.DuckDbUtil.PROP_MEMORY_LIMIT, priorMem);
        }
    }

    @Test
    void storedResourceCapsInstallAtBoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("scheduler.toon"),
                "max_concurrent_consignments: 5\nduckdb_memory_limit: 2GB\nmax_concurrent_job_runs: 4\n");
        ConcurrencyBroker.use(null);
        try (Ctx c = open(root)) {
            assertEquals("2GB", com.gamma.util.DuckDbUtil.memoryLimit(null),
                    "a configured memory_limit must take effect without any request");
        } finally {
            com.gamma.util.DuckDbUtil.installMemoryLimit(null);
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}
