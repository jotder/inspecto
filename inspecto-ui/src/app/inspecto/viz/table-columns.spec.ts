import { describe, expect, it } from 'vitest';
import { statusBadgeClasses, statusTone } from 'app/inspecto/components/status-badge.component';
import { humanizeColumn, isBadgeColumn, tableColDefs } from './table-columns';
import { followRowLinkOnEnter, RowLinkCell } from './row-link-cell.component';

describe('table columns — readable headers', () => {
    it('turns measure ids into "<Field> (<agg>)"', () => {
        expect(humanizeColumn('sum_breaks')).toBe('Breaks');
        expect(humanizeColumn('avg_detect_minutes')).toBe('Detect minutes (average)');
        expect(humanizeColumn('max_value')).toBe('Value (max)');
        expect(humanizeColumn('count_distinct_msisdn')).toBe('MSISDN (distinct)');
        expect(humanizeColumn('count')).toBe('Count');
    });

    it('sentence-cases dimensions and keeps units and acronyms', () => {
        expect(humanizeColumn('outstanding_sar')).toBe('Outstanding (SAR)');
        expect(humanizeColumn('tmf_dimension')).toBe('TM Forum dimension');
        expect(humanizeColumn('kpi_id')).toBe('KPI ID');
        expect(humanizeColumn('max_p95_latency_ms')).toBe('p95 latency (ms) (max)');
        expect(humanizeColumn('chain_stage')).toBe('Chain stage');
    });

    it('sentence-cases an all-caps column instead of shouting it', () => {
        expect(humanizeColumn('sum_FIRST_INGS_SCORE')).toBe('First ings score');
        expect(humanizeColumn('MATCH_ID')).toBe('Match ID');
        expect(humanizeColumn('PLAYER_OF_THE_MATCH')).toBe('Player of the match');
    });

    it('a widget columnLabels entry wins over the default', () => {
        const [a, b] = tableColDefs(['max_value', 'kpi'], { columnLabels: { max_value: 'Actual' } });
        expect(a.headerName).toBe('Actual');
        expect(b.headerName).toBe('KPI');
    });
});

describe('table columns — status badges', () => {
    it('badges status / severity / rag columns by default, nothing else', () => {
        expect(isBadgeColumn('status')).toBe(true);
        expect(isBadgeColumn('severity')).toBe(true);
        expect(isBadgeColumn('kpi_rag')).toBe(true);
        expect(isBadgeColumn('owner')).toBe(false);
        expect(isBadgeColumn('status_note')).toBe(false);
    });

    it('an explicit badgeColumns list replaces the default', () => {
        expect(isBadgeColumn('outcome', { badgeColumns: ['outcome'] })).toBe(true);
        expect(isBadgeColumn('status', { badgeColumns: ['outcome'] })).toBe(false);
    });

    it('renders a RAG value as a tone-coloured badge and escapes the text', () => {
        const [def] = tableColDefs(['status']);
        const render = def.cellRenderer as (p: { value: unknown }) => string;
        const green = render({ value: 'Green' });
        expect(green).toContain(statusBadgeClasses('success'));
        expect(green).toContain('>Green<');
        expect(render({ value: 'Red' })).toContain(statusBadgeClasses('error'));
        expect(render({ value: 'Amber' })).toContain(statusBadgeClasses('warning'));
        expect(render({ value: '<img src=x>' })).not.toContain('<img');
        expect(render({ value: null })).toBe('');
    });

    it('RAG and control outcomes map to the shared tones', () => {
        expect(statusTone('Green')).toBe('success');
        expect(statusTone('AMBER')).toBe('warning');
        expect(statusTone('red')).toBe('error');
        expect(statusTone('Pass')).toBe('success');
    });

    it('leakage / fraud case states map to tones (Open stays info)', () => {
        expect(statusTone('Open')).toBe('info');
        expect(statusTone('Investigating')).toBe('warning');
        expect(statusTone('Confirmed')).toBe('error');
        expect(statusTone('Recovered')).toBe('success');
        expect(statusTone('Closed - no loss')).toBe('success');
    });
});

describe('table columns — UIE-6 row link', () => {
    it('only the rowLink idField cell becomes a link, and Enter follows it', () => {
        const opts = { rowLink: { kind: 'reconciliation' as const, idField: 'recon_id' } };
        const [control, recon] = tableColDefs(['control', 'recon_id'], opts);
        expect(control.cellRenderer).toBeUndefined();
        expect(recon.cellRenderer).toBe(RowLinkCell);
        expect(recon.cellRendererParams).toEqual({ kind: 'reconciliation' });
        expect(recon.suppressKeyboardEvent).toBe(followRowLinkOnEnter);
    });

    it('a link wins over a status badge on the same column', () => {
        const [status] = tableColDefs(['status'], { rowLink: { kind: 'incident', idField: 'status' } });
        expect(status.cellRenderer).toBe(RowLinkCell);
    });

    it('a labelField carries the link and hides the id column', () => {
        const opts = { rowLink: { kind: 'reconciliation' as const, idField: 'recon_id', labelField: 'control' } };
        const defs = tableColDefs(['control', 'status', 'recon_id'], opts);
        expect(defs.map((d) => d.field)).toEqual(['control', 'status']);
        expect(defs[0].cellRenderer).toBe(RowLinkCell);
        expect(defs[0].cellRendererParams).toEqual({ kind: 'reconciliation', idField: 'recon_id' });
        expect(defs[0].suppressKeyboardEvent).toBe(followRowLinkOnEnter);
        expect(defs[1].cellRenderer).not.toBe(RowLinkCell);
    });

    it('a labelled link wins over a status badge on the label column', () => {
        const [status] = tableColDefs(['status', 'case_id'], {
            rowLink: { kind: 'case', idField: 'case_id', labelField: 'status' },
        });
        expect(status.cellRenderer).toBe(RowLinkCell);
    });

    it('a labelField the result lacks falls back to the id cell and keeps it shown', () => {
        const defs = tableColDefs(['recon_id'], {
            rowLink: { kind: 'reconciliation', idField: 'recon_id', labelField: 'control' },
        });
        expect(defs.map((d) => d.field)).toEqual(['recon_id']);
        expect(defs[0].cellRendererParams).toEqual({ kind: 'reconciliation' });
    });

    it('no rowLink, no link', () => {
        const [id] = tableColDefs(['recon_id']);
        expect(id.cellRenderer).toBeUndefined();
        expect(id.suppressKeyboardEvent).toBeUndefined();
    });
});
