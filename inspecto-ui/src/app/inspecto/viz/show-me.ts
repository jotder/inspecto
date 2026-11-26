import { isIdColumn, ResultColumn, ResultSet } from './result-set';
import { allViz } from './viz-registry';
import { ChannelValue, ControlValues, FieldRole, VizField, VizFit, VizPlugin, VizRenderOptions } from './viz-types';
import { anomalyThreshold, OVERLAY_SUFFIX, OVERLAY_VIZ_TYPES, OverlaySummary } from './chart-overlays';
import { MARKER_VIZ_TYPES, MarkerKpi } from './chart-markers';
import { formatNumber } from './number-format';
import { COMPARE_LABEL, COMPARE_VIZ_TYPES, CompareKind } from './compare';

/**
 * "Show Me" — rank the registered plugins for a field set (à la Tableau/Superset), and auto-assign fields to
 * a plugin's channels. Pure functions over {@link VizField}s; the explore UI calls these to seed the builder.
 */

interface FieldCounts {
    dim: number;
    measure: number;
    temporal: number;
    /** The LOWEST known cardinality among the dimension fields — the best category a chart could be drawn
     *  over, which is the one {@link autoAssignChannels} reaches for (0 when unknown/no dimensions). One
     *  per-row identifier or near-unique date must not make every category chart look unfit. */
    minDimCardinality: number;
}

function counts(columns: ResultColumn[]): FieldCounts {
    const dims = columns.filter((f) => f.role === 'dimension');
    const known = dims.map((f) => f.cardinality).filter((n): n is number => n != null);
    return {
        dim: dims.length,
        measure: columns.filter((f) => f.role === 'measure').length,
        temporal: columns.filter((f) => f.role === 'temporal').length,
        minDimCardinality: known.length ? Math.min(...known) : 0,
    };
}

/** Suitability score, or `-1` to disqualify (a hard `fit` constraint is violated). Higher = better fit. */
function fitScore(fit: VizFit, c: FieldCounts): number {
    if (fit.minMeasure != null && c.measure < fit.minMeasure) return -1;
    if (fit.minDim != null && c.dim < fit.minDim) return -1;
    if (fit.temporal === true && c.temporal === 0) return -1;

    let score = 0;
    if (fit.temporal === true && c.temporal > 0) score += 3;
    if (fit.temporal === false && c.temporal === 0) score += 1;
    if (fit.maxMeasure != null && c.measure > fit.maxMeasure) score -= 1; // tolerated, penalised
    if (fit.maxDim != null && c.dim > fit.maxDim) score -= 1;
    // A declared ceiling is a real preference signal either way: reward staying comfortably under it (the
    // classic "pie shines with a few slices" case) so it's genuinely preferred, not just tied, over a plugin
    // with no ceiling at all — and only mildly penalise (never enough to disqualify) crossing it.
    if (fit.maxCardinality != null) score += c.minDimCardinality > fit.maxCardinality ? -1 : 1;
    // Reward plugins whose measure appetite matches what's available.
    if (fit.minMeasure != null) score += Math.min(c.measure, fit.maxMeasure ?? c.measure);
    return score;
}

/** The plugins that fit the result set, best first. Accepts a full {@link ResultSet} (the query editor's
 *  described output) or, as a shorthand, a raw column/field list (the widget builder's dataset fields —
 *  a `VizField[]` is structurally a `ResultColumn[]`). View-bound plugins (`meta.viewKind`) are excluded:
 *  they bind a saved investigation view, not fields, so no result set can recommend them. */
export function recommend(input: ResultSet | ResultColumn[] | VizField[]): VizPlugin[] {
    const c = counts(Array.isArray(input) ? input : input.columns);
    return allViz()
        .filter((p) => !p.meta.viewKind)
        .map((p) => ({ p, score: fitScore(p.meta.fit, c) }))
        .filter((x) => x.score >= 0)
        .sort((a, b) => b.score - a.score)
        .map((x) => x.p);
}

/** Category ceiling for a plugin that declares none — past ~30 bars/points a category axis stops reading. */
const DEFAULT_MAX_CATEGORIES = 30;

/**
 * A dimension that identifies rows rather than grouping them: named like one (`id`, `*_id`), or near-unique
 * per row (a `MATCH_ID`, a free-text `DATE`) with more values than the plugin can draw as categories.
 * Charting by it gives one bar/slice/point per row.
 */
function isIdentifierLike(f: VizField, ceiling: number, rowCount?: number): boolean {
    if (isIdColumn(f.name)) return true;
    return (
        rowCount != null &&
        rowCount > 0 &&
        f.cardinality != null &&
        f.cardinality > ceiling &&
        f.cardinality >= 0.9 * rowCount
    );
}

/**
 * Greedily map fields onto a plugin's channels: each control takes the next unused field whose role it accepts
 * (acceptRoles are tried in declared order, so an `x` that accepts `['temporal','dimension']` prefers time).
 * Measure channels default to `sum`.
 *
 * Category (dimension) channels skip identifier-like columns and prefer a dimension within the plugin's
 * `maxCardinality` (else {@link DEFAULT_MAX_CATEGORIES}) — declared order first, then the lowest-cardinality
 * rest. An optional break-down channel (the non-required `series` of bar/line/area) is left EMPTY: filling it with a
 * leftover dimension is how a 70-entry legend happens. `rowCount` (the loaded rows) enables the
 * near-unique check; without it only the name rule applies.
 */
export function autoAssignChannels(plugin: VizPlugin, fields: VizField[], rowCount?: number): ControlValues {
    const ceiling = plugin.meta.fit.maxCardinality ?? DEFAULT_MAX_CATEGORIES;
    const categories = fields.filter((f) => f.role === 'dimension' && !isIdentifierLike(f, ceiling, rowCount));
    const fitting = categories.filter((f) => f.cardinality == null || f.cardinality <= ceiling);
    const overflow = categories
        .filter((f) => !fitting.includes(f))
        .sort((a, b) => (a.cardinality ?? 0) - (b.cardinality ?? 0));
    const pools: Record<FieldRole, VizField[]> = {
        temporal: fields.filter((f) => f.role === 'temporal'),
        measure: fields.filter((f) => f.role === 'measure'),
        dimension: [...fitting, ...overflow],
    };
    const used = new Set<string>();
    const values: ControlValues = {};

    for (const control of plugin.controls) {
        // An optional break-down (bar's `series`, treemap's `subgroup`) stays empty.
        if ((control.channel === 'series' || control.channel === 'subgroup') && !control.required) continue;
        // KPI-DEFAULT-COMPARE-1: a KPI's prior period is never guessed — an unrelated measure fakes a delta badge.
        if (control.channel === 'compare' && !control.required) continue;
        const pick = takeNext(control.acceptRoles, pools, used);
        if (!pick) continue;
        const cv: ChannelValue = control.isMeasure ? { field: pick.name, agg: 'sum' } : { field: pick.name };
        values[control.channel] = [cv];
    }
    return values;
}

function takeNext(roles: FieldRole[], pools: Record<FieldRole, VizField[]>, used: Set<string>): VizField | undefined {
    for (const role of roles) {
        const field = pools[role].find((f) => !used.has(f.name));
        if (field) {
            used.add(field.name);
            return field;
        }
    }
    return undefined;
}

// ── BI-INSIGHTS-1 I-20: insight-aware Show-Me ─────────────────────────────────────────────────────────────────────

/** One overlay Show-Me offers for the Widget being built. `options` is the Widget's options with it added; nothing is
 *  applied until the user clicks (a suggestion is only a suggestion). */
export interface OverlaySuggestion<O extends VizRenderOptions = VizRenderOptions> {
    id: 'forecast' | 'anomaly' | 'target' | 'rag' | 'compare-previous' | 'compare-lastYear';
    text: string;
    action: string;
    options: O;
}

/** What {@link suggestOverlays} reads. `probe` is the overlay summary of the Widget's own `/bi/query` statement run with
 *  `overlay: {forecast, anomaly}` (the I-11 / I-7 route); `probeRows` its rows. `kpi` is the bound KPI's definition. */
export interface OverlaySuggestionInput<O extends VizRenderOptions = VizRenderOptions> {
    vizType: string;
    controls: ControlValues;
    options: O;
    probe?: readonly OverlaySummary[] | null;
    probeRows?: readonly Record<string, unknown>[];
    kpi?: MarkerKpi | null;
    /** The Widget's result rows (or the probe's: the same statement), read for the compare rule; default `probeRows`. */
    rows?: readonly Record<string, unknown>[];
}

/** A year back, less a week's slack, so a weekly series whose buckets do not land on the same weekday still counts. */
const YEAR_MS = 358 * 86_400_000;

/** The distinct, valid bucket times of the date axis, ascending. */
function bucketTimes(rows: readonly Record<string, unknown>[], field: string): number[] {
    const out = new Set<number>();
    for (const r of rows) {
        const v = r[field];
        const t =
            v instanceof Date
                ? v.getTime()
                : typeof v === 'string' || typeof v === 'number'
                  ? Date.parse(String(v))
                  : NaN;
        if (Number.isFinite(t)) out.add(t);
    }
    return [...out].sort((a, b) => a - b);
}

/**
 * BI-INSIGHTS-1 I-1 in Show-Me: which comparisons the series has the history for. Only a line / bar / area over a grained
 * date with no break-down (`series`) and no comparison on yet (the cases `overlayEligible` allows). The rule:
 * - **same period last year** — the first bucket is at least a year (less a week) before the last, so the last bucket
 *   has a year-ago bucket to compare with;
 * - **previous period** — otherwise, when there are at least 3 buckets (a change between the last two reads as a trend
 *   only with one more before it).
 * At most one is offered: last year when the history allows it, since it removes the season the previous period keeps.
 */
export function compareSuggestion(
    vizType: string,
    controls: ControlValues,
    options: VizRenderOptions,
    rows: readonly Record<string, unknown>[],
): CompareKind | null {
    if (!(COMPARE_VIZ_TYPES as readonly string[]).includes(vizType) || options.compare?.kind) return null;
    if (!overlayEligible(vizType, controls, options) || controls['series']?.length) return null;
    const times = bucketTimes(rows, controls['x']![0].field);
    if (times.length >= 2 && times[times.length - 1] - times[0] >= YEAR_MS) return 'lastYear';
    return times.length >= 3 ? 'previous' : null;
}

/** The cycle a detected period names at a grain, in words — or `null` when it names none (no "seasonal" claim then). */
const CYCLE_WORD: Readonly<Record<string, Readonly<Record<number, string>>>> = {
    day: { 7: 'weekly' },
    week: { 52: 'yearly' },
    month: { 12: 'yearly' },
    quarter: { 4: 'yearly' },
    hour: { 24: 'daily' },
};

/** True when the Widget can ask the server for overlays: a line / bar / area with a Measure over a grained date, neither stacked nor
 *  value-sorted (the cases the overlay renderer draws, `chart-overlays.ts`). */
export function overlayEligible(vizType: string, controls: ControlValues, options: VizRenderOptions): boolean {
    const grain = controls['x']?.[0]?.grain;
    return (
        (OVERLAY_VIZ_TYPES as readonly string[]).includes(vizType) &&
        !!grain &&
        !!controls['y']?.length &&
        grain !== 'auto' &&
        !options.stacked &&
        !options.sort
    );
}

/**
 * The overlays worth offering for this Widget, in the order shown. Statistical honesty (plan §10): a forecast band is
 * offered only when the server found a seasonal cycle (`periodSource: detected`) AND had two seasons of history (no
 * `reason`); anomaly markers only when at least one point passes the default sensitivity; a target line / band only
 * when there is a target / bands to draw. An overlay already on the Widget is never offered again.
 */
export function suggestOverlays<O extends VizRenderOptions>(input: OverlaySuggestionInput<O>): OverlaySuggestion<O>[] {
    const { vizType, controls, options, probe, probeRows = [], kpi } = input;
    const out: OverlaySuggestion<O>[] = [];
    const compare = compareSuggestion(vizType, controls, options, input.rows ?? probeRows);
    if (compare) {
        const n = bucketTimes(input.rows ?? probeRows, controls['x']![0].field).length;
        out.push({
            id: compare === 'lastYear' ? 'compare-lastYear' : 'compare-previous',
            text:
                compare === 'lastYear'
                    ? `Over a year of history (${formatNumber(n)} points): each point can be read against the same period last year.`
                    : `${formatNumber(n)} points of history: the latest can be read against the period before.`,
            action: `Compare with the ${COMPARE_LABEL[compare]}`,
            options: { ...options, compare: { kind: compare } },
        });
    }
    if (overlayEligible(vizType, controls, options)) {
        const grain = controls['x']![0].grain!;
        const seasonal = (probe ?? []).find(
            (s) => s.overlay === 'forecast' && !s.reason && s.periodSource === 'detected' && s.period != null,
        );
        const cycle = seasonal ? CYCLE_WORD[grain]?.[seasonal.period!] : undefined;
        if (seasonal && !options.overlay?.forecast) {
            const pattern = cycle
                ? `${cycle[0].toUpperCase()}${cycle.slice(1)} seasonal pattern`
                : `A ${seasonal.period}-${grain} cycle`;
            out.push({
                id: 'forecast',
                text: `${pattern} found over ${seasonal.points} ${grain}s of history.`,
                action: 'Add a forecast band',
                options: { ...options, overlay: { ...options.overlay, forecast: {} } },
            });
        }
        const scored = (probe ?? []).filter((s) => s.overlay === 'anomaly' && !s.reason && (s.scored ?? 0) > 0);
        if (scored.length && !options.overlay?.anomaly) {
            const threshold = anomalyThreshold(undefined);
            const ids = new Set(scored.map((s) => s.of));
            let flagged = 0;
            for (const row of probeRows)
                for (const id of ids) {
                    const z = row[id + OVERLAY_SUFFIX.z];
                    if (typeof z === 'number' && Math.abs(z) >= threshold) flagged++;
                }
            if (flagged > 0)
                out.push({
                    id: 'anomaly',
                    text: `${formatNumber(flagged)} unusual ${flagged === 1 ? 'point' : 'points'} against the usual level.`,
                    action: 'Show anomaly markers',
                    options: { ...options, overlay: { ...options.overlay, anomaly: {} } },
                });
        }
    }
    if (MARKER_VIZ_TYPES.includes(vizType)) {
        const markers = options.markers ?? [];
        // The bound KPI's target first, as `resolveMarkers` draws it; else the Widget's own.
        const kpiTarget = options.kpi?.kpiId ? kpi?.target : undefined;
        const target = kpiTarget ?? options.kpi?.target;
        if (target != null && !markers.some((m) => m.kind === 'target')) {
            out.push({
                id: 'target',
                text: `${kpiTarget != null ? 'This KPI' : 'This Widget'} has a target of ${formatNumber(target)}.`,
                action: 'Add a target line',
                options: { ...options, markers: [...markers, { kind: 'target' }] },
            });
        }
        if (options.kpi?.kpiId && kpi?.bands && !markers.some((m) => m.kind === 'rag')) {
            out.push({
                id: 'rag',
                text: 'This KPI has green, amber and red bands.',
                action: 'Add the target bands',
                options: { ...options, markers: [...markers, { kind: 'rag' }] },
            });
        }
    }
    return out;
}
