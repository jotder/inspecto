import type {
    AuthoredNode,
    AuthoredPipeline,
    PipelineDryRunResult,
    PipelineGraph,
    PipelineNode,
    PipelineNodeType,
    PipelineRunRelation,
    PipelineRunResult,
    PipelineSummary,
} from '../../api/pipelines.service';
import type { PipelineViewData, PipelineViewSummary } from '../../api/views.service';
import type { IconMap } from '../../api/icon-map.service';
import type { AttributeSpec } from '../../component-model/attribute-spec';
import NODE_ATTRIBUTE_CONTRACT from '../node-attributes.contract.json';
import STEP_TYPES_CONTRACT from '../step-types.contract.json';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest, MockResponse } from '../mock-http';
import { MockStore } from '../mock-store';
import { liftConfig, LOWERABLE, lowerGraph } from '../pipeline-editable';
import { PIPELINE_CONFIGS_COLL, type StoredPipelineConfig } from './onboarding.handler';

/**
 * The Pipelines-editor mock domain (authored-DAG CRUD, graph projections, dry-run, run-to-here,
 * icon map, provenance stubs) — the `/pipelines` half of the old `pipeline-mock` interceptor, now
 * backed by the persistent {@link MockStore} (`authored-pipeline` collection + the `config/icon-map`
 * singleton) so authored pipelines survive a reload. Behavior is otherwise a faithful port.
 */

/**
 * The processor palette — a **faithful port of the backend enum `BuiltinNodeType`**, which is what
 * `GET /pipelines/node-types` really serves (via `PipelineProjection.catalog()`). Keep it that way.
 *
 * ⚠ **This list used to be invented** (`collector.file`, `parser.dsv`, `sink.file`, …) and not one of
 * those strings existed server-side, so the editor could author pipelines the backend silently dropped:
 * `PipelineValidator` only *warns* `UNKNOWN_TYPE`, and `PipelineCompiler` groups nodes by matching
 * `type()` against this enum, so an unknown-typed node never becomes the acquisition input. Offline it
 * all looked fine — the textbook *mock more lenient than the server* failure. Corrected 2026-07-31
 * (W2/U-D). **Adding a type here without adding it to `BuiltinNodeType` re-opens exactly that hole.**
 *
 * Order, labels, descriptions, `accepts`/`emits` and `emitsNamedRoutes` all mirror the enum; the rel
 * strings are `PipelineRel` constants. Note `acquisition` vs `adapter` replaces the old
 * file/database/stream split — **which connector a source uses is carried by its Connection profile
 * (`collector.connection`), not by the node type** — and `alert` is CONTROL, not a transform.
 */
export const NODE_TYPES: PipelineNodeType[] = (
    [
        // entry / acquisition (the collector role)
        {
            type: 'acquisition',
            category: 'SOURCE',
            label: 'Collect',
            description: 'Collects files (poll/listing); the pipeline entry.',
            accepts: [],
            emits: ['data', 'gap', 'failure'],
            emitsNamedRoutes: false,
        },
        {
            type: 'adapter',
            category: 'SOURCE',
            label: 'Adapter',
            description: 'Windows a stream/push source into intermediate files (by time/count/size), then lands them.',
            accepts: [],
            emits: ['data'],
            emitsNamedRoutes: false,
        },
        // parse — a family since B6/P3a: the generic parser plus one type per format, delimited first.
        {
            type: 'parser',
            category: 'PARSE',
            label: 'Parser',
            description:
                'Reads a landed file into rows; may dispatch by schema/segment (route:*) with an unmatched branch.',
            accepts: ['data'],
            emits: ['data', 'unmatched'],
            emitsNamedRoutes: true,
        },
        {
            type: 'parser.delimited',
            category: 'PARSE',
            label: 'Delimited',
            description: 'Reads a delimited (CSV-like) landed file into rows; the delimited grammar is its config.',
            accepts: ['data'],
            emits: ['data', 'unmatched'],
            emitsNamedRoutes: true,
        },
        {
            type: 'parser.fixedwidth',
            category: 'PARSE',
            label: 'Fixed-Width',
            description: 'Reads a fixed-width landed file into rows; positional slices carved from each record.',
            accepts: ['data'],
            emits: ['data', 'unmatched'],
            emitsNamedRoutes: true,
        },
        {
            type: 'parser.asn1',
            category: 'PARSE',
            label: 'ASN.1',
            description: 'Decodes BER/DER records against an X.680 grammar and flattens them onto segment schemas.',
            accepts: ['data'],
            emits: ['data', 'unmatched'],
            emitsNamedRoutes: true,
        },
        {
            type: 'parser.json',
            category: 'PARSE',
            label: 'JSON',
            description: 'Reads an NDJSON or JSON-array landed file into rows; top-level keys become the columns.',
            accepts: ['data'],
            emits: ['data', 'unmatched'],
            emitsNamedRoutes: true,
        },
        {
            type: 'parser.text_regex',
            category: 'PARSE',
            label: 'Parser (text/regex)',
            description: 'Reads matching lines into rows via named capture groups; non-matching lines are dropped.',
            accepts: ['data'],
            emits: ['data', 'unmatched'],
            emitsNamedRoutes: true,
        },
        {
            type: 'parser.plugin',
            category: 'PARSE',
            label: 'Parser (plugin)',
            description: 'Decodes records through a deployed custom ParserPlugin, loaded via its StreamingFileIngester.',
            accepts: ['data'],
            emits: ['data', 'unmatched'],
            emitsNamedRoutes: true,
        },
        // transform family
        {
            type: 'transform.map',
            category: 'TRANSFORM',
            label: 'Map',
            description: 'Maps raw fields onto the canonical schema.',
            accepts: ['data'],
            emits: ['data'],
            emitsNamedRoutes: false,
        },
        {
            type: 'transform.filter',
            category: 'TRANSFORM',
            label: 'Filter',
            description: 'Keeps/drops rows by predicate; index-anchored CSV row-filter.',
            accepts: ['data'],
            emits: ['data', 'dropped'],
            emitsNamedRoutes: false,
        },
        {
            type: 'transform.select',
            category: 'TRANSFORM',
            label: 'Select',
            description: 'Projects a subset / reorder of columns.',
            accepts: ['data'],
            emits: ['data'],
            emitsNamedRoutes: false,
        },
        {
            type: 'transform.derive',
            category: 'TRANSFORM',
            label: 'Derive',
            description: 'Adds computed columns (SQL-expression registry).',
            accepts: ['data'],
            emits: ['data'],
            emitsNamedRoutes: false,
        },
        {
            type: 'transform.validate',
            category: 'TRANSFORM',
            label: 'Validate',
            description: 'Splits rows into valid / invalid by rule.',
            accepts: ['data'],
            emits: ['data', 'invalid'],
            emitsNamedRoutes: false,
        },
        // marker vs fingerprint are DIFFERENT subsystems — never flatten them into one "dedup"
        {
            type: 'transform.dedup.marker',
            category: 'TRANSFORM',
            label: 'Dedup (marker)',
            description: 'File-level dedup via marker files.',
            accepts: ['data'],
            emits: ['data', 'duplicate'],
            emitsNamedRoutes: false,
        },
        {
            type: 'transform.dedup',
            category: 'TRANSFORM',
            label: 'Dedup (record)',
            description: 'Record-grain dedup by business key (QUALIFY); duplicates are a counted reject stream.',
            accepts: ['data'],
            emits: ['data', 'duplicate'],
            emitsNamedRoutes: false,
        },
        {
            type: 'transform.route',
            category: 'TRANSFORM',
            label: 'Route',
            description: 'Content-based routing into operator-defined branches (case / clone).',
            accepts: ['data'],
            emits: ['data'],
            emitsNamedRoutes: true,
        },
        {
            type: 'transform.join',
            category: 'TRANSFORM',
            label: 'Join',
            description: 'Joins against a Reference Dataset by key.',
            accepts: ['data'],
            emits: ['data'],
            emitsNamedRoutes: false,
        },
        {
            type: 'transform.summarize',
            category: 'TRANSFORM',
            label: 'Summarize',
            description: 'Group-by rollup with algebraically-composable measures.',
            accepts: ['data'],
            emits: ['data'],
            emitsNamedRoutes: false,
        },
        {
            type: 'transform.split',
            category: 'TRANSFORM',
            label: 'Split',
            description: 'Explodes one row into many (UNNEST).',
            accepts: ['data'],
            emits: ['data'],
            emitsNamedRoutes: false,
        },
        {
            type: 'transform.merge',
            category: 'TRANSFORM',
            label: 'Merge',
            description: 'Joins / unions multiple inbound data edges (fan-in).',
            accepts: ['data'],
            emits: ['data'],
            emitsNamedRoutes: false,
        },
        {
            type: 'enrichment',
            category: 'TRANSFORM',
            label: 'Enrichment',
            description: 'Joins against reference data (post-commit stage-2 join).',
            accepts: ['data', 'on_commit'],
            emits: ['data', 'on_commit'],
            emitsNamedRoutes: false,
        },
        // sink family — one family, three materialisation behaviours
        {
            type: 'sink.persistent',
            category: 'SINK',
            label: 'Sink (persistent)',
            description: 'Writes the batch to a resting store — a Parquet file / DuckDB table.',
            accepts: ['data'],
            emits: ['success', 'failure', 'on_commit'],
            emitsNamedRoutes: false,
        },
        {
            type: 'sink.materialized',
            category: 'SINK',
            label: 'Sink (materialized)',
            description: 'Maintains a managed/temp table, upserted per batch — an incremental rollup / summary.',
            accepts: ['data'],
            emits: ['success', 'failure', 'on_commit'],
            emitsNamedRoutes: false,
        },
        {
            type: 'sink.view',
            category: 'SINK',
            label: 'Sink (view)',
            description: 'A non-persistent logical store; jobs / KPI / report / alert APIs bind to it by store name.',
            accepts: ['data'],
            emits: ['on_commit'],
            emitsNamedRoutes: false,
        },
        // reporting / notification — CONTROL: side-tasks with no downstream data edge
        {
            type: 'alert',
            category: 'CONTROL',
            label: 'Alert',
            description: 'Raises an alert from rule / gap / failure outcomes.',
            accepts: ['data', 'gap', 'failure'],
            emits: [],
            emitsNamedRoutes: false,
        },
        {
            type: 'gap',
            category: 'CONTROL',
            label: 'Gap detection',
            description: 'Reports sequence gaps as SEQUENCE_GAP events.',
            accepts: ['gap'],
            emits: [],
            emitsNamedRoutes: false,
        },
        {
            type: 'event',
            category: 'CONTROL',
            label: 'Event',
            description: 'Emits a notification / event.',
            accepts: ['data', 'success', 'failure', 'gap'],
            emits: [],
            emitsNamedRoutes: false,
        },
    ] as Omit<PipelineNodeType, 'lowerable'>[]
).map((t) => ({
    ...t,
    lowerable: LOWERABLE.has(t.type),
    // §3.1: the mock must publish the SAME attribute vocabulary the server does, straight from the
    // committed contract the Java side is byte-compared against — otherwise the offline preview would
    // drive its node forms from a different table than production, which is exactly the "a mock must
    // never be more lenient than the server" failure. `?? []` matches the server: a type with no schema
    // publishes an empty list, not an absent key.
    attributes: (NODE_ATTRIBUTE_CONTRACT as Record<string, AttributeSpec[]>)[t.type] ?? [],
}));

const CATEGORY_OF = new Map(NODE_TYPES.map((t) => [t.type, t.category]));

/** MockStore collection for authored pipelines (distinct from the `component:*` kind collections). */
export const PIPELINES_COLL = 'authored-pipeline';

const FLOWS = /\/pipelines$/;
const NODE_TYPES_RE = /\/pipelines\/node-types$/;
const STEP_TYPES_RE = /\/pipelines\/step-types$/;
const COMBINED = /\/pipelines\/combined$/;
const AUTHORED = /\/pipelines\/authored$/;
const AUTHORED_RAW = /\/pipelines\/authored\/([^/]+)\/raw$/;
const DRY_RUN = /\/pipelines\/authored\/([^/]+)\/dry-run$/;
const RUN_TO = /\/pipelines\/authored\/([^/]+)\/run$/;
const AUTHORED_ID = /\/pipelines\/authored\/([^/]+)$/;
const GRAPH_RAW = /\/pipelines\/([^/]+)\/graph\/raw$/;
const PIPELINE_GRAPH = /\/pipelines\/([^/]+)\/graph$/;
const SAVE_AS_TEMPLATE = /\/pipelines\/([^/]+)\/save-as-template$/;
const LABEL = /\/pipelines\/([^/]+)\/label$/;
const RENAME = /\/pipelines\/([^/]+)\/rename$/;
const PROV_BATCHES = /\/provenance\/batches$/;
const PROV = /\/provenance$/;
const ICON_MAP_RE = /\/config\/icon-map$/;
const VIEWS = /\/views$/;
const VIEW_DATA = /\/views\/([^/]+)\/data$/;
const VIEW_NAME = /\/views\/([^/]+)$/;

export function pipelinesHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockPipelines) return undefined;
        const { method, url, space } = req;
        // Grandfathered *_flow.toon store (read-only now). The CANONICAL pipelines the editor edits
        // live in PIPELINE_CONFIGS_COLL (shared with onboarding — W5/U-A: one model).
        const all = (): AuthoredPipeline[] => store.list<AuthoredPipeline>(space, PIPELINES_COLL);
        const configs = (): StoredPipelineConfig[] => store.list<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL);
        const graphOfName = (name: string): AuthoredPipeline | undefined => {
            const rec = store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, name);
            return rec ? liftConfig(rec.config) : undefined;
        };
        let m: string[] | null;

        if (method === 'GET' && NODE_TYPES_RE.test(url)) return json(NODE_TYPES);
        // The recipe-verb palette — served straight from the committed contract JSON, which
        // StepTypesContractTest pins byte-wise to the real PipelineProjection.stepCatalog(), so the
        // offline preview cannot drift from what the server publishes.
        if (method === 'GET' && STEP_TYPES_RE.test(url)) return json(STEP_TYPES_CONTRACT);
        if (method === 'GET' && COMBINED.test(url)) return json(combined(configs().map((r) => liftConfig(r.config))));
        // GET /pipelines/authored* — grandfathered flow reads only (writes retired with W5).
        if (method === 'GET' && AUTHORED.test(url)) return json(all().map(summaryOf));
        if (method === 'GET' && (m = match(url, AUTHORED_RAW))) {
            return json(store.get<AuthoredPipeline>(space, PIPELINES_COLL, m[1]) ?? null);
        }
        if (method === 'POST' && (m = match(url, DRY_RUN))) {
            const body = (req.body ?? {}) as {
                sampleRows?: Record<string, unknown>[];
                pipeline?: AuthoredPipeline;
            };
            // A candidate body previews that DRAFT graph and never consults the stored one — including
            // for an id with no stored pipeline at all (the server skips the lookup entirely, so no 404).
            // W5: otherwise the editor dry-runs registered pipelines — fall back to the lifted config.
            const f = body.pipeline ?? store.get<AuthoredPipeline>(space, PIPELINES_COLL, m[1]) ?? graphOfName(m[1]);
            return json(dryRun(f, body.sampleRows));
        }
        if (method === 'POST' && (m = match(url, RUN_TO))) {
            const files = (req.body as { files?: string[] })?.files ?? [];
            const f = store.get<AuthoredPipeline>(space, PIPELINES_COLL, m[1]) ?? graphOfName(m[1]);
            return json(runToNode(m[1], f, req.params['to'] ?? '', files));
        }
        // GET /pipelines — the registered canonical pipelines (what the editor lists).
        if (method === 'GET' && FLOWS.test(url)) return json(configs().map(configSummary));
        // W5: the editable round-trip over the canonical *_pipeline.toon.
        if (method === 'GET' && (m = match(url, GRAPH_RAW))) {
            const g = graphOfName(m[1]);
            return g ? json(g) : error(404, `no pipeline named '${m[1]}'`);
        }
        if (method === 'POST' && (m = match(url, SAVE_AS_TEMPLATE))) {
            return saveAsTemplate(store, space, m[1], req.body as { id?: string; name?: string });
        }
        if (method === 'POST' && (m = match(url, LABEL))) {
            return relabel(store, space, m[1], req.body as { name?: string });
        }
        if (method === 'POST' && (m = match(url, RENAME))) {
            return rename(store, space, m[1], req.body as { newId?: string; newName?: string });
        }
        if (method === 'PUT' && (m = match(url, PIPELINE_GRAPH))) {
            return saveGraph(store, space, m[1], req.body as AuthoredPipeline);
        }
        if (method === 'GET' && (m = match(url, PIPELINE_GRAPH))) {
            return json(graphOf(graphOfName(m[1])));
        }
        // POST/PUT /pipelines/authored* retired with W5 — 405 where the path still serves reads.
        if (method === 'POST' && AUTHORED.test(url))
            return error(405, 'authored-flow writes retired (W5) — edit the pipeline graph');
        if (method === 'PUT' && match(url, AUTHORED_ID))
            return error(405, 'authored-flow writes retired (W5) — PUT /pipelines/{name}/graph');
        if (method === 'DELETE' && (m = match(url, AUTHORED_ID))) {
            // Referential integrity (R2) — e.g. a job triggering on this pipeline blocks the delete.
            const refs = store.referencesTo(space, PIPELINES_COLL, m[1]);
            if (refs.length) {
                const by = refs.map((r) => `${r.collection.replace('component:', '')}/${r.id}`).join(', ');
                return error(409, `pipeline "${m[1]}" is still referenced by: ${by}`);
            }
            store.delete(space, PIPELINES_COLL, m[1]);
            return json({ deleted: true });
        }

        if (method === 'GET' && ICON_MAP_RE.test(url)) {
            return json({ ...(store.get<IconMap>(space, 'config', 'icon-map') ?? {}) });
        }
        if (method === 'PUT' && ICON_MAP_RE.test(url)) {
            return json({ ...store.put(space, 'config', 'icon-map', req.body as IconMap) });
        }

        if (method === 'GET' && (PROV_BATCHES.test(url) || PROV.test(url))) return json([]);

        if (method === 'GET' && VIEWS.test(url))
            return json(
                all()
                    .flatMap((f) => viewsOf(f))
                    .map(viewSummaryOf),
            );
        if (method === 'GET' && (m = match(url, VIEW_DATA)))
            return viewData(all(), m[1], Number(req.params['limit']) || 1000);
        if (method === 'GET' && (m = match(url, VIEW_NAME))) {
            const view = all()
                .flatMap((f) => viewsOf(f))
                .find((v) => v.node.name === m![1]);
            return view ? json(viewSummaryOf(view)) : error(404, `no view '${m[1]}'`);
        }

        return undefined;
    };
}

/**
 * PUT /pipelines/{name}/graph — lower the graph onto the existing canonical config (or a fresh
 * scaffold), refusing unrepresentable topologies with named codes exactly as the backend does. The
 * strictness here is load-bearing: a mock that accepted a MULTI_SINK graph would green-light a
 * preview the real server 422s.
 */
function saveGraph(store: MockStore, space: string, name: string, body: AuthoredPipeline): MockResponse {
    const g: AuthoredPipeline = { ...body, name };
    const existing = store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, name);
    const strict = g.active || !existing;
    const result = lowerGraph(g, existing?.config ?? {}, strict);
    if ('refusals' in result) {
        return error(422, `graph cannot be lowered: ${result.refusals[0].code}`, {
            written: false,
            refusals: result.refusals,
        });
    }
    const path = name.endsWith('_pipeline') ? `${name}.toon` : `${name}_pipeline.toon`;
    store.put(space, PIPELINE_CONFIGS_COLL, name, {
        id: name,
        path,
        config: result.config,
        registered: existing?.registered ?? true,
    } satisfies StoredPipelineConfig);
    return json({ written: true, path, name, findings: [] });
}

/** Every `sink.view` node across a flow, paired with its owning flow name. */
function viewsOf(f: AuthoredPipeline): { node: AuthoredNode; flow: string }[] {
    return f.nodes.filter((n) => n.type === 'sink.view').map((node) => ({ node, flow: f.name }));
}

function viewSummaryOf(v: { node: AuthoredNode; flow: string }): PipelineViewSummary {
    return {
        store: v.node.name || v.node.id,
        flow: v.flow,
        source_store: [],
        has_derived_sql: true,
        defined_at: new Date().toISOString(),
    };
}

/** A bounded, pure-mock sample for a view's data preview — no real SQL execution. */
function viewData(flows: AuthoredPipeline[], name: string, limit: number): MockResponse {
    const v = flows.flatMap((f) => viewsOf(f)).find((v) => v.node.name === name);
    if (!v) return error(404, `no view '${name}'`);
    const rows = [
        { id: 1001, msisdn: '8801700000001', start_time: '2026-06-24 09:00:00', duration_s: 42 },
        { id: 1002, msisdn: '8801700000002', start_time: '2026-06-24 09:01:30', duration_s: 17 },
    ].slice(0, limit);
    const data: PipelineViewData = {
        view: name,
        columns: rows.length ? Object.keys(rows[0]) : [],
        rowCount: rows.length,
        capped: false,
        rows,
    };
    return json(data);
}

/**
 * The `GET /pipelines` row for a stored config. `template`/`displayName` come from the CONFIG, not the
 * lifted graph — matching `PipelineRoutes.pipelineSummaries`, which reads them off `PipelineConfig` and emits
 * each only when set (so an ordinary pipeline's row is byte-identical to before).
 */
function configSummary(r: StoredPipelineConfig): PipelineSummary {
    const cfg = r.config;
    const s = summaryOf(liftConfig(cfg));
    // ⚠ `liftConfig` puts the raw `name:` on the graph, but the server's PipelineLift puts the derived
    // IDENTITY there — and `name` is what every other route is keyed by. Left as the display name, a
    // relabelled pipeline would list under its new label and then 404 on `GET /pipelines/{name}/graph/raw`.
    s.name = r.id;
    if (cfg['template'] === true || cfg['template'] === 'true') s.template = true;
    const display = typeof cfg['name'] === 'string' ? (cfg['name'] as string) : '';
    if (display && display !== s.name) s.displayName = display;
    return s;
}

/** The server's identity rule: lowercase, spaces → underscores (`PipelineConfigParser`). */
function derivedId(name: string): string {
    return name.trim().toLowerCase().replace(/ /g, '_');
}

/**
 * `POST /pipelines/{name}/save-as-template` — mirrors `PipelineRoutes.saveAsTemplate`.
 *
 * ⚠ Keep the gates AND the neutralising in lockstep with the server. If this accepted an id the backend
 * refuses, or left a `dirs` entry pointing at the source, the offline preview would greenlight exactly the
 * collision the feature exists to prevent.
 */
function saveAsTemplate(
    store: MockStore,
    space: string,
    source: string,
    body: { id?: string; name?: string },
): MockResponse {
    const src = store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, source);
    if (!src) return error(404, `no pipeline named '${source}'`);
    const raw = (body?.id ?? '').trim();
    if (!raw) return error(400, "body must include 'id' (the new template's pipeline id)");
    const id = raw.toLowerCase();
    if (!/^[a-z0-9][a-z0-9_]*$/.test(id))
        return error(422, `id '${id}' must match [a-z0-9][a-z0-9_]* (lowercase letters, digits and underscores)`);
    if (store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, id))
        return error(409, `pipeline id '${id}' is already registered`);

    const cfg = { ...(src.config as Record<string, unknown>) };
    const sandbox = `templates/${id}`;
    const notes: string[] = [];

    cfg['name'] = (body.name ?? '').trim() || id;
    cfg['id'] = id;
    cfg['template'] = true;
    cfg['active'] = false;
    cfg['stream'] = id;

    // Every dir moves into the sandbox — the well-known leaf names, then anything else by its own key.
    const leaf: Record<string, string> = { poll: 'inbox', status_dir: 'status', log_dir: 'logs' };
    const dirs: Record<string, unknown> = {};
    const srcDirs = (src.config['dirs'] ?? {}) as Record<string, unknown>;
    for (const k of Object.keys(srcDirs)) {
        dirs[k] = k === 'status_file' ? `${sandbox}/status/${id}_status.csv` : `${sandbox}/${leaf[k] ?? k}`;
    }
    if (!dirs['poll']) dirs['poll'] = `${sandbox}/inbox`;
    if (!dirs['database']) dirs['database'] = `${sandbox}/database`;
    cfg['dirs'] = dirs;

    // The collector id is the acquisition ledger's dedup key; `source:` is the legacy spelling.
    const colKey = 'collector' in src.config || !('source' in src.config) ? 'collector' : 'source';
    cfg[colKey] = { ...((src.config[colKey] ?? {}) as Record<string, unknown>), id };

    const out = src.config['output'] as Record<string, unknown> | undefined;
    if (out) {
        const copy = { ...out };
        const lake = copy['ducklake'] as Record<string, unknown> | undefined;
        if (lake && 'data_path' in lake) copy['ducklake'] = { ...lake, data_path: `${sandbox}/ducklake` };
        cfg['output'] = copy;
    }

    const processing = src.config['processing'] as Record<string, unknown> | undefined;
    const schemaRef = processing?.['schema_file'];
    if (processing && typeof schemaRef === 'string' && schemaRef.trim()) {
        cfg['processing'] = { ...processing, schema_file: `${id}_schema.toon` };
        notes.push(`copied the schema to ${id}_schema.toon`);
    }

    store.put(space, PIPELINE_CONFIGS_COLL, id, {
        id,
        path: `${id}_pipeline.toon`,
        config: cfg,
        registered: true,
    });
    return json({
        written: true,
        path: `${id}_pipeline.toon`,
        id,
        source,
        template: true,
        notes,
        findings: [],
    });
}

/** `POST /pipelines/{name}/label` — mirrors `PipelineRoutes.relabel`: stamp `id`, then set `name`. */
function relabel(store: MockStore, space: string, name: string, body: { name?: string }): MockResponse {
    const rec = store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, name);
    if (!rec) return error(404, `no pipeline named '${name}'`);
    const label = (body?.name ?? '').trim();
    if (!label) return error(400, "body must include 'name' (the new display name)");

    const declared = rec.config['id'];
    const stampedId = !(typeof declared === 'string' && declared.trim());
    const id = stampedId ? derivedId(String(rec.config['name'] ?? name)) : String(declared).trim();
    // The record key and file name are the IDENTITY — a relabel must not move either.
    store.put(space, PIPELINE_CONFIGS_COLL, name, {
        ...rec,
        config: { ...rec.config, name: label, id },
    });
    return json({ written: true, path: rec.path, id, name: label, stampedId, findings: [] });
}

/**
 * `POST /pipelines/{name}/rename` — mirrors `PipelineRoutes.rename`'s gate order and response shape
 * (active 409, id-shape 422, id-taken 409, then move). The mock has no ledger/audit-CSV/DuckDB-mirror
 * models, so `ledgerRowsMoved`/`auditFilesRenamed`/`dependentsRewritten` are always 0 — real counts,
 * not simulated ones, so the preview never claims work it didn't do.
 */
function rename(
    store: MockStore,
    space: string,
    source: string,
    body: { newId?: string; newName?: string },
): MockResponse {
    const src = store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, source);
    if (!src) return error(404, `no pipeline named '${source}'`);
    const raw = (body?.newId ?? '').trim();
    if (!raw) return error(400, "body must include 'newId'");
    const newId = raw.toLowerCase();
    if (!/^[a-z0-9][a-z0-9_]*$/.test(newId))
        return error(422, `newId '${newId}' must match [a-z0-9][a-z0-9_]* (lowercase letters, digits and underscores)`);
    if (src.config['active'] === true || src.config['active'] === 'true')
        return error(409, `pipeline '${source}' is active; deactivate (active: false) before renaming`);
    if (store.get<StoredPipelineConfig>(space, PIPELINE_CONFIGS_COLL, newId))
        return error(409, `pipeline id '${newId}' is already registered`);

    const oldId = String(src.config['id'] ?? source);
    const newName = (body.newName ?? '').trim() || String(src.config['name'] ?? oldId);
    const cfg = { ...(src.config as Record<string, unknown>), id: newId, name: newName };

    store.delete(space, PIPELINE_CONFIGS_COLL, source);
    store.put(space, PIPELINE_CONFIGS_COLL, newId, {
        id: newId,
        path: `${newId}_pipeline.toon`,
        config: cfg,
        registered: true,
    });
    return json({
        written: true,
        oldId,
        id: newId,
        name: newName,
        path: `${newId}_pipeline.toon`,
        ledgerRowsMoved: 0,
        auditFilesRenamed: 0,
        dependentsRewritten: 0,
        findings: [],
        journal: [],
    });
}

function summaryOf(f: AuthoredPipeline): PipelineSummary {
    return {
        name: f.name,
        active: f.active,
        nodeCount: f.nodes.length,
        edgeCount: f.edges.length,
        produces: [],
        consumes: [],
    };
}

/** rel → edge styling kind: terminal/keep flows are data; failure/unmatched/dropped are control. */
function edgeKind(rel: string): 'data' | 'control' | 'route' {
    if (rel.startsWith('route:')) return 'route';
    if (['failure', 'unmatched', 'dropped', 'gap', 'invalid'].includes(rel)) return 'control';
    return 'data';
}

function graphOf(f: AuthoredPipeline | undefined): PipelineGraph | null {
    if (!f) return null;
    const nodes: PipelineNode[] = f.nodes.map((n) => ({
        id: n.id,
        type: n.type,
        category: CATEGORY_OF.get(n.type) ?? 'TRANSFORM',
        label: n.name || n.type,
        name: n.name,
        description: n.description,
        use: n.use,
    }));
    return {
        name: f.name,
        active: f.active,
        nodes,
        edges: f.edges.map((e) => ({ from: e.from, to: e.to, rel: e.rel, kind: edgeKind(e.rel) })),
        produces: [],
        consumes: [],
    };
}

/** Build the combined topology: every pipeline's nodes + edges, namespaced `<pipeline>/<node>`. */
function combined(flows: AuthoredPipeline[]): unknown {
    const nodes = flows.flatMap((f) =>
        f.nodes.map((n) => ({
            id: `${f.name}/${n.id}`,
            type: n.type,
            category: CATEGORY_OF.get(n.type) ?? 'TRANSFORM',
            label: n.name || n.id,
            name: n.name,
            use: n.use,
            flow: f.name,
        })),
    );
    const edges = flows.flatMap((f) =>
        f.edges.map((e) => ({
            from: `${f.name}/${e.from}`,
            to: `${f.name}/${e.to}`,
            rel: e.rel,
            kind: edgeKind(e.rel),
            flow: f.name,
        })),
    );
    return { flows: flows.map((f) => ({ name: f.name, active: f.active })), nodes, edges, links: [] };
}

/** The canned sample used when the caller sent none — the shape the editor's own dry-run panel seeds. */
const DRY_RUN_SAMPLE: Record<string, unknown>[] = [
    { id: 1001, msisdn: '8801700000001', start_time: '2026-06-24 09:00:00', duration_s: 42 },
    { id: 1002, msisdn: '8801700000002', start_time: '2026-06-24 09:01:30', duration_s: 17 },
];

/**
 * One mapping rule as the map node carries it — inline `rules`, or a lifted config's legacy
 * `schema.mapping.rules`. Both shapes reach the server ({@code RowShaper.mappingSchemaOf}).
 */
interface MapRule {
    targetColumn?: string;
    sourceExpression?: string;
    transformType?: string;
}

function mapRulesOf(node: AuthoredNode): MapRule[] {
    const cfg = node.config ?? {};
    const inline = cfg['rules'];
    if (Array.isArray(inline)) return inline as MapRule[];
    const schema = cfg['schema'] as { mapping?: { rules?: unknown } } | undefined;
    const nested = schema?.mapping?.rules;
    return Array.isArray(nested) ? (nested as MapRule[]) : [];
}

/**
 * Project rows through one node. Only `transform.map` reshapes — every other type passes rows through,
 * which is the honest limit of a mock: the server compiles each rule to DuckDB SQL and runs it.
 *
 * ⚠ **EXPR rules evaluate to `null` here, never to a made-up value.** An EXPR rule's source *is* a DuckDB
 * scalar expression and there is no SQL engine on this path ({@link MockHandler} is synchronous, so the
 * data-table's lazy AlaSQL is not reachable). A plausible-looking fabricated value would be worse than a
 * blank: it invites trusting an offline preview that never evaluated anything. DIRECT rules — a plain
 * column reference — are applied exactly, so a rule-set change still shows a real before/after difference.
 */
function projectThrough(node: AuthoredNode, rows: Record<string, unknown>[]): Record<string, unknown>[] {
    if (node.type !== 'transform.map') return rows;
    const rules = mapRulesOf(node).filter((r) => (r.targetColumn ?? '').trim().length);
    if (!rules.length) return rows;
    return rows.map((row) =>
        Object.fromEntries(
            rules.map((rule) => [
                rule.targetColumn as string,
                (rule.transformType ?? '').trim().toUpperCase() === 'EXPR'
                    ? null
                    : (row[(rule.sourceExpression ?? '').trim()] ?? null),
            ]),
        ),
    );
}

/**
 * Walk the sample through the graph in declaration order, reporting each non-sink node's output and
 * each sink's input. `rel` is **`data`**, matching the server's `PipelineRel.DATA` — this said `success`
 * until 2026-08-06, a name no dry-run response has ever carried.
 */
function dryRun(f: AuthoredPipeline | undefined, sampleRows?: Record<string, unknown>[]): PipelineDryRunResult {
    const seed = sampleRows?.length ? sampleRows : DRY_RUN_SAMPLE;
    const seedNode = f?.nodes.find((n) => n.type.startsWith('collector'))?.id ?? f?.nodes[0]?.id ?? '';
    const nodes: PipelineDryRunResult['nodes'] = [];
    const sinks: PipelineDryRunResult['sinks'] = [];
    let rows = seed;
    for (const n of f?.nodes ?? []) {
        if (n.type.startsWith('sink')) {
            sinks.push({ node: n.id, store: n.name || n.id, rowCount: rows.length, rows });
            continue;
        }
        rows = projectThrough(n, rows);
        nodes.push({ node: n.id, type: n.type, relations: [{ rel: 'data', rowCount: rows.length, rows }] });
    }
    return { seedNode, nodes, sinks, warnings: dryRunWarnings(seedNode, nodes, sinks) };
}

/**
 * Mirror of the server's DRYRUN-2 rule (`PipelineDryRun.warningsFor`): a run that succeeds yet reports
 * nothing says so, rather than answering an empty 200 that reads as success. Kept in the same shape and
 * wording family as the server so the mock does not teach the UI a contract the real API lacks.
 */
function dryRunWarnings(
    seedNode: string,
    nodes: PipelineDryRunResult['nodes'],
    sinks: PipelineDryRunResult['sinks'],
): string[] {
    if (!nodes.length && !sinks.length)
        return [
            `the sample reached no node past the seed '${seedNode}' — nothing downstream consumed it, ` +
                `so this run exercised nothing`,
        ];
    if (sinks.length && sinks.every((s) => s.rowCount === 0))
        return [
            'no sink received any rows — the sample was filtered or joined away before reaching an ' +
                'output, so this run cannot tell you the flow writes what you expect',
        ];
    return [];
}

/** The nodes from the seed source down to (and including) `toNode`, in declaration order — the run subgraph. */
function subgraphTo(f: AuthoredPipeline | undefined, toNode: string): AuthoredNode[] {
    if (!f) return [];
    const incoming = new Map<string, string[]>();
    for (const e of f.edges) {
        const list = incoming.get(e.to) ?? [];
        list.push(e.from);
        incoming.set(e.to, list);
    }
    const keep = new Set<string>();
    const stack = [toNode];
    while (stack.length) {
        const cur = stack.pop()!;
        if (keep.has(cur)) continue;
        keep.add(cur);
        for (const p of incoming.get(cur) ?? []) stack.push(p);
    }
    return f.nodes.filter((n) => keep.has(n.id));
}

/**
 * Run-to-here: walk seed→toNode and emit per-relation counts + a bounded sample, plus the scratch Parquet
 * the run "landed". A parser splits success/unmatched; a transform splits kept/dropped — the matched/rejected
 * feedback the grammar/rules loop needs. Pure mock data; no real parse.
 */
function runToNode(name: string, f: AuthoredPipeline | undefined, toNode: string, files: string[]): PipelineRunResult {
    const matched = [
        { id: 1001, msisdn: '8801700000001', start_time: '2026-06-24 09:00:00', duration_s: 42 },
        { id: 1002, msisdn: '8801700000002', start_time: '2026-06-24 09:01:30', duration_s: 17 },
        { id: 1003, msisdn: '8801700000003', start_time: '2026-06-24 09:03:11', duration_s: 8 },
    ];
    const path = subgraphTo(f, toNode);
    const relations: PipelineRunRelation[] = [];
    for (const n of path) {
        const cat = CATEGORY_OF.get(n.type);
        if (cat === 'SOURCE') {
            const rows = (files.length ? files : ['(built-in sample)']).map((p, i) => ({
                file: p,
                bytes: 10240 + i * 512,
            }));
            relations.push({ node: n.id, rel: 'success', rowCount: rows.length, rows });
        } else if (cat === 'PARSE') {
            relations.push({ node: n.id, rel: 'success', rowCount: matched.length, rows: matched });
            relations.push({
                node: n.id,
                rel: 'unmatched',
                rowCount: 1,
                rows: [{ line: 7, raw: '##trailer,checksum,0xdeadbeef' }],
            });
        } else if (cat === 'TRANSFORM') {
            relations.push({ node: n.id, rel: 'kept', rowCount: 2, rows: matched.slice(0, 2) });
            relations.push({ node: n.id, rel: 'dropped', rowCount: 1, rows: matched.slice(2) });
        }
    }
    const toNodeObj = f?.nodes.find((n) => n.id === toNode);
    // The landed Parquet reflects the target node's primary output (success/kept), not its reject branch.
    const atTarget = relations.filter((r) => r.node === toNode);
    const primary = atTarget.find((r) => r.rel === 'success' || r.rel === 'kept') ?? atTarget[atTarget.length - 1];
    const output = {
        store: toNodeObj?.name || toNode,
        format: 'PARQUET',
        path: `data/_scratch/${name}/${toNode}/part-0001.parquet`,
        rowCount: primary?.rowCount ?? 0,
    };
    // `subgraphTo` truncates to the ancestor closure of `toNode` — since 5b (2026-08-14) the server bounds
    // its walk the same way (`PipelineExecutor.ancestorsOf`), so mock and server now agree about which nodes
    // a run-to-here covers. The "ran the whole graph anyway" warning both used to carry is gone from both.
    const warnings = files.length ? [] : ['No files selected — ran over a bounded built-in sample.'];
    return {
        seedNode: path[0]?.id ?? '',
        toNode,
        files,
        relations,
        output,
        warnings,
    };
}
