import { ENTITY_TYPE_GLYPH } from '@inspecto/core/graph/catalog-graph';
import { baseEdgeKind, EdgePattern } from '@inspecto/core/graph/graph-view.component';
import type { G6GraphData } from '@inspecto/core/graph/graph-types';
import { canvasTheme, ICON_COLOR_SWATCHES } from '@inspecto/core/theme/chart-tokens';
import { SUPER_NODE_KIND } from '@inspecto/link-analysis/graph/graph-analysis';

/**
 * The look Link Analysis gives each node type and link type on its own, so a graph of several types reads without any
 * Display setting. Everything is a SUGGESTION layered under the analyst's own choices: the page merges it below the
 * per-kind overrides, so an explicit colour, shape or pattern always wins.
 *
 * The rules:
 * - The MOST COMMON node type and the most common link type keep the neutral default look: they are the background,
 *   and the rare types are what the eye should find. Ties are broken by name, so the choice is deterministic.
 * - A type's look starts from a hash of its NAME, never from the order it appeared in, so the same type looks the same
 *   across redraws and expands. Two types that hash to the same look are separated by probing, the MORE COMMON type
 *   claiming its slot first (ties by name): so a rarer type that turns up never moves a commoner one, and a type only
 *   changes look when it clashes with one at least as common. Distinctness is kept over absolute stability: two types
 *   never share both colour and shape.
 * - No automatic colour equals, or comes close to, the default link colour in either colour scheme (a link type must
 *   never look neutral by accident: `AUTO_COLORS` drops the swatches within {@link NEUTRAL_DISTANCE} of it).
 * - Colour is never the only channel: a node type also gets a shape (not the circle the common type keeps), a link type
 *   also gets a line pattern.
 * - A type with a source-stamped colour keeps it (no auto colour), and an Entity Type that has a glyph keeps its icon
 *   tile (no auto shape: a shape override would switch the tile off).
 */
export interface AutoStyle {
    nodeColors: Record<string, string>;
    nodeShapes: Record<string, string>;
    edgeColors: Record<string, string>;
    edgePatterns: Record<string, EdgePattern>;
}

/** G6 shapes handed to the less common node types; `circle` stays with the most common one. */
export const AUTO_NODE_SHAPES: readonly string[] = ['diamond', 'rect', 'triangle', 'hexagon', 'star'];
/** Line patterns handed to the less common link types; solid is among them, with a colour of its own. */
export const AUTO_EDGE_PATTERNS: readonly EdgePattern[] = ['dashed', 'dotted', 'solid'];

const EMPTY: AutoStyle = { nodeColors: {}, nodeShapes: {}, edgeColors: {}, edgePatterns: {} };

/** `#rrggbb` as [r, g, b]. */
export function rgbOf(hex: string): [number, number, number] {
    const n = parseInt(hex.replace('#', ''), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** How close (Euclidean RGB distance, of 441) a colour may come to the default link colour before it reads as neutral. */
export const NEUTRAL_DISTANCE = 40;
const NEUTRAL_LINK_COLORS = [canvasTheme(false).edge, canvasTheme(true).edge].map(rgbOf);
/** The colours the automatic look draws from: the shared swatches, minus any too close to a default link colour. */
export const AUTO_COLORS: readonly string[] = ICON_COLOR_SWATCHES.filter((c) => {
    const [r, g, b] = rgbOf(c);
    return NEUTRAL_LINK_COLORS.every(([nr, ng, nb]) => Math.hypot(r - nr, g - ng, b - nb) >= NEUTRAL_DISTANCE);
});

/** FNV-1a, 32 bit: a stable, well spread number for a type name. */
export function hashName(name: string): number {
    let h = 0x811c9dc5;
    for (let i = 0; i < name.length; i++) {
        h ^= name.charCodeAt(i);
        h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h >>> 0;
}

/** The most common key; ties go to the alphabetically first. */
function commonest(counts: Map<string, number>): string | null {
    let best: string | null = null;
    for (const [k, c] of [...counts.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
        if (best === null || c > (counts.get(best) ?? 0)) best = k;
    }
    return best;
}

/** The kinds by how many there are, most first, ties by name: the order in which they claim a slot. */
function byCommonness(counts: Map<string, number>, skip: string | null): string[] {
    return [...counts.keys()]
        .filter((k) => k !== skip)
        .sort((a, b) => (counts.get(b) ?? 0) - (counts.get(a) ?? 0) || a.localeCompare(b));
}

/** Slot per name in `size` slots: the hashed slot, else the next free one; `names` come in the order they claim slots. */
function slots(names: string[], size: number): Map<string, number> {
    const taken = new Set<number>();
    const out = new Map<string, number>();
    for (const name of names) {
        let s = hashName(name) % size;
        for (let tries = 0; taken.has(s) && tries < size; tries++) s = (s + 1) % size;
        taken.add(s);
        out.set(name, s);
    }
    return out;
}

export function autoStyle(g: G6GraphData | null): AutoStyle {
    if (!g) return EMPTY;
    const nodeCounts = new Map<string, number>();
    const stamped = new Set<string>();
    for (const n of g.nodes) {
        const kind = String(n.data.kind);
        if (kind === SUPER_NODE_KIND) continue; // a stand-in, not a type
        nodeCounts.set(kind, (nodeCounts.get(kind) ?? 0) + 1);
        if (n.data.color) stamped.add(kind);
    }
    const edgeCounts = new Map<string, number>();
    for (const e of g.edges) {
        const kind = baseEdgeKind(e.data.kind);
        edgeCounts.set(kind, (edgeCounts.get(kind) ?? 0) + 1);
    }

    const out: AutoStyle = { nodeColors: {}, nodeShapes: {}, edgeColors: {}, edgePatterns: {} };
    const colors = AUTO_COLORS;

    const nodeWinner = commonest(nodeCounts);
    const nodeKinds = byCommonness(nodeCounts, nodeWinner);
    const nodeSlots = slots(nodeKinds, colors.length * AUTO_NODE_SHAPES.length);
    for (const kind of nodeKinds) {
        const s = nodeSlots.get(kind)!;
        if (!stamped.has(kind)) out.nodeColors[kind] = colors[s % colors.length];
        if (!ENTITY_TYPE_GLYPH[kind]) out.nodeShapes[kind] = AUTO_NODE_SHAPES[Math.floor(s / colors.length)];
    }

    const edgeWinner = commonest(edgeCounts);
    const edgeKinds = byCommonness(edgeCounts, edgeWinner);
    const edgeSlots = slots(edgeKinds, colors.length * AUTO_EDGE_PATTERNS.length);
    for (const kind of edgeKinds) {
        const s = edgeSlots.get(kind)!;
        out.edgeColors[kind] = colors[s % colors.length];
        out.edgePatterns[kind] = AUTO_EDGE_PATTERNS[Math.floor(s / colors.length)];
    }
    return out;
}
