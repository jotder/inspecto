import { describe, expect, it } from 'vitest';
import { quantileCont, varSamp } from './stat-aggregates';

describe('stat aggregates (offline twin of DuckDB)', () => {
    it('quantileCont interpolates between neighbours at (n - 1)·p, like quantile_cont', () => {
        const xs = [10, 1, 4, 2, 3];
        expect(quantileCont(xs, 0)).toBe(1);
        expect(quantileCont(xs, 0.5)).toBe(3);
        expect(quantileCont(xs, 1)).toBe(10);
        expect(quantileCont(xs, 0.95)).toBeCloseTo(8.8, 12);
        expect(quantileCont([4, 1, 3, 2], 0.5)).toBe(2.5);
        expect(quantileCont([], 0.5)).toBeNull();
    });

    it('varSamp is the sample variance and NULL below two values', () => {
        expect(varSamp([1, 2, 3, 4, 10])).toBe(12.5);
        expect(varSamp([7])).toBeNull();
        expect(varSamp([])).toBeNull();
    });
});
