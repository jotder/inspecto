import { describe, expect, it } from 'vitest';
import { GraphRunView, WorkingSet } from 'app/inspecto/api';
import { EntityProjection } from 'app/inspecto/graph';
import {
    budgetNextAction,
    buildServerIdMap,
    toCommunityMap,
    toGroups,
    toNodeScores,
    toPredictedLinks,
    toSelection,
    toSuspicionScores,
} from './graph-run-apply';
import { workingSetToGraph } from './investigation-state';

const P: EntityProjection = {
    datasetId: 'calls',
    sourceCol: 'A',
    targetCol: 'B',
    linkKindCol: 'K',
    entityType: 'acct',
};

/** The D-U9 wire id: `lk.` + base64url of source NUL target NUL kind - what the server's links relation serves. */
function linkId(source: string, target: string, kind: string | null): string {
    return (
        'lk.' +
        btoa(`${source}\0${target}\0${kind ?? ''}`)
            .replace(/\+/g, '-')
            .replace(/\//g, '_')
            .replace(/=+$/, '')
    );
}

function ws(): WorkingSet {
    const e = (id: string) => ({ id, type: null, hop: 0, seed: id, admittedBy: 1, hidden: false, kept: false });
    const l = (source: string, target: string, kind: string | null, count = 1) => ({
        source,
        target,
        kind,
        count,
        admittedBy: 1,
        linkId: linkId(source, target, kind),
    });
    return {
        entities: [e('Ann'), e('Bob'), e('Cy'), e('Di')],
        // Two links between Ann and Bob that differ only by kind, plus Bob-Cy and a parallel Cy-Di.
        links: [l('Ann', 'Bob', 'calls'), l('Ann', 'Bob', 'texts'), l('Bob', 'Cy', 'calls', 3), l('Cy', 'Di', null)],
        excluded: [],
        hash: 'h',
    };
}

describe('buildServerIdMap (D-4 step 7)', () => {
    const w = ws();
    const canvas = workingSetToGraph(w, P);
    const map = buildServerIdMap(w, P, canvas);
    const edgeOf = (s: string, t: string, kindStartsWith: string) =>
        canvas.edges.find(
            (e) =>
                e.source.endsWith(s.toLowerCase()) &&
                e.target.endsWith(t.toLowerCase()) &&
                e.id.includes(kindStartsWith),
        )!;

    it('maps each server linkId to the canvas edge with the same endpoints AND kind', () => {
        const calls = edgeOf('Ann', 'Bob', ':calls');
        const texts = edgeOf('Ann', 'Bob', ':texts');
        expect(calls.id).not.toBe(texts.id);
        expect(map.edge(linkId('Ann', 'Bob', 'calls'))).toBe(calls.id);
        expect(map.edge(linkId('Ann', 'Bob', 'texts'))).toBe(texts.id);
        // a link with no kind is drawn as kind 'link'
        expect(map.edge(linkId('Cy', 'Di', null))).toBe(edgeOf('Cy', 'Di', ':link').id);
    });

    it('a returned edge selection highlights the intended canvas edge and no other', () => {
        const sel = toSelection({ nodeIds: ['Ann', 'Bob'], edgeIds: [linkId('Ann', 'Bob', 'texts')] }, map);
        expect(sel.edgeIds).toEqual([edgeOf('Ann', 'Bob', ':texts').id]);
        expect(sel.edgeIds).not.toContain(edgeOf('Ann', 'Bob', ':calls').id);
        expect(sel.edgeIds).not.toContain(edgeOf('Bob', 'Cy', ':calls').id);
        expect(sel.nodeIds).toEqual(canvas.nodes.filter((n) => ['Ann', 'Bob'].includes(n.data.label)).map((n) => n.id));
    });

    it('drops ids the canvas does not draw instead of inventing them', () => {
        const sel = toSelection(
            { nodeIds: ['Ann', 'Nobody'], edgeIds: [linkId('Ann', 'Nobody', 'calls'), 'lk.garbage'] },
            map,
        );
        expect(sel.edgeIds).toEqual([]);
        expect(sel.nodeIds).toHaveLength(1);
    });

    it('maps a node by its folded raw spelling too (typed ids fold "0044 1" and "+44 1")', () => {
        const typed: EntityProjection = { ...P, sourceType: { id: 'msisdn', normaliser: 'e164' } };
        const w2: WorkingSet = {
            entities: [{ id: '0044 1', type: null, hop: 0, seed: '', admittedBy: 1, hidden: false, kept: false }],
            links: [
                {
                    source: '0044 1',
                    target: 'Bob',
                    kind: 'call',
                    count: 1,
                    admittedBy: 1,
                    linkId: linkId('0044 1', 'Bob', 'call'),
                },
            ],
            excluded: [],
            hash: 'h',
        };
        const g = workingSetToGraph(w2, typed);
        const m = buildServerIdMap(w2, typed, g);
        expect(m.node('0044 1')).toBe('msisdn:+441');
        expect(m.edge(linkId('0044 1', 'Bob', 'call'))).toBe(g.edges[0].id);
        expect(m.serverNode('msisdn:+441')).toBe('0044 1');
    });
});

describe('server results land on canvas ids (D-4 step 7)', () => {
    const w = ws();
    const canvas = workingSetToGraph(w, P);
    const map = buildServerIdMap(w, P, canvas);
    const id = (label: string) => canvas.nodes.find((n) => n.data.label === label)!.id;

    it('ranked scores keep the server order and the server score, on canvas ids', () => {
        const ranked = toNodeScores(
            [
                { id: 'Bob', label: 'Bob', score: 9.5 },
                { id: 'Ann', label: 'Ann', score: 4 },
                { id: 'Hidden', label: 'Hidden', score: 3 }, // not drawn on this canvas
                { id: 'Cy', label: 'Cy', score: 1 },
            ],
            map,
        );
        expect(ranked).toEqual([
            { id: id('Bob'), label: 'Bob', score: 9.5 },
            { id: id('Ann'), label: 'Ann', score: 4 },
            { id: id('Cy'), label: 'Cy', score: 1 },
        ]);
    });

    it('suspicion scores keep their factors', () => {
        const factors = { degree: 1, betweenness: 2, pageRank: 3, core: 4, triangles: 5 };
        expect(toSuspicionScores([{ id: 'Cy', label: 'Cy', score: 0.7, factors }], map)).toEqual([
            { id: id('Cy'), label: 'Cy', score: 0.7, factors },
        ]);
    });

    it('communities become node -> community on canvas ids, a member-named community included', () => {
        const byNode = toCommunityMap(
            [
                { id: 'Ann', community: 'Ann' },
                { id: 'Bob', community: 'Ann' },
                { id: 'Cy', community: '3' }, // Louvain: a number, left alone
            ],
            map,
        );
        expect(byNode.get(id('Bob'))).toBe(id('Ann'));
        expect(byNode.get(id('Cy'))).toBe('3');
    });

    it('groups and predicted links are translated, dropping what is not drawn', () => {
        expect(toGroups([['Ann', 'Bob', 'Ghost'], ['Ghost']], map)).toEqual([[id('Ann'), id('Bob')]]);
        expect(
            toPredictedLinks(
                [
                    { source: 'Ann', target: 'Cy', sourceLabel: 'Ann', targetLabel: 'Cy', score: 0.5 },
                    { source: 'Ann', target: 'Ghost', sourceLabel: 'Ann', targetLabel: 'Ghost', score: 0.4 },
                ],
                map,
            ),
        ).toEqual([{ source: id('Ann'), target: id('Cy'), sourceLabel: 'Ann', targetLabel: 'Cy', score: 0.5 }]);
    });
});

describe('budgetNextAction', () => {
    const run = (over: Partial<GraphRunView>): GraphRunView =>
        ({
            status: 'BUDGET_EXCEEDED',
            budget: { maxNodes: 500, maxEdges: 5000, timeoutMs: 10_000 },
            consumed: { nodes: 800, edges: 1200, elapsedMs: 10_001, work: 0 },
            ...over,
        }) as GraphRunView;
    const ceilings = { maxNodes: 100_000, maxEdges: 1_000_000, timeoutMs: 600_000 };

    it('offers raising the budget only when what the run needed fits under the server ceiling', () => {
        expect(budgetNextAction(run({ exceeded: 'NODES' }), ceilings)).toMatchObject({
            kind: 'raise',
            budget: { maxNodes: 800 },
        });
        expect(budgetNextAction(run({ exceeded: 'NODES' }), { ...ceilings, maxNodes: 799 })).toMatchObject({
            kind: 'narrow',
        });
        expect(budgetNextAction(run({ exceeded: 'EDGES' }), ceilings)).toMatchObject({
            kind: 'raise',
            budget: { maxEdges: 1200 },
        });
        expect(budgetNextAction(run({ exceeded: 'TIMEOUT' }), ceilings)).toMatchObject({
            kind: 'raise',
            budget: { timeoutMs: 600_000 },
        });
    });

    it('otherwise - a ceiling already reached, a WORK limit, or no ceiling known - says to narrow', () => {
        expect(
            budgetNextAction(
                run({ exceeded: 'TIMEOUT', budget: { maxNodes: 1, maxEdges: 1, timeoutMs: 600_000 } }),
                ceilings,
            ).kind,
        ).toBe('narrow');
        expect(budgetNextAction(run({ exceeded: 'WORK' }), ceilings).kind).toBe('narrow');
        expect(budgetNextAction(run({ exceeded: 'NODES' }), null).kind).toBe('narrow');
    });
});
