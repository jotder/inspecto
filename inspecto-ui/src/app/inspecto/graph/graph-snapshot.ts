import { ConditionGroup } from '../query/query-types';
import { G6GraphData } from './graph-types';

/**
 * **Evidence snapshot** (spec §3.6 / plan S1.3, UI first). A saved view re-runs the *question* and may show
 * a different graph tomorrow; a snapshot freezes the *answer* as it is now — the nodes, the links with their
 * folded counts, the metrics the analyst computed, the predicate that produced it and the viewport — with a
 * manifest hash over the frozen content so a later reader can tell whether it is the same evidence.
 *
 * Framework-free. The hash is SHA-256 (Web Crypto) over the UTF-8 bytes of a canonical JSON form, written as
 * `sha256:<64 lowercase hex>` — the same format and canonicalisation as the server's
 * `InvestigationEvaluator.sha256(canonical(…))`, so custody is SHA-256 end to end (LA-12). The server stores
 * `manifestHash` verbatim and never recomputes it; the Dossier's manifest hashes the stored bytes.
 * ⚠ Async, and `crypto.subtle` exists only in a secure context (https or localhost): hashing refuses there
 * rather than falling back to a weaker digest.
 */
export interface GraphSnapshot {
    id: string;
    title: string;
    description?: string;
    createdAt: string;
    /** Frozen content. */
    nodes: G6GraphData['nodes'];
    edges: G6GraphData['edges'];
    /** Metric vectors the analyst had computed, keyed by metric then node id. */
    metrics: Record<string, Record<string, number>>;
    predicate?: ConditionGroup | null;
    /** What produced the graph — source plane and Dataset, for provenance. */
    origin: { sourceId: string; dataset?: string; query: unknown };
    viewport?: { layout: string };
    annotations: { targetId: string; text: string }[];
    /** `sha256:<hex>` of {nodes, edges, metrics, predicate, origin} in canonical form. */
    manifestHash: string;
    /** Which Case ids this snapshot is attached to (UI-first: kept in the browser session). */
    attachedTo: string[];
}

/** Canonical JSON: object keys sorted recursively, so a hash is stable across insertion order. */
export function canonicalJson(value: unknown): string {
    return JSON.stringify(sortKeys(value));
}

function sortKeys(v: unknown): unknown {
    if (Array.isArray(v)) return v.map(sortKeys);
    if (v && typeof v === 'object') {
        return Object.fromEntries(
            Object.keys(v as Record<string, unknown>)
                .sort()
                .map((k) => [k, sortKeys((v as Record<string, unknown>)[k])]),
        );
    }
    return v;
}

const SHA256_PREFIX = 'sha256:';

/**
 * SHA-256 of the UTF-8 bytes of `text`, as `sha256:<64 lowercase hex>` — the server's format
 * (`InvestigationEvaluator.sha256`). Rejects outside a secure context, where `crypto.subtle` is absent.
 */
export async function sha256(text: string): Promise<string> {
    const subtle = globalThis.crypto?.subtle;
    if (!subtle) throw new Error('SHA-256 needs a secure context (https or localhost) — nothing was sealed.');
    const digest = await subtle.digest('SHA-256', new TextEncoder().encode(text));
    return SHA256_PREFIX + Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

export interface SnapshotInput {
    title: string;
    description?: string;
    graph: G6GraphData;
    metrics?: Record<string, Record<string, number>>;
    predicate?: ConditionGroup | null;
    origin: GraphSnapshot['origin'];
    viewport?: GraphSnapshot['viewport'];
    annotations?: GraphSnapshot['annotations'];
    /** Injected for determinism in tests. */
    now?: Date;
}

/** Freeze a graph as evidence. Stranded (`missing`) nodes are excluded — they are not part of the answer. */
export async function snapshotGraph(input: SnapshotInput): Promise<GraphSnapshot> {
    const nodes = input.graph.nodes.filter((n) => !n.data.missing);
    const keep = new Set(nodes.map((n) => n.id));
    const edges = input.graph.edges.filter((e) => keep.has(e.source) && keep.has(e.target));
    const metrics = input.metrics ?? {};
    const predicate = input.predicate ?? null;
    const manifestHash = await sha256(canonicalJson({ nodes, edges, metrics, predicate, origin: input.origin }));
    const now = input.now ?? new Date();
    return {
        id: `snp-${now.getTime().toString(36)}-${manifestHash.slice(SHA256_PREFIX.length, SHA256_PREFIX.length + 6)}`,
        title: input.title,
        description: input.description,
        createdAt: now.toISOString(),
        nodes,
        edges,
        metrics,
        predicate,
        origin: input.origin,
        viewport: input.viewport,
        annotations: input.annotations ?? [],
        manifestHash,
        attachedTo: [],
    };
}

/** Re-derive the hash from a snapshot's frozen content; `true` when it still matches its manifest. */
export async function verifySnapshot(s: GraphSnapshot): Promise<boolean> {
    const { nodes, edges, metrics, predicate, origin } = s;
    return (await sha256(canonicalJson({ nodes, edges, metrics, predicate, origin }))) === s.manifestHash;
}
