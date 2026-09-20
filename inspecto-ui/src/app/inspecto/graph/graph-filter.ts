import { isComplete, evaluateRows } from '../query/query-eval';
import { inferColumns } from '../query/query-columns';
import { ColumnMeta, ConditionGroup } from '../query/query-types';
import { G6GraphData } from './graph-types';

/**
 * **Stage 1 of the two-stage query loop** (spec §3.7): evaluate the shared condition tree over the working
 * set in the browser. The same tree is what `POST /inv/projection` accepts as `filter` for stage 2, so the
 * two evaluators must agree — this module owns the row shape the browser side evaluates against.
 *
 * A projected edge becomes one row: its `attrs`, plus the source and target **column names** carrying the
 * endpoint labels (the raw projected values), plus `kind` and `count`. Conditions on the endpoint columns
 * are therefore ordinary leaves, exactly as they are server-side.
 */
export interface EndpointColumns {
    sourceCol: string;
    targetCol: string;
}

/** Whether the tree carries at least one complete condition (an empty or half-built tree is no constraint). */
export function hasConditions(group: ConditionGroup): boolean {
    return group.items.some((it) => (it.kind === 'group' ? hasConditions(it) : isComplete(it)));
}

/** Deep copy — the condition-group editor mutates the bound tree in place. */
export function cloneGroup(group: ConditionGroup): ConditionGroup {
    return JSON.parse(JSON.stringify(group)) as ConditionGroup;
}

/** The rows stage 1 evaluates: one per edge, endpoint labels under the projection's column names. */
export function edgeRows(g: G6GraphData, cols: EndpointColumns): Record<string, unknown>[] {
    const label = new Map(g.nodes.map((n) => [n.id, n.data.label]));
    return g.edges.map((e) => ({
        ...(e.data.attrs ?? {}),
        [cols.sourceCol]: label.get(e.source) ?? e.source,
        [cols.targetCol]: label.get(e.target) ?? e.target,
        kind: e.data.kind,
        count: (e.data as { count?: number }).count ?? 1,
        __edge: e.id,
    }));
}

/** The columns the predicate builder offers: endpoints first, then every attribute, typed by inference. */
export function predicateColumns(g: G6GraphData, cols: EndpointColumns): ColumnMeta[] {
    const inferred = inferColumns(edgeRows(g, cols)).filter((c) => c.name !== '__edge');
    const order = [cols.sourceCol, cols.targetCol];
    return [
        ...order.map((name) => inferred.find((c) => c.name === name) ?? { name, type: 'string' as const }),
        ...inferred.filter((c) => !order.includes(c.name)),
    ];
}

/**
 * Keep the edges the tree matches, and the nodes those edges touch. Nodes already marked stranded
 * (`missing`) are kept regardless — a filter never silently drops what the analyst has seen.
 */
export function filterEdgesByPredicate(g: G6GraphData, where: ConditionGroup, cols: EndpointColumns): G6GraphData {
    if (!hasConditions(where)) return g;
    const rows = edgeRows(g, cols);
    const columns = predicateColumns(g, cols);
    const kept = new Set(
        evaluateRows({ projection: '*', where }, { name: 'edges', rows, columns }).map((r) => r['__edge'] as string),
    );
    const edges = g.edges.filter((e) => kept.has(e.id));
    const touched = new Set(edges.flatMap((e) => [e.source, e.target]));
    return { nodes: g.nodes.filter((n) => touched.has(n.id) || n.data.missing), edges };
}

/**
 * Merge a stage-2 (pushed-down) result over the previous working set: every node the new predicate
 * excluded is **marked** `missing` (rendered dimmed and dashed), never dropped, so the analyst sees what
 * the narrower question removed. Nodes the new result contains lose any earlier mark.
 */
export function markStranded(previous: G6GraphData, next: G6GraphData): G6GraphData {
    const present = new Set(next.nodes.map((n) => n.id));
    const stranded = previous.nodes
        .filter((n) => !present.has(n.id))
        .map((n) => ({ ...n, data: { ...n.data, missing: true } }));
    return {
        nodes: [
            ...next.nodes.map((n) => (n.data.missing ? { ...n, data: { ...n.data, missing: false } } : n)),
            ...stranded,
        ],
        edges: next.edges,
    };
}

/**
 * The stage-1 → stage-2 translation of a node filter: "this value appears as **either** endpoint" is an
 * `OR` group over the source and target columns, not a single leaf (spec §3.7).
 */
export function endpointGroup(value: string, cols: EndpointColumns): ConditionGroup {
    return {
        kind: 'group',
        op: 'OR',
        items: [
            { kind: 'condition', field: cols.sourceCol, operator: '=', value },
            { kind: 'condition', field: cols.targetCol, operator: '=', value },
        ],
    };
}
