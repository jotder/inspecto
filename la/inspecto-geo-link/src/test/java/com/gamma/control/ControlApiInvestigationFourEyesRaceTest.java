package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D-U7 four-eyes under a race, over real HTTP: several approvers decide ONE pending request at the same instant and exactly one
 * decision is made. The Investigation store has no lock around "read the request, run the expand, mark it approved" any more, so
 * the guarantee is the compare-and-set that moves the request's status BEFORE the append it authorises. A retry loop that
 * re-ran the whole decide on a lost append would find the request still {@code pending} (its status is only written after the
 * append) and append the expand twice: the log would then hold more than one step per request.
 */
class ControlApiInvestigationFourEyesRaceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003", D = "27820000004";
    private static final String ROWS = String.join(",", "('" + A + "','" + B + "','voice')",
            "('" + A + "','" + C + "','sms')", "('" + B + "','" + D + "','voice')");
    private static final String CREATE = "{\"id\":\"case-a\",\"purpose\":\"Fraud referral FR-7\","
            + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String OPS = "/inv/investigations/case-a/ops";
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void severalApproversDecidingOneRequestAtOnceMakeExactlyOneDecision(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer lead-1" -> Optional.of(new Subject("lead-1", Set.of("canApproveLinkExpansions")));
            case "Bearer lead-2" -> Optional.of(new Subject("lead-2", Set.of("canApproveLinkExpansions")));
            case "Bearer lead-3" -> Optional.of(new Subject("lead-3", Set.of("canApproveLinkExpansions")));
            default -> Optional.empty();
        });
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        CollectorService svc = null;
        ControlApi api = null;
        try {
            svc = new CollectorService(List.of(pipe), 3600, 1);
            api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)", "2026-09-24T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            int port = api.port();
            Files.writeString(root.resolve("link-analysis.toon"), "four_eyes_budget_above: 100\n");
            assertEquals(200, send(port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(port, "POST", OPS, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}", "Bearer owner").statusCode());
            Path log = root.resolve("audit/snapshots/investigations/case-a/log.jsonl");

            for (int round = 1; round <= 4; round++) {
                String rid = "p" + round;
                JsonNode pending = JSON.readTree(send(port, "POST", OPS, "{\"op\":\"expand\",\"budget\":" + (500 + round) + "}", "Bearer owner").body())
                        .get("data");
                assertEquals(rid, pending.at("/pending/id").asText(), pending.toString());
                int stepsBefore = Files.readAllLines(log).size();
                String approve = "/inv/investigations/case-a/pending/" + rid + "/approve";

                List<String> approvers = List.of("Bearer lead-1", "Bearer lead-2", "Bearer lead-3", "Bearer lead-1", "Bearer lead-2", "Bearer lead-3");
                CountDownLatch go = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(approvers.size());
                List<Integer> statuses = new ArrayList<>();
                try {
                    List<Future<Integer>> fs = new ArrayList<>();
                    for (String who : approvers)
                        fs.add(pool.submit(() -> {
                            go.await();
                            return send(port, "POST", approve, "{}", who).statusCode();
                        }));
                    go.countDown();
                    for (Future<Integer> f : fs) statuses.add(f.get());
                } finally {
                    pool.shutdownNow();
                }
                assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "exactly one approval wins round " + round + ": " + statuses);
                assertEquals(approvers.size() - 1, statuses.stream().filter(s -> s == 409).count(), "every other decider is refused 409: " + statuses);
                assertEquals(stepsBefore + 1, Files.readAllLines(log).size(), "the expand ran once, not once per approver");
                JsonNode after = JSON.readTree(send(port, "GET", "/inv/investigations/case-a/log", null, "Bearer owner").body()).get("data");
                assertEquals("approved", after.at("/pending/" + (round - 1) + "/status").asText());
            }
            assertEquals(200, send(port, "POST", "/inv/investigations/case-a/replay", "{}", "Bearer owner").statusCode(), "and the log still replays");
        } finally {
            Authenticators.forTest(null);
            System.clearProperty("assist.write.root");
            if (api != null) api.close();
            if (svc != null) svc.close();
        }
    }
}
