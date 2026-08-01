package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.List;

/**
 * A graph cannot be lowered to the canonical {@code *_pipeline.toon} shape (W5, plan U-A). Each
 * {@link Refusal} names WHY with a stable code the UI can render next to the offending node —
 * replacing the silent truncation the compile-back used to do (it picked the first sink and
 * dropped the rest).
 *
 * <p>Codes: {@code UNSUPPORTED_NODE} (the flat config has no home for this node type),
 * {@code MULTI_SINK} (more than one distinct persistent database dir — the flat config has exactly
 * one), and the strict-mode completeness set {@code NO_ACQUISITION} / {@code NO_PARSER} /
 * {@code NO_PERSISTENT_SINK} / {@code PARSER_NO_SCHEMA} (an {@code active} pipeline must be whole;
 * an inactive draft may be partial).
 */
@PublicApi(since = "4.7.0")
public class PipelineCompileException extends RuntimeException {

    /** One named reason a graph cannot lower; {@code nodeId} is null for graph-level refusals. */
    public record Refusal(String code, String nodeId, String message) {}

    private final List<Refusal> refusals;

    public PipelineCompileException(List<Refusal> refusals) {
        super("graph cannot be lowered: " + refusals.stream()
                .map(r -> r.code() + (r.nodeId() != null ? "(" + r.nodeId() + ")" : "") + " — " + r.message())
                .toList());
        this.refusals = List.copyOf(refusals);
    }

    public List<Refusal> refusals() {
        return refusals;
    }
}
