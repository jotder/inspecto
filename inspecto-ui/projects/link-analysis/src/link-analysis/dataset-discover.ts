import type { ColDef } from 'ag-grid-community';
import type { DiscoverBusy, DiscoverResult } from '@inspecto/link-analysis/api/inv.service';
import { presetNumber, presetSourceLine } from './presets';

/**
 * LA-BASIC-DISCOVER-1 - the pure half of the Discover panel: the words for each section and what *Draw these* starts from.
 * No Angular, no HTTP. A section the server did not measure reads its reason, never a 0.
 */

/** A count as read: exact, or "about" when the server counted approximately. */
export function discoverCount(n: number | null | undefined, approximate = false): string {
    if (n === null || n === undefined) return '';
    return (approximate ? 'about ' : '') + n.toLocaleString('en');
}

/** The period in one line: `2026-10-01 to 2026-10-09 (9 days, by last_seen)`; '' when not measured. */
export function discoverPeriod(r: DiscoverResult | null): string {
    const p = r?.period;
    if (!p?.assessed || !p.from || !p.to) return '';
    const days =
        p.days === null || p.days === undefined ? '' : `${p.days.toLocaleString('en')} day${p.days === 1 ? '' : 's'}, `;
    return `${p.from.slice(0, 10)} to ${p.to.slice(0, 10)} (${days}by ${p.column})`;
}

/** The busiest table's columns: the entity, its links, its distinct counterparts. */
export function discoverBusyColumns(end: 'from' | 'to'): ColDef<DiscoverBusy>[] {
    const fmt = (p: { value: unknown }) => presetNumber(p.value);
    return [
        { field: 'entity', headerName: end === 'from' ? 'Sends to most' : 'Reached by most' },
        { field: 'links', headerName: 'Links', valueFormatter: fmt },
        {
            field: 'counterparts',
            headerName: end === 'from' ? 'Distinct receivers' : 'Distinct senders',
            valueFormatter: fmt,
        },
    ];
}

/** What *Draw these* starts from: the busiest entities at both ends, once each. */
export function discoverDrawValues(r: DiscoverResult | null): string[] {
    const b = r?.busiest;
    if (!b?.assessed) return [];
    return [...new Set([...b.from, ...b.to].map((x) => x.entity).filter((v) => v !== null && v !== ''))];
}

/** Where the busiest came from, when the link index answered. */
export function discoverSourceLine(r: DiscoverResult | null): string {
    return presetSourceLine(r?.busiest ?? null);
}

/** A measure's numbers in one line: `Total 1,234.5 USD · median 12 · 1,000 rows`. */
export function discoverMeasureLine(m: DiscoverResult['measures']['items'][number]): string {
    const unit = m.unit ? ` ${m.unit}` : '';
    const parts = [`Total ${presetNumber(m.sum)}${unit}`];
    if (m.median !== null) parts.push(`median ${presetNumber(m.median)}${unit}`);
    parts.push(`${m.rows.toLocaleString('en')} row${m.rows === 1 ? '' : 's'} with a number`);
    return parts.join(' · ');
}
