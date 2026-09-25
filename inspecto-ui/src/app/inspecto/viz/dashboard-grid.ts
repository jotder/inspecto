/**
 * UIE-3 — the dashboard grid: FOUR columns. A tile's `span` is how many it takes: 1 = a quarter (a KPI strip holds
 * four), 2 = half, 3 = three quarters, 4 = the full row. Until 2026-09-25 the grid had two columns and a span meant
 * 1 = half, 2 = full, so four KPI tiles took two rows and a quarter of the screen each (demo review F-05); stored
 * spans were migrated 1 → 2, 2 → 4 in the same change.
 *
 * ONE rule behind the three places a dashboard renders (the editor, the menu view, the share viewer), so a tile is
 * the same width wherever it is seen. Framework-free.
 */

import type { VizRender } from './viz-types';

export type TileSpan = 1 | 2 | 3 | 4;

export const GRID_COLUMNS = 4;

/** The gap between tiles, in rem — the `gap-4` every host uses. */
const GAP_REM = 1;

/** A stored span as a valid one: 1–4, rounded; anything else (absent, 0, "wide") reads as half. */
export function tileSpan(span: unknown): TileSpan {
    const n = Math.round(Number(span));
    return n >= 1 && n <= GRID_COLUMNS ? (n as TileSpan) : 2;
}

/** The tile's `flex-basis` in a `flex-wrap gap-4` row: `span` columns plus the gaps between them. */
export function tileBasis(span: unknown): string {
    const s = tileSpan(span);
    if (s === GRID_COLUMNS) return '100%';
    const gaps = (GRID_COLUMNS - 1) * GAP_REM;
    return `calc((100% - ${gaps}rem) * ${s} / ${GRID_COLUMNS} + ${(s - 1) * GAP_REM}rem)`;
}

/** The next width when a tile's width button is pressed: quarter → half → three quarters → full → quarter. */
export function nextSpan(span: unknown): TileSpan {
    return ((tileSpan(span) % GRID_COLUMNS) + 1) as TileSpan;
}

/** How the width is said in a tooltip or an aria-label. */
export function spanLabel(span: unknown): string {
    return ({ 1: 'quarter width', 2: 'half width', 3: 'three-quarter width', 4: 'full width' } as const)[tileSpan(span)];
}

/** What a tile will hold once its data arrives — picks the tile card's loading skeleton. */
export type TileShape = 'kpi' | 'chart' | 'table';

/** The tile shape for a Widget's render kind: the KPI number (plain or with its trend), a grid, or (everything
 *  else) a chart. */
export function tileShapeOf(render: VizRender | undefined): TileShape {
    if (render?.kind === 'component' && (render.componentKey === 'kpi' || render.componentKey === 'kpi-trend'))
        return 'kpi';
    return render?.kind === 'aggrid' ? 'table' : 'chart';
}
