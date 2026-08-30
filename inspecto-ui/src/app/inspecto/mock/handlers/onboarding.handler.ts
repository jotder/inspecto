import type { ComponentDef } from '../../api/components.service';
import type { ConfigDependent } from '../../api/models';
import { configPipelineId } from '../../component-model/pipeline-scaffold';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { componentCollection } from './components.handler';

/**
 * Stream-onboarding mock domain — the offline mirror of the server-side draft lifecycle
 * (`ConfigRoutes` + pipeline registration, v5.1.0/v5.2.0): write → register → read back →
 * overwrite → delete, plus the stateless `POST /config/preview/parsing` / `.../schema` sample
 * previews and `POST /config/suggest/schema` draft inference
 * (tiny JS parsers that mimic the DuckDB frontends' behaviour: header skip,
 * min-record-length drop, named regex groups, NDJSON validity filter, TRY_CAST-alike type
 * checks). Pipeline + schema + enrichment configs (enrichment registration mirrors pipelines:
 * `POST /enrichment` flips the stored flag); `/config/spec` + `/validate` stay with the demo
 * handler (registered ahead of this one).
 */

export const PIPELINE_CONFIGS_COLL = 'pipeline-config';
export const SCHEMA_CONFIGS_COLL = 'schema-config';
export const ENRICHMENT_CONFIGS_COLL = 'enrichment-config';

/** One stored draft/config file: the decoded map + its write-root-relative path. */
export interface StoredPipelineConfig {
    id: string;
    path: string;
    config: Record<string, unknown>;
    registered: boolean;
}

/** One stored schema file — no registration/active concept (never a pipeline itself). */
export interface StoredSchemaConfig {
    id: string;
    path: string;
    config: Record<string, unknown>;
}

/** One stored enrichment file — registration mirrors pipelines (POST /enrichment hot-registers). */
export interface StoredEnrichmentConfig {
    id: string;
    path: string;
    config: Record<string, unknown>;
    registered: boolean;
}

const WRITE = /\/config\/write$/;
const PATCH = /\/config\/patch$/;
const PREVIEW_PARSING = /\/config\/preview\/parsing$/;
const PREVIEW_SCHEMA = /\/config\/preview\/schema$/;
const SUGGEST_SCHEMA = /\/config\/suggest\/schema$/;
const CONFIG_FILE = /\/config\/(pipeline|schema|enrichment)\/([^/?]+)$/;
const SCHEMA_DERIVED = /\/config\/schema\/derived(\?.*)?$/;
const CONFIG_IMPACT = /\/config\/pipeline\/([^/?]+)\/impact$/;
const RUNS = /\/runs$/;
const ENRICHMENT = /\/enrichment$/;

/**
 * Reverse-dependency scan mirroring the backend `PipelineDependents` — same binding keys, same
 * matching rules, same two transitive Studio hops (dataset → widget → dashboard). ⚠ It must stay
 * exactly as strict as the server: a mock that reports no dependents where the backend reports some
 * turns a 409 into a passing offline rehearsal of a delete that will fail for real.
 */
function scanDependents(store: MockStore, space: string, id: string): Record<string, ConfigDependent[]> {
    const out: Record<string, ConfigDependent[]> = {};
    const add = (kind: string, name: string, via: string): void => {
        (out[kind] ??= []).push({ name, via });
    };
    const matches = (v: unknown): boolean =>
        String(v ?? '')
            .trim()
            .toLowerCase() === id.toLowerCase();

    for (const rec of store.list<StoredEnrichmentConfig>(space, ENRICHMENT_CONFIGS_COLL)) {
        const cfg = rec.config;
        const triggers = cfg['triggers'] as Record<string, unknown> | undefined;
        if (triggers && matches(triggers['on_pipeline'])) add('enrichment', rec.id, 'triggers.on_pipeline');
        const refs = cfg['references'] as Record<string, Record<string, unknown>> | undefined;
        for (const [rname, rv] of Object.entries(refs ?? {})) {
            if (rv && matches(rv['ref'])) add('enrichment', rec.id, `references.${rname}.ref`);
        }
    }

    for (const kind of ['expectation', 'decision-rule']) {
        for (const def of store.list<ComponentDef>(space, componentCollection(kind))) {
            const targetType = String(def.content?.['targetType'] ?? 'pipeline');
            if (targetType.toLowerCase() !== 'pipeline') continue;
            if (matches(def.content?.['target'])) add(kind, def.name, 'target');
        }
    }

    const datasets = new Set<string>();
    for (const def of store.list<ComponentDef>(space, componentCollection('dataset'))) {
        if (matches(def.content?.['sourceName'])) {
            add('dataset', def.name, 'sourceName');
            datasets.add(def.name);
            continue;
        }
        const ref = String(def.content?.['physicalRef'] ?? '').trim();
        if (!ref) continue;
        const head = ref.includes('/') ? ref.slice(0, ref.indexOf('/')) : ref;
        if (head.toLowerCase() === id.toLowerCase()) {
            add('dataset', def.name, 'physicalRef');
            datasets.add(def.name);
        }
    }

    const widgets = new Set<string>();
    if (datasets.size) {
        for (const def of store.list<ComponentDef>(space, componentCollection('widget'))) {
            const dsId = String(def.content?.['datasetId'] ?? '').trim();
            if (dsId && datasets.has(dsId)) {
                add('widget', def.name, 'datasetId');
                widgets.add(def.name);
            }
        }
    }
    if (widgets.size) {
        for (const def of store.list<ComponentDef>(space, componentCollection('dashboard'))) {
            const tiles = (def.content?.['tiles'] as { widgetId?: string }[] | undefined) ?? [];
            if (tiles.some((t) => t?.widgetId && widgets.has(String(t.widgetId).trim()))) {
                add('dashboard', def.name, 'tiles[].widgetId');
            }
        }
    }
    return out;
}

/** `WriteGates.safeName` — a name/id unusable as a jailed filename is a 422, not a silent slug. */
const SAFE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

function collFor(type: string): string {
    return type === 'schema'
        ? SCHEMA_CONFIGS_COLL
        : type === 'enrichment'
          ? ENRICHMENT_CONFIGS_COLL
          : PIPELINE_CONFIGS_COLL;
}

export function onboardingHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockDemo) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'POST' && WRITE.test(url)) {
            const body = (req.body ?? {}) as { type?: string; config?: Record<string, unknown>; overwrite?: boolean };
            if (!body.config) return undefined;
            if (body.type === 'pipeline') {
                const display = String(body.config['name'] ?? '').trim();
                if (!display) return error(422, "config is missing its identity field 'name'");
                // ⚠ The file — and this store's key — is named for the config's IDENTITY, not its
                // display label: `id:` when stamped, else the derivation. Keyed by `name` this mock
                // stored "My Pipe" where the server stores "my_pipe", so every later read by the id
                // the list route hands back would miss. Mirrors `ConfigRoutes.identityFields`.
                const name = configPipelineId(body.config, display);
                if (!SAFE_NAME.test(name))
                    return error(422, `unsafe config name '${name}' (allowed: letters, digits, '.', '_', '-')`);
                const existing = store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, name);
                if (existing && !body.overwrite)
                    return error(409, `file exists: ${name}.toon (pass overwrite:true to replace)`);
                // Both zone gates below now mirror REAL server rules: ConfigSpecs.pipeline() carries
                // `parsing-source-timezone-resolvable` and `parsing-formats-carry-no-zone-directive`,
                // and ConfigWriteRoutes 422s on ERROR findings. ⚠ Until 2026-08-29 the first of these
                // existed HERE ONLY — the mock was ahead of the server, so an author saw a 422 offline
                // and a silent load-time failure against a real backend.
                const parsingBlock = body.config['parsing'] as Record<string, unknown> | undefined;
                if (parsingBlock && parsingBlock['source_timezone'] !== undefined) {
                    const bad = zoneRefusal(parsingBlock['source_timezone'], 'parsing.source_timezone');
                    if (bad) return error(422, bad);
                }
                const badFormat = formatZoneDirectiveRefusal(parsingBlock);
                if (badFormat) return error(422, badFormat);
                // The real route writes `<name>_pipeline.toon` — the bootstrap-scan convention.
                const path = name.endsWith('_pipeline') ? `${name}.toon` : `${name}_pipeline.toon`;
                store.put(space, PIPELINE_CONFIGS_COLL, name, {
                    id: name,
                    path,
                    config: body.config,
                    registered: existing?.registered ?? false,
                } satisfies StoredPipelineConfig);
                return json({
                    type: 'pipeline',
                    written: true,
                    path,
                    name,
                    bytes: JSON.stringify(body.config).length,
                    overwritten: !!existing,
                    findings: [],
                });
            }
            if (body.type === 'schema') {
                const raw = (body.config['raw'] ?? {}) as Record<string, unknown>;
                const name = String(raw['name'] ?? '').trim();
                if (!name) return error(422, "config is missing its identity field 'raw.name'");
                // The registry component (component:schema) and the config file (schema-config) are
                // the SAME file server-side — the gate must diff against whichever the mock holds.
                const compColl = componentCollection('schema');
                const existing = store.get<StoredSchemaConfig>(space, SCHEMA_CONFIGS_COLL, name);
                const existingComp = store.get<{ content?: Record<string, unknown> }>(space, compColl, name);
                const baseline = existing?.config ?? existingComp?.content;
                // The BACKWARD compatibility gate (SchemaCompatibility.check + ConfigRoutes.write,
                // mock-never-more-lenient): 422 with cell-anchored findings unless overridden.
                const compatibility = String((body as { compatibility?: unknown }).compatibility ?? '');
                if (baseline && compatibility.toLowerCase() !== 'none') {
                    const findings = schemaBackwardFindings(baseline, body.config);
                    if (findings.length) {
                        return error(422, 'schema edit is not BACKWARD-compatible; not written', {
                            type: 'schema',
                            written: false,
                            findings,
                        });
                    }
                }
                const zoneBad = schemaZoneFindings(body.config);
                if (zoneBad.length)
                    return error(422, zoneBad[0], { type: 'schema', written: false, findings: zoneBad });
                store.put(space, SCHEMA_CONFIGS_COLL, name, {
                    id: name,
                    path: `${name}.toon`,
                    config: body.config,
                } satisfies StoredSchemaConfig);
                store.put(space, compColl, name, {
                    type: 'schema',
                    name,
                    ref: `schema/${name}`,
                    content: body.config,
                });
                return json({
                    type: 'schema',
                    written: true,
                    path: `${name}.toon`,
                    name,
                    bytes: JSON.stringify(body.config).length,
                    overwritten: !!baseline,
                    findings: [],
                });
            }
            if (body.type === 'enrichment') {
                const name = String(body.config['name'] ?? '').trim();
                if (!name) return error(422, "config is missing its identity field 'name'");
                const existing = store.get<StoredEnrichmentConfig>(space, ENRICHMENT_CONFIGS_COLL, name);
                if (existing && !body.overwrite)
                    return error(409, `file exists: ${name}.toon (pass overwrite:true to replace)`);
                // The real route writes `<name>_enrich.toon` — the bootstrap-scan convention.
                const path = name.endsWith('_enrich') ? `${name}.toon` : `${name}_enrich.toon`;
                store.put(space, ENRICHMENT_CONFIGS_COLL, name, {
                    id: name,
                    path,
                    config: body.config,
                    registered: existing?.registered ?? false,
                } satisfies StoredEnrichmentConfig);
                return json({
                    type: 'enrichment',
                    written: true,
                    path,
                    name,
                    bytes: JSON.stringify(body.config).length,
                    overwritten: !!existing,
                    findings: [],
                });
            }
            return undefined; // other types: not mocked here
        }

        // Block-level save — mirrors ConfigRoutes.patchConfig with the SERVER's strictness
        // (mock-never-more-lenient): 400 bad body, 404 unknown type/missing file (patch never
        // creates), 409 identity change, deep merge with explicit-null deletes.
        if (method === 'POST' && PATCH.test(url)) {
            const body = (req.body ?? {}) as { type?: string; name?: string; patch?: Record<string, unknown> };
            const name = String(body.name ?? '').trim();
            if (
                !body.type ||
                !name ||
                typeof body.patch !== 'object' ||
                body.patch === null ||
                Array.isArray(body.patch)
            ) {
                return error(400, "body must include 'type', 'name' and 'patch' (a partial config map)");
            }
            if (!['pipeline', 'schema', 'enrichment'].includes(body.type)) {
                return error(404, `unknown config type: ${body.type}`);
            }
            const coll = collFor(body.type);
            const rec = store.get<StoredPipelineConfig>(space, coll, name);
            if (!rec) return error(404, `no such config: ${name}.toon (create it via /config/write first)`);
            const idField = body.type === 'schema' ? 'raw' : 'name';
            const merged = deepMergeNullDeletes(rec.config, body.patch);
            const before = identityOf(rec.config, body.type);
            const after = identityOf(merged, body.type);
            if (before && before !== after) {
                return error(
                    409,
                    `patch changes the identity field '${idField}' (${before} → ${after}); rename via /config/write`,
                );
            }
            // Same BACKWARD gate as /config/write; patch has NO compatibility override key.
            if (body.type === 'schema') {
                const findings = schemaBackwardFindings(rec.config, merged);
                if (findings.length) {
                    return error(422, 'schema edit is not BACKWARD-compatible; not written', {
                        type: 'schema',
                        written: false,
                        findings,
                    });
                }
                const zoneBad = schemaZoneFindings(merged);
                if (zoneBad.length)
                    return error(422, zoneBad[0], { type: 'schema', written: false, findings: zoneBad });
                store.put(space, componentCollection('schema'), name, {
                    type: 'schema',
                    name,
                    ref: `schema/${name}`,
                    content: merged,
                });
            }
            store.put(space, coll, name, { ...rec, config: merged });
            return json({
                type: body.type,
                written: true,
                path: rec.path,
                name,
                bytes: JSON.stringify(merged).length,
                overwritten: true,
                findings: [],
            });
        }

        if (method === 'POST' && PREVIEW_PARSING.test(url)) {
            const body = (req.body ?? {}) as { config?: Record<string, unknown>; sample_text?: string };
            if (!body.config || !String(body.sample_text ?? '').trim()) {
                return error(400, "body must include 'config' (a pipeline draft map) and 'sample_text'");
            }
            try {
                return json(previewParsing(body.config, String(body.sample_text)));
            } catch (e) {
                return error(422, e instanceof Error ? e.message : 'sample does not parse with these settings');
            }
        }

        if (method === 'POST' && PREVIEW_SCHEMA.test(url)) {
            const body = (req.body ?? {}) as {
                config?: Record<string, unknown>;
                sampleRows?: Record<string, unknown>[];
            };
            if (!body.config || !body.sampleRows?.length) {
                return error(400, "body must include 'config' (a schema draft map) and non-empty 'sampleRows'");
            }
            try {
                return json(previewSchema(body.config, body.sampleRows));
            } catch (e) {
                return error(422, e instanceof Error ? e.message : 'schema preview failed');
            }
        }

        if (method === 'POST' && SUGGEST_SCHEMA.test(url)) {
            const body = (req.body ?? {}) as {
                sampleRows?: Record<string, unknown>[];
                config?: Record<string, unknown>;
            };
            if (!body.sampleRows?.length) {
                return error(400, "body must include non-empty 'sampleRows'");
            }
            try {
                // B3: `config` is the draft the caller holds; absent, there is nothing to have drifted from.
                return json(suggestSchema(body.sampleRows, body.config));
            } catch (e) {
                return error(422, e instanceof Error ? e.message : 'schema suggestion failed');
            }
        }

        // GET /config/schema/derived?pipeline=… (step-workbench S5). ⚠ Mirrors the server's GATES
        // exactly — 400 missing param, 403 a path where a bare name belongs, 404 unknown pipeline,
        // 422 no schema to derive from — because a mock that answers 200 where the server refuses
        // turns a hard failure into a passing rehearsal.
        // ⚠ FIDELITY LIMIT, stated rather than hidden: the real route derives TYPES from DuckDB
        // (`DESCRIBE`). Offline there is no engine, so the declared field types are echoed instead.
        // The SHAPE and the gates are faithful; the exact type strings are not, and no offline check
        // can stand in for the server's own inference.
        if (method === 'GET' && SCHEMA_DERIVED.test(url)) {
            // ⚠ Query params arrive as req.params, NOT in the url — Angular's HttpRequest.url carries
            // no query string, so parsing the url here silently sees every request as param-less.
            const pipeline = (req.params['pipeline'] ?? '').trim();
            if (!pipeline) return error(400, "query parameter 'pipeline' is required");
            if (pipeline.includes('/') || pipeline.includes('\\') || pipeline.includes('..'))
                return error(403, "'pipeline' must be a bare pipeline name, not a path");
            const rec = store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, pipeline);
            if (!rec) return error(404, `no pipeline named '${pipeline}'`);

            const processing = (rec.config['processing'] ?? {}) as Record<string, unknown>;
            const parsing = (rec.config['parsing'] ?? {}) as Record<string, unknown>;
            const plugin = (parsing['plugin'] ?? {}) as Record<string, unknown>;
            const ingesterClass = (plugin['ingester'] ?? processing['ingester'] ?? null) as string | null;
            const schemaRef = String(processing['schema_file'] ?? '').replace(/\.toon$/, '');
            const schema = schemaRef
                ? (store.get<StoredSchemaConfig>(space, SCHEMA_CONFIGS_COLL, schemaRef)?.config ??
                  (store.get<{ content?: Record<string, unknown> }>(space, componentCollection('schema'), schemaRef)
                      ?.content as Record<string, unknown> | undefined))
                : undefined;
            if (!schema)
                return error(
                    422,
                    `pipeline '${pipeline}' declares no schema (a draft may be saved schema-less, but nothing can be derived from it)`,
                );

            const typedSource = !!ingesterClass && String(ingesterClass).trim() !== '';
            const raw = (schema['raw'] ?? {}) as Record<string, unknown>;
            const fields = (raw['fields'] ?? []) as { name?: string; type?: string }[];
            const columns = fields
                .filter((f) => !!f?.name)
                .map((f) => ({ name: String(f.name), type: typedSource ? String(f.type ?? 'VARCHAR') : 'VARCHAR' }));
            return json({
                pipeline,
                sourcePath: typedSource ? 'plugin' : 'csv',
                typedSource,
                ingesterClass: ingesterClass ?? null,
                schemas: [{ key: 'single', table: schemaRef || null, columns }],
            });
        }

        if (method === 'GET' && (m = match(url, CONFIG_FILE))) {
            const [, type, name] = m;
            const rec = store.get<StoredPipelineConfig | StoredSchemaConfig>(space, collFor(type), name);
            if (!rec) return error(404, `no such config: ${name}.toon`);
            return json({ type, name: rec.id, path: rec.path, config: rec.config });
        }

        if (method === 'GET' && (m = match(url, CONFIG_IMPACT))) {
            const rec = store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, m[1]);
            if (!rec) return error(404, `no such config: ${m[1]}.toon`);
            const id = configPipelineId(rec.config, rec.id);
            const dependents = scanDependents(store, space, id);
            const total = Object.values(dependents).reduce((n, xs) => n + xs.length, 0);
            return json({ pipeline: id, total, truncated: false, dependents });
        }

        if (method === 'DELETE' && (m = match(url, CONFIG_FILE))) {
            const [, type, name] = m;
            const rec = store.get<StoredPipelineConfig | StoredSchemaConfig>(space, collFor(type), name);
            if (!rec) return error(404, `no such config: ${name}.toon`);
            if (type === 'pipeline' && (rec as StoredPipelineConfig).config['active'] === true) {
                return error(409, `pipeline '${rec.id}' is active; deactivate (active: false) before deleting`);
            }
            if (type === 'pipeline' && String(req.params['force'] ?? '').toLowerCase() !== 'true') {
                const id = configPipelineId((rec as StoredPipelineConfig).config, rec.id);
                const dependents = scanDependents(store, space, id);
                const names = Object.entries(dependents).flatMap(([kind, xs]) => xs.map((x) => `${kind}/${x.name}`));
                if (names.length) {
                    return error(
                        409,
                        `pipeline '${id}' is referenced by ${names.length} config(s): ${names.join(', ')}` +
                            ` — repoint or remove them, or re-send with ?force=true`,
                    );
                }
            }
            store.delete(space, collFor(type), rec.id);
            return json({ type, name: rec.id, deleted: true, path: rec.path });
        }

        if (method === 'POST' && RUNS.test(url)) {
            const configPath = String((req.body as { configPath?: string } | null)?.configPath ?? '').trim();
            if (!configPath) return error(400, "body must include 'configPath'");
            const rec = store
                .list<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL)
                .find((r) => r.path === configPath || `${r.id}.toon` === configPath);
            if (!rec) return error(404, `no config file at ${configPath}`);
            store.put(space, PIPELINE_CONFIGS_COLL, rec.id, { ...rec, registered: true });
            return json({ registered: true, id: rec.id, path: rec.path, findings: [] });
        }

        if (method === 'POST' && ENRICHMENT.test(url)) {
            const configPath = String((req.body as { configPath?: string } | null)?.configPath ?? '').trim();
            if (!configPath) return error(400, "body must include 'configPath'");
            const rec = store
                .list<StoredEnrichmentConfig>(space, ENRICHMENT_CONFIGS_COLL)
                .find((r) => r.path === configPath || `${r.id}.toon` === configPath);
            if (!rec) return error(404, `no config file at ${configPath}`);
            store.put(space, ENRICHMENT_CONFIGS_COLL, rec.id, { ...rec, registered: true });
            return json({ registered: true, name: rec.id, path: rec.path, findings: [] });
        }

        return undefined;
    };
}

/** Old type → the strictly-more-general types it may widen to (SchemaCompatibility.WIDENINGS). */
const WIDENINGS: Record<string, string[]> = {
    INTEGER: ['BIGINT', 'DOUBLE'],
    BIGINT: ['DOUBLE'],
    DATE: ['TIMESTAMP'],
};

/**
 * Mirrors `SchemaCompatibility.check` exactly (mock-never-more-lenient, both directions): BACKWARD-
 * diff existing → draft `raw.fields[]`. ERROR on field removed, type changed outside the widening
 * lattice (anything → VARCHAR is fine; types compare case-insensitively), or selector moved
 * (compared verbatim). Findings anchor `raw.fields[NAME]` / `.type` / `.selector`, same messages.
 */
/**
 * The SERVER's source-zone refusals, mirrored (mock-never-more-lenient) — `SourceZones.validateZone`
 * plus `Identifiers.validateFieldZone`.
 *
 * <p>⚠ The engine gates on the JVM's `ZoneId.getAvailableZoneIds()`, which a browser cannot enumerate.
 * `Intl.DateTimeFormat` is used as the resolver instead because it accepts the same region ids —
 * INCLUDING legacy aliases like `Asia/Calcutta` that `Intl.supportedValuesOf` omits, so validating
 * against the offered list would refuse values the server accepts (stricter, but wrong).
 *
 * <p>⛔ Offset forms are rejected BEFORE the resolver: modern engines accept `+05:30` as a valid
 * `timeZone`, DuckDB does not, so the resolver alone would green-light the one mistake operators
 * actually make.
 */
/**
 * The `%z`/`%Z` refusal, mirroring `SourceZoneGrammar.formatRefusal` + the
 * `parsing-formats-carry-no-zone-directive` rule on `ConfigSpecs.pipeline()`.
 *
 * A zone directive parses the offset and then loses it: the engine's trailing `::TIMESTAMP` renders
 * that instant in the SERVER's zone, so the same file imports differently on different machines.
 *
 * ⚠ `%%` is an escaped literal percent, so `'%%z'` is the two characters `%z` in the input text and
 * is NOT a directive — the scan consumes `%%` as a pair. ⚠ Only the `delimited:` block is checked: a
 * list authored at `parsing:` level is not read by the engine's `mergeParsing` at all.
 */
export function formatZoneDirectiveRefusal(parsing: Record<string, unknown> | undefined): string | null {
    const delimited = parsing?.['delimited'] as Record<string, unknown> | undefined;
    if (!delimited) return null;
    for (const key of ['date_formats', 'timestamp_formats']) {
        const formats = delimited[key];
        if (!Array.isArray(formats)) continue;
        for (const fmt of formats) {
            const directive = zoneDirectiveIn(String(fmt ?? ''));
            if (!directive) continue;
            return (
                `parsing.delimited.${key}: format '${fmt}' uses the zone directive '%${directive}', ` +
                `which is not supported. The offset it parses is then re-rendered in the SERVER's zone, ` +
                `so the same file would import differently on different machines. Declare the zone ` +
                `instead: parsing.source_timezone, or raw.fields[].timezone / .timezone_column.`
            );
        }
    }
    return null;
}

/** The zone directive in a strptime format, or `''` for none. Exactly `%z` and `%Z`. */
function zoneDirectiveIn(format: string): string {
    for (let i = 0; i < format.length - 1; i++) {
        if (format[i] !== '%') continue;
        const next = format[i + 1];
        if (next === '%') {
            i++;
            continue;
        } // escaped literal — consume the pair
        if (next === 'z' || next === 'Z') return next;
    }
    return '';
}

export function zoneRefusal(zone: unknown, where: string): string | null {
    const z = String(zone ?? '').trim();
    if (!z) return `${where} is blank — remove the key or name a zone`;
    if (/^[+-]/.test(z) || z === 'Z')
        return `${where}: a fixed offset is not accepted (it cannot express daylight saving); use the IANA region id, e.g. 'Asia/Kolkata'`;
    try {
        new Intl.DateTimeFormat('en-US', { timeZone: z });
    } catch {
        return `${where}: unknown time zone '${z}' — use an IANA region id such as 'Asia/Kolkata'`;
    }
    return null;
}

/**
 * Every source-zone refusal a schema config would draw from the server, in `Identifiers`' order.
 *
 * <p>⚠ Deliberately does NOT include the engine's *TIMESTAMPTZ needs a zone* check: that one is a
 * PIPELINE-load rule (it has to see `parsing.source_timezone`, which lives in a different file), and a
 * schema write here cannot see the pipeline that references it. The columns editor hints it per row
 * instead. Known gap, not an oversight — the server still refuses at load.
 */
export function schemaZoneFindings(config: Record<string, unknown>): string[] {
    const raw = config['raw'] as Record<string, unknown> | undefined;
    const fields = Array.isArray(raw?.['fields']) ? (raw!['fields'] as Record<string, unknown>[]) : [];
    const declared = new Set(fields.map((f) => String(f['name'] ?? '')));
    const out: string[] = [];
    for (const f of fields) {
        const name = String(f['name'] ?? '');
        const tz = String(f['timezone'] ?? '').trim();
        const tzc = String(f['timezone_column'] ?? '').trim();
        if (tz && tzc) {
            out.push(
                `Schema field '${name}' sets both timezone and timezone_column. timezone_column takes precedence, so the fixed timezone would never apply — keep one.`,
            );
            continue;
        }
        if (tz) {
            const bad = zoneRefusal(tz, `raw.fields[${name}].timezone`);
            if (bad) out.push(bad);
        }
        if (tzc && !declared.has(tzc))
            out.push(`Schema field '${name}' sets timezone_column '${tzc}', which is not a declared raw field.`);
    }
    return out;
}

export function schemaBackwardFindings(
    existing: Record<string, unknown>,
    draft: Record<string, unknown>,
): { severity: string; fieldPath: string; message: string }[] {
    const oldFields = fieldsByName(existing);
    const newFields = fieldsByName(draft);
    if (!oldFields.size) return [];

    const findings: { severity: string; fieldPath: string; message: string }[] = [];
    const up = (v: unknown): string => (v === null || v === undefined ? '' : String(v).trim().toUpperCase());
    const verbatim = (v: unknown): string => (v === null || v === undefined ? '' : String(v).trim());
    const isWidening = (o: string, n: string): boolean => n === 'VARCHAR' || (WIDENINGS[o] ?? []).includes(n);

    for (const [name, of] of oldFields) {
        const nf = newFields.get(name);
        if (!nf) {
            findings.push({
                severity: 'ERROR',
                fieldPath: `raw.fields[${name}]`,
                message:
                    `breaking change (BACKWARD): field '${name}' removed — existing data and referencing ` +
                    `Mappings still expect it; copy the schema to a new name, or pass compatibility: "none" to override`,
            });
            continue;
        }
        const oldType = up(of['type']);
        const newType = up(nf['type']);
        if (oldType && oldType !== newType && !isWidening(oldType, newType)) {
            findings.push({
                severity: 'ERROR',
                fieldPath: `raw.fields[${name}].type`,
                message:
                    `breaking change (BACKWARD): type of '${name}' changed ${oldType} → ` +
                    `${newType || '(none)'} (not a widening) — already-written data would not read back; ` +
                    `copy to a new name, or pass compatibility: "none"`,
            });
        }
        const oldSel = verbatim(of['selector']);
        const newSel = verbatim(nf['selector']);
        if (oldSel && oldSel !== newSel) {
            findings.push({
                severity: 'ERROR',
                fieldPath: `raw.fields[${name}].selector`,
                message:
                    `breaking change (BACKWARD): selector of '${name}' moved '${oldSel}' → '${newSel}' — ` +
                    `existing raw files would parse into different columns; copy to a new name, or pass compatibility: "none"`,
            });
        }
    }
    return findings;
}

/** `raw.fields[].name → field map`, declaration order; empty on any shape mismatch. */
function fieldsByName(schema: Record<string, unknown>): Map<string, Record<string, unknown>> {
    const out = new Map<string, Record<string, unknown>>();
    const raw = schema?.['raw'];
    if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return out;
    const fields = (raw as Record<string, unknown>)['fields'];
    if (!Array.isArray(fields)) return out;
    for (const f of fields) {
        if (f !== null && typeof f === 'object' && !Array.isArray(f)) {
            const name = (f as Record<string, unknown>)['name'];
            if (name !== null && name !== undefined && String(name).trim().length) {
                out.set(String(name).trim(), f as Record<string, unknown>);
            }
        }
    }
    return out;
}

/** The config's identity value, per type (mirrors ConfigRoutes.identityField). */
function identityOf(config: Record<string, unknown>, type: string): string {
    if (type === 'schema') {
        const raw = (config['raw'] ?? {}) as Record<string, unknown>;
        return String(raw['name'] ?? '');
    }
    return String(config['name'] ?? '');
}

/** Mirrors ConfigRoutes.deepMerge: maps merge recursively, scalars/lists replace, `null` deletes. */
function deepMergeNullDeletes(base: Record<string, unknown>, patch: Record<string, unknown>): Record<string, unknown> {
    const out: Record<string, unknown> = { ...base };
    for (const [k, v] of Object.entries(patch)) {
        if (v === null) {
            delete out[k];
        } else if (
            typeof v === 'object' &&
            !Array.isArray(v) &&
            out[k] !== null &&
            typeof out[k] === 'object' &&
            !Array.isArray(out[k])
        ) {
            out[k] = deepMergeNullDeletes(out[k] as Record<string, unknown>, v as Record<string, unknown>);
        } else {
            out[k] = v;
        }
    }
    return out;
}

/** Catalog projections of the registered drafts — merged into the demo streams/references lists. */
export function draftStreamRows(store: MockStore, space: string): Record<string, unknown>[] {
    return registered(store, space)
        .filter((r) => String(r.config['produces'] ?? '') !== 'reference')
        .map((r) => originRow(r, 'STREAM', `stream:${r.id}`));
}

export function draftReferenceRows(store: MockStore, space: string): Record<string, unknown>[] {
    return registered(store, space)
        .filter((r) => String(r.config['produces'] ?? '') === 'reference')
        .map((r) => originRow(r, 'REFERENCE_DATASET', `ref:${r.id}`));
}

function registered(store: MockStore, space: string): StoredPipelineConfig[] {
    return store.list<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL).filter((r) => r.registered);
}

function originRow(r: StoredPipelineConfig, kind: string, id: string): Record<string, unknown> {
    const collector = (r.config['collector'] ?? {}) as Record<string, unknown>;
    return {
        id,
        kind,
        label: r.id,
        description: {
            text: String(
                r.config['description'] ?? `${String(collector['connector'] ?? 'local')} collector feeding ${r.id}`,
            ),
            source: 'collector',
        },
        attrs: {
            connector: String(collector['connector'] ?? 'local'),
            connection: (collector['connection'] as string | undefined) ?? null,
            pipeline: r.id,
            active: r.config['active'] === true,
        },
    };
}

// ── the tiny frontend parsers (mirror the real preview's semantics, JS-grade) ──────────────

interface Preview {
    frontend: string;
    columns: string[];
    rowCount: number;
    rows: Record<string, unknown>[];
    rejectedRows: number;
}

interface SchemaPreviewMock {
    columns: string[];
    okCount: number;
    rejectedCount: number;
    rejectedRows: Record<string, unknown>[];
    mappedColumns?: string[];
    mappedCount?: number;
    mappedRows?: Record<string, unknown>[];
}

/** Mirrors `ComponentPreview.schema()`'s cast set: only DOUBLE/DATE/TIMESTAMP actually reject a
 *  non-blank value that fails; VARCHAR (and anything else) always passes. */
function previewSchema(config: Record<string, unknown>, sampleRows: Record<string, unknown>[]): SchemaPreviewMock {
    const raw = (config['raw'] ?? {}) as Record<string, unknown>;
    const fields = (Array.isArray(raw['fields']) ? raw['fields'] : []) as { name?: string; type?: string }[];
    const columns = [...new Set(sampleRows.flatMap((r) => Object.keys(r)))];
    if (!columns.length) throw new Error('sample rows have no columns');
    if (!fields.length) throw new Error("schema has no typed fields (expected 'raw.fields' / 'fields' / 'columns')");

    const castOk = (value: unknown, type?: string): boolean => {
        const v = value === undefined || value === null ? '' : String(value);
        if (v === '') return true; // blank/null: never rejects
        switch ((type ?? '').trim().toUpperCase()) {
            case 'DOUBLE':
                return Number.isFinite(Number(v));
            case 'DATE':
            case 'TIMESTAMP':
                return !Number.isNaN(Date.parse(v));
            default:
                return true; // VARCHAR / unknown: never rejects
        }
    };

    const ok: Record<string, unknown>[] = [];
    const rejected: Record<string, unknown>[] = [];
    for (const row of sampleRows) {
        const allCast = fields.every((f) => !f.name || !(f.name in row) || castOk(row[f.name], f.type));
        (allCast ? ok : rejected).push(row);
    }
    const out: SchemaPreviewMock = {
        columns,
        okCount: ok.length,
        rejectedCount: rejected.length,
        rejectedRows: rejected.slice(0, 200),
    };

    // B1: the mapped half, present only when the draft declares rules — same condition as the server.
    const mapping = (config['mapping'] ?? {}) as Record<string, unknown>;
    const rules = (Array.isArray(mapping['rules']) ? mapping['rules'] : []) as MappingRuleMock[];
    if (fields.length && rules.length) {
        out.mappedColumns = rules.map((r) => r.targetColumn ?? '');
        out.mappedRows = ok.slice(0, 200).map((row) => {
            const mappedRow: Record<string, unknown> = {};
            for (const r of rules) mappedRow[r.targetColumn ?? ''] = mappedValue(r, row);
            return mappedRow;
        });
        out.mappedCount = ok.length;
    }
    return out;
}

interface MappingRuleMock {
    targetColumn?: string;
    sourceExpression?: string;
    transformType?: string;
}

/**
 * One mapped cell. ⚠ **The offline mock cannot evaluate a DuckDB expression** — it has no SQL engine —
 * so only `DIRECT` (blank/omitted included, matching `TransformCompiler`'s own default) resolves, by
 * reading the source column. `EXPR`, `CONCAT_DT` and `FILENAME_DATE` yield `null`: the offline preview
 * therefore proves the mapped table's *plumbing* (columns, row count, rename projection) but NOT an
 * expression's result, which needs the real server.
 *
 * ⚠ One narrow direction where this IS leniency, and cannot be fixed here: the server compiles the rule
 * and a malformed expression fails DuckDB's binder as a 422 (an `EXPR` is emitted verbatim — the author
 * owns any explicit cast, so `QUANTITY * 2` over a VARCHAR column refuses), whereas the mock has no
 * binder and yields a null cell. Offline authoring therefore cannot tell a valid expression from an
 * unbindable one; a pane must present these cells as *not evaluated*, never as a computed value.
 *
 * <p>⚠ The source lookup falls back to a CASE-INSENSITIVE match, because DuckDB resolves an unquoted
 * identifier that way: an identity rule seeded from a schema (whose field names are upper-cased) maps a
 * parsed column named `Column0` through `COLUMN0` on the server. Found in the preview 2026-08-16 — the
 * exact-match-only version rendered "3 rows mapped" over a grid of blank cells, which reads as a broken
 * feature rather than as the mock being stricter than the engine it mirrors.
 */
function mappedValue(rule: MappingRuleMock, row: Record<string, unknown>): unknown {
    const type = (rule.transformType ?? '').trim().toUpperCase();
    if (type !== '' && type !== 'DIRECT') return null;
    const source = rule.sourceExpression ?? '';
    if (source in row) return row[source];
    const folded = Object.keys(row).find((k) => k.toLowerCase() === source.toLowerCase());
    return folded === undefined ? null : row[folded];
}

/**
 * Mirrors `SchemaSuggest.infer` + `ConfigRoutes.suggestSchema` (mock-never-more-lenient): per
 * column, the most specific type every non-blank value accepts wins — BIGINT (with the server's
 * DOUBLE=BIGINT round-trip guard: DuckDB's TRY_CAST rounds, so only integral values count) →
 * DOUBLE → TIMESTAMP (demoted to DATE when no value carries a non-midnight time) → BOOLEAN, else
 * VARCHAR. Blanks abstain; an all-blank column is VARCHAR. Same response shape as the real route:
 * a DRAFT fields list (selector = the column key) + identity mapping rules.
 */
function suggestSchema(
    sampleRows: Record<string, unknown>[],
    draft?: Record<string, unknown>,
): {
    fields: Record<string, string>[];
    mapping: { rules: Record<string, string>[] };
    drift?: SchemaDriftMock;
} {
    const columns: string[] = [];
    for (const r of sampleRows) for (const k of Object.keys(r)) if (!columns.includes(k)) columns.push(k);
    if (!columns.length) throw new Error('sample rows have no columns');

    const infer = (col: string): string => {
        const values = sampleRows
            .map((r) => r[col])
            .filter((v) => v !== null && v !== undefined && String(v).trim() !== '')
            .map((v) => String(v).trim());
        if (!values.length) return 'VARCHAR'; // nothing to vote with — unknown is not evidence
        if (values.every((v) => Number.isFinite(Number(v)) && Number.isInteger(Number(v)))) return 'BIGINT';
        if (values.every((v) => Number.isFinite(Number(v)))) return 'DOUBLE';
        if (values.every((v) => !Number.isNaN(Date.parse(v)))) {
            const timed = values.some((v) => {
                const t = /(\d{1,2}):(\d{2})(?::(\d{2}))?/.exec(v);
                return !!t && (Number(t[1]) !== 0 || Number(t[2]) !== 0 || Number(t[3] ?? 0) !== 0);
            });
            return timed ? 'TIMESTAMP' : 'DATE';
        }
        if (values.every((v) => /^(true|false|t|f|yes|no|1|0)$/i.test(v))) return 'BOOLEAN';
        return 'VARCHAR';
    };

    const fields = columns.map((c) => ({ name: c, selector: c, type: infer(c) }));
    const out: {
        fields: Record<string, string>[];
        mapping: { rules: Record<string, string>[] };
        drift?: SchemaDriftMock;
    } = {
        fields,
        mapping: {
            rules: fields.map((f) => ({
                targetColumn: f.name,
                sourceExpression: f.name,
                transformType: 'DIRECT',
            })),
        },
    };
    if (draft) out.drift = schemaDrift(draft, fields);
    return out;
}

interface SchemaDriftMock {
    drifted: boolean;
    added: { name: string; type: string }[];
    missing: { name: string; type: string }[];
    typeChanged: { name: string; declared: string; suggested: string }[];
}

/**
 * Mirrors `SchemaSuggest.drift` (B3). The join key is the draft field's **selector** (its name when
 * blank) — the selector points into the parsed sample, while `name` is an output column the author is
 * free to rename deliberately, so keying on name would report every intentional rename as drift.
 *
 * ⛔ There is no `renamed` category here either: a renamed SOURCE column is indistinguishable from one
 * removed and another added, and surfaces as a `missing` + `added` pair. Presenting that pair as a
 * likely rename is a UI affordance over the two facts, never a claim the server (or this mock) makes.
 */
function schemaDrift(draft: Record<string, unknown>, inferred: { name: string; type: string }[]): SchemaDriftMock {
    const raw = (draft['raw'] ?? {}) as Record<string, unknown>;
    const draftFields = (Array.isArray(raw['fields']) ? raw['fields'] : []) as {
        name?: string;
        selector?: string;
        type?: string;
    }[];
    const bySampleColumn = new Map(inferred.map((f) => [f.name, f]));

    const missing: { name: string; type: string }[] = [];
    const typeChanged: { name: string; declared: string; suggested: string }[] = [];
    const claimed = new Set<string>();

    for (const df of draftFields) {
        const name = (df.name ?? '').trim();
        if (!name) continue;
        const selector = (df.selector ?? '').trim();
        const key = selector || name;
        const declared = (df.type ?? '').trim();

        const match = bySampleColumn.get(key);
        if (!match) {
            missing.push({ name, type: declared });
            continue;
        }
        claimed.add(key);
        // A field with no declared type has nothing to have changed FROM.
        if (declared && declared.toUpperCase() !== match.type.toUpperCase())
            typeChanged.push({ name, declared, suggested: match.type });
    }

    const added = inferred.filter((f) => !claimed.has(f.name)).map((f) => ({ name: f.name, type: f.type }));
    return {
        drifted: added.length > 0 || missing.length > 0 || typeChanged.length > 0,
        added,
        missing,
        typeChanged,
    };
}

function previewParsing(config: Record<string, unknown>, sampleText: string): Preview {
    const parsing = (config['parsing'] ?? {}) as Record<string, unknown>;
    const delimited = (parsing['delimited'] ?? {}) as Record<string, unknown>;
    const frontend = String(parsing['frontend'] ?? 'delimited').toLowerCase();
    const proc = (config['processing'] ?? {}) as Record<string, unknown>;
    if (frontend === 'plugin' || proc['ingester']) {
        throw new Error(
            'parsing preview is not supported for the plugin frontend — run the pipeline against a real file instead',
        );
    }

    // Engine default: has_header true — except json/text_regex, whose records have no header.
    const headerless = frontend === 'json' || frontend === 'text_regex';
    const hasHeader = delimited['has_header'] === undefined ? !headerless : delimited['has_header'] === true;
    const skip = Number(delimited['skip_header_lines'] ?? 0) + (hasHeader && frontend !== 'delimited' ? 1 : 0);
    const allLines = sampleText.split(/\r?\n/).filter((l, i, a) => l.length > 0 || i < a.length - 1);
    const lines = allLines.slice(skip);

    switch (frontend) {
        case 'fixedwidth':
        case 'fixed_width': {
            const fw = (parsing['fixedwidth'] ?? {}) as Record<string, unknown>;
            const fields = (fw['fields'] ?? []) as { name?: string; start?: number; length?: number }[];
            if (!fields.length) throw new Error('fixedwidth.fields[] must be a non-empty list of {name,start,length}');
            const widest = Math.max(...fields.map((f) => Number(f.start ?? 0) + Number(f.length ?? 1)));
            const minLen = Number(fw['min_record_length'] ?? 0) || widest;
            const columns = fields.map((f, i) => String(f.name ?? `field_${i}`));
            const rows = lines
                .filter((l) => l.length >= minLen)
                .map((l) =>
                    Object.fromEntries(
                        fields.map((f, i) => [
                            columns[i],
                            l.substring(Number(f.start ?? 0), Number(f.start ?? 0) + Number(f.length ?? 1)).trim(),
                        ]),
                    ),
                );
            return {
                frontend: 'fixedwidth',
                columns,
                rowCount: rows.length,
                rows: rows.slice(0, 200),
                rejectedRows: 0,
            };
        }
        case 'json': {
            const jf = String(((parsing['json'] ?? {}) as Record<string, unknown>)['format'] ?? 'newline');
            if (jf === 'newline') {
                const parsed: Record<string, unknown>[] = [];
                let invalid = 0;
                for (const l of lines) {
                    if (!l.trim()) continue;
                    try {
                        parsed.push(JSON.parse(l) as Record<string, unknown>);
                    } catch {
                        invalid++;
                    }
                }
                const columns = [...new Set(parsed.flatMap((r) => Object.keys(r)))];
                return {
                    frontend: 'json',
                    columns,
                    rowCount: parsed.length,
                    rows: parsed.slice(0, 200),
                    rejectedRows: invalid,
                };
            }
            const doc = JSON.parse(sampleText) as unknown;
            const arr = Array.isArray(doc) ? (doc as Record<string, unknown>[]) : [doc as Record<string, unknown>];
            const columns = [...new Set(arr.flatMap((r) => Object.keys(r)))];
            return { frontend: 'json', columns, rowCount: arr.length, rows: arr.slice(0, 200), rejectedRows: 0 };
        }
        case 'text_regex': {
            const tr = (parsing['text_regex'] ?? {}) as Record<string, unknown>;
            const raw = String(tr['pattern'] ?? '');
            if (!raw) throw new Error('text_regex.pattern is required');
            const jsPattern = raw.replace(/\(\?P</g, '(?<');
            const re = new RegExp(jsPattern);
            const groups = [...raw.matchAll(/\(\?P?<([A-Za-z][A-Za-z0-9_]*)>/g)].map((g) => g[1]);
            if (!groups.length) throw new Error('text_regex.pattern must contain at least one named capture group');
            const rows = lines
                .map((l) => re.exec(l)?.groups)
                .filter((g): g is Record<string, string> => !!g)
                .map((g) => Object.fromEntries(groups.map((k) => [k, g[k] ?? null])));
            return {
                frontend: 'text_regex',
                columns: groups,
                rowCount: rows.length,
                rows: rows.slice(0, 200),
                rejectedRows: 0,
            };
        }
        default: {
            const delim = String(delimited['delimiter'] ?? ',') || ',';
            const skipDataLines = Number(delimited['skip_header_lines'] ?? 0);
            const body = allLines.slice(skipDataLines);
            const header = hasHeader ? body[0] : null;
            const dataLines = hasHeader ? body.slice(1) : body;
            const width = (header ?? dataLines[0] ?? '').split(delim).length;
            const columns = header
                ? header.split(delim).map((h) => h.trim())
                : Array.from({ length: width }, (_, i) => `column${i}`);
            let rejected = 0;
            const rows: Record<string, unknown>[] = [];
            for (const l of dataLines) {
                if (!l.length) continue;
                const cells = l.split(delim);
                if (cells.length !== columns.length) {
                    rejected++;
                    continue;
                }
                rows.push(Object.fromEntries(columns.map((c, i) => [c, cells[i]])));
            }
            return {
                frontend: 'delimited',
                columns,
                rowCount: rows.length,
                rows: rows.slice(0, 200),
                rejectedRows: rejected,
            };
        }
    }
}
