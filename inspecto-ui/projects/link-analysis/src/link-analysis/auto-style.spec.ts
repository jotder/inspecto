import { describe, expect, it } from 'vitest';
import type { G6GraphData } from '@inspecto/core/graph/graph-types';
import { canvasTheme, ICON_COLOR_SWATCHES } from '@inspecto/core/theme/chart-tokens';
import { SUPER_NODE_KIND } from '@inspecto/link-analysis/graph/graph-analysis';
import {
    AUTO_COLORS,
    AUTO_EDGE_PATTERNS,
    AUTO_NODE_SHAPES,
    NEUTRAL_DISTANCE,
    autoStyle,
    hashName,
    rgbOf,
} from './auto-style';

type NodeSpec = [kind: string, count: number, color?: string];

/** A graph with `count` nodes of each kind and one link per pair of consecutive nodes carrying the given link kinds. */
function graph(nodes: NodeSpec[], edges: [kind: string, count: number][] = []): G6GraphData {
    const g: G6GraphData = { nodes: [], edges: [] };
    for (const [kind, count, color] of nodes)
        for (let i = 0; i < count; i++)
            g.nodes.push({
                id: `${kind}:${i}`,
                data: { label: `${kind}${i}`, kind, ...(color ? { color } : {}) },
            } as never);
    for (const [kind, count] of edges)
        for (let i = 0; i < count; i++)
            g.edges.push({ id: `${kind}->${i}`, source: 'a', target: 'b', data: { kind } } as never);
    return g;
}

describe('autoStyle (colour and shape per node type, colour and pattern per link type)', () => {
    it('gives nothing for no graph, and nothing for a single type: the most common type stays neutral', () => {
        expect(autoStyle(null)).toEqual({ nodeColors: {}, nodeShapes: {}, edgeColors: {}, edgePatterns: {} });
        const one = autoStyle(graph([['account', 5]], [['pays', 3]]));
        expect(one.nodeColors).toEqual({});
        expect(one.nodeShapes).toEqual({});
        expect(one.edgeColors).toEqual({});
        expect(one.edgePatterns).toEqual({});
    });

    it('leaves the most common node type and the most common link type neutral, and styles every other', () => {
        const s = autoStyle(
            graph(
                [
                    ['account', 10],
                    ['device', 3],
                    ['merchant', 2],
                ],
                [
                    ['pays', 8],
                    ['shares', 2],
                    ['calls', 1],
                ],
            ),
        );
        expect(Object.keys(s.nodeColors).sort()).toEqual(['device', 'merchant']);
        expect(Object.keys(s.nodeShapes).sort()).toEqual(['device', 'merchant']);
        expect(Object.keys(s.edgeColors).sort()).toEqual(['calls', 'shares']);
        expect(Object.keys(s.edgePatterns).sort()).toEqual(['calls', 'shares']);
    });

    it('breaks a tie for most common by name, so the result is deterministic', () => {
        const s = autoStyle(
            graph([
                ['b', 4],
                ['a', 4],
                ['c', 1],
            ]),
        );
        expect(Object.keys(s.nodeColors).sort()).toEqual(['b', 'c']); // `a` is the alphabetically first of the tied pair
    });

    it('uses only token colours, real G6 shapes and the three patterns', () => {
        const s = autoStyle(
            graph(
                [
                    ['x', 9],
                    ['p', 1],
                    ['q', 1],
                    ['r', 1],
                ],
                [
                    ['u', 9],
                    ['v', 1],
                    ['w', 1],
                ],
            ),
        );
        for (const c of [...Object.values(s.nodeColors), ...Object.values(s.edgeColors)])
            expect(ICON_COLOR_SWATCHES).toContain(c);
        for (const sh of Object.values(s.nodeShapes)) expect(AUTO_NODE_SHAPES).toContain(sh);
        for (const p of Object.values(s.edgePatterns)) expect(AUTO_EDGE_PATTERNS).toContain(p);
        expect(Object.values(s.nodeShapes)).not.toContain('circle'); // circle belongs to the common type
    });

    it('is stable by type NAME: a type keeps its look when the others change or the order differs', () => {
        const a = autoStyle(
            graph([
                ['account', 10],
                ['device', 3],
                ['merchant', 2],
            ]),
        );
        const b = autoStyle(
            graph([
                ['merchant', 2],
                ['account', 10],
                ['device', 3],
            ]),
        ); // another order
        expect(b.nodeColors).toEqual(a.nodeColors);
        expect(b.nodeShapes).toEqual(a.nodeShapes);
        const withMore = autoStyle(
            graph([
                ['account', 10],
                ['device', 3],
                ['merchant', 2],
                ['zebra', 1],
            ]),
        );
        expect(withMore.nodeColors['device']).toBe(a.nodeColors['device']);
        expect(withMore.nodeShapes['merchant']).toBe(a.nodeShapes['merchant']);
    });

    it('a RARER type that turns up never moves a commoner one, even if its name sorts first and its look clashes', () => {
        const base = autoStyle(
            graph([
                ['account', 10],
                ['device', 3],
                ['merchant', 2],
            ]),
        );
        // `a89` is the case that moved `device` when slots were claimed in name order: an earlier name, a clashing slot.
        for (const rare of ['a89', 'a', 'aa', 'b', 'c0', 'ant', 'ab', 'a1', 'a2', 'a3']) {
            const s = autoStyle(
                graph([
                    ['account', 10],
                    ['device', 3],
                    ['merchant', 2],
                    [rare, 1],
                ]),
            );
            expect(s.nodeColors['device'], rare).toBe(base.nodeColors['device']);
            expect(s.nodeShapes['device'], rare).toBe(base.nodeShapes['device']);
            expect(s.nodeColors['merchant'], rare).toBe(base.nodeColors['merchant']);
            expect(s.nodeShapes['merchant'], rare).toBe(base.nodeShapes['merchant']);
        }
    });

    it('never gives a type the default link colour, so no link type looks neutral by accident', () => {
        const neutral = [canvasTheme(false).edge, canvasTheme(true).edge];
        const far = (c: string): boolean =>
            neutral.every((n) => {
                const [r, g, b] = rgbOf(c);
                const [nr, ng, nb] = rgbOf(n);
                return Math.hypot(r - nr, g - ng, b - nb) >= NEUTRAL_DISTANCE;
            });
        // Equal is not enough: in the dark scheme a grey-blue swatch sat 16 apart from the default link colour.
        for (const c of AUTO_COLORS) expect(far(c), c).toBe(true);
        expect(AUTO_COLORS.length).toBeGreaterThanOrEqual(8);
        expect(ICON_COLOR_SWATCHES.filter((c) => !far(c)).length).toBeGreaterThanOrEqual(2); // the filter really removes some
        expect(AUTO_COLORS.length).toBeLessThan(ICON_COLOR_SWATCHES.length);
        const many = autoStyle(
            graph(
                [['n', 1]],
                [['common', 40], ...Array.from({ length: 30 }, (_, i): [string, number] => [`l${i}`, 1])],
            ),
        );
        for (const c of Object.values(many.edgeColors)) expect(neutral).not.toContain(c.toLowerCase());
    });

    it('separates two types that hash to the same look (no two share both colour and shape)', () => {
        const kinds: NodeSpec[] = [['big', 50], ...Array.from({ length: 30 }, (_, i): NodeSpec => [`type-${i}`, 1])];
        const s = autoStyle(graph(kinds));
        const looks = Object.keys(s.nodeColors).map((k) => `${s.nodeColors[k]}|${s.nodeShapes[k]}`);
        expect(new Set(looks).size).toBe(looks.length);
    });

    it('keeps a source-stamped colour (no auto colour) and an Entity Type icon tile (no auto shape)', () => {
        const s = autoStyle(
            graph([
                ['account', 10],
                ['stamped', 2, ICON_COLOR_SWATCHES[0]],
                ['imei', 2],
                ['plain', 2],
            ]),
        );
        expect(s.nodeColors['stamped']).toBeUndefined();
        expect(s.nodeShapes['stamped']).toBeDefined();
        expect(s.nodeColors['imei']).toBeDefined();
        expect(s.nodeShapes['imei']).toBeUndefined(); // imei has a glyph: a shape override would switch its tile off
        expect(s.nodeColors['plain']).toBeDefined();
        expect(s.nodeShapes['plain']).toBeDefined();
    });

    it('ignores the folded count suffix on a link kind and the super-node stand-in', () => {
        const s = autoStyle(
            graph(
                [
                    ['account', 4],
                    [SUPER_NODE_KIND, 9],
                ],
                [
                    ['pays · 3', 5],
                    ['pays · 4', 4],
                    ['shares', 1],
                ],
            ),
        );
        expect(Object.keys(s.nodeColors)).toEqual([]); // the super-node is not a type, so `account` is the only one
        expect(Object.keys(s.edgeColors)).toEqual(['shares']); // `pays · 3` and `pays · 4` are one kind
    });

    it('hashes a name the same way every time', () => {
        expect(hashName('account')).toBe(hashName('account'));
        expect(hashName('account')).not.toBe(hashName('device'));
    });
});
