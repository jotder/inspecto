package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.etl.TypeFlow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The plugin seam for pipeline node types. Mirrors
 * {@link com.gamma.acquire.CollectorConnectorFactory}: the engine {@link java.util.ServiceLoader}s the
 * available node types and matches one by its {@link #type()} discriminator. The lean core ships the
 * {@link BuiltinNodeType built-ins}; editions/plugins contribute extra types by listing a provider in
 * {@code META-INF/services/com.gamma.pipeline.PipelineNodeType}.
 *
 * <p>Phase-1 scope is <b>descriptor-level</b> — {@link #type()} + {@link #category()} + a UI
 * {@link #label()}/{@link #description()}, plus the relationships a node {@link #emits()}/
 * {@link #accepts()}. These feed the lift, the (Phase-3) wiring validator <b>and the UI palette</b>
 * (the built-in processor definitions are not deferred — the visualiser needs them, doc §6). Execution
 * and dry-run hooks are added in later phases, so this interface stays small and stable for now.
 *
 * <p><b>Reserved extension point</b> (modularization plan C7, 2026-07-21): as of 4.x no external
 * provider exists — every shipped node type is a {@link BuiltinNodeType}. The {@code ServiceLoader}
 * contribution path is nevertheless live in {@link PipelineNodeTypes} and kept deliberately, for
 * edition/plugin node types; it mirrors the proven {@code CollectorConnectorFactory} seam. Don't
 * remove it for being "unused" — implement a provider against it instead.
 */
@PublicApi(since = "4.0.0")
public interface PipelineNodeType {

    /** The {@code type} value this descriptor handles, e.g. {@code "acquisition"}. */
    String type();

    /** The {@link NodeCategory family} this node type belongs to (palette grouping + role checks). */
    default NodeCategory category() {
        return NodeCategory.TRANSFORM;
    }

    /** Short human label for the UI palette / node inspector (defaults to {@link #type()}). */
    default String label() {
        return type();
    }

    /** One-line description for the UI palette / node inspector (defaults to empty). */
    default String description() {
        return "";
    }

    /**
     * Control/split relationships this node type may emit, besides operator-defined named
     * {@code route:*} branches (see {@link #emitsNamedRoutes()}). Enforced by
     * {@link PipelineValidator} (T9): an outbound edge whose relationship is not emitted here is rejected.
     */
    default Set<String> emits() {
        return Set.of(PipelineRel.DATA);
    }

    /**
     * Relationships this node type accepts inbound. An entry node accepts nothing.
     * {@link PipelineValidator} enforces this on {@code data} edges (a {@code data} edge's target must
     * accept {@code data}) and on outcome/route edges as a neighbour-pairing check (A6): their target
     * must accept the relationship or accept {@code data} — a row-consumer (sink/alert) takes any
     * outcome stream as rows, so handlers need not list every inbound outcome.
     */
    default Set<String> accepts() {
        return Set.of(PipelineRel.DATA);
    }

    /** Whether this node type emits operator-defined {@code route:<key>} branches (a parser dispatcher, route, plugin). */
    default boolean emitsNamedRoutes() {
        return false;
    }

    /**
     * The columns this node type's {@code data} output carries, when the provider can state them
     * <em>without running</em> — the plugin half of the derived-schema story (`TYPEFLOW-CONSUMERS-1` (c)).
     *
     * <p><b>Why an SPI method at all.</b> A built-in {@code transform.sql} step is described by
     * {@code TypeFlow.describe}: DuckDB's binder plans its SQL over the upstream shape and returns the
     * output columns without reading a row. A <em>plugin</em> step has no SQL to plan — its
     * {@link com.gamma.pipeline.exec.PipelineNodeExecutor#shape} is arbitrary Java — so nothing can derive
     * its output, and every consumer of a derived schema stops dead at a plugin node. Only the provider
     * knows, so only the provider can say.
     *
     * <p>{@code inputColumns} is the upstream Step's typed output; {@code config} is the node's own
     * authored attribute map. Return {@link Optional#empty()} — the default — to say <b>"not statically
     * knowable"</b>, which is honest for a node whose shape depends on the data. ⛔ Do not return an empty
     * LIST to mean that: a present-but-empty answer states the node emits no columns, and a consumer
     * cannot tell the two apart. Unknown is not empty.
     *
     * <p>⚠ <b>Declared, not yet enforced.</b> Nothing in the tree reads this today (2026-09-11): it was
     * added ahead of its consumers so pack authors have a stable contract to write against, on the
     * operator's explicit call. That means a provider's answer is currently <em>unverified</em> — no gate
     * compares it against what {@code shape} actually produces. Whoever wires the first consumer must
     * decide what happens when they disagree, and should expect existing packs to be wrong.
     */
    default Optional<List<TypeFlow.Column>> outputColumns(List<TypeFlow.Column> inputColumns,
                                                          Map<String, Object> config) {
        return Optional.empty();
    }
}
