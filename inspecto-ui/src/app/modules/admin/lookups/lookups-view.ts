import { LookupMiss, LookupRow } from 'app/inspecto/api';

/** One grid row of the Lookups screen: a keyed lookup Step and its last run's counts, summed over its mappings. */
export interface LookupGridRow {
    pipeline: string;
    step: string;
    reference: string;
    match: string;
    lookedUpBy: string;
    lastRunAt: string | null;
    outcome: string | null;
    hits: number | null;
    misses: number | null;
    /** misses / (hits + misses), 0..1; null when nothing was looked up. */
    missRate: number | null;
    referenceRows: number | null;
    nullKeys: number | null;
    /** The receipt cut some counts to fit, so the sums may be short. */
    truncated: boolean;
    /** The last run's unmatched-key sample, fingerprints and shapes only. */
    unmatched: LookupMiss[];
    /** The in-memory test index: ready, building, failed or cold. */
    indexState: string;
    /** Its estimated memory in bytes, when ready. */
    indexBytes: number | null;
    /** Why it was refused, when failed. */
    indexError: string | null;
}

/** Sums `lookup.<on>.hit|miss` across the Step's mappings; `reference.*` read as they are. */
export function toGridRow(row: LookupRow): LookupGridRow {
    const run = row.lastRun ?? null;
    let hits: number | null = null;
    let misses: number | null = null;
    for (const [name, n] of Object.entries(run?.counts ?? {})) {
        if (!name.startsWith('lookup.')) continue;
        if (name.endsWith('.hit')) hits = (hits ?? 0) + n;
        else if (name.endsWith('.miss')) misses = (misses ?? 0) + n;
    }
    // A receipt keeps at most 8 counts: when some were cut, a sum would understate the misses, so show none.
    if (run?.truncated) hits = misses = null;
    const looked = (hits ?? 0) + (misses ?? 0);
    return {
        pipeline: row.pipeline,
        step: row.step,
        reference: row.reference ?? '',
        match: row.match.replace('_', ' '),
        lookedUpBy: row.lookedUpBy.join(', '),
        lastRunAt: run?.startedAt ?? null,
        outcome: run?.outcome ?? null,
        hits,
        misses,
        missRate: looked > 0 ? (misses ?? 0) / looked : null,
        referenceRows: run?.counts['reference.rows'] ?? null,
        nullKeys: run?.counts['reference.null_keys'] ?? null,
        truncated: run?.truncated ?? false,
        unmatched: row.unmatched ?? [],
        indexState: row.index?.state ?? 'cold',
        indexBytes: row.index?.bytes ?? null,
        indexError: row.index?.error ?? null,
    };
}
