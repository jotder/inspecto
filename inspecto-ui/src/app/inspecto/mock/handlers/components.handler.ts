import type { ComponentDef, ComponentVersion } from '../../api/components.service';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest, MockResponse } from '../mock-http';
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

const STUDIO_KINDS = new Set([
    'dataset',
    'query',
    'widget',
    'dashboard',
    'requirement',
    'reconciliation',
    'link-analysis-view',
    'geo-map-view',
    'pattern-pack',
]);

/** MockStore collection for a component kind. */
export const componentCollection = (kind: string): string => `component:${kind}`;
/** MET-5: MockStore collection holding a kind's archived prior copies (mirrors the backend `.history/`). */
const historyCollection = (kind: string): string => `component-history:${kind}`;
/** Keep the newest N archived copies per component (mirrors the backend keep bound). */
const HISTORY_KEEP = 10;

/** One archived copy as stored in the mock history collection. */
interface StoredVersion {
    id: string;
    version: number;
    savedAt: string;
    contentHash: string;
    content: Record<string, unknown>;
}

const COMPONENT_TEST = /\/components\/([^/]+)\/([^/]+)\/test$/;
const COMPONENT_VERSIONS = /\/components\/([^/]+)\/([^/]+)\/versions$/;
const COMPONENT_RESTORE = /\/components\/([^/]+)\/([^/]+)\/versions\/([^/]+)\/restore$/;
const COMPONENT_ONE = /\/components\/([^/]+)\/([^/]+)$/;
const COMPONENTS = /\/components\/([^/]+)$/;
const MAPPING_VALIDATE = /\/components\/mapping\/validate$/;
// ⛔ `grammar` is deliberately EXCLUDED, though the server registers it: this domain gave the grammar
// preview up to the served `/parsers` framework, a boundary a spec pins, and no UI caller needs the
// inline grammar arm — a parse node opens the grammar editor, never the node dialog.
const COMPONENT_PREVIEW = /\/components\/(transform|sink)\/preview$/;

/** The transform-type vocabulary — mirrors `TransformCompiler.TRANSFORM_TYPES`. */
const TRANSFORM_TYPES = new Set(['DIRECT', 'EXPR', 'CONCAT_DT', 'FILENAME_DATE']);

/**
 * Mapping-rule findings — a deliberate mirror of the Java `MappingRules.validate` (inspecto-engine),
 * which is the authority. It exists so the offline preview is NOT more lenient than the server: the
 * backend refuses these rules on both `POST /components/mapping/validate` and the write itself, so a
 * mock that accepted them would greenlight a mapping the server 422s. Keep the two in lockstep —
 * `components.handler.spec.ts` pins the cases.
 */
export function mappingRuleFindings(rules: Record<string, unknown>[]): {
    severity: string;
    fieldPath: string;
    message: string;
}[] {
    const err = (fieldPath: string, message: string) => ({ severity: 'ERROR', fieldPath, message });
    if (!rules.length) return [err('', 'A mapping needs at least one rule.')];
    const out: { severity: string; fieldPath: string; message: string }[] = [];
    const seen = new Map<string, number>();
    rules.forEach((rule, i) => {
        const str = (k: string): string => String(rule[k] ?? '').trim();
        const at = `rules[${i}].`;
        const target = str('targetColumn');
        const source = str('sourceExpression');
        const type = str('transformType').toUpperCase();

        if (!target) out.push(err(`${at}targetColumn`, 'A target column is required.'));
        else if (seen.has(target))
            out.push(
                err(
                    `${at}targetColumn`,
                    `Duplicate target column '${target}' — rule ${seen.get(target)! + 1} already writes it.`,
                ),
            );
        else seen.set(target, i);

        if (type && !TRANSFORM_TYPES.has(type)) {
            out.push(
                err(
                    `${at}transformType`,
                    `Unknown transform type '${str('transformType')}'. ` +
                        `Valid: DIRECT (or leave blank), ${[...TRANSFORM_TYPES].sort().join(', ')}.`,
                ),
            );
            return;
        }
        if (!source) {
            out.push(err(`${at}sourceExpression`, 'A source expression is required.'));
            return;
        }
        if (type === 'CONCAT_DT' && !source.includes('|'))
            out.push(err(`${at}sourceExpression`, "CONCAT_DT needs '<dateColumn>|<timeColumn>'."));
        if (type === 'FILENAME_DATE' && target !== 'EVENT_DATE')
            out.push(
                err(`${at}targetColumn`, `FILENAME_DATE is only supported for the EVENT_DATE column, got '${target}'.`),
            );
    });
    return out;
}

export function componentsHandler(flags: MockFlags): MockHandler {
    const enabledFor = (kind: string): boolean => (STUDIO_KINDS.has(kind) ? !!flags.mockStudio : !!flags.mockPipelines);

    return (req: MockRequest, store: MockStore) => {
        const { method, url, space } = req;
        let m: string[] | null;

        if (flags.mockPipelines) {
            // The parser-editor's grammar preview + the ASN.1 module library moved to the served
            // parser framework (`/parsers` — see parsers.handler); this domain keeps only the
            // component registry + per-component test.
            if (method === 'POST' && (m = match(url, COMPONENT_TEST))) return json(componentTest(m[1], m[2]));
        }

        // Inline preview — before COMPONENT_ONE, which would otherwise read `preview` as a component id
        // (the same ordering the server uses: the literal paths register ahead of the `{id}` patterns).
        if (method === 'POST' && (m = match(url, COMPONENT_PREVIEW))) {
            return previewInline(m[1], req.body as Record<string, unknown> | null);
        }

        // S6b mapping validate — before COMPONENT_ONE, which would otherwise read `validate` as an id.
        if (method === 'POST' && MAPPING_VALIDATE.test(url) && enabledFor('mapping')) {
            const rules = (req.body as Record<string, unknown> | null)?.['rules'];
            if (!Array.isArray(rules)) return error(400, "body must include 'rules' (a list of mapping rules)");
            if (rules.some((r) => typeof r !== 'object' || r === null || Array.isArray(r)))
                return error(400, "every entry of 'rules' must be an object");
            const findings = mappingRuleFindings(rules as Record<string, unknown>[]);
            return json({ type: 'mapping', findings, clean: findings.length === 0 });
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
                const refusal = mappingWriteRefusal(kind, req.body);
                if (refusal) return error(422, refusal);
                const saved = putComponent(store, space, kind, req.body, id);
                emitAudit(store, space, {
                    action: `${kind}.updated`,
                    category: 'config',
                    targetType: kind,
                    targetId: id,
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
                deleteComponent(store, space, kind, id); // purges archived versions too (MET-5)
                emitAudit(store, space, {
                    action: `${kind}.deleted`,
                    category: 'destructive',
                    targetType: kind,
                    targetId: id,
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
                const refusal = mappingWriteRefusal(kind, req.body);
                if (refusal) return error(422, refusal);
                const created = putComponent(store, space, kind, req.body);
                emitAudit(store, space, {
                    action: `${kind}.created`,
                    category: 'config',
                    targetType: kind,
                    targetId: created.name,
                    message: `Created ${kind} ${created.name}`,
                });
                return json(created);
            }
        }
        return undefined;
    };
}

/**
 * The 422 message a mapping write earns, or `null` when it may proceed — mirrors the backend's
 * `validateKind` gate (S6b), which refuses invalid rules on create AND update. Non-mapping kinds and
 * a body carrying no `rules` are untouched, exactly as server-side.
 */
function mappingWriteRefusal(kind: string, body: unknown): string | null {
    if (kind !== 'mapping') return null;
    const rules = (body as Record<string, unknown> | null)?.['rules'];
    if (rules === undefined) return null;
    if (!Array.isArray(rules)) return "mapping 'rules' must be a list";
    if (rules.some((r) => typeof r !== 'object' || r === null || Array.isArray(r)))
        return "every entry of mapping 'rules' must be an object";
    const findings = mappingRuleFindings(rules as Record<string, unknown>[]);
    if (!findings.length) return null;
    return (
        'mapping rules are invalid: ' +
        findings.map((f) => (f.fieldPath ? `${f.fieldPath}: ` : '') + f.message).join('; ')
    );
}

/** Create (POST, id in body) or replace (PUT, id in URL) — mirrors the real id→name split. Exported so
 *  sibling domain handlers persisting a component kind through their own routes (expectations) share the
 *  MET-5 archive-on-save behaviour instead of re-rolling it. */
export function putComponent(
    store: MockStore,
    space: string,
    kind: string,
    body: unknown,
    idFromUrl?: string,
): ComponentDef {
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
export function putComponentQuiet(
    store: MockStore,
    space: string,
    kind: string,
    content: Record<string, unknown>,
    name: string,
): ComponentDef {
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
function archiveVersion(
    store: MockStore,
    space: string,
    kind: string,
    id: string,
    content: Record<string, unknown>,
): void {
    const coll = historyCollection(kind);
    const mine = store.list<StoredVersion>(space, coll).filter((v) => v.id === id);
    const next = mine.reduce((mx, v) => Math.max(mx, v.version), 0) + 1;
    store.put(space, coll, `${id}~v${next}`, {
        id,
        version: next,
        savedAt: new Date().toISOString(),
        contentHash: `mock-${id}-v${next}`,
        content,
    });
    const kept = [...mine.map((v) => v.version), next].sort((a, b) => b - a);
    for (const v of kept.slice(HISTORY_KEEP)) store.delete(space, coll, `${id}~v${v}`);
}

/** Prior copies of a component, newest first (MET-5). */
function listVersions(store: MockStore, space: string, kind: string, id: string): ComponentVersion[] {
    return store
        .list<StoredVersion>(space, historyCollection(kind))
        .filter((v) => v.id === id)
        .sort((a, b) => b.version - a.version)
        .map((v) => ({
            type: kind,
            id,
            version: v.version,
            savedAt: v.savedAt,
            contentHash: v.contentHash,
            content: v.content,
        }));
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
        action: `${kind}.restored`,
        category: 'config',
        targetType: kind,
        targetId: id,
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

/**
 * `POST /components/{transform|sink}/preview` — the offline arm of the inline preview
 * (`ComponentRoutes.previewInline*`). It mirrors the server's REFUSALS exactly, which is the point: the
 * 400 on a missing `config`, the 400 on an empty sample, and the 422 when a transform config is not
 * `transform.*` are all decisions an operator must meet offline too. What it cannot mirror is the
 * OUTCOME — the server runs the production `RowShaper` on a throwaway DuckDB and this has no SQL engine
 * — so the happy path reports the shape it can compute honestly: the input columns, and the sample's own
 * row count on the default relation. ⛔ Do not grow this into a second transform evaluator.
 */
function previewInline(family: string, body: Record<string, unknown> | null): MockResponse {
    const config = body?.['config'];
    if (typeof config !== 'object' || config === null || Array.isArray(config))
        return error(400, "body must include 'config' (the node config object to preview)");
    const cfg = config as Record<string, unknown>;
    const rows = body?.['sampleRows'];
    if (!Array.isArray(rows) || !rows.length) return error(400, 'sampleRows is required');
    const sample = rows as Record<string, unknown>[];
    const columns = [...new Set(sample.flatMap((r) => Object.keys(r ?? {})))];
    if (family === 'sink') {
        const store = typeof cfg['store'] === 'string' ? (cfg['store'] as string) : null;
        return json({
            store,
            rowCount: sample.length,
            rows: sample.slice(0, 20),
            warnings: store ? [] : ["sink declares no 'store' name"],
        });
    }
    const type = typeof cfg['type'] === 'string' ? (cfg['type'] as string) : '';
    if (!type.startsWith('transform.'))
        return error(422, "inline config is not a transform ('type: transform.*' required)");
    return json({
        inputColumns: columns,
        relations: [{ rel: 'data', rowCount: sample.length, rows: sample.slice(0, 20) }],
    });
}
