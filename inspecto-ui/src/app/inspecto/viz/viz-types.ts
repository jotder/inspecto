import { NumberFormat } from './number-format';
import { ColumnType, ConditionGroup } from 'app/inspecto/query';

/**
 * The visualization seam — a framework-agnostic `VizPlugin` registry that is the **first `ComponentKind`
 * slice** of the unified component model (a widget is a `kind:'widget'` component; each plugin is a widget
 * sub-type). Pure data + pure functions, no Angular — the Angular `viz-render` host resolves `render` keys to
 * components. Mirrors the `query/` core's split (types + compiler + in-browser run). See
 * docs/superpower/report-builder-design.md.
 */

/** A column's analytic role — the vocabulary the field-mapper + Show-Me recommender work in. */
export type FieldRole = 'dimension' | 'measure' | 'temporal';

/** A dataset column as the viz layer sees it (structurally compatible with a Studio `DatasetColumn`). */
export interface VizField {
    name: string;
    type: ColumnType;
    role: FieldRole;
    label?: string;
    /** Distinct-value count hint, when known — feeds Show-Me's cardinality-aware scoring. */
    cardinality?: number;
    /** A dataset **named measure** — this field is a ready-made aggregate SQL expression, not a column;
     *  channels carrying it use the expression verbatim and skip the aggregation picker. */
    expression?: string;
}

/** Aggregation a measure channel applies to its column (offline: compiled to AlaSQL aggregate fns). */
export type Aggregation = 'sum' | 'avg' | 'min' | 'max' | 'count' | 'countDistinct';

/** One measure in a {@link QuerySpec} — a stable id, a compiled SQL expression, and a display label. */
export interface QueryMeasure {
    id: string;
    expression: string;
    label: string;
    /** Structured origin (set by `buildMeasure`) — what the server path sends as a validated `{agg, field}`
     *  pair (M2; the wire never carries SQL text). Absent on named-measure expressions, which therefore
     *  only run offline. `field` is unset for `count`. */
    agg?: Aggregation;
    field?: string;
}

export type SortDir = 'asc' | 'desc';

/**
 * The structured query a chart emits — compiled to AlaSQL now (offline) and DuckDB later via the same shape.
 * Distinct from the Query Core `QueryModel` (projection + filter only); this adds group-by + aggregate measures.
 */
export interface QuerySpec {
    datasetId: string;
    sourceName: string;
    groupBy: string[];
    /** Temporal group-by columns → the bucket they group into. The one source of truth for bucketing:
     *  offline it drives `bucketSpecRows`, live it travels in the `/bi/query` body as `grains`. */
    grains?: Record<string, TimeGrain>;
    measures: QueryMeasure[];
    filters?: ConditionGroup | null;
    orderBy?: { field: string; dir: SortDir }[];
    limit?: number | null;
}

/** The channels a plugin can map fields onto (Tableau-style). */
export type ChannelId = 'x' | 'y' | 'series' | 'size' | 'color' | 'value' | 'compare';

/** A field-mapper control: one channel, the roles it accepts, and whether it's multi/required. */
export interface ControlSpec {
    channel: ChannelId;
    label: string;
    acceptRoles: FieldRole[];
    /** A measure channel needs an aggregation picker; a dimension/temporal channel does not. */
    isMeasure?: boolean;
    multiple?: boolean;
    required?: boolean;
}

/** Time bucketing applied to a temporal channel before grouping (offline: rows are pre-bucketed;
 *  server-side this compiles to `DATE_TRUNC` later). `auto`/absent ⇒ raw values. */
export type TimeGrain = 'auto' | 'day' | 'week' | 'month';

/** One channel's assignment: the field name + (for measure channels) the aggregation. */
export interface ChannelValue {
    field: string;
    agg?: Aggregation;
    /** Temporal channels only — the time bucket to group into. */
    grain?: TimeGrain;
    /** Named-measure fields only — the measure's aggregate SQL, used verbatim instead of `agg(field)`. */
    expression?: string;
}

/** The current field-mapper state: channel → assignment(s). */
export type ControlValues = Partial<Record<ChannelId, ChannelValue[]>>;

/** How the render host should draw this plugin's output. `chartType` also carries the two Chart.js-native
 *  approximations (`bubble` — true, `gauge` — a doughnut styled as a half-circle by `VizRenderComponent`). */
export type VizRender =
    | { kind: 'chartjs'; chartType: string }
    | { kind: 'aggrid' }
    | { kind: 'component'; componentKey: string }
    | { kind: 'g6' };

/** Suitability hints used by the Show-Me recommender to rank plugins for a field set. */
export interface VizFit {
    minDim?: number;
    maxDim?: number;
    minMeasure?: number;
    maxMeasure?: number;
    /** Requires (true) or forbids (false) a temporal field; omit if indifferent. */
    temporal?: boolean;
    /** Soft ceiling on a dimension's cardinality (e.g. pie ≤8 slices) — exceeding it penalises, not
     *  disqualifies, so a plain `table` (no such ceiling) naturally rises to the top for high-cardinality data. */
    maxCardinality?: number;
}

export interface VizMeta {
    type: string;
    label: string;
    icon: string;
    fit: VizFit;
    /** A **view-bound** plugin renders a saved investigation view (a Component of this kind, e.g.
     *  `geo-map-view`) instead of a dataset + channel mapping. Such plugins have no controls, skip the
     *  query pipeline entirely (the widget's binding is a `viewId`), and are excluded from Show-Me. */
    viewKind?: string;
}

/** A Chart.js-ish dataset the render host feeds to `<inspecto-chart>` (kept loose to avoid a chart.js dep here). */
export interface VizSeries {
    label: string;
    data: number[];
    [k: string]: unknown;
}

/**
 * The render-affecting subset of a Studio Widget's advanced options — kept here (not in Studio's
 * `WidgetOptions`) because {@link VizRenderComponent} is shared/core and must not import a feature type.
 * `WidgetOptions` extends this with the caption-only `title`/`subtitle` fields.
 */
export interface VizRenderOptions {
    axis?: { xTitle?: string; yTitle?: string };
    legend?: { show?: boolean; position?: 'top' | 'right' | 'bottom' | 'left' };
    /** A named palette key resolved by the render host (`app/inspecto/theme/chart-tokens`). */
    palette?: string;
    /** Order categories by their value (Chart.js render kinds only). */
    sort?: SortDir;
    /** Keep only the first N categories after sorting — a "top N" trim. */
    limit?: number;
    /** Stack series (bar/area only). */
    /** Drop blank (NULL/empty) categories from a chart — e.g. a leaderboard over a column some rows leave empty. */
    hideBlank?: boolean;
    stacked?: boolean;
    /** Table only: header text per result column (`max_value` → 'Value'). Unlisted columns get
     *  {@link humanizeColumn}'s default. */
    columnLabels?: Record<string, string>;
    /** Table only: columns rendered as status badges. Default: columns named status / severity / rag. */
    badgeColumns?: string[];
    /** UIE-1, KPI and (UIE-8) Gauge: the target, and which direction is good. Both state on / off target in words and tone;
     *  the KPI colours its delta the same way, the Gauge draws good / bad zones. `better` defaults to `higher`. */
    kpi?: { target?: number; better?: 'higher' | 'lower' };
    /** UIE-4: how this widget's numbers read — KPI value, chart axes and tooltips, and every table column not named in
     *  {@link columnFormats}. Absent: grouped, up to two decimals (axes compact). */
    format?: NumberFormat;
    /** UIE-4, table only: a per-column format, keyed by result column id (`sum_exposure_sar`). */
    columnFormats?: Record<string, NumberFormat>;
    /** UIE-6, table only: the operational object each row describes. The `idField` cell becomes a link to it; a row
     *  with no id stays plain text. `idField` must be a result column (a chosen dimension). */
    rowLink?: RowLink;
}

/** UIE-6: the operational objects a table row can open — each has a detail route taking its id. */
export type RowLinkKind = 'case' | 'incident' | 'reconciliation';

export interface RowLink {
    kind: RowLinkKind;
    idField: string;
}

/** The render-ready props a plugin produces from result rows (labels + series, or raw rows for the table). */
export interface VizProps {
    labels: string[];
    series: VizSeries[];
    /** For the table plugin: the raw result rows + column order. */
    rows?: Record<string, unknown>[];
    columns?: string[];
    /** For KPI: the single headline value (+ optional comparison). */
    value?: number;
    /** UIE-1, KPI: the prior-period value from the optional `compare` channel — the delta's baseline. */
    compare?: number;
}

/**
 * A visualization plugin — config-path first (controls → query → props) with an optional component escape
 * hatch (`render.kind:'component'`). The generalization target the adoption plan names: registering these is
 * the `widget` ComponentKind's job.
 */
export interface VizPlugin {
    meta: VizMeta;
    controls: ControlSpec[];
    /** Build the structured query from the field mapping (caller supplies dataset id/source). */
    buildQuery(
        values: ControlValues,
        ctx: { datasetId: string; sourceName: string; filters?: ConditionGroup | null },
    ): QuerySpec;
    /** Shape result rows into render props for this plugin. */
    transformProps(rows: Record<string, unknown>[], values: ControlValues): VizProps;
    render: VizRender;
}
