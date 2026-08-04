import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { SIGNALS_COLL } from '../signals';
import type { Signal } from '../../signal/signal';
import type { Condition, ConditionGroup } from '../../query/query-types';
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
/** AGT-6a A5.1. Matched BEFORE {@link TOOLS}, mirroring the backend's route-registration order. */
const DERIVE = /\/agent\/tools\/([^/?]+)\/derive$/;

/**
 * The offline stand-in for the model hop: a deliberately crude sentence → condition reader. It exists so
 * the surface's derive path — the prompt box, the `derivedArgs` echo, and both failure answers — is
 * exercisable with no backend and no model, not to be a language model.
 *
 * Honest about what it is: it recognises `<field> over|under|above|below <number>` and nothing else. A
 * sentence it cannot read yields **no condition**, which the caller turns into the same retryable 422 a
 * real local model produces when it answers in prose — never a silently empty filter that looks like it
 * worked.
 */
/**
 * AGT-6a A5.2's offline stand-in for the schema half: read field names out of the clause after "with"
 * ("…with an id, an amount and an event date" → `id`, `amount`, `event_date`). Same contract as
 * {@link readCondition} — a sentence it cannot read yields **nothing**, which becomes the retryable 422,
 * never an empty schema presented as a successful draft.
 */
function readSchemaFields(prompt: string): Record<string, unknown>[] {
    const clause = /\bwith\b(.+)$/i.exec(prompt);
    if (!clause) return [];
    return clause[1]
        .split(/,|\band\b/i)
        .map((part) => part.trim().replace(/^(an?|the)\s+/i, '').replace(/[.?!]+$/, '').trim())
        .filter((name) => /^[a-z][a-z0-9 _-]*$/i.test(name) && name.length > 0)
        .map((name) => {
            const key = name.toLowerCase().replace(/[\s-]+/g, '_');
            // ⚠ These must be the schema form's OWN type vocabulary (`string|integer|bigint|double|
            // boolean|date|timestamp`). Emitting a plausible-but-foreign type like `number` applies
            // silently and leaves the row's type dropdown BLANK — it looks like the draft worked.
            const type = /_at$|timestamp/.test(key) ? 'timestamp'
                : /date|time/.test(key) ? 'date'
                    : /amount|total|price|rate|ratio/.test(key) ? 'double'
                        : /count|qty|quantity|_id$|^id$|num/.test(key) ? 'integer'
                            : /^is_|^has_|flag/.test(key) ? 'boolean'
                                : 'string';
            return { name: key, type };
        });
}

/**
 * AGT-6a A5.3's offline stand-in for the graph half: recognise the three stages a sentence can name —
 * collect/acquire, filter/drop, write/store — and wire them in that order.
 *
 * ⚠ Node types must be the engine's OWN registered vocabulary (`acquisition`, `transform.filter`,
 * `sink.persistent`). A plausible-but-unregistered type is a validator WARNING rather than an obvious
 * break, so a wrong one here would look like a working draft — the same trap the schema half hit with
 * `number`. Same contract as the others: an unreadable sentence yields nothing, hence a retryable 422.
 */
function readPipeline(prompt: string): Record<string, unknown> | null {
    const stages: { id: string; type: string; config?: Record<string, unknown> }[] = [];
    if (/\bcollect|acquire|ingest|read\b/i.test(prompt)) stages.push({ id: 'acq', type: 'acquisition' });
    const cond = readCondition(prompt);
    if (cond)
        stages.push({
            id: 'flt',
            type: 'transform.filter',
            // The filter KEEPS what the sentence describes dropping, so invert the operator it read.
            config: { where: `${cond.field} ${cond.operator === '<' ? '>=' : '<='} ${cond.value}` },
        });
    if (/\bwrite|store|sink|save|persist\b/i.test(prompt))
        stages.push({ id: 'sink', type: 'sink.persistent', config: { store: 'drafted' } });
    if (stages.length < 2) return null;   // one lone node is not a topology
    return {
        name: 'drafted_flow',
        nodes: stages,
        edges: stages.slice(1).map((s, i) => ({ from: stages[i].id, to: s.id })),
    };
}

/**
 * ⚠ Emits the CANONICAL leaf — `{kind:'condition', field, operator, value}`, with the comparison under
 * `operator` (not `op`) and the operand as TEXT, wrapped by the caller in a `{op, items}` group.
 *
 * That is not a style choice. The server renders `when` through `ConditionSql`, which walks
 * `items`/`conditions` and reads each leaf's `operator`; a flat `{field, op, value}` is not a group, so
 * it contributes no constraint and `predicate()` answers `TRUE` — the tool then returns SQL with **no
 * WHERE at all**, `clean:true`, no findings. A mock emitting the flat shape here would be teaching the
 * derive path the one argument shape that fails silently and looks like it worked.
 */
function readCondition(prompt: string): Condition | null {
    const m = /(\w+)\s+(over|above|greater than|under|below|less than)\s+(-?\d+(?:\.\d+)?)/i.exec(prompt);
    if (!m) return null;
    const greater = /over|above|greater/i.test(m[2]);
    return { kind: 'condition', field: m[1].toLowerCase(), operator: greater ? '>' : '<', value: m[3] };
}

/** The one leaf as the group the tool's `when` argument must be. */
function asGroup(condition: Condition): ConditionGroup {
    return { kind: 'group', op: 'AND', items: [condition] };
}

/**
 * The offline counterpart to the server's {@code ConditionSql.predicate}: walk the structured tree the
 * pane builds and render a predicate, or `''` when the tree imposes no constraint.
 *
 * Deliberately **not** a reimplementation — the server's typed casts (`TRY_CAST(… AS DOUBLE)`, the
 * boolean CASE, the LIKE escaping) are its business and copying them here would be a second, drifting
 * SQL renderer. What must match is **acceptance**: the same trees render, the same trees render nothing,
 * and an unreadable operator is `FALSE` rather than silently dropped. That parity is the whole point —
 * this branch used to read a flat `{field, op, value}` and fall back to a hardcoded `cost_usd > 100`,
 * so a real condition tree produced a WHERE clause the operator's conditions never asked for.
 */
function renderPredicate(node: unknown): string {
    if (!isGroupNode(node)) return '';
    const g = node as Record<string, unknown>;
    const raw = g['items'] ?? g['conditions'];   // ConditionSql accepts either key
    const items = Array.isArray(raw) ? raw : [];
    const joiner = String(g['op'] ?? 'AND').toUpperCase() === 'OR' ? ' OR ' : ' AND ';
    const parts = items
        .map((item) => (isGroupNode(item) ? renderPredicate(item) : renderLeaf(item)))
        .filter((part) => part !== '');
    return parts.length ? `(${parts.join(joiner)})` : '';
}

/** `kind` decides when it is declared; otherwise the presence of child items does — ConditionSql's rule. */
function isGroupNode(node: unknown): boolean {
    if (typeof node !== 'object' || node === null || Array.isArray(node)) return false;
    const n = node as Record<string, unknown>;
    if (n['kind'] === 'group') return true;
    if (n['kind'] === 'condition') return false;
    return 'items' in n || 'conditions' in n;
}

/** One leaf, or `''` when incomplete — the server's `isComplete` gate, which is what makes a half-built
 *  row contribute nothing instead of narrowing the result to `FALSE`. */
function renderLeaf(node: unknown): string {
    if (typeof node !== 'object' || node === null) return '';
    const c = node as Record<string, unknown>;
    const field = typeof c['field'] === 'string' ? c['field'] : '';
    const operator = typeof c['operator'] === 'string' ? c['operator'] : '';
    if (!field || !operator) return '';
    const operand = (key: string) => (c[key] === undefined || c[key] === null ? '' : String(c[key]));
    const lit = (v: string) => (/^-?\d+(?:\.\d+)?$/.test(v) ? v : `'${v.replace(/'/g, "''")}'`);
    const value = operand('value');
    const value2 = operand('value2');
    if (operator === 'isNull') return `${field} IS NULL`;
    if (operator === 'isNotNull') return `${field} IS NOT NULL`;
    if (operator === 'between') return value && value2 ? `(${field} >= ${lit(value)} AND ${field} <= ${lit(value2)})` : '';
    if (!value) return '';
    switch (operator) {
        case 'contains': return `${field} LIKE '%${value}%'`;
        case 'startsWith': return `${field} LIKE '${value}%'`;
        case 'endsWith': return `${field} LIKE '%${value}'`;
        case 'in': return `${field} IN (${value.split(',').map((v) => lit(v.trim())).join(', ')})`;
        case '=': case '!=': case '<': case '<=': case '>': case '>=':
            return `${field} ${operator === '!=' ? '<>' : operator} ${lit(value)}`;
        // An operator the renderer does not know narrows to nothing, exactly as ConditionSql does —
        // never "ignore the clause", which would widen the result set instead.
        default: return 'FALSE';
    }
}

/**
 * What `component_draft` will validate, and the REQUIRED field paths of each kind's `ConfigSpec` —
 * mirrored from `ConfigSpecs` (`inspecto-config`), which the real tool resolves via `configType(kind)`.
 * `alert-rule` maps to the `alert` type; that rename is the one place kind ≠ type.
 *
 * ⚠ A SUBSET, deliberately: the real spec also checks enums, int/bool parsability and cross-field rules,
 * and re-implementing that here would be a second, drifting copy of the config system. What is mirrored
 * is the part that decides ACCEPTANCE — an unvalidatable kind is refused, and a missing required field is
 * a finding — because the old branch had neither, so offline every non-empty config came back
 * `clean:true` and the A5.2 repair loop could not be exercised at all. Offline findings remain a
 * rehearsal of the shape, never evidence that the real spec passes.
 */
const DRAFT_SPECS: Record<string, { type: string; required: string[]; nonEmpty?: string[] }> = {
    pipeline: { type: 'pipeline', required: ['name', 'dirs.poll', 'dirs.database'] },
    enrichment: { type: 'enrichment', required: ['name', 'input.database', 'output.database'] },
    job: { type: 'job', required: ['job.name', 'job.type'] },
    // `raw.name`, NOT a bare `fields`: the registry schema COMPONENT was retired 2026-07-31 (unification
    // W1), so this kind again means the TOON schema config and the real tool resolves it through
    // `ConfigSpecs.forType`. Keep this mirroring that — a bare column list was the component's shape.
    schema: { type: 'schema', required: ['raw.name'] },
    meta: { type: 'meta', required: ['name'] },
    'alert-rule': { type: 'alert', required: ['alert.name', 'alert.threshold', 'alert.window'] },
    expectation: { type: 'expectation', required: ['name', 'target', 'column'] },
    widget: { type: 'widget', required: ['vizType'] },
    dashboard: { type: 'dashboard', required: ['tiles'], nonEmpty: ['tiles'] },
};

/** `RawConfig.present`: a dotted path resolves through nested maps, and a null leaf counts as absent. */
function presentAt(config: Record<string, unknown>, path: string): boolean {
    let node: unknown = config;
    for (const segment of path.split('.')) {
        if (typeof node !== 'object' || node === null || Array.isArray(node)) return false;
        node = (node as Record<string, unknown>)[segment];
    }
    return node !== undefined && node !== null;
}

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
    'entity': 'A business node in a business graph (a caller, an account). Never used for artifacts (Component/Part) or assets (Asset/Lineage).',
    'link': 'A business edge between Entities (a call, a transaction) carrying typed attributes (call-type, duration). Never used for artifacts (Component/Part) or assets (Asset/Lineage).',
    'entity projection': "The mapping (not a store) that folds a Dataset's rows into an Entity/Link graph: column → source Entity, column → target Entity, optional columns → Link type/attributes.",
    'link-analysis view': 'A saved investigation in the Link Analysis Studio (Component kind `link-analysis-view`); when its source is `entity-projection` it is a Widget (a Graph Visualization Type bound to a Dataset).',
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

        // AGT-6a A5.1 — the derive hop. Checked FIRST, because /agent/tools/([^/?]+)$ would otherwise
        // never match this URL at all and the route would 404 offline while working against a real
        // backend. Rather than reimplement each tool's offline body, it derives the arguments and then
        // RE-ENTERS this same handler on the deterministic URL: the two paths cannot drift, which is
        // exactly the property the backend gets by reusing runTool.
        const d = match(req.url, DERIVE);
        if (d) {
            const tool = d[1];
            if (MUTATING.has(tool))          // refused before any "model" runs, like the real route
                return error(403, `tool '${tool}' is mutating and is not invocable directly`);
            const prompt = typeof (req.body as { prompt?: unknown } | null)?.prompt === 'string'
                ? String((req.body as { prompt: string }).prompt).trim() : '';
            if (!prompt) return error(400, 'prompt is required');
            if (tool !== 'query_author' && tool !== 'component_draft' && tool !== 'pipeline_author')
                return error(404, `unknown tool: '${tool}'`);   // A5.1 + A5.2 + A5.3

            // Schema-keyed, pane-wins: the same merge order the backend does.
            let derivedArgs: Record<string, unknown>;
            if (tool === 'pipeline_author') {
                const graph = readPipeline(prompt);
                if (!graph)
                    return error(422, `the model did not produce arguments for '${tool}'`
                        + ' — rephrase the request, or fill the form directly');
                derivedArgs = { flow: graph, ...argsOf(req) };
            } else if (tool === 'component_draft') {
                const fields = readSchemaFields(prompt);
                if (!fields.length)
                    return error(422, `the model did not produce arguments for '${tool}'`
                        + ' — rephrase the request, or fill the form directly');
                derivedArgs = { config: { fields }, ...argsOf(req) };
            } else {
                const when = readCondition(prompt);
                if (!when)
                    return error(422, `the model did not produce arguments for '${tool}'`
                        + ' — rephrase the request, or fill the form directly');
                derivedArgs = { when: asGroup(when), ...argsOf(req) };
            }
            const inner = agentHandler(flags)(
                { ...req, url: req.url.replace(/\/derive$/, ''), body: { args: derivedArgs } }, store);
            if (!inner || (inner.status ?? 200) >= 400) return inner;
            // A5.2: the real backend may spend up to 3 repair turns. Offline there is no model to run a
            // second turn with, so 1 is the honest number — never fake a loop that did not run. ⚠ It is
            // 1 even when the draft comes back WITH findings: that is a loop which did not converge, and
            // reporting 3 would claim repair attempts that never happened.
            return json(tool === 'query_author'
                ? { value: inner.body, derivedArgs }
                : { value: inner.body, derivedArgs, turns: 1 });
        }

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
                // ⚠ `when` is a structured GROUP (`{op, items:[{field, operator, value}]}`), the shape the
                // server's ConditionSql walks — never a flat `{field, op, value}`. A tree that renders no
                // constraint must produce NO WHERE, because that is precisely what the real tool does with
                // one, and the draft comes back `clean:true` either way: the missing filter is the only
                // evidence the operator ever gets.
                const predicate = renderPredicate(args['when']);
                const name = text(args, 'name');
                return json({
                    kind: 'query',
                    id: name || null,
                    clean: true,
                    findings: [],
                    draft: {
                        type: 'sql',
                        // The real tool renders trusted SQL server-side — the model never writes SQL text.
                        text: `SELECT * FROM (SELECT * FROM ${dataset}) AS __q${predicate ? ` WHERE ${predicate}` : ''}`,
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
                // ⚠ These shapes are the SERVER's, verified against InspectoTools.widgetDraft/tile:
                // a widget draft is `{vizType, datasetId, controls, options.title}` (NOT `{kind, title,
                // measures}`) and a tile is `{widgetId, span}` (NOT an x/y/w/h grid rect). The earlier
                // mock invented both, so an adopting pane would have saved empty widgets and untileable
                // dashboards while looking correct offline — the same leniency trap as `pipeline_author`.
                const base = title.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '');
                const norm = measures.map((raw) => {
                    const measure = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
                    const agg = text(measure, 'agg') || 'count';
                    const field = text(measure, 'field');
                    if (agg !== 'count' && !field) return null;
                    return {
                        value: { agg, field: field || '*' },
                        label: text(measure, 'label') || `${agg} ${field || '*'}`.trim(),
                    };
                });
                if (norm.some((n) => n === null)) return error(422, 'a non-count measure requires a field');
                const kept = norm as { value: { agg: string; field: string }; label: string }[];
                const groupBy = (Array.isArray(args['groupBy']) ? args['groupBy'] : [])
                    .filter((g): g is string => typeof g === 'string' && g.trim() !== '')
                    .map((g) => g.trim());

                const widget = (id: string, vizType: string, controls: Record<string, unknown>, caption: string) => ({
                    id,
                    draft: { vizType, datasetId: dataset, controls, options: { title: caption } },
                });
                // No groupBy → one `kpi` widget per measure, span 1. With groupBy → ONE `bar` widget, span 2.
                const widgets = groupBy.length
                    ? [
                          widget(
                              `${base}_chart`,
                              'bar',
                              {
                                  x: [{ field: groupBy[0] }],
                                  y: kept.map((k) => k.value),
                                  ...(groupBy.length > 1 ? { series: [{ field: groupBy[1] }] } : {}),
                              },
                              title,
                          ),
                      ]
                    : kept.map((k, i) => widget(`${base}_kpi_${i + 1}`, 'kpi', { value: [k.value] }, k.label));
                const tiles = widgets.map((w) => ({ widgetId: w.id, span: groupBy.length ? 2 : 1 }));
                const filter = args['filter'];
                const draft: Record<string, unknown> = { tiles };
                if (filter && typeof filter === 'object' && Object.keys(filter).length) draft['filter'] = filter;
                return json({ kind: 'dashboard', id: base, clean: true, findings: [], draft, widgets });
            }
            case 'pipeline_author': {
                // ⚠ The graph arrives under `flow` — the real tool 422s anything else. This branch used to
                // read `nodes`/`edges` flat, which made the pane's (also flat, also wrong) call look like
                // it worked offline while failing against every real backend.
                const flow = args['flow'];
                if (!flow || typeof flow !== 'object' || Array.isArray(flow))
                    return error(422, 'flow is required and must be an object');
                const graph = flow as Record<string, unknown>;
                const nodes = Array.isArray(graph['nodes']) ? graph['nodes'] : [];
                if (nodes.length === 0) return error(422, 'invalid flow: nodes is required and must be non-empty');
                const name = text(graph, 'name') || 'authored_pipeline';
                const edges = Array.isArray(graph['edges']) ? graph['edges'] : [];
                // A subset of PipelineValidator: only DANGLING_TO, which is the defect a model actually
                // produces. Offline findings are a rehearsal of the shape, never evidence of the real rules.
                const ids = new Set(nodes.map((n) => String((n as Record<string, unknown>)?.['id'] ?? '')));
                const findings = edges
                    .filter((e) => !ids.has(String((e as Record<string, unknown>)?.['to'] ?? '')))
                    .map((e) => ({
                        severity: 'ERROR',
                        code: 'DANGLING_TO',
                        fieldPath: 'edges',
                        message: `Edge target '${String((e as Record<string, unknown>)['to'])}' is not a node in this flow.`,
                    }));
                const clean = findings.length === 0;
                return json({
                    name,
                    // Mirrors PipelineCodec.toMap, `active` included — the real echo always carries it, and
                    // a mock that drops it renders a phantom "active (removed)" row in the operator's diff.
                    flow: { name, active: graph['active'] === true, nodes, edges },
                    nodes: nodes.map((n) => ({
                        id: (n as Record<string, unknown>)?.['id'],
                        type: (n as Record<string, unknown>)?.['type'] ?? 'transform',
                    })),
                    clean,
                    findings,
                    simulated: clean,
                    ...(clean
                        ? {
                              seedNode: String((nodes[0] as Record<string, unknown>)?.['id'] ?? 'n1'),
                              nodeOutputs: nodes.map((n, i) => ({
                                  node: (n as Record<string, unknown>)?.['id'],
                                  rows: 1000 - i * 120,
                              })),
                              sinks: [],
                          }
                        : { note: 'graph parsed but is not executable — fix the findings first' }),
                });
            }
            // Link Analysis V2 (d): derive an entity-projection mapping from the column list the PANE
            // supplies (no route returns a Dataset's columns). Offline the pane's columns come from
            // SAMPLE_SOURCES, so a draft here is over sample columns — never evidence the real path works.
            case 'projection_author': {
                const datasetId = text(args, 'datasetId');
                if (!datasetId) return error(422, 'datasetId is required');
                const columns = (Array.isArray(args['columns']) ? args['columns'] : [])
                    .map((c) => (typeof c === 'string' ? c : text((c ?? {}) as Record<string, unknown>, 'name')))
                    .filter((c): c is string => !!c);
                if (columns.length < 2) {
                    return error(422, 'columns is required and must name at least two columns');
                }
                const hint = text(args, 'hint').toLowerCase();
                const narrowed = hint ? columns.filter((c) => c.toLowerCase().includes(hint)) : columns;
                const candidates = narrowed.length >= 2 ? narrowed : columns;
                const findings = narrowed.length >= 2
                    ? []
                    : [{ severity: 'WARNING' as const, fieldPath: 'hint', message: `hint '${hint}' matched fewer than two columns — it was ignored` }];
                // Same shapes the real tool scores, kept short: it is the RESULT SHAPE that matters here.
                const pairs = [['caller', 'callee'], ['source', 'target'], ['src', 'dst'], ['from', 'to']];
                const carries = (c: string, token: string) => c.toLowerCase().split(/[^a-z0-9]+/).includes(token)
                    || c.toLowerCase().startsWith(token) || c.toLowerCase().endsWith(token);
                let source: string | undefined;
                let target: string | undefined;
                for (const [a, b] of pairs) {
                    source = candidates.find((c) => carries(c, a));
                    target = candidates.find((c) => carries(c, b) && c !== source);
                    if (source && target) break;
                    source = undefined;
                    target = undefined;
                }
                if (!source || !target) {
                    const ids = candidates.filter((c) => carries(c, 'id')).slice(0, 2);
                    [source, target] = ids.length === 2 ? ids : [undefined, undefined];
                }
                if (!source || !target) {
                    return json({
                        kind: 'link-analysis-view', id: datasetId, clean: false,
                        findings: [...findings, {
                            severity: 'ERROR' as const, fieldPath: 'projections.0.sourceCol',
                            message: `no source/target column pair could be derived from [${candidates.join(', ')}] — pick the two endpoint columns by hand`,
                        }],
                        draft: { query: { projections: [{ datasetId, sourceCol: '', targetCol: '' }] } },
                    });
                }
                const linkKindCol = candidates.find(
                    (c) => c !== source && c !== target && ['kind', 'type', 'relation'].some((t) => carries(c, t)),
                );
                const attrCols = columns.filter((c) => c !== source && c !== target && c !== linkKindCol).slice(0, 8);
                return json({
                    kind: 'link-analysis-view',
                    id: datasetId,
                    clean: findings.length === 0,
                    findings,
                    draft: {
                        query: {
                            // entityType is left UNSET on a single mapping — it changes node ids.
                            projections: [{
                                datasetId, sourceCol: source, targetCol: target,
                                ...(linkKindCol ? { linkKindCol } : {}),
                                ...(attrCols.length ? { attrCols } : {}),
                            }],
                        },
                    },
                });
            }
            case 'component_draft': {
                const kind = text(args, 'kind');
                if (!kind) return error(422, 'kind is required');
                const config = args['config'];
                if (typeof config !== 'object' || config === null) {
                    return error(422, 'config is required and must be an object');
                }
                // An unvalidatable kind is a REFUSAL, not an empty pass. This branch used to accept any
                // string and echo it back as `type`, so a wrong kind looked like a clean draft offline.
                const spec = DRAFT_SPECS[kind.trim().toLowerCase()];
                if (!spec) {
                    return error(422, `no structural spec for kind '${kind}' (validatable kinds: `
                        + `${Object.keys(DRAFT_SPECS).join(', ')})`);
                }
                const draft = config as Record<string, unknown>;
                // A findings-carrying draft is a SUCCESS — the repair loop is the point of the surface.
                const findings = spec.required
                    .filter((path) => !presentAt(draft, path))
                    .map((path) => ({
                        severity: 'ERROR' as const,
                        fieldPath: path,
                        message: `Missing required field '${path}'`,
                    }));
                // The two list kinds carry a server-side cross-field rule that a PRESENT but EMPTY list is
                // still an ERROR (`at-least-one-field` / `at-least-one-tile`). Without this the mock passes
                // `{fields:[]}` — a draft the pane's `applySchemaDraft` then discards, so Apply no-ops with
                // no finding to explain why.
                for (const path of spec.nonEmpty ?? []) {
                    const value = draft[path];
                    if (Array.isArray(value) && value.length === 0) {
                        findings.push({
                            severity: 'ERROR' as const,
                            fieldPath: path,
                            message: path === 'tiles'
                                ? 'A dashboard needs at least one tile.'
                                : 'A schema component needs at least one field.',
                        });
                    }
                }
                return json({ kind, type: spec.type, clean: findings.length === 0, findings, draft });
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
