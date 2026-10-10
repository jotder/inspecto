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
 * `width` × `height` box with the score axis fixed at 0..100. Fewer than two runs draws nothing.
 */
export function sparklinePoints(history: AnomalyHistoryPoint[], width: number, height: number): string {
    if (history.length < 2) return '';
    const runs = [...history].reverse();
    const step = width / (runs.length - 1);
    return runs
        .map((h, i) => {
            const v = Math.min(100, Math.max(0, Number(h.score) || 0));
            return `${(i * step).toFixed(1)},${(height - (v / 100) * height).toFixed(1)}`;
        })
        .join(' ');
}
