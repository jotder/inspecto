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
 * AGT-6a A5.3 — natural-language authoring for {@code pipeline_author}.
 *
 * <p>The plan expected a hop here, on the grounds that a graph's errors are "structural, not field-level"
 * and so could not be fed back. That premise was wrong: {@link com.gamma.pipeline.PipelineValidator} already
 * reports <b>coded, structured</b> issues, so a topology has the same anchored-feedback substrate a component
 * config has. What this pins is the part that is specific to a graph — the validator's findings reaching the
 * repair turn, and the offered schema carrying the live node-type vocabulary. The loop's own termination and
 * hand-over properties are pinned once, in {@code ComponentDraftRepairLoopTest}.
 */
class PipelineAuthorRepairLoopTest {

    private static final ModelInfo MODEL = new ModelInfo("stub", "scripted", true);

    /** A gateway that answers each successive {@code chat} with the next scripted flow. */
    private static final class ScriptedModel implements LlmGateway {
        private final List<Map<String, Object>> flows;
        private final List<ChatRequest> seen = new ArrayList<>();
        private int turn;

        @SafeVarargs
        ScriptedModel(Map<String, Object>... flows) { this.flows = List.of(flows); }

        @Override
        public ChatResult chat(ChatRequest request) {
            seen.add(request);
            Map<String, Object> flow = flows.get(Math.min(turn++, flows.size() - 1));
            return new ChatResult(null,
                    List.of(new ToolCall("pipeline_author", Map.of("flow", flow), new RunId("t"))),
                    MODEL, null);
        }

        @Override public void chatStream(ChatRequest r, TokenSink s) { throw new UnsupportedOperationException(); }
        @Override public EmbeddingResult embed(EmbeddingRequest r) { throw new UnsupportedOperationException(); }
        @Override public ModelInfo activeChatModel() { return MODEL; }
        @Override public boolean isAvailable(ModelRole role) { return true; }
    }

    private static Map<String, Object> flow(List<Map<String, Object>> edges) {
        return Map.of(
                "name", "orders_flow",
                "nodes", List.of(
                        Map.of("id", "acq", "type", "acquisition"),
                        Map.of("id", "flt", "type", "transform.filter",
                                "config", Map.of("where", "CAST(amt AS INT) >= 100")),
                        Map.of("id", "sink", "type", "sink.persistent", "config", Map.of("store", "big"))),
                "edges", edges);
    }

    private static final Map<String, Object> WIRED = flow(List.of(
            Map.of("from", "acq", "to", "flt"),
            Map.of("from", "flt", "to", "sink")));

    /** `flt → warehouse` names a node that does not exist ⇒ a DANGLING_TO error. */
    private static final Map<String, Object> DANGLING = flow(List.of(
            Map.of("from", "acq", "to", "flt"),
            Map.of("from", "flt", "to", "warehouse")));

    private Map<String, Object> derive(LlmGateway gateway) {
        InspectoIntelligenceAgent agent = new InspectoIntelligenceAgent(gateway);
        agent.init(new CollectorService(List.of(), 3600, 1));
        agent.start();
        try {
            return agent.deriveTool("pipeline_author",
                    "filter orders over 100 and write them to the big store", Map.of(), null).orElseThrow();
        } finally {
            agent.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(Map<String, Object> view) {
        return (Map<String, Object>) view.get("value");
    }

    @Test
    void repairsADanglingEdgeAcrossTurnsAndStopsOnceTheTopologyValidates() {
        Map<String, Object> view = derive(new ScriptedModel(DANGLING, WIRED));

        assertEquals(true, view.get("ok"));
        assertEquals(true, value(view).get("clean"));
        assertEquals(2, view.get("turns"));
        assertEquals("orders_flow", value(view).get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnexecutableGraphIsAFindingRatherThanAToolError() {
        // This is the property the whole slice rests on. If a structurally broken graph came back ok=false,
        // the loop would treat it as "not repairable" and bail on turn one — which is exactly the case NL
        // authoring exists to rescue.
        ScriptedModel model = new ScriptedModel(DANGLING);
        Map<String, Object> view = derive(model);

        assertEquals(true, view.get("ok"));
        assertEquals(InspectoIntelligenceAgent.MAX_REPAIR_TURNS, view.get("turns"));
        assertEquals(false, value(view).get("clean"));
        assertEquals(false, value(view).get("simulated"), "an invalid graph is never simulated");
        List<Map<String, Object>> findings = (List<Map<String, Object>>) value(view).get("findings");
        assertTrue(findings.stream().anyMatch(f -> "DANGLING_TO".equals(f.get("code"))), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> "edges".equals(f.get("fieldPath"))), findings.toString());
    }

    @Test
    void feedsTheValidatorsCodedFindingBackIntoTheRepairTurn() {
        ScriptedModel model = new ScriptedModel(DANGLING, WIRED);
        derive(model);

        assertEquals(2, model.seen.size());
        String repairTurn = model.seen.get(1).messages().stream()
                .map(m -> String.valueOf(m.text())).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(repairTurn.contains("warehouse"), "the offending node id must reach the model: " + repairTurn);
        assertTrue(repairTurn.contains("flow"), "the repair turn names the argument to correct: " + repairTurn);
    }

    @Test
    void theOfferedSchemaCarriesTheLiveNodeTypeVocabularyAndLeavesRelOpen() {
        ScriptedModel model = new ScriptedModel(WIRED);
        derive(model);

        String offered = model.seen.get(0).tools().get(0).jsonSchema();
        assertFalse(offered.contains("\"flow\":{\"type\":\"object\"}"), "the bare payload must be gone");
        assertTrue(offered.contains("transform.filter"),
                () -> "node types come from the live registry, was: " + offered);
        // `route:<key>` is open-ended, so enumerating rel would forbid the branch dispatch transform.route
        // and parser exist to express.
        assertFalse(offered.contains("\"unmatched\""), "rel must stay a free string, not an enum");
    }
}
