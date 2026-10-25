import { describe, expect, it } from 'vitest';
import { ConditionalReadCache } from './conditional-read-cache';

describe('ConditionalReadCache (PERF-P10a-2)', () => {
    it('keys on method + path + canonical body: key order is irrelevant, path and method are not', () => {
        const c = new ConditionalReadCache(() => 's|u');
        expect(c.keyOf('POST', '/p', { a: 1, b: { y: 1, x: 2 } })).toBe(
            c.keyOf('POST', '/p', { b: { x: 2, y: 1 }, a: 1 }),
        );
        expect(c.keyOf('POST', '/p', { a: 1 })).not.toBe(c.keyOf('POST', '/q', { a: 1 }));
        expect(c.keyOf('POST', '/p', { a: 1 })).not.toBe(c.keyOf('GET', '/p', { a: 1 }));
    });

    it('stores a tag verbatim and the same body object', () => {
        const c = new ConditionalReadCache(() => 's|u');
        const k = c.keyOf('POST', '/p', {});
        const body = { nodes: [] };
        c.store(k, 'W/"x"', body);
        expect(c.entry(k)?.etag).toBe('W/"x"');
        expect(c.entry(k)?.body).toBe(body);
    });

    it('an identity change empties the cache before the next key is built', () => {
        let id = 's|u1';
        const c = new ConditionalReadCache(() => id);
        c.store(c.keyOf('POST', '/p', {}), '"a"', { n: 1 });
        expect(c.size).toBe(1);
        id = 's|u2';
        c.keyOf('POST', '/p', {});
        expect(c.size).toBe(0);
    });

    it('a scope change empties it; the same scope does not', () => {
        const c = new ConditionalReadCache(() => 's|u');
        c.store(c.keyOf('POST', '/p', {}), '"a"', { n: 1 });
        c.setScope('');
        expect(c.size).toBe(1);
        c.setScope('mask');
        expect(c.size).toBe(0);
    });

    it('no tag stores nothing and forgets the older answer', () => {
        const c = new ConditionalReadCache(() => 's|u');
        const k = c.keyOf('POST', '/p', {});
        c.store(k, '"a"', { n: 1 });
        c.store(k, null, { n: 2 });
        expect(c.entry(k)).toBeUndefined();
    });

    it('does not retain a body over the budget (measured once from the JSON when no size is given)', () => {
        const c = new ConditionalReadCache(() => 's|u', Date.now, 100);
        const k = c.keyOf('POST', '/p', {});
        c.store(k, '"a"', { pad: 'x'.repeat(200) });
        expect(c.entry(k)).toBeUndefined();
        c.store(k, '"a"', { pad: 'x' });
        expect(c.entry(k)).toBeDefined();
    });

    it('a caller-supplied size is used instead of measuring: over budget is not kept, in budget is', () => {
        const c = new ConditionalReadCache(() => 's|u', Date.now, 100);
        const k = c.keyOf('POST', '/p', {});
        c.store(k, '"a"', { tiny: 1 }, 101);
        expect(c.entry(k)).toBeUndefined();
        c.store(k, '"a"', { tiny: 1 }, 100);
        expect(c.entry(k)).toBeDefined();
        expect(c.bytes).toBe(100);
    });

    it('is bounded by TOTAL size: the least recently used entries go first, and the total stays within budget', () => {
        const c = new ConditionalReadCache(() => 's|u', Date.now, 100);
        const [a, b, d] = ['a', 'b', 'd'].map((n) => c.keyOf('POST', '/p', { n }));
        c.store(a, '"a"', 1, 40);
        c.store(b, '"b"', 2, 40);
        c.touch(a); // b is now the least recently used
        c.store(d, '"d"', 3, 40); // 120 > 100: evicts b
        expect(c.entry(b)).toBeUndefined();
        expect(c.entry(a)).toBeDefined();
        expect(c.entry(d)).toBeDefined();
        expect(c.bytes).toBe(80);
        c.store(a, '"a2"', 4, 90); // replacing a: 90 + 40 > 100 evicts d, a stays
        expect(c.entry(d)).toBeUndefined();
        expect(c.bytes).toBe(90);
    });

    it('drop and clear keep the total honest', () => {
        const c = new ConditionalReadCache(() => 's|u', Date.now, 100);
        const k = c.keyOf('POST', '/p', {});
        c.store(k, '"a"', 1, 30);
        c.drop(k);
        expect(c.bytes).toBe(0);
        c.store(k, '"a"', 1, 30);
        c.clear();
        expect(c.bytes).toBe(0);
    });
});
