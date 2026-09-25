import { formatNumber, NumberFormat } from './number-format';
import { Better } from './target-status';

/** Whether a change is good, bad or no change at all — the tone the shared status badge draws. */
export type DeltaTone = 'good' | 'bad' | 'flat';

/**
 * "▲ Up 12.4 % (+SAR 0.9M) vs prior period" — a value's change against a baseline, in words and arrows as well as
 * tone, so colour is never the only signal (WCAG 1.4.1). Shared by the KPI tile (baseline = the `compare` channel)
 * and the KPI trend tile (baseline = an earlier point of the series). The amount is SIGNED and sits before "vs", so
 * it cannot be read as the baseline itself; a percent value changes in POINTS, never a second relative percentage.
 * `vs` names the baseline ("prior period", "Aug 2026"). `null` without a finite baseline. Framework-free.
 */
export function kpiDelta(
    value: number,
    prev: number | undefined,
    better: Better,
    format?: NumberFormat,
    vs = 'prior period',
): { text: string; tone: DeltaTone } | null {
    if (prev == null || !Number.isFinite(prev)) return null;
    const diff = value - prev;
    if (diff === 0) return { text: `No change vs ${vs}`, tone: 'flat' };
    const up = diff > 0;
    const tone: DeltaTone = up === (better === 'higher') ? 'good' : 'bad';
    if (format?.style === 'percent') {
        const pts = formatNumber(Math.abs(diff), { decimals: 1 });
        return { text: `${up ? '▲ Up' : '▼ Down'} ${pts} pts vs ${vs}`, tone };
    }
    const pct = prev !== 0 ? ` ${formatNumber(Math.abs((diff / prev) * 100), { decimals: 1 })} %` : '';
    const amount = formatNumber(Math.abs(diff), { ...format, compact: true });
    return { text: `${up ? '▲ Up' : '▼ Down'}${pct} (${up ? '+' : '−'}${amount}) vs ${vs}`, tone };
}

/** The status-badge value for a tone: the shared badge owns status colour (PASS → success, FAIL → error). */
export function toneBadge(tone: DeltaTone): string {
    return tone === 'good' ? 'PASS' : tone === 'bad' ? 'FAIL' : '';
}
