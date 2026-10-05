import { G6GraphData } from '@inspecto/core/graph';
import { GraphSelection } from '@inspecto/link-analysis/graph/graph-analysis';

/** How many equal slices the playback divides the timeline's range into (fixed: a knob nobody asked for). */
export const PLAYBACK_SLICES = 20;

/** One step of the playback: the half-open range `[start, end)` in epoch millis (the LAST slice also includes `end`). */
export interface PlaybackSlice {
    index: number;
    count: number;
    start: number;
    end: number;
    last: boolean;
}

/** A degenerate range (one instant) is a single slice; otherwise {@link PLAYBACK_SLICES}. */
export function playbackSliceCount(extent: readonly [number, number]): number {
    return extent[1] > extent[0] ? PLAYBACK_SLICES : 1;
}

/** Slice `index` of the range `extent`, clamped into `[0, count)`. */
export function playbackSlice(extent: readonly [number, number], index: number): PlaybackSlice {
    const count = playbackSliceCount(extent);
    const i = Math.min(count - 1, Math.max(0, Math.trunc(index)));
    const width = (extent[1] - extent[0]) / count;
    const last = i === count - 1;
    return { index: i, count, start: extent[0] + i * width, end: last ? extent[1] : extent[0] + (i + 1) * width, last };
}

/**
 * The links of `g` whose date in the `attrs[col]` column falls in `slice`, with their endpoints. A READ: the graph is
 * never changed, it is only highlighted. Same date rule as `filterByTime` (an unparseable or missing date is never active).
 */
export function activeInSlice(g: G6GraphData, col: string, slice: PlaybackSlice): GraphSelection {
    const edgeIds: string[] = [];
    const nodeIds = new Set<string>();
    for (const e of g.edges) {
        const t = Date.parse(e.data.attrs?.[col] ?? '');
        if (!Number.isFinite(t) || t < slice.start || (slice.last ? t > slice.end : t >= slice.end)) continue;
        edgeIds.push(e.id);
        nodeIds.add(e.source);
        nodeIds.add(e.target);
    }
    return { nodeIds: [...nodeIds], edgeIds };
}

/** True when the system asks for reduced motion; false where `matchMedia` is missing (SSR, jsdom). */
export function prefersReducedMotion(): boolean {
    return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
        ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
        : false;
}
