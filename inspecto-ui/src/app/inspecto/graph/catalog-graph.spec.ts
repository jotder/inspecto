import { describe, expect, it } from 'vitest';
import { MetadataEdge, MetadataNode, NodeKind } from 'app/inspecto/api';
import { NODE_KIND_FALLBACK } from 'app/inspecto/theme/chart-tokens';
import { CATALOG_NODE_KINDS, legendFor, nodeColor, nodeKindLabel, nodeShape, toG6Data } from './catalog-graph';

/** Built-in node types registered by G6 v5 (`@antv/g6/lib/elements/nodes`). */
const G6_NODE_TYPES = ['circle', 'rect', 'ellipse', 'diamond', 'triangle', 'hexagon', 'star', 'donut', 'image', 'html'];

const node = (id: string, kind: string, label = id): MetadataNode => ({ id, kind: kind as NodeKind, label });

const edge = (from: string, to: string, kind: string): MetadataEdge => ({
    from,
    to,
    kind: kind as MetadataEdge['kind'],
});

describe('nodeShape', () => {
    it('maps known kinds to built-in G6 node types', () => {
        expect(nodeShape('STREAM')).toBe('circle');
        expect(nodeShape('TABLE')).toBe('rect');
        expect(nodeShape('KPI')).toBe('diamond');
        expect(nodeShape('REPORT')).toBe('hexagon');
        expect(nodeShape('ENRICHMENT')).toBe('triangle');
    });

    it('falls back to a circle for unknown kinds', () => {
        expect(nodeShape('NO_SUCH_KIND' as NodeKind)).toBe('circle');
    });

    it('only ever names a built-in G6 node type', () => {
        for (const kind of CATALOG_NODE_KINDS) expect(G6_NODE_TYPES).toContain(nodeShape(kind));
    });

    // 🔴 The real risk is a SILENT fallthrough, not a wrong shape: an unhandled kind returns 'circle'
    // from `default:` and looks deliberate. STREAM is the only kind whose intended shape IS a circle,
    // so pinning the circle set to exactly [STREAM] makes any missing `case` fail loudly.
    it('gives every catalog kind an explicit shape — only STREAM is a circle', () => {
        expect(CATALOG_NODE_KINDS.filter((k) => nodeShape(k) === 'circle')).toEqual(['STREAM']);
    });

    it('separates the three Studio BI kinds by shape, since they share a hue family', () => {
        expect(new Set(['DATASET', 'WIDGET', 'DASHBOARD'].map(nodeShape)).size).toBe(3);
    });
});

describe('nodeColor', () => {
    it('returns a hex colour for known and unknown kinds', () => {
        expect(nodeColor('STREAM')).toMatch(/^#[0-9A-F]{6}$/i);
        expect(nodeColor('WIDGET' as NodeKind)).toMatch(/^#[0-9A-F]{6}$/i);
    });

    it('gives each known kind a distinct colour', () => {
        const kinds: NodeKind[] = ['STREAM', 'SCHEMA', 'TABLE', 'COLUMN', 'KPI', 'REPORT', 'ENRICHMENT'];
        expect(new Set(kinds.map(nodeColor)).size).toBe(kinds.length);
    });

    it('gives every catalog kind an accent colour, never the fallback grey', () => {
        expect(CATALOG_NODE_KINDS.filter((k) => nodeColor(k) === NODE_KIND_FALLBACK)).toEqual([]);
    });

    it('keeps every catalog kind mutually distinct in colour', () => {
        expect(new Set(CATALOG_NODE_KINDS.map(nodeColor)).size).toBe(CATALOG_NODE_KINDS.length);
    });

    // SCHEMA (pipeline-editor synthetic) and RAW_SCHEMA (catalog wire) are one concept in two
    // spellings — the ONE intentional collision, so a future "de-duplicate the palette" edit
    // cannot quietly split them.
    it('styles the two Schema spellings identically', () => {
        expect(nodeColor('RAW_SCHEMA')).toBe(nodeColor('SCHEMA'));
        expect(nodeShape('RAW_SCHEMA')).toBe(nodeShape('SCHEMA'));
        expect(nodeKindLabel('RAW_SCHEMA')).toBe(nodeKindLabel('SCHEMA'));
    });
});

describe('nodeKindLabel', () => {
    it('prints the GLOSSARY term, not the raw enum token', () => {
        expect(nodeKindLabel('DERIVED_TABLE')).toBe('Matrix');
        expect(nodeKindLabel('REFERENCE_DATASET')).toBe('Reference Dataset');
        expect(nodeKindLabel('RAW_SCHEMA')).toBe('Schema');
        expect(nodeKindLabel('DASHBOARD')).toBe('Dashboard');
    });

    // KPI is the single kind whose canonical GLOSSARY term IS its enum token, so it is the only
    // member allowed in this set. A new kind added without a label lands here and fails.
    it('leaves no catalog kind printing its raw enum token', () => {
        expect(CATALOG_NODE_KINDS.filter((k) => nodeKindLabel(k) === k)).toEqual(['KPI']);
    });

    // ⛔ GLOSSARY §5 D-4 retires the user-facing term: "author no new user-facing 'Enrichment' copy".
    it('does not label ENRICHMENT, whose user-facing term is retired', () => {
        expect(nodeKindLabel('ENRICHMENT')).toBe('ENRICHMENT');
    });

    it('still falls back to the raw token for an unmapped kind', () => {
        expect(nodeKindLabel('NO_SUCH_KIND' as NodeKind)).toBe('NO_SUCH_KIND');
    });
});

describe('toG6Data', () => {
    it('carries id/label/kind into node data', () => {
        const { nodes } = toG6Data([node('t1', 'TABLE', 'Orders')], []);
        expect(nodes).toEqual([{ id: 't1', data: { label: 'Orders', kind: 'TABLE' } }]);
    });

    it('maps from/to to source/target and keeps the edge kind', () => {
        const { edges } = toG6Data([], [edge('a', 'b', 'EMITS')]);
        expect(edges[0].source).toBe('a');
        expect(edges[0].target).toBe('b');
        expect(edges[0].data.kind).toBe('EMITS');
    });

    it('produces unique ids even for parallel edges between the same pair', () => {
        const { edges } = toG6Data(
            [],
            [edge('a', 'b', 'EMITS'), edge('a', 'b', 'REFERENCES'), edge('a', 'b', 'EMITS')],
        );
        expect(new Set(edges.map((e) => e.id)).size).toBe(3);
    });
});

describe('legendFor', () => {
    it('lists each distinct kind once, in first-seen order', () => {
        const legend = legendFor([
            node('t1', 'TABLE'),
            node('c1', 'COLUMN'),
            node('t2', 'TABLE'),
            node('s1', 'STREAM'),
        ]);
        expect(legend.map((l) => l.kind)).toEqual(['TABLE', 'COLUMN', 'STREAM']);
        expect(legend[0].fill).toBe(nodeColor('TABLE'));
    });
});
