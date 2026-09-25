import { describe, expect, it } from 'vitest';
import {
    buildTreemap,
    layoutTreemap,
    Rect,
    squarify,
    TREEMAP_DEFAULT_LIMIT,
    TREEMAP_HEADER,
    TREEMAP_OTHER,
    TreemapRow,
} from './treemap-layout';

const EPS = 1e-6;
const area = (r: Rect): number => r.w * r.h;

function inside(r: Rect, b: Rect): boolean {
    return r.x >= b.x - EPS && r.y >= b.y - EPS && r.x + r.w <= b.x + b.w + EPS && r.y + r.h <= b.y + b.h + EPS;
}

function overlap(a: Rect, b: Rect): number {
    const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
    const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
    return w > 0 && h > 0 ? w * h : 0;
}

function expectNoOverlaps(rects: Rect[]): void {
    for (let i = 0; i < rects.length; i++)
        for (let j = i + 1; j < rects.length; j++) expect(overlap(rects[i], rects[j])).toBeLessThan(EPS);
}

describe('squarify', () => {
    const bounds: Rect = { x: 10, y: 20, w: 600, h: 400 };
    // The classic Bruls et al. example, plus a long tail.
    const values = [6, 6, 4, 3, 2, 2, 1, 0.5, 0.25, 12, 9];

    it('gives each value an area proportional to it, and together they fill the bounds', () => {
        const rects = squarify(values, bounds);
        const total = values.reduce((s, v) => s + v, 0);
        rects.forEach((r, i) => {
            const expected = (values[i] / total) * area(bounds);
            expect(Math.abs(area(r) - expected) / expected).toBeLessThan(1e-6);
        });
        expect(rects.reduce((s, r) => s + area(r), 0)).toBeCloseTo(area(bounds), 6);
    });

    it('keeps every rectangle inside the bounds and none overlap', () => {
        const rects = squarify(values, bounds);
        rects.forEach((r) => expect(inside(r, bounds)).toBe(true));
        expectNoOverlaps(rects);
    });

    it('returns rectangles in INPUT order, places the largest first (top-left), and is deterministic', () => {
        const a = squarify(values, bounds);
        const b = squarify(values, bounds);
        expect(a).toEqual(b);
        const largest = values.indexOf(Math.max(...values));
        expect(a[largest].x).toBe(bounds.x);
        expect(a[largest].y).toBe(bounds.y);
        // Ties place by input index: of the two sixes, the first sits at or before the second.
        const [i6, j6] = [values.indexOf(6), values.lastIndexOf(6)];
        expect(a[i6].x + a[i6].y).toBeLessThanOrEqual(a[j6].x + a[j6].y);
    });

    it('keeps aspect ratios reasonable (the point of squarifying) for comparable values', () => {
        const rects = squarify([5, 5, 5, 5], { x: 0, y: 0, w: 200, h: 200 });
        rects.forEach((r) => expect(Math.max(r.w / r.h, r.h / r.w)).toBeLessThan(1.01));
    });

    it('gives zero, negative and non-numeric values an empty rectangle and lays out the rest', () => {
        const rects = squarify([4, 0, -3, NaN, 4], { x: 0, y: 0, w: 100, h: 50 });
        [1, 2, 3].forEach((i) => expect(area(rects[i])).toBe(0));
        expect(area(rects[0])).toBeCloseTo(2500, 6);
        expect(area(rects[4])).toBeCloseTo(2500, 6);
    });

    it('returns only empty rectangles for an empty box or no positive value', () => {
        expect(squarify([1, 2], { x: 0, y: 0, w: 0, h: 10 }).every((r) => area(r) === 0)).toBe(true);
        expect(squarify([0, -1], bounds).every((r) => area(r) === 0)).toBe(true);
        expect(squarify([], bounds)).toEqual([]);
    });
});

describe('buildTreemap', () => {
    it('orders groups largest first, sums duplicates and totals what is shown', () => {
        const tree = buildTreemap([
            { group: 'IRSF', value: 3 },
            { group: 'Wangiri', value: 5 },
            { group: 'IRSF', value: 4 },
        ]);
        expect(tree.groups.map((g) => [g.name, g.value])).toEqual([
            ['IRSF', 7],
            ['Wangiri', 5],
        ]);
        expect(tree.total).toBe(12);
        expect(tree.excluded).toBe(0);
    });

    it('excludes zero, negative and non-numeric values and counts them', () => {
        const tree = buildTreemap([
            { group: 'a', value: 10 },
            { group: 'b', value: 0 },
            { group: 'c', value: -4 },
            { group: 'd', value: NaN },
        ]);
        expect(tree.groups.map((g) => g.name)).toEqual(['a']);
        expect(tree.excluded).toBe(3);
        expect(tree.total).toBe(10);
    });

    it('folds everything past the limit into one "Other" holding their sum and count', () => {
        const rows: TreemapRow[] = Array.from({ length: 8 }, (_, i) => ({ group: `g${i}`, value: 80 - i * 10 }));
        const tree = buildTreemap(rows, 5);
        expect(tree.groups.map((g) => g.name)).toEqual(['g0', 'g1', 'g2', 'g3', TREEMAP_OTHER]);
        const other = tree.groups[4];
        expect(other.value).toBe(40 + 30 + 20 + 10);
        expect(other.folded).toBe(4);
        expect(tree.total).toBe(rows.reduce((s, r) => s + r.value, 0));
    });

    it('does not fold when the groups fit the limit exactly, and defaults the limit to 20', () => {
        const five: TreemapRow[] = Array.from({ length: 5 }, (_, i) => ({ group: `g${i}`, value: i + 1 }));
        expect(buildTreemap(five, 5).groups.some((g) => g.folded)).toBe(false);
        const many: TreemapRow[] = Array.from({ length: 30 }, (_, i) => ({ group: `g${i}`, value: i + 1 }));
        const tree = buildTreemap(many);
        expect(tree.groups.length).toBe(TREEMAP_DEFAULT_LIMIT);
        expect(tree.groups[TREEMAP_DEFAULT_LIMIT - 1].folded).toBe(11);
    });

    it('nests two levels: each group sums its subgroups, both levels largest first', () => {
        const tree = buildTreemap([
            { group: 'SIM box', subgroup: 'Retail', value: 2 },
            { group: 'SIM box', subgroup: 'Online', value: 6 },
            { group: 'IRSF', subgroup: 'Roaming', value: 5 },
            { group: 'SIM box', subgroup: 'Online', value: 1 },
            { group: 'IRSF', subgroup: 'Retail', value: -1 },
        ]);
        expect(tree.groups.map((g) => [g.name, g.value])).toEqual([
            ['SIM box', 9],
            ['IRSF', 5],
        ]);
        expect(tree.groups[0].children).toEqual([
            { name: 'Online', value: 7 },
            { name: 'Retail', value: 2 },
        ]);
        expect(tree.excluded).toBe(1);
    });
});

describe('layoutTreemap', () => {
    it('draws one tile per group whose area is its share of the box', () => {
        const tree = buildTreemap([
            { group: 'a', value: 3 },
            { group: 'b', value: 1 },
        ]);
        const tiles = layoutTreemap(tree, 400, 200);
        expect(tiles.map((t) => [t.name, t.level, t.frame])).toEqual([
            ['a', 1, false],
            ['b', 1, false],
        ]);
        expect(area(tiles[0])).toBeCloseTo(60000, 6);
        expect(tiles[0].share).toBeCloseTo(0.75, 9);
    });

    it('places subgroups inside their group frame, below its header band, without overlapping', () => {
        const tree = buildTreemap([
            { group: 'SIM box', subgroup: 'Online', value: 6 },
            { group: 'SIM box', subgroup: 'Retail', value: 3 },
            { group: 'SIM box', subgroup: 'Dealer', value: 1 },
            { group: 'IRSF', subgroup: 'Roaming', value: 10 },
        ]);
        const tiles = layoutTreemap(tree, 600, 300);
        const frame = tiles.find((t) => t.level === 1 && t.name === 'SIM box')!;
        expect(frame.frame).toBe(true);
        const kids = tiles.filter((t) => t.level === 2 && t.group === 'SIM box');
        expect(kids.map((k) => k.name)).toEqual(['Online', 'Retail', 'Dealer']);
        const body: Rect = { x: frame.x, y: frame.y + TREEMAP_HEADER, w: frame.w, h: frame.h - TREEMAP_HEADER };
        kids.forEach((k) => expect(inside(k, body)).toBe(true));
        expectNoOverlaps(kids);
        // Within the group, subgroup areas keep their proportions.
        expect(area(kids[0]) / area(kids[1])).toBeCloseTo(2, 6);
        // Shares are of the whole treemap, not of the group.
        expect(kids[0].share).toBeCloseTo(6 / 20, 9);
    });

    it('gives the folded group a key of its own, so a real group called "Other" does not collide', () => {
        const rows: TreemapRow[] = [
            { group: 'Other', value: 50 },
            ...Array.from({ length: 4 }, (_, i) => ({ group: `g${i}`, value: 10 - i })),
        ];
        const keys = layoutTreemap(buildTreemap(rows, 3), 300, 200).map((t) => t.key);
        expect(new Set(keys).size).toBe(keys.length);
    });

    it('lays out nothing in an empty box', () => {
        expect(layoutTreemap(buildTreemap([{ group: 'a', value: 1 }]), 0, 100)).toEqual([]);
    });
});
