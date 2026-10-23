package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /signals} over real HTTP: a failing Job run emits {@code job.run.started} (INFO) and
 * {@code job.run.failed} (CRITICAL) signals; the route reconstructs them from the event ledger and filters
 * by type glob, exact type, the {@code severity} floor and the {@code source} Ref (kind or {@code kind:id})
 * — plus the 400 gate on an unrecognised severity. A companion test drives {@code GET /signals/tree}, which
 * assembles one correlation chain into a parent→child forest (and 400s without a {@code correlationId}).
 */
class ControlApiSignalsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
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
        return new Ctx(spaces, api, api.port());
    }

    /** HOME-TILES-1: a count over the ledger, windowed, with the type required and the cap stated. */
    /** 2026-09-17 (SIGNALS-LIST-UNCAPPED-1): the caller's limit was passed straight through; now it is clamped. */
    @Test
    void theListLimitIsClampedLikeEveryOtherListRoute() {
        assertEquals(200, SignalRoutes.clampLimit(null), "absent -> default");
        assertEquals(200, SignalRoutes.clampLimit("garbage"), "non-number -> default, not 400 (unchanged behaviour)");
        assertEquals(200, SignalRoutes.clampLimit("0"), "non-positive -> default");
        assertEquals(50, SignalRoutes.clampLimit("50"));
        assertEquals(5000, SignalRoutes.clampLimit("99999999"), "ceiling");
    }

    @Test
    void countsSignalsOfATypeInAWindow(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";
            assertEquals(422, send(c.port, "GET", base + "/signals/count", null).statusCode(), "type is required");

            assertEquals(200, send(c.port, "POST", base + "/jobs",
                    "{\"name\":\"broken\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"cron\":\"0 3 * * *\"}").statusCode());
            send(c.port, "POST", base + "/jobs/broken/trigger", null);
            pollForTerminalRunSignal(c.port, base);

            JsonNode all = JSON.readTree(send(c.port, "GET", base + "/signals/count?type=job.run.*", null).body()).get("data");
            assertTrue(all.get("count").asInt() >= 1, all.toString());
            assertFalse(all.get("capped").asBoolean());
            assertEquals(0, all.get("datasets").asInt(), "a job.run signal names no dataset");

            long future = System.currentTimeMillis() + 3_600_000L;
            JsonNode none = JSON.readTree(send(c.port, "GET", base + "/signals/count?type=job.run.*&since=" + future, null).body()).get("data");
            assertEquals(0, none.get("count").asInt(), "the window bounds the count");
        }
    }

    /** TELCO-FRAUD-STORY-GAPS-1: the Home tile asks for two Dataset-write types in ONE call, so a Dataset named by
     *  both counts once; the old route read the list as one literal type and counted nothing. */
    @Test
    void countsSeveralTypesInOneCallAndADatasetNamedByBothCountsOnce(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            com.gamma.event.EventLog log = c.spaces().space(com.gamma.service.SpaceId.of("acme")).orElseThrow().service().eventLog();
            log.emit(signal("dataset.write", "tf_cdr"));
            log.emit(signal("job.dataset.produced", "tf_cdr"));       // the same Dataset, the other way
            log.emit(signal("job.dataset.produced", "tf_irsf_offenders"));
            log.emit(signal("job.run.succeeded", null));             // another type: not counted

            String base = "/spaces/acme/signals/count?type=";
            JsonNode both = JSON.readTree(send(c.port, "GET", base + "dataset.write,%20job.dataset.produced", null).body()).get("data");
            assertEquals(3, both.get("count").asInt(), both.toString());
            assertEquals(2, both.get("datasets").asInt(), "tf_cdr written both ways counts once: " + both);
            assertFalse(both.get("capped").asBoolean());

            JsonNode one = JSON.readTree(send(c.port, "GET", base + "job.dataset.produced", null).body()).get("data");
            assertEquals(2, one.get("count").asInt(), "a single type is unchanged: " + one);
            assertEquals(2, one.get("datasets").asInt());
            assertEquals(422, send(c.port, "GET", base + "%20,%20", null).statusCode(), "a list of blanks is no type");
        }
    }

    /** {@code capped} reads the RAW signal page: a window full of other types may hide some of the asked type.
     *  The old route tested the filtered size, so a full page of job signals reported an exact-looking 0. (Driven
     *  through {@code tally} with a small page: the default in-memory ledger holds fewer than MAX_LIMIT events.) */
    @Test
    void capsWhenTheWindowIsFullEvenIfFewSignalsAreOfTheAskedType() {
        List<com.gamma.signal.Signal> full = List.of(sig("job.run.started", null), sig("job.run.started", null), sig("dataset.write", "a"));
        java.util.Map<String, Object> r = SignalRoutes.tally(full, List.of("dataset.write"), 3);
        assertEquals(1, r.get("count"));
        assertEquals(true, r.get("capped"), "the page was full, so the figure is a floor: " + r);
        assertEquals(false, SignalRoutes.tally(full, List.of("dataset.write"), 4).get("capped"), "a page with room is exact");
    }

    private static com.gamma.signal.Signal sig(String type, String dataset) {
        return new com.gamma.signal.Signal(null, type, null, null, com.gamma.signal.Ref.of("job", "t"), null, null,
                null, "acme", null, null, dataset == null ? java.util.Map.of() : java.util.Map.of("dataset", dataset), 0);
    }

    private static com.gamma.audit.Event signal(String type, String dataset) {
        return sig(type, dataset).toEvent();
    }

    @Test
    void queriesSignalsWithTypeAndSeverityFilters(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";

            // a maintenance job missing its required 'dir' param → the run throws → job.run.failed (CRITICAL)
            assertEquals(200, send(c.port, "POST", base + "/jobs",
                    "{\"name\":\"broken\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"cron\":\"0 3 * * *\"}").statusCode());
            send(c.port, "POST", base + "/jobs/broken/trigger", null);

            // signals arrive off the request thread — wait for the terminal one
            List<JsonNode> runSignals = pollForTerminalRunSignal(c.port, base);
            List<String> types = runSignals.stream().map(s -> s.get("type").asText()).toList();
            assertTrue(types.contains("job.run.started"), types.toString());
            assertTrue(types.contains("job.run.failed"), types.toString());

            // the severity floor filters in-store (the new param): CRITICAL keeps only the failure
            JsonNode critical = json(send(c.port, "GET", base + "/signals?severity=CRITICAL", null));
            assertTrue(critical.isArray() && !critical.isEmpty(), "a CRITICAL signal was emitted");
            boolean sawFailed = false;
            for (JsonNode s : critical) {
                assertEquals("critical", s.get("severity").asText(), "severity floor admits only CRITICAL (wire lowercase)");
                if ("job.run.failed".equals(s.get("type").asText())) sawFailed = true;
            }
            assertTrue(sawFailed, "the failed run's CRITICAL signal is present");

            // exact-type filter
            JsonNode failed = json(send(c.port, "GET", base + "/signals?type=job.run.failed", null));
            assertTrue(failed.isArray() && !failed.isEmpty());
            for (JsonNode s : failed) assertEquals("job.run.failed", s.get("type").asText());

            // source filter (the new param): the run signals are sourced Ref("job","broken") — filter by kind…
            JsonNode bySourceKind = json(send(c.port, "GET", base + "/signals?source=job", null));
            assertTrue(bySourceKind.isArray() && !bySourceKind.isEmpty(), "source=job returns the run signals");
            for (JsonNode s : bySourceKind)
                assertEquals("job", s.get("source").get("kind").asText(), "every result is job-sourced");
            // …and by the compact kind:id form
            JsonNode byCompact = json(send(c.port, "GET", base + "/signals?source=job:broken", null));
            assertTrue(byCompact.isArray() && !byCompact.isEmpty(), "source=job:broken matches the 'broken' job's signals");
            for (JsonNode s : byCompact) assertEquals("broken", s.get("source").get("id").asText());
            // a source matching nothing filters everything out
            JsonNode noSource = json(send(c.port, "GET", base + "/signals?source=pipeline", null));
            assertTrue(noSource.isArray() && noSource.isEmpty(), "a non-matching source returns no signals");

            // an unrecognised severity is a 400 (not a silent "everything")
            assertEquals(400, send(c.port, "GET", base + "/signals?severity=NOPE", null).statusCode());
        }
    }

    @Test
    void assemblesTheCausationTreeForACorrelationChain(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";
            assertEquals(200, send(c.port, "POST", base + "/jobs",
                    "{\"name\":\"broken\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"cron\":\"0 3 * * *\"}").statusCode());
            send(c.port, "POST", base + "/jobs/broken/trigger", null);

            List<JsonNode> runSignals = pollForTerminalRunSignal(c.port, base);
            String correlationId = runSignals.get(0).get("correlationId").asText();
            assertFalse(correlationId.isBlank(), "a manual run stamps its runId as the correlationId");

            // the tree view returns the chain's signals as nodes, each carrying a children array
            JsonNode tree = json(send(c.port, "GET", base + "/signals/tree?correlationId=" + correlationId, null));
            assertTrue(tree.isArray() && !tree.isEmpty(), "the correlation chain's signals come back as a tree");
            List<String> rootTypes = new ArrayList<>();
            for (JsonNode node : tree) {
                assertTrue(node.has("children") && node.get("children").isArray(), "every node carries a children array");
                assertTrue(node.has("signalId") && node.has("type"), "a node is the full signal view");
                rootTypes.add(node.get("type").asText());
            }
            // run-lifecycle signals carry no causationId today, so they are sibling roots under the runId
            assertTrue(rootTypes.contains("job.run.started") && rootTypes.contains("job.run.failed"), rootTypes.toString());

            // correlationId is required — a tree without an anchor is an unbounded forest
            assertEquals(400, send(c.port, "GET", base + "/signals/tree", null).statusCode());
        }
    }

    /** Poll {@code GET /signals?type=job.run.*} until the terminal (failed/completed) signal is recorded. */
    private List<JsonNode> pollForTerminalRunSignal(int port, String base) throws Exception {
        for (int i = 0; i < 150; i++) {
            JsonNode arr = json(send(port, "GET", base + "/signals?type=job.run.*", null));
            if (arr.isArray()) {
                List<JsonNode> out = new ArrayList<>();
                arr.forEach(out::add);
                boolean terminal = out.stream().map(s -> s.get("type").asText())
                        .anyMatch(t -> t.equals("job.run.failed") || t.equals("job.run.completed"));
                if (terminal) return out;
            }
            Thread.sleep(100);
        }
        fail("no terminal job.run.* signal within timeout");
        return null;   // unreachable
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}
