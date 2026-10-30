import { describe, expect, it } from 'vitest';
import { G6GraphData } from '@inspecto/core/graph';
import { patternFromSelection } from './pattern-from-selection';

/** Five deposits into a hub, then hub → relay → offshore; amounts and times on every link. */
function graph(): G6GraphData {
    const legs: [string, string, string, number, string][] = [
        ...['s1', 's2', 's3', 's4', 's5'].map(
            (s, i) =>
                [s, 'hub', 'pays', 950 + i, `2026-09-01T1${i}:00:00Z`] as [string, string, string, number, string],
        ),
        ['hub', 'r1', 'pays', 4800, '2026-09-01T20:00:00Z'],
        ['r1', 'off', 'wires', 4700, '2026-09-01T22:00:00Z'],
        ['x', 'y', 'pays', 10, '2026-09-01T09:00:00Z'],
    ];
    const kind = (id: string) => (id === 'off' ? 'offshore' : id.startsWith('s') ? 'entity' : 'acct');
    const ids = [...new Set(legs.flatMap(([s, t]) => [s, t]))];
    return {
        nodes: ids.map((id) => ({ id, data: { label: id, kind: kind(id) } })),
        edges: legs.map(([source, target, k, a, at], i) => ({
            id: `tx${i}`,
            source,
            target,
            data: { kind: k, attrs: { AMOUNT: String(a), AT: at } },
        })),
    };
}

describe('patternFromSelection (LA-PRODUCT-NEXT-1 phase 5a)', () => {
    it('reads a three-hop chain as linear steps, in the direction its links run, with their order', () => {
        const d = patternFromSelection(graph(), ['off', 'r1', 'hub', 's1'], 'AT');
        expect(d).toEqual({
            kind: 'path',
            summary: 'A chain of 3 hops.',
            steps: [
                {}, // s1 is untyped: a wildcard start
                { direction: 'out', edgeKind: 'pays', nodeKind: 'acct' },
                { direction: 'out', edgeKind: 'pays', nodeKind: 'acct', afterPrevious: true },
                { direction: 'out', edgeKind: 'wires', nodeKind: 'offshore', afterPrevious: true },
            ],
        });
    });

    it('without a time column a chain carries no order', () => {
        const d = patternFromSelection(graph(), ['s1', 'hub', 'r1'], '');
        expect(d.kind === 'path' && d.steps.some((s) => s.afterPrevious)).toBe(false);
    });

    it('reads a five-leg fan-in as one branching stage with its breadth and a value band', () => {
        const d = patternFromSelection(graph(), ['hub', 's1', 's2', 's3', 's4', 's5'], 'AT');
        expect(d).toEqual({
            kind: 'fan',
            summary: 'A star: 5 send to one.',
            stages: [
                {
                    shape: 'fan-in',
                    minBranches: 5,
                    edgeKind: 'pays',
                    nodeKind: 'acct',
                    threshold: { attr: 'AMOUNT', min: 950, max: 955 },
                },
            ],
        });
    });

    it('a star with legs in and out becomes collect-then-distribute, ordered when the example was', () => {
        const d = patternFromSelection(graph(), ['hub', 's1', 's2', 'r1'], 'AT');
        expect(d.kind).toBe('fan');
        if (d.kind !== 'fan') return;
        expect(d.stages.map((s) => [s.shape, s.minBranches, s.afterPrevious])).toEqual([
            ['fan-in', 2, undefined],
            ['fan-out', 1, true],
        ]);
    });

    it('no value band when the legs carry two numeric columns (a guess)', () => {
        const g = graph();
        for (const e of g.edges) e.data.attrs = { ...e.data.attrs, FEE: '1' };
        const d = patternFromSelection(g, ['hub', 's1', 's2', 's3'], 'AT');
        expect(d.kind === 'fan' && d.stages[0].threshold).toBeUndefined();
    });

    it('refuses what it cannot read, in words', () => {
        expect(patternFromSelection(graph(), ['hub'])).toEqual({
            kind: 'refused',
            reason: 'Select at least two linked entities.',
        });
        expect(patternFromSelection(graph(), ['hub', 's1', 'x', 'y']).kind).toBe('refused'); // not connected
        // a star around hub plus a hop beyond one of its leaves: neither a chain nor a star
        const d = patternFromSelection(graph(), ['hub', 's1', 's2', 's3', 'r1', 'off']);
        expect(d).toEqual({
            kind: 'refused',
            reason: 'Select a chain (each entity linked to the next) or a star (many entities linked to one).',
        });
    });
});
