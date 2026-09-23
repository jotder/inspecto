package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import dev.toonformat.jtoon.JToon;
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
 * LA-20 / D-E7 on the Enterprise edition, end to end: with {@code inspecto-policy} AND {@code inspecto-geo-link} on
 * the classpath the core discovers the real {@code PolicyEngine} on its own (nothing here forces a decider), and an
 * authored Access Policy targeting {@code resourceKinds: [investigation]} judges an Investigation's Working Set
 * relation — and, through the one shared gate {@code InvestigationRoutes.open}, EVERY Investigation route: a
 * DENY is a 404 even for the Investigation's owner, and with no policy the Professional rule (owner-only) remains.
 */
class ControlApiInvestigationPolicyTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        new ViewStore(root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('alice','bob'),('alice','carol')) AS t(caller,callee)", "2026-09-23T00:00:00Z"));
        new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
        return new Ctx(svc, api, api.port(), root);
    }

    @Test
    void anAuthoredPolicyDenyHidesTheWorkingSetEvenFromItsOwner(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations",
                    "{\"id\":\"case-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}",
                    "owner").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "owner").statusCode());
            String path = "/inv/investigations/case-a/working-set";

            HttpResponse<String> ok = send(c.port, "GET", path, null, "owner");
            assertEquals(200, ok.statusCode(), "no authored policy: the owner reads it — " + ok.body());
            assertEquals(404, send(c.port, "GET", path, null, "other").statusCode(),
                    "no authored policy: owner-only still holds (an ABSTAIN never widens)");

            Files.writeString(root.resolve("access-policies.toon"), JToon.encode(Map.of("policies", List.of(
                    Map.of("name", "freeze-calls-investigations", "effect", "deny",
                            "target", Map.of("resourceKinds", List.of("investigation")),
                            "when", "resource.dataset == 'calls_ds'")))));
            assertEquals(404, send(c.port, "GET", path, null, "owner").statusCode(),
                    "the PolicyEngine's row-level DENY hides the relation from its own owner");
            // 🔴 The DENY must hold on EVERY Investigation route, not just the relation: /log carries the sealed rows,
            // /replay and /dossier render them, /ops writes. It first shipped on /working-set alone, so this line
            // used to assert /log stayed 200 — the policy hid one view of data every other route still served.
            for (String[] r : new String[][]{
                    {"GET", "/inv/investigations/case-a/log", null},
                    {"GET", "/inv/investigations/case-a/working-set?at=1", null},   // LA-21: a Frozen Widget's pinned read
                    {"GET", "/inv/investigations/case-a/dossier", null},
                    {"POST", "/inv/investigations/case-a/replay", "{}"},
                    {"POST", "/inv/investigations/case-a/ops", "{\"op\":\"seed\",\"ids\":[\"bob\"]}"}}) {
                assertEquals(404, send(c.port, r[0], r[1], r[2], "owner").statusCode(),
                        r[0] + " " + r[1] + " must obey the same DENY as the Working Set");
            }
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String subject) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + subject);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
