package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * COMPONENT-VALIDATE-ALL-1 over real HTTP, in the module that owns the Reconciliation validator: its draft is judged by
 * {@code POST /validate}, the one-Dataset shape is refused at save too (422), and a misspelled tolerance key is named.
 */
class ControlApiReconValidateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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
            return Optional.of(new Subject("author-1", def.capabilities(), def.dataScopes(), Map.of(), null, null, null, agent));
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
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("author-1", "checker-1"))));
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        return client.send(b.POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** {@code POST /validate} of {@code {type, id, config}}; the {@code data} node. */
    private JsonNode validate(Ctx c, String type, String config, String auth) throws Exception {
        HttpResponse<String> r = send(c, "/validate", "{\"type\":\"" + type + "\",\"id\":\"draft_x\",\"config\":" + config + "}", auth);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String messages(JsonNode data) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode f : data.get("findings")) sb.append(f.get("severity").asText()).append(' ').append(f.get("message").asText()).append(" | ");
        return sb.toString();
    }

    @Test
    void aReconciliationDraftIsJudgedAndATypoedToleranceIsNamed(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode one = validate(c, "reconciliation", "{\"datasets\":[\"a\"],\"keyColumns\":[\"k\"]}", AUTHOR);
            assertFalse(one.get("clean").asBoolean(), "a Reconciliation needs two or three Datasets: " + one);

            // the same shape is refused when it is saved, not first when it runs
            HttpResponse<String> save = send(c, "/components/reconciliation", "{\"id\":\"rc_one\",\"datasets\":[\"a\"],\"keyColumns\":[\"k\"]}", AUTHOR);
            assertEquals(422, save.statusCode(), save.body());
            assertTrue(save.body().contains("2 or 3 datasets"), save.body());

            JsonNode typo = validate(c, "reconciliation", "{\"datasets\":[\"a\",\"b\"],\"keyColumns\":[\"k\"],"
                    + "\"compareColumns\":[{\"column\":\"amount\",\"agg\":\"sum\",\"toleranceTyp\":\"absolute\",\"tolerance\":0}]}", AGENT);
            assertTrue(typo.toString().contains("compareColumns[0].toleranceTyp"), typo.toString());
        }
    }

}
