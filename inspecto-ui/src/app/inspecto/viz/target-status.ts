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
