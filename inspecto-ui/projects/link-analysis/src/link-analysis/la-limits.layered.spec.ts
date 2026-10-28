import { LAYERED_MAX_RANK_WIDTH, layeredShapeReason, layeredWidestRank } from './la-limits';

/** LA-DAGRE-WIDE-SHALLOW-FIT-1: a providers → claims graph stacks hundreds of nodes in one Layered rank. */
function bipartite(providers: number, claims: number) {
    const nodes = [
        ...Array.from({ length: providers }, (_, i) => ({ id: `p${i}` })),
        ...Array.from({ length: claims }, (_, i) => ({ id: `c${i}` })),
    ];
    const edges = Array.from({ length: claims }, (_, i) => ({ source: `p${i % providers}`, target: `c${i}` }));
    return { nodes, edges };
}

describe('Layered rank width', () => {
    it('counts the widest rank of a wide, shallow graph', () => {
        expect(layeredWidestRank(bipartite(20, 300))).toBe(300);
    });

    it('ranks a chain one node per rank, and survives a cycle', () => {
        const g = { nodes: [{ id: 'a' }, { id: 'b' }, { id: 'c' }], edges: [
            { source: 'a', target: 'b' }, { source: 'b', target: 'c' }, { source: 'c', target: 'a' },
        ] };
        expect(layeredWidestRank(g)).toBe(1);
    });

    it('disables Layered with the reason only when a rank is over the limit', () => {
        expect(layeredShapeReason(bipartite(20, 300))).toContain('300 nodes in one rank');
        expect(layeredShapeReason(bipartite(5, LAYERED_MAX_RANK_WIDTH))).toBeNull();
        expect(layeredShapeReason(null)).toBeNull();
    });
});
