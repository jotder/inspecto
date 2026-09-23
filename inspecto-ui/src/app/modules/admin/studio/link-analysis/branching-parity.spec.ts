import { afterEach, describe, expect, it } from 'vitest';
import type { BranchingPatternResult, ProjectionTriple } from 'app/inspecto/api';
import {
    BranchStage,
    G6GraphData,
    GraphSelection,
    configureGraphLimits,
    matchBranchingPattern,
    resetGraphLimits,
} from 'app/inspecto/graph';
import {
    branchingResultToGraph,
    configureProjectionLimits,
    projectTriples,
    resetProjectionLimits,
} from './entity-projection';
import fixture from './branching-parity.fixture.json';

/**
 * LA-14b parity — the browser half. `branching-parity.fixture.json` is ALSO run through the server compiler by
 * `ControlApiInvPatternTest` (inspecto-geo-link), and both assert its one `expected`. The graph is built the way the
 * studio builds it — `projectTriples` over `/inv/projection` rows with `attrCols: [TS, AMOUNT]` — so node keys fold
 * spellings exactly as they do on screen.
 */
type Row = [string, string, string, number];
const rows = fixture.rows as Row[];
const stages = fixture.stages as BranchStage[];

/** `/inv/projection` rows: one per distinct (source, target, TS, AMOUNT), counted. */
function triples(extra: Row[] = [], counts: number[] = []): ProjectionTriple[] {
    return [...rows, ...extra].map(([source, target, ts, amount], i) => ({
        source,
        target,
        kind: null,
        count: counts[i] ?? 1,
        attrs: { TS: ts, AMOUNT: String(amount) },
    }));
}

/** A match in the fixture's comparison form: sorted layers of keys, sorted `source>target@TS` legs. */
function canon(g: G6GraphData, m: GraphSelection & { layers: string[][] }) {
    const key = (id: string) => id.replace(/^entity:/, '');
    const byId = new Map(g.edges.map((e) => [e.id, e]));
    return {
        layers: m.layers.map((l) => l.map(key).sort()),
        edges: m.edgeIds
            .map((id) => byId.get(id)!)
            .map((e) => `${key(e.source)}>${key(e.target)}@${e.data.attrs!['TS']}`)
            .sort(),
    };
}

describe('LA-14b branching parity (browser half of the shared golden)', () => {
    afterEach(() => {
        resetGraphLimits();
        resetProjectionLimits();
    });

    it('the browser matcher finds exactly the golden matches', () => {
        const g = projectTriples(triples(), false);
        const res = matchBranchingPattern(g, stages, { timeAttr: fixture.timeAttr });
        expect(res.refusal).toBeUndefined();
        expect(res.matches.map((m) => canon(g, m))).toEqual(fixture.expected);
    });

    it('on a projection truncated at 2 000 links the ring is cut FIRST — the reason the server half exists', () => {
        // The server sorts `cnt DESC, source, target` and keeps 2 000: 2 100 heavy pairs (count 3) outrank every
        // one-off deposit, so no structuring leg survives and the browser can only refuse. Both browser node caps
        // are lifted so the LINK cap alone is what cuts the ring here (the 500-node projection cap would cut it too).
        configureProjectionLimits({ projectionNodeCap: 10_000 });
        configureGraphLimits({ analysisNodeCap: 10_000 });
        const heavy: Row[] = Array.from({ length: 2100 }, (_, i) => [
            `HEAVY-${i}`,
            `PAYEE-${i}`,
            '2026-04-01 10:00:00',
            50000,
        ]);
        const all = triples(heavy, [...rows.map(() => 1), ...heavy.map(() => 3)]);
        const cut = [...all]
            .sort((a, b) => b.count - a.count || a.source.localeCompare(b.source) || a.target.localeCompare(b.target))
            .slice(0, 2000);
        const g = projectTriples(cut, true);
        const res = matchBranchingPattern(g, stages, { timeAttr: fixture.timeAttr });
        expect(res.matches).toHaveLength(0);
        expect(res.refusal).toContain('No link in this graph passes 900 ≤ AMOUNT < 1000');

        // the twin: the SAME rows uncut — the ring is there, so the refusal above is the cap's doing
        const whole = projectTriples(all, false);
        const found = matchBranchingPattern(whole, stages, { timeAttr: fixture.timeAttr });
        expect(found.matches.map((m) => canon(whole, m))).toEqual(fixture.expected);
    });

    it('a server answer maps onto the working set as the SAME match the browser would draw', () => {
        const g = projectTriples(triples(), false);
        const browser = matchBranchingPattern(g, stages, { timeAttr: fixture.timeAttr }).matches[0];
        // What `POST /inv/pattern/branching` returns for the fixture: raw spellings, legs spelled out.
        const legs = browser.edgeIds.map((id) => g.edges.find((e) => e.id === id)!);
        const raw = (id: string) => g.nodes.find((n) => n.id === id)!.data.spellings![0];
        const res: BranchingPatternResult = {
            matches: [
                {
                    nodeIds: browser.nodeIds.map(raw),
                    edgeIds: legs.map((_, i) => `leg${i}`),
                    layers: browser.layers.map((l) => l.map(raw)),
                },
            ],
            edges: legs.map((e, i) => ({
                id: `leg${i}`,
                source: raw(e.source),
                target: raw(e.target),
                kind: 'link',
                attrs: e.data.attrs as Record<string, string>,
            })),
            truncated: false,
            legCapped: false,
            fences: { maxLegs: 100000, workBudget: 500000, timeoutMs: 5000 },
        };
        const { graph, state } = branchingResultToGraph(res, g);
        expect(graph.edges).toHaveLength(g.edges.length); // every leg reused, none duplicated
        expect(state.matches.map((m) => canon(graph, m))).toEqual(fixture.expected);

        // over an EMPTY working set (the ring was truncated out) the legs are added, and the match still draws
        const fresh = branchingResultToGraph(res, { nodes: [], edges: [] });
        expect(fresh.graph.edges).toHaveLength(legs.length);
        expect(fresh.state.matches.map((m) => canon(fresh.graph, m))).toEqual(fixture.expected);
    });
});
