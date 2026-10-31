import { describe, expect, it } from 'vitest';
import type { WorkingSet, WorkingSetEntity } from '@inspecto/link-analysis/api/inv.service';
import {
    cleanupSets,
    keepOnlySelected,
    leafNeighbours,
    offPathsBetween,
    recentExcludeReasons,
    weaklyLinked,
} from './investigation-cleanup';

/** s(tart) — a — b — c, with d hanging off a and e off b; c is kept; x is hidden. */
function ws(over: Partial<Record<string, Partial<WorkingSetEntity>>> = {}): WorkingSet {
    const ent = (id: string, hop: number, type = 'msisdn'): WorkingSetEntity => ({
        id,
        type,
        hop,
        seed: 's',
        admittedBy: 1,
        hidden: false,
        kept: false,
        ...over[id],
    });
    const link = (source: string, target: string, count: number) => ({ source, target, kind: 'call', count, admittedBy: 1 });
    return {
        entities: [ent('s', 0), ent('a', 1), ent('b', 2), ent('c', 3, 'imei'), ent('d', 2, 'imei'), ent('e', 3), ent('x', 1)],
        links: [link('s', 'a', 10), link('a', 'b', 5), link('b', 'c', 1), link('a', 'd', 1), link('b', 'e', 2), link('s', 'x', 9)],
        excluded: [
            { id: 'p', step: 3, reason: 'Call centre' },
            { id: 'q', step: 5, reason: 'Not relevant' },
            { id: 'r', step: 4, reason: 'Call centre' },
        ],
        hash: '',
    };
}

describe('investigation-cleanup (LA-INVESTIGATE-UX-1)', () => {
    it('leaves: one pass of the entities with one link, sparing start and kept entities, ignoring hidden ones', () => {
        // c (kept) and s (start) are spared; d and e dangle
        const sets = cleanupSets(ws({ c: { kept: true }, x: { hidden: true } }));
        expect(sets.leaves.sort()).toEqual(['d', 'e']);
        // without the keep c dangles too; b and a do not (yet): a tree is never taken away whole in one press
        expect(cleanupSets(ws({ x: { hidden: true } })).leaves.sort()).toEqual(['c', 'd', 'e']);
    });

    it('types: per entity type, largest first, spared entities never offered', () => {
        const sets = cleanupSets(ws({ c: { kept: true } }));
        expect(sets.types).toEqual([
            { type: 'msisdn', ids: ['a', 'b', 'e', 'x'] },
            { type: 'imei', ids: ['d'] },
        ]);
    });

    it('weakly linked: events summed over an entity\'s visible links, under the minimum', () => {
        // a: 10+5+1 = 16 · b: 5+1+2 = 8 · c: 1 · d: 1 · e: 2 · x: hidden (its 9 do not count for s either)
        expect(weaklyLinked(ws({ x: { hidden: true } }), 3).sort()).toEqual(['c', 'd', 'e']);
        expect(weaklyLinked(ws({ x: { hidden: true } }), 9).sort()).toEqual(['b', 'c', 'd', 'e']);
    });

    it('keep only the selection: everything else that is visible, start and kept entities spared', () => {
        expect(keepOnlySelected(ws({ x: { hidden: true }, e: { kept: true } }), ['a', 'b']).sort()).toEqual(['c', 'd']);
    });

    it('paths between two picked entities: everything off a short enough path is taken away', () => {
        // s — a — b — c: the path from a to c holds a, b, c; d and e hang off it; s is a start entity
        expect(offPathsBetween(ws({ x: { hidden: true } }), 'a', 'c', 4)?.sort()).toEqual(['d', 'e']);
        // a path longer than allowed keeps only the two ends
        expect(offPathsBetween(ws({ x: { hidden: true } }), 'a', 'c', 1)?.sort()).toEqual(['b', 'd', 'e']);
    });

    it('shrink: the neighbours that link only to the entity', () => {
        expect(leafNeighbours(ws(), ['b']).sort()).toEqual(['c', 'e']);
        expect(leafNeighbours(ws({ c: { kept: true } }), ['b'])).toEqual(['e']);
        expect(leafNeighbours(ws(), ['s'])).toEqual(['x']);
    });

    it('recent reasons: distinct, newest first, at most three', () => {
        expect(recentExcludeReasons(ws())).toEqual(['Not relevant', 'Call centre']);
        expect(recentExcludeReasons(null)).toEqual([]);
    });
});
