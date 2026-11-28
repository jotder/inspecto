package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobService;
import com.gamma.service.CollectorService;
import com.gamma.signal.Ref;
import com.gamma.signal.Signal;
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
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /signals/board} (SIGNAL-BOARD-1 SB-3) over real HTTP with an armed Authenticator: 401 without a credential,
 * 403 for a Subject without {@code canAuthorWorkbench} (the only proof of the gate), 200 with it. The body is the live
 * Space: a Signal posted to the ledger, a Job whose {@code when} guard says no to it, a type nobody hears and a Job
 * listening to a type nothing posts.
 */
class ControlApiSignalBoardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if ("Bearer author".equals(auth)) return Optional.of(new Subject("author-1", Set.of("canAuthorWorkbench")));
            if ("Bearer reader".equals(auth)) return Optional.of(new Subject("reader-1", Set.of("canOperateRuns")));
            return Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private HttpResponse<String> get(int port, String query, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/signals/board" + query)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void theBoardIsGatedOnAuthoringAndShowsTheSpacesTypesListenersAndFindings(@TempDir Path dir) throws Exception {
        CollectorService svc = new CollectorService(List.of(TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write()), 3600, 1);
        JobService jobs = svc.jobServiceOrCreate();
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(401, get(port, "", null).statusCode(), "no credential");
            assertEquals(403, get(port, "", "reader").statusCode(), "a Subject without canAuthorWorkbench");

            // a Job that hears probe.burst but whose guard says no (no Run is started), and one nothing posts to
            jobs.upsertJob(JobConfig.fromMap(Map.of("job", Map.of("name", "on_burst", "type", "maintenance",
                    "task", "cleanup", "on_signal", "probe.burst", "when", "$signal.i == 99"))));
            jobs.upsertJob(JobConfig.fromMap(Map.of("job", Map.of("name", "on_typo", "type", "maintenance",
                    "task", "cleanup", "on_signal", "probe.brust"))));
            svc.eventLog().emit(signal("probe.burst", Map.of("i", 1)));
            svc.eventLog().emit(signal("loop.ping", Map.of("k", "v")));   // nobody listens

            JsonNode data = pollForGuard(port);
            assertEquals(24, data.get("hours").asInt(), "the default window");
            assertFalse(data.get("capped").asBoolean());
            assertFalse(data.get("missedTracked").asBoolean(), "SB-2b not wired yet");

            JsonNode burst = find(data.get("types"), "probe.burst");
            assertEquals(1, burst.get("posted").asInt());
            assertEquals(1, burst.get("guardsSaidNo").asInt(), "the when guard said no");
            assertEquals(0, burst.get("runsStarted").asInt());
            assertEquals("on_burst", burst.get("listeners").get(0).get("job").asText());
            assertEquals("job:poster", burst.get("postedBy").get(0).asText());
            assertFalse(burst.get("lastPosted").isNull());

            assertTrue(names(data.get("unheard"), "type").contains("loop.ping"), data.get("unheard").toString());
            assertFalse(names(data.get("unheard"), "type").contains("probe.burst"));
            assertEquals(List.of("on_typo"), names(data.get("unposted"), "job"));
            assertEquals(data.get("types").size(), data.get("typesTotal").asInt());
            assertFalse(data.get("typesTruncated").asBoolean());

            assertEquals(168, JSON.readTree(get(port, "?hours=9999", "author").body()).get("data").get("hours").asInt(),
                    "the window is bounded");
            assertEquals(400, get(port, "?hours=abc", "author").statusCode(), "a window that is not a number");
        } finally {
            api.close();
            svc.close();
        }
    }

    /** The guard is evaluated on the ledger's listener thread; wait until the skipped Run is recorded. */
    private JsonNode pollForGuard(int port) throws Exception {
        JsonNode data = null;
        for (int i = 0; i < 100; i++) {
            HttpResponse<String> ok = get(port, "", "author");
            assertEquals(200, ok.statusCode(), ok.body());
            data = JSON.readTree(ok.body()).get("data");
            if (find(data.get("types"), "probe.burst").get("guardsSaidNo").asInt() > 0) return data;
            Thread.sleep(100);
        }
        return data;
    }

    private static com.gamma.audit.Event signal(String type, Map<String, Object> payload) {
        return new Signal(null, type, null, null, Ref.of("job", "poster"), null, null, null, null, null, null,
                payload, 0).toEvent();
    }

    private static List<String> names(JsonNode list, String field) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode n : list) out.add(n.get(field).asText());
        return out;
    }

    private static JsonNode find(JsonNode types, String type) {
        for (JsonNode t : types) if (type.equals(t.get("type").asText())) return t;
        return fail("no Signal type '" + type + "' in " + types);
    }
}
