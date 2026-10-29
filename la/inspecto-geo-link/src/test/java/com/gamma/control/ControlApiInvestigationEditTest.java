package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.EventLog;
import com.gamma.la.core.LinkEventTypes;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-INVESTIGATION-RENAME-1 — {@code PATCH /inv/investigations/{id}} over real HTTP with an ARMED Authenticator (with no Subject
 * {@code withCapability} is a no-op): 401 without a credential, 403 without {@code canManageIncidents}, 403 for a non-lead member,
 * 404 for a stranger and an unknown id, 422 for a body naming neither field / a blank or over-long title / a blank purpose, and
 * 200 for the lead — after which the log header and the list show the new values, the sealed header is unchanged, and an
 * audit event records before and after.
 */
class ControlApiInvestigationEditTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"title\":\"Rehearsal run\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String OWNER = "Bearer owner", MEMBER = "Bearer member", STRANGER = "Bearer stranger", NOCAP = "Bearer nocap";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private static void subjects() {
        Set<String> all = Set.of("canManageIncidents");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", all));
            case NOCAP -> Optional.of(new Subject("analyst-1", Set.of()));
            case MEMBER -> Optional.of(new Subject("analyst-2", all));   // granted the analyst role below
            case STRANGER -> Optional.of(new Subject("analyst-3", all));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('a','b','voice'),('a','c','sms'),('b','d','voice'))"
                            + " AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertTrue(r.statusCode() / 100 == 2, method + " " + path + " → " + r.statusCode() + " " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body, auth).statusCode();
    }

    @Test
    void everyGateIsArmedAndTheLeadRenamesAndRestatesThePurpose(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            ok(c, "POST", "/inv/investigations", CREATE, OWNER);
            ok(c, "POST", INV + "/members", "{\"subject\":\"analyst-2\",\"role\":\"analyst\"}", OWNER);
            Path header = root.resolve("audit/snapshots/investigations/inv-a/header.json");
            String sealed = Files.readString(header);
            String rename = "{\"title\":\"Ring around b\",\"purpose\":\"Fraud referral FR-10\"}";

            assertEquals(401, status(c, "PATCH", INV, rename, null), "no credential");
            assertEquals(403, status(c, "PATCH", INV, rename, NOCAP), "no canManageIncidents");
            assertEquals(403, status(c, "PATCH", INV, rename, MEMBER), "an analyst member reads, only a lead edits");
            assertEquals(404, status(c, "PATCH", INV, rename, STRANGER), "not a member: reads as absent");
            assertEquals(404, status(c, "PATCH", "/inv/investigations/inv-nope", rename, OWNER), "unknown id");
            assertEquals(422, status(c, "PATCH", INV, "{}", OWNER), "names neither field");
            assertEquals(422, status(c, "PATCH", INV, "{\"title\":\"  \"}", OWNER), "blank title");
            assertEquals(422, status(c, "PATCH", INV, "{\"title\":\"" + "x".repeat(201) + "\"}", OWNER), "title over 200 chars");
            assertEquals(422, status(c, "PATCH", INV, "{\"purpose\":\"\"}", OWNER), "blank purpose");
            assertEquals("Rehearsal run", ok(c, "GET", INV + "/log", null, OWNER).get("header").get("title").asText(),
                    "a refusal changes nothing");

            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            JsonNode out;
            try {
                out = ok(c, "PATCH", INV, rename, OWNER);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            assertEquals("Ring around b", out.get("title").asText());
            assertEquals("Fraud referral FR-10", out.get("purpose").asText());
            assertEquals("analyst-1", out.get("updatedBy").asText());

            JsonNode h = ok(c, "GET", INV + "/log", null, OWNER).get("header");
            assertEquals("Ring around b", h.get("title").asText(), "the log header shows the new title");
            assertEquals("Fraud referral FR-10", h.get("purpose").asText());
            JsonNode listed = ok(c, "GET", "/inv/investigations", null, OWNER).get("items").get(0);
            assertEquals("Ring around b", listed.get("title").asText(), "File ▸ Open… shows the new title");
            assertEquals(sealed, Files.readString(header), "the sealed header is never rewritten");

            ok(c, "PATCH", INV, "{\"title\":\"Only the title\"}", OWNER);
            h = ok(c, "GET", INV + "/log", null, OWNER).get("header");
            assertEquals("Only the title", h.get("title").asText());
            assertEquals("Fraud referral FR-10", h.get("purpose").asText(), "a title-only edit keeps the purpose");

            List<Event> mine = seen.stream().filter(e -> e.type().equals(LinkEventTypes.LINK_INVESTIGATION_EDITED)).toList();
            assertEquals(1, mine.size());
            Map<String, String> a = mine.get(0).attributes();
            assertEquals("inv-a", a.get("investigationId"));
            assertEquals("title,purpose", a.get("changed"));
            assertEquals("Rehearsal run", a.get("previousTitle"));
            assertEquals("Fraud referral FR-9", a.get("previousPurpose"));
            assertEquals("analyst-1", a.get(com.gamma.audit.AuditAttrs.ACTOR));
        }
    }
}
