import { hashContent } from '../../transfer/content-hash';
import { MockFlags } from '../mock-flags';
import { error, json, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { componentCollection } from './components.handler';
import { CONNECTIONS_COLL } from './connections.handler';
import { JOBS_COLL } from './jobs.handler';
import { PIPELINES_COLL } from './pipelines.handler';

/**
 * The Metadata-Bundle transport pipe (`POST /bundle/export` + `POST /bundle/import`) — the offline
 * half of U-F (2026-08-01), added when the UI stopped fanning out one per-kind write per row, and
 * stopped authoring bundle content itself, and became a caller of the routes the backend already
 * owned. Without this handler neither direction works offline.
 *
 * ⚠ **A mock must never be more lenient than the server.** This one exists to keep the *gates*
 * honest, because they are the whole reason the UI became a caller — a mock that happily wrote every
 * item would turn each of these hard failures into a passing offline rehearsal:
 *
 * 1. **Referential integrity**, fail-closed with a 422 BEFORE any write — an import may not
 *    introduce a reference that resolves to nothing in `(store ∪ incoming)`.
 * 2. **Connection secrets** — a secret-looking field that is present, non-blank and not a `${…}`
 *    reference fails that item. `BundleRoutes` calls this defence-in-depth; a bundle must never be
 *    able to smuggle a raw credential onto the target.
 * 3. **Unsupported kind** → `skipped` with a reason, never a silent write.
 * 4. **Idempotent re-promotion** — identical content hash ⇒ `unchanged`, and nothing is written.
 * 5. **Existing defaults to skip** unless the caller passes `actions {"<kind>/<id>": "overwrite"}`.
 *
 * Apply order mirrors the backend's `APPLY_ORDER` so a referenced kind is written before its
 * referencer. What is deliberately NOT mirrored: the backend's real `ComponentIntegrity` ref graph —
 * this checks the bundle's own declared `refs`, which is the subset the UI can know about offline.
 */

/** Mirrors `BundleRoutes.APPLY_ORDER`; a kind absent here applies last (same as the backend's default). */
const APPLY_ORDER = [
    'connection', 'grammar', 'transform', 'sink', 'dataset', 'query', 'widget',
    'dashboard', 'reconciliation', 'link-analysis-view', 'geo-map-view', 'decision-rule',
    'authored-pipeline', 'job', 'saved-view',
];

/** Kinds with their own store, outside the component registry (mirrors `OWN_STORE_KINDS`). */
const OWN_STORE: Record<string, string> = {
    connection: CONNECTIONS_COLL,
    'authored-pipeline': PIPELINES_COLL,
    job: JOBS_COLL,
    'decision-rule': 'decision-rule',
};

/** Retired as a component kind — accepted in an OLD bundle, reported per-item, never written. */
const RETIRED = new Set(['schema']);

const SECRET_KEY = /pass(word)?|secret|token|api_?key|credential/i;

interface IncomingItem {
    kind: string;
    id: string;
    content?: Record<string, unknown>;
    refs?: { kind: string; id: string; resolution?: string }[];
    provenance?: { contentHash?: string };
}

const collectionFor = (kind: string): string => OWN_STORE[kind] ?? componentCollection(kind);

const orderOf = (kind: string): number => {
    const i = APPLY_ORDER.indexOf(kind);
    return i < 0 ? APPLY_ORDER.length : i;
};

/** A secret-looking field carrying a literal rather than a `${…}` reference — the item must fail. */
function literalSecret(content: Record<string, unknown>): string | null {
    for (const [k, v] of Object.entries(content)) {
        if (SECRET_KEY.test(k) && typeof v === 'string' && v.trim() && !v.startsWith('${')) return k;
        if (v && typeof v === 'object' && !Array.isArray(v)) {
            const nested = literalSecret(v as Record<string, unknown>);
            if (nested) return `${k}.${nested}`;
        }
    }
    return null;
}

/**
 * References the bundle would INTRODUCE that resolve to nothing on the target. Computed over
 * (store ∪ incoming) so items that satisfy each other pass, and only `included`-resolution refs are
 * checked — an `external` ref is the bundle's declared contract, surfaced by the `requires` panel
 * before the user ever clicks apply, not a reason to reject the import.
 */
function introducedFindings(store: MockStore, space: string, items: IncomingItem[]): string[] {
    const incoming = new Set(items.map((i) => `${i.kind}/${i.id}`));
    const out: string[] = [];
    for (const item of items) {
        for (const ref of item.refs ?? []) {
            if (ref.resolution !== 'included') continue;
            const key = `${ref.kind}/${ref.id}`;
            if (incoming.has(key)) continue;
            if (store.has(space, collectionFor(ref.kind), ref.id)) continue;
            out.push(`${item.kind}/${item.id} → ${key}`);
        }
    }
    return out;
}

/**
 * The bundle **transport** view of a connection, mirroring `ConnectionProfile.toBundleMap()`: a
 * secret-bearing field travels ONLY as a `${…}` reference — a literal is omitted entirely, never
 * masked, because a bundle lands in git and support tickets and a `***` sentinel would round-trip
 * into the target as a literal-looking value. Mirrored here or the offline export would hand back a
 * bundle the (equally mirrored) import gate then refuses.
 */
function toBundleView(content: Record<string, unknown>): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(content)) {
        if (SECRET_KEY.test(k)) {
            if (typeof v === 'string' && v.startsWith('${')) out[k] = v;
            continue;
        }
        out[k] = v && typeof v === 'object' && !Array.isArray(v)
            ? toBundleView(v as Record<string, unknown>)
            : v;
    }
    return out;
}

/** What this instance would store for an item — the export content and the `originHash` baseline. */
function storedContent(store: MockStore, space: string, kind: string, id: string): Record<string, unknown> | null {
    if (RETIRED.has(kind)) return null;
    const raw = store.get<Record<string, unknown>>(space, collectionFor(kind), id);
    if (!raw) return null;
    return kind === 'connection' ? toBundleView(raw) : raw;
}

/**
 * `POST /bundle/export` — read each requested item off this instance and return the v2 envelope with
 * real content + an authoritative `provenance.contentHash`. The caller's `refs` are echoed and its
 * `requires` stamped with `originHash` where the referent resolves here (that is what lets the
 * target's preview distinguish *satisfied* from *present at a different version*). Requested items
 * this instance does not hold are omitted and reported under `missing` — a partial bundle is valid.
 */
function exportBundle(req: MockRequest, store: MockStore) {
    const body = (req.body ?? {}) as {
        items?: { kind: string; id: string; refs?: unknown }[];
        sourceSpace?: string | null;
        requires?: { kind: string; id: string }[];
    };
    const requested = body.items ?? [];
    if (!requested.length) return error(422, "export body must include a non-empty 'items' array");

    const exportedAt = new Date().toISOString();
    const sourceSpace = body.sourceSpace ?? null;
    const items: unknown[] = [];
    const missing: { kind: string; id: string }[] = [];
    for (const r of requested) {
        const content = storedContent(store, req.space, r.kind, r.id);
        if (!content) {
            missing.push({ kind: r.kind, id: r.id });
            continue;
        }
        items.push({
            kind: r.kind,
            id: r.id,
            content,
            ...(Array.isArray(r.refs) ? { refs: r.refs } : {}),
            provenance: { sourceSpace, exportedAt, contentHash: `sha256:${hashContent(content)}` },
        });
    }
    const requires = (body.requires ?? []).map((ref) => {
        const origin = storedContent(store, req.space, ref.kind, ref.id);
        return origin ? { ...ref, originHash: `sha256:${hashContent(origin)}` } : ref;
    });

    return json({
        bundle: {
            format: 'inspecto-metadata-bundle',
            version: 2,
            exportedAt,
            sourceSpace,
            ...(requires.length ? { requires } : {}),
            items,
        },
        missing,
    });
}

export function bundleHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockOps) return undefined;
        const { method, url, space } = req;
        if (method === 'POST' && /\/bundle\/export$/.test(url)) return exportBundle(req, store);
        if (method !== 'POST' || !/\/bundle\/import$/.test(url)) return undefined;

        const body = (req.body ?? {}) as { bundle?: unknown; actions?: Record<string, string> };
        const envelope = (body.bundle ?? body) as { format?: string; items?: IncomingItem[] };
        if (envelope?.format !== 'inspecto-metadata-bundle' || !Array.isArray(envelope.items)) {
            return error(422, 'not an Inspecto metadata bundle');
        }
        const actions = body.actions ?? {};
        const items = [...envelope.items].sort((a, b) => orderOf(a.kind) - orderOf(b.kind));

        const introduced = introducedFindings(store, space, items);
        if (introduced.length) {
            return error(422, `bundle fails referential integrity — import would introduce: ${introduced.join(', ')}`);
        }

        const results: { kind: string; id: string; status: string; message?: string }[] = [];
        let imported = 0, overwritten = 0, skipped = 0, unchanged = 0, failed = 0;

        for (const item of items) {
            const { kind, id } = item;
            const push = (status: string, message?: string) => results.push({ kind, id, status, ...(message ? { message } : {}) });

            if (RETIRED.has(kind)) {
                push('skipped', 'unsupported kind (promote via the UI or whole-space export)');
                skipped++;
                continue;
            }
            if (!item.content || typeof item.content !== 'object') {
                push('failed', "item has no 'content' object");
                failed++;
                continue;
            }
            const secret = kind === 'connection' ? literalSecret(item.content) : null;
            if (secret) {
                push('failed', `refusing a literal secret in "${secret}" — a bundle may only carry \${…} references`);
                failed++;
                continue;
            }

            const coll = collectionFor(kind);
            const current = store.get<Record<string, unknown>>(space, coll, id);
            const incomingHash = item.provenance?.contentHash ?? `sha256:${hashContent(item.content)}`;
            if (current && incomingHash === `sha256:${hashContent(current)}`) {
                push('unchanged');
                unchanged++;
                continue;
            }
            const action = actions[`${kind}/${id}`];
            if (action === 'skip' || (current && action !== 'overwrite')) {
                push('skipped', current
                    ? `already exists (pass actions {"${kind}/${id}":"overwrite"} to replace)`
                    : 'explicit skip action');
                skipped++;
                continue;
            }
            // The id is authoritative — the item's own identity field is stamped from it, exactly as
            // every per-kind mock create does, so a bundle cannot rename an artifact by editing content.
            const idField = kind === 'connection' ? 'id' : 'name';
            store.put(space, coll, id, { ...item.content, [idField]: id });
            push(current ? 'overwritten' : 'imported');
            if (current) overwritten++; else imported++;
        }

        return json({ imported, overwritten, skipped, unchanged, failed, results });
    };
}
