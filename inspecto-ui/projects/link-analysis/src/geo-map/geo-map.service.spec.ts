import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ComponentsService } from '@inspecto/core/api';
import { GeoMapService, GeoMapView } from './geo-map.service';

function service(): GeoMapService {
    TestBed.configureTestingModule({ providers: [{ provide: ComponentsService, useValue: {} }] });
    return TestBed.inject(GeoMapService);
}

const VIEW: GeoMapView = { id: 'night-meetings', name: 'Night meetings', sourceId: 'dataset', query: {} };

/** LA-ONBOARD-DEFECTS-1 (5): a Geo map saved view lost its name the way a Link Analysis case did (phase 8). */
describe('GeoMapService - a saved view keeps its own name', () => {
    it('reads the name back after the registry stamps `name` with the id, as ComponentStore.write does', () => {
        const s = service();
        const stored = { ...s.toContent(VIEW), name: 'night-meetings' }; // the server's write: in-file identity == URL id
        expect(s.fromContent('night-meetings', stored).name).toBe('Night meetings');
    });

    it('an older view saved without a title still reads its stored name, else its id', () => {
        const s = service();
        expect(s.fromContent('v1', { name: 'Old name' }).name).toBe('Old name');
        expect(s.fromContent('v2', { title: '  ', name: 'v2' }).name).toBe('v2');
        expect(s.fromContent('v3', {}).name).toBe('v3');
    });
});
