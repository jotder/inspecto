/**
 * Step workbench — the input strip's pure logic (S4b).
 *
 * <p>Which relation feeds a Step is a **graph fact the editor already holds**: the authored `edges[]`.
 * The design's §2.1 is explicit that this needs no server lookup, and that holds — `AuthoredPipeline`
 * carries `{from, rel, to}` for every edge, so the strip is a projection of the model, not a fetch.
 *
 * <p>🔴 <b>The design's other half is REFUTED (grounded 2026-09-07).</b> §4 planned "for
 * `transform.merge`/`join` a select over the inbound edges [that] writes the existing input keys".
 * There are no such keys:
 *
 * <ul>
 *   <li><b>`transform.merge`</b> declares NO input attribute — `NodeAttributes` has no MERGE entry at
 *       all, and `RowShaper.merge(conn, node, inputs, outPrefix)` receives its inputs as a positional
 *       `List<String>` the executor builds from the graph edges. The edges ARE the input binding.</li>
 *   <li><b>`transform.join`</b>'s second input is a Reference component (`reference` + `on`), not an
 *       inbound edge, so a select over edges would not be editing its inputs either.</li>
 * </ul>
 *
 * A picker here would therefore write a key nothing reads. The strip is READ-ONLY on purpose: it states
 * what feeds the Step — which for a fan-in node is every inbound edge, in edge order — and the way to
 * change that stays the canvas, which is where edges are authored.
 */

/** One inbound edge as the strip states it. */
export interface InputRelation {
    /** The upstream node id. */
    from: string;
    /** The relation the edge carries: `DATA`, `DROPPED`, `route:<key>`, … */
    rel: string;
    /** The upstream node's display label when it has one, else its id. */
    label: string;
}

/** The minimum of an authored pipeline the strip reads — kept structural so a spec needs no API types. */
export interface InputGraph {
    nodes: readonly { id: string; label?: string; name?: string }[];
    edges: readonly { from: string; rel: string; to: string }[];
}

/**
 * Every edge pointing AT `nodeId`, in the order the model declares them — which for a fan-in node is the
 * order `RowShaper.merge` receives its inputs, so the strip's order is the execution order, not a
 * cosmetic one.
 *
 * <p>Returns `[]` for a node with no inbound edge (a Collector, or a Step not yet connected) — the strip
 * then says so rather than rendering an empty row, because "reads nothing" is exactly the state a
 * dangling Step is in and the author needs to see it.
 */
export function inputRelations(graph: InputGraph | null | undefined, nodeId: string): InputRelation[] {
    if (!graph || !nodeId) return [];
    const labels = new Map(graph.nodes.map((n) => [n.id, n.label || n.name || n.id]));
    return graph.edges
        .filter((e) => e.to === nodeId)
        .map((e) => ({ from: e.from, rel: e.rel, label: labels.get(e.from) ?? e.from }));
}

/**
 * The one-line sentence the strip shows. Plain language, not a schema dump — the authoring redesign's
 * standing rule for this pane.
 *
 * <p>The relation is named only when it is NOT the ordinary `DATA` edge: on a route branch or a
 * `DROPPED` feed, WHICH relation arrives is the whole point and hiding it would make two very different
 * Steps read identically.
 */
export function inputSummary(inputs: readonly InputRelation[]): string {
    if (!inputs.length) return 'Reads nothing yet — connect an upstream step on the canvas.';
    const named = inputs.map((i) => (isPlainData(i.rel) ? i.label : `${i.label} · ${i.rel}`));
    return named.length === 1 ? `Reads ${named[0]}` : `Reads ${named.length} inputs: ${named.join(', ')}`;
}

/** `DATA` is the unremarkable edge; every other relation is worth naming. Case-insensitive — the authored
 *  model has carried both `DATA` and `data` at different times. */
function isPlainData(rel: string): boolean {
    return (rel ?? '').toLowerCase() === 'data';
}
