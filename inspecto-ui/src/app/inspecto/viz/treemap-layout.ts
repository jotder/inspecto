/**
 * The `treemap` Visualization Type's pure core — no Angular, no DOM. Three steps:
 *
 * 1. {@link buildTreemap} turns the plugin's normalised rows into a one- or two-level tree: sums duplicate rows,
 *    drops zero / negative / non-numeric values (and counts them, so the text alternative can say so), orders
 *    groups largest-first and folds everything past `limit` into one "Other" group.
 * 2. {@link squarify} is the squarified layout (Bruls, Huizing & van Wijk, 2000): rectangles whose areas are
 *    proportional to the values, filling the bounds, with aspect ratios kept close to 1.
 * 3. {@link layoutTreemap} places the groups in the box, then each group's subgroups inside its rectangle.
 */

export interface Rect {
    x: number;
    y: number;
    w: number;
    h: number;
}

/** One normalised row from the plugin's `transformProps`: the level-1 name, the optional level-2 name, the value. */
export interface TreemapRow {
    group: string;
    subgroup?: string;
    value: number;
}

export interface TreemapLeaf {
    name: string;
    value: number;
}

export interface TreemapGroup {
    name: string;
    value: number;
    /** Level-2 subgroups, largest first. Empty for a one-level treemap and for the folded "Other" group. */
    children: TreemapLeaf[];
    /** The folded "Other" group: how many groups it stands for. Absent on a real group. */
    folded?: number;
}

export interface TreemapTree {
    groups: TreemapGroup[];
    /** The sum of every value shown — the denominator of each share. */
    total: number;
    /** Rows left out because their value was zero, negative or not a number. */
    excluded: number;
}

/** The default number of groups before the rest fold into "Other" (`options.treemap.limit`). */
export const TREEMAP_DEFAULT_LIMIT = 20;

/** The folded group's name. */
export const TREEMAP_OTHER = 'Other';

/** Build the tree. `limit` < 1 or absent ⇒ {@link TREEMAP_DEFAULT_LIMIT}. The fold keeps `limit` groups in total:
 *  the largest `limit - 1` plus "Other" — unless the groups fit exactly, when nothing folds. */
export function buildTreemap(rows: readonly TreemapRow[], limit?: number): TreemapTree {
    const max = limit != null && limit >= 1 ? Math.floor(limit) : TREEMAP_DEFAULT_LIMIT;
    let excluded = 0;
    const byGroup = new Map<string, Map<string, number> | number>();
    for (const r of rows) {
        if (!Number.isFinite(r.value) || r.value <= 0) {
            excluded++;
            continue;
        }
        const existing = byGroup.get(r.group);
        if (r.subgroup === undefined) {
            byGroup.set(r.group, (typeof existing === 'number' ? existing : 0) + r.value);
        } else {
            const kids = existing instanceof Map ? existing : new Map<string, number>();
            kids.set(r.subgroup, (kids.get(r.subgroup) ?? 0) + r.value);
            byGroup.set(r.group, kids);
        }
    }
    const all: TreemapGroup[] = [...byGroup.entries()].map(([name, v]) => {
        if (typeof v === 'number') return { name, value: v, children: [] };
        const children = [...v.entries()].map(([n, value]) => ({ name: n, value })).sort(byValueDesc);
        return { name, value: children.reduce((s, c) => s + c.value, 0), children };
    });
    all.sort(byValueDesc);
    const groups = all.length > max ? [...all.slice(0, max - 1), other(all.slice(max - 1))] : all;
    return { groups, total: groups.reduce((s, g) => s + g.value, 0), excluded };
}

function other(rest: TreemapGroup[]): TreemapGroup {
    return { name: TREEMAP_OTHER, value: rest.reduce((s, g) => s + g.value, 0), children: [], folded: rest.length };
}

/** Largest first; a tie keeps first-seen order (Array.prototype.sort is stable). */
function byValueDesc(a: { value: number }, b: { value: number }): number {
    return b.value - a.value;
}

/**
 * Squarified layout. Returns one rectangle per value, IN INPUT ORDER; a zero, negative or non-finite value gets an
 * empty rectangle at the bounds' origin. Values are placed largest-first (ties by input index), so the result is
 * deterministic, and the positive rectangles tile the bounds exactly (up to floating-point error).
 */
export function squarify(values: readonly number[], bounds: Rect): Rect[] {
    const out: Rect[] = values.map(() => ({ x: bounds.x, y: bounds.y, w: 0, h: 0 }));
    const order = values
        .map((_, i) => i)
        .filter((i) => Number.isFinite(values[i]) && values[i] > 0)
        .sort((a, b) => values[b] - values[a] || a - b);
    const total = order.reduce((s, i) => s + values[i], 0);
    if (!order.length || bounds.w <= 0 || bounds.h <= 0) return out;
    const scale = (bounds.w * bounds.h) / total;
    const area = (i: number): number => values[i] * scale;

    let { x, y, w, h } = bounds;
    let row: number[] = [];
    let rowArea = 0;

    /** The worst aspect ratio in a row laid along a side of length `side`. */
    const worst = (items: number[], sum: number, side: number): number => {
        let maxA = 0;
        let minA = Infinity;
        for (const i of items) {
            maxA = Math.max(maxA, area(i));
            minA = Math.min(minA, area(i));
        }
        const s2 = sum * sum;
        const side2 = side * side;
        return Math.max((side2 * maxA) / s2, s2 / (side2 * minA));
    };

    /** Lay the row along the shorter side of the remaining box, then shrink the box past it. */
    const place = (items: number[], sum: number, last: boolean): void => {
        if (w >= h) {
            // A column on the left, as wide as the row's area needs.
            const thick = last ? w : sum / h;
            let cy = y;
            items.forEach((i, k) => {
                const len = k === items.length - 1 ? y + h - cy : area(i) / thick;
                out[i] = { x, y: cy, w: thick, h: len };
                cy += len;
            });
            x += thick;
            w -= thick;
        } else {
            // A row along the top.
            const thick = last ? h : sum / w;
            let cx = x;
            items.forEach((i, k) => {
                const len = k === items.length - 1 ? x + w - cx : area(i) / thick;
                out[i] = { x: cx, y, w: len, h: thick };
                cx += len;
            });
            y += thick;
            h -= thick;
        }
    };

    for (let k = 0; k < order.length; ) {
        const i = order[k];
        const side = Math.min(w, h);
        if (!row.length || worst([...row, i], rowArea + area(i), side) <= worst(row, rowArea, side)) {
            row.push(i);
            rowArea += area(i);
            k++;
        } else {
            place(row, rowArea, false);
            row = [];
            rowArea = 0;
        }
    }
    // The final row takes whatever is left, so rounding never leaves a sliver of empty box.
    place(row, rowArea, true);
    return out;
}

/** One drawn rectangle. `level` 1 = a group (drawn when it has no subgroups, or as the frame behind them),
 *  2 = a subgroup inside its group. */
export interface TreemapTile extends Rect {
    key: string;
    level: 1 | 2;
    /** The level-1 group this tile belongs to. */
    group: string;
    /** The tile's own name: the group's for level 1, the subgroup's for level 2. */
    name: string;
    value: number;
    /** value / total of the whole treemap, 0–1. */
    share: number;
    /** Position among its siblings, largest first — drives the level-2 tint. */
    index: number;
    /** A level-1 tile that frames subgroups (drawn behind them, with a header band when there is room). */
    frame: boolean;
    /** The folded "Other" group: how many groups it stands for. */
    folded?: number;
}

/** The group header band's height (px) when a framed group is tall and wide enough to carry its name. */
export const TREEMAP_HEADER = 18;
const HEADER_MIN_H = 48;
const HEADER_MIN_W = 72;
/** The inset between a group's frame and its subgroups (px). */
const INSET = 2;

/** Lay the tree out in a `width` × `height` box: groups first, then each group's subgroups inside it. */
export function layoutTreemap(tree: TreemapTree, width: number, height: number): TreemapTile[] {
    const tiles: TreemapTile[] = [];
    if (!tree.groups.length || tree.total <= 0 || width <= 0 || height <= 0) return tiles;
    const rects = squarify(
        tree.groups.map((g) => g.value),
        { x: 0, y: 0, w: width, h: height },
    );
    tree.groups.forEach((g, gi) => {
        const r = rects[gi];
        const frame = g.children.length > 0;
        tiles.push({
            ...r,
            // The folded group gets its own key, so a real group that happens to be called "Other" cannot collide.
            key: g.folded ? 'other' : `g:${g.name}`,
            level: 1,
            group: g.name,
            name: g.name,
            value: g.value,
            share: g.value / tree.total,
            index: gi,
            frame,
            ...(g.folded ? { folded: g.folded } : {}),
        });
        if (!frame) return;
        const header = hasHeader(r) ? TREEMAP_HEADER : INSET;
        const inner: Rect = {
            x: r.x + INSET,
            y: r.y + header,
            w: Math.max(0, r.w - 2 * INSET),
            h: Math.max(0, r.h - header - INSET),
        };
        const kids = squarify(
            g.children.map((c) => c.value),
            inner,
        );
        g.children.forEach((c, ci) =>
            tiles.push({
                ...kids[ci],
                key: `s:${g.name}\u0000${c.name}`,
                level: 2,
                group: g.name,
                name: c.name,
                value: c.value,
                share: c.value / tree.total,
                index: ci,
                frame: false,
            }),
        );
    });
    return tiles;
}

/** Whether a framed group of this size carries a header band with its name (else its subgroups fill it). */
export function hasHeader(tile: Rect): boolean {
    return tile.h >= HEADER_MIN_H && tile.w >= HEADER_MIN_W;
}
