package com.gamma.intelligence;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.EmbeddingRequest;
import com.eoiagent.model.EmbeddingResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.model.ModelRole;
import com.eoiagent.model.TokenSink;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-2 verification, 2026-09-29: a read-only user's SESSION could make the model call a gated tool and get
 * rows back, because the in-session gate lived in the external eoiagent platform. The tools now enforce the
 * caller's control-plane capability themselves. This drives the session path the way the verifier's probe did:
 * a scripted model emits one tool call, then answers; what the tool returned is read off the next model
 * request, which is where the platform feeds a tool result back.
 */
class SessionToolGateTest {

    private static final ModelInfo MODEL = new ModelInfo("stub", "scripted", true);
    private static final List<String> GATED = List.of("component_draft", "query_author", "projection_author",
            "kpi_report_builder", "pipeline_author", "anomaly_scan", "suggest_expectations");

    /** Schema-valid arguments per tool: the platform validates them before the tool is invoked. */
    private static final Map<String, Map<String, Object>> ARGS = Map.of(
            "component_draft", Map.of("kind", "expectation", "config", Map.of()),
            "query_author", Map.of("dataset", "orders"),
            "projection_author", Map.of("datasetId", "orders", "columns", List.of("a")),
            "kpi_report_builder", Map.of("dataset", "orders", "title", "t", "measures", List.of()),
            "pipeline_author", Map.of("pipeline", "p"),
            "anomaly_scan", Map.of("table", "status", "column", "rows"),
            "suggest_expectations", Map.of("table", "status", "column", "rows"));

    private final List<CollectorService> services = new ArrayList<>();

    @AfterEach
    void closeServices() {
        services.forEach(CollectorService::close);
        services.clear();
    }

    /** First turn: call {@code tool}. Every later turn: plain text. Records every request it is sent. */
    private static final class OneToolCall implements LlmGateway {
        private final String tool;
        final List<ChatRequest> seen = new ArrayList<>();

        OneToolCall(String tool) { this.tool = tool; }

        @Override
        public ChatResult chat(ChatRequest request) {
            seen.add(request);
            if (seen.size() == 1)
                return new ChatResult(null, List.of(new ToolCall(tool, ARGS.get(tool), new RunId("t"))), MODEL, null);
            return new ChatResult("done", List.of(), MODEL, null);
        }

        @Override public void chatStream(ChatRequest r, TokenSink s) { throw new UnsupportedOperationException(); }
        @Override public EmbeddingResult embed(EmbeddingRequest r) { throw new UnsupportedOperationException(); }
        @Override public ModelInfo activeChatModel() { return MODEL; }
        @Override public boolean isAvailable(ModelRole role) { return true; }
    }

    /** What the model was shown after the tool ran, i.e. every request after the first. */
    private String turnAfterTool(String tool, Set<String> capabilities) {
        OneToolCall model = new OneToolCall(tool);
        InspectoIntelligenceAgent agent = new InspectoIntelligenceAgent(model);
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        services.add(svc);
        agent.init(svc);
        agent.start();
        try {
            // Platform role "admin" on purpose: eoiagent's own in-session gate then lets every tool through, so
            // what is exercised is OUR gate alone. That is the verifier's point: do not rely on the platform's.
            AgentSessionResult s = agent.openSession(new AgentSessionRequest("admin", Map.of(), null, capabilities));
            agent.ask(s.sessionId(), new AgentAskRequest("scan it", Map.of()));
            assertTrue(model.seen.size() >= 2, "the platform must feed the tool result back to the model");
            return model.seen.subList(1, model.seen.size()).toString();
        } finally {
            agent.close();
        }
    }

    @Test
    void aReadOnlySessionIsRefusedEveryGatedTool() {
        for (String tool : GATED) {
            String fedBack = turnAfterTool(tool, Set.of());
            assertTrue(fedBack.contains("tool '" + tool + "' refused: missing capability 'canAuthorWorkbench'"),
                    tool + " must be refused inside a read-only session; the model saw: " + fedBack);
        }
    }

    @Test
    void anAuthorsSessionRunsTheGatedTools() {
        for (String tool : GATED) {
            String fedBack = turnAfterTool(tool, Set.of("canAuthorWorkbench"));
            assertFalse(fedBack.contains("refused"), tool + " must run for an author; the model saw: " + fedBack);
            assertTrue(fedBack.contains("toolName=" + tool), tool + " must have been invoked");
            assertFalse(fedBack.contains("invalid arguments"), tool + ": the scripted arguments must pass the schema,"
                    + " or this test proves nothing; the model saw: " + fedBack);
        }
    }
}
