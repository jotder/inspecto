import { afterEach, describe, expect, it, vi } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';

import { SessionService, SpacesService, apiUrl } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LaLimitItem, laLimit, resetLaLimits } from '@inspecto/link-analysis/link-analysis/la-limits';
import { LaLimitsService } from '@inspecto/link-analysis/link-analysis/la-limits.service';
import { LinkAnalysisLimitsComponent } from './link-analysis-limits.component';

const GET = apiUrl('/settings/link-analysis/limits');

function item(over: Partial<LaLimitItem> & { key: string }): LaLimitItem {
    return {
        layer: 'space',
        browser: false,
        group: 'canvas',
        unit: 'nodes',
        default: 500,
        min: 1,
        max: 50_000,
        value: null,
        inForce: 500,
        editable: true,
        description: 'Nodes admitted to one projection.',
        ...over,
    };
}

const ITEMS: LaLimitItem[] = [
    item({ key: 'projection_node_cap', browser: true }),
    item({ key: 'display.lod_label_cap', browser: true, group: 'display', default: 300, inForce: 300, description: 'Labels drop above this.' }),
    item({ key: 'connect.max_seeds', group: 'connect', unit: 'count', default: 20, inForce: 20, max: 2000, description: 'Seeds per connect.' }),
    item({
        key: 'graph_run.ceiling_nodes',
        layer: 'install',
        group: 'graph_run',
        default: 500_000,
        inForce: 500_000,
        max: 50_000_000,
        editable: false,
        description: 'The hard ceiling of a graph run.',
    }),
];

function setup(items = ITEMS) {
    localStorage.clear();
    resetLaLimits();
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [LinkAnalysisLimitsComponent],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(withXhr()),
            provideHttpClientTesting(),
            { provide: SpacesService, useValue: { currentSpaceId: signal('s1') } },
            { provide: SessionService, useValue: { actor: signal('ana') } },
            { provide: ToastrService, useValue: toastr },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisLimitsComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne(GET).flush({ items });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const section = (name: string) => el.querySelector<HTMLFormElement>(`form[data-section="${name}"]`)!;
    const input = (name: string, key: string) => section(name).querySelector<HTMLInputElement>(`input[data-key="${key}"]`)!;
    const type = (name: string, key: string, value: string) => {
        const i = input(name, key);
        i.value = value;
        i.dispatchEvent(new Event('input'));
        fixture.detectChanges();
    };
    const submit = (name: string) => {
        section(name).querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
        fixture.detectChanges();
    };
    return { fixture, el, http, toastr, section, input, type, submit, svc: TestBed.inject(LaLimitsService) };
}

describe('LinkAnalysisLimitsComponent', () => {
    afterEach(() => {
        TestBed.inject(HttpTestingController).verify();
        localStorage.clear();
        resetLaLimits();
    });

    it('renders the three sections from the served items, grouped, with units and the value in force (a11y)', async () => {
        const { el, section, input } = setup();
        const headings = Array.from(el.querySelectorAll('h2')).map((h) => h.textContent!.trim());
        expect(headings).toEqual(['Analysis', 'Host resources', 'My browser']);
        const legends = Array.from(section('space').querySelectorAll('legend')).map((l) => l.textContent!.trim());
        expect(legends).toEqual(['Canvas', 'Display', 'Connect']);
        expect(section('space').textContent).toContain('Projection node cap (nodes)');
        expect(section('space').textContent).toContain('In force: 500 nodes');
        expect(section('space').textContent).toContain('Max seeds');
        expect(input('space', 'connect.max_seeds').disabled).toBe(false);
        // the install item is read-only for this caller, and says why
        expect(input('install', 'graph_run.ceiling_nodes').disabled).toBe(true);
        expect(section('install').textContent).toContain('needs the system administrator capability');
        expect(section('install').querySelector('button[type="submit"]')).toBeNull();
        // only the browser items are offered for a personal value
        expect(section('browser').querySelectorAll('input').length).toBe(2);
        expect(input('browser', 'projection_node_cap').placeholder).toBe('Space value 500');
        await expectNoA11yViolations(el);
    });

    it('saves only the changed Space keys and applies the answer', () => {
        const { http, toastr, type, submit } = setup();
        type('space', 'connect.max_seeds', '30');
        submit('space');
        const req = http.expectOne(GET);
        expect(req.request.method).toBe('PUT');
        expect(req.request.body).toEqual({ 'connect.max_seeds': 30 });
        req.flush({ items: ITEMS.map((i) => (i.key === 'connect.max_seeds' ? { ...i, value: 30, inForce: 30 } : i)) });
        expect(laLimit('connect.max_seeds')).toBe(30);
        expect(toastr.success).toHaveBeenCalled();
    });

    it('a blank field is sent as null (inherit)', () => {
        const { http, type, submit } = setup(ITEMS.map((i) => (i.key === 'connect.max_seeds' ? { ...i, value: 25 } : i)));
        type('space', 'connect.max_seeds', '');
        submit('space');
        const req = http.expectOne(GET);
        expect(req.request.body).toEqual({ 'connect.max_seeds': null });
        req.flush({ items: ITEMS });
    });

    it('refuses a value outside the item range before the call, with an inline error', () => {
        const { http, el, type, submit } = setup();
        type('space', 'connect.max_seeds', '2001');
        submit('space');
        http.expectNone(GET);
        const error = el.querySelector('form[data-section="space"] mat-error');
        expect(error?.textContent).toContain('A whole number from 1 to 2,000');
        expect(error?.closest('.mat-mdc-form-field-subscript-wrapper')).not.toBeNull();
    });

    it("shows the server's 422 verbatim", () => {
        const { fixture, http, el, type, submit } = setup();
        type('space', 'connect.max_seeds', '40');
        submit('space');
        http.expectOne(GET).flush(
            { error: { message: "'connect.max_seeds' must be 1..2000" } },
            { status: 422, statusText: 'Unprocessable Entity' },
        );
        fixture.detectChanges();
        expect(el.textContent).toContain("'connect.max_seeds' must be 1..2000");
    });

    it('keeps a personal browser value, clamped to the Space value, and resets it', async () => {
        const { fixture, el, svc, type, submit } = setup();
        type('browser', 'projection_node_cap', '200');
        submit('browser');
        expect(svc.effective('projection_node_cap')).toBe(200);
        expect(laLimit('projection_node_cap')).toBe(200);
        expect(JSON.parse(localStorage.getItem('inspecto.la.limits.personal.s1.ana')!)).toEqual({ projection_node_cap: 200 });

        const reset = el.querySelector<HTMLButtonElement>('button[aria-label="Reset Projection node cap (nodes) to the Space value"]')!;
        expect(reset).not.toBeNull();
        await expectNoA11yViolations(el);
        reset.click();
        fixture.detectChanges();
        expect(svc.effective('projection_node_cap')).toBe(500);
        expect(localStorage.getItem('inspecto.la.limits.personal.s1.ana')).toBeNull();
    });

    it('accepts a decimal per_s rate, renders a null default as none, and states a pod-scoped answer', () => {
        localStorage.clear();
        resetLaLimits();
        TestBed.configureTestingModule({
            imports: [LinkAnalysisLimitsComponent],
            providers: [
                provideNoopAnimations(),
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                { provide: SpacesService, useValue: { currentSpaceId: signal('s1') } },
                { provide: SessionService, useValue: { actor: signal('ana') } },
                { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            ],
        });
        const fixture = TestBed.createComponent(LinkAnalysisLimitsComponent);
        const http = TestBed.inject(HttpTestingController);
        fixture.detectChanges();
        TestBed.tick();
        http.expectOne(GET).flush({
            items: [
                item({ key: 'rate.link_analysis_per_s', layer: 'install', group: 'rate', unit: 'per_s', default: 0.5, inForce: 0.5, min: 0.01, max: 1000 }),
                item({ key: 'index.build_memory_pct', layer: 'install', group: 'index', unit: 'pct_ram', default: null, inForce: null, min: 1, max: 95 }),
            ],
            metadata: { podScoped: true },
        });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const host = el.querySelector<HTMLFormElement>('form[data-section="install"]')!;
        expect(el.textContent).toContain('this server (pod) only');
        expect(host.textContent).toContain('In force: none.');
        expect(host.querySelector<HTMLInputElement>('input[data-key="index.build_memory_pct"]')!.placeholder).toBe('default none');
        const rate = host.querySelector<HTMLInputElement>('input[data-key="rate.link_analysis_per_s"]')!;
        rate.value = '0.75';
        rate.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        host.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
        const req = http.expectOne(apiUrl('/system/link-analysis/limits'));
        expect(req.request.body).toEqual({ 'rate.link_analysis_per_s': 0.75 });
        req.flush({ items: [] });
    });

    it('refuses a personal value above the Space value', () => {
        const { el, svc, type, submit } = setup();
        type('browser', 'projection_node_cap', '600');
        submit('browser');
        expect(svc.personal('projection_node_cap')).toBeNull();
        expect(el.querySelector('form[data-section="browser"] mat-error')?.textContent).toContain('from 1 to 500');
    });
});
