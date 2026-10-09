package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.service.CollectorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-set size limit ({@code max_set_bytes}, operator 2026-10-10) over real HTTP: a step whose sealed Working Set would exceed the
 * Space's limit is refused {@code 413 PAYLOAD_TOO_LARGE} naming the limit and how to raise it, nothing is stored, it is audited as
 * {@code LINK_WORKING_SET_TOO_LARGE}, and raising the setting lets the same step through.
 */
class ControlApiInvestigationSetLimitTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('a','b','voice','2026-09-01 10:30:00')", "('a','c','voice','2026-09-02 12:00:00')",
            "('b','c','sms','2026-09-03 21:30:00')", "('c','d','sms','2026-09-05 08:00:00')");
    private static final String RANGE = "from=2026-09-01T00:00:00-03:00&to=2026-09-06T00:00:00-03:00";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel, CAST(ts AS TIMESTAMP) AS ts FROM (VALUES " + ROWS
                            + ") AS t(caller,callee,channel,ts)", "2026-09-23T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode post(Ctx c, String path, String body) throws Exception {
        return data(send(c.port, "POST", path, body, null));
    }

    private int status(Ctx c, String method, String path, String body) throws Exception {
        return send(c.port, method, path, body, null).statusCode();
    }

    private void create(Ctx c, String id, boolean timed) throws Exception {
        post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                + "\"targetCol\":\"callee\",\"linkKindCol\":\"channel\""
                + (timed ? ",\"timeCol\":\"ts\",\"timeColZone\":\"America/Sao_Paulo\"" : "") + "}");
    }

    @Test
    void aStepWhoseSetIsOverTheSpacesLimitIs413AndAuditedAndRaisingTheLimitLetsItThrough(@TempDir Path cfg, @TempDir Path root) throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "PUT", "/settings/link-analysis", "{\"maxSetBytes\":1024}", null).statusCode());
            create(c, "case-a", false);
            String ops = "/inv/investigations/case-a/ops";
            post(c, ops, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            post(c, ops, "{\"op\":\"expand\"}");
            Path log = root.resolve("audit/snapshots/investigations/case-a/log.jsonl");
            int before = Files.readAllLines(log).size();
            String big = "{\"op\":\"annotate\",\"ids\":[\"b\"],\"note\":\"" + "n".repeat(1200) + "\"}";
            HttpResponse<String> refused = send(c.port, "POST", ops, big, null);
            assertEquals(413, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("PAYLOAD_TOO_LARGE") && refused.body().contains("1024") && refused.body().contains("max_set_bytes"), refused.body());
            assertEquals(before, Files.readAllLines(log).size(), "nothing was stored");
            assertEquals(1, seen.stream().filter(e -> LinkEventTypes.LINK_WORKING_SET_TOO_LARGE.equals(e.type())).count(), "audited");
            assertFalse(seen.stream().anyMatch(e -> String.valueOf(e.attributes()).contains("nnnnnnnn")), "never the content");

            assertEquals(200, send(c.port, "PUT", "/settings/link-analysis", "{\"maxSetBytes\":1048576}", null).statusCode());
            assertEquals(200, send(c.port, "POST", ops, big, null).statusCode(), "the same step passes once the limit is raised, no restart");
            assertEquals(before + 1, Files.readAllLines(log).size());
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }
}
