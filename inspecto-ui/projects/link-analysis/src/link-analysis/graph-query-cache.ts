import { GraphSourceQuery } from '@inspecto/core/graph';
import { stableJson } from '@inspecto/link-analysis/api/stable-json';

/**
 * PERF-P10a-1: staleness window. Within this time a re-run does not see a new pipeline run (the client has no signal
 * for it). A change of Space, user or the Space's masking mode in force (LinkAnalysisSettingsService), an Entity List
 * write, and Refresh do drop the cache. How long a folded graph answer is served without asking the server again (no Dataset freshness token exists client-side). */
export const GRAPH_CACHE_TTL_MS = 60_000;
/** Bound on kept answers; the least recently used goes first. */
export const GRAPH_CACHE_MAX_ENTRIES = 20;
/** PERF-P10a-4: a burst of predicate edits yields one request after this pause. */
export const PUSH_FILTER_DEBOUNCE_MS = 250;

export { stableJson };

export interface CachedGraph<T> {
    readonly value: T;
    /** Epoch ms the server answered - the UI's "as of". */
    readonly fetchedAt: number;
    readonly fromCache: boolean;
}

/**
 * Client result cache + in-flight de-duplication for graph queries. The key carries the viewer/space identity, and
 * a change of identity empties the cache first, so an answer is never shared across viewers or Spaces.
 */
export class GraphQueryCache<T> {
    private readonly done = new Map<string, { value: T; fetchedAt: number }>();
    private readonly inflight = new Map<string, Promise<CachedGraph<T>>>();
    private lastIdentity: string | null = null;

    constructor(
        private readonly identity: () => string,
        private readonly now: () => number = Date.now,
        /** PERF-P10a-2: when a re-fetch after expiry folds to the same content, keep the PREVIOUS value (same reference). */
        private readonly same?: (a: T, b: T) => boolean,
    ) {}

    get(sourceId: string, q: GraphSourceQuery, fetch: () => Promise<T>, force = false): Promise<CachedGraph<T>> {
        const id = this.identity();
        if (id !== this.lastIdentity) {
            this.clear();
            this.lastIdentity = id;
        }
        const key = `${id}\u0000${sourceId}\u0000${stableJson(q)}`;
        const hit = this.done.get(key);
        if (hit && !force && this.now() - hit.fetchedAt < GRAPH_CACHE_TTL_MS) {
            this.done.delete(key); // LRU: re-insert as most recent
            this.done.set(key, hit);
            return Promise.resolve({ ...hit, fromCache: true });
        }
        const pending = this.inflight.get(key);
        if (pending && !force) return pending;
        const p = fetch().then(
            (fetched) => {
                const value = hit && this.same?.(hit.value, fetched) ? hit.value : fetched;
                const entry = { value, fetchedAt: this.now() };
                if (this.inflight.get(key) === p) {
                    this.inflight.delete(key);
                    this.done.delete(key);
                    this.done.set(key, entry);
                    while (this.done.size > GRAPH_CACHE_MAX_ENTRIES)
                        this.done.delete(this.done.keys().next().value as string);
                }
                return { ...entry, fromCache: false };
            },
            (err: unknown) => {
                if (this.inflight.get(key) === p) this.inflight.delete(key);
                throw err;
            },
        );
        this.inflight.set(key, p);
        return p;
    }

    /** Drop everything: a build, an import or a mutation in this session may have changed the server's answer. */
    clear(): void {
        this.done.clear();
        this.inflight.clear();
    }
}
