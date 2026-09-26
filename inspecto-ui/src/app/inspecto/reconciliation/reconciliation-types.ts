/**
 * Reconciliation — C9 (Wave 3). Pure, framework-free: define a **Dataset-vs-Dataset** match (key
 * columns + per-column numeric tolerances) and compute the **breaks** between the two sides. Stored as a
 * `reconciliation` component, mirroring `dataset-types.ts`/`requirement-types.ts`'s
 * "just a component" shape.
 *
 * Semantics locked with the product owner 2026-07-03: match rows by `keyColumns`; for each
 * `compareColumns` entry apply an exact / absolute / percent tolerance before flagging a value break; a
 * break **auto-closes** when its key re-matches within tolerance on a later run, while manual resolutions
 * are preserved across runs. Since R2-03 (operator 2026-09-26) that lifecycle is applied SERVER-side
 * (`ReconBreaks.merge`) and recorded as {@link ReconState} — never in the config.
 */

export type ToleranceType = 'exact' | 'absolute' | 'percent';

/** How a compare column aggregates on the Board (record-grain break truth stays the tolerance). */
export type MeasureAgg = 'sum' | 'count';

/** One column compared between the two sides, with the tolerance under which a difference is NOT a break. */
export interface CompareColumn {
    column: string;
    toleranceType: ToleranceType;
    /** Ignored for `exact`. For `absolute`: max |left-right|. For `percent`: max |left-right| / |left| * 100. */
    tolerance: number;
    /** Board rollup aggregation (default `sum`). */
    agg?: MeasureAgg;
}

/** Board display severity for |Δ%| vs the anchor — independent of a column's record-level tolerance. */
export interface ReconBands {
    warnPct: number;
    breachPct: number;
}

/** The locked defaults: < 1 % ok · 1–2 % warn · > 2 % breach. */
export const DEFAULT_BANDS: ReconBands = { warnPct: 1, breachPct: 2 };

/**
 * ⚠ `cardinality_break` (2026-09-12, `RECON-CARDINALITY-1`) is an ASSERTION failure, not a value
 * mismatch: the key matched, but a side contributed more rows than the reconciliation's declared
 * `cardinality` allows. Its evidence is the per-side ROW COUNT, carried in `leftValue`/`rightValue`.
 * The server emits it only for a reconciliation that declares a cardinality, so it never appears for
 * one authored before the option existed.
 */
export type BreakType = 'missing_left' | 'missing_right' | 'value_break' | 'cardinality_break';
/** `assigned` = an unresolved Break with an {@link ReconBreak.assignee} (`ASSURE-BREAK-LIFECYCLE-1`). */
export type BreakStatus = 'open' | 'assigned' | 'resolved' | 'auto_closed';
/** The anchor-relative pair a Break was found on: A vs B, or A vs C on a 3-way Reconciliation. */
export type ReconPair = 'AB' | 'AC';

/** One reconciliation discrepancy for a single key (and, for value breaks, a single compare column). */
export interface ReconBreak {
    /**
     * The pair this Break belongs to — part of its recorded identity ({@link lifecycleId}), so an A-vs-B and an
     * A-vs-C Break on one key and column resolve and age independently. Absent reads as `AB`: a Break recorded
     * before pairs existed was an A-vs-B one (the server applies the same rule to its state file).
     */
    pair?: ReconPair;
    key: string;
    /**
     * The key as the server spelled it — one value per key column — so a follow-up call (the raw rows
     * behind a cardinality break, RECON-CARDINALITY-2) can name the key without parsing the display string.
     */
    keyValues?: Record<string, unknown>;
    type: BreakType;
    /** The compare column that broke (value breaks only). */
    column?: string;
    leftValue?: unknown;
    rightValue?: unknown;
    /**
     * Signed change from left to right, `right − left` (value breaks on numeric columns only) — it reads the
     * same way as the `A → B` field diff and the Board's anchor-relative Δ%: 149 → 99 is −50 (B under-bills).
     * The Break's impact is an absolute amount computed separately, never derived from this sign.
     */
    diff?: number;
    status: BreakStatus;
    /** Manual-resolution note (preserved across re-runs). */
    note?: string;
    /**
     * ISO instant this break was FIRST observed, carried across every later run by the server's lifecycle
     * merge (`BREAK-AGING-1`, 2026-09-11; server-side since R2-03). Age is derived from it — a break has a
     * status but, before this, carried no time at all, so "how long has this been broken" was unanswerable.
     *
     * ⚠ Optional on purpose, and it must stay optional: a live Break no run has recorded yet, and one a
     * status change appended before any run saw it, carry none. {@link breakAgeDays} returns `null` rather
     * than guessing, and the UI shows an em-dash — an invented age would read exactly like a measured one.
     */
    firstSeenAt?: string;
    /**
     * The recorded lifecycle's counters (`ASSURE-BREAK-LIFECYCLE-1`, server-side in `ReconBreaks.merge`) — all
     * absent on a live Break no run has recorded. `occurrences` = recorded runs the Break was present in;
     * `recurrences` = times it reappeared after auto-closing (it is then RE-OPENED, keeping `firstSeenAt`).
     */
    lastSeenAt?: string;
    occurrences?: number;
    recurrences?: number;
    /** Who owns an `assigned` Break; kept on record when it resolves or auto-closes. */
    assignee?: string;
    /**
     * Whole days an unresolved (`open` / `assigned`) Break has been broken — computed by the SERVER at read
     * time, absent for a settled or unstamped Break. {@link breakAgeDays} prefers it.
     */
    ageDays?: number;
}

/**
 * A Reconciliation's recorded OPERATIONAL state (R2-03, operator 2026-09-26 — reversing C9, which kept it in
 * the browser and wrote it back through the authoring PUT, so an operations-only user could never record a
 * run). Kept server-side in `recon-state/<id>.json`, written by `POST /recon/{id}/record` and
 * `POST /recon/{id}/breaks/status` (both `canOperateRuns`), read by `GET /recon/{id}/state`.
 */
export interface ReconState {
    reconciliation: string;
    /** The last recorded run, or `null` when none was ever recorded. */
    lastRunAt: string | null;
    runs: number;
    /** The recorded lifecycle — both pairs on a 3-way Reconciliation, each Break carrying its `pair`. */
    breaks: ReconBreak[];
}

/**
 * The money a Break puts at risk (UIE-10). `column` is any column of the reconciled Datasets. A compared
 * column's impact is |A - B| at the Break's key, a missing side counting 0; any other column is CARRIED on
 * each Break by the server without being compared, and its impact is the value on the side that has it
 * (anchor first) — see `breakImpacts`.
 */
export interface ReconImpact {
    column: string;
    /** ISO 4217 code the impact is shown in, e.g. `SAR`. Absent: a plain number. */
    currency?: string;
}

/** The persisted body of a `reconciliation` component (everything except id/name). */
export interface ReconciliationConfig {
    /** Business description, the Breaks page title (UIE-10). The id is a code, not a name. */
    description?: string;
    /** Optional monetary impact per Break (UIE-10). */
    impact?: ReconImpact;
    leftDataset: string;
    rightDataset: string;
    /** Optional third dataset — turns the recon 3-way (anchor = leftDataset, design §6). */
    thirdDataset?: string;
    /**
     * The **v2** anchor-first dataset list — the shape `/recon/run` takes and the shape an authored
     * config on disk uses (`datasets[3]: a, b, c`). Read-side only: {@code fromContent} folds it into
     * {@link leftDataset}/{@link rightDataset}/{@link thirdDataset}, which stay the UI's model.
     */
    datasets?: string[];
    keyColumns: string[];
    compareColumns: CompareColumn[];
    /** Board severity bands (defaults to {@link DEFAULT_BANDS} when absent). */
    bands?: ReconBands;
}

export interface Reconciliation extends ReconciliationConfig {
    id: string;
    name: string;
    /** The stored body as read. Keys this model does not carry (`columnMap`, `filters`, `cardinality`, …) are
     *  written back untouched: a save is a whole-body PUT, so anything not written back is deleted. */
    raw?: Record<string, unknown>;
}

/** The readable title of a Reconciliation — its business description, else its name, else its id (UIE-10, R2-16). */
export function reconciliationTitle(r: Pick<Reconciliation, 'id' | 'name' | 'description'>): string {
    return r.description?.trim() || r.name?.trim() || r.id;
}

/** What a side label needs of a Dataset — the Studio `Dataset` satisfies it. */
export interface DatasetLabelSource {
    id: string;
    name?: string;
    description?: string;
}

/**
 * Dataset id → the readable label a Reconciliation side is shown by (R2-16): the Dataset's description,
 * else its name, else its id — the same order as {@link reconciliationTitle}. A side whose Dataset is not
 * in the list is absent here; callers fall back to the id.
 */
export function datasetLabels(datasets: readonly DatasetLabelSource[]): Record<string, string> {
    const out: Record<string, string> = {};
    for (const d of datasets) out[d.id] = d.description?.trim() || d.name?.trim() || d.id;
    return out;
}

export interface ReconSummary {
    leftRows: number;
    rightRows: number;
    matchedKeys: number;
    open: number;
    resolved: number;
    autoClosed: number;
    byType: Record<BreakType, number>;
    /** Aging histogram over the **open** breaks only (`BREAK-AGING-1`) — see {@link AGE_BUCKETS}. */
    byAge: Record<AgeBucket, number>;
}

/** Aging buckets, in days since a break was first seen (`BREAK-AGING-1`). */
export const AGE_BUCKETS = ['0-30', '30-60', '60-90', '90+', 'unknown'] as const;
export type AgeBucket = (typeof AGE_BUCKETS)[number];

/**
 * Whole days since {@link ReconBreak.firstSeenAt}, or `null` when the break carries no stamp — a break
 * persisted before `BREAK-AGING-1`, or one that has never been through a run's merge.
 *
 * ⛔ Never substitute "0" for a missing stamp: a break with no recorded first sighting is not a new one,
 * and reporting it as fresh is the opposite of what an aging view is for.
 */
export function breakAgeDays(b: ReconBreak, now: Date = new Date()): number | null {
    // The server's age wins (ASSURE-BREAK-LIFECYCLE-1); the local derivation covers a stamp it did not age.
    if (typeof b.ageDays === 'number') return b.ageDays;
    if (!b.firstSeenAt) return null;
    const seen = Date.parse(b.firstSeenAt);
    if (Number.isNaN(seen)) return null;
    return Math.max(0, Math.floor((now.getTime() - seen) / 86_400_000));
}

/**
 * Open breaks rolled up by age bucket, empty buckets dropped — the shared aging strip behind BOTH the
 * Board and the Breaks page.
 *
 * ⚠ It lives here rather than in either component on purpose: two components deriving the same histogram
 * is exactly how one concept ends up with two drifting definitions, and the "open only" rule below is the
 * kind of thing that drifts first.
 *
 * Counts **unresolved** (`open` / `assigned`) breaks only: resolved and auto-closed breaks are settled work,
 * and including them would make the backlog look older the more of it you cleared. An assigned Break is still
 * broken — owning it does not make it younger.
 */
export function openAgeBuckets(breaks: ReconBreak[], now: Date = new Date()): { bucket: AgeBucket; count: number }[] {
    const counts = new Map<AgeBucket, number>();
    for (const b of breaks) {
        if (!isUnresolved(b)) continue;
        const bucket = ageBucketOf(b, now);
        counts.set(bucket, (counts.get(bucket) ?? 0) + 1);
    }
    return AGE_BUCKETS.filter((b) => counts.has(b)).map((bucket) => ({ bucket, count: counts.get(bucket)! }));
}

/** An `open` or `assigned` Break — still broken, whoever owns it. */
export function isUnresolved(b: Pick<ReconBreak, 'status'>): boolean {
    return b.status === 'open' || b.status === 'assigned';
}

/**
 * The recorded lifecycle's headline counts for the Board (`ASSURE-BREAK-LIFECYCLE-1`): unresolved Breaks with
 * an assignee, and unresolved Breaks that have recurred at least once.
 */
export function lifecycleCounts(breaks: ReconBreak[]): { assigned: number; recurring: number } {
    let assigned = 0,
        recurring = 0;
    for (const b of breaks) {
        if (!isUnresolved(b)) continue;
        if (b.status === 'assigned') assigned++;
        if ((b.recurrences ?? 0) > 0) recurring++;
    }
    return { assigned, recurring };
}

/** Display label for an age bucket — `unknown` is spelled out rather than shown as a range. */
export function ageBucketLabel(b: AgeBucket): string {
    return b === 'unknown' ? 'No first-seen date' : `${b} days`;
}

/** The bucket a break falls in; `unknown` when it carries no first-seen stamp. */
export function ageBucketOf(b: ReconBreak, now: Date = new Date()): AgeBucket {
    const days = breakAgeDays(b, now);
    if (days === null) return 'unknown';
    // Upper-exclusive so a boundary day lands in exactly one bucket: 30 days old is '30-60', not both.
    if (days < 30) return '0-30';
    if (days < 60) return '30-60';
    if (days < 90) return '60-90';
    return '90+';
}

const KEY_SEP = '';

/** Composite key for a row (key column values joined). */
function keyOf(row: Record<string, unknown>, keyColumns: string[]): string {
    return keyColumns.map((k) => String(row[k] ?? '')).join(KEY_SEP);
}

/**
 * Escape one identity part so a `|` inside a value cannot be read as the separator.
 *
 * ⚠ Mirror of `ReconBreaks.esc` (engine) — see {@link breakId}.
 */
function escPart(part: string): string {
    return part.replace(/\\/g, '\\\\').replace(/\|/g, '\\|');
}

/**
 * Stable identity for a break — `(type, key, column)` — the key the server's recorded lifecycle merges and
 * overlays by (R2-03) **and the server's Incident dedupe grain** (`BREAK-DEDUPE-GRAIN-1`).
 *
 * ⛔ **One contract with the backend.** `ReconBreaks.identity()` (engine; `ReconRoutes.breakIdentity()`
 * delegates to it) renders the byte-identical string: the recorded state is matched to the live Breaks by it,
 * and it is the `breakId` attribute promotions dedupe on and `GET /recon/promoted` indexes by. Changing
 * either spelling alone silently empties both overlays — every Break would read as open and un-promoted.
 *
 * 🔴 **Why the parts are escaped, and why this does not use `KEY_SEP`.** `KEY_SEP` is `''` and belongs to
 * {@link keyOf}, which composes `b.key` itself out of the key columns' values — so a key routinely contains
 * the separators (the repo's own fixtures use keys like `EU|voice`). Joining with `''` made this function
 * non-injective: `('break', 'EU|voice', 'amount')` and `('break', 'EU', 'voice|amount')` rendered the same
 * string. Escaping `\` then `|` makes distinct triples stay distinct. ⛔ Do NOT "tidy" this back onto
 * `KEY_SEP` — that constant is a different concern, and changing it would alter every break's `key` value.
 */
export function breakId(b: ReconBreak): string {
    return `${escPart(b.type)}|${escPart(b.key)}|${escPart(b.column ?? '')}`;
}

/** A Break's pair — `AB` when it carries none. */
export function pairOf(b: Pick<ReconBreak, 'pair'>): ReconPair {
    return b.pair ?? 'AB';
}

/**
 * A Break's RECORDED-lifecycle identity — `(pair, type, key, column)` — what the Breaks page overlays the
 * recorded status / note / first-seen by. It is {@link breakId} with the pair in front.
 *
 * ⛔ One contract with the backend's `ReconBreaks.lifecycleId()`, which renders the byte-identical string.
 * ⚠ The Incident dedupe grain stays {@link breakId} (no pair) — promotions are keyed by that, not this.
 */
export function lifecycleId(b: ReconBreak): string {
    return `${escPart(pairOf(b))}|${breakId(b)}`;
}

/** True when `left` and `right` agree within the column's tolerance (non-numeric ⇒ exact string compare). */
export function withinTolerance(left: unknown, right: unknown, c: CompareColumn): boolean {
    if (c.toleranceType === 'exact') return String(left ?? '') === String(right ?? '');
    const a = Number(left);
    const b = Number(right);
    if (Number.isNaN(a) || Number.isNaN(b)) return String(left ?? '') === String(right ?? '');
    const delta = Math.abs(a - b);
    if (c.toleranceType === 'absolute') return delta <= c.tolerance;
    // percent — relative to |left|; with left 0 there is no meaningful percentage, so require exact equality.
    if (a === 0) return delta === 0;
    return (delta / Math.abs(a)) * 100 <= c.tolerance;
}

/**
 * Compute the fresh break set for a run. Every returned break is `open` — lifecycle (auto-close, preserved
 * manual resolutions) is applied separately, server-side, against the previous recorded run.
 */
export function runReconciliation(
    config: Pick<ReconciliationConfig, 'keyColumns' | 'compareColumns'>,
    leftRows: Record<string, unknown>[],
    rightRows: Record<string, unknown>[],
): ReconBreak[] {
    const { keyColumns, compareColumns } = config;
    const leftByKey = new Map(leftRows.map((r) => [keyOf(r, keyColumns), r]));
    const rightByKey = new Map(rightRows.map((r) => [keyOf(r, keyColumns), r]));
    const breaks: ReconBreak[] = [];

    for (const [key, lrow] of leftByKey) {
        const rrow = rightByKey.get(key);
        if (!rrow) {
            breaks.push({ key, type: 'missing_right', status: 'open' });
            continue;
        }
        for (const c of compareColumns) {
            if (withinTolerance(lrow[c.column], rrow[c.column], c)) continue;
            const a = Number(lrow[c.column]);
            const b = Number(rrow[c.column]);
            breaks.push({
                key,
                type: 'value_break',
                column: c.column,
                leftValue: lrow[c.column],
                rightValue: rrow[c.column],
                diff: Number.isNaN(a) || Number.isNaN(b) ? undefined : b - a,
                status: 'open',
            });
        }
    }
    for (const [key] of rightByKey) {
        if (!leftByKey.has(key)) breaks.push({ key, type: 'missing_left', status: 'open' });
    }
    return breaks;
}

/** Roll a break set up into the report cards. */
export function summarize(
    breaks: ReconBreak[],
    leftRows: number,
    rightRows: number,
    matchedKeys: number,
    now: Date = new Date(),
): ReconSummary {
    const byType: Record<BreakType, number> = {
        missing_left: 0,
        missing_right: 0,
        value_break: 0,
        cardinality_break: 0,
    };
    const byAge: Record<AgeBucket, number> = { '0-30': 0, '30-60': 0, '60-90': 0, '90+': 0, unknown: 0 };
    let open = 0,
        resolved = 0,
        autoClosed = 0;
    for (const b of breaks) {
        if (b.status === 'auto_closed') autoClosed++;
        else {
            byType[b.type]++;
            if (b.status === 'resolved') resolved++;
            else {
                open++;
                // Aging counts OPEN breaks only: a resolved or auto-closed break is settled work, and
                // including it would make the backlog look older the more of it you cleared.
                byAge[ageBucketOf(b, now)]++;
            }
        }
    }
    return { leftRows, rightRows, matchedKeys, open, resolved, autoClosed, byType, byAge };
}

/** Count keys present on both sides (for the summary's matched count). */
export function matchedKeyCount(
    keyColumns: string[],
    leftRows: Record<string, unknown>[],
    rightRows: Record<string, unknown>[],
): number {
    const right = new Set(rightRows.map((r) => keyOf(r, keyColumns)));
    let n = 0;
    for (const l of leftRows) if (right.has(keyOf(l, keyColumns))) n++;
    return n;
}

/** Build a fresh reconciliation definition (id = slug + suffix; nothing references it by id yet). */
export function buildReconciliation(
    name: string,
    leftDataset: string,
    rightDataset: string,
    keyColumns: string[],
    compareColumns: CompareColumn[],
): Reconciliation {
    const slug =
        name
            .trim()
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, '_')
            .replace(/^_+|_+$/g, '') || 'reconciliation';
    const suffix = Math.random().toString(36).slice(2, 6);
    return {
        id: `${slug}_${suffix}`,
        name: name.trim(),
        leftDataset,
        rightDataset,
        keyColumns,
        compareColumns,
    };
}
