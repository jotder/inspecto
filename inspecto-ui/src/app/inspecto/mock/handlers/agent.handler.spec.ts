import { describe, expect, it } from 'vitest';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { agentHandler } from './agent.handler';

const req = (url: string, body: unknown): MockRequest => ({
    method: 'POST',
    url,
    body,
    params: {},
    space: 'default',
});

/**
 * AGT-6a A5.3 — `pipeline_author` offline.
 *
 * This spec exists because of how the two A2 bugs survived: the mock was **more lenient than the real
 * backend**, so a pane calling the tool wrongly looked correct offline and failed against every real
 * deployment. A mock that accepts what the server rejects is worse than no mock, so its strictness is
 * pinned here rather than left to the preview.
 */
describe('agentHandler · pipeline_author', () => {
    const handler = agentHandler({ mockOps: true });
    const store = new MockStore();

    const WIRED = {
        name: 'orders_flow',
        nodes: [
            { id: 'acq', type: 'acquisition' },
            { id: 'sink', type: 'sink.persistent' },
        ],
        edges: [{ from: 'acq', to: 'sink' }],
    };

    it('rejects the graph passed flat, exactly as the real tool does', () => {
        const res = handler(req('/api/agent/tools/pipeline_author', { args: WIRED }), store);
        expect(res?.status).toBe(422);
        expect(String((res?.body as { error?: string })?.error)).toContain('flow is required');
    });

    it('accepts the graph under `flow` and echoes the parsed graph back', () => {
        const res = handler(req('/api/agent/tools/pipeline_author', { args: { flow: WIRED } }), store);
        const body = res?.body as Record<string, unknown>;

        expect(res?.status ?? 200).toBe(200);
        expect(body['name']).toBe('orders_flow');
        // The adapter reads `flow` as the graph — a name string here renders as "no suggestion".
        expect(body['flow']).toMatchObject({ name: 'orders_flow' });
        expect(body['clean']).toBe(true);
    });

    it('reports a dangling edge as a finding rather than a refusal', () => {
        const flow = { ...WIRED, edges: [{ from: 'acq', to: 'warehouse' }] };
        const res = handler(req('/api/agent/tools/pipeline_author', { args: { flow } }), store);
        const body = res?.body as Record<string, unknown>;

        expect(res?.status ?? 200).toBe(200);
        expect(body['clean']).toBe(false);
        expect(body['simulated']).toBe(false);
        expect(body['findings']).toMatchObject([{ code: 'DANGLING_TO', fieldPath: 'edges' }]);
    });

    it('derives a topology from a sentence and reports the turn count', () => {
        const res = handler(
            req('/api/agent/tools/pipeline_author/derive',
                { prompt: 'collect orders, drop rows under 100 and write them to the store', args: {} }),
            store,
        );
        const body = res?.body as { value: Record<string, unknown>; derivedArgs: Record<string, unknown>; turns: number };

        expect(res?.status ?? 200).toBe(200);
        expect(body.turns).toBe(1);
        const flow = body.derivedArgs['flow'] as { nodes: { type: string }[] };
        expect(flow.nodes.map((n) => n.type)).toEqual(['acquisition', 'transform.filter', 'sink.persistent']);
        expect(body.value['clean']).toBe(true);
    });

    it('answers an unreadable sentence with a retryable 422, never an empty graph', () => {
        const res = handler(
            req('/api/agent/tools/pipeline_author/derive', { prompt: 'do something clever', args: {} }),
            store,
        );
        expect(res?.status).toBe(422);
    });
});

/**
 * `query_author` offline — the same bug class, one tool over.
 *
 * The server renders `when` through `ConditionSql`, which walks a GROUP (`{op, items:[…]}`) and reads
 * each leaf's comparison under `operator`. Anything else contributes no constraint and yields SQL with
 * no WHERE, reported as a clean draft — so the mock's job is to render exactly the trees the server
 * renders and nothing more. It used to read a flat `{field, op, value}` and fall back to a hardcoded
 * `cost_usd > 100`, which both hid that failure and invented a filter of its own.
 */
describe('agentHandler · query_author', () => {
    const handler = agentHandler({ mockOps: true });
    const store = new MockStore();

    const sqlOf = (args: Record<string, unknown>): string => {
        const res = handler(req('/api/agent/tools/query_author', { args }), store);
        expect(res?.status ?? 200).toBe(200);
        return String((res?.body as { draft: { text: string } }).draft.text);
    };

    it('renders the structured condition tree the pane actually passes', () => {
        const when = {
            kind: 'group',
            op: 'AND',
            items: [
                { kind: 'condition', field: 'amount', operator: '>', value: '100' },
                { kind: 'condition', field: 'status', operator: '=', value: 'open' },
            ],
        };
        expect(sqlOf({ dataset: 'orders', when })).toContain("WHERE (amount > 100 AND status = 'open')");
    });

    it('nests sub-groups and honours the group operator', () => {
        const when = {
            kind: 'group',
            op: 'OR',
            items: [
                { kind: 'condition', field: 'a', operator: '=', value: '1' },
                { kind: 'group', op: 'AND', items: [{ kind: 'condition', field: 'b', operator: '<', value: '2' }] },
            ],
        };
        expect(sqlOf({ dataset: 'orders', when })).toContain('WHERE (a = 1 OR (b < 2))');
    });

    it('emits NO where clause for a tree that imposes no constraint — never an invented predicate', () => {
        expect(sqlOf({ dataset: 'orders' })).not.toContain('WHERE');
        expect(sqlOf({ dataset: 'orders', when: {} })).not.toContain('WHERE');
        // A half-built row contributes nothing (the server's `isComplete` gate), it does not narrow to FALSE.
        const partial = { kind: 'group', op: 'AND', items: [{ kind: 'condition', field: 'amount', operator: '>' }] };
        expect(sqlOf({ dataset: 'orders', when: partial })).not.toContain('WHERE');
    });

    it('drops a FLAT condition exactly as the server does, rather than papering over it', () => {
        // Deliberately mirrors the server instead of 422ing: `ConditionSql` accepts this and renders TRUE,
        // so the honest offline answer is the same missing filter. Reporting success WITH the predicate —
        // what the old fallback did — is what made the flat shape look supported.
        expect(sqlOf({ dataset: 'orders', when: { field: 'amount', op: '>', value: 100 } })).not.toContain('WHERE');
    });

    it('derives a GROUP from a sentence, not the flat shape ConditionSql cannot read', () => {
        const res = handler(
            req('/api/agent/tools/query_author/derive', { prompt: 'orders over 100', args: { dataset: 'orders' } }),
            store,
        );
        const body = res?.body as { value: { draft: { text: string } }; derivedArgs: Record<string, unknown> };

        expect(res?.status ?? 200).toBe(200);
        expect(body.derivedArgs['when']).toMatchObject({
            kind: 'group',
            op: 'AND',
            items: [{ kind: 'condition', field: 'orders', operator: '>', value: '100' }],
        });
        // The echo and the SQL must agree — a derivedArgs panel contradicting the draft reads as a bug.
        expect(body.value.draft.text).toContain('WHERE (orders > 100)');
    });
});

/**
 * `component_draft` offline — the tool is a VALIDATOR, so a mock that validates nothing is the worst
 * kind of lenient: it reports every draft `clean:true` and the A5.2 repair loop can never be seen to
 * run. These pin the two gates that decide acceptance against the real `ConfigSpecs`.
 */
describe('agentHandler · component_draft', () => {
    const handler = agentHandler({ mockOps: true });
    const store = new MockStore();

    const call = (args: Record<string, unknown>) =>
        handler(req('/api/agent/tools/component_draft', { args }), store);

    it('refuses a kind with no structural spec instead of echoing it back as clean', () => {
        const res = call({ kind: 'grammar', config: { delimiter: ',' } });
        expect(res?.status).toBe(422);
        expect(String((res?.body as { error?: string })?.error)).toContain('no structural spec');
    });

    it('resolves the config TYPE, which is not always the kind', () => {
        const res = call({ kind: 'alert-rule', config: { alert: { name: 'a', threshold: 1, window: '5m' } } });
        expect((res?.body as { type: string }).type).toBe('alert');
        expect((res?.body as { clean: boolean }).clean).toBe(true);
    });

    it('reports a missing required field as an anchored finding, at 200 — findings are the payload', () => {
        const res = call({ kind: 'expectation', config: { name: 'amount_not_null', target: 'orders' } });
        const body = res?.body as { clean: boolean; findings: { fieldPath: string; severity: string }[] };

        expect(res?.status ?? 200).toBe(200);
        expect(body.clean).toBe(false);
        expect(body.findings).toMatchObject([{ fieldPath: 'column', severity: 'ERROR' }]);
    });

    it('resolves dotted paths through nested maps, so a satisfied nested field is not re-reported', () => {
        expect((call({ kind: 'job', config: { job: { name: 'nightly', type: 'sql' } } })?.body as { clean: boolean }).clean)
            .toBe(true);
        const partial = call({ kind: 'job', config: { job: { name: 'nightly' } } })?.body as {
            findings: { fieldPath: string }[];
        };
        expect(partial.findings.map((f) => f.fieldPath)).toEqual(['job.type']);
    });

    it("validates the SCHEMA kind against the registry component's shape, not the TOON config's", () => {
        // The Components pane's A5.2 draft shape. `schema` names two unrelated things — the TOON schema
        // config (`raw.name` required) and this registry component (a bare column list) — and the tool's
        // `kind` vocabulary is the COMPONENT one, so the pane's own draft must come back clean.
        // Previously it resolved to the config spec and always answered "Missing required field 'raw.name'",
        // a finding the operator could not act on and the repair loop could only make worse.
        const res = call({ kind: 'schema', config: { fields: [{ name: 'id', type: 'integer' }] } });
        const body = res?.body as { clean: boolean; findings: { fieldPath: string }[] };

        expect(body.clean).toBe(true);
        expect(body.findings).toEqual([]);
    });

    it('reports a schema draft that carries no fields, rather than passing it', () => {
        // ⚠ Both halves matter. An ABSENT `fields` is the missing-required finding; a PRESENT but EMPTY one
        // is the server's `at-least-one-field` cross-field rule. The empty case is the trap: the pane's
        // `applySchemaDraft` discards a fieldless draft, so without a finding Apply would silently no-op.
        const missing = call({ kind: 'schema', config: { name: 'events' } })?.body as {
            clean: boolean; findings: { fieldPath: string }[];
        };
        expect(missing.clean).toBe(false);
        expect(missing.findings.map((f) => f.fieldPath)).toEqual(['fields']);

        const empty = call({ kind: 'schema', config: { fields: [] } })?.body as {
            clean: boolean; findings: { fieldPath: string; message: string }[];
        };
        expect(empty.clean).toBe(false);
        expect(empty.findings.map((f) => f.message)).toEqual(['A schema component needs at least one field.']);
    });
});

/**
 * AGT-6a — `kpi_report_builder` offline.
 *
 * This tool had NO mock coverage, which is how three shape divergences survived: the widget draft was
 * `{kind, title, measures}` instead of the server's `{vizType, datasetId, controls, options.title}`,
 * tiles were an x/y/w/h grid rect instead of `{widgetId, span}`, and the `groupBy` branch was missing
 * entirely. A pane built on those would have saved empty widgets and untileable dashboards while
 * looking correct offline. Shapes below are pinned against `InspectoTools.widgetDraft`/`tile`.
 */
describe('agentHandler · kpi_report_builder', () => {
    const handler = agentHandler({ mockOps: true });
    const store = new MockStore();
    const call = (args: Record<string, unknown>) =>
        handler(req('/api/agent/tools/kpi_report_builder', { args }), store);

    it('emits one kpi widget per measure with the server’s widget-draft shape', () => {
        const res = call({
            dataset: 'cdr_sample',
            title: 'Revenue Report',
            measures: [{ agg: 'sum', field: 'revenue' }, { agg: 'count' }],
        });
        const body = res?.body as Record<string, unknown>;
        expect(res?.status ?? 200).toBe(200);
        expect(body['id']).toBe('revenue_report');

        const widgets = body['widgets'] as { id: string; draft: Record<string, unknown> }[];
        expect(widgets.map((w) => w.id)).toEqual(['revenue_report_kpi_1', 'revenue_report_kpi_2']);
        expect(widgets[0].draft).toEqual({
            vizType: 'kpi',
            datasetId: 'cdr_sample',
            controls: { value: [{ agg: 'sum', field: 'revenue' }] },
            options: { title: 'sum revenue' },
        });
        // ⚠ NOT a grid rect — `DashboardTile` is `{widgetId, span}` and nothing else.
        expect((body['draft'] as Record<string, unknown>)['tiles']).toEqual([
            { widgetId: 'revenue_report_kpi_1', span: 1 },
            { widgetId: 'revenue_report_kpi_2', span: 1 },
        ]);
    });

    it('collapses to ONE span-2 bar widget when groupBy is present', () => {
        const body = call({
            dataset: 'cdr_sample',
            title: 'By Region',
            measures: [{ agg: 'sum', field: 'revenue' }],
            groupBy: ['region', 'tariff'],
        })?.body as Record<string, unknown>;

        const widgets = body['widgets'] as { id: string; draft: Record<string, unknown> }[];
        expect(widgets).toHaveLength(1);
        expect(widgets[0].id).toBe('by_region_chart');
        expect(widgets[0].draft['vizType']).toBe('bar');
        expect(widgets[0].draft['controls']).toEqual({
            x: [{ field: 'region' }],
            y: [{ agg: 'sum', field: 'revenue' }],
            series: [{ field: 'tariff' }],
        });
        expect((body['draft'] as Record<string, unknown>)['tiles']).toEqual([{ widgetId: 'by_region_chart', span: 2 }]);
    });

    it('rejects what the real tool rejects', () => {
        expect(call({ title: 'x', measures: [{ agg: 'count' }] })?.status).toBe(422);
        expect(call({ dataset: 'd', measures: [{ agg: 'count' }] })?.status).toBe(422);
        expect(call({ dataset: 'd', title: 'x', measures: [] })?.status).toBe(422);
        // A non-count agg without a field is a server-side 422 — the mock must not invent `*`.
        expect(call({ dataset: 'd', title: 'x', measures: [{ agg: 'sum' }] })?.status).toBe(422);
    });
});
