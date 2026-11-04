import type { ColDef } from 'ag-grid-community';
import type { EntitySummaryCounterpart, EntitySummaryResult } from '@inspecto/link-analysis/api/inv.service';
import { presetNumber } from './presets';

/**
 * LA-ENTITY-SUMMARY-1 - the pure half of the entity summary: its sentences, the counterpart tables' columns and the activity
 * bars. No Angular, no HTTP. A part the Dataset does not map is not shown; nothing unmeasured reads as a zero.
 */

const n = (v: number): string => v.toLocaleString('en');

/** The headline in one sentence: `12 links (9 out, 3 in) with 4 counterparts, 2026-10-01 to 2026-10-05.` */
export function summaryHeadline(s: EntitySummaryResult | null): string {
    if (!s?.assessed || s.links === undefined) return '';
    if (s.links === 0) return `Not in this Dataset: no link names it.`;
    const seen = s.firstSeen && s.lastSeen ? `, ${s.firstSeen.slice(0, 10)} to ${s.lastSeen.slice(0, 10)}` : '';
    return (
        `${n(s.links)} link${s.links === 1 ? '' : 's'} (${n(s.linksOut ?? 0)} out, ${n(s.linksIn ?? 0)} in) with ` +
        `${n(s.counterparts ?? 0)} counterpart${s.counterparts === 1 ? '' : 's'}${seen}.`
    );
}

/** The primary Measure in one line: `amount: 35 USD sent, 1,500 USD received`; a side no row of which held a number is
 *  left out (never a 0 nobody summed); '' without a Measure. */
export function summaryMeasureLine(s: EntitySummaryResult | null): string {
    const m = s?.measure;
    if (!m) return '';
    const unit = m.unit ? ` ${m.unit}` : '';
    const parts = [];
    if (m.out !== null) parts.push(`${presetNumber(m.out)}${unit} sent`);
    if (m.in !== null) parts.push(`${presetNumber(m.in)}${unit} received`);
    return `${m.column}: ${parts.length ? parts.join(', ') : 'no number in its links'}`;
}

/** A counterparts table's columns; the amount column only when the Dataset has a Measure. */
export function summaryCounterpartColumns(withAmount: boolean): ColDef<EntitySummaryCounterpart>[] {
    const fmt = (p: { value: unknown }) => presetNumber(p.value);
    const cols: ColDef<EntitySummaryCounterpart>[] = [
        { field: 'entity', headerName: 'Counterpart' },
        { field: 'links', headerName: 'Links', valueFormatter: fmt },
        { field: 'out', headerName: 'Out', valueFormatter: fmt },
        { field: 'in', headerName: 'In', valueFormatter: fmt },
    ];
    if (withAmount) cols.push({ field: 'amount', headerName: 'Amount', valueFormatter: fmt });
    return cols;
}

/** One activity bar: the day, its links and its height as a share of the busiest day (0..100). */
export interface ActivityBar {
    day: string;
    links: number;
    pct: number;
}

/** The activity bars, oldest first, each scaled to the busiest day. */
export function activityBars(s: EntitySummaryResult | null): ActivityBar[] {
    const days = s?.activity ?? [];
    const max = Math.max(0, ...days.map((d) => d.links));
    return days.map((d) => ({
        day: d.day,
        links: d.links,
        pct: max ? Math.max(4, Math.round((d.links / max) * 100)) : 0,
    }));
}

/** The activity in words, for a screen reader and the caption: active days and the busiest one. */
export function activityLine(s: EntitySummaryResult | null): string {
    const days = s?.activity ?? [];
    if (!days.length) return '';
    const top = days.reduce((a, b) => (b.links > a.links ? b : a));
    return `Active on ${n(days.length)} day${days.length === 1 ? '' : 's'}; the busiest was ${top.day} with ${n(top.links)} link${top.links === 1 ? '' : 's'}.`;
}
