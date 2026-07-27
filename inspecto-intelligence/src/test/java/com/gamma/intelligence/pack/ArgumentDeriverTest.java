package com.gamma.intelligence.pack;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.StubLlmGateway;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGT-6a A5.1 — the derive hop's safety properties, pinned against a deterministic stub gateway.
 *
 * <p>These are the assertions that stop the hop from quietly becoming something else. The route tests in
 * {@code AgentRoutesTest} cover the HTTP gate order; this covers what happens to the model's output
 * <b>after</b> it arrives and before it reaches a tool.
 */
class ArgumentDeriverTest {

    /** The real {@code query_author} spec's shape: `when` is bare, which is exactly why filtering matters. */
    private static final ToolSpec QUERY_AUTHOR = new ToolSpec(
            "query_author",
            "Draft a Query from a dataset and a condition tree.",
            "{\"type\":\"object\",\"properties\":{\"dataset\":{\"type\":\"string\"},"
                    + "\"when\":{\"type\":\"object\"},\"name\":{\"type\":\"string\"}},"
                    + "\"required\":[\"dataset\"]}",
            false, Role.USER, Capability.AUTHOR_PIPELINE);

    private static LlmGateway modelEmitting(Map<String, Object> arguments) {
        return StubLlmGateway.builder()
                .replyToolCalls(new ToolCall("query_author", arguments, new RunId("test")))
                .build();
    }

    @Test
    void aModelEmittedSqlKeyIsDroppedNotSpliced() {
        // THE invariant of this slice. query_author's whole safety story is that the SERVER renders the
        // SQL and SqlGuard-checks it; the model only supplies a dataset and a condition. A blind putAll
        // here would let a model hand us a finished statement and we would carry it into the draft —
        // turning a model into a SQL author through the back door. The merge is schema-keyed, so an
        // undeclared key cannot survive however plausible it looks.
        Map<String, Object> emitted = new LinkedHashMap<>();
        emitted.put("dataset", "orders");
        emitted.put("when", Map.of("op", ">", "field", "amount", "value", 100));
        emitted.put("sql", "SELECT * FROM orders; DROP TABLE orders");
        emitted.put("text", "SELECT 1");

        ArgumentDeriver.Derivation d =
                ArgumentDeriver.derive(modelEmitting(emitted), QUERY_AUTHOR, "orders over 100", Map.of());

        assertTrue(d.ok(), d.error());
        assertFalse(d.args().containsKey("sql"), "an undeclared key must never reach the tool");
        assertFalse(d.args().containsKey("text"));
        assertEquals("orders", d.args().get("dataset"));
        assertEquals("amount", ((Map<?, ?>) d.args().get("when")).get("field"));
    }

    @Test
    void thePanesIdentityFieldsOutrankTheModels() {
        // The screen knows which Dataset is open; a model can hallucinate one. If this inverts, an
        // operator's sentence can silently retarget the draft at a dataset they were not looking at.
        Map<String, Object> emitted = new LinkedHashMap<>();
        emitted.put("dataset", "hallucinated_table");
        emitted.put("when", Map.of("op", "=", "field", "status", "value", "OPEN"));

        ArgumentDeriver.Derivation d = ArgumentDeriver.derive(
                modelEmitting(emitted), QUERY_AUTHOR, "open ones", Map.of("dataset", "orders"));

        assertTrue(d.ok(), d.error());
        assertEquals("orders", d.args().get("dataset"), "the pane's dataset wins");
        assertEquals("status", ((Map<?, ?>) d.args().get("when")).get("field"), "but its condition survives");
    }

    @Test
    void malformedModelArgumentsAreARetryableFailureNotADraft() {
        // The transport parks unparseable output under _raw. Merging that into a draft would produce a
        // plausible-looking Query built from nothing.
        ArgumentDeriver.Derivation d = ArgumentDeriver.derive(
                modelEmitting(Map.of(ArgumentDeriver.RAW_ARGUMENTS_KEY, "{\"dataset\": \"orde")),
                QUERY_AUTHOR, "orders over 100", Map.of());

        assertFalse(d.ok());
        assertNull(d.args());
        assertTrue(d.error().contains("malformed"));
    }

    @Test
    void aProseAnswerWithNoToolCallIsItsOwnDistinctFailure() {
        // The commonest local-model failure: it narrates instead of calling. Distinct message from the
        // malformed case, because the operator's remedy differs (rephrase vs. try again).
        LlmGateway narrating = StubLlmGateway.builder().replyText("Sure! You could filter on amount.").build();

        ArgumentDeriver.Derivation d =
                ArgumentDeriver.derive(narrating, QUERY_AUTHOR, "orders over 100", Map.of());

        assertFalse(d.ok());
        assertTrue(d.error().contains("did not produce arguments"));
        assertFalse(d.error().contains("malformed"), "the two model failures must read differently");
    }

    @Test
    void anUnreadableSchemaDropsEverythingRatherThanTrusting() {
        // Fail closed. If the spec's schema cannot be parsed we cannot say which keys are declared, and
        // the safe answer is to keep only what the pane supplied — never to wave the model's output through.
        ToolSpec broken = new ToolSpec("query_author", "d", "not json at all",
                false, Role.USER, Capability.AUTHOR_PIPELINE);

        ArgumentDeriver.Derivation d = ArgumentDeriver.derive(
                modelEmitting(Map.of("dataset", "x", "sql", "SELECT 1")), broken, "q",
                Map.of("dataset", "orders"));

        assertTrue(d.ok(), d.error());
        assertEquals(Map.of("dataset", "orders"), d.args());
    }
}
