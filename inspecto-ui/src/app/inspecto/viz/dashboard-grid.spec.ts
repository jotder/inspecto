import { describe, expect, it } from 'vitest';
import { nextSpan, spanLabel, tileBasis, tileSpan } from './dashboard-grid';

describe('dashboard grid (UIE-3)', () => {
    it('four columns: a quarter, a half, three quarters and the full row', () => {
        expect(tileBasis(4)).toBe('100%');
        expect(tileBasis(1)).toBe('calc((100% - 3rem) * 1 / 4 + 0rem)');
        expect(tileBasis(2)).toBe('calc((100% - 3rem) * 2 / 4 + 1rem)');
    });

    it('reads an invalid stored span as half', () => {
        expect(tileSpan(undefined)).toBe(2);
        expect(tileSpan(0)).toBe(2);
        expect(tileSpan('wide')).toBe(2);
        expect(tileSpan(3)).toBe(3);
    });

    it('the width button cycles quarter → half → three quarters → full → quarter', () => {
        expect([1, 2, 3, 4].map(nextSpan)).toEqual([2, 3, 4, 1]);
        expect(spanLabel(1)).toBe('quarter width');
    });
});
