import { humanizeColumn } from 'app/inspecto/viz/column-label';
import {
    escapeHtml,
    StatusTone,
    statusEdgeClasses,
    statusIconClasses,
    statusRowClasses,
} from 'app/inspecto/components/status-badge.component';
import {
    CompareColumn,
    DEFAULT_BANDS,
    ReconBands,
    Reconciliation,
    ReconBreak,
    withinTolerance,
} from './reconciliation-types';

/**
 * Reconciliation Board wire types + the offline aggregate engine (DAT-7,
 * `docs/superpower/reconciliation-board-design.md`). The types mirror the backend `/recon/run` and
 * `/recon/breaks` contracts byte-for-byte; {@link aggregateRecon}/{@link reconBreakSets} are an
 * in-browser mirror of the server's `ReconService`, unit-tested for parity with the backend's
 * `ReconServiceTest`. ⚠ Since the offline mock backend was removed (2026-08-31) NOTHING CALLS THEM —
 * `ReconExecService` always executes server-side. They are kept only as that parity mirror; see
 * `docs/superpower/mock-backend-removal-plan.md` §3b, which decides whether they stay.
 */

/** Wire name of the implicit COUNT(*) measure (always compared on the Board). */
export const RECON_RECORDS = '__records';

/** Side wire keys in anchor order — 'a' is always the anchor; a 3-way recon adds 'c'. */
export const SIDE_KEYS = ['a', 'b', 'c'] as const;
export type SideKey = (typeof SIDE_KEYS)[number];

/** One Board grain row — each side's aggregated measures for one key-column combination. */
export interface ReconGrainRow {
    key: Record<string, unknown>;
    a: Record<string, number | null>;
    b: Record<string, number | null>;
    c?: Record<string, number | null>;
    inA: boolean;
    inB: boolean;
    inC?: boolean;
}

/** One anchor-relative pair's Break summary (design §6 — pairs[0] = A↔B, pairs[1] = A↔C). */
/** A pair's Break counts; `cardinality_break` is present only when the Reconciliation declares a cardinality. */
export interface ReconByType {
    missing_left: number;
    missing_right: number;
    value_break: number;
    cardinality_break?: number;
}

export interface ReconPairSummary {
    side: SideKey;
    matchedKeys: number;
    byType: ReconByType;
}

export interface ReconRunSummary {
    groups: number;
    /** A↔B (mirrors pairs[0]) — kept flat so 2-way consumers are unchanged. */
    matchedKeys: number;
    byType: ReconByType;
    /** One entry per non-anchor side (present on 3-way; length 1 on 2-way). */
    pairs?: ReconPairSummary[];
}

/** The `/recon/run` payload. */
export interface ReconRunResult {
    keyColumns: string[];
    measures: string[];
    rows: ReconGrainRow[];
    totals: { a: Record<string, number | null>; b: Record<string, number | null>; c?: Record<string, number | null> };
    summary: ReconRunSummary;
    statistics: { rowCount: number; elapsedMs: number; truncated: boolean; cached?: boolean };
    /** The ONE day compared (RECON-PERF-1, operator 2026-10-09) — the requested day, or the latest present. */
    day?: string;
    /** Every day present across the sides, newest first (capped server-side). */
    availableDays?: string[];
    /** The grain-row filter this page was cut with. */
    filter?: ReconFilter;
    /** Set when only a deterministic sample of the day's keys was compared; `null` = every key. */
    sample?: { sampled: boolean; size: number; keys: number; totalKeys: number } | null;
    /** This page of `rows`; `total` = every row the filter matches on the day. Totals/summary are the whole day's. */
    page?: { offset: number; limit: number; total: number };
}

/** The Board grain-row filters `/recon/run` accepts (`missing_c` only on a 3-way Reconciliation). */
export const RECON_FILTERS = ['all', 'breaks', 'missing_a', 'missing_b', 'missing_c', 'value_break'] as const;
export type ReconFilter = (typeof RECON_FILTERS)[number];

/** How each filter reads in the toolbar. */
export const RECON_FILTER_LABELS: Record<ReconFilter, string> = {
    all: 'All keys',
    breaks: 'Breaks only',
    missing_a: 'Missing on A',
    missing_b: 'Missing on B',
    missing_c: 'Missing on C',
    value_break: 'Value breaks',
};

/** The page sizes the Board offers; the server refuses more than 200. */
export const RECON_PAGE_SIZES = [25, 50, 100, 200] as const;
export const RECON_DEFAULT_PAGE_SIZE = 50;

/** One `/recon/run` request's view state — never persisted (operator, 2026-10-09). */
export interface ReconRunQuery {
    /** ISO day; absent = the latest day present. */
    day?: string | null;
    offset: number;
    limit: number;
    filter: ReconFilter;
    /** Compare at most this many keys (deterministic); absent = every key. */
    sample?: number | null;
}

/** The non-anchor side keys present in a result (['b'] for 2-way, ['b','c'] for 3-way). */
export function comparedSides(result: ReconRunResult): SideKey[] {
    return result.totals.c ? ['b', 'c'] : ['b'];
}

/** One row of a `/recon/breaks` set (missing sets carry a single side). */
export interface ReconBreakRow {
    key: Record<string, unknown>;
    a?: Record<string, number | null>;
    b?: Record<string, number | null>;
    /**
     * The carried impact column's per-side value — present only when the Reconciliation's `impact.column` is
     * NOT a compare column. Never compared; a side absent at the key (or without the column) is null.
     */
    impact?: { a: number | null; b: number | null };
}

export interface ReconBreakSet {
    rows: ReconBreakRow[];
    rowCount: number;
    truncated: boolean;
}

/** The `/recon/breaks` payload, keyed by break type. */
export type ReconBreakSets = Partial<
    Record<'missing_left' | 'missing_right' | 'value_break' | 'cardinality_break', ReconBreakSet>
>;

// ── offline aggregate engine (mirror of the backend ReconService) ────────────────────

const KEY_SEP = '\u0000';

interface SideGroup {
    key: Record<string, unknown>;
    measures: Record<string, number | null>;
}

/** Group one side's rows at the key grain with per-measure sum/count + the implicit record count. */
function groupSide(
    rows: Record<string, unknown>[],
    keyColumns: string[],
    compare: CompareColumn[],
): Map<string, SideGroup> {
    const groups = new Map<string, SideGroup>();
    for (const row of rows) {
        const id = keyColumns.map((k) => String(row[k] ?? '')).join(KEY_SEP);
        let g = groups.get(id);
        if (!g) {
            const key: Record<string, unknown> = {};
            for (const k of keyColumns) key[k] = row[k] ?? null;
            g = { key, measures: { [RECON_RECORDS]: 0 } };
            // COUNT(col) of an all-NULL group is 0 (backend parity); SUM stays NULL until a value lands.
            for (const c of compare) g.measures[c.column] = (c.agg ?? 'sum') === 'count' ? 0 : null;
            groups.set(id, g);
        }
        g.measures[RECON_RECORDS] = (g.measures[RECON_RECORDS] ?? 0) + 1;
        for (const c of compare) {
            const v = row[c.column];
            if (v === null || v === undefined) continue;
            if ((c.agg ?? 'sum') === 'count') {
                g.measures[c.column] = (g.measures[c.column] ?? 0) + 1;
            } else {
                const n = Number(v);
                if (!Number.isNaN(n)) g.measures[c.column] = (g.measures[c.column] ?? 0) + n;
            }
        }
    }
    return groups;
}

/** Aggregated-value agreement — {@link withinTolerance} over the rolled-up numbers (backend parity). */
function aggWithin(a: number | null, b: number | null, c: CompareColumn): boolean {
    if (a === null || b === null) return a === b;
    return withinTolerance(a, b, c);
}

/** Human-readable break key for a grain row (also the {@code breakId} identity component). */
export function breakKeyOf(key: Record<string, unknown>, keyColumns: string[]): string {
    return keyColumns.map((k) => String(key[k] ?? '')).join(' · ');
}

/**
 * Map `/recon/breaks` sets to the C9 record model {@link ReconBreak} (all `open`) so the locked
 * lifecycle — {@code mergeBreaks} auto-close + preserved manual resolutions — keeps working unchanged
 * over server-computed breaks. A value-break grain row expands to one break per compare column that is
 * actually outside its tolerance.
 */
export function breaksFromSets(
    recon: Pick<Reconciliation, 'keyColumns' | 'compareColumns'>,
    sets: ReconBreakSets,
): ReconBreak[] {
    const out: ReconBreak[] = [];
    for (const row of sets.missing_right?.rows ?? [])
        out.push({ key: breakKeyOf(row.key, recon.keyColumns), type: 'missing_right', status: 'open' });
    for (const row of sets.missing_left?.rows ?? [])
        out.push({ key: breakKeyOf(row.key, recon.keyColumns), type: 'missing_left', status: 'open' });
    // A cardinality break is one per KEY, not one per compare column: a key has one cardinality. Its
    // evidence is the per-side row count, which the server always includes for this set even when the
    // reconciliation has the implicit record count switched off.
    for (const row of sets.cardinality_break?.rows ?? [])
        out.push({
            key: breakKeyOf(row.key, recon.keyColumns),
            keyValues: row.key,
            type: 'cardinality_break',
            leftValue: row.a?.[RECON_RECORDS] ?? null,
            rightValue: row.b?.[RECON_RECORDS] ?? null,
            status: 'open',
        });
    for (const row of sets.value_break?.rows ?? []) {
        for (const c of recon.compareColumns) {
            const a = row.a?.[c.column] ?? null;
            const b = row.b?.[c.column] ?? null;
            if (aggWithin(a, b, c)) continue;
            out.push({
                key: breakKeyOf(row.key, recon.keyColumns),
                type: 'value_break',
                column: c.column,
                leftValue: a,
                rightValue: b,
                diff: a !== null && b !== null ? b - a : undefined,
                status: 'open',
            });
        }
    }
    return out;
}

/**
 * UIE-10: the monetary impact of each Break key in `/recon/breaks` sets, keyed by the same
 * {@link breakKeyOf} string a {@link ReconBreak} carries. Two cases, by whether the impact column is compared:
 *
 * - **Compared** — |A − B| of that column, a side absent at the key counting 0 (the whole present amount
 *   is unmatched).
 * - **Carried** (not a compare column; operator decision 2026-09-25) — the server puts the column's per-side
 *   value on each row as `impact: {a, b}` without comparing it. A carried column says what the key is WORTH,
 *   not how far apart the sides are, so the impact is the value on the side that has it: the anchor's (`a`)
 *   when present, else the compared side's (`b`). E.g. a subscriber active in the HLR but absent from or
 *   inactive in billing → that subscriber's monthly fee.
 *
 * ⚠ A key with no value to read (no `impact` on the row, or null on both sides) gets no entry, never an
 * invented 0 — a 0 would read as "no money at risk".
 */
export function breakImpacts(
    recon: Pick<Reconciliation, 'keyColumns' | 'compareColumns' | 'impact'>,
    sets: ReconBreakSets,
): Record<string, number> {
    const col = recon.impact?.column;
    if (!col) return {};
    const compared = recon.compareColumns.some((c) => c.column === col);
    const out: Record<string, number> = {};
    // A duplicate key's money is a different question (the extra copies) — see {@link duplicateImpacts}.
    for (const [type, set] of Object.entries(sets)) {
        if (type === 'cardinality_break') continue;
        for (const row of set?.rows ?? []) {
            const key = breakKeyOf(row.key, recon.keyColumns);
            if (compared) {
                out[key] = Math.abs((row.a?.[col] ?? 0) - (row.b?.[col] ?? 0));
            } else {
                const v = row.impact?.a ?? row.impact?.b;
                if (v !== null && v !== undefined) out[key] = v;
            }
        }
    }
    return out;
}

/** A declared cardinality that asserts something; `many_to_many` (and a blank) asserts nothing. */
export type ReconCardinality = 'one_to_one' | 'one_to_many' | 'many_to_one';

/**
 * The Reconciliation's declared cardinality, or null when it declares none. It is stored, not modelled —
 * read from `raw`, the same place `serverConfig` sends it from — and a blank or `many_to_many` is null
 * because the server then produces no cardinality Breaks at all.
 */
export function reconCardinality(recon: Pick<Reconciliation, 'raw'> | null | undefined): ReconCardinality | null {
    const c = recon?.raw?.['cardinality'];
    return c === 'one_to_one' || c === 'one_to_many' || c === 'many_to_one' ? c : null;
}

/** The pair roles a cardinality declares "one" — the sides on which a repeated key is a Break. */
export function oneSides(cardinality: ReconCardinality): ('a' | 'b')[] {
    return cardinality === 'one_to_one' ? ['a', 'b'] : cardinality === 'one_to_many' ? ['a'] : ['b'];
}

/**
 * The money behind each duplicate key (a `cardinality_break` row), keyed like {@link breakImpacts}.
 *
 * Decision (2026-09-25): a duplicate's impact is the value of its EXTRA copies — what is billed (or
 * provisioned) beyond the one record the cardinality allows. The impact column arrives SUMMED per side
 * (compared: `a`/`b` measures; carried: `impact: {a, b}`), so on a "one" side with n records the extra copies
 * are worth `sum × (n − 1) / n` (the per-record average times the surplus); both "one" sides add up under
 * `one_to_one`. A "many" side's repeats are allowed and count nothing.
 *
 * ⚠ As in {@link breakImpacts}, a key with no value to read gets no entry, never an invented 0.
 */
export function duplicateImpacts(
    recon: Pick<Reconciliation, 'keyColumns' | 'compareColumns' | 'impact' | 'raw'>,
    sets: ReconBreakSets,
): Record<string, number> {
    const col = recon.impact?.column;
    const cardinality = reconCardinality(recon);
    if (!col || !cardinality) return {};
    const compared = recon.compareColumns.some((c) => c.column === col);
    const out: Record<string, number> = {};
    for (const row of sets.cardinality_break?.rows ?? []) {
        let extra: number | null = null;
        for (const side of oneSides(cardinality)) {
            const n = row[side]?.[RECON_RECORDS] ?? 0;
            const sum = compared ? row[side]?.[col] : row.impact?.[side];
            if (n > 1 && sum !== null && sum !== undefined) extra = (extra ?? 0) + (sum * (n - 1)) / n;
        }
        if (extra !== null) out[breakKeyOf(row.key, recon.keyColumns)] = extra;
    }
    return out;
}

/**
 * The in-browser mirror of `POST /recon/run` over already-resolved side rows. Pass {@code thirdRows} to
 * run 3-way: side 0 (left) is the anchor, each further side is reconciled against it (design §6).
 */
export function aggregateRecon(
    recon: Pick<Reconciliation, 'keyColumns' | 'compareColumns'>,
    leftRows: Record<string, unknown>[],
    rightRows: Record<string, unknown>[],
    thirdRows?: Record<string, unknown>[] | null,
): ReconRunResult {
    const t0 = Date.now();
    const { keyColumns, compareColumns } = recon;
    const threeWay = !!thirdRows;
    const grouped = [
        groupSide(leftRows, keyColumns, compareColumns),
        groupSide(rightRows, keyColumns, compareColumns),
        ...(threeWay ? [groupSide(thirdRows!, keyColumns, compareColumns)] : []),
    ];
    const measures = [...compareColumns.map((c) => c.column), RECON_RECORDS];

    const ids = [...new Set(grouped.flatMap((g) => [...g.keys()]))].sort();
    const emptySide = (): Record<string, number | null> => {
        const m: Record<string, number | null> = { [RECON_RECORDS]: null };
        for (const c of compareColumns) m[c.column] = null;
        return m;
    };
    const rows: ReconGrainRow[] = [];
    for (const id of ids) {
        const g = grouped.map((side) => side.get(id));
        const row: ReconGrainRow = {
            key: g.find((x) => x)!.key,
            a: g[0]?.measures ?? emptySide(),
            b: g[1]?.measures ?? emptySide(),
            inA: !!g[0],
            inB: !!g[1],
        };
        if (threeWay) {
            row.c = g[2]?.measures ?? emptySide();
            row.inC = !!g[2];
        }
        rows.push(row);
    }

    // Anchor-relative pair summaries: one per non-anchor side.
    const pairs: ReconPairSummary[] = [];
    for (let other = 1; other < grouped.length; other++) {
        const byType = { missing_left: 0, missing_right: 0, value_break: 0 };
        let matched = 0;
        for (const id of ids) {
            const ga = grouped[0].get(id);
            const go = grouped[other].get(id);
            if (ga && !go) byType.missing_right++;
            else if (go && !ga) byType.missing_left++;
            else if (ga && go) {
                matched++;
                for (const c of compareColumns)
                    if (!aggWithin(ga.measures[c.column], go.measures[c.column], c)) byType.value_break++;
            }
        }
        pairs.push({ side: SIDE_KEYS[other], matchedKeys: matched, byType });
    }

    const summary: ReconRunSummary = {
        groups: ids.length,
        matchedKeys: pairs[0].matchedKeys,
        byType: pairs[0].byType,
        pairs,
    };
    const totals: ReconRunResult['totals'] = {
        a: sideTotals(grouped[0], compareColumns),
        b: sideTotals(grouped[1], compareColumns),
    };
    if (threeWay) totals.c = sideTotals(grouped[2], compareColumns);

    return {
        keyColumns,
        measures,
        rows,
        totals,
        summary,
        statistics: { rowCount: rows.length, elapsedMs: Date.now() - t0, truncated: false },
    };
}

function sideTotals(groups: Map<string, SideGroup>, compare: CompareColumn[]): Record<string, number | null> {
    const totals: Record<string, number | null> = { [RECON_RECORDS]: 0 };
    for (const c of compare) totals[c.column] = null;
    for (const g of groups.values()) {
        totals[RECON_RECORDS] = (totals[RECON_RECORDS] ?? 0) + (g.measures[RECON_RECORDS] ?? 0);
        for (const c of compare) {
            const v = g.measures[c.column];
            if (v !== null && v !== undefined) totals[c.column] = (totals[c.column] ?? 0) + v;
        }
    }
    return totals;
}

/**
 * The in-browser mirror of `POST /recon/breaks` for one anchor-relative pair (all three sets, optionally
 * path-scoped / type-filtered). {@code side} picks the compared side ('b' default, or 'c' on a 3-way
 * recon); output rows always carry the anchor as {@code a} and the compared side as {@code b} (roles).
 */
export function reconBreakSets(
    recon: Pick<Reconciliation, 'keyColumns' | 'compareColumns'> & { thirdDataset?: string },
    leftRows: Record<string, unknown>[],
    rightRows: Record<string, unknown>[],
    path?: Record<string, string> | null,
    type?: 'missing_left' | 'missing_right' | 'value_break' | null,
    side: SideKey = 'b',
    thirdRows?: Record<string, unknown>[] | null,
): ReconBreakSets {
    const run = aggregateRecon(recon, leftRows, rightRows, thirdRows);
    const otherOf = (r: ReconGrainRow) => (side === 'c' ? (r.c ?? null) : r.b);
    const inOther = (r: ReconGrainRow) => (side === 'c' ? !!r.inC : r.inB);
    const inPath = (key: Record<string, unknown>): boolean =>
        !path || Object.entries(path).every(([dim, v]) => String(key[dim] ?? '') === v);
    const set = (rows: ReconBreakRow[]): ReconBreakSet => ({ rows, rowCount: rows.length, truncated: false });

    const out: ReconBreakSets = {};
    if (!type || type === 'missing_right')
        out.missing_right = set(
            run.rows.filter((r) => r.inA && !inOther(r) && inPath(r.key)).map((r) => ({ key: r.key, a: r.a })),
        );
    if (!type || type === 'missing_left')
        out.missing_left = set(
            run.rows.filter((r) => inOther(r) && !r.inA && inPath(r.key)).map((r) => ({ key: r.key, b: otherOf(r)! })),
        );
    if (!type || type === 'value_break')
        out.value_break = set(
            run.rows
                .filter(
                    (r) =>
                        r.inA &&
                        inOther(r) &&
                        inPath(r.key) &&
                        recon.compareColumns.some((c) => !aggWithin(r.a[c.column], otherOf(r)![c.column], c)),
                )
                .map((r) => ({ key: r.key, a: r.a, b: otherOf(r)! })),
        );
    return out;
}

// ── Board tree + severity bands ──────────────────────────────────────────────────────

export type ReconBand = 'ok' | 'warn' | 'breach' | 'structural';

/**
 * Δ% of `b` vs the anchor `a`, from ROLLED-UP values (never averaged child Δ%s). `null` = no meaningful
 * percentage: a missing/NULL side, or a zero anchor with a non-zero other ("new" — structural severity).
 */
export function deltaPct(a: number | null | undefined, b: number | null | undefined): number | null {
    if (a === null || a === undefined || b === null || b === undefined) return null;
    if (a === 0) return b === 0 ? 0 : null;
    return ((b - a) / Math.abs(a)) * 100;
}

/** The severity band for one Δ% cell. */
export function bandFor(pct: number | null | undefined, bands: ReconBands = DEFAULT_BANDS): ReconBand {
    if (pct === null || pct === undefined) return 'structural';
    const abs = Math.abs(pct);
    if (abs > bands.breachPct) return 'breach';
    if (abs >= bands.warnPct) return 'warn';
    return 'ok';
}

/** Encode a Board dimension path (`region=EU`, `product=data`) as a stable node id / query param. */
export function encodePath(path: Record<string, unknown>, keyColumns: string[]): string {
    return keyColumns
        .filter((k) => k in path)
        .map((k) => `${k}:${encodeURIComponent(String(path[k] ?? ''))}`)
        .join('|');
}

/** Decode {@link encodePath}'s form back to a dim → value map (unknown segments are skipped). */
export function decodePath(encoded: string | null | undefined): Record<string, string> | null {
    if (!encoded) return null;
    const out: Record<string, string> = {};
    for (const seg of encoded.split('|')) {
        const i = seg.indexOf(':');
        if (i > 0) out[seg.slice(0, i)] = decodeURIComponent(seg.slice(i + 1));
    }
    return Object.keys(out).length ? out : null;
}

// ── cell renderers (text tones only — the token guard forbids status-tinted fills) ───

const BAND_TONE: Record<ReconBand, string> = {
    ok: 'text-green-600 dark:text-green-400',
    warn: 'text-amber-600 dark:text-amber-400',
    breach: 'text-red-600 dark:text-red-400 font-medium',
    structural: 'text-red-600 dark:text-red-400',
};
const BAND_GLYPH: Record<ReconBand, string> = { ok: '✓', warn: '!', breach: '✕', structural: '⊘' };

/** The lint-sanctioned `text-*` tone class for a band (glyph + text carry the meaning, never color alone). */
export function bandTone(band: ReconBand): string {
    return BAND_TONE[band];
}

/** The severity glyph for a band. */
export function bandGlyph(band: ReconBand): string {
    return BAND_GLYPH[band];
}

/** A band as a reader says it — the row's tooltip and its visually-hidden name (operator, 2026-10-09). */
export const BAND_LABEL: Record<ReconBand, string> = {
    ok: 'Within tolerance',
    warn: 'Warning',
    breach: 'Breach',
    structural: 'Missing on a side',
};

/** The shape icon per band — the non-colour cue beside the row tint (WCAG 1.4.1). */
export const BAND_ICON: Record<ReconBand, string> = {
    ok: 'heroicons_outline:check-circle',
    warn: 'heroicons_outline:exclamation-triangle',
    breach: 'heroicons_outline:x-circle',
    structural: 'heroicons_outline:minus-circle',
};

/** The design-system status tone a band is tinted with (a missing side reads as a breach). */
export function bandStatusTone(band: ReconBand): StatusTone {
    return band === 'ok' ? 'success' : band === 'warn' ? 'warning' : 'error';
}

const BAND_RANK: Record<ReconBand, number> = { ok: 0, warn: 1, breach: 2, structural: 3 };

/**
 * One grain row's band: `structural` when the key is missing on any side, else the WORST band of its Δ% over every
 * measure and compared side (the same `bandFor` the TOTAL strip uses).
 */
export function rowBand(row: ReconGrainRow, result: ReconRunResult, bands: ReconBands = DEFAULT_BANDS): ReconBand {
    const sides = comparedSides(result);
    if (!row.inA || !row.inB || (sides.includes('c') && row.inC === false)) return 'structural';
    let worst: ReconBand = 'ok';
    for (const m of result.measures)
        for (const s of sides) {
            const band = bandFor(deltaPct(row.a?.[m], row[s]?.[m]), bands);
            if (BAND_RANK[band] > BAND_RANK[worst]) worst = band;
        }
    return worst;
}

/** The legend entries for a band set: each band's name and its threshold, worst last. */
export function bandLegend(bands: ReconBands = DEFAULT_BANDS): { band: ReconBand; label: string; range: string }[] {
    return [
        { band: 'ok', label: BAND_LABEL.ok, range: `< ${bands.warnPct}%` },
        { band: 'warn', label: BAND_LABEL.warn, range: `${bands.warnPct}–${bands.breachPct}%` },
        { band: 'breach', label: BAND_LABEL.breach, range: `> ${bands.breachPct}%` },
        { band: 'structural', label: BAND_LABEL.structural, range: 'key on some sides only' },
    ];
}

/** A signed Δ% as text (`+1.5%`), `new` for a zero anchor, `—` for a missing side — words carry no colour names. */
export function fmtPct(a: number | null | undefined, b: number | null | undefined): string {
    if (a === null || a === undefined || b === null || b === undefined) return '—';
    const pct = deltaPct(a, b);
    if (pct === null) return 'new';
    return `${pct > 0 ? '+' : ''}${pct.toFixed(1)}%`;
}

/** Compact numeric formatter for the Board's measure value columns (`—` for a missing side). */
export function fmtMeasure(v: unknown): string {
    if (v === null || v === undefined) return '—';
    const n = Number(v);
    if (Number.isNaN(n)) return String(v);
    return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

/** How a Board side is named in its column headers (R2-16): a readable label, and the Dataset id for the tooltip. */
export interface BoardSide {
    label: string;
    id: string;
}

/** A Board measure as a reader says it: the implicit COUNT(*) is "Records", a column is humanised. */
export function measureLabel(measure: string): string {
    return measure === RECON_RECORDS ? 'Records' : humanizeColumn(measure);
}

/** The in-flight message: "Comparing HLR, CRM and CBS for 2026-09-26…" (no day yet ⇒ "for the latest day…"). */
export function comparingMessage(labels: string[], day?: string | null): string {
    const names =
        labels.length > 1 ? `${labels.slice(0, -1).join(', ')} and ${labels[labels.length - 1]}` : (labels[0] ?? '');
    return `Comparing ${names} for ${day ? day : 'the latest day'}…`;
}

/** A recorded/live Break's band: a value break by its Δ% (anchor-relative), every other Break type as `structural`. */
export function breakBand(
    b: { type: string; leftValue?: unknown; rightValue?: unknown },
    bands: ReconBands = DEFAULT_BANDS,
): ReconBand {
    if (b.type !== 'value_break') return 'structural';
    const a = typeof b.leftValue === 'number' ? b.leftValue : null;
    const v = typeof b.rightValue === 'number' ? b.rightValue : null;
    return bandFor(deltaPct(a, v), bands);
}

/** The row tint + left edge for a band (the table row's classes; the icon and name are the non-colour cue). */
export function bandRowClass(band: ReconBand): string {
    const tone = bandStatusTone(band);
    return `${statusRowClasses(tone)} ${statusEdgeClasses(tone)}`;
}

/**
 * An ag-Grid key-cell renderer that carries a Break's band without a status column (operator, 2026-10-09): the band's
 * shape glyph (tooltip = the band name, hidden from assistive tech) and the band name as visually-hidden text,
 * before the key itself. Pairs with {@link bandRowClass} on the row.
 */
export function bandKeyCell(bandOf: (row: unknown) => ReconBand): (p: { value?: unknown; data?: unknown }) => string {
    return (p) => {
        const band = bandOf(p.data);
        const label = BAND_LABEL[band];
        const tone = statusIconClasses(bandStatusTone(band));
        return (
            `<span class="${tone} mr-1" aria-hidden="true" title="${label}">${bandGlyph(band)}</span>` +
            `<span class="sr-only">${label}: </span>${escapeHtml(String(p.value ?? ''))}`
        );
    };
}
