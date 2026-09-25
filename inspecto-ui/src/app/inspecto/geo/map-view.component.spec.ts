import { SimpleChanges } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { getWorkerUrl } from 'maplibre-gl';
import ANGULAR_JSON from '../../../../angular.json';
import { ensureMaplibreSetup, MapViewComponent, maplibreWorkerUrl } from './map-view.component';
import { GeoData } from './geo-types';

const CONFIG_PROVIDER = { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } };

const DATA: GeoData = {
    points: [
        { id: 'a', lat: 23.8, lon: 90.4, kind: 'tower', label: 'A' },
        { id: 'b', lat: 51.5, lon: -0.13, kind: 'device', label: 'B' },
    ],
    routes: [],
};

// jsdom has no WebGL, so the MapLibre map never mounts (the guarded path) — these specs cover
// the component shell; the live map is verified in the browser (/design gallery).
describe('MapViewComponent', () => {
    it('stays unmounted without data and passes axe', async () => {
        await TestBed.configureTestingModule({
            imports: [MapViewComponent],
            providers: [CONFIG_PROVIDER],
        }).compileComponents();
        const fixture = TestBed.createComponent(MapViewComponent);
        fixture.componentInstance.data = null;
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('tolerates data + input changes where WebGL is unavailable', async () => {
        await TestBed.configureTestingModule({
            imports: [MapViewComponent],
            providers: [CONFIG_PROVIDER],
        }).compileComponents();
        const fixture = TestBed.createComponent(MapViewComponent);
        fixture.componentInstance.data = DATA;
        fixture.detectChanges();
        // guarded mount: no canvas, no throw; export/fit are safe no-ops
        expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
        expect(fixture.componentInstance.exportPng()).toBeNull();
        fixture.componentInstance.fitToData();
        fixture.componentInstance.data = { points: [], routes: [] };
        fixture.componentInstance.ngOnChanges({} as SimpleChanges);
        expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
    });
});

// DW-16b: maplibre-gl 6 looks for its worker beside its own module URL — inside our bundle a file the
// build never emitted, so every map painted only its background. The worker is now copied to
// assets/maplibre/ (with the shared module it imports) and MapLibre is pointed there before any map.
describe('MapLibre worker', () => {
    it('is served from assets/maplibre, resolved against the base href', () => {
        expect(maplibreWorkerUrl()).toBe(new URL('assets/maplibre/maplibre-gl-worker.mjs', document.baseURI).href);
    });

    it('is the worker MapLibre uses once the map host has set up', () => {
        ensureMaplibreSetup();
        expect(getWorkerUrl()).toBe(maplibreWorkerUrl());
    });

    it('is copied into the build together with the shared module it imports', () => {
        const assets = ANGULAR_JSON.projects.gamma.architect.build.options.assets as unknown[];
        const copied = assets
            .filter((a): a is { glob: string; input: string; output: string } => typeof a === 'object')
            .filter((a) => a.input === 'node_modules/maplibre-gl/dist' && a.output === 'assets/maplibre')
            .map((a) => a.glob);
        expect(copied).toEqual(expect.arrayContaining(['maplibre-gl-worker.mjs', 'maplibre-gl-shared.mjs']));
    });
});
