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
 * {@code UNSUPPORTED_BINDING} (it has a home for the node, but not for the {@code use:} component ref
 * the node carries — added 2026-08-14 for AUTHOR-1, where such a ref was dropped in silence) and the
 * strict-mode completeness set {@code NO_ACQUISITION} / {@code NO_PARSER} /
 * {@code NO_PERSISTENT_SINK} / {@code PARSER_NO_SCHEMA} (an {@code active} pipeline must be whole; an
 * inactive draft may be partial).
 *
 * <p>⚠ <b>The "too many of a kind" codes are gone, and that is the direction of travel.</b>
 * {@code MULTI_SINK} stopped firing when {@code sinks:} became a plural block, and
 * {@code MULTI_JOIN} / {@code MULTI_DEDUP} / {@code MULTI_ROUTE} / {@code MULTI_SUMMARIZE} were
 * removed with the ordered {@code steps:} chain (multiplicity plan A3). Each existed only to make a
 * silent discard visible while the flat file had one slot per kind; none was ever the destination, and
 * each went in the same change that let the format hold what it was refusing. A count is not what
 * should constrain a pipeline — whether a step accepts its neighbours is ({@code PipelineValidator}).
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
