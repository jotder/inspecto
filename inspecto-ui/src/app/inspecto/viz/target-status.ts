import { formatNumber, NumberFormat } from './number-format';

/** Which direction of a value is good (`options.kpi.better`). */
export type Better = 'higher' | 'lower';

/**
 * "Target 99 — below target": a value against its target (`options.kpi.target`), in the widget's format and in
 * words, so tone is never the only signal (WCAG 1.4.1). Shared by the KPI tile and the Gauge. `null` without a
 * finite target. Framework-free.
 */
export function targetStatus(
    value: number,
    target: number | undefined,
    better: Better,
    format?: NumberFormat,
): { text: string; met: boolean } | null {
    if (target == null || !Number.isFinite(target)) return null;
    const met = better === 'higher' ? value >= target : value <= target;
    const where = met ? 'on target' : better === 'higher' ? 'below target' : 'above target';
    return { text: `Target ${formatNumber(target, format)} — ${where}`, met };
}

/** The parts of a KPI projection the tile reads (`KpiProjection` in `api/kpis.service.ts`). */
export interface ProjectionLike {
    status: 'projected' | 'none';
    reason?: string;
    value?: number;
    lower?: number;
    upper?: number;
    level?: number;
    probability?: number | null;
    band?: 'GREEN' | 'AMBER' | 'RED' | null;
}

/**
 * The projection in words (BI-INSIGHTS-1 I-12), never colour alone: `badge` is the RAG of the landing ("Projected:
 * Amber", toned by the shared badge; `null` when the landing has no RAG), `text` the landing with its interval and the
 * chance of meeting the target ("Lands at 1.2M (1.0M–1.4M, 80 % range) · 72 % chance of meeting the target"), or "No
 * projection: <reason>". Framework-free.
 */
export function projectionStatus(
    p: ProjectionLike | null | undefined,
    format?: NumberFormat,
): { text: string; badge: { value: string; label: string } | null } | null {
    if (!p) return null;
    if (p.status !== 'projected' || p.value == null)
        return { text: `No projection: ${p.reason ?? 'unknown'}`, badge: null };
    const level = p.level == null ? '' : `, ${formatNumber(p.level * 100, { decimals: 0 })} % range`;
    const range =
        p.lower != null && p.upper != null && p.upper > p.lower
            ? ` (${formatNumber(p.lower, format)}–${formatNumber(p.upper, format)}${level})`
            : '';
    const chance =
        p.probability == null
            ? ''
            : ` · ${formatNumber(p.probability * 100, { decimals: 0 })} % chance of meeting the target`;
    const badge = p.band
        ? { value: p.band, label: `Projected: ${p.band.charAt(0)}${p.band.slice(1).toLowerCase()}` }
        : null;
    return { text: `Lands at ${formatNumber(p.value, format)}${range}${chance}`, badge };
}
