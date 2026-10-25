import { describe, expect, it } from 'vitest';
import { deepEqual, stableJson } from './stable-json';

describe('stableJson', () => {
    it('sorts object keys at every depth', () => {
        expect(stableJson({ b: 1, a: { d: 1, c: 2 } })).toBe(stableJson({ a: { c: 2, d: 1 }, b: 1 }));
    });
});

describe('deepEqual (PERF-P10a-2)', () => {
    it('compares plain data by structure, in both directions, including keys only one side has', () => {
        expect(deepEqual({ a: [1, { b: 2 }] }, { a: [1, { b: 2 }] })).toBe(true);
        expect(deepEqual({ a: 1 }, { a: 1, b: 2 })).toBe(false);
        expect(deepEqual({ a: 1, b: 2 }, { a: 1 })).toBe(false);
        expect(deepEqual([1, 2], [2, 1])).toBe(false);
        expect(deepEqual({ a: undefined }, {})).toBe(true);
        expect(deepEqual(null, {})).toBe(false);
        expect(deepEqual([], {})).toBe(false);
    });

    it('compares Dates by time', () => {
        expect(deepEqual(new Date(5), new Date(5))).toBe(true);
        expect(deepEqual(new Date(5), new Date(6))).toBe(false);
        expect(deepEqual({ at: new Date(5) }, { at: new Date(6) })).toBe(false);
    });

    it('compares Maps by entries', () => {
        expect(deepEqual(new Map([['a', { n: 1 }]]), new Map([['a', { n: 1 }]]))).toBe(true);
        expect(deepEqual(new Map([['a', { n: 1 }]]), new Map([['a', { n: 2 }]]))).toBe(false);
        expect(deepEqual(new Map([['a', 1]]), new Map([['b', 1]]))).toBe(false);
        expect(deepEqual(new Map([['a', 1]]), new Map())).toBe(false);
    });

    it('compares Sets by members, order-insensitively', () => {
        expect(deepEqual(new Set([1, 2]), new Set([2, 1]))).toBe(true);
        expect(deepEqual(new Set([{ n: 1 }]), new Set([{ n: 1 }]))).toBe(true);
        expect(deepEqual(new Set([1, 2]), new Set([1, 3]))).toBe(false);
        expect(deepEqual(new Set([1]), new Set([1, 2]))).toBe(false);
    });

    it('treats a cycle as NOT equal instead of overflowing the stack', () => {
        const a: Record<string, unknown> = { n: 1 };
        a['self'] = a;
        const b: Record<string, unknown> = { n: 1 };
        b['self'] = b;
        expect(() => deepEqual(a, b)).not.toThrow();
        expect(deepEqual(a, b)).toBe(false);
        const x: unknown[] = [];
        x.push(x);
        const y: unknown[] = [];
        y.push(y);
        expect(deepEqual(x, y)).toBe(false);
    });

    it('a repeated NON-cyclic subtree (a diamond) is not mistaken for a cycle', () => {
        const shared = { n: 1 };
        const shared2 = { n: 1 };
        expect(deepEqual({ l: shared, r: shared }, { l: shared2, r: shared2 })).toBe(true);
    });
});
