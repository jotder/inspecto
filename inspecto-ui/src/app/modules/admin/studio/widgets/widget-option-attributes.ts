import { AttributeSpec } from 'app/inspecto/component-model';
import { CHART_PALETTES } from 'app/inspecto/theme/chart-tokens';

/**
 * The widget **advanced-options** attribute set (the cog dialog) — every knob is always visible but
 * **none is required** (`tier: 'required', required: false`), the case that motivated decoupling
 * validation from the visibility tier. A closed, curated set (no free-form styling); colors resolve only
 * from a named palette. Flat keys here; {@link WidgetOptionsDialog} re-nests them into `axis`/`legend`
 * on save. Mirrors `job-attributes.ts` (the schema-form pilot).
 */
export const WIDGET_OPTION_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'title',
        label: 'Title',
        type: 'string',
        tier: 'required',
        required: false,
        placeholder: "Defaults to the widget's name",
    },
    { key: 'subtitle', label: 'Subtitle', type: 'string', tier: 'required', required: false },
    { key: 'xTitle', label: 'X axis title', type: 'string', tier: 'required', required: false },
    { key: 'yTitle', label: 'Y axis title', type: 'string', tier: 'required', required: false },
    { key: 'y2Title', label: 'Right axis title (Combo)', type: 'string', tier: 'required', required: false },
    // Auto writes no `legend.show`, so the chart theme decides (a legend only for 2+ series, or a pie/donut's slices).
    {
        key: 'legendShow',
        label: 'Legend',
        type: 'select',
        tier: 'required',
        required: false,
        default: 'auto',
        options: [
            { value: 'auto', label: 'Auto (only when there are 2+ series)' },
            { value: 'show', label: 'Show' },
            { value: 'hide', label: 'Hide' },
        ],
    },
    {
        key: 'legendPosition',
        label: 'Legend position',
        type: 'select',
        tier: 'required',
        required: false,
        default: 'top',
        options: [
            { value: 'top', label: 'top' },
            { value: 'right', label: 'right' },
            { value: 'bottom', label: 'bottom' },
            { value: 'left', label: 'left' },
        ],
    },
    {
        key: 'palette',
        label: 'Color palette',
        type: 'select',
        tier: 'required',
        required: false,
        default: Object.keys(CHART_PALETTES)[0],
        options: Object.keys(CHART_PALETTES).map((p) => ({ value: p, label: p })),
    },
    {
        key: 'sort',
        label: 'Sort',
        type: 'select',
        tier: 'required',
        required: false,
        default: '',
        options: [
            { value: '', label: 'Unsorted' },
            { value: 'asc', label: 'Ascending' },
            { value: 'desc', label: 'Descending' },
        ],
    },
    { key: 'limit', label: 'Limit (top N)', type: 'number', tier: 'required', required: false, min: 1 },
    { key: 'stacked', label: 'Stack series', type: 'boolean', tier: 'required', required: false, default: false },
    {
        key: 'hideBlank',
        label: 'Hide blank categories',
        type: 'boolean',
        tier: 'required',
        required: false,
        default: false,
    },
    // UIE-4: how the widget's numbers read (KPI value, axes, tooltips, table cells).
    {
        key: 'numberStyle',
        label: 'Number style',
        type: 'select',
        tier: 'required',
        required: false,
        default: '',
        options: [
            { value: '', label: 'Number' },
            { value: 'currency', label: 'Currency' },
            { value: 'percent', label: 'Percent (value is already in points)' },
        ],
    },
    { key: 'currency', label: 'Currency code (e.g. SAR)', type: 'string', tier: 'required', required: false },
    { key: 'compact', label: 'Compact (7.7M)', type: 'boolean', tier: 'required', required: false, default: false },
    { key: 'decimals', label: 'Decimals', type: 'number', tier: 'required', required: false, min: 0 },
    // UIE-1: a KPI's target and which direction is good (UIE-8: the Gauge, KPI trend and Progress list read the same two; the Waterfall reads
    // the direction alone, to tone its increases and decreases).
    {
        key: 'kpiTarget',
        label: 'Target (KPI, KPI trend, Gauge, Progress list)',
        type: 'number',
        tier: 'required',
        required: false,
    },
    {
        key: 'kpiBetter',
        label: 'Better when (KPI, Gauge, Waterfall)',
        type: 'select',
        tier: 'required',
        required: false,
        default: 'higher',
        options: [
            { value: 'higher', label: 'Higher' },
            { value: 'lower', label: 'Lower' },
        ],
    },
    // Waterfall only: the opening step, the closing total bar, and the order of the change steps.
    {
        key: 'waterfallStart',
        label: 'Waterfall: opening step',
        type: 'string',
        tier: 'required',
        required: false,
        placeholder: 'The step whose value is the starting total, e.g. Billed revenue',
    },
    {
        key: 'waterfallShowTotal',
        label: 'Waterfall: show total bar',
        type: 'boolean',
        tier: 'required',
        required: false,
        default: true,
    },
    {
        key: 'waterfallTotalLabel',
        label: 'Waterfall: total bar label',
        type: 'string',
        tier: 'required',
        required: false,
        placeholder: 'Total',
    },
    {
        key: 'waterfallOrder',
        label: 'Waterfall: step order',
        type: 'select',
        tier: 'required',
        required: false,
        default: 'data',
        options: [
            { value: 'data', label: 'As queried (by step)' },
            { value: 'asc', label: 'Largest decrease first' },
            { value: 'desc', label: 'Largest increase first' },
        ],
    },
    // Combo only: the line measures on their own right axis.
    {
        key: 'comboSecondaryAxis',
        label: 'Combo: line on a right axis',
        type: 'boolean',
        tier: 'required',
        required: false,
        default: true,
    },
    // Heatmap only: how the cells are coloured (`options.heatmap`).
    {
        key: 'heatmapScale',
        label: 'Heatmap: colour scale',
        type: 'select',
        tier: 'required',
        required: false,
        default: 'sequential',
        options: [
            { value: 'sequential', label: 'Sequential (low → high)' },
            { value: 'diverging', label: 'Diverging (around a midpoint)' },
            { value: 'status', label: 'Status (Pass / Fail words, or on / off target)' },
        ],
    },
    {
        key: 'heatmapMidpoint',
        label: 'Heatmap: midpoint (diverging, default 0)',
        type: 'number',
        tier: 'required',
        required: false,
    },
    // UIE-6, table only: each row opens the operational object it describes. Both are needed for a link.
    {
        key: 'rowLinkKind',
        label: 'Table: rows open',
        type: 'select',
        tier: 'required',
        required: false,
        default: '',
        options: [
            { value: '', label: 'Nothing' },
            { value: 'case', label: 'Case' },
            { value: 'incident', label: 'Incident' },
            { value: 'reconciliation', label: 'Reconciliation' },
        ],
    },
    {
        key: 'rowLinkIdField',
        label: 'Table: column holding the id',
        type: 'string',
        tier: 'required',
        required: false,
        placeholder: 'A dimension of this table, e.g. reconciliation_id',
    },
    // ── Per-Visualization-Type options (kpi-trend / progress-list / treemap / combo) ─────────────────────────────
    // Shown only for the vizType named in WIDGET_OPTION_VIZ_TYPES below. Blank or the default writes nothing.
    {
        key: 'trendCompareBack',
        label: 'KPI trend: compare with the point this many steps back',
        type: 'number',
        tier: 'required',
        required: false,
        min: 1,
        placeholder: '1 (the previous point)',
    },
    {
        key: 'progressMax',
        label: 'Progress list: value of a full bar',
        type: 'number',
        tier: 'required',
        required: false,
        placeholder: 'The largest value shown',
    },
    {
        key: 'treemapLimit',
        label: 'Treemap: groups before the rest fold into "Other"',
        type: 'number',
        tier: 'required',
        required: false,
        min: 2,
        placeholder: '20',
    },
    // Combo: the right-axis number format (`options.format2`) — the same four fields as the number format above.
    {
        key: 'format2Style',
        label: 'Right axis: number style',
        type: 'select',
        tier: 'required',
        required: false,
        default: '',
        group: 'Right axis format',
        options: [
            { value: '', label: 'Number' },
            { value: 'currency', label: 'Currency' },
            { value: 'percent', label: 'Percent (value is already in points)' },
        ],
    },
    {
        key: 'format2Currency',
        label: 'Right axis: currency code (e.g. SAR)',
        type: 'string',
        tier: 'required',
        required: false,
        group: 'Right axis format',
    },
    {
        key: 'format2Compact',
        label: 'Right axis: compact (7.7M)',
        type: 'boolean',
        tier: 'required',
        required: false,
        default: false,
        group: 'Right axis format',
    },
    {
        key: 'format2Decimals',
        label: 'Right axis: decimals',
        type: 'number',
        tier: 'required',
        required: false,
        min: 0,
        group: 'Right axis format',
    },
    // Gauge: the scale the arc spans (`options.gauge`); a blank end keeps its default (0 / 100) and writes nothing.
    { key: 'gaugeMin', label: 'Gauge: minimum', type: 'number', tier: 'required', required: false, placeholder: '0' },
    { key: 'gaugeMax', label: 'Gauge: maximum', type: 'number', tier: 'required', required: false, placeholder: '100' },
    // UIE-6: optional readable column carrying the link instead of the id; the id column is then hidden (still queried).
    {
        key: 'rowLinkLabelField',
        label: 'Table: link text column',
        type: 'string',
        tier: 'required',
        required: false,
        placeholder: 'Optional readable dimension, e.g. control — hides the id column',
    },
];

/**
 * Options shown only for one Visualization Type (flat key → vizType). The dialog drops the others from its form,
 * and a dropped field leaves its stored value untouched.
 */
export const WIDGET_OPTION_VIZ_TYPES: Record<string, string> = {
    trendCompareBack: 'kpi-trend',
    progressMax: 'progress-list',
    treemapLimit: 'treemap',
    format2Style: 'combo',
    format2Currency: 'combo',
    format2Compact: 'combo',
    format2Decimals: 'combo',
    gaugeMin: 'gauge',
    gaugeMax: 'gauge',
};
// ── end per-Visualization-Type options ──────────────────────────────────────────────────────────────────────
