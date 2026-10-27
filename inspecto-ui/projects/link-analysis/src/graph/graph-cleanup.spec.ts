import { describe, expect, it } from 'vitest';
import type { G6GraphData } from '@inspecto/core/graph/graph-types';
import {
    cleanupEffect,
    foldBranches,
    foldableOf,
    keepOnly,
    keepPathsBetweenSeeds,
    removeSelected,
    shortestPathsBetweenSeeds,
    trimDanglingLeaves,
} from './graph-cleanup';

/** `'a-b b-c'` → nodes a, b, c and links a→b, b→c (ids `a-b`, `b-c`). Extra lone nodes via `alone`. */
function graph(links: string, alone: string[] = []): G6GraphData {
    const pairs = links.split(/\s+/).filter(Boolean).map((p) => p.split('-') as [string, string]);
    const ids = [...new Set([...pairs.flat(), ...alone])];
    return {
        nodes: ids.map((id) => ({ id, data: { label: id, kind: 'entity' } })),
        edges: pairs.map(([s, t]) => ({ id: `${s}-${t}`, source: s, target: t, data: { kind: 'link' } })),
    };
}

describe('graph-cleanup', () => {
    it('Collapse folds only the pendant neighbours and badges the root with the count', () => {
        // hub h: leaves x, y; b also links to c, so it is not pendant
        const g = graph('h-x y-h h-b b-c');
        expect(foldableOf(g, 'h').sort()).toEqual(['x', 'y']);
        const f = foldBranches(g, ['h']);
        expect(f.nodes.map((n) => n.id).sort()).toEqual(['b', 'c', 'h']);
        expect(f.nodes.find((n) => n.id === 'h')!.data.folded).toBe(2);
        expect(f.edges.map((e) => e.id).sort()).toEqual(['b-c', 'h-b']);
        // a root with nothing to fold is left unchanged; another root is never folded away
        expect(foldBranches(g, ['c'])).toBe(g);
        expect(foldBranches(graph('a-b'), ['a', 'b']).nodes).toHaveLength(2);
    });

    it('Keep paths between seeds keeps simple paths up to L, drops dead ends near seeds', () => {
        // s-a-t is a path; d hangs off s (a dead end within 2 hops of t through s, but on no SIMPLE s-t path)
        const g = graph('s-a a-t s-d t-q q-r r-w w-z');
        const r = keepPathsBetweenSeeds(g, ['s', 't'], 4);
        expect(r.truncated).toBe(false);
        expect(r.hide!.sort()).toEqual(['d', 'q', 'r', 'w', 'z']);
    });

    it('Keep paths between seeds never counts a walk that loops back through a seed', () => {
        // s-x-y-s is a triangle hanging on s: the walk s→x→y→s→t is 4 links, but no SIMPLE s-t path visits x or y
        const g = graph('s-x x-y y-s s-t');
        expect(keepPathsBetweenSeeds(g, ['s', 't'], 4).hide!.sort()).toEqual(['x', 'y']);
    });

    it('Keep paths between seeds honours L exactly', () => {
        const g = graph('s-a a-b b-c c-t'); // 4 links
        expect(keepPathsBetweenSeeds(g, ['s', 't'], 4).hide).toEqual([]);
        expect(keepPathsBetweenSeeds(g, ['s', 't'], 3).hide!.sort()).toEqual(['a', 'b', 'c']);
        // fewer than two seeds keeps only the seed
        expect(keepPathsBetweenSeeds(g, ['s'], 4).hide!.sort()).toEqual(['a', 'b', 'c', 't']);
    });

    it('Keep paths between seeds gives up over its budget instead of freezing', () => {
        const g = graph('s-a a-b b-t s-b');
        expect(keepPathsBetweenSeeds(g, ['s', 't'], 4, 2)).toEqual({ hide: null, truncated: true });
    });

    it('Highlight shortest paths lights every shortest route and nothing longer', () => {
        // two shortest s→t routes (via a, via b) and one longer (via c-d)
        const g = graph('s-a a-t s-b b-t s-c c-d d-t');
        const r = shortestPathsBetweenSeeds(g, ['s', 't']);
        expect(r.nodeIds.sort()).toEqual(['a', 'b', 's', 't']);
        expect(r.edgeIds.sort()).toEqual(['a-t', 'b-t', 's-a', 's-b']);
        // disconnected seeds light nothing
        expect(shortestPathsBetweenSeeds(graph('s-a', ['t']), ['s', 't'])).toEqual({ nodeIds: [], edgeIds: [] });
    });

    it('Trim dangling leaves trims chains iteratively, keeps cycles and spared nodes', () => {
        // cycle a-b-c-a, chain c-d-e hanging off, leaf p pinned, isolated z
        const g = graph('a-b b-c c-a c-d d-e a-p', ['z']);
        expect(trimDanglingLeaves(g, ['p']).sort()).toEqual(['d', 'e', 'z']);
        // sparing the chain's end keeps the whole chain
        expect(trimDanglingLeaves(g, ['p', 'e'])).toEqual(['z']);
    });

    it('Keep only / Remove selected are exact complements on the canvas', () => {
        const g = graph('a-b b-c');
        expect(keepOnly(g, ['a', 'ghost'])).toEqual(['b', 'c']);
        expect(removeSelected(g, ['a', 'ghost'])).toEqual(['a']);
    });

    it('states the effect first', () => {
        expect(cleanupEffect(612, 921)).toBe('hides 612 of 921 entities');
    });
});
