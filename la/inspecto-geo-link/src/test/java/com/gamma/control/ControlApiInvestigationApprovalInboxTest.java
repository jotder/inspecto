package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /inv/pending} — the four-eyes approver inbox (D-U7), over real HTTP with an armed Authenticator. The inbox
 * lists exactly what {@code approve}/{@code deny} would let the caller decide: never the caller's own request, never an
 * Investigation whose membership record leaves the caller without a lead/reviewer role.
 */
class ControlApiInvestigationApprovalInboxTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001";
    private static final String ROWS = "('" + A + "','27820000002','voice'),('" + A + "','27820000003','sms')";
    private static final String OWNER = "Bearer owner", SELF = "Bearer self", LEAD = "Bearer lead", PLAIN = "Bearer plain",
            OUTSIDER = "Bearer outsider";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case SELF -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canApproveLinkExpansions")));
            case LEAD -> Optional.of(new Subject("lead-1", Set.of("canApproveLinkExpansions")));
            case OUTSIDER -> Optional.of(new Subject("fm.manager", Set.of("canApproveLinkExpansions")));
            case PLAIN -> Optional.of(new Subject("lead-2", Set.of()));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                        "SELECT caller, callee, channel FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)",
                        "2026-10-11T00:00:00Z"));
                new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
                Files.writeString(writeRoot.resolve("link-analysis.toon"), "four_eyes_budget_above: 100\n");
            }
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode data(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** An Investigation owned by analyst-1 with one held expand (p1). */
    private void held(Ctx c, String id, String seed) throws Exception {
        data(c, "POST", "/inv/investigations", "{\"id\":\"" + id + "\",\"title\":\"Case " + id + "\",\"purpose\":\"Fraud referral FR-7\","
                + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}", OWNER);
        data(c, "POST", "/inv/investigations/" + id + "/ops", seed, OWNER);
        JsonNode pending = data(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"expand\",\"budget\":500}", OWNER);
        assertEquals("pending", pending.get("status").asText(), pending.toString());
    }

    private static String seedA() {
        return "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}";
    }

    @Test
    void gatesFailClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, send(c, "GET", "/inv/pending", null, null).statusCode());
            assertEquals(403, send(c, "GET", "/inv/pending", null, PLAIN).statusCode(), "needs canApproveLinkExpansions");
            assertEquals(422, send(c, "GET", "/inv/pending?status=maybe", null, LEAD).statusCode());
            assertEquals(422, send(c, "GET", "/inv/pending?limit=x", null, LEAD).statusCode());
            assertEquals(200, send(c, "GET", "/inv/pending", null, LEAD).statusCode());
        }
    }

    @Test
    void withoutASubjectTheInboxIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(null);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c, "GET", "/inv/pending", null, null);
            assertEquals(403, r.statusCode(), "the requester cannot be told apart without a Subject: " + r.body());
        }
    }

    @Test
    void aReadOnlyControlPlaneIs503(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, send(c, "GET", "/inv/pending", null, LEAD).statusCode());
        }
    }

    @Test
    void anApproverSeesOthersRequestsAndDecidesThemTheRequesterNeverSeesOrDecidesTheirOwn(@TempDir Path cfg,
                                                                                           @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            held(c, "case-a", seedA());

            JsonNode inbox = data(c, "GET", "/inv/pending", null, LEAD);
            assertEquals(1, inbox.get("total").asInt(), inbox.toString());
            JsonNode item = inbox.at("/items/0");
            assertEquals("case-a", item.get("investigationId").asText());
            assertEquals("Case case-a", item.get("title").asText());
            assertEquals("p1", item.get("requestId").asText());
            assertEquals("expand", item.get("kind").asText());
            assertEquals("pending", item.get("status").asText());
            assertEquals("analyst-1", item.get("requestedBy").asText());
            assertFalse(item.get("requestedAt").asText().isBlank());
            assertEquals("Fraud referral FR-7", item.get("purpose").asText());
            assertEquals(500, item.at("/params/budget").asInt());
            assertTrue(item.get("summary").asText().contains("reading at most 500 link rows"), item.toString());
            assertEquals("[\"budget 500 > 100\"]", item.at("/sensitivity/exceeded").toString());

            // The requester — even holding the capability — never sees their own request, and still cannot decide it.
            assertEquals(0, data(c, "GET", "/inv/pending", null, SELF).get("total").asInt());
            assertEquals(403, send(c, "POST", "/inv/investigations/case-a/pending/p1/approve", "{}", SELF).statusCode());

            data(c, "POST", "/inv/investigations/case-a/pending/p1/approve", "{}", LEAD);
            assertEquals(0, data(c, "GET", "/inv/pending", null, LEAD).get("total").asInt(), "decided: out of the pending view");
            JsonNode approved = data(c, "GET", "/inv/pending?status=approved", null, LEAD);
            assertEquals("lead-1", approved.at("/items/0/decidedBy").asText(), approved.toString());

            data(c, "POST", "/inv/investigations/case-a/ops", "{\"op\":\"expand\",\"budget\":700}", OWNER);
            data(c, "POST", "/inv/investigations/case-a/pending/p2/deny", "{\"reason\":\"disproportionate\"}", LEAD);
            JsonNode denied = data(c, "GET", "/inv/pending?status=denied", null, LEAD);
            assertEquals("disproportionate", denied.at("/items/0/reason").asText(), denied.toString());
            assertEquals(2, data(c, "GET", "/inv/pending?status=all", null, LEAD).get("total").asInt());
            assertEquals(1, data(c, "GET", "/inv/pending?status=all&limit=1", null, LEAD).get("items").size());
            assertTrue(data(c, "GET", "/inv/pending?status=all&limit=1", null, LEAD).get("truncated").asBoolean());
        }
    }

    @Test
    void aMembershipRecordLimitsTheInboxToLeadsAndReviewers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            held(c, "case-a", seedA());
            // Owner-only (no membership record): any approver may decide, so a non-member approver sees it.
            assertEquals(1, data(c, "GET", "/inv/pending", null, OUTSIDER).get("total").asInt());

            data(c, "POST", "/inv/investigations/case-a/members", "{\"subject\":\"lead-1\",\"role\":\"analyst\"}", OWNER);
            assertEquals(0, data(c, "GET", "/inv/pending", null, LEAD).get("total").asInt(), "an analyst member cannot approve");
            assertEquals(0, data(c, "GET", "/inv/pending", null, OUTSIDER).get("total").asInt(), "nor can a non-member now");
            assertEquals(403, send(c, "POST", "/inv/investigations/case-a/pending/p1/approve", "{}", LEAD).statusCode(),
                    "the inbox agrees with the decision gate");

            data(c, "POST", "/inv/investigations/case-a/members", "{\"subject\":\"lead-1\",\"role\":\"reviewer\"}", OWNER);
            assertEquals(1, data(c, "GET", "/inv/pending", null, LEAD).get("total").asInt(), "a reviewer may decide");
        }
    }

    @Test
    void entityIdsAreMaskedPerTheSpace(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            data(c, "POST", "/inv/investigations", "{\"id\":\"case-m\",\"title\":\"About " + A + "\",\"purpose\":\"Referral on " + A + "\","
                    + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", OWNER);
            JsonNode seeded = data(c, "POST", "/inv/investigations/case-m/ops",
                    "{\"op\":\"seed\",\"ids\":[\"" + A + "\"],\"entityType\":\"msisdn\"}", OWNER);
            String token = seeded.at("/delta/admitted/0").asText();
            assertTrue(token.startsWith("masked:"), seeded.toString());
            data(c, "POST", "/inv/investigations/case-m/ops", "{\"op\":\"expand\",\"ids\":[\"" + token + "\"],\"budget\":500}", OWNER);

            HttpResponse<String> r = send(c, "GET", "/inv/pending", null, LEAD);
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(r.body().contains(A), "no raw entity id in the inbox: " + r.body());
            assertTrue(r.body().contains(token), r.body());
        }
    }
}
