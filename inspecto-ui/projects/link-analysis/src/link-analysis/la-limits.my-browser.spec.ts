import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { SessionService, SpacesService, apiUrl } from '@inspecto/core/api';
import { G6GraphData } from '@inspecto/core/graph/index';
import { frequentLocations } from '@inspecto/link-analysis/geo/geo-analysis';
import { GeoPoint } from '@inspecto/link-analysis/geo/geo-types';
import { closenessCentrality } from '@inspecto/link-analysis/graph/graph-analysis';
import { LaLimitItem, laLimit, resetLaLimits } from './la-limits';
import { LaLimitsService } from './la-limits.service';
import { defaultLayoutFor } from './link-analysis.component';

/**
 * LA-CONFIG-LIMITS-1 CL-5 / CL-14: the analyst's OWN value for a `browser:true` item - the display caps and the
 * per-algorithm caps - changes what THIS browser does, and is clamped to `[min, the Space's value]`. Asserted on the
 * behaviour each value governs, not just on the number the service hands back.
 */
const browserItem = (key: string, inForce: number, min = 1): LaLimitItem => ({
    key,
    layer: 'space',
    browser: true,
    group: key.split('.')[0],
    unit: 'nodes',
    default: inForce,
    min,
    max: 100_000,
    value: null,
    inForce,
    editable: false,
    description: '…',
});

const ITEMS = [
    browserItem('display.layered_max_nodes', 30),
    browserItem('display.geo_point_cap', 5000),
    browserItem('display.lod_label_cap', 300, 10),
    browserItem('browser_algorithms.closenessCentrality', 1000),
];

function chain(n: number): G6GraphData {
    const nodes = Array.from({ length: n }, (_, i) => ({ id: `c${i}`, data: { label: `c${i}`, kind: 'entity' } }));
    const edges = Array.from({ length: n - 1 }, (_, i) => ({ id: `e${i}`, source: `c${i}`, target: `c${i + 1}`, data: { kind: 'link' } }));
    return { nodes, edges } as G6GraphData;
}

/** `n` sightings of one entity at one spot: one frequent location of `min(n, cap)` visits. */
const sightings = (n: number): GeoPoint[] =>
    Array.from({ length: n }, (_, i) => ({ id: `pt:${i}`, lat: 1, lon: 1, kind: 'phone', label: 'A' }));

describe('My browser: a personal value changes this browser and is clamped to the Space (CL-5, CL-14)', () => {
    let svc: LaLimitsService;

    beforeEach(() => {
        localStorage.clear();
        resetLaLimits();
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                { provide: SpacesService, useValue: { currentSpaceId: signal('s1') } },
                { provide: SessionService, useValue: { actor: signal('ana') } },
            ],
        });
        svc = TestBed.inject(LaLimitsService);
        TestBed.tick();
        TestBed.inject(HttpTestingController).expectOne(apiUrl('/settings/link-analysis/limits')).flush({ items: ITEMS });
    });

    afterEach(() => {
        localStorage.clear();
        resetLaLimits();
    });

    it('display items are browser items read through effective()', () => {
        for (const k of ['display.layered_max_nodes', 'display.geo_point_cap', 'display.lod_label_cap'] as const) {
            expect(svc.item(k)?.browser, k).toBe(true);
            expect(laLimit(k)).toBe(svc.effective(k));
        }
    });

    it('layered layout: a lower personal cap opens a graph in Force; a higher one is clamped to the Space value', () => {
        const g = chain(20);
        expect(defaultLayoutFor(g, false)).toBe('dagre'); // the probe that would otherwise succeed
        svc.setPersonal('display.layered_max_nodes', 10);
        expect(defaultLayoutFor(g, false)).toBe('force');
        svc.setPersonal('display.layered_max_nodes', 500); // above the Space's 30
        expect(svc.effective('display.layered_max_nodes')).toBe(30);
        expect(defaultLayoutFor(chain(31), false)).toBe('force');
        expect(defaultLayoutFor(g, false)).toBe('dagre');
    });

    it('geo point cap: a personal value bounds what one analysis reads', () => {
        expect(frequentLocations(sightings(40), 100)[0].count).toBe(40);
        svc.setPersonal('display.geo_point_cap', 25);
        expect(frequentLocations(sightings(40), 100)[0].count).toBe(25);
    });

    it('LOD label cap: clamped to [min, Space value]', () => {
        svc.setPersonal('display.lod_label_cap', 50);
        expect(laLimit('display.lod_label_cap')).toBe(50);
        svc.setPersonal('display.lod_label_cap', 2);
        expect(laLimit('display.lod_label_cap')).toBe(10);
        svc.setPersonal('display.lod_label_cap', 9999);
        expect(laLimit('display.lod_label_cap')).toBe(300);
        svc.resetPersonal('display.lod_label_cap');
        expect(laLimit('display.lod_label_cap')).toBe(300);
    });

    it('a per-algorithm browser cap: a personal value makes THIS browser refuse sooner, never later than the Space', () => {
        const g = chain(40);
        expect(() => closenessCentrality(g)).not.toThrow();
        svc.setPersonal('browser_algorithms.closenessCentrality', 30);
        expect(() => closenessCentrality(g)).toThrowError('Closeness is capped at 30 nodes (graph has 40).');
        svc.setPersonal('browser_algorithms.closenessCentrality', 5000);
        expect(laLimit('browser_algorithms.closenessCentrality')).toBe(1000);
    });
});
