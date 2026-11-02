import { describe, expect, it } from 'vitest';
import { withOrphans } from './remove-nodes';
import { unsavedStepLine } from './unsaved-investigation';

const l = (source: string, target: string) => ({ source, target });

describe('withOrphans - Remove takes the neighbours it leaves unlinked', () => {
    it('removes a leaf on either side of the link, keeps a neighbour still linked elsewhere', () => {
        // hub -> out (leaf), in -> hub (leaf), hub -> kept, kept -> other
        const links = [l('hub', 'out'), l('in', 'hub'), l('hub', 'kept'), l('kept', 'other')];
        expect(withOrphans(['hub'], links).sort()).toEqual(['hub', 'in', 'out']);
    });

    it('does not cascade: a node orphaned only by an orphan leaving stays', () => {
        // a -> b -> c: removing a orphans nothing (b still links c)
        expect(withOrphans(['a'], [l('a', 'b'), l('b', 'c')])).toEqual(['a']);
    });

    it('a neighbour linked only to removed nodes goes too', () => {
        expect(withOrphans(['a', 'b'], [l('a', 'x'), l('x', 'b'), l('a', 'b')]).sort()).toEqual(['a', 'b', 'x']);
    });

    it('nothing picked removes nothing', () => {
        expect(withOrphans([], [l('a', 'b')])).toEqual([]);
    });

    it('the unsaved step list names a Remove', () => {
        expect(unsavedStepLine({ kind: 'remove', ids: ['a', 'b'] })).toBe('Remove 2 entities');
    });
});
