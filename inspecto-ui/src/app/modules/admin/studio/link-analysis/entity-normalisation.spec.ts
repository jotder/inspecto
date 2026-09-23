import { describe, expect, it } from 'vitest';
import { normalizeEntityKey } from 'app/inspecto/graph';
import { coLocationGraph, coLocations } from 'app/inspecto/geo/geo-analysis';
import { GeoPoint } from 'app/inspecto/geo/geo-types';
import { ProjectedGraph, projectEntities, projectTriples, splitIdentityGroups } from './entity-projection';

/**
 * Decision D-S4 (2026-09-23): Link Analysis stays value-projected, but entity ids are NORMALISED by one
 * shared key (`normalizeEntityKey`) at every mint site, the display label keeps a raw spelling, and the
 * split-identity notice keeps reporting the spellings that were folded together.
 */
const MAP = { datasetId: 'd', sourceCol: 'from', targetCol: 'to' };
const SPELLINGS = ['ACME Ltd', 'acme ltd', ' Acme  Ltd.'];

describe('normalizeEntityKey', () => {
    it('folds case, collapses internal whitespace, strips trailing punctuation and trims', () => {
        expect(SPELLINGS.map(normalizeEntityKey)).toEqual(['acme ltd', 'acme ltd', 'acme ltd']);
    });
});

describe('entity id normalisation (D-S4)', () => {
    it('projectEntities folds three spellings into one node with summed edge counts', () => {
        const rows = SPELLINGS.map((s) => ({ from: s, to: 'Beta' }));
        const g = projectEntities(rows, MAP) as ProjectedGraph;

        expect(g.nodes.map((n) => n.id).sort()).toEqual(['entity:acme ltd', 'entity:beta']);
        expect(g.edges).toHaveLength(1);
        expect(g.edges[0].data['count']).toBe(3);
        // the display is a raw spelling, never the lowercase key
        expect(g.nodes.find((n) => n.id === 'entity:acme ltd')?.data.label).toBe('ACME Ltd');
    });

    it('projectTriples folds three spellings into one node with summed edge counts', () => {
        const triples = SPELLINGS.map((s) => ({ source: s, target: 'Beta', kind: null, count: 2 }));
        const g = projectTriples(triples, false);

        expect(g.nodes.map((n) => n.id).sort()).toEqual(['entity:acme ltd', 'entity:beta']);
        expect(g.edges).toHaveLength(1);
        expect(g.edges[0].data['count']).toBe(6);
        expect(g.nodes.find((n) => n.id === 'entity:acme ltd')?.data.label).toBe('ACME Ltd');
    });

    it('the split-identity notice still reports the folded spellings', () => {
        const rows = SPELLINGS.map((s) => ({ from: s, to: 'Beta' }));
        const g = projectEntities(rows, MAP) as ProjectedGraph;
        const groups = splitIdentityGroups(g);

        expect(groups).toHaveLength(1);
        expect(groups[0].key).toBe('acme ltd');
        expect(groups[0].spellings).toEqual(['ACME Ltd', 'acme ltd', 'Acme  Ltd.']);
    });

    it('coLocationGraph mints the same id as the projection', () => {
        const HOUR = 3_600_000;
        const pts: GeoPoint[] = [
            { id: 'p0', lat: 23.81, lon: 90.41, kind: 'point', label: ' Acme  Ltd.', time: HOUR },
            { id: 'p1', lat: 23.81, lon: 90.41, kind: 'point', label: 'Beta', time: HOUR },
        ];
        const g = coLocationGraph(coLocations(pts, 300, HOUR));
        const projected = projectEntities([{ from: 'ACME Ltd', to: 'Beta' }], MAP) as ProjectedGraph;

        expect(g.nodes.map((n) => n.id).sort()).toEqual(projected.nodes.map((n) => n.id).sort());
        // edges must point at the minted node ids, not at raw labels
        const ids = new Set(g.nodes.map((n) => n.id));
        expect(g.edges.every((e) => ids.has(e.source) && ids.has(e.target))).toBe(true);
        expect(g.nodes.find((n) => n.id === 'entity:acme ltd')?.data.label).toBe(' Acme  Ltd.');
    });
});
