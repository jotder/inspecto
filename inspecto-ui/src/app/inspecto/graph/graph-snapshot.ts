import { ConditionGroup } from '../query/query-types';
import { G6GraphData } from './graph-types';

/**
 * **Evidence snapshot** (spec §3.6 / plan S1.3, UI first). A saved view re-runs the *question* and may show
 * a different graph tomorrow; a snapshot freezes the *answer* as it is now — the nodes, the links with their
 * folded counts, the metrics the analyst computed, the predicate that produced it and the viewport — with a
 * manifest hash over the frozen content so a later reader can tell whether it is the same evidence.
 *
 * Framework-free. ⚠ The hash is FNV-1a 64 over a canonical JSON form: a stable *fingerprint* for the UI-first
 * cut, **not** a cryptographic digest — the plan's chain-of-custody gate (S3.2, SHA-256) replaces it when the
 * backend `POST /inv/snapshots` lands. Say so in the UI; never present it as SHA-256.
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
    /** Fingerprint of {nodes, edges, metrics, predicate, origin} in canonical form. */
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

/** FNV-1a, 64-bit, as 16 hex characters. A fingerprint, not a cryptographic digest. */
export function fnv1a64(text: string): string {
    let h = 0xcbf29ce484222325n;
    const prime = 0x100000001b3n;
    const mask = 0xffffffffffffffffn;
    for (let i = 0; i < text.length; i++) {
        h ^= BigInt(text.charCodeAt(i));
        h = (h * prime) & mask;
    }
    return h.toString(16).padStart(16, '0');
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
export function snapshotGraph(input: SnapshotInput): GraphSnapshot {
    const nodes = input.graph.nodes.filter((n) => !n.data.missing);
    const keep = new Set(nodes.map((n) => n.id));
    const edges = input.graph.edges.filter((e) => keep.has(e.source) && keep.has(e.target));
    const metrics = input.metrics ?? {};
    const predicate = input.predicate ?? null;
    const manifestHash = fnv1a64(canonicalJson({ nodes, edges, metrics, predicate, origin: input.origin }));
    const now = input.now ?? new Date();
    return {
        id: `snp-${now.getTime().toString(36)}-${manifestHash.slice(0, 6)}`,
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

/** Re-derive the fingerprint from a snapshot's frozen content; `true` when it still matches its manifest. */
export function verifySnapshot(s: GraphSnapshot): boolean {
    const { nodes, edges, metrics, predicate, origin } = s;
    return fnv1a64(canonicalJson({ nodes, edges, metrics, predicate, origin })) === s.manifestHash;
}
