import { describe, expect, it } from 'vitest';
import { GeoPoint } from 'app/inspecto/geo';
import { entityId } from './entity-projection';
import { GeoLinkBrushService, nodeIdsForKeys, pointIdsForNodes } from './geo-link-brush';

/**
 * LA-22: Geo ↔ Link brushing is keyed by the threaded entity key (D-U3), never by a display label and
 * never by the positional `GeoPoint.id`.
 */
function pt(id: string, key?: string, label?: string): GeoPoint {
    return { id, lat: 0, lon: 0, kind: 'point', key, label };
}

describe('nodeIdsForKeys (Geo → Link)', () => {
    it('maps a geo key to the node the projection minted for the same value', () => {
        const nodes = new Set([entityId(undefined, 'IMSI-1'), entityId(undefined, 'IMSI-2')]);
        expect(nodeIdsForKeys(['IMSI-1'], nodes, [undefined])).toEqual([entityId(undefined, 'IMSI-1')]);
    });

    it('matches a type-scoped node only through the mapping entity type', () => {
        const nodes = new Set([entityId('person', 'Bob'), entityId('account', 'Bob')]);
        expect(nodeIdsForKeys(['Bob'], nodes, ['person'])).toEqual([entityId('person', 'Bob')]);
    });

    it('matches a spelling variant of the key, because ids are normalised (D-S4)', () => {
        const acme = entityId(undefined, 'ACME Ltd');
        expect(nodeIdsForKeys([' acme  ltd.'], new Set([acme]), [undefined])).toEqual([acme]);
    });
});

describe('pointIdsForNodes (Link → Geo)', () => {
    it('highlights the points whose KEY maps to a selected node, ignoring their labels', () => {
        const points = [pt('pt:0', 'K1', 'Somebody'), pt('pt:1', 'K2', 'K1'), pt('pt:2', undefined, 'K1')];
        expect(pointIdsForNodes(points, new Set([entityId(undefined, 'K1')]), [undefined])).toEqual(['pt:0']);
    });
});

describe('GeoLinkBrushService', () => {
    it('holds the latest brush and clears it', () => {
        const s = new GeoLinkBrushService();
        s.fromGeo(['K1', 'K1', '']);
        expect(s.brush()).toEqual({ origin: 'geo', keys: ['K1'] });
        s.fromLink(['entity:K1'], [undefined]);
        expect(s.brush()).toEqual({ origin: 'link', nodeIds: ['entity:K1'], entityTypes: [undefined] });
        s.clear();
        expect(s.brush()).toBeNull();
    });
});
