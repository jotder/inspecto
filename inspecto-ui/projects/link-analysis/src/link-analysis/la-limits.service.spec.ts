import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { SessionService, SpacesService, apiUrl } from '@inspecto/core/api';
import { LA_LIMIT_FALLBACKS, LaLimitItem, laLimit, resetLaLimits } from './la-limits';
import { LaLimitsService } from './la-limits.service';

const GET = apiUrl('/settings/link-analysis/limits');

function item(over: Partial<LaLimitItem> & { key: string }): LaLimitItem {
    return {
        layer: 'space',
        browser: false,
        group: 'canvas',
        unit: 'nodes',
        default: 500,
        min: 1,
        max: 100_000,
        value: null,
        inForce: 500,
        editable: true,
        description: '…',
        ...over,
    };
}

const ITEMS: LaLimitItem[] = [
    item({ key: 'projection_node_cap', browser: true, value: 900, inForce: 900 }),
    item({ key: 'display.lod_label_cap', browser: true, default: 300, inForce: 300, min: 10 }),
    item({ key: 'connect.max_seeds', group: 'connect', unit: 'count', default: 20, inForce: 25, value: 25 }),
    item({ key: 'graph_run.ceiling_nodes', layer: 'install', group: 'graph_run', default: 500_000, inForce: 500_000, editable: false }),
];

const STORE_KEY = 'inspecto.la.limits.personal.s1.ana';

describe('LaLimitsService', () => {
    let svc: LaLimitsService;
    let http: HttpTestingController;
    let space: ReturnType<typeof signal<string | null>>;

    beforeEach(() => {
        localStorage.clear();
        resetLaLimits();
        space = signal<string | null>('s1');
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                { provide: SpacesService, useValue: { currentSpaceId: space } },
                { provide: SessionService, useValue: { actor: signal('ana') } },
            ],
        });
        svc = TestBed.inject(LaLimitsService);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => {
        http.verify();
        vi.restoreAllMocks();
        localStorage.clear();
        resetLaLimits();
    });

    const load = (items = ITEMS) => {
        TestBed.tick();
        http.expectOne(GET).flush({ items });
    };

    it('falls back to the shipped catalogue defaults until the GET answers', () => {
        TestBed.tick();
        expect(svc.inForce('projection_node_cap')).toBe(LA_LIMIT_FALLBACKS.projection_node_cap);
        expect(laLimit('connect.max_seeds')).toBe(LA_LIMIT_FALLBACKS['connect.max_seeds']);
        http.expectOne(GET).flush({ items: ITEMS });
    });

    it('loads the in-force values and publishes them to the store the pure modules read', () => {
        load();
        expect(svc.items()).toHaveLength(4);
        expect(svc.inForce('projection_node_cap')).toBe(900);
        expect(svc.inForce('connect.max_seeds')).toBe(25);
        expect(laLimit('projection_node_cap')).toBe(900);
        expect(laLimit('connect.max_seeds')).toBe(25);
        // a key the server did not publish keeps its fallback
        expect(svc.inForce('investigation.max_frontier')).toBe(LA_LIMIT_FALLBACKS['investigation.max_frontier']);
    });

    it('reloads when the Space changes, back to the fallbacks while the new answer is pending', () => {
        load();
        space.set('s2');
        TestBed.tick();
        expect(laLimit('projection_node_cap')).toBe(LA_LIMIT_FALLBACKS.projection_node_cap);
        http.expectOne(GET).flush({ items: [item({ key: 'projection_node_cap', browser: true, inForce: 700 })] });
        expect(laLimit('projection_node_cap')).toBe(700);
    });

    it('a failed GET leaves the fallbacks in force and says why', () => {
        TestBed.tick();
        http.expectOne(GET).flush({ error: { message: 'nope' } }, { status: 500, statusText: 'Server Error' });
        expect(svc.loadError()).toBe('nope');
        expect(laLimit('projection_node_cap')).toBe(LA_LIMIT_FALLBACKS.projection_node_cap);
    });

    it('effective = the personal value for a browser item, clamped to [min, Space value]', () => {
        load();
        svc.setPersonal('projection_node_cap', 400);
        expect(svc.effective('projection_node_cap')).toBe(400);
        expect(svc.inForce('projection_node_cap')).toBe(900);
        expect(laLimit('projection_node_cap')).toBe(400);

        svc.setPersonal('projection_node_cap', 5000); // above the Space value
        expect(svc.effective('projection_node_cap')).toBe(900);
        svc.setPersonal('display.lod_label_cap', 2); // below the item's min
        expect(svc.effective('display.lod_label_cap')).toBe(10);
        expect(JSON.parse(localStorage.getItem(STORE_KEY)!)).toEqual({ projection_node_cap: 900, 'display.lod_label_cap': 10 });
    });

    it('a personal value never applies to a non-browser item', () => {
        load();
        svc.setPersonal('connect.max_seeds', 3);
        expect(svc.effective('connect.max_seeds')).toBe(25);
        expect(localStorage.getItem(STORE_KEY)).toBeNull();
    });

    it('reads the stored personal map per Space and user, and clamps it when the Space value is lower', () => {
        localStorage.setItem(STORE_KEY, JSON.stringify({ projection_node_cap: 2000, 'display.lod_label_cap': 'x' }));
        load();
        expect(svc.personal('projection_node_cap')).toBe(900);
        expect(svc.personal('display.lod_label_cap')).toBeNull();
        expect(laLimit('projection_node_cap')).toBe(900);
    });

    it('reset returns to the Space value and clears the stored entry', () => {
        load();
        svc.setPersonal('projection_node_cap', 400);
        svc.resetPersonal('projection_node_cap');
        expect(svc.effective('projection_node_cap')).toBe(900);
        expect(localStorage.getItem(STORE_KEY)).toBeNull();
    });

    it('keeps working when localStorage throws (the value holds for this page only)', () => {
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new Error('QuotaExceededError');
        });
        vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
            throw new Error('SecurityError');
        });
        load();
        expect(() => svc.setPersonal('projection_node_cap', 300)).not.toThrow();
        expect(svc.effective('projection_node_cap')).toBe(300);
    });

    it('saveSpace PUTs the changed keys and applies the answer', () => {
        load();
        svc.saveSpace({ 'connect.max_seeds': 30, projection_node_cap: null }).subscribe();
        const req = http.expectOne(GET);
        expect(req.request.method).toBe('PUT');
        expect(req.request.body).toEqual({ 'connect.max_seeds': 30, projection_node_cap: null });
        req.flush({ items: [item({ key: 'connect.max_seeds', inForce: 30 })] });
        expect(laLimit('connect.max_seeds')).toBe(30);
    });

    it('saveInstall PUTs to the system route', () => {
        load();
        svc.saveInstall({ 'graph_run.ceiling_nodes': 600_000 }).subscribe();
        const req = http.expectOne(apiUrl('/system/link-analysis/limits'));
        expect(req.request.method).toBe('PUT');
        req.flush({ items: ITEMS });
        expect(svc.items()).toHaveLength(4);
    });
});
