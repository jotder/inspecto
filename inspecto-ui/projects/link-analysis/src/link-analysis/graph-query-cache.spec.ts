import { describe, expect, it, vi } from 'vitest';
import { GraphSourceQuery } from '@inspecto/core/graph';
import { GRAPH_CACHE_MAX_ENTRIES, GRAPH_CACHE_TTL_MS, GraphQueryCache, stableJson } from './graph-query-cache';

const q = (over: Partial<GraphSourceQuery> = {}) => ({ from: 'a', depth: 2, ...over }) as GraphSourceQuery;

describe('GraphQueryCache (PERF-P10a-1 / -3)', () => {
    it('serves an identical re-run with no fetch; a different query fetches once', async () => {
        const cache = new GraphQueryCache<string>(() => 's|u');
        const fetch = vi.fn(() => Promise.resolve('g'));
        const first = await cache.get('src', q(), fetch);
        const second = await cache.get('src', { depth: 2, from: 'a' } as GraphSourceQuery, fetch); // key order is irrelevant
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(first.fromCache).toBe(false);
        expect(second.fromCache).toBe(true);
        expect(second.value).toBe(first.value);
        expect(second.fetchedAt).toBe(first.fetchedAt);
        await cache.get('src', q({ depth: 3 }), fetch);
        expect(fetch).toHaveBeenCalledTimes(2);
    });

    it('PERF-P10a-2: an expired re-fetch that is "same" keeps the previous object; a different one replaces it', async () => {
        let t = 0;
        const cache = new GraphQueryCache<{ v: number }>(
            () => 's|u',
            () => t,
            (a, b) => a.v === b.v,
        );
        const first = await cache.get('src', q(), () => Promise.resolve({ v: 1 }));
        t = GRAPH_CACHE_TTL_MS + 1;
        const second = await cache.get('src', q(), () => Promise.resolve({ v: 1 }));
        expect(second.fromCache).toBe(false);
        expect(second.value).toBe(first.value);
        t = 2 * GRAPH_CACHE_TTL_MS + 2;
        const third = await cache.get('src', q(), () => Promise.resolve({ v: 2 }));
        expect(third.value).not.toBe(first.value);
        expect(third.value.v).toBe(2);
    });

    it('PERF-P10a-2: a forced (Refresh) re-fetch NEVER keeps the old object, even when "same" says equal', async () => {
        const cache = new GraphQueryCache<{ v: number }>(
            () => 's|u',
            () => 0,
            () => true,
        );
        const first = await cache.get('src', q(), () => Promise.resolve({ v: 1 }));
        const forced = await cache.get('src', q(), () => Promise.resolve({ v: 1 }), true);
        expect(forced.value).not.toBe(first.value);
    });

    it('two overlapping identical calls share ONE fetch', async () => {
        const cache = new GraphQueryCache<string>(() => 's|u');
        let release!: (v: string) => void;
        const fetch = vi.fn(() => new Promise<string>((r) => (release = r)));
        const p1 = cache.get('src', q(), fetch);
        const p2 = cache.get('src', q(), fetch);
        release('g');
        const [a, b] = await Promise.all([p1, p2]);
        expect(fetch).toHaveBeenCalledTimes(1);
        expect(a.value).toBe(b.value);
    });

    it('NEVER shares an answer across viewers or Spaces (a change of identity empties the cache)', async () => {
        let identity = 'space1|alice';
        const cache = new GraphQueryCache<string>(() => identity);
        const fetch = vi.fn(() => Promise.resolve(`for ${identity}`));
        expect((await cache.get('src', q(), fetch)).value).toBe('for space1|alice');
        identity = 'space1|bob';
        const bob = await cache.get('src', q(), fetch);
        expect(bob.fromCache).toBe(false);
        expect(bob.value).toBe('for space1|bob');
        identity = 'space2|bob';
        expect((await cache.get('src', q(), fetch)).fromCache).toBe(false);
        identity = 'space1|alice';
        expect((await cache.get('src', q(), fetch)).fromCache).toBe(false); // alice's old answer is gone, not parked
        expect(fetch).toHaveBeenCalledTimes(4);
    });

    it('expires after the TTL, bypasses on force, and clear() drops everything', async () => {
        let t = 1_000;
        const cache = new GraphQueryCache<string>(
            () => 'x',
            () => t,
        );
        const fetch = vi.fn(() => Promise.resolve('g'));
        await cache.get('s', q(), fetch);
        t += GRAPH_CACHE_TTL_MS - 1;
        expect((await cache.get('s', q(), fetch)).fromCache).toBe(true);
        t += 1;
        expect((await cache.get('s', q(), fetch)).fromCache).toBe(false);
        expect((await cache.get('s', q(), fetch, true)).fromCache).toBe(false);
        expect(fetch).toHaveBeenCalledTimes(3);
        cache.clear();
        await cache.get('s', q(), fetch);
        expect(fetch).toHaveBeenCalledTimes(4);
    });

    it('is bounded (least recently used goes first) and does not cache a failure', async () => {
        const cache = new GraphQueryCache<string>(() => 'x');
        const fetch = vi.fn(() => Promise.resolve('g'));
        for (let i = 0; i < GRAPH_CACHE_MAX_ENTRIES + 1; i++) await cache.get('s', q({ depth: i }), fetch);
        await cache.get('s', q({ depth: 0 }), fetch); // evicted: fetches again
        expect(fetch).toHaveBeenCalledTimes(GRAPH_CACHE_MAX_ENTRIES + 2);
        const bad = vi.fn(() => Promise.reject(new Error('503')));
        await expect(cache.get('s', q({ from: 'z' }), bad)).rejects.toThrow('503');
        await expect(cache.get('s', q({ from: 'z' }), bad)).rejects.toThrow('503');
        expect(bad).toHaveBeenCalledTimes(2);
    });

    it('stableJson sorts keys at every depth', () => {
        expect(stableJson({ b: 1, a: { d: 1, c: 2 } })).toBe(stableJson({ a: { c: 2, d: 1 }, b: 1 }));
    });
});
