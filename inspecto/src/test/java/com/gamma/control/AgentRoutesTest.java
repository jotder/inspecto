package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.intelligence.AgentAnswerSink;
import com.gamma.intelligence.AgentAskRequest;
import com.gamma.intelligence.AgentAskResult;
import com.gamma.intelligence.AgentSessionRequest;
import com.gamma.intelligence.AgentSessionResult;
import com.gamma.intelligence.spi.IntelligenceAgent;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for the AGT-5 (P0) {@code /agent/*} routes over real HTTP, against a fake {@link IntelligenceAgent}. */
class AgentRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, IntelligenceAgent agent) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        if (agent != null) svc.registerIntelligenceAgent(agent);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void agentRoutesReturn503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions", "{}");
            assertEquals(503, r.statusCode());
        }
    }

    @Test
    void openSessionThenAskRoundTrips(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions",
                    "{\"role\":\"analyst\",\"page\":{\"pageId\":\"overview\"}}");
            assertEquals(200, opened.statusCode());
            JsonNode openedBody = V1Body.of(opened.body());
            String sessionId = openedBody.get("sessionId").asText();
            assertFalse(sessionId.isBlank());

            HttpResponse<String> asked = send(ctx.port(), "POST",
                    "/agent/sessions/" + sessionId + "/ask", "{\"question\":\"How does ingestion work?\"}");
            assertEquals(200, asked.statusCode());
            JsonNode askedBody = V1Body.of(asked.body());
            assertEquals("TEXT", askedBody.get("kind").asText());
            assertTrue(askedBody.get("text").asText().contains("How does ingestion work?"));
        }
    }

    @Test
    void askOnAnUnknownSessionIs404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST",
                    "/agent/sessions/does-not-exist/ask", "{\"question\":\"hi\"}");
            assertEquals(404, r.statusCode());
        }
    }

    @Test
    void askStreamRoundTripsAsServerSentEvents(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = V1Body.of(opened.body()).get("sessionId").asText();

            HttpResponse<String> streamed = send(ctx.port(), "POST",
                    "/agent/sessions/" + sessionId + "/ask/stream", "{\"question\":\"stream this\"}");
            assertEquals(200, streamed.statusCode());
            assertEquals("text/event-stream", streamed.headers().firstValue("Content-Type").orElse(null));
            assertTrue(streamed.body().contains("event: complete"));
            assertTrue(streamed.body().contains("echo: stream this"));
        }
    }

    @Test
    void askStreamEmitsAnArtifactFrameBeforeComplete(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = V1Body.of(opened.body()).get("sessionId").asText();

            HttpResponse<String> streamed = send(ctx.port(), "POST",
                    "/agent/sessions/" + sessionId + "/ask/stream", "{\"question\":\"stream this\"}");
            assertEquals(200, streamed.statusCode());
            String body = streamed.body();
            int artifactIdx = body.indexOf("event: artifact");
            int completeIdx = body.indexOf("event: complete");
            assertTrue(artifactIdx >= 0, "expected an event: artifact frame");
            assertTrue(artifactIdx < completeIdx, "artifact frame must precede the complete frame");

            // Extract the data: line belonging to the artifact frame and parse it (Map.of()'s
            // iteration order is unspecified/randomized per JVM run, so compare structurally).
            String afterArtifact = body.substring(artifactIdx);
            String dataPrefix = "data: ";
            int dataIdx = afterArtifact.indexOf(dataPrefix);
            String artifactJson = afterArtifact.substring(dataIdx + dataPrefix.length(),
                    afterArtifact.indexOf('\n', dataIdx));
            JsonNode artifactNode = JSON.readTree(artifactJson);
            assertEquals("chart", artifactNode.get("kind").asText());
            assertTrue(artifactNode.get("config").isObject());
        }
    }

    @Test
    void askStreamOnAnUnknownSessionIsAnErrorEventNotA404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST",
                    "/agent/sessions/does-not-exist/ask/stream", "{\"question\":\"hi\"}");
            assertEquals(200, r.statusCode()); // headers are already committed by the time the error is known
            assertTrue(r.body().contains("event: error"));
        }
    }

    @Test
    void askWithoutAQuestionIs400(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = V1Body.of(opened.body()).get("sessionId").asText();
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions/" + sessionId + "/ask", "{}");
            assertEquals(400, r.statusCode());
        }
    }

    @Test
    void openSessionPassesAGoalKindThroughToTheAgent(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions",
                    "{\"role\":\"analyst\",\"goalKind\":\"INVESTIGATION\"}");
            assertEquals(200, r.statusCode());
            assertEquals("INVESTIGATION", agent.lastGoalKind);
        }
    }

    @Test
    void openSessionWithAnUnknownGoalKindIs400(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions",
                    "{\"role\":\"analyst\",\"goalKind\":\"NOT_A_KIND\"}");
            assertEquals(400, r.statusCode());
        }
    }

    @Test
    void casesRouteIs503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/cases", null).statusCode());
            assertEquals(503, send(ctx.port(), "GET", "/agent/cases/case-1", null).statusCode());
        }
    }

    @Test
    void recentCasesReturnsTheSeededCasesNewestFirst(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent(Map.of(
                "case-1", Map.of("id", "case-1", "outcome", "open"),
                "case-2", Map.of("id", "case-2", "outcome", "resolved"))))) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/cases", null);
            assertEquals(200, r.statusCode());
            JsonNode cases = V1Body.of(r.body()).get("cases");
            assertEquals(2, cases.size());
        }
    }

    @Test
    void caseByIdReturnsTheMatchingCase(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent(Map.of(
                "case-1", Map.of("id", "case-1", "outcome", "open"))))) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/cases/case-1", null);
            assertEquals(200, r.statusCode());
            assertEquals("open", V1Body.of(r.body()).get("outcome").asText());
        }
    }

    @Test
    void caseByIdOnAnUnknownIdIs404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/cases/does-not-exist", null);
            assertEquals(404, r.statusCode());
        }
    }

    // --- AGT-5 P3: approvals inbox routes ---------------------------------------------------------

    @Test
    void approvalRoutesAre503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/approvals", null).statusCode());
            assertEquals(503, send(ctx.port(), "GET", "/agent/approvals/appr-1", null).statusCode());
            assertEquals(503, send(ctx.port(), "POST", "/agent/approvals/appr-1/decision",
                    "{\"decision\":\"approve\"}").statusCode());
        }
    }

    @Test
    void recentApprovalsReturnsSeededEntries(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "PENDING");
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/approvals", null);
            assertEquals(200, r.statusCode());
            JsonNode approvals = V1Body.of(r.body()).get("approvals");
            assertEquals(1, approvals.size());
            assertEquals("component_apply", approvals.get(0).get("tool").asText());
        }
    }

    @Test
    void approvalByIdReturnsTheMatchOr404(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "PENDING");
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> ok = send(ctx.port(), "GET", "/agent/approvals/appr-1", null);
            assertEquals(200, ok.statusCode());
            assertEquals("PENDING", V1Body.of(ok.body()).get("status").asText());
            assertEquals(404, send(ctx.port(), "GET", "/agent/approvals/nope", null).statusCode());
        }
    }

    @Test
    void decisionApprovesAPendingApproval(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "PENDING");
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/approvals/appr-1/decision",
                    "{\"decision\":\"approve\",\"decidedBy\":\"alice\"}");
            assertEquals(200, r.statusCode());
            JsonNode body = V1Body.of(r.body());
            assertEquals("APPROVED", body.get("status").asText());
            assertEquals("alice", body.get("decidedBy").asText());
        }
    }

    @Test
    void decisionOnAnUnknownOrDecidedApprovalIs404(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "APPROVED"); // already decided
        try (Ctx ctx = open(dir, agent)) {
            assertEquals(404, send(ctx.port(), "POST", "/agent/approvals/appr-1/decision",
                    "{\"decision\":\"approve\"}").statusCode());
            assertEquals(404, send(ctx.port(), "POST", "/agent/approvals/nope/decision",
                    "{\"decision\":\"approve\"}").statusCode());
        }
    }

    @Test
    void decisionWithAMissingOrUnrecognizedVerbIs400(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "PENDING");
        try (Ctx ctx = open(dir, agent)) {
            assertEquals(400, send(ctx.port(), "POST", "/agent/approvals/appr-1/decision",
                    "{\"decision\":\"maybe\"}").statusCode());
            assertEquals(400, send(ctx.port(), "POST", "/agent/approvals/appr-1/decision", "{}").statusCode());
        }
    }

    // --- AGT-5 P4: autonomy policy routes ---------------------------------------------------------

    @Test
    void policyRoutesAre503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/policy", null).statusCode());
            assertEquals(503, send(ctx.port(), "PUT", "/agent/policy", "{}").statusCode());
            assertEquals(503, send(ctx.port(), "POST", "/agent/policy/kill-switch",
                    "{\"engaged\":true}").statusCode());
        }
    }

    @Test
    void getPolicyReturnsTheCurrentView(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/policy", null);
            assertEquals(200, r.statusCode());
            assertFalse(V1Body.of(r.body()).get("killSwitch").asBoolean());
        }
    }

    @Test
    void putPolicyReplacesAndAttributesTheActor(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "PUT", "/agent/policy",
                    "{\"classes\":{\"batch_rerun\":{\"mode\":\"auto\",\"maxPerHour\":3}}}");
            assertEquals(200, r.statusCode());
            JsonNode body = V1Body.of(r.body());
            assertEquals("auto", body.get("classes").get("batch_rerun").get("mode").asText());
            // No X-Agent-Session header → attributed to the calling human actor, not "agent:*".
            assertFalse(body.get("updatedBy").asText().startsWith("agent:"));
        }
    }

    @Test
    void killSwitchEngagesAndDisengages(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> on = send(ctx.port(), "POST", "/agent/policy/kill-switch",
                    "{\"engaged\":true}");
            assertEquals(200, on.statusCode());
            assertTrue(V1Body.of(on.body()).get("killSwitch").asBoolean());

            HttpResponse<String> off = send(ctx.port(), "POST", "/agent/policy/kill-switch",
                    "{\"engaged\":false}");
            assertFalse(V1Body.of(off.body()).get("killSwitch").asBoolean());
        }
    }

    @Test
    void killSwitchWithoutEngagedIs400(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            assertEquals(400, send(ctx.port(), "POST", "/agent/policy/kill-switch", "{}").statusCode());
            assertEquals(400, send(ctx.port(), "POST", "/agent/policy/kill-switch",
                    "{\"engaged\":\"maybe\"}").statusCode());
        }
    }

    // --- AGT-5 P5: case-similarity recall route ---------------------------------------------------

    @Test
    void similarCasesRouteReturnsNeighboursOr404(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent(Map.of("case-1", Map.of("id", "case-1")));
        agent.seedSimilar("case-1", List.of(Map.of("id", "case-2", "similarity", 0.5)));
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> ok = send(ctx.port(), "GET", "/agent/cases/case-1/similar", null);
            assertEquals(200, ok.statusCode());
            JsonNode similar = V1Body.of(ok.body()).get("similar");
            assertEquals(1, similar.size());
            assertEquals("case-2", similar.get(0).get("id").asText());
            // The greedy /agent/cases/(.+) must not shadow /similar (registration-order match).
            assertEquals(404, send(ctx.port(), "GET", "/agent/cases/nope/similar", null).statusCode());
        }
    }

    // --- AGT-5 P5: Case feedback routes -----------------------------------------------------------

    @Test
    void feedbackPostValidatesAndRecords(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent(Map.of("case-1", Map.of("id", "case-1")));
        try (Ctx ctx = open(dir, agent)) {
            // Missing rating → 400.
            assertEquals(400, send(ctx.port(), "POST", "/agent/cases/case-1/feedback", "{}").statusCode());
            // Unknown case → 404.
            assertEquals(404, send(ctx.port(), "POST", "/agent/cases/nope/feedback",
                    "{\"rating\":\"helpful\"}").statusCode());
            // Valid → 200 + stored view.
            HttpResponse<String> ok = send(ctx.port(), "POST", "/agent/cases/case-1/feedback",
                    "{\"rating\":\"helpful\",\"note\":\"good\"}");
            assertEquals(200, ok.statusCode());
            assertEquals("HELPFUL", V1Body.of(ok.body()).get("rating").asText());
        }
    }

    @Test
    void feedbackListDegradesEmptyAnd503WhenModuleAbsent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/feedback", null).statusCode());
        }
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent(Map.of("case-1", Map.of("id", "case-1")));
        try (Ctx ctx = open(dir, agent)) {
            send(ctx.port(), "POST", "/agent/cases/case-1/feedback", "{\"rating\":\"not_helpful\"}");
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/feedback", null);
            assertEquals(200, r.statusCode());
            assertEquals(1, V1Body.of(r.body()).get("feedback").size());
        }
    }

    @Test
    void actionsRoutesDegradeTo503OnlyWhenModuleAbsentAndReturnSeededEntries(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/actions", null).statusCode());
        }
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedAction("act-1", "batch_rerun", "SUCCEEDED");
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/actions", null);
            assertEquals(200, r.statusCode());
            JsonNode actions = V1Body.of(r.body()).get("actions");
            assertEquals(1, actions.size());
            assertEquals("batch_rerun", actions.get(0).get("actionClass").asText());
            assertEquals(200, send(ctx.port(), "GET", "/agent/actions/act-1", null).statusCode());
            assertEquals(404, send(ctx.port(), "GET", "/agent/actions/nope", null).statusCode());
        }
    }

    // ─── AGT-6a A1: POST /agent/tools/{name} — one test per gate, plus the happy path ───

    @Test
    void toolDispatchIs503WhenTheModuleIsAbsent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "POST", "/agent/tools/component_draft", "{}").statusCode());
        }
    }

    @Test
    void toolDispatchReturnsTheToolsOwnResultVerbatim(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/component_draft",
                    "{\"args\":{\"kind\":\"expectation\",\"config\":{\"id\":\"amt-nonneg\"}}}");
            assertEquals(200, r.statusCode());
            JsonNode body = V1Body.of(r.body());
            // The draft + anchored findings arrive as structure, not prose — this is the whole point of
            // the route: a pane can diff and apply this, which it cannot do with an /ask answer.
            assertEquals("expectation", body.get("kind").asText());
            assertFalse(body.get("clean").asBoolean());
            assertEquals("column", body.get("findings").get(0).get("fieldPath").asText());
            assertEquals("amt-nonneg", body.get("draft").get("config").get("id").asText());
            // args are passed through untouched, and the audited actor rides along for attribution
            assertEquals("amt-nonneg",
                    ((Map<?, ?>) agent.lastToolArgs.get("config")).get("id"));
            assertEquals("appUser", agent.lastToolSession, "the audited actor default");

            // ...and an agent session token propagates as the attribution the SPI promises
            HttpRequest attributed = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + ctx.port() + "/api/v1/agent/tools/component_draft"))
                    .header("Content-Type", "application/json")
                    .header("X-Agent-Session", "run-42")
                    .POST(BodyPublishers.ofString("{\"args\":{\"kind\":\"expectation\",\"config\":{}}}"))
                    .build();
            assertEquals(200, client.send(attributed, BodyHandlers.ofString()).statusCode());
            assertEquals("agent:run-42", agent.lastToolSession);
        }
    }

    @Test
    void aDraftWithFindingsIs200NotAnError(@TempDir Path dir) throws Exception {
        // Guards the contract the surface depends on: findings are the payload, not a failure. If this
        // ever becomes 4xx the inline surface loses the repair loop it exists to render.
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/component_draft",
                    "{\"args\":{\"kind\":\"expectation\",\"config\":{}}}");
            assertEquals(200, r.statusCode());
            assertTrue(V1Body.of(r.body()).get("findings").size() > 0);
        }
    }

    @Test
    void anUnknownToolIs404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/no_such_tool", "{}");
            assertEquals(404, r.statusCode());
            assertTrue(r.body().contains("no_such_tool"));
        }
    }

    @Test
    void aMutatingToolIs403AndIsNeverInvoked(@TempDir Path dir) throws Exception {
        // THE draft-only invariant. The act belt is reachable only through the approval spine; if this
        // gate regresses, the inline surface becomes a second, ungated way to mutate state.
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/component_apply",
                    "{\"args\":{\"type\":\"expectation\",\"id\":\"amt-nonneg\",\"config\":{}}}");
            assertEquals(403, r.statusCode());
            assertTrue(r.body().contains("mutating"));
            assertNull(agent.appliedArgs, "a mutating tool must be refused before it is invoked");
        }
    }

    @Test
    void anExpectedToolFailureIs422WithTheToolsMessage(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/component_draft", "{}");
            assertEquals(422, r.statusCode());
            assertTrue(r.body().contains("kind is required"));
        }
    }

    // ─── AGT-6a A5.1: POST /agent/tools/{name}/derive — the NL hop, one test per gate ───

    @Test
    void deriveIsNotSwallowedByTheGreedyToolRoute(@TempDir Path dir) throws Exception {
        // THE registration-order trap. /agent/tools/(.+) is greedy and would match "query_author/derive"
        // as a tool NAME, answering 404 for a route that exists. If this fails, the derive route was
        // registered after its sibling.
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/query_author/derive",
                    "{\"prompt\":\"orders over 100\",\"args\":{\"dataset\":\"orders\"}}");
            assertEquals(200, r.statusCode());
            assertNull(agent.lastToolArgs, "the A1 route must not have handled this");
            assertEquals("orders over 100", agent.lastDerivePrompt);
        }
    }

    @Test
    void deriveEchoesDerivedArgsAndLetsThePaneWinOnIdentity(@TempDir Path dir) throws Exception {
        // derivedArgs is non-negotiable: with a model in the loop the operator must see what their
        // sentence became before they Apply. The pane's dataset must survive the model's opinion.
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/query_author/derive",
                    "{\"prompt\":\"orders over 100\",\"args\":{\"dataset\":\"orders\"}}");
            assertEquals(200, r.statusCode());
            JsonNode body = V1Body.of(r.body());
            assertEquals("orders", body.get("derivedArgs").get("dataset").asText());
            assertEquals("amount", body.get("derivedArgs").get("when").get("field").asText());
            assertEquals("sql", body.get("value").get("type").asText());
        }
    }

    @Test
    void deriveIs503WhenTheModuleIsAbsent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "POST", "/agent/tools/query_author/derive",
                    "{\"prompt\":\"x\"}").statusCode());
        }
    }

    @Test
    void deriveIs503WhenNoModelIsConfigured(@TempDir Path dir) throws Exception {
        // Distinct from every 422 below on purpose. "This deployment has no model" is not the operator's
        // sentence being wrong, and telling them to rephrase would be a lie they cannot act on.
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.deriveNoModel = true;
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/query_author/derive",
                    "{\"prompt\":\"orders over 100\"}");
            assertEquals(503, r.statusCode());
            assertTrue(r.body().contains("no local model is configured"));
        }
    }

    @Test
    void deriveRefusesAMutatingToolBeforeAnyModelCall(@TempDir Path dir) throws Exception {
        // The A1 draft-only invariant, inherited. NL input must not become a second way to act.
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/component_apply/derive",
                    "{\"prompt\":\"apply it\"}");
            assertEquals(403, r.statusCode());
            assertTrue(r.body().contains("mutating"));
            assertNull(agent.lastDerivePrompt, "a mutating tool must be refused before the model is called");
            assertNull(agent.appliedArgs);
        }
    }

    @Test
    void deriveIs400WithoutAPrompt(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            assertEquals(400, send(ctx.port(), "POST", "/agent/tools/query_author/derive",
                    "{\"args\":{\"dataset\":\"orders\"}}").statusCode());
            assertEquals(400, send(ctx.port(), "POST", "/agent/tools/query_author/derive",
                    "{\"prompt\":\"   \"}").statusCode(), "blank is not a prompt");
        }
    }

    @Test
    void deriveIs404ForAnUnknownTool(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/tools/no_such_tool/derive",
                    "{\"prompt\":\"x\"}");
            assertEquals(404, r.statusCode());
            assertTrue(r.body().contains("no_such_tool"));
        }
    }

    @Test
    void theTwoModelFailuresAre422AndSayDifferentThings(@TempDir Path dir) throws Exception {
        // Malformed arguments and "answered in prose, never called the tool" are different local-model
        // failures. Both are retryable, so both are 422 — but an operator debugging one must not be
        // reading the other's message.
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> malformed = send(ctx.port(), "POST", "/agent/tools/query_author/derive",
                    "{\"prompt\":\"bad json\"}");
            assertEquals(422, malformed.statusCode());
            assertTrue(malformed.body().contains("malformed arguments"));

            HttpResponse<String> silent = send(ctx.port(), "POST", "/agent/tools/query_author/derive",
                    "{\"prompt\":\"silent\"}");
            assertEquals(422, silent.statusCode());
            assertTrue(silent.body().contains("did not produce arguments"));
            assertFalse(silent.body().contains("malformed"), "the two failures must not share a message");
        }
    }

    /** A deterministic in-memory agent — no eoiagent/model dependency needed in the core test tree. */
    private static final class FakeIntelligenceAgent implements IntelligenceAgent {
        // Stand-in for the eoiagent GoalKind enum (not on the core test classpath).
        private static final java.util.Set<String> KNOWN_GOAL_KINDS =
                java.util.Set.of("QA", "ANALYSIS", "SQL_GEN", "PIPELINE_AUTHOR", "INVESTIGATION", "OPERATIONAL_ACTION");

        private final Map<String, String> sessions = new ConcurrentHashMap<>();
        private final Map<String, Object> cases;
        // Insertion-ordered so recentApprovals is deterministic; entries mutate on decision.
        private final Map<String, Map<String, Object>> approvals = new java.util.LinkedHashMap<>();
        volatile String lastGoalKind;

        FakeIntelligenceAgent() { this(Map.of()); }
        FakeIntelligenceAgent(Map<String, Object> cases) { this.cases = cases; }

        /** Seed one approval view (mirrors the {@code Approval.toView()} shape the real agent emits). */
        void seedApproval(String id, String tool, String status) {
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("id", id);
            view.put("tool", tool);
            view.put("status", status);
            approvals.put(id, view);
        }

        // P4: a trivial in-memory policy the route tests exercise (echo-and-store, no real engine).
        private Map<String, Object> policy = new java.util.LinkedHashMap<>(
                Map.of("killSwitch", false, "classes", new java.util.LinkedHashMap<>()));

        @Override public String name() { return "fake-intelligence"; }
        @Override public void init(CollectorService service) {}

        // AGT-6a A1: a stand-in belt for POST /agent/tools/{name}. "component_draft" mirrors the real
        // tool's result shape (ok=true even when findings exist); "component_apply" stands in for the
        // mutating belt and must be refused before invocation; anything else is unknown.
        volatile Map<String, Object> lastToolArgs;
        volatile String lastToolSession;
        volatile Map<String, Object> appliedArgs;

        // AGT-6a A5.1: a stand-in for the derive hop. The route's job is the gate order and the
        // derivedArgs echo, so the "model" here is a fixed translation keyed off the prompt: "bad json"
        // stands in for malformed output, "silent" for a model that answered in prose with no tool call,
        // and anything else derives a condition. deriveNoModel flips the deployment-has-no-model case.
        volatile boolean deriveNoModel;
        volatile String lastDerivePrompt;
        volatile Map<String, Object> lastDeriveArgs;

        @Override
        public java.util.Optional<Map<String, Object>> deriveTool(String name, String prompt,
                                                                  Map<String, Object> args, String session) {
            if ("component_apply".equals(name)) {   // refused BEFORE any model call, like the real impl
                throw new IllegalStateException("tool 'component_apply' is mutating and is not invocable directly");
            }
            if (!"query_author".equals(name)) return java.util.Optional.empty();
            if (deriveNoModel) {
                throw new UnsupportedOperationException("no local model is configured, so a natural-language"
                        + " request cannot be interpreted");
            }
            this.lastDerivePrompt = prompt;
            this.lastDeriveArgs = args;
            this.lastToolSession = session;
            if ("bad json".equals(prompt))
                return java.util.Optional.of(Map.of("ok", false,
                        "error", "the model returned malformed arguments for 'query_author'"));
            if ("silent".equals(prompt))
                return java.util.Optional.of(Map.of("ok", false,
                        "error", "the model did not produce arguments for 'query_author'"));
            Map<String, Object> derived = new java.util.LinkedHashMap<>();
            derived.put("when", Map.of("op", ">", "field", "amount", "value", 100));
            derived.putAll(args);                    // the pane's identity fields win, as the SPI promises
            return java.util.Optional.of(Map.of("ok", true,
                    "value", Map.of("type", "sql", "text", "SELECT * FROM orders WHERE amount > 100"),
                    "derivedArgs", derived));
        }

        @Override
        public java.util.Optional<Map<String, Object>> runTool(String name, Map<String, Object> args, String session) {
            this.lastToolArgs = args;
            this.lastToolSession = session;
            if ("component_apply".equals(name)) {
                throw new IllegalStateException("tool 'component_apply' is mutating and is not invocable directly");
            }
            if (!"component_draft".equals(name)) return java.util.Optional.empty();
            Object kind = args.get("kind");
            if (kind == null) return java.util.Optional.of(Map.of("ok", false, "error", "kind is required"));
            return java.util.Optional.of(Map.of("ok", true, "value", Map.of(
                    "kind", kind,
                    "type", kind,
                    "clean", false,
                    "findings", List.of(Map.of(
                            "severity", "ERROR", "fieldPath", "column", "message", "column is required")),
                    "draft", args)));
        }

        @Override
        public java.util.Optional<Map<String, Object>> autonomyPolicy() {
            return java.util.Optional.of(new java.util.LinkedHashMap<>(policy));
        }

        @Override
        public java.util.Optional<Map<String, Object>> updateAutonomyPolicy(Map<String, Object> body, String by) {
            Map<String, Object> next = new java.util.LinkedHashMap<>(body == null ? Map.of() : body);
            next.putIfAbsent("killSwitch", false);
            next.put("updatedBy", by);
            this.policy = next;
            return java.util.Optional.of(new java.util.LinkedHashMap<>(policy));
        }

        @Override
        public java.util.Optional<Map<String, Object>> setAutonomyKillSwitch(boolean engaged, String by) {
            policy.put("killSwitch", engaged);
            policy.put("updatedBy", by);
            return java.util.Optional.of(new java.util.LinkedHashMap<>(policy));
        }

        // P5: Case feedback the /agent/cases/{id}/feedback + /agent/feedback routes exercise.
        private final List<Map<String, Object>> feedback = new java.util.ArrayList<>();

        @Override
        public java.util.Optional<Map<String, Object>> recordCaseFeedback(String caseId, Map<String, Object> body, String by) {
            if (!cases.containsKey(caseId)) return java.util.Optional.empty(); // unknown case → 404
            String rating = String.valueOf(body.get("rating"));
            if (!"helpful".equalsIgnoreCase(rating) && !"not_helpful".equalsIgnoreCase(rating)) {
                throw new IllegalArgumentException("bad rating"); // → route maps to 400
            }
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("id", "fb-" + feedback.size());
            v.put("caseId", caseId);
            v.put("rating", rating.toUpperCase(java.util.Locale.ROOT));
            v.put("submittedBy", by);
            feedback.add(v);
            return java.util.Optional.of(v);
        }

        @Override
        public List<Map<String, Object>> recentCaseFeedback(int limit) {
            return List.copyOf(feedback);
        }

        // P4 slice 2: the autonomy ledger the /agent/actions routes read.
        private final Map<String, Map<String, Object>> actions = new java.util.LinkedHashMap<>();

        void seedAction(String id, String actionClass, String status) {
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("id", id);
            v.put("actionClass", actionClass);
            v.put("status", status);
            actions.put(id, v);
        }

        @Override
        public List<Map<String, Object>> recentAutonomousActions(int limit) {
            return List.copyOf(actions.values());
        }

        @Override
        public java.util.Optional<Map<String, Object>> autonomousActionById(String id) {
            return java.util.Optional.ofNullable(actions.get(id));
        }

        @Override
        public List<Map<String, Object>> recentApprovals(int limit) {
            return List.copyOf(approvals.values());
        }

        @Override
        public java.util.Optional<Map<String, Object>> approvalById(String id) {
            return java.util.Optional.ofNullable(approvals.get(id));
        }

        @Override
        public java.util.Optional<Map<String, Object>> decideApproval(String id, boolean approve, String decidedBy) {
            Map<String, Object> a = approvals.get(id);
            if (a == null || !"PENDING".equals(a.get("status"))) return java.util.Optional.empty();
            Map<String, Object> updated = new java.util.LinkedHashMap<>(a);
            updated.put("status", approve ? "APPROVED" : "DENIED");
            updated.put("decidedBy", decidedBy);
            approvals.put(id, updated);
            return java.util.Optional.of(updated);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> recentCases(int limit) {
            return cases.values().stream().map(v -> (Map<String, Object>) v).toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public java.util.Optional<Map<String, Object>> caseById(String id) {
            return java.util.Optional.ofNullable((Map<String, Object>) cases.get(id));
        }

        // P5: seeded similarity neighbours the /agent/cases/{id}/similar route reads.
        private final Map<String, List<Map<String, Object>>> similar = new java.util.LinkedHashMap<>();

        void seedSimilar(String caseId, List<Map<String, Object>> neighbours) {
            similar.put(caseId, neighbours);
        }

        @Override
        public List<Map<String, Object>> similarCases(String id, int k) {
            return similar.getOrDefault(id, List.of());
        }

        @Override
        public AgentSessionResult openSession(AgentSessionRequest request) {
            lastGoalKind = request.goalKind();
            if (lastGoalKind != null && !KNOWN_GOAL_KINDS.contains(lastGoalKind)) {
                throw new IllegalArgumentException("unknown goalKind: '" + lastGoalKind + "'"); // → route maps to 400
            }
            String id = UUID.randomUUID().toString();
            sessions.put(id, request.role() == null ? "" : request.role()); // ConcurrentHashMap forbids null values
            return new AgentSessionResult(id, Instant.now().toString());
        }

        @Override
        public AgentAskResult ask(String sessionId, AgentAskRequest request) {
            if (!sessions.containsKey(sessionId)) {
                throw new IllegalArgumentException("unknown session: '" + sessionId + "'");
            }
            return new AgentAskResult("TEXT", "echo: " + request.question(), List.of(), null, null);
        }

        @Override
        public void askStream(String sessionId, AgentAskRequest request, AgentAnswerSink sink) {
            try {
                AgentAskResult result = ask(sessionId, request);
                sink.onArtifact(Map.of("kind", "chart", "config", Map.of()));
                sink.onComplete(result);
            } catch (IllegalArgumentException e) {
                sink.onError(e.getMessage());
            }
        }
    }
}
