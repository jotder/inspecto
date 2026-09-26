import {
    StatusTone,
    statusBadgeClasses,
    statusSeverity,
    statusTone,
} from 'app/inspecto/components/status-badge.component';
import { Better } from './target-status';

/**
 * The `heatmap` Visualization Type's pure core: pivot grouped rows into a rows × columns matrix, and colour a cell
 * on one of three scales. Framework-free.
 *
 * - `sequential` (default) — one hue, light → strong, from the low to the high value.
 * - `diverging` — two hues around a midpoint (default 0), for a variance-type measure: below it the warn hue, above
 *   it the primary hue, the midpoint itself blank.
 * - `status` — a cell holding a status word (Pass / Fail / Amber …) takes that word's status tone, the same classes
 *   the status badge uses. A NUMERIC cell under `status` is judged against `options.kpi.target` / `better` (on target
 *   = success, off = error) — the RAG matrix for a measure.
 *
 * Colours are `--gamma-*` tokens (`rgba(var(--gamma-primary-rgb), a)`), so the ramp resolves in both themes; the
 * value is always printed (or in the cell's accessible name), so colour is never the only signal.
 */

export type HeatmapScale = 'sequential' | 'diverging' | 'status';

/** `options.heatmap` on a Widget. */
export interface HeatmapOptions {
    scale?: HeatmapScale;
    /** `diverging` only: the neutral value. Default 0. */
    midpoint?: number;
}

/** One cell's value: a number, a status word, or `null` when the source had no row for that pair. */
export type HeatmapValue = number | string | null;

/** The pivoted matrix: `cells[r][c]` is the value at `rows[r]` × `columns[c]`. */
export interface HeatmapMatrix {
    rows: string[];
    columns: string[];
    cells: HeatmapValue[][];
}

const byLabel = new Intl.Collator('en', { numeric: true, sensitivity: 'base' });

function str(v: unknown): string {
    return v == null ? '' : String(v);
}

/** A result value as a cell value: a finite number (or numeric string) stays a number, text stays text. */
function cellValue(v: unknown): HeatmapValue {
    if (v == null || v === '') return null;
    if (typeof v === 'number') return Number.isFinite(v) ? v : null;
    if (typeof v === 'bigint') return Number(v);
    const n = Number(v);
    return typeof v === 'string' && v.trim() !== '' && Number.isFinite(n) ? n : String(v);
}

/**
 * Pivot grouped rows (`rowField`, `columnField`, `valueKey`) into a matrix. Row and column labels are sorted by label
 * (numeric-aware, so `2` < `10` and ISO dates read in calendar order). A pair with no source row is `null` — an empty
 * cell, never a zero nobody measured. With `reduce`, several source rows for one pair fold into one value (the status
 * scale's worst-of, {@link worstCell}); without it the last row wins.
 */
export function pivotHeatmap(
    rows: readonly Record<string, unknown>[],
    rowField: string,
    columnField: string,
    valueKey: string,
    reduce?: (a: HeatmapValue, b: HeatmapValue) => HeatmapValue,
): HeatmapMatrix {
    const rowSet = new Set<string>();
    const colSet = new Set<string>();
    const at = new Map<string, HeatmapValue>();
    for (const r of rows) {
        const rk = str(r[rowField]);
        const ck = str(r[columnField]);
        rowSet.add(rk);
        colSet.add(ck);
        const key = JSON.stringify([rk, ck]);
        const v = cellValue(r[valueKey]);
        at.set(key, reduce && at.has(key) ? reduce(at.get(key)!, v) : v);
    }
    const sorted = (s: Set<string>): string[] => [...s].sort(byLabel.compare);
    const rowLabels = sorted(rowSet);
    const columns = sorted(colSet);
    return {
        rows: rowLabels,
        columns,
        cells: rowLabels.map((rk) => columns.map((ck) => at.get(JSON.stringify([rk, ck])) ?? null)),
    };
}

/**
 * Fold two values of one cell into the one the `status` scale shows: of two status words the WORSE by the shared tone
 * severity ({@link statusSeverity}: Fail over Warning over Pass, Red over Amber over Green — never alphabetical), a tie
 * broken by label so the result is independent of row order; of two numbers the `agg` (`max` / `min`) the Widget
 * picked; an empty value never wins over a real one.
 */
export function worstCell(a: HeatmapValue, b: HeatmapValue, agg: 'max' | 'min'): HeatmapValue {
    if (a == null) return b;
    if (b == null) return a;
    if (typeof a === 'number' && typeof b === 'number') return agg === 'max' ? Math.max(a, b) : Math.min(a, b);
    const sa = statusSeverity(String(a));
    const sb = statusSeverity(String(b));
    if (sa !== sb) return sa > sb ? a : b;
    return byLabel.compare(String(a), String(b)) <= 0 ? a : b;
}

/** The numeric extent of a matrix, or `null` when it holds no number. */
export function heatDomain(m: HeatmapMatrix): { min: number; max: number } | null {
    let min = Infinity;
    let max = -Infinity;
    for (const row of m.cells)
        for (const v of row)
            if (typeof v === 'number') {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
    return min === Infinity ? null : { min, max };
}

/** Position of `v` on a sequential scale, 0 (min) → 1 (max). A flat domain puts every value at the strong end. */
export function sequentialLevel(v: number, min: number, max: number): number {
    if (max <= min) return 1;
    return Math.max(0, Math.min(1, (v - min) / (max - min)));
}

/** Position of `v` on a diverging scale, −1 (furthest below `mid`) → 0 (`mid`) → 1 (furthest above). Both sides share
 *  one span — the larger distance from `mid` — so equal distances read as equal strength. */
export function divergingLevel(v: number, min: number, max: number, mid: number): number {
    const span = Math.max(Math.abs(max - mid), Math.abs(min - mid));
    if (span === 0) return 0;
    return Math.max(-1, Math.min(1, (v - mid) / span));
}

/** How one cell is painted: an inline `background` (the ramps) or a class set (status tones), and whether its text
 *  needs the on-colour ink because the fill is strong. */
export interface CellPaint {
    background: string | null;
    classes: string;
    strong: boolean;
    /** `status` scale: the tone the cell took (for the legend and the specs). */
    tone?: StatusTone;
}

/** The lightest and strongest alpha of a ramp — light enough to read as "low", strong enough to carry on-colour ink. */
const ALPHA_MIN = 0.08;
const ALPHA_MAX = 0.92;
/** From this alpha the fill is dark enough that the text switches to the on-colour ink. Computed for the light theme
 *  (indigo over white): below ~0.8 the default ink reads better, above it white does; the dark theme reads either. */
const STRONG_AT = 0.8;

/** A token ramp colour for `level` 0..1 of the named palette hue (`primary` / `warn`). */
export function rampColor(hue: 'primary' | 'warn', level: number): string {
    const a = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * Math.max(0, Math.min(1, level));
    return `rgba(var(--gamma-${hue}-rgb), ${a.toFixed(2)})`;
}

function rampPaint(hue: 'primary' | 'warn', level: number): CellPaint {
    const alpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * level;
    const strong = alpha >= STRONG_AT;
    return { background: rampColor(hue, level), classes: strong ? `text-on-${hue}` : '', strong };
}

/** The tone of a cell under the `status` scale: a word by its meaning, a number against the target (if any). */
export function statusCellTone(v: HeatmapValue, target?: number, better: Better = 'higher'): StatusTone {
    if (v == null) return 'neutral';
    if (typeof v === 'string') return statusTone(v);
    if (target == null || !Number.isFinite(target)) return 'neutral';
    const met = better === 'higher' ? v >= target : v <= target;
    return met ? 'success' : 'error';
}

/** Paint one cell. An empty cell (`null`) is never painted. */
export function cellPaint(
    v: HeatmapValue,
    scale: HeatmapScale,
    domain: { min: number; max: number } | null,
    opts: { midpoint?: number; target?: number; better?: Better } = {},
): CellPaint {
    const none: CellPaint = { background: null, classes: '', strong: false };
    if (v == null) return none;
    if (scale === 'status') {
        const tone = statusCellTone(v, opts.target, opts.better);
        // The status badge owns the tone -> class pairs; a number against its target reads as PASS / FAIL.
        const word = typeof v === 'string' ? v : tone === 'success' ? 'PASS' : tone === 'error' ? 'FAIL' : '';
        return { background: null, classes: statusBadgeClasses(word), strong: false, tone };
    }
    if (typeof v !== 'number' || !domain) return none;
    if (scale === 'diverging') {
        const level = divergingLevel(v, domain.min, domain.max, opts.midpoint ?? 0);
        if (level === 0) return none;
        return rampPaint(level < 0 ? 'warn' : 'primary', Math.abs(level));
    }
    return rampPaint('primary', sequentialLevel(v, domain.min, domain.max));
}
