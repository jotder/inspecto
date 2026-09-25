import { statusTone } from 'app/inspecto/components/status-badge.component';
import { CHART_CATEGORICAL_NEUTRAL, CHART_TONE } from 'app/inspecto/theme/chart-tokens';

/**
 * UIE-2 — colour by MEANING, then by NAME, never by position.
 *
 * - A label with a status tone ({@link statusTone}: Fail, Red, Critical, Amber, Pass, Green, Open, …) takes that
 *   tone's colour, so a chart agrees with the status badges in the table beside it.
 * - Any other label takes a colour derived from its text, from a palette without red or green. The same label is
 *   the same colour in every widget, and a chart never repeats a colour while a free one remains.
 *
 * Deterministic for a given label set, so colours survive a reload and an unrelated filter.
 */
export function seriesColors(labels: readonly string[]): string[] {
    const palette = CHART_CATEGORICAL_NEUTRAL;
    const used = new Set<string>();
    const out: string[] = new Array(labels.length);
    const neutral: number[] = [];
    labels.forEach((label, i) => {
        const tone = statusTone(label);
        if (tone === 'neutral') {
            neutral.push(i);
        } else {
            out[i] = CHART_TONE[tone];
            used.add(out[i]);
        }
    });
    for (const i of neutral) {
        let slot = hash(labels[i]) % palette.length;
        for (let tries = 0; tries < palette.length && used.has(palette[slot]); tries++) slot = (slot + 1) % palette.length;
        out[i] = palette[slot];
        used.add(out[i]);
    }
    return out;
}

/** A small, stable string hash (FNV-1a) — the same label always lands on the same palette slot. */
function hash(text: string): number {
    let h = 0x811c9dc5;
    for (let i = 0; i < text.length; i++) {
        h ^= text.charCodeAt(i);
        h = Math.imul(h, 0x01000193);
    }
    return h >>> 0;
}
