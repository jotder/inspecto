package com.gamma.intelligence;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.InlineArtifact;
import com.eoiagent.core.RunId;
import com.eoiagent.model.StubLlmGateway;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the AGT-5 (P0) session lifecycle, deterministic and offline via
 * {@link StubLlmGateway} — no live Ollama required (CPU-only CI).
 */
class InspectoIntelligenceAgentTest {

    private InspectoIntelligenceAgent open(StubLlmGateway gateway) {
        InspectoIntelligenceAgent agent = new InspectoIntelligenceAgent(gateway);
        agent.init(new CollectorService(List.of(), 3600, 1));
        agent.start();
        return agent;
    }

    @Test
    void openSessionThenAskReturnsTheScriptedAnswer() {
        StubLlmGateway gateway = StubLlmGateway.builder()
                .defaultReplyText("Ingestion runs nightly via the scheduler.")
                .build();
        InspectoIntelligenceAgent agent = open(gateway);
        try {
            AgentSessionResult session = agent.openSession(new AgentSessionRequest("analyst", Map.of()));
            assertNotNull(session.sessionId());

            AgentAskResult answer = agent.ask(session.sessionId(),
                    new AgentAskRequest("How does ingestion work?", Map.of()));
            assertEquals("TEXT", answer.kind());
            assertTrue(answer.text().contains("nightly"));
        } finally {
            agent.close();
        }
    }

    @Test
    void askOnAnUnknownSessionThrowsIllegalArgument() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> agent.ask("does-not-exist", new AgentAskRequest("hi", Map.of())));
        } finally {
            agent.close();
        }
    }

    @Test
    void openSessionAcceptsAKnownGoalKindAndRejectsAnUnknownOne() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        try {
            AgentSessionResult session = agent.openSession(
                    new AgentSessionRequest("analyst", Map.of(), "INVESTIGATION"));
            assertNotNull(session.sessionId(), "a valid goal kind opens a session");

            assertThrows(IllegalArgumentException.class,
                    () -> agent.openSession(new AgentSessionRequest("analyst", Map.of(), "NOT_A_KIND")));
        } finally {
            agent.close();
        }
    }

    // ─── AGT-6a A1: runTool — deterministic single-tool dispatch against the REAL belt ───

    @Test
    void runToolInvokesARealDraftToolAndReturnsItsFindingsVerbatim() {
        // No gateway scripting: runTool must not involve the model at all. The draft comes back as
        // structure (clean/findings/draft) so a pane can diff and apply it.
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("unused").build());
        try {
            Map<String, Object> result = agent.runTool("component_draft",
                    Map.of("kind", "expectation", "config", Map.of()), "run-1").orElseThrow();
            assertEquals(Boolean.TRUE, result.get("ok"), "a draft with findings is still a successful call");

            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) result.get("value");
            assertEquals("expectation", value.get("kind"));
            assertEquals(Boolean.FALSE, value.get("clean"), "an empty expectation config cannot be clean");
            assertFalse(((List<?>) value.get("findings")).isEmpty(), "findings anchor the repair loop");
        } finally {
            agent.close();
        }
    }

    @Test
    void runToolRefusesEveryMutatingToolOnTheRealBelt() {
        // THE draft-only invariant, asserted against the real ToolSpecs rather than a fake: every
        // mutating tool the pack registers must be refused. A new act tool is covered automatically.
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("unused").build());
        try {
            List<String> mutating = new com.gamma.intelligence.pack.InspectoPack(
                    new CollectorService(List.of(), 3600, 1)).toolProvider().tools().stream()
                    .filter(t -> t.spec().mutating())
                    .map(t -> t.spec().name())
                    .toList();
            assertFalse(mutating.isEmpty(), "the act belt should not be empty — otherwise this proves nothing");

            for (String tool : mutating) {
                IllegalStateException refused = assertThrows(IllegalStateException.class,
                        () -> agent.runTool(tool, Map.of(), "run-1"),
                        tool + " is mutating and must not be invocable through runTool");
                assertTrue(refused.getMessage().contains("mutating"));
            }
        } finally {
            agent.close();
        }
    }

    @Test
    void runToolOnAnUnknownToolIsEmpty() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("unused").build());
        try {
            assertTrue(agent.runTool("no_such_tool", Map.of(), "run-1").isEmpty(),
                    "an unknown tool is empty — the control route maps that to 404");
        } finally {
            agent.close();
        }
    }

    @Test
    void runToolReportsAnExpectedToolFailureAsNotOk() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("unused").build());
        try {
            // Missing the required "kind" — the tools never throw, so this is an ok=false result (→ 422).
            Map<String, Object> result = agent.runTool("component_draft", Map.of(), "run-1").orElseThrow();
            assertEquals(Boolean.FALSE, result.get("ok"));
            assertNotNull(result.get("error"));
        } finally {
            agent.close();
        }
    }

    @Test
    void closeIsCleanAndIdempotent() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        agent.close();
        agent.close(); // idempotent, no throw
    }

    @Test
    void recentCasesAndCaseByIdProjectTheStore() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        try {
            agent.caseStore().add(new com.gamma.intelligence.investigation.Case(
                    "case-1", "incident:1", Map.of("type", "pipeline.batch.failed"),
                    List.of(), List.of(), "open", List.of(), java.time.Instant.now()));

            List<Map<String, Object>> recent = agent.recentCases(50);
            assertEquals(1, recent.size());
            assertEquals("case-1", recent.get(0).get("id"));

            assertEquals("open", agent.caseById("case-1").orElseThrow().get("outcome"));
            assertTrue(agent.caseById("nope").isEmpty());
        } finally {
            agent.close();
        }
    }

    @Test
    void caseFeedbackIsRecordedValidatedAndFoldedIntoTheCaseView() {
        InspectoIntelligenceAgent agent = open(StubLlmGateway.builder().defaultReplyText("ok").build());
        try {
            agent.caseStore().add(new com.gamma.intelligence.investigation.Case(
                    "case-1", "incident:1", Map.of("type", "pipeline.batch.failed"),
                    List.of(), List.of(), "open", List.of(), java.time.Instant.now()));

            // Unknown case → empty (route maps to 404); a known case records + echoes the stored view.
            assertTrue(agent.recordCaseFeedback("nope", Map.of("rating", "helpful"), "alice").isEmpty());
            Map<String, Object> stored = agent.recordCaseFeedback(
                    "case-1", Map.of("rating", "helpful", "note", "fixed it"), "alice").orElseThrow();
            assertEquals("HELPFUL", stored.get("rating"));
            assertEquals("alice", stored.get("submittedBy"));

            // A bad rating value throws (the route maps that to 400).
            assertThrows(IllegalArgumentException.class,
                    () -> agent.recordCaseFeedback("case-1", Map.of("rating", "banana"), "alice"));

            // Feedback is folded into the case's detail view and listed in the recent feed.
            Object folded = agent.caseById("case-1").orElseThrow().get("feedback");
            assertTrue(folded instanceof List<?> l && l.size() == 1);
            assertEquals(1, agent.recentCaseFeedback(50).size());
        } finally {
            agent.close();
        }
    }

    // S4: no live eoiagent session/tool produces an INLINE_ARTIFACT answer today (checked
    // DefaultAgentSession, eoiagent-examples, eoiagent-app-reference — no producer anywhere), so
    // these two tests construct the AgentAnswer/InlineArtifact directly and drive the package-private
    // toResult(...) test seam, bypassing the live AgentSession path entirely.

    @Test
    void toResultParsesAValidInlineArtifactIntoTheA2uiMap() {
        AgentAnswer answer = new AgentAnswer(AnswerKind.INLINE_ARTIFACT, "here's a chart",
                new InlineArtifact("application/vnd.a2ui+json", "title",
                        "{\"kind\":\"chart\",\"config\":{}}".getBytes(StandardCharsets.UTF_8), Map.of()),
                null, List.of(), new RunId("run-1"));

        AgentAskResult result = InspectoIntelligenceAgent.toResult(answer);

        assertEquals(Map.of("kind", "chart", "config", Map.of()), result.artifact());
    }

    @Test
    void toResultDropsAnArtifactThatFailsValidationRatherThanThrowing() {
        AgentAnswer wrongMimeType = new AgentAnswer(AnswerKind.INLINE_ARTIFACT, "here's a chart",
                new InlineArtifact("text/plain", "title",
                        "{\"kind\":\"chart\",\"config\":{}}".getBytes(StandardCharsets.UTF_8), Map.of()),
                null, List.of(), new RunId("run-2"));
        assertNull(InspectoIntelligenceAgent.toResult(wrongMimeType).artifact());

        AgentAnswer malformedJson = new AgentAnswer(AnswerKind.INLINE_ARTIFACT, "here's a chart",
                new InlineArtifact("application/vnd.a2ui+json", "title",
                        "not json".getBytes(StandardCharsets.UTF_8), Map.of()),
                null, List.of(), new RunId("run-3"));
        assertNull(InspectoIntelligenceAgent.toResult(malformedJson).artifact());

        AgentAnswer unknownKind = new AgentAnswer(AnswerKind.INLINE_ARTIFACT, "here's a chart",
                new InlineArtifact("application/vnd.a2ui+json", "title",
                        "{\"kind\":\"unknown-kind\"}".getBytes(StandardCharsets.UTF_8), Map.of()),
                null, List.of(), new RunId("run-4"));
        assertNull(InspectoIntelligenceAgent.toResult(unknownKind).artifact());
    }
}
