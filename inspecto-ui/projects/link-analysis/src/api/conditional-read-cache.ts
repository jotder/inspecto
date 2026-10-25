import { stableJson } from './stable-json';

/**
 * TOTAL budget for remembered bodies, in estimated size (response Content-Length when sent, else the JSON length,
 * measured once at store time). 32 M: a handful of large Working Set / projection answers, which sit parsed in the
 * heap next to the 20 cached graphs; the least recently used goes first. A body that alone exceeds it is never kept.
 */
export const CONDITIONAL_CACHE_BUDGET = 32_000_000;

interface Entry {
    readonly etag: string;
    readonly body: unknown;
    readonly size: number;
    readonly storedAt: number;
}

/**
 * PERF-P10a-2: the last body and `ETag` of each projection read, so the next identical read can ask
 * `If-None-Match` and reuse the body on a `304`. Key = method + path + canonical body, under the current
 * identity (Space + viewer + scope): an identity change empties the cache first, so an answer is never
 * offered to another viewer or Space. The tag is kept verbatim (a gzip-weakened `W/"x"` is echoed as is).
 */
export class ConditionalReadCache {
    private readonly entries = new Map<string, Entry>();
    private total = 0;
    private lastIdentity: string | null = null;
    private scope = '';

    constructor(
        private readonly identity: () => string,
        private readonly now: () => number = Date.now,
        private readonly budget = CONDITIONAL_CACHE_BUDGET,
    ) {}

    /** The key for a request; first drops everything when the identity (or the scope set by the host) moved. */
    keyOf(method: string, path: string, body: unknown): string {
        const id = `${this.identity()}\u0000${this.scope}`;
        if (id !== this.lastIdentity) {
            this.clear();
            this.lastIdentity = id;
        }
        return `${id}\u0000${method}\u0000${path}\u0000${stableJson(body)}`;
    }

    /** The host's extra identity (the masking mode in force); a change empties the cache. */
    setScope(scope: string): void {
        if (scope !== this.scope) {
            this.scope = scope;
            this.clear();
        }
    }

    entry(key: string): Entry | undefined {
        return this.entries.get(key);
    }

    /**
     * Remember an answer. No tag, or a body over the budget, stores nothing and forgets any older answer.
     * `size` is the caller's cheap estimate (Content-Length); without it the body is measured ONCE here.
     */
    store(key: string, etag: string | null, body: unknown, size?: number): void {
        this.drop(key);
        if (!etag || body === null || body === undefined) return;
        const bytes = size !== undefined && size >= 0 ? size : JSON.stringify(body).length;
        if (bytes > this.budget) return;
        this.entries.set(key, { etag, body, size: bytes, storedAt: this.now() });
        this.total += bytes;
        for (const [k, e] of this.entries) {
            if (this.total <= this.budget) break;
            this.entries.delete(k);
            this.total -= e.size;
        }
    }

    /** A 304 refreshes recency (LRU) without touching the body. */
    touch(key: string): void {
        const e = this.entries.get(key);
        if (e) {
            this.entries.delete(key);
            this.entries.set(key, e);
        }
    }

    drop(key: string): void {
        const e = this.entries.get(key);
        if (e) {
            this.entries.delete(key);
            this.total -= e.size;
        }
    }

    clear(): void {
        this.entries.clear();
        this.total = 0;
    }

    get size(): number {
        return this.entries.size;
    }

    /** Estimated size held, for tests and diagnostics. */
    get bytes(): number {
        return this.total;
    }
}
