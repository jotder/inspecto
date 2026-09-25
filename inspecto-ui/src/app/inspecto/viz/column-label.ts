/**
 * Readable text for a result column id — shared by the table plugin's headers and the chart plugins' series
 * (legend) labels. Pure: no Angular, no ag-Grid, so the data-only plugin helpers can use it.
 */

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
    // An all-caps column (`FIRST_INGS_SCORE`, common in warehouse-style stores) is shouted, not cased on
    // purpose — sentence-case it like any other; acronyms still come back through TOKENS.
    const cased = /[a-z]/.test(field) ? field : field.toLowerCase();
    const parts = cased.split('_').filter(Boolean);
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
 * field-less measure) reads "Count". An all-caps id reads the same: `sum_FIRST_INGS_SCORE` → "First ings
 * score (total)", `MATCH_ID` → "Match ID".
 */
export function humanizeColumn(column: string): string {
    if (column === 'count') return 'Count';
    const m = AGG_PREFIX.exec(column);
    if (!m) return words(column);
    return `${words(m[2])} (${AGG_LABEL[m[1]]})`;
}
