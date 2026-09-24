import { AlertRule, EventRow } from 'app/inspecto/api';

/**
 * Dataset freshness, DERIVED AT READ TIME (BACKLOG DUCKLE-C1-DATASET-FRESHNESS-1 residual 4).
 *
 * <p>The last publication is the newest durable `dataset.write` Signal whose source is `dataset:<id>`;
 * the limit is the `maximumAge` of a freshness Alert Rule naming that Dataset. ⛔ Nothing here is
 * persisted — `stale-tiles.ts` records why a stored stale flag is wrong — and ⛔ never read
 * `CatalogOverlay`/`latestRunTime`: a pipeline run is not a Dataset publication.
 *
 * <p>Fail-to-unknown: a failed fetch, or an empty page, is `unknown`, never `fresh` or `stale`. The
 * `source` filter is applied server-side AFTER the store page is cut, so an empty answer only means "not
 * on this page", not "never published".
 */
export type FreshnessState = 'fresh' | 'stale' | 'published' | 'unknown';

export interface DatasetFreshness {
    state: FreshnessState;
    /** Newest `dataset.write` time (epoch ms), when one was seen. */
    lastWrite: number | null;
    /** The declared `maximumAge` (e.g. `2h`), when a freshness rule names this Dataset. */
    maximumAge: string | null;
}

/** How many ledger rows to ask for — `source` is post-filtered, so `limit=1` would usually answer `[]`. */
export const FRESHNESS_SIGNAL_LIMIT = 500;

const UNIT_MS: Record<string, number> = { s: 1_000, m: 60_000, h: 3_600_000, d: 86_400_000 };

/** `Ns|Nm|Nh|Nd` → ms; anything else → null (an unreadable limit is no limit, not a verdict). */
export function parseMaximumAge(raw: string | null | undefined): number | null {
    const m = /^\s*(\d+)\s*([smhd])\s*$/i.exec(raw ?? '');
    return m ? Number(m[1]) * UNIT_MS[m[2].toLowerCase()] : null;
}

/** The freshness rule's `maximumAge` for a Dataset, or null. */
export function maximumAgeFor(rules: AlertRule[], datasetId: string): string | null {
    return rules.find((r) => r.dataset === datasetId && !!r.maximumAge)?.maximumAge ?? null;
}

/** The verdict. `rows` null = the fetch failed. */
export function deriveFreshness(
    rows: EventRow[] | null,
    maximumAge: string | null,
    now: number = Date.now(),
): DatasetFreshness {
    const times = (rows ?? []).map((r) => r.ts).filter((t) => Number.isFinite(t));
    const lastWrite = times.length ? Math.max(...times) : null;
    if (lastWrite === null) return { state: 'unknown', lastWrite: null, maximumAge };
    const limit = parseMaximumAge(maximumAge);
    if (limit === null) return { state: 'published', lastWrite, maximumAge };
    return { state: now - lastWrite <= limit ? 'fresh' : 'stale', lastWrite, maximumAge };
}

/** Badge value (a status token the shared badge tones) + label. */
export function freshnessBadge(f: DatasetFreshness): { value: string; label: string; tooltip: string } {
    const when = f.lastWrite === null ? '' : new Date(f.lastWrite).toLocaleString();
    switch (f.state) {
        case 'fresh':
            return { value: 'ok', label: 'Fresh', tooltip: `Last published ${when}; limit ${f.maximumAge}` };
        case 'stale':
            return { value: 'warning', label: 'Stale', tooltip: `Last published ${when}; limit ${f.maximumAge}` };
        case 'published':
            return {
                value: 'neutral',
                label: `Published ${when}`,
                tooltip: 'No freshness limit (maximumAge) is declared for this Dataset.',
            };
        default:
            return {
                value: 'neutral',
                label: 'Freshness unknown',
                tooltip: 'No recent publication found in the Signal ledger.',
            };
    }
}
