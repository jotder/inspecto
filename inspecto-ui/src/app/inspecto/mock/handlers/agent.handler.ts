import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { SIGNALS_COLL } from '../signals';
import type { Signal } from '../../signal/signal';
import { PIPELINES_COLL } from './pipelines.handler';

/**
 * AGT-6a A1 — offline mock for `POST /agent/tools/{name}`, the deterministic single-tool dispatch the
 * inline authoring surface runs on. The real intelligence module is backend-only (503 in mock mode), so
 * without this the surface cannot be exercised offline at all.
 *
 * It reproduces the route's GATES as well as its happy paths, because those are what the surface's
 * degrade behaviour is built on: unknown tool → 404, mutating tool → 403 (the draft-only invariant),
 * missing required args → 422, and a draft carrying findings → 200 (findings are the payload, not an
 * error). Result shapes mirror the real tools in `InspectoTools` — if those change, change these too.
 *
 * Gated on `mockOps` so a real backend takes over when connected.
 */

const TOOLS = /\/agent\/tools\/([^/?]+)$/;

/** Stand-in for the real belt's mutating act tools, which the route refuses with 403. */
const MUTATING = new Set([
    'component_apply',
    'component_rollback',
    'job_run',
    'pipeline_rerun',
    'alert_ack',
    'schedule_apply',
    'runbook_operator',
]);

/**
 * The canonical terms the adopting panes declare (AGT-6a A4), keyed lowercase. Copied verbatim from
 * `docs/GLOSSARY.md`, which the real `glossary_lookup` parses at runtime — a paraphrase here would put a
 * second, drifting definition of the binding vocabulary in the codebase. It is a SUBSET (the real tool
 * parses the whole file), so keep it covering what the adopting panes declare — a term missing here
 * answers 422 and falls through to `docs_search`, which offline has nothing real to cite.
 */
const GLOSSARY: Record<string, string> = {
    'alert': 'A fired instance of an Alert Rule (severity: info / warning / critical).',
    'alert rule': 'Watches an observability Metric against a threshold and fires an Alert when crossed.',
    'batch': 'A set of one or more files ingested and processed together as one unit of work.',
    'case': 'A group of related Incidents managed as one larger investigation with a shared resolution.',
    'catalog': 'The library/index of all Schemas (and Datasets) in a Space, with version history and usage.',
    'collector': 'A configured collection task bound to one Connection: what to collect (paths/queries), how often, and where it lands.',
    'connection': 'Named endpoint + credentials for reaching a remote system (SFTP/FTP/FTPS, a database, cloud storage).',
    'dataset': 'The umbrella for any queryable relation the BI layer can bind to: Table | Derived Table | Reference Dataset | View | Matrix.',
    'decision rule': 'A business-logic / routing rule that transforms or routes records.',
    'derived table': 'A materialized Table produced by a Transform or cube/rollup.',
    'diagnosis': 'An AI-assisted root-cause analysis of a failing Run or Collector that produces an Incident with a suggested fix.',
    'disposition': 'The decided outcome a Case resolves with (built-in ladder: confirmed · …).',
    'executable': 'The abstraction for anything the Scheduler can start and that produces a Run. It is either a Pipeline or a Job.',
    'expectation': 'A data-quality rule that validates records against a Schema (non-null, range, regex, …).',
    'findings': "A Case's resolution artifact (the loose, business counterpart of the Incident postmortem).",
    'incident': 'A tracked operational problem. Raised automatically by an Alert or a Diagnosis, or by hand.',
    'job': 'An atomic, Quartz-style Executable that can do anything. A Job may also be embedded as a Step.',
    'measure': 'A BI aggregation (SUM, AVG, COUNT, …) over a Dataset.',
    'notification': 'Delivery of a Signal to a channel (email, webhook); a consumer of the ledger.',
    'pipeline': 'A named, authored DAG of Steps that turns raw source files into clean, partitioned Tables.',
    'reference': 'A named external dimension data origin, the slow-changing counterpart to a Stream.',
    'run': 'One execution of an Executable.',
    'scheduler': 'The Operations engine that owns Triggers and starts Executables (Pipelines or Jobs).',
    'step': 'One node in a Pipeline. A Step is a Parser, Transform, Enrichment, or Sink — or an embedded Job.',
    'stream': 'A named external event / fact data origin as seen in the Catalog.',
    'table': 'A Hive-style root directory of Parquet files, partitioned by date / partition key.',
    'tag': 'A user-created label attached to an Incident or Case for cross-cutting grouping.',
    'tag rule': 'A saved search that applies a Tag (the Gmail-filter metaphor): it auto-tags newly arriving items.',
    'trigger': 'The start condition of a run: cron | event | manual | on-pipeline. Owned by the Scheduler.',
    'view': 'A virtual (logical) query over a Table, Derived Table, or View. No storage of its own.',
    'widget': "A Visualization Type + Config + a binding to a Dataset's resultset metadata — the configured, renderable instance.",
};

function argsOf(req: MockRequest): Record<string, unknown> {
    const body = req.body as { args?: unknown } | null;
    const args = body?.args;
    return typeof args === 'object' && args !== null ? (args as Record<string, unknown>) : {};
}

function text(args: Record<string, unknown>, key: string): string {
    const v = args[key];
    return typeof v === 'string' ? v.trim() : '';
}

function minutes(args: Record<string, unknown>, key: string, fallback: number): number {
    const v = args[key];
    return typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : fallback;
}

function signalsOf(store: MockStore, space: string): Signal[] {
    return store.list<Signal>(space, SIGNALS_COLL);
}

/**
 * A pipeline's state as `status_get` reports it, derived from the ledger the mock actually has:
 * `committedBatches` counts real `BATCH_COMMITTED` signals. `paused` is always false — the mock
 * pipeline record carries no paused flag, and claiming one would be the invention this whole tool
 * exists to avoid.
 */
function pipelineStatus(store: MockStore, space: string, name: string): { name: string; paused: boolean; committedBatches: number } {
    const committedBatches = signalsOf(store, space).filter(
        (s) => s.type === 'BATCH_COMMITTED' && s.source?.id === name,
    ).length;
    return { name, paused: false, committedBatches };
}

export function agentHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockOps) return undefined;
        if (req.method !== 'POST') return undefined;
        const space = req.space;
        const m = match(req.url, TOOLS);
        if (!m) return undefined;

        const tool = m[1]; // group 0 is the full match; `match` already decoded the groups
        // The draft-only invariant, mirrored offline so the surface's 403 path is exercisable.
        if (MUTATING.has(tool)) {
            return error(403, `tool '${tool}' is mutating and is not invocable directly`);
        }
        const args = argsOf(req);

        switch (tool) {
            case 'suggest_expectations': {
                const table = text(args, 'table');
                const column = text(args, 'column');
                if (!table) return error(422, 'table is required');
                if (!column) return error(422, 'column is required');
                // Deterministic "profile" — the real tool runs SQL; the shape is what matters here.
                const rows = 12_480;
                return json({
                    table,
                    column,
                    profile: { rows, nulls: 0, nullFraction: 0, distinct: 8134, numeric: true, min: '0.0', max: '412.75' },
                    suggestions: [
                        {
                            name: `${table}_${column}_not_null`,
                            description: `Auto-suggested from profiling: ${column} was never null across ${rows} rows`,
                            targetType: 'pipeline',
                            target: table,
                            column,
                            kind: 'non_null',
                            severity: 'MAJOR',
                            enabled: true,
                        },
                        {
                            name: `${table}_${column}_range`,
                            description: 'Auto-suggested from profiling: observed numeric bounds over 12480 values',
                            targetType: 'pipeline',
                            target: table,
                            column,
                            kind: 'range',
                            min: '0.0',
                            max: '412.75',
                            severity: 'MAJOR',
                            enabled: true,
                        },
                    ],
                });
            }
            case 'query_author': {
                const dataset = text(args, 'dataset');
                if (!dataset) return error(422, 'dataset is required');
                const when = args['when'];
                const hasFilter = typeof when === 'object' && when !== null && Object.keys(when).length > 0;
                const name = text(args, 'name');
                return json({
                    kind: 'query',
                    id: name || null,
                    clean: true,
                    findings: [],
                    draft: {
                        type: 'sql',
                        // The real tool renders trusted SQL server-side — the model never writes SQL text.
                        text: `SELECT * FROM (SELECT * FROM ${dataset}) AS __q${hasFilter ? ' WHERE cost_usd > 100' : ''}`,
                        datasetId: dataset,
                    },
                });
            }
            case 'kpi_report_builder': {
                const dataset = text(args, 'dataset');
                const title = text(args, 'title');
                const measures = Array.isArray(args['measures']) ? args['measures'] : [];
                if (!dataset) return error(422, 'dataset is required');
                if (!title) return error(422, 'title is required');
                if (measures.length === 0) return error(422, 'measures is required and must be a non-empty array');
                const base = title.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '');
                const widgets = measures.map((raw, i) => {
                    const measure = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
                    const agg = text(measure, 'agg') || 'count';
                    const field = text(measure, 'field') || '*';
                    return {
                        id: `${base}_kpi_${i + 1}`,
                        draft: {
                            kind: 'kpi',
                            datasetId: dataset,
                            title: text(measure, 'label') || `${agg}(${field})`,
                            measures: [{ agg, field }],
                        },
                    };
                });
                return json({
                    kind: 'dashboard',
                    id: base,
                    clean: true,
                    findings: [],
                    draft: { title, tiles: widgets.map((w, i) => ({ widgetId: w.id, x: (i % 3) * 4, y: Math.floor(i / 3) * 4, w: 4, h: 4 })) },
                    widgets,
                });
            }
            case 'pipeline_author': {
                const nodes = Array.isArray(args['nodes']) ? args['nodes'] : [];
                if (nodes.length === 0) return error(422, 'nodes is required and must be a non-empty array');
                const name = text(args, 'name') || 'authored_pipeline';
                return json({
                    flow: { name, nodes, edges: Array.isArray(args['edges']) ? args['edges'] : [] },
                    nodes: nodes.map((n, i) => ({ id: `n${i + 1}`, type: (n as Record<string, unknown>)?.['type'] ?? 'transform' })),
                    simulated: true,
                    seedNode: 'n1',
                    nodeOutputs: nodes.map((_, i) => ({ id: `n${i + 1}`, rows: 1000 - i * 120 })),
                    sinks: [],
                });
            }
            case 'component_draft': {
                const kind = text(args, 'kind');
                if (!kind) return error(422, 'kind is required');
                const config = args['config'];
                if (typeof config !== 'object' || config === null) {
                    return error(422, 'config is required and must be an object');
                }
                const draft = config as Record<string, unknown>;
                // A findings-carrying draft is a SUCCESS — the repair loop is the point of the surface.
                const findings = Object.keys(draft).length === 0
                    ? [{ severity: 'ERROR' as const, fieldPath: 'name', message: 'name is required' }]
                    : [];
                return json({ kind, type: kind, clean: findings.length === 0, findings, draft });
            }
            // AGT-6a A4 — the two read tools behind "explain this screen". They are non-mutating reads,
            // so the real route needs no new capability; offline they answer from a small extract of
            // docs/GLOSSARY.md (the real tool parses the whole file, which the SPA cannot read).
            case 'glossary_lookup': {
                const term = text(args, 'term');
                if (!term) return error(422, 'term is required');
                const definition = GLOSSARY[term.toLowerCase()];
                // A term with no canonical definition is a 422 — that is what drives the docs fallback.
                if (!definition) return error(422, `no canonical definition for '${term}'`);
                return json({ term, definition });
            }
            case 'docs_search': {
                const query = text(args, 'query');
                if (!query) return error(422, 'query is required');
                return json({
                    query,
                    hits: [
                        {
                            file: 'GLOSSARY.md',
                            line: 1,
                            snippet: `Offline mock: the docs corpus is not readable from the SPA, so '${query}' has no real citations.`,
                        },
                    ],
                });
            }
            // AGT-6a A4-status — "why is this red". Unlike every other tool mocked here, these three
            // answer from the MOCK STORE's own ledger rather than a canned shape: their whole value is
            // that they report deployment state, so inventing activity would make the affordance lie.
            // Offline the mock store IS the deployment, and an empty ledger honestly answers "nothing
            // happened".
            case 'status_get': {
                const pipelineId = text(args, 'pipelineId');
                const names = store.list<{ name: string }>(space, PIPELINES_COLL).map((p) => p.name);
                if (!pipelineId) return json({ pipelines: names.map((n) => pipelineStatus(store, space, n)) });
                const found = names.find((n) => n.toLowerCase() === pipelineId.toLowerCase());
                if (!found) return error(422, `unknown pipeline: '${pipelineId}'`);
                return json(pipelineStatus(store, space, found));
            }
            case 'signal_timeline': {
                const correlationId = text(args, 'correlationId');
                if (!correlationId) return error(422, 'correlationId is required');
                const since = Date.now() - minutes(args, 'sinceMinutes', 1440) * 60_000;
                const matched = signalsOf(store, space)
                    .filter((s) => s.correlationId === correlationId && s.at >= since)
                    .sort((a, b) => a.at - b.at);
                if (matched.length === 0) return error(422, `no signals for correlationId '${correlationId}'`);
                return json({
                    correlationId,
                    count: matched.length,
                    timeline: matched.map((s) => ({
                        signalId: s.signalId,
                        at: new Date(s.at).toISOString(),
                        type: s.type,
                        severity: s.severity,
                        message: typeof s.payload?.['message'] === 'string' ? s.payload['message'] : s.type,
                        causedBy: s.payload?.['causationId'] ?? null,
                        payload: s.payload ?? {},
                    })),
                });
            }
            case 'timeline_build': {
                if (args['sinceMinutes'] === undefined) return error(422, 'sinceMinutes is required');
                const since = Date.now() - minutes(args, 'sinceMinutes', 1440) * 60_000;
                const focus = text(args, 'focus').toLowerCase();
                const entries = signalsOf(store, space)
                    .filter((s) => s.at >= since)
                    .map((s) => ({
                        at: new Date(s.at).toISOString(),
                        kind: 'signal',
                        summary: typeof s.payload?.['message'] === 'string' ? s.payload['message'] : s.type,
                        ref: s.source?.id ?? s.signalId,
                        severity: s.severity,
                    }))
                    // `focus` is the tool's own substring filter — the pane passes its entity id, so a
                    // row's button shows that row's activity rather than the whole deployment's.
                    .filter((e) => !focus || `${e.summary} ${e.ref}`.toLowerCase().includes(focus))
                    .sort((a, b) => a.at.localeCompare(b.at));
                return json({ count: entries.length, truncated: false, timeline: entries });
            }
            default:
                return error(404, `unknown tool: '${tool}'`);
        }
    };
}
