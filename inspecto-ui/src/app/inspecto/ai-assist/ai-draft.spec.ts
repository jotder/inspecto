import { describe, expect, it } from 'vitest';
import { adaptToolResult, configDiff } from './ai-draft';

describe('adaptToolResult', () => {
    it('normalizes a component_draft envelope, keeping the anchored findings', () => {
        const drafts = adaptToolResult('component_draft', {
            kind: 'expectation',
            type: 'expectation',
            clean: false,
            findings: [{ severity: 'ERROR', fieldPath: 'column', message: 'column is required' }],
            draft: { name: 'amt-nonneg' },
        });

        expect(drafts).toHaveLength(1);
        expect(drafts[0].clean).toBe(false);
        expect(drafts[0].findings[0].fieldPath).toBe('column');
        expect(drafts[0].config).toEqual({ name: 'amt-nonneg' });
    });

    it('prefers the id as the label and falls back to the kind', () => {
        expect(adaptToolResult('query_author', { kind: 'query', id: 'costly_calls', clean: true, findings: [], draft: { type: 'sql' } })[0].label)
            .toBe('costly_calls');
        // The real tool returns id: null when the caller supplied no name.
        expect(adaptToolResult('query_author', { kind: 'query', id: null, clean: true, findings: [], draft: { type: 'sql' } })[0].label)
            .toBe('query');
    });

    it('normalizes a projection_author draft through the shared envelope branch', () => {
        // Registration in BOTH the AiToolName union and this adapter is what stops a new tool from
        // silently rendering as "no suggestion" — so assert the candidate is really produced.
        const projections = [{ datasetId: 'cdr', sourceCol: 'caller_id', targetCol: 'callee_id' }];
        const drafts = adaptToolResult('projection_author', {
            kind: 'link-analysis-view', id: 'cdr', clean: true, findings: [],
            draft: { query: { projections } },
        });
        expect(drafts).toHaveLength(1);
        expect(drafts[0].label).toBe('cdr');
        expect(drafts[0].config).toEqual({ query: { projections } });
    });

    it('turns every suggest_expectations suggestion into its own candidate', () => {
        const drafts = adaptToolResult('suggest_expectations', {
            table: 'cdr',
            column: 'cost_usd',
            profile: { rows: 12480, nulls: 0, numeric: true },
            suggestions: [
                { name: 'cdr_cost_usd_not_null', kind: 'non_null', description: 'never null' },
                { name: 'cdr_cost_usd_range', kind: 'range', description: 'observed bounds' },
            ],
        });

        expect(drafts.map((d) => d.label)).toEqual(['cdr_cost_usd_not_null', 'cdr_cost_usd_range']);
        // Derived by deterministic SQL from real data — there is nothing to repair.
        expect(drafts.every((d) => d.clean && d.findings.length === 0)).toBe(true);
        expect(drafts[0].note).toContain('12,480 rows');
    });

    it('orders kpi_report_builder widgets as prerequisites of the dashboard', () => {
        const drafts = adaptToolResult('kpi_report_builder', {
            kind: 'dashboard',
            id: 'revenue',
            clean: true,
            findings: [],
            draft: { title: 'Revenue', tiles: [] },
            widgets: [
                { id: 'revenue_kpi_1', draft: { kind: 'kpi' } },
                { id: 'revenue_kpi_2', draft: { kind: 'kpi' } },
            ],
        });

        expect(drafts).toHaveLength(1);
        // The dashboard tiles them, so they must exist first — the ordering is load-bearing.
        expect(drafts[0].prerequisites?.map((p) => p.label)).toEqual(['revenue_kpi_1', 'revenue_kpi_2']);
    });

    it('reports the simulation in the pipeline_author note', () => {
        const drafts = adaptToolResult('pipeline_author', {
            flow: { name: 'dedup_msisdn', nodes: [] },
            nodes: [{ id: 'n1' }, { id: 'n2' }],
            simulated: true,
        });

        expect(drafts[0].label).toBe('dedup_msisdn');
        expect(drafts[0].note).toBe('2 nodes · dry-run simulated');
    });

    it('yields no candidates for an unrecognized shape rather than a broken card', () => {
        expect(adaptToolResult('component_draft', { unexpected: true })).toEqual([]);
        expect(adaptToolResult('suggest_expectations', null)).toEqual([]);
        expect(adaptToolResult('pipeline_author', 'nope')).toEqual([]);
    });
});

describe('configDiff', () => {
    it('classifies added, changed, removed and unchanged fields', () => {
        const rows = configDiff(
            { name: 'a', severity: 'MINOR', gone: 'x' },
            { name: 'a', severity: 'MAJOR', added: 'y' },
        );
        const byPath = Object.fromEntries(rows.map((r) => [r.path, r.change]));

        expect(byPath).toEqual({ name: 'same', severity: 'changed', gone: 'removed', added: 'added' });
    });

    it('treats a null current as a create — everything is added', () => {
        const rows = configDiff(null, { name: 'a', kind: 'range' });
        expect(rows.every((r) => r.change === 'added')).toBe(true);
    });

    it('flattens nested objects to dotted paths and compares arrays whole', () => {
        const rows = configDiff({ target: { type: 'pipeline' }, cols: ['a'] }, { target: { type: 'dataset' }, cols: ['a', 'b'] });
        const byPath = Object.fromEntries(rows.map((r) => [r.path, r.change]));

        expect(byPath['target.type']).toBe('changed');
        expect(byPath['cols']).toBe('changed');
        expect(rows.find((r) => r.path === 'cols')?.after).toBe('["a","b"]');
    });

    it('renders an absent value as a dash rather than "undefined"', () => {
        const removed = configDiff({ gone: 'x' }, {}).find((r) => r.path === 'gone');
        expect(removed?.after).toBe('—');
    });
});
