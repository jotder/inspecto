import { ColDef } from 'ag-grid-community';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { VizRenderOptions } from './viz-types';

/** Aggregations a measure column id is prefixed with (`channelMeasure` → `<agg>_<field>`). */
const AGG_PREFIX = /^(count_distinct|sum|avg|min|max|count)_(.+)$/;

/** Tokens that read wrong once sentence-cased — units, acronyms and domain abbreviations. */
const TOKENS: Record<string, string> = {
    sar: '(SAR)',
    pct: '(%)',
    ms: '(ms)',
    id: 'ID',
    kpi: 'KPI',
    kpis: 'KPIs',
    tmf: 'TM Forum',
    rfp: 'RFP',
    rag: 'RAG',
    gl: 'GL',
    p95: 'p95',
    sla: 'SLA',
    msisdn: 'MSISDN',
    imsi: 'IMSI',
    rtsc: 'RTSC',
};

/** Aggregation names as a reader says them; `max` of a value we already know is one row reads as the value. */
const AGG_LABEL: Record<string, string> = {
    sum: 'total',
    avg: 'average',
    min: 'min',
    max: 'max',
    count: 'count',
    count_distinct: 'distinct',
};

function words(field: string): string {
    const parts = field.split('_').filter(Boolean);
    return parts
        .map((p, i) => {
            const t = TOKENS[p.toLowerCase()];
            if (t) return t;
            return i === 0 ? p.charAt(0).toUpperCase() + p.slice(1) : p;
        })
        .join(' ');
}

/**
 * Header text for a result column when the widget names none: `outstanding_sar` → "Outstanding (SAR)",
 * `sum_breaks` → "Breaks (total)", `tmf_dimension` → "TM Forum dimension". A bare `count` (the
 * field-less measure) reads "Count".
 */
export function humanizeColumn(column: string): string {
    if (column === 'count') return 'Count';
    const m = AGG_PREFIX.exec(column);
    if (!m) return words(column);
    return `${words(m[2])} (${AGG_LABEL[m[1]]})`;
}

/** Columns rendered as status badges: the widget's `badgeColumns`, else any column named status / severity / rag. */
export function isBadgeColumn(column: string, opts?: VizRenderOptions): boolean {
    if (opts?.badgeColumns) return opts.badgeColumns.includes(column);
    return /(^|_)(status|severity|rag)$/i.test(column);
}

function escapeHtml(s: string): string {
    return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

/** ag-Grid column definitions for the table plugin: readable headers + status badges. */
export function tableColDefs(columns: string[], opts?: VizRenderOptions): ColDef[] {
    return columns.map((field) => {
        const def: ColDef = { field, headerName: opts?.columnLabels?.[field] ?? humanizeColumn(field) };
        if (isBadgeColumn(field, opts)) {
            def.cellRenderer = (p: { value: unknown }) => {
                if (p.value == null || p.value === '') return '';
                const text = escapeHtml(String(p.value));
                return statusBadgeHtml(text, text);
            };
        }
        return def;
    });
}
