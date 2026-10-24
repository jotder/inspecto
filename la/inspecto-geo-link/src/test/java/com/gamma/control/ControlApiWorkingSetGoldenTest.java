package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PERF-P4-3 golden: the Working Set body (field order included, as clients parse it) must stay byte-identical when the
 * cache key moves from sha256(log) to the store's logToken. The golden file was captured from the PRE-change build
 * ({@code -Dgolden.write=true}); only the volatile fields are normalised ({@code key}/{@code head.workingSetHash} hash
 * time-stamped log lines, {@code cached} is hit-or-miss).
 */
class ControlApiWorkingSetGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String OWNER = "Bearer owner";
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", OWNER);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(int port, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(port, method, path, body);
        assertEquals(200, r.statusCode(), method + " " + path + " -> " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String normalised(JsonNode data) {
        ObjectNode n = data.deepCopy();
        n.put("key", "<key>");
        n.put("cached", false);
        ((ObjectNode) n.get("head")).put("workingSetHash", "<hash>");
        return n.toString();
    }

    /** Masked tokens come from a random per-Investigation key: number them by first appearance; a linkId is base64 of tokens. */
    private static String canon(String text) {
        java.util.Map<String, String> seen = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("masked:[0-9a-f]{16}").matcher(text.replaceAll("\"linkId\":\"lk\\.[^\"]+\"", "\"linkId\":\"<lk>\""));
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, "masked:#" + seen.computeIfAbsent(m.group(), k -> Integer.toString(seen.size())));
        m.appendTail(sb);
        return sb.toString();
    }

    @Test
    void theBodyIsByteIdenticalToThePreChangeResponse(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> "Bearer owner".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("analyst-1", Set.of("canManageIncidents"))) : Optional.empty());
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        StringBuilder out = new StringBuilder();
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                new ViewStore(root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                        "SELECT caller, callee, channel FROM (VALUES ('a','b','voice'),('a','c','sms'),('b','d','voice'))"
                                + " AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
                new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
                int port = api.port();
                for (String mode : List.of("none", "all")) {
                    Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: " + mode + "\n");
                    if (mode.equals("none")) {
                        ok(port, "POST", "/inv/investigations", CREATE);
                        ok(port, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}");
                        ok(port, "POST", INV + "/ops", "{\"op\":\"expand\"}");
                    }
                    for (String q : List.of("of=entities", "of=links", "of=excluded", "of=entities&limit=1&offset=1", "of=links&at=1")) {
                        out.append(mode).append(' ').append(q).append('\n')
                                .append(normalised(ok(port, "GET", INV + "/working-set?" + q, null))).append('\n');
                    }
                }
            } finally {
                api.close();
                svc.close();
            }
        } finally {
            System.clearProperty("assist.write.root");
        }
        Path golden = Path.of("src/test/resources/golden/working-set-bodies.txt");
        if (Boolean.getBoolean("golden.write")) {
            Files.writeString(golden, canon(out.toString()));
            return;
        }
        assertEquals(Files.readString(golden).replace("\r\n", "\n"), canon(out.toString()));
    }
}
