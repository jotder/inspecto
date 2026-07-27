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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGT-6a A5.2 — the bounded repair loop for {@code component_draft}.
 *
 * <p>{@code query_author}'s NL path is a single hop; this one is a loop, because {@code component_draft}
 * has no authoring logic and one turn reliably yields a probably-invalid config. What is pinned here is the
 * loop's <b>termination and hand-over</b> behaviour — the properties that decide whether it helps an
 * operator or wastes their time and then hands back something worse than it had.
 */
class ComponentDraftRepairLoopTest {

    private static final ModelInfo MODEL = new ModelInfo("stub", "scripted", true);

    /** A gateway that answers each successive {@code chat} with the next scripted config. */
    private static final class ScriptedModel implements LlmGateway {
        private final List<Map<String, Object>> configs;
        private final List<ChatRequest> seen = new ArrayList<>();
        private int turn;

        @SafeVarargs
        ScriptedModel(Map<String, Object>... configs) { this.configs = List.of(configs); }

        @Override
        public ChatResult chat(ChatRequest request) {
            seen.add(request);
            // past the script the model keeps repeating its last answer, as a stuck model would
            Map<String, Object> config = configs.get(Math.min(turn++, configs.size() - 1));
            return new ChatResult(null,
                    List.of(new ToolCall("component_draft", Map.of("config", config), new RunId("t"))),
                    MODEL, null);
        }

        @Override public void chatStream(ChatRequest r, TokenSink s) { throw new UnsupportedOperationException(); }
        @Override public EmbeddingResult embed(EmbeddingRequest r) { throw new UnsupportedOperationException(); }
        @Override public ModelInfo activeChatModel() { return MODEL; }
        @Override public boolean isAvailable(ModelRole role) { return true; }
    }

    private static final Map<String, Object> VALID = Map.of(
            "name", "orders_id_notnull", "target", "orders", "column", "ORDER_ID", "kind", "non_null");
    /** Missing `target` and `column` ⇒ two anchored findings. */
    private static final Map<String, Object> TWO_BAD = Map.of("name", "half_baked");
    /** Missing `column` only ⇒ one finding, i.e. strictly better than {@link #TWO_BAD}. */
    private static final Map<String, Object> ONE_BAD = Map.of(
            "name", "half_baked", "target", "orders", "kind", "non_null");

    private InspectoIntelligenceAgent open(LlmGateway gateway) {
        InspectoIntelligenceAgent agent = new InspectoIntelligenceAgent(gateway);
        agent.init(new CollectorService(List.of(), 3600, 1));
        agent.start();
        return agent;
    }

    private Map<String, Object> derive(LlmGateway gateway) {
        InspectoIntelligenceAgent agent = open(gateway);
        try {
            return agent.deriveTool("component_draft", "an expectation that ORDER_ID is never null",
                    Map.of("kind", "expectation"), null).orElseThrow();
        } finally {
            agent.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(Map<String, Object> view) {
        return (Map<String, Object>) view.get("value");
    }

    @Test
    void repairsAcrossTurnsAndStopsAsSoonAsTheDraftIsClean() {
        Map<String, Object> view = derive(new ScriptedModel(TWO_BAD, VALID));

        assertEquals(true, view.get("ok"));
        assertEquals(true, value(view).get("clean"));
        assertEquals(2, view.get("turns"), "converged on the second turn — and did not spend the third");
        assertEquals(VALID, value(view).get("draft"));
    }

    @Test
    void aCleanFirstTurnCostsExactlyOneModelCall() {
        // The good case must not pay for the loop. If this ever reads 2, the loop is re-deriving after
        // success and every well-formed request got twice as slow for nothing.
        ScriptedModel model = new ScriptedModel(VALID);
        Map<String, Object> view = derive(model);

        assertEquals(1, view.get("turns"));
        assertEquals(1, model.seen.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStuckModelStopsAtTheTurnCapAndHandsBackTheDraftWithItsFindings() {
        // The cap is a hand-over, not a failure: ok stays true and the findings ride along, because that
        // is exactly the A1 experience the surface already renders for human repair.
        ScriptedModel model = new ScriptedModel(TWO_BAD);
        Map<String, Object> view = derive(model);

        assertEquals(true, view.get("ok"), "a non-converging loop is not an error");
        assertEquals(InspectoIntelligenceAgent.MAX_REPAIR_TURNS, view.get("turns"));
        assertEquals(3, model.seen.size(), "the cap bounds model calls, not just the reported turn count");
        assertEquals(false, value(view).get("clean"));
        List<Map<String, Object>> findings = (List<Map<String, Object>>) value(view).get("findings");
        assertTrue(findings.stream().anyMatch(f -> "target".equals(f.get("fieldPath"))), findings.toString());
    }

    @Test
    void handsBackTheBestDraftSeenNotTheLastOne() {
        // A later turn can regress. Returning the last draft because it happened to be last would make the
        // loop actively harmful — the operator would get a worse config than one the loop already had.
        Map<String, Object> view = derive(new ScriptedModel(ONE_BAD, TWO_BAD, TWO_BAD));

        assertEquals(InspectoIntelligenceAgent.MAX_REPAIR_TURNS, view.get("turns"));
        assertEquals(ONE_BAD, value(view).get("draft"), "the single-finding draft, not the two-finding last one");
    }

    @Test
    void feedsTheFindingsBackSoTheNextTurnIsARepairAndNotARetry() {
        // Without this the loop is just N identical rolls of the same dice. The second request must carry
        // the rejected config and its anchored findings.
        ScriptedModel model = new ScriptedModel(TWO_BAD, VALID);
        derive(model);

        assertEquals(2, model.seen.size());
        String repairTurn = model.seen.get(1).messages().stream()
                .map(m -> String.valueOf(m.text())).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(repairTurn.contains("half_baked"), "the rejected config is handed back: " + repairTurn);
        assertTrue(repairTurn.contains("target"), "the anchored finding is handed back: " + repairTurn);
    }

    @Test
    void theOfferedSchemaIsTheKindsProjectedSpecNotABareObject() {
        // Plan D9's payoff: the model is constrained by the very spec that will judge it. `kind` comes from
        // the pane, so this is safe to do before the model has said anything.
        ScriptedModel model = new ScriptedModel(VALID);
        derive(model);

        String offered = model.seen.get(0).tools().get(0).jsonSchema();
        assertTrue(offered.contains("\"column\""),
                () -> "config should carry the expectation spec's own fields, was: " + offered);
        assertFalse(offered.contains("\"config\":{\"type\":\"object\"}"), "the bare payload must be gone");
    }

    @Test
    void aFirstTurnModelFailureIsTheCallersErrorAndNotAnEmptyDraft() {
        LlmGateway narrating = new LlmGateway() {
            @Override public ChatResult chat(ChatRequest r) { return new ChatResult("I could help!", List.of(), MODEL, null); }
            @Override public void chatStream(ChatRequest r, TokenSink s) { throw new UnsupportedOperationException(); }
            @Override public EmbeddingResult embed(EmbeddingRequest r) { throw new UnsupportedOperationException(); }
            @Override public ModelInfo activeChatModel() { return MODEL; }
            @Override public boolean isAvailable(ModelRole role) { return true; }
        };

        Map<String, Object> view = derive(narrating);

        assertEquals(false, view.get("ok"));
        assertTrue(String.valueOf(view.get("error")).contains("did not produce arguments"));
        assertNull(view.get("value"), "no draft is better than an empty one presented as a draft");
    }
}
