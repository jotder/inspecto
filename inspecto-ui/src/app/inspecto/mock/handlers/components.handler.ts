import type { ComponentDef, ComponentVersion } from '../../api/components.service';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { emitAudit } from '../signals';

/**
 * Unified `/components/*` mock domain — the merge of the old `studio-mock` (dataset / widget /
 * dashboard kinds) and the registry half of `pipeline-mock` (grammar / schema / transform / sink
 * kinds + per-component test), now backed by the persistent {@link MockStore} instead of two
 * module-level constants. The old interceptor-ordering hack (studio before pipeline so the Studio
 * kinds weren't swallowed) is gone — one handler owns the whole family. DELETE now enforces
 * referential integrity (409 + referencers), mirroring the backend. (The grammar preview + ASN.1
 * module library moved to the served parser framework — see `parsers.handler`.)
 */

const STUDIO_KINDS = new Set(['dataset', 'query', 'widget', 'dashboard', 'requirement', 'reconciliation', 'link-analysis-view', 'geo-map-view', 'pattern-pack']);

/** MockStore collection for a component kind. */
export const componentCollection = (kind: string): string => `component:${kind}`;
/** MET-5: MockStore collection holding a kind's archived prior copies (mirrors the backend `.history/`). */
const historyCollection = (kind: string): string => `component-history:${kind}`;
/** Keep the newest N archived copies per component (mirrors the backend keep bound). */
const HISTORY_KEEP = 10;

/** One archived copy as stored in the mock history collection. */
interface StoredVersion { id: string; version: number; savedAt: string; contentHash: string; content: Record<string, unknown>; }

const COMPONENT_TEST = /\/components\/([^/]+)\/([^/]+)\/test$/;
const COMPONENT_VERSIONS = /\/components\/([^/]+)\/([^/]+)\/versions$/;
const COMPONENT_RESTORE = /\/components\/([^/]+)\/([^/]+)\/versions\/([^/]+)\/restore$/;
const COMPONENT_ONE = /\/components\/([^/]+)\/([^/]+)$/;
const COMPONENTS = /\/components\/([^/]+)$/;

export function componentsHandler(flags: MockFlags): MockHandler {
    const enabledFor = (kind: string): boolean =>
        STUDIO_KINDS.has(kind) ? !!flags.mockStudio : !!flags.mockPipelines;

    return (req: MockRequest, store: MockStore) => {
        const { method, url, space } = req;
        let m: string[] | null;

        if (flags.mockPipelines) {
            // The parser-editor's grammar preview + the ASN.1 module library moved to the served
            // parser framework (`/parsers` — see parsers.handler); this domain keeps only the
            // component registry + per-component test.
            if (method === 'POST' && (m = match(url, COMPONENT_TEST))) return json(componentTest(m[1], m[2]));
        }

        // MET-5 version history (before COMPONENT_ONE — these are longer, anchored paths).
        if (method === 'POST' && (m = match(url, COMPONENT_RESTORE)) && enabledFor(m[1])) {
            return restoreVersion(store, space, m[1], m[2], m[3]);
        }
        if (method === 'GET' && (m = match(url, COMPONENT_VERSIONS)) && enabledFor(m[1])) {
            return json(listVersions(store, space, m[1], m[2]));
        }

        if ((m = match(url, COMPONENT_ONE)) && enabledFor(m[1])) {
            const [, kind, id] = m;
            const coll = componentCollection(kind);
            if (method === 'GET') return json(store.get<ComponentDef>(space, coll, id) ?? null);
            if (method === 'PUT') {
                // Mirrors the real backend: update requires the id to already exist (404 otherwise —
                // create is POST). Without this check the mock silently upserted on PUT, masking the
                // same class of offline/live divergence the POST-409 fix (item 3) was written to catch.
                if (!store.get<ComponentDef>(space, coll, id)) {
                    return error(404, `${kind} "${id}" not found`);
                }
                const saved = putComponent(store, space, kind, req.body, id);
                emitAudit(store, space, {
                    action: `${kind}.updated`, category: 'config', targetType: kind, targetId: id,
                    message: `Updated ${kind} ${id}`,
                });
                return json(saved);
            }
            if (method === 'DELETE') {
                const refs = store.referencesTo(space, coll, id);
                if (refs.length) {
                    const by = refs.map((r) => `${r.collection.replace('component:', '')}/${r.id}`).join(', ');
                    return error(409, `${kind} "${id}" is still referenced by: ${by}`);
                }
                deleteComponent(store, space, kind, id);   // purges archived versions too (MET-5)
                emitAudit(store, space, {
                    action: `${kind}.deleted`, category: 'destructive', targetType: kind, targetId: id,
                    message: `Deleted ${kind} ${id}`,
                });
                return json({ deleted: true });
            }
        }
        if ((m = match(url, COMPONENTS)) && enabledFor(m[1])) {
            const kind = m[1];
            if (method === 'GET') return json(store.list<ComponentDef>(space, componentCollection(kind)));
            if (method === 'POST') {
                // Mirror the real backend: create 409s on an existing id (update is PUT). Keeping the
                // mock honest here is what surfaces create-on-edit bugs offline.
                const id = String((req.body as Record<string, unknown> | null)?.['id'] ?? 'unnamed');
                if (store.get<ComponentDef>(space, componentCollection(kind), id)) {
                    return error(409, `${kind} "${id}" already exists`);
                }
                const created = putComponent(store, space, kind, req.body);
                emitAudit(store, space, {
                    action: `${kind}.created`, category: 'config', targetType: kind, targetId: created.name,
                    message: `Created ${kind} ${created.name}`,
                });
                return json(created);
            }
        }
        return undefined;
    };
}

/** Create (POST, id in body) or replace (PUT, id in URL) — mirrors the real id→name split. Exported so
 *  sibling domain handlers persisting a component kind through their own routes (expectations) share the
 *  MET-5 archive-on-save behaviour instead of re-rolling it. */
export function putComponent(store: MockStore, space: string, kind: string, body: unknown, idFromUrl?: string): ComponentDef {
    const content = { ...((body as Record<string, unknown>) ?? {}) };
    const name = String(idFromUrl ?? content['id'] ?? 'unnamed');
    delete content['id'];
    // MET-5: archive the outgoing copy before overwriting it (a create over nothing archives nothing).
    const prior = store.get<ComponentDef>(space, componentCollection(kind), name);
    if (prior) archiveVersion(store, space, kind, name, prior.content);
    const def: ComponentDef = { type: kind, name, ref: `${kind}/${name}`, content };
    return store.put(space, componentCollection(kind), name, def);
}

/** Write a component WITHOUT archiving — for result-stamp updates (e.g. an Expectation's `lastResult`
 *  after a run-check), which are not authoring edits (mirrors the backend's `write(…, archive=false)`). */
export function putComponentQuiet(store: MockStore, space: string, kind: string, content: Record<string, unknown>, name: string): ComponentDef {
    const def: ComponentDef = { type: kind, name, ref: `${kind}/${name}`, content };
    return store.put(space, componentCollection(kind), name, def);
}

/** Delete a component AND its archived versions (mirrors the backend's delete-purges-history). */
export function deleteComponent(store: MockStore, space: string, kind: string, id: string): void {
    store.delete(space, componentCollection(kind), id);
    const coll = historyCollection(kind);
    for (const v of store.list<StoredVersion>(space, coll).filter((v) => v.id === id)) {
        store.delete(space, coll, `${id}~v${v.version}`);
    }
}

/** Snapshot the prior content into the kind's history collection, then prune to {@link HISTORY_KEEP}. */
function archiveVersion(store: MockStore, space: string, kind: string, id: string, content: Record<string, unknown>): void {
    const coll = historyCollection(kind);
    const mine = store.list<StoredVersion>(space, coll).filter((v) => v.id === id);
    const next = mine.reduce((mx, v) => Math.max(mx, v.version), 0) + 1;
    store.put(space, coll, `${id}~v${next}`, {
        id, version: next, savedAt: new Date().toISOString(), contentHash: `mock-${id}-v${next}`, content,
    });
    const kept = [...mine.map((v) => v.version), next].sort((a, b) => b - a);
    for (const v of kept.slice(HISTORY_KEEP)) store.delete(space, coll, `${id}~v${v}`);
}

/** Prior copies of a component, newest first (MET-5). */
function listVersions(store: MockStore, space: string, kind: string, id: string): ComponentVersion[] {
    return store.list<StoredVersion>(space, historyCollection(kind))
        .filter((v) => v.id === id)
        .sort((a, b) => b.version - a.version)
        .map((v) => ({ type: kind, id, version: v.version, savedAt: v.savedAt, contentHash: v.contentHash, content: v.content }));
}

/** Restore an archived version as current (which archives the outgoing copy); mirrors the backend. */
function restoreVersion(store: MockStore, space: string, kind: string, id: string, versionStr: string) {
    const version = Number(versionStr);
    if (!Number.isInteger(version)) return error(400, `version must be an integer, got '${versionStr}'`);
    if (!store.get<ComponentDef>(space, componentCollection(kind), id)) return error(404, `no ${kind} '${id}'`);
    const v = store.get<StoredVersion>(space, historyCollection(kind), `${id}~v${version}`);
    if (!v) return error(404, `no version ${version} of ${kind} '${id}'`);
    const restored = putComponent(store, space, kind, { ...v.content, id }, id);
    emitAudit(store, space, {
        action: `${kind}.restored`, category: 'config', targetType: kind, targetId: id,
        message: `Restored ${kind} ${id} to version ${version}`,
    });
    return json(restored);
}

function componentTest(type: string, idRef: string): unknown {
    return {
        type,
        id: idRef,
        ok: true,
        detail: `${type} "${idRef}" validated against a bounded sample`,
        rowCount: 2,
        rows: [
            { id: 1001, msisdn: '8801700000001', duration_s: 42 },
            { id: 1002, msisdn: '8801700000002', duration_s: 17 },
        ],
    };
}

