import { fmtDateTime } from '../format/format';
import { AnomalyBand, AnomalyFeature, AnomalyHistoryPoint } from '../api/anomaly-scores.service';

/** The status-badge tone and label of a band. */
export function bandBadge(band: AnomalyBand): { value: string; label: string } {
    switch (band) {
        case 'high':
            return { value: 'error', label: 'High' };
        case 'elevated':
            return { value: 'warning', label: 'Elevated' };
        default:
            return { value: 'success', label: 'Normal' };
    }
}

/** Features by contribution, largest first; `share` is each one's percentage of the total contribution. */
export function rankedFeatures(features: AnomalyFeature[]): (AnomalyFeature & { share: number })[] {
    const total = features.reduce((s, f) => s + Math.max(0, f.contribution ?? 0), 0);
    return [...features]
        .sort((a, b) => (b.contribution ?? 0) - (a.contribution ?? 0))
        .map((f) => ({ ...f, share: total > 0 ? (Math.max(0, f.contribution ?? 0) / total) * 100 : 0 }));
}

/**
 * SVG polyline points for the score history (served newest first), drawn oldest → newest left to right on a
 * `width` × `height` box with the score axis fixed at 0..100. One point per period (a re-run of the same period replaces its earlier run);
 * fewer than two periods draws nothing.
 */
export function sparklinePoints(history: AnomalyHistoryPoint[], width: number, height: number): string {
    if (onePerPeriod(history).length < 2) return '';
    const runs = onePerPeriod(history).reverse();
    const step = width / (runs.length - 1);
    return runs
        .map((h, i) => {
            const v = Math.min(100, Math.max(0, Number(h.score) || 0));
            return `${(i * step).toFixed(1)},${(height - (v / 100) * height).toFixed(1)}`;
        })
        .join(' ');
}

/**
 * A server timestamp (`2026-10-13 23:54:15.428051`, `2026-10-12 00:00:00.0`) as the app's one date-time spelling
 * (`13 Oct 2026, 23:54:15`); `dateOnly` keeps just the day (`12 Oct 2026`) for a period start. The value is
 * normalised to `T` + milliseconds first (the microsecond form is not portable), and one that does not parse comes
 * back as written. Callers keep the raw value in a `title`.
 */
export function stampLabel(raw: string | null | undefined, dateOnly = false): string {
    if (!raw) return '';
    if (!/^\d{4}-\d{2}-\d{2}/.test(raw.trim())) return raw;
    const iso = raw
        .trim()
        .replace(' ', 'T')
        .replace(/(\.\d{3})\d+/, '$1');
    const label = fmtDateTime(iso);
    return dateOnly && label !== iso ? label.split(',')[0] : label;
}

/** The history one point per `period_start`, the latest run winning (served newest first, so the first seen). */
export function onePerPeriod(history: AnomalyHistoryPoint[]): AnomalyHistoryPoint[] {
    const seen = new Set<string>();
    return history.filter((h) => !seen.has(h.periodStart) && !!seen.add(h.periodStart));
}
