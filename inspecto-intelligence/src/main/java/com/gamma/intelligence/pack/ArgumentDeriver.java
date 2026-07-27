package com.gamma.intelligence.pack;

import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AGT-6a A5.1: turn one operator sentence into <b>the arguments of one named tool</b> — nothing else.
 *
 * <p>This is the model hop the inline-authoring surface was missing. A1 shipped deterministic dispatch:
 * the pane hands over structured arguments and the tool renders a draft with no model involved. That is
 * right for four of the five tools, but it means an operator still hand-builds a condition tree. This
 * class produces those arguments from prose, and then <b>the deterministic path runs unchanged</b>.
 *
 * <h3>Containment is the request shape, not a policy</h3>
 * The {@link ChatRequest} offers <b>exactly one</b> non-mutating tool, and only that call's
 * {@code arguments} are read. The model therefore cannot select a different tool, cannot reach the
 * deliberative loop's tool-choice step, and cannot have its prose paraphrased into the answer — there is
 * no path from here to any of those. Nothing downstream has to remember a rule.
 *
 * <h3>The model still never writes SQL</h3>
 * For {@code query_author} the hop emits only {@code dataset} and {@code when}; the server renders the
 * relation and the predicate and {@code SqlGuard}-checks the statement exactly as before. That invariant
 * survives because the merge is <b>schema-keyed</b> ({@link #allowedKeys}) rather than a blind
 * {@code putAll}: a model that helpfully emits {@code text} or {@code sql} has those keys <b>dropped</b>,
 * not spliced into the draft. A blind merge here would have quietly turned a model into a SQL author.
 *
 * <h3>Pane context outranks the model on identity</h3>
 * Arguments the pane supplied are applied <b>last</b> and win. The screen knows which Dataset is open; a
 * model can hallucinate one. This generalises the A3 rule, and it is why the hop takes {@code args} as
 * well as {@code prompt} instead of replacing them.
 *
 * <h3>Three failures, three different answers</h3>
 * They are distinguished deliberately, because conflating them blames the operator's sentence for a
 * deployment fact: no model configured is the caller's 503 (decided by {@code GatewayFactory.Gateway}),
 * while a malformed argument payload ({@link #RAW_ARGUMENTS_KEY}) and no tool call at all are two
 * different retryable 422 messages.
 */
public final class ArgumentDeriver {

    /**
     * The key eoiagent's tool mapping parks unparseable model output under. Its presence means the model
     * answered with something that was not valid arguments — a local-model quality failure, retryable,
     * and never something to merge into a draft.
     */
    public static final String RAW_ARGUMENTS_KEY = "_raw";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ArgumentDeriver() {}

    /** A derived argument map, or the reason it could not be produced. Exactly one field is non-null. */
    public record Derivation(Map<String, Object> args, String error) {
        static Derivation of(Map<String, Object> args) { return new Derivation(args, null); }
        static Derivation failed(String error) { return new Derivation(null, error); }
        public boolean ok() { return error == null; }
    }

    /**
     * One turn, one offered tool. Returns the arguments to invoke {@code spec.name()} with, already merged
     * with {@code paneArgs} (which win).
     */
    public static Derivation derive(LlmGateway gateway, ToolSpec spec, String prompt, Map<String, Object> paneArgs) {
        ChatRequest request = new ChatRequest(
                List.of(new ChatMessageRecord(ChatRole.SYSTEM, systemPrompt(spec), Instant.now(), Map.of()),
                        new ChatMessageRecord(ChatRole.USER, prompt, Instant.now(), Map.of())),
                List.of(spec),                       // exactly one — this is the containment
                ChatOptions.defaults());
        ChatResult result = gateway.chat(request);

        List<ToolCall> calls = result.toolCalls();
        if (calls == null || calls.isEmpty())
            return Derivation.failed("the model did not produce arguments for '" + spec.name()
                    + "' — rephrase the request, or fill the form directly");

        Map<String, Object> emitted = calls.get(0).arguments();
        if (emitted == null) emitted = Map.of();
        if (emitted.containsKey(RAW_ARGUMENTS_KEY))
            return Derivation.failed("the model returned malformed arguments for '" + spec.name()
                    + "' — try again, or rephrase more simply");

        // Schema-keyed, NOT putAll: an unknown key is dropped rather than carried into the draft.
        Map<String, Object> allowed = allowedKeys(spec);
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : emitted.entrySet())
            if (allowed.containsKey(e.getKey())) merged.put(e.getKey(), e.getValue());
        // Pane args last: the screen's identity fields outrank anything the model said.
        if (paneArgs != null) merged.putAll(paneArgs);
        return Derivation.of(merged);
    }

    /**
     * The tool's declared top-level properties. An unreadable or property-less schema yields an empty set,
     * which drops <b>everything</b> the model emitted and leaves only the pane's own arguments — fail
     * closed, because the alternative is splicing unvalidated keys into a draft.
     */
    private static Map<String, Object> allowedKeys(ToolSpec spec) {
        try {
            Map<String, Object> schema =
                    JSON.readValue(spec.jsonSchema(), new TypeReference<Map<String, Object>>() {});
            Object props = schema.get("properties");
            return props instanceof Map<?, ?> m
                    ? m.entrySet().stream().collect(LinkedHashMap::new,
                            (acc, e) -> acc.put(String.valueOf(e.getKey()), e.getValue()), Map::putAll)
                    : Map.of();
        } catch (Exception unreadable) {
            return Map.of();
        }
    }

    /**
     * Deliberately terse and tool-specific. The schema is already carried natively in the request (the
     * transport maps {@code ToolSpec.jsonSchema} onto a LangChain4j {@code ToolSpecification}), so the
     * prompt's job is only to stop the model narrating instead of calling — the common local-model failure
     * — and to forbid the identity guessing that {@code paneArgs} would override anyway.
     */
    private static String systemPrompt(ToolSpec spec) {
        return """
               You translate one operator sentence into a single call of the tool `%s`.

               %s

               Rules:
               - Call the tool exactly once. Do not answer in prose; a prose answer is a failure.
               - Emit only arguments the tool's schema declares. Never emit SQL, or a query's text.
               - Omit any argument the sentence does not determine. Do not guess identifiers such as a
                 dataset, table or component id — the caller supplies those and yours would be discarded.
               """.formatted(spec.name(), spec.description());
    }
}
