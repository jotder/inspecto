import { describe, expect, it } from 'vitest';
import {
    AuthoredNode,
    AuthoredPipeline,
    PipelineCombined,
    PipelineGraph,
    PipelineNode,
    PipelineNodeType,
} from 'app/inspecto/api';
import { NODE_KIND_COLORS } from 'app/inspecto/theme/chart-tokens';
import {
    TestOutcome,
    addEdgeToModel,
    addNodeToModel,
    addRouteBranch,
    insertBranchHead,
    insertRouteAfter,
    removeRouteBranch,
    setRouteBranchWhere,
    setRouteDefault,
    applyNodePatchInModel,
    authoredToG6,
    bindKindFor,
    candidateRelsFor,
    edgeRefusal,
    EdgeRules,
    categoryVisualKind,
    computeNodeStatus,
    decodeEdgeId,
    detectStepChain,
    encodeEdgeId,
    flattenStepChain,
    groupByCategory,
    insertStepAfter,
    moveStepInChain,
    nodeConfigEntries,
    nodeDisplayLabel,
    nodeLastRunTotal,
    provenanceCounts,
    removeStepFromChain,
    removeEdgeFromModel,
    removeNodeFromModel,
    resolveNodeIcon,
    setEdgeRelInModel,
    toCombinedG6Data,
    toPipelineG6Data,
    uniqueNodeId,
    validatePipeline,
} from './pipeline-graph';

const node = (over: Partial<PipelineNode>): PipelineNode => ({
    id: 'n',
    type: 'transform.sql',
    category: 'TRANSFORM',
    label: 'Map',
    ...over,
});

describe('categoryVisualKind', () => {
    it('maps flow categories onto catalog node kinds for shape/colour reuse', () => {
        expect(categoryVisualKind('SOURCE')).toBe('STREAM');
        expect(categoryVisualKind('PARSE')).toBe('SCHEMA');
        expect(categoryVisualKind('TRANSFORM')).toBe('ENRICHMENT');
        expect(categoryVisualKind('SINK')).toBe('TABLE');
        expect(categoryVisualKind('CONTROL')).toBe('KPI');
        expect(categoryVisualKind('STORE')).toBe('TABLE'); // the combined-view shared-store node
    });

    it('passes unknown categories through (NodeKind includes string)', () => {
        expect(categoryVisualKind('WHATEVER')).toBe('WHATEVER');
    });
});

describe('nodeDisplayLabel', () => {
    it('prefers the user-given name, falling back to the type label', () => {
        expect(nodeDisplayLabel(node({ name: 'Active subscribers' }))).toBe('Active subscribers');
        expect(nodeDisplayLabel(node({ name: undefined }))).toBe('Map');
        expect(nodeDisplayLabel(node({ name: '  ' }))).toBe('Map');
    });
});

describe('toPipelineG6Data', () => {
    const graph: PipelineGraph = {
        name: 'F',
        active: true,
        produces: ['orders'],
        consumes: [],
        nodes: [
            node({ id: 'acq', type: 'acquisition', category: 'SOURCE', label: 'Acquisition' }),
            node({
                id: 'sink',
                type: 'sink.persistent',
                category: 'SINK',
                label: 'Sink (persistent)',
                name: 'orders',
                store: 'orders',
            }),
        ],
        edges: [
            { from: 'acq', to: 'sink', rel: 'data', kind: 'data' },
            { from: 'acq', to: 'sink', rel: 'route:emea', kind: 'route', routeKey: 'emea' },
        ],
    };

    it('maps nodes with the display label + visual kind', () => {
        const { nodes } = toPipelineG6Data(graph);
        expect(nodes[0]).toEqual({ id: 'acq', data: { label: 'Acquisition', kind: 'STREAM' } });
        expect(nodes[1].data).toEqual({ label: 'orders', kind: 'TABLE' }); // user name + SINK→TABLE
    });

    it('keeps parallel edges unique and carries the relationship as the edge-kind label', () => {
        const { edges } = toPipelineG6Data(graph);
        expect(new Set(edges.map((e) => e.id)).size).toBe(2);
        expect(edges[0].data.kind).toBe('data');
        expect(edges[1].data.kind).toBe('route:emea');
    });

    it('overlays provenance counts onto matching edges (label + weight) and leaves others plain', () => {
        const counts = provenanceCounts([{ nodeId: 'acq', rel: 'data', rowCount: 1234 }]);
        const { edges } = toPipelineG6Data(graph, counts);
        expect(edges[0].data).toEqual({ kind: 'data · 1,234', weight: 1234 }); // matched
        expect(edges[1].data).toEqual({ kind: 'route:emea' }); // no count for this rel
    });
});

describe('authoredToG6 last-run overlay (T17)', () => {
    const flow: AuthoredPipeline = {
        name: 'F',
        active: true,
        nodes: [
            { id: 'acq', type: 'acquisition' },
            { id: 'sink', type: 'sink.persistent', name: 'orders' },
        ],
        edges: [
            { from: 'acq', to: 'sink', rel: 'data' },
            { from: 'acq', to: 'sink', rel: 'dropped' },
        ],
    };
    const typeCat = new Map([
        ['acquisition', 'SOURCE'],
        ['sink.persistent', 'SINK'],
    ]);

    it('paints matching edges with the last-run count and leaves others plain', () => {
        const counts = provenanceCounts([{ nodeId: 'acq', rel: 'data', rowCount: 42 }]);
        const { edges } = authoredToG6(flow, typeCat, undefined, undefined, counts);
        expect(edges[0].data).toEqual({ kind: 'data · 42', weight: 42 });
        expect(edges[1].data).toEqual({ kind: 'dropped' });
    });

    it('leaves edges plain when no counts are supplied (no run yet / provenance backend unset)', () => {
        const { edges } = authoredToG6(flow, typeCat);
        expect(edges.map((e) => e.data.kind)).toEqual(['data', 'dropped']);
        expect(edges.every((e) => !('weight' in e.data))).toBe(true);
    });
});

describe('nodeLastRunTotal', () => {
    it('sums every relationship a node emitted in the run', () => {
        const counts = provenanceCounts([
            { nodeId: 'acq', rel: 'data', rowCount: 40 },
            { nodeId: 'acq', rel: 'dropped', rowCount: 2 },
            { nodeId: 'sink', rel: 'data', rowCount: 40 },
        ]);
        expect(nodeLastRunTotal('acq', counts)).toBe(42);
        expect(nodeLastRunTotal('sink', counts)).toBe(40);
    });

    it('returns null for a node that recorded nothing in the run (distinct from a real zero)', () => {
        const counts = provenanceCounts([{ nodeId: 'acq', rel: 'data', rowCount: 0 }]);
        expect(nodeLastRunTotal('sink', counts)).toBeNull();
        expect(nodeLastRunTotal('acq', counts)).toBe(0);
    });
});

describe('toCombinedG6Data', () => {
    const combined: PipelineCombined = {
        flows: [
            { name: 'orders_etl', active: true },
            { name: 'orders_rollup', active: true },
        ],
        nodes: [
            { id: 'orders_etl/acq', type: 'acquisition', category: 'SOURCE', label: 'Acquisition', flow: 'orders_etl' },
            {
                id: 'orders_etl/sink',
                type: 'sink.persistent',
                category: 'SINK',
                label: 'Sink',
                store: 'orders',
                flow: 'orders_etl',
            },
            {
                id: 'orders_rollup/src',
                type: 'transform.sql',
                category: 'TRANSFORM',
                label: 'Read',
                sourceStore: 'orders',
                flow: 'orders_rollup',
            },
            { id: 'store:orders', type: 'store', category: 'STORE', label: 'orders', store: 'orders' },
        ],
        edges: [
            { from: 'orders_etl/acq', to: 'orders_etl/sink', rel: 'data', kind: 'data' },
            { from: 'orders_etl/sink', to: 'store:orders', rel: 'produces', kind: 'store', restsOnDisk: true },
            { from: 'store:orders', to: 'orders_rollup/src', rel: 'consumes', kind: 'store', restsOnDisk: true },
        ],
        links: [{ producer: 'orders_etl', store: 'orders', consumer: 'orders_rollup' }],
    };

    it('maps namespaced flow nodes plus the synthetic store node (as a TABLE)', () => {
        const { nodes } = toCombinedG6Data(combined);
        expect(nodes.find((n) => n.id === 'orders_etl/acq')?.data.kind).toBe('STREAM');
        const store = nodes.find((n) => n.id === 'store:orders');
        expect(store?.data).toEqual({ label: 'orders', kind: 'TABLE' });
    });

    it('carries the store-join edges with unique ids', () => {
        const { edges } = toCombinedG6Data(combined);
        expect(new Set(edges.map((e) => e.id)).size).toBe(3);
        expect(edges.some((e) => e.source === 'orders_etl/sink' && e.target === 'store:orders')).toBe(true);
        expect(edges.some((e) => e.source === 'store:orders' && e.target === 'orders_rollup/src')).toBe(true);
    });
});

describe('resolveNodeIcon', () => {
    // An exact-type rule and a category rule, so the precedence below is actually exercised. The
    // exact rule is on a TRANSFORM type and the category rule on PARSE — with one type per category
    // the two cases cannot share a type and still test different branches.
    const map = {
        PARSE: { glyph: 'lines', color: NODE_KIND_COLORS.SCHEMA },
        'transform.filter': { glyph: 'filter', color: NODE_KIND_COLORS.ENRICHMENT },
    };

    it('prefers an exact type rule over the category rule', () => {
        const r = resolveNodeIcon('transform.filter', 'TRANSFORM', map);
        expect(r.color).toBe(NODE_KIND_COLORS.ENRICHMENT);
        expect(r.iconSrc.startsWith('data:image/svg+xml')).toBe(true);
    });

    it('falls back to the category rule, then to the built-in kind glyph', () => {
        expect(resolveNodeIcon('parser', 'PARSE', map).color).toBe(NODE_KIND_COLORS.SCHEMA);
        // no rule for SINK in this map → built-in fallback still yields an icon
        expect(resolveNodeIcon('sink.persistent', 'SINK', map).iconSrc.startsWith('data:image/svg+xml')).toBe(true);
    });

    it('embeds iconSrc+color into toPipelineG6Data only when a map is supplied', () => {
        const g = {
            name: 'F',
            active: true,
            produces: [],
            consumes: [],
            nodes: [node({ id: 'p', type: 'parser', category: 'PARSE' })],
            edges: [],
        };
        expect(toPipelineG6Data(g, undefined, map).nodes[0].data.iconSrc).toBeTruthy();
        expect(toPipelineG6Data(g).nodes[0].data.iconSrc).toBeUndefined();
    });
});

describe('bindKindFor', () => {
    /**
     * ⛔ Only a kind the server has a `use:` home for may appear here. `transform` and `sink` were
     * removed once AUTHOR-1(b) made those refs a named refusal: the picker they drove could only ever
     * hand the author a failed save. Adding one back means giving it a home in
     * `PipelineEditable.USE_HOME` first.
     */
    it('binds a grammar on a parser, and nothing else', () => {
        expect(bindKindFor('PARSE')).toBe('grammar');
        expect(bindKindFor('TRANSFORM')).toBeNull();
        expect(bindKindFor('SINK')).toBeNull();
        expect(bindKindFor('SOURCE')).toBeNull();
        expect(bindKindFor('CONTROL')).toBeNull();
    });
});

describe('computeNodeStatus', () => {
    const refs = new Set(['grammar/cdr_csv']);
    const noTests = new Map<string, TestOutcome>();

    it('flags a parser with no grammar as unconfigured', () => {
        expect(computeNodeStatus({ id: 'p', type: 'parser' }, 'PARSE', refs, noTests)).toBe('unconfigured');
    });

    it('flags a bound-but-missing ref as dangling (only once the registry is loaded)', () => {
        const n = { id: 'p', type: 'parser', use: 'grammar/ghost' };
        expect(computeNodeStatus(n, 'PARSE', refs, noTests)).toBe('dangling');
        expect(computeNodeStatus(n, 'PARSE', refs, noTests, false)).toBe('configured'); // pre-load: no false flag
    });

    it('is configured when the ref resolves, and a recorded test outcome wins', () => {
        const n = { id: 'p', type: 'parser', use: 'grammar/cdr_csv' };
        expect(computeNodeStatus(n, 'PARSE', refs, noTests)).toBe('configured');
        expect(computeNodeStatus(n, 'PARSE', refs, new Map([['p', 'tested']]))).toBe('tested');
        expect(computeNodeStatus(n, 'PARSE', refs, new Map([['p', 'rejects']]))).toBe('rejects');
    });

    /**
     * Phase 4 S4 / D-13: a Step switched off reads as `disabled` whatever else is true of it — the
     * author's explicit decision outranks every derived state, including a recorded test outcome.
     * `enabled: true` is the default and must never produce the state.
     */
    it('reports a switched-off Step as disabled, over any derived status', () => {
        const off = { id: 'w', type: 'sink.persistent', config: { database: '/db', enabled: false } };
        expect(computeNodeStatus(off, 'SINK', refs, noTests)).toBe('disabled');
        expect(computeNodeStatus(off, 'SINK', refs, new Map([['w', 'tested']]))).toBe('disabled');
        // A blank-but-disabled Step is still disabled, not 'unconfigured'.
        expect(
            computeNodeStatus({ id: 'w', type: 'sink.persistent', config: { enabled: false } }, 'SINK', refs, noTests),
        ).toBe('disabled');
        const on = { id: 'w', type: 'sink.persistent', config: { database: '/db', enabled: true } };
        expect(computeNodeStatus(on, 'SINK', refs, noTests)).toBe('configured');
    });

    /**
     * ⚠ The decoupling guard: a transform/sink still needs configuration after losing its bind kind.
     * `needsRef` used to be `bindKindFor(cat) != null`, so nulling those kinds would have quietly made
     * every blank transform 'configured' and dropped its Validate error.
     */
    it('still flags a blank transform and sink as unconfigured, though neither binds a component', () => {
        expect(computeNodeStatus({ id: 'f', type: 'transform.filter' }, 'TRANSFORM', refs, noTests)).toBe(
            'unconfigured',
        );
        expect(computeNodeStatus({ id: 'w', type: 'sink.persistent' }, 'SINK', refs, noTests)).toBe('unconfigured');
        expect(
            computeNodeStatus(
                { id: 'f', type: 'transform.filter', config: { where: 'x > 1' } },
                'TRANSFORM',
                refs,
                noTests,
            ),
        ).toBe('configured');
    });

    it('treats a source as unconfigured until a connection is bound', () => {
        expect(computeNodeStatus({ id: 's', type: 'acquisition' }, 'SOURCE', refs, noTests)).toBe('unconfigured');
        expect(
            computeNodeStatus({ id: 's', type: 'acquisition', use: 'connection/cdr' }, 'SOURCE', refs, noTests),
        ).toBe('configured');
    });
});

describe('validatePipeline', () => {
    const typeCat = new Map([
        ['acquisition', 'SOURCE'],
        ['parser', 'PARSE'],
        ['sink.persistent', 'SINK'],
    ]);
    const refs = new Set(['grammar/cdr_csv']);

    it('reports an error for an unconfigured node and blocks activation', () => {
        const flow: AuthoredPipeline = {
            name: 'f',
            active: false,
            nodes: [
                { id: 'src', type: 'acquisition', use: 'connection/cdr' },
                { id: 'parse', type: 'parser' }, // no grammar → error
                { id: 'write', type: 'sink.persistent', use: 'sink/out' },
            ],
            edges: [
                { from: 'src', rel: 'data', to: 'parse' },
                { from: 'parse', rel: 'data', to: 'write' },
            ],
        };
        const findings = validatePipeline(flow, typeCat, refs, new Map());
        expect(findings.some((f) => f.severity === 'error' && f.nodeId === 'parse')).toBe(true);
    });

    it('warns when there is no source or no sink', () => {
        const flow: AuthoredPipeline = {
            name: 'f',
            active: false,
            nodes: [{ id: 'parse', type: 'parser', use: 'grammar/cdr_csv' }],
            edges: [],
        };
        const findings = validatePipeline(flow, typeCat, refs, new Map());
        expect(findings.some((f) => /no source/i.test(f.message))).toBe(true);
        expect(findings.some((f) => /no writer/i.test(f.message))).toBe(true);
    });
});

describe('groupByCategory', () => {
    it('orders groups by the canonical category order, unknown categories last', () => {
        const t = (type: string, category: string): PipelineNodeType => ({
            type,
            category,
            label: type,
            description: '',
            accepts: [],
            emits: [],
            emitsNamedRoutes: false,
            lowerable: true,
        });
        const groups = groupByCategory([
            t('gap', 'CONTROL'),
            t('acquisition', 'SOURCE'),
            t('x', 'WEIRD'),
            t('sink.view', 'SINK'),
        ]);
        expect(groups.map((g) => g.category)).toEqual(['SOURCE', 'SINK', 'CONTROL', 'WEIRD']);
    });
});

describe('nodeConfigEntries', () => {
    it('stringifies non-string config values for display', () => {
        const n: AuthoredNode = { id: 'n', type: 't', config: { a: 'x', b: 42, c: { nested: true } } };
        expect(nodeConfigEntries(n)).toEqual([
            { k: 'a', v: 'x' },
            { k: 'b', v: '42' },
            { k: 'c', v: '{"nested":true}' },
        ]);
    });

    it('is empty for a node with no config', () => {
        expect(nodeConfigEntries({ id: 'n', type: 't' })).toEqual([]);
    });
});

describe('canvas edge-id codec', () => {
    it('round-trips (from, to, rel) through encode/decode', () => {
        const id = encodeEdgeId('src', 'dst', 'data');
        expect(decodeEdgeId(id)).toEqual({ from: 'src', to: 'dst', rel: 'data' });
    });

    it('two encodes of the same triple stay distinct ids (nonce)', () => {
        expect(encodeEdgeId('a', 'b', 'data') === encodeEdgeId('a', 'b', 'data')).toBe(false);
    });

    it('decode returns null for a malformed id', () => {
        expect(decodeEdgeId('not-an-edge-id')).toBeNull();
    });
});

describe('uniqueNodeId', () => {
    it('sanitizes the type into a base id and starts at _1', () => {
        expect(uniqueNodeId(null, 'transform.filter')).toBe('transform_filter_1');
    });

    it('skips ids already present on the model', () => {
        const model: AuthoredPipeline = {
            name: 'f',
            active: false,
            nodes: [
                { id: 'parser_1', type: 'parser' },
                { id: 'parser_2', type: 'parser' },
            ],
            edges: [],
        };
        expect(uniqueNodeId(model, 'parser')).toBe('parser_3');
    });
});

describe('authored-model reducers', () => {
    const base: AuthoredPipeline = {
        name: 'f',
        active: false,
        nodes: [
            { id: 'a', type: 'acquisition' },
            { id: 'b', type: 'transform.filter' },
        ],
        edges: [{ from: 'a', rel: 'data', to: 'b' }],
    };

    it('addNodeToModel appends without mutating the input', () => {
        const next = addNodeToModel(base, { id: 'c', type: 'sink.persistent' });
        expect(next.nodes).toHaveLength(3);
        expect(base.nodes).toHaveLength(2); // original untouched
    });

    it('addEdgeToModel appends a new edge, and returns null for a duplicate', () => {
        const next = addEdgeToModel(base, 'b', 'c', 'data');
        expect(next?.edges).toHaveLength(2);
        expect(addEdgeToModel(base, 'a', 'b', 'data')).toBeNull();
    });

    it('removeNodeFromModel drops the node and every edge touching it', () => {
        const next = removeNodeFromModel(base, 'a');
        expect(next.nodes.map((n) => n.id)).toEqual(['b']);
        expect(next.edges).toHaveLength(0);
    });

    it('removeEdgeFromModel drops only the matching edge', () => {
        const withTwo = { ...base, edges: [...base.edges, { from: 'b', rel: 'data', to: 'a' }] };
        const next = removeEdgeFromModel(withTwo, 'a', 'b', 'data');
        expect(next.edges).toEqual([{ from: 'b', rel: 'data', to: 'a' }]);
    });

    it('setEdgeRelInModel relabels, and returns null when unchanged or colliding', () => {
        const next = setEdgeRelInModel(base, 'a', 'b', 'data', 'dropped');
        expect(next?.edges[0].rel).toBe('dropped');
        expect(setEdgeRelInModel(base, 'a', 'b', 'data', 'data')).toBeNull(); // unchanged
        // The collision case needs the TARGET rel to already exist on the same pair.
        const withTwo = { ...base, edges: [...base.edges, { from: 'a', rel: 'dropped', to: 'b' }] };
        expect(setEdgeRelInModel(withTwo, 'a', 'b', 'data', 'dropped')).toBeNull(); // would collide
    });

    it('applyNodePatchInModel replaces a node by id', () => {
        const patched: AuthoredNode = { id: 'a', type: 'acquisition', name: 'Renamed' };
        const next = applyNodePatchInModel(base, patched);
        expect(next.nodes.find((n) => n.id === 'a')?.name).toBe('Renamed');
    });
});

describe('candidateRelsFor', () => {
    it("offers the source node's emitted rels plus data and the edge's current rel", () => {
        const model: AuthoredPipeline = {
            name: 'f',
            active: false,
            nodes: [
                { id: 'a', type: 'transform.filter' },
                { id: 'b', type: 'sink.persistent' },
            ],
            edges: [],
        };
        const emits = new Map([['transform.filter', ['kept', 'dropped']]]);
        const id = encodeEdgeId('a', 'b', 'kept');
        expect(candidateRelsFor(model, id, emits)).toEqual(['data', 'kept', 'dropped']);
    });

    it('returns an empty list for a malformed edge id', () => {
        expect(candidateRelsFor(null, 'bad', new Map())).toEqual([]);
    });

    it("drops a rel the TARGET refuses, but never the edge's current one", () => {
        const model: AuthoredPipeline = {
            name: 'f',
            active: false,
            nodes: [
                { id: 'a', type: 'transform.filter' },
                { id: 'b', type: 'gap' },
            ],
            edges: [],
        };
        const emits = new Map([['transform.filter', ['dropped']]]);
        const rules = new Map<string, EdgeRules>([
            ['transform.filter', { accepts: ['data'], emits: ['data', 'dropped'], emitsNamedRoutes: false }],
            // a gap node takes only `gap` — neither `data` nor `dropped`
            ['gap', { accepts: ['gap'], emits: [], emitsNamedRoutes: false }],
        ]);
        const id = encodeEdgeId('a', 'b', 'gap');
        // without rules the picker offers everything the source emits — the old, looser behaviour
        expect(candidateRelsFor(model, id, emits)).toEqual(['data', 'dropped', 'gap']);
        // with them, only the current rel survives: the target accepts neither of the others
        expect(candidateRelsFor(model, id, emits, rules)).toEqual(['gap']);
    });
});

/**
 * 🔴 A mirror of the server's `PipelineValidator` edge checks. It must be exact in BOTH directions:
 * looser and the canvas builds a graph the save 422s; stricter and it greys out work the backend
 * would happily store.
 */
describe('edgeRefusal (mirrors PipelineValidator)', () => {
    const model: AuthoredPipeline = {
        name: 'f',
        active: false,
        nodes: [
            { id: 'src', type: 'transform.filter' },
            { id: 'sink', type: 'sink.persistent' },
            { id: 'gap', type: 'gap' },
            { id: 'plugin', type: 'some.served.plugin' },
        ],
        edges: [],
    };
    const rules = new Map<string, EdgeRules>([
        ['transform.filter', { accepts: ['data'], emits: ['data', 'dropped'], emitsNamedRoutes: false }],
        ['sink.persistent', { accepts: ['data'], emits: [], emitsNamedRoutes: false }],
        ['gap', { accepts: ['gap'], emits: ['gap'], emitsNamedRoutes: false }],
        ['router', { accepts: ['data'], emits: ['data'], emitsNamedRoutes: true }],
    ]);

    it('allows a data edge into a target that accepts data', () => {
        expect(edgeRefusal(model, 'src', 'sink', 'data', rules)).toBeNull();
    });

    it('refuses a rel the source does not emit (ILLEGAL_EMIT)', () => {
        expect(edgeRefusal(model, 'src', 'sink', 'invalid', rules)).toContain('does not emit');
    });

    it('refuses a data edge into a target that does not accept data (ILLEGAL_ACCEPT)', () => {
        // the gap node accepts only `gap`
        const refusal = edgeRefusal(model, 'src', 'gap', 'data', rules);
        expect(refusal).toContain('does not accept data');
    });

    it('accepts an outcome edge when the target accepts data — the handler exemption', () => {
        // sink accepts only `data`, yet a `dropped` stream is rows to a row-consumer
        expect(edgeRefusal(model, 'src', 'sink', 'dropped', rules)).toBeNull();
    });

    it('refuses an outcome edge the target neither names nor consumes as rows (ILLEGAL_PAIRING)', () => {
        const gapOnly = new Map(rules);
        gapOnly.set('sink.persistent', { accepts: ['gap'], emits: [], emitsNamedRoutes: false });
        expect(edgeRefusal(model, 'src', 'sink', 'dropped', gapOnly)).toContain('does not consume rows');
    });

    it('exempts on_commit — a cross-pipeline trigger whose target is not a local node', () => {
        const withOnCommit = new Map(rules);
        withOnCommit.set('transform.filter', {
            accepts: ['data'],
            emits: ['data', 'dropped', 'on_commit'],
            emitsNamedRoutes: false,
        });
        withOnCommit.set('sink.persistent', { accepts: ['gap'], emits: [], emitsNamedRoutes: false });
        expect(edgeRefusal(model, 'src', 'sink', 'on_commit', withOnCommit)).toBeNull();
    });

    it('⚠ exempts an UNKNOWN type, exactly as the server does inside ifPresent', () => {
        // a served/plugin type this map has not seen must not be refused
        expect(edgeRefusal(model, 'plugin', 'sink', 'anything', rules)).toBeNull();
        expect(edgeRefusal(model, 'src', 'plugin', 'data', rules)).toBeNull();
    });

    it('allows a route:* branch only from a source that emits named routes', () => {
        const routed: AuthoredPipeline = {
            ...model,
            nodes: [...model.nodes, { id: 'r', type: 'router' }],
        };
        expect(edgeRefusal(routed, 'r', 'sink', 'route:emea', rules)).toBeNull();
        // ...and the plain filter, which does not, is refused
        expect(edgeRefusal(routed, 'src', 'sink', 'route:emea', rules)).toContain('does not emit');
        // a bare `route:` with no key is not a named route
        expect(edgeRefusal(routed, 'r', 'sink', 'route:', rules)).toContain('does not emit');
    });
});

// ── Recipe view: chain detection (ELT amendment UI plan §1) ────────────────────────────────────────

const an = (id: string, type = 'transform.filter', config?: Record<string, unknown>): AuthoredNode => ({
    id,
    type,
    config,
});

const linearPipeline = (): AuthoredPipeline => ({
    name: 'p',
    active: false,
    nodes: [an('collect-1', 'acquisition'), an('parse-1', 'parser'), an('sink-1', 'sink.persistent')],
    edges: [
        { from: 'collect-1', rel: 'data', to: 'parse-1' },
        { from: 'parse-1', rel: 'data', to: 'sink-1' },
    ],
});

describe('detectStepChain', () => {
    it('detects a simple linear chain from the single entry node', () => {
        const chain = detectStepChain(linearPipeline());
        expect(chain).not.toBeNull();
        expect(chain!.trunk.map((n) => n.id)).toEqual(['collect-1', 'parse-1', 'sink-1']);
        expect(chain!.branches).toBeUndefined();
    });

    it('detects a route node as a branch point, each branch its own chain', () => {
        const model: AuthoredPipeline = {
            name: 'p',
            active: false,
            nodes: [
                an('collect-1', 'acquisition'),
                an('route-1', 'transform.route', {
                    mode: 'case',
                    branches: [{ key: 'emea', where: "region = 'EU'" }, { key: 'other' }],
                    default: 'other',
                }),
                an('sink-emea', 'sink.persistent'),
                an('sink-other', 'sink.persistent'),
            ],
            edges: [
                { from: 'collect-1', rel: 'data', to: 'route-1' },
                { from: 'route-1', rel: 'route:emea', to: 'sink-emea' },
                { from: 'route-1', rel: 'route:other', to: 'sink-other' },
            ],
        };
        const chain = detectStepChain(model);
        expect(chain).not.toBeNull();
        expect(chain!.trunk.map((n) => n.id)).toEqual(['collect-1', 'route-1']);
        expect(chain!.branches).toHaveLength(2);
        const emea = chain!.branches!.find((b) => b.key === 'emea')!;
        expect(emea.routeId).toBe('route-1'); // branch edits target the owning route node (S3)
        expect(emea.where).toBe("region = 'EU'");
        expect(emea.isDefault).toBe(false);
        expect(emea.chain.trunk.map((n) => n.id)).toEqual(['sink-emea']);
        const other = chain!.branches!.find((b) => b.key === 'other')!;
        expect(other.isDefault).toBe(true);
    });

    it('returns null for fan-in (two edges into the same node)', () => {
        const model: AuthoredPipeline = {
            name: 'p',
            active: false,
            nodes: [an('a'), an('b'), an('c')],
            edges: [
                { from: 'a', rel: 'data', to: 'c' },
                { from: 'b', rel: 'data', to: 'c' },
            ],
        };
        expect(detectStepChain(model)).toBeNull();
    });

    it('returns null when there is no single entry node', () => {
        const model: AuthoredPipeline = {
            name: 'p',
            active: false,
            nodes: [an('a'), an('b')],
            edges: [],
        };
        expect(detectStepChain(model)).toBeNull();
    });

    it('returns null for a mixed fan-out (data alongside a non-route, non-guarantee relationship)', () => {
        // `failure` is a control edge with no Guarantee semantics — unlike `gap`/`unmatched`
        // (which S2 tolerates as housekeeping side-nodes), this stays Canvas-only.
        const model: AuthoredPipeline = {
            name: 'p',
            active: false,
            nodes: [an('a'), an('b'), an('c')],
            edges: [
                { from: 'a', rel: 'data', to: 'b' },
                { from: 'a', rel: 'failure', to: 'c' },
            ],
        };
        expect(detectStepChain(model)).toBeNull();
    });

    it('returns null for a graph with no nodes', () => {
        expect(detectStepChain({ name: 'p', active: false, nodes: [], edges: [] })).toBeNull();
    });
});

describe('flattenStepChain', () => {
    it('tolerates guarantee side-nodes (gap watch) instead of forcing Canvas', () => {
        const model: AuthoredPipeline = {
            name: 'p',
            active: false,
            nodes: [an('acq', 'acquisition'), an('gap', 'gap'), an('parse', 'parser'), an('sink', 'sink.persistent')],
            edges: [
                { from: 'acq', rel: 'gap', to: 'gap' },
                { from: 'acq', rel: 'data', to: 'parse' },
                { from: 'parse', rel: 'data', to: 'sink' },
            ],
        };
        const chain = detectStepChain(model);
        expect(chain).not.toBeNull();
        expect(chain!.trunk.map((n) => n.id)).toEqual(['acq', 'parse', 'sink']);
    });

    it('flattens a linear trunk with depth 0 throughout', () => {
        const chain = detectStepChain(linearPipeline())!;
        const rows = flattenStepChain(chain);
        expect(rows).toHaveLength(3);
        expect(rows.every((r) => r.depth === 0 && r.kind === 'node')).toBe(true);
    });

    it('inserts a branch-header row before each branch and indents its chain one level deeper', () => {
        const chain = {
            trunk: [an('collect-1')],
            branches: [
                {
                    routeId: 'r',
                    key: 'emea',
                    where: "region='EU'",
                    isDefault: false,
                    chain: { trunk: [an('sink-emea')] },
                },
                { routeId: 'r', key: 'other', isDefault: true, chain: { trunk: [an('sink-other')] } },
            ],
        };
        const rows = flattenStepChain(chain);
        expect(rows.map((r) => r.kind)).toEqual(['node', 'branch', 'node', 'branch', 'node']);
        const emeaHeader = rows[1];
        expect(emeaHeader.kind).toBe('branch');
        if (emeaHeader.kind === 'branch') {
            expect(emeaHeader.key).toBe('emea');
            expect(emeaHeader.where).toBe("region='EU'");
            expect(emeaHeader.depth).toBe(0);
        }
        expect(rows[2].depth).toBe(1); // sink-emea, one level under its branch header
    });
});

// ── Recipe editing reducers (UI plan S2) ───────────────────────────────────────────────────────────

describe('insertStepAfter', () => {
    it('splices a node into the trunk, rewiring after → node → next', () => {
        const next = insertStepAfter(linearPipeline(), an('dedup-1', 'transform.dedup'), 'parse-1')!;
        expect(next).not.toBeNull();
        expect(detectStepChain(next)!.trunk.map((n) => n.id)).toEqual(['collect-1', 'parse-1', 'dedup-1', 'sink-1']);
    });

    it('inserts as the new entry when afterId is null', () => {
        const next = insertStepAfter(linearPipeline(), an('collect-0', 'acquisition'), null)!;
        expect(detectStepChain(next)!.trunk.map((n) => n.id)).toEqual(['collect-0', 'collect-1', 'parse-1', 'sink-1']);
    });

    it('appends after the tail (no next edge to rewire)', () => {
        const next = insertStepAfter(linearPipeline(), an('sink-2', 'sink.persistent'), 'sink-1')!;
        expect(detectStepChain(next)!.trunk.map((n) => n.id)).toEqual(['collect-1', 'parse-1', 'sink-1', 'sink-2']);
    });

    it('ignores guarantee side-edges when rewiring (gap watch stays on the collect node)', () => {
        const model: AuthoredPipeline = {
            name: 'p',
            active: false,
            nodes: [an('acq', 'acquisition'), an('gap', 'gap'), an('sink', 'sink.persistent')],
            edges: [
                { from: 'acq', rel: 'gap', to: 'gap' },
                { from: 'acq', rel: 'data', to: 'sink' },
            ],
        };
        const next = insertStepAfter(model, an('parse', 'parser'), 'acq')!;
        expect(next).not.toBeNull();
        expect(next.edges).toContainEqual({ from: 'acq', rel: 'gap', to: 'gap' });
        expect(detectStepChain(next)!.trunk.map((n) => n.id)).toEqual(['acq', 'parse', 'sink']);
    });

    it('refuses to insert after a branch point and on a duplicate id', () => {
        const routed: AuthoredPipeline = {
            name: 'p',
            active: false,
            nodes: [an('r', 'transform.route'), an('a', 'sink.persistent'), an('b', 'sink.persistent')],
            edges: [
                { from: 'r', rel: 'route:a', to: 'a' },
                { from: 'r', rel: 'route:b', to: 'b' },
            ],
        };
        expect(insertStepAfter(routed, an('x'), 'r')).toBeNull();
        expect(insertStepAfter(linearPipeline(), an('parse-1'), 'collect-1')).toBeNull();
    });
});

describe('removeStepFromChain', () => {
    it('removes a mid-chain node and reconnects its neighbours', () => {
        const next = removeStepFromChain(linearPipeline(), 'parse-1')!;
        expect(detectStepChain(next)!.trunk.map((n) => n.id)).toEqual(['collect-1', 'sink-1']);
    });

    it('removes the entry and the tail without fabricating edges', () => {
        expect(detectStepChain(removeStepFromChain(linearPipeline(), 'collect-1')!)!.trunk.map((n) => n.id)).toEqual([
            'parse-1',
            'sink-1',
        ]);
        expect(detectStepChain(removeStepFromChain(linearPipeline(), 'sink-1')!)!.trunk.map((n) => n.id)).toEqual([
            'collect-1',
            'parse-1',
        ]);
    });

    it('refuses a node wired beyond the trunk (branch point / non-data edges)', () => {
        const model: AuthoredPipeline = {
            name: 'p',
            active: false,
            nodes: [an('acq', 'acquisition'), an('gap', 'gap'), an('sink', 'sink.persistent')],
            edges: [
                { from: 'acq', rel: 'gap', to: 'gap' },
                { from: 'acq', rel: 'data', to: 'sink' },
            ],
        };
        expect(removeStepFromChain(model, 'acq')).toBeNull();
    });
});

describe('moveStepInChain', () => {
    it('swaps a node with its predecessor / successor, keeping the chain intact', () => {
        const up = moveStepInChain(linearPipeline(), 'parse-1', 'up')!;
        expect(detectStepChain(up)!.trunk.map((n) => n.id)).toEqual(['parse-1', 'collect-1', 'sink-1']);

        const down = moveStepInChain(linearPipeline(), 'parse-1', 'down')!;
        expect(detectStepChain(down)!.trunk.map((n) => n.id)).toEqual(['collect-1', 'sink-1', 'parse-1']);
    });

    it('refuses at the ends of the trunk', () => {
        expect(moveStepInChain(linearPipeline(), 'collect-1', 'up')).toBeNull();
        expect(moveStepInChain(linearPipeline(), 'sink-1', 'down')).toBeNull();
    });
});

// ── S2 gate: recipe edit → lower → re-lift, byte-stable on untouched sections ──────────────────────

describe('recipe edit round trip (over the mock lift/lower, which mirrors the server)', () => {
    it('an inserted Step survives lower → lift, and untouched sections stay byte-stable', async () => {
        const { liftConfig, lowerGraph } = await import('app/modules/admin/pipelines/pipeline-editable');
        const cfg = {
            name: 'orders',
            active: false,
            collector: { id: 'SRC', duplicate: { mode: 'checksum' } },
            dirs: { poll: '/in', database: '/db', quarantine: '/db/q' },
            parsing: { grammar: 'grammar/pipe', header: true },
            processing: { file_pattern: 'glob:**/*.csv' },
            output: { format: 'PARQUET' },
        } as Record<string, unknown>;

        const lifted = liftConfig(structuredClone(cfg));
        const edited = insertStepAfter(
            lifted,
            { id: 'flt', type: 'transform.filter', config: { where: 'AMT > 0' } },
            'parse',
        )!;
        expect(edited).not.toBeNull();

        const lowered = lowerGraph(edited, structuredClone(cfg), false);
        expect('config' in lowered, JSON.stringify(lowered)).toBe(true);
        const out = (lowered as { config: Record<string, unknown> }).config;

        // untouched sections byte-stable (the S2 gate)
        expect(out['collector']).toEqual(cfg['collector']);
        expect(out['parsing']).toEqual(cfg['parsing']);
        expect((out['dirs'] as Record<string, unknown>)['quarantine']).toBe('/db/q');
        // the edit itself landed
        expect(
            ((out['processing'] as Record<string, unknown>)['csv_settings'] as Record<string, unknown>)['where'],
        ).toBe('AMT > 0');
        // and the round trip re-lifts to a chain carrying the new Step
        const relifted = detectStepChain(liftConfig(out));
        expect(relifted!.trunk.some((n) => n.type === 'transform.filter')).toBe(true);
    });
});

// ── S3: route branch reducers + the route parity round trip ────────────────────────────────────────

/** A routed pipeline: collect → route with two branches, each feeding its own sink. */
const routedPipeline = (): AuthoredPipeline => ({
    name: 'p',
    active: false,
    nodes: [
        an('collect-1', 'acquisition'),
        an('route-1', 'transform.route', {
            mode: 'case',
            branches: [{ key: 'emea', where: "region = 'EU'" }, { key: 'other' }],
            default: 'other',
        }),
        an('sink-emea', 'sink.persistent'),
        an('sink-other', 'sink.persistent'),
    ],
    edges: [
        { from: 'collect-1', rel: 'data', to: 'route-1' },
        { from: 'route-1', rel: 'route:emea', to: 'sink-emea' },
        { from: 'route-1', rel: 'route:other', to: 'sink-other' },
    ],
});

describe('addRouteBranch', () => {
    it('adds the branch entry plus a sink wired via route:<key>, carrying a derived destination', () => {
        const next = addRouteBranch(routedPipeline(), 'route-1', 'apac')!;
        expect(next).not.toBeNull();
        const route = next.nodes.find((n) => n.id === 'route-1')!;
        expect((route.config!['branches'] as { key: string }[]).map((b) => b.key)).toEqual(['emea', 'other', 'apac']);
        const edge = next.edges.find((e) => e.rel === 'route:apac')!;
        expect(edge.from).toBe('route-1');
        const sink = next.nodes.find((n) => n.id === edge.to)!;
        expect(sink.type).toBe('sink.persistent');
        // 🔴 `database` is the branch↔sink join key on BOTH halves of the round-trip, so a sink created
        // without one lowers to nothing and the branch loses its target. This fixture's existing sinks
        // declare no database, so the destination falls back to the scaffold's `data/<name>` convention.
        expect(sink.config!['database']).toBe('data/p/apac/database');
        // the whole thing stays recipe-expressible
        expect(detectStepChain(next)!.branches!.map((b) => b.key)).toEqual(['emea', 'other', 'apac']);
    });

    /** With a primary sink present the branch store lands BESIDE it, whatever its path. */
    it('derives the branch store beside the primary sink home', () => {
        const withPrimary: AuthoredPipeline = {
            name: 'orders',
            active: false,
            nodes: [
                an('route-1', 'transform.route', { mode: 'case', branches: [] }),
                an('sink', 'sink.persistent', { database: '/srv/lake/orders/database' }),
            ],
            edges: [],
        };
        const next = addRouteBranch(withPrimary, 'route-1', 'late')!;
        const added = next.nodes.find((n) => n.id !== 'sink' && n.type === 'sink.persistent')!;
        expect(added.config!['database']).toBe('/srv/lake/orders/late/database');
        expect(added.config!['backup']).toBe('/srv/lake/orders/late/backup');
    });

    it('refuses a duplicate key (the recipe branches map would silently collapse it server-side) and a blank key', () => {
        expect(addRouteBranch(routedPipeline(), 'route-1', 'emea')).toBeNull();
        expect(addRouteBranch(routedPipeline(), 'route-1', '  ')).toBeNull();
        expect(addRouteBranch(routedPipeline(), 'sink-emea', 'x')).toBeNull(); // not a route node
    });
});

describe('removeRouteBranch', () => {
    it('removes the entry, the edge, and the branch subtree; clears default when it pointed there', () => {
        const next = removeRouteBranch(routedPipeline(), 'route-1', 'other')!;
        expect(next).not.toBeNull();
        expect(next.nodes.some((n) => n.id === 'sink-other')).toBe(false);
        expect(next.edges.some((e) => e.rel === 'route:other')).toBe(false);
        const route = next.nodes.find((n) => n.id === 'route-1')!;
        expect((route.config!['branches'] as { key: string }[]).map((b) => b.key)).toEqual(['emea']);
        expect(route.config!['default']).toBeUndefined();
    });

    it('refuses when a branch node is reachable from outside the branch (fan-in — canvas work)', () => {
        const m = routedPipeline();
        m.edges.push({ from: 'sink-emea', rel: 'data', to: 'sink-other' });
        expect(removeRouteBranch(m, 'route-1', 'other')).toBeNull();
    });
});

describe('setRouteBranchWhere / setRouteDefault', () => {
    it('sets and clears a branch predicate on the route node config', () => {
        const set = setRouteBranchWhere(routedPipeline(), 'route-1', 'other', "region <> 'EU'")!;
        const branches = set.nodes.find((n) => n.id === 'route-1')!.config!['branches'] as {
            key: string;
            where?: string;
        }[];
        expect(branches.find((b) => b.key === 'other')!.where).toBe("region <> 'EU'");

        const cleared = setRouteBranchWhere(set, 'route-1', 'other', '  ')!;
        const cb = cleared.nodes.find((n) => n.id === 'route-1')!.config!['branches'] as {
            key: string;
            where?: string;
        }[];
        expect('where' in cb.find((b) => b.key === 'other')!).toBe(false);
        expect(setRouteBranchWhere(routedPipeline(), 'route-1', 'nope', 'x')).toBeNull();
    });

    it('marks zero-or-one default via the scalar default key (the engine contract — no exactly-one rule exists)', () => {
        const set = setRouteDefault(routedPipeline(), 'route-1', 'emea')!;
        expect(set.nodes.find((n) => n.id === 'route-1')!.config!['default']).toBe('emea');
        const cleared = setRouteDefault(set, 'route-1', null)!;
        expect(cleared.nodes.find((n) => n.id === 'route-1')!.config!['default']).toBeUndefined();
        expect(setRouteDefault(routedPipeline(), 'route-1', 'nope')).toBeNull(); // default must name a branch
    });
});

describe('insertRouteAfter', () => {
    it('splices the route in, rewiring the downstream edge as its first branch', () => {
        const route = an('route-1', 'transform.route', { mode: 'case', branches: [{ key: 'branch_1' }] });
        const next = insertRouteAfter(linearPipeline(), route, 'parse-1')!;
        expect(next).not.toBeNull();
        expect(next.edges).toContainEqual({ from: 'parse-1', rel: 'data', to: 'route-1' });
        expect(next.edges).toContainEqual({ from: 'route-1', rel: 'route:branch_1', to: 'sink-1' });
        const chain = detectStepChain(next)!;
        expect(chain.trunk.map((n) => n.id)).toEqual(['collect-1', 'parse-1', 'route-1']);
        expect(chain.branches!.map((b) => b.key)).toEqual(['branch_1']);
    });

    it('refuses at the tail (nothing to route to) and without a seeded branch', () => {
        const route = an('route-1', 'transform.route', { mode: 'case', branches: [{ key: 'branch_1' }] });
        expect(insertRouteAfter(linearPipeline(), route, 'sink-1')).toBeNull();
        expect(insertRouteAfter(linearPipeline(), an('route-2', 'transform.route'), 'parse-1')).toBeNull();
    });
});

describe('insertBranchHead (MIDBRANCH-UI-1)', () => {
    it("makes the node the branch's first Step, keeping the route:<key> edge on the route side", () => {
        const next = insertBranchHead(routedPipeline(), an('filter-emea'), 'route-1', 'emea')!;
        expect(next).not.toBeNull();
        expect(next.edges).toContainEqual({ from: 'route-1', rel: 'route:emea', to: 'filter-emea' });
        expect(next.edges).toContainEqual({ from: 'filter-emea', rel: 'data', to: 'sink-emea' });
        expect(next.edges).not.toContainEqual({ from: 'route-1', rel: 'route:emea', to: 'sink-emea' });
        const chain = detectStepChain(next)!;
        const emea = chain.branches!.find((b) => b.key === 'emea')!;
        expect(emea.chain.trunk.map((n) => n.id)).toEqual(['filter-emea', 'sink-emea']);
        // the other branch is untouched
        expect(chain.branches!.find((b) => b.key === 'other')!.chain.trunk.map((n) => n.id)).toEqual(['sink-other']);
    });

    it('then inserting AFTER an in-branch Step is plain insertStepAfter, and the tail refuses nothing new', () => {
        const headed = insertBranchHead(routedPipeline(), an('filter-emea'), 'route-1', 'emea')!;
        const next = insertStepAfter(headed, an('dedup-emea', 'transform.dedup'), 'filter-emea')!;
        const emea = detectStepChain(next)!.branches!.find((b) => b.key === 'emea')!;
        expect(emea.chain.trunk.map((n) => n.id)).toEqual(['filter-emea', 'dedup-emea', 'sink-emea']);
    });

    it('refuses an unknown branch or a taken node id', () => {
        expect(insertBranchHead(routedPipeline(), an('x'), 'route-1', 'nope')).toBeNull();
        expect(insertBranchHead(routedPipeline(), an('sink-emea'), 'route-1', 'emea')).toBeNull();
    });
});

describe('RECIPE_VERBS fallback vs the served step-types contract (S4 dual-read)', () => {
    it('every client verb maps to the same node type the server publishes, in the same order', async () => {
        const contract = (await import('app/inspecto/contracts/step-types.contract.json')).default as {
            verb: string;
            type: string;
            lowerable: boolean;
        }[];
        const { RECIPE_VERBS } = await import('./pipeline-graph');
        // the served builtins are exactly the fallback map — a drift here means one side changed alone
        expect(contract.map((c) => c.type)).toEqual(RECIPE_VERBS.map((v) => v.type));
        for (const c of contract) expect(c.lowerable, `${c.verb} must be saveable`).toBe(true);
    });

    /** `type` is an entry's only key: the served `verb` is NOT unique — it names both filter and join
     *  (the recipe spells a join `transform: {join: …}`) — which is why the client tuple omits it. */
    it('every entry has a unique type, and both transform shapes are offered', async () => {
        const { RECIPE_VERBS } = await import('./pipeline-graph');
        const types = RECIPE_VERBS.map((v) => v.type);
        expect(new Set(types).size, `duplicate type in RECIPE_VERBS: ${types.join(', ')}`).toBe(types.length);
        expect(types).toContain('transform.filter');
        expect(types).toContain('transform.join');
    });
});

describe('route parity round trip (mock lift/lower mirrors PipelineEditable/PipelineLift)', () => {
    it('a route: block lifts to branches, an added branch lowers back with its database stamped', async () => {
        const { liftConfig, lowerGraph } = await import('app/modules/admin/pipelines/pipeline-editable');
        const cfg = {
            name: 'orders',
            active: false,
            collector: { id: 'SRC' },
            dirs: { poll: '/in', database: '/db' },
            parsing: { grammar: 'grammar/pipe' },
            output: { format: 'PARQUET' },
            route: {
                mode: 'case',
                branches: [{ key: 'main', where: 'AMT > 0', database: '/db' }],
            },
        } as Record<string, unknown>;

        // lift: the route node exists and its branch pairs with the sink by database
        const lifted = liftConfig(structuredClone(cfg));
        expect(lifted.nodes.some((n) => n.type === 'transform.route')).toBe(true); // mock is NOT stricter than the server
        const chain = detectStepChain(lifted)!;
        expect(chain.branches!.map((b) => b.key)).toEqual(['main']);

        // edit: add a branch, configure its sink's destination
        let edited = addRouteBranch(lifted, 'route', 'errors')!;
        const errSink = edited.edges.find((e) => e.rel === 'route:errors')!.to;
        const sink = edited.nodes.find((n) => n.id === errSink)!;
        edited = applyNodePatchInModel(edited, { ...sink, config: { ...sink.config, database: '/db-errors' } });
        edited = setRouteBranchWhere(edited, 'route', 'errors', 'AMT <= 0')!;

        // lower: no UNSUPPORTED_NODE refusal; route block carries both branches, databases stamped
        const lowered = lowerGraph(edited, structuredClone(cfg), false);
        expect('config' in lowered, JSON.stringify(lowered)).toBe(true);
        const out = (lowered as { config: Record<string, unknown> }).config;
        const route = out['route'] as { branches: { key: string; where?: string; database?: string }[] };
        expect(route.branches.map((b) => b.key)).toEqual(['main', 'errors']);
        expect(route.branches[1].database).toBe('/db-errors');
        expect(route.branches[1].where).toBe('AMT <= 0');
        // two destinations ⇒ plural sinks: block alongside the shorthand
        expect((out['sinks'] as unknown[]).length).toBe(2);
        // untouched sections stay byte-stable
        expect(out['collector']).toEqual(cfg['collector']);
        expect(out['parsing']).toEqual(cfg['parsing']);

        // and the round trip re-lifts both branches
        const relifted = detectStepChain(liftConfig(out))!;
        expect(relifted.branches!.map((b) => b.key).sort()).toEqual(['errors', 'main']);
    });

    /**
     * 🔴 The same trip WITHOUT hand-configuring the new branch's destination — i.e. what the Recipe
     * editor actually produces. The test above passed while this was broken precisely because it set
     * `database: '/db-errors'` itself, supplying by hand the one thing the UI never supplied: the branch
     * sink was created with an empty config, `routeSection` skips a branch whose target declares no
     * database, `sinks:` is keyed by distinct database — so the branch lowered to NOTHING, the save
     * reported success, and reopening showed the destination gone. Reproduced live 2026-08-17.
     */
    it('keeps a branch added in the editor across a save, with no manual destination', async () => {
        const { liftConfig, lowerGraph } = await import('app/modules/admin/pipelines/pipeline-editable');
        const cfg = {
            name: 'orders',
            active: false,
            collector: { id: 'SRC' },
            dirs: { poll: '/in', database: '/db' },
            parsing: { grammar: 'grammar/pipe' },
            route: { mode: 'case', branches: [{ key: 'main', where: 'AMT > 0', database: '/db' }] },
        } as Record<string, unknown>;

        const edited = addRouteBranch(liftConfig(structuredClone(cfg)), 'route', 'errors')!;
        const lowered = lowerGraph(edited, structuredClone(cfg), false);
        expect('config' in lowered, JSON.stringify(lowered)).toBe(true);
        const out = (lowered as { config: Record<string, unknown> }).config;

        // the branch entry carries a destination, and it is a SECOND one, so `sinks:` is emitted
        const route = out['route'] as { branches: { key: string; database?: string }[] };
        expect(route.branches[1]).toMatchObject({ key: 'errors', database: '/db/errors/database' });
        expect((out['sinks'] as unknown[]).length).toBe(2);

        // …and the branch still has a target after the reopen, which is the whole point
        const relifted = liftConfig(out);
        const branchEdge = relifted.edges.find((e) => e.rel === 'route:errors');
        expect(branchEdge, 'the branch lost its destination on the round trip').toBeDefined();
        expect(relifted.nodes.some((n) => n.id === branchEdge!.to && n.type === 'sink.persistent')).toBe(true);
    });
});
