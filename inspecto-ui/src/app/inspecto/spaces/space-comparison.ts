import type { ColDef } from 'ag-grid-community';
import type {
    SpaceComparisonAxis,
    SpaceComparisonRequest,
    SpaceComparisonResult,
} from '../api/space-comparison.service';
import { fmtBytes } from '../format/format';

/**
 * Pure shaping for the Space comparison dialog (`space.comparison` Job Type) — the request built from
 * the form, the run's terminal test, and the result as table columns and plain-language lines.
 */

/** The run is finished once it leaves `RUNNING` (SUCCESS / FAILED / SKIPPED / ERROR). */
export function isTerminalRunStatus(status: string | null | undefined): boolean {
    return !!status && status !== 'RUNNING';
}

/**
 * The `POST /space-comparisons` body. A blank or non-positive bound is left out so the server's own
 * default applies; an empty axes list means every axis, so it is left out too.
 */
export function comparisonRequest(
    spaces: string[],
    opts: { windowDays?: unknown; top?: unknown; axes?: unknown },
): SpaceComparisonRequest {
    const body: SpaceComparisonRequest = { spaces: [...spaces] };
    const windowDays = positiveInt(opts.windowDays);
    const top = positiveInt(opts.top);
    if (windowDays !== undefined) body.window_days = windowDays;
    if (top !== undefined) body.top = top;
    const axes = Array.isArray(opts.axes) ? opts.axes.map((a) => String(a).trim()).filter(Boolean) : [];
    if (axes.length) body.axes = axes;
    return body;
}

function positiveInt(v: unknown): number | undefined {
    if (v === null || v === undefined || v === '') return undefined;
    const n = Number(v);
    return Number.isInteger(n) && n >= 1 ? n : undefined;
}

/** A signed growth rate: `+1.5 MB/day`, `−200 B/day`, `0 B/day`. */
export function fmtGrowth(bytesPerDay: number): string {
    const sign = bytesPerDay > 0 ? '+' : bytesPerDay < 0 ? '−' : '';
    return `${sign}${fmtBytes(Math.abs(bytesPerDay))}/day`;
}

/** One line saying how much of the request could be compared. */
export function comparisonSummary(r: SpaceComparisonResult): string {
    const days = r.windowDays === 1 ? '1 day' : `${r.windowDays} days`;
    return `${r.comparable.length} of ${r.spaces.length} Spaces compared over the last ${days}.`;
}

/** The Spaces left out, each with the server's reason. */
export function notComparableList(r: SpaceComparisonResult): { space: string; reason: string }[] {
    return Object.entries(r.notComparable ?? {}).map(([space, reason]) => ({ space, reason }));
}

/**
 * Grid columns for the per-axis rows: axis, spread, fastest grower, then size and growth per compared
 * Space. Values are read with a getter, never a `field`, because a dotted field is a nested path.
 */
export function comparisonColumns(r: SpaceComparisonResult): ColDef<SpaceComparisonAxis>[] {
    const cols: ColDef<SpaceComparisonAxis>[] = [
        { colId: 'axis', headerName: 'Storage axis', valueGetter: (p) => p.data?.axis },
        {
            colId: 'spread',
            headerName: 'Spread',
            headerTooltip: 'Largest minus smallest latest size across the compared Spaces',
            valueGetter: (p) => p.data?.spreadBytes,
            valueFormatter: (p) => (typeof p.value === 'number' ? fmtBytes(p.value) : ''),
        },
        { colId: 'fastest', headerName: 'Fastest growing', valueGetter: (p) => p.data?.fastest ?? '' },
    ];
    for (const space of r.comparable) {
        cols.push(
            {
                colId: `${space}:bytes`,
                headerName: `${space} size`,
                valueGetter: (p) => p.data?.spaces?.[space]?.bytes ?? 0,
                valueFormatter: (p) => fmtBytes(Number(p.value ?? 0)),
            },
            {
                colId: `${space}:rate`,
                headerName: `${space} growth`,
                valueGetter: (p) => p.data?.spaces?.[space]?.bytesPerDay ?? 0,
                valueFormatter: (p) => fmtGrowth(Number(p.value ?? 0)),
            },
        );
    }
    return cols;
}
