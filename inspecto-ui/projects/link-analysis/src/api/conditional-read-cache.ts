import { stableJson } from './stable-json';

/** Bound on remembered answers; the least recently used goes first. */
export const CONDITIONAL_CACHE_MAX_ENTRIES = 40;
/** A body whose JSON is longer than this is not retained (no conditional request is ever sent for it). */
export const CONDITIONAL_CACHE_MAX_BODY_CHARS = 4_000_000;

interface Entry {
    readonly etag: string;
    readonly body: unknown;
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
    private lastIdentity: string | null = null;
    private scope = '';

    constructor(
        private readonly identity: () => string,
        private readonly now: () => number = Date.now,
        private readonly maxEntries = CONDITIONAL_CACHE_MAX_ENTRIES,
        private readonly maxBodyChars = CONDITIONAL_CACHE_MAX_BODY_CHARS,
    ) {}

    /** The key for a request; first drops everything when the identity (or the scope set by the host) moved. */
    keyOf(method: string, path: string, body: unknown): string {
        const id = `${this.identity()}\u0000${this.scope}`;
        if (id !== this.lastIdentity) {
            this.entries.clear();
            this.lastIdentity = id;
        }
        return `${id}\u0000${method}\u0000${path}\u0000${stableJson(body)}`;
    }

    /** The host's extra identity (the masking mode in force); a change empties the cache. */
    setScope(scope: string): void {
        if (scope !== this.scope) {
            this.scope = scope;
            this.entries.clear();
        }
    }

    entry(key: string): Entry | undefined {
        return this.entries.get(key);
    }

    /** Remember an answer. No tag, or a body over the size guard, stores nothing and forgets any older answer. */
    store(key: string, etag: string | null, body: unknown): void {
        this.entries.delete(key);
        if (!etag || body === null || body === undefined) return;
        if (JSON.stringify(body).length > this.maxBodyChars) return;
        this.entries.set(key, { etag, body, storedAt: this.now() });
        while (this.entries.size > this.maxEntries) this.entries.delete(this.entries.keys().next().value as string);
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
        this.entries.delete(key);
    }

    clear(): void {
        this.entries.clear();
    }

    get size(): number {
        return this.entries.size;
    }
}
