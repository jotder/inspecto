import { describe, expect, it } from 'vitest';
import { rankNeighbours, STRONGEST_NEIGHBOURS } from './neighbour-ranking';

const t = (source: string, target: string, count: number, kind: string | null = 'call') => ({
    source,
    target,
    kind,
    count,
});

describe('rankNeighbours (LA-EXPAND-PICK-NEIGHBOURS-1)', () => {
    it('folds the link rows per neighbour in either direction and ranks the strongest first', () => {
        const rows = [t('a', 'b', 2), t('c', 'a', 5), t('a', 'b', 4, 'sms'), t('a', 'd', 6)];
        expect(rankNeighbours(rows, 'a')).toEqual([
            { id: 'b', count: 6, kinds: ['call', 'sms'] },
            { id: 'd', count: 6, kinds: ['call'] },
            { id: 'c', count: 5, kinds: ['call'] },
        ]);
    });

    it('breaks a tie by id, skips a self-link and an untyped kind, and returns nothing for no rows', () => {
        expect(rankNeighbours([t('a', 'a', 9), t('a', 'z', 1, null), t('a', 'y', 1, null)], 'a')).toEqual([
            { id: 'y', count: 1, kinds: [] },
            { id: 'z', count: 1, kinds: [] },
        ]);
        expect(rankNeighbours([], 'a')).toEqual([]);
        expect(STRONGEST_NEIGHBOURS).toBe(10);
    });
});
