import { SpaceIntegritySummary } from 'app/inspecto/api';
import { formatNumber } from 'app/inspecto/viz/number-format';

/** What the Spaces screen shows for one Space's integrity findings. */
export interface SpaceIntegrityView {
    total: number;
    /** The chip text: "No integrity findings", "1 integrity finding", "3 integrity findings". */
    label: string;
    /** The three numbers, for the tooltip and for screen readers. */
    detail: string;
}

/**
 * The integrity count of a Space row (`SPACE-LINK-CENSUS-1` LC-4): broken links + duplicates + files changed outside
 * the server, from the `GET /spaces` summary. `null` — show nothing — unless the report is `ready`: a Space still
 * computing, switched off or without a lock has no count, and a zero nobody measured must never be drawn.
 */
export function spaceIntegrityView(i: SpaceIntegritySummary | undefined | null): SpaceIntegrityView | null {
    if (!i || i.status !== 'ready') return null;
    const broken = i.broken ?? 0;
    const duplicates = i.duplicates ?? 0;
    const outside = i.changedOutside ?? 0;
    const total = broken + duplicates + outside;
    const label =
        total === 0
            ? 'No integrity findings'
            : total === 1
              ? '1 integrity finding'
              : `${formatNumber(total)} integrity findings`;
    const detail = `Broken links: ${formatNumber(broken)} · Duplicates: ${formatNumber(duplicates)} · Changed outside the server: ${formatNumber(outside)}`;
    return { total, label, detail };
}
