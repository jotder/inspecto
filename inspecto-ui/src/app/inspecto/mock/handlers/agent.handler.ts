import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';

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

function argsOf(req: MockRequest): Record<string, unknown> {
    const body = req.body as { args?: unknown } | null;
    const args = body?.args;
    return typeof args === 'object' && args !== null ? (args as Record<string, unknown>) : {};
}

function text(args: Record<string, unknown>, key: string): string {
    const v = args[key];
    return typeof v === 'string' ? v.trim() : '';
}

export function agentHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest) => {
        if (!flags.mockOps) return undefined;
        if (req.method !== 'POST') return undefined;
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
            default:
                return error(404, `unknown tool: '${tool}'`);
        }
    };
}
