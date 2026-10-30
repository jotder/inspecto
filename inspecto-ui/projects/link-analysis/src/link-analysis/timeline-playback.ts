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

/** The window widths the strip offers, trailing the handle (the slice's end). `slice` = the slice alone; `all` = from the start. */
export type PlaybackWindow = 'slice' | '1d' | '7d' | '30d' | 'all';

const DAY_MS = 86_400_000;
const WIDTH_MS: Record<'1d' | '7d' | '30d', number> = { '1d': DAY_MS, '7d': 7 * DAY_MS, '30d': 30 * DAY_MS };

/** The faintest an in-window link fades to: above the 0.2 the canvas dims an out-of-window link to, so it stays legible. */
export const AGE_FADE_FLOOR = 0.45;

/**
 * The range the strip highlights for `slice` and `window`: the slice itself, or `window` back from the handle (the slice's
 * end), never before the data's start. Same half-open rule as a slice (the last one includes its end).
 */
export function playbackWindow(
    extent: readonly [number, number],
    slice: PlaybackSlice,
    window: PlaybackWindow,
): PlaybackSlice {
    if (window === 'slice') return slice;
    const start = window === 'all' ? extent[0] : Math.max(extent[0], slice.end - WIDTH_MS[window]);
    return { ...slice, start: Math.min(start, slice.start) };
}

/**
 * Age fade: each active link's opacity by its age back from the handle — 1 at the handle, {@link AGE_FADE_FLOOR} at the
 * window's start — so the newest activity stands out. A link with no parseable date is not in the map.
 */
export function ageOpacity(
    g: G6GraphData,
    col: string,
    range: PlaybackSlice,
    edgeIds: readonly string[],
): Map<string, number> {
    const ids = new Set(edgeIds);
    const span = range.end - range.start;
    const out = new Map<string, number>();
    for (const e of g.edges) {
        if (!ids.has(e.id)) continue;
        const t = Date.parse(e.data.attrs?.[col] ?? '');
        if (!Number.isFinite(t)) continue;
        const age = span > 0 ? Math.min(1, Math.max(0, (range.end - t) / span)) : 0;
        out.set(e.id, Math.round((1 - (1 - AGE_FADE_FLOOR) * age) * 100) / 100);
    }
    return out;
}

/** The Investigation `window` op that seals `range` (`[from, to)`, UTC ISO instants; the last slice's end is kept in). */
export function sealedWindow(range: PlaybackSlice): { from: string; to: string } {
    return {
        from: new Date(Math.ceil(range.start)).toISOString(),
        to: new Date(Math.ceil(range.end) + (range.last ? 1 : 0)).toISOString(),
    };
}
