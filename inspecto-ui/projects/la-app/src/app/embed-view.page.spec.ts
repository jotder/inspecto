import { Location } from '@angular/common';
import { HttpBackend, HttpErrorResponse, HttpRequest, HttpResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { Component, input, output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import type { G6GraphData } from '@inspecto/core/graph';
import { GraphViewComponent } from '@inspecto/core/graph/graph-view.component';
import { firstValueFrom, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { provideAppEnvironment } from '@inspecto/core/api/app-environment';
import { configureLaLimits, resetLaLimits } from '@inspecto/link-analysis/link-analysis/la-limits';
import { EMBED_SCRUBBED_PATH, EmbedViewPageComponent } from './embed-view.page';
import { EmbedView, EmbedViewService } from './embed-view.service';

const VIEW: EmbedView = {
    investigation: { id: 'inv-1', title: 'Ring case', purpose: 'p', createdAt: 'x' },
    head: { step: 4 },
    workingSet: {
        entities: [{ entityId: 'msisdn:****12' }, { entityId: 'b' }, { entityId: 'a' }],
        links: [{ source: 'a', target: 'b', kind: 'calls', count: 2 }],
        excludedCount: 0,
    },
    annotations: [{ step: 2, note: 'seen twice' }],
    references: [{ title: 'cdr ****99' }],
    link: { expiresAt: '2026-10-13T10:00:00Z' },
};

/** Stands in for G6, which cannot mount in jsdom: the page's own template and toolset around it are what these specs read. */
@Component({ selector: 'inspecto-graph-view', standalone: true, template: '' })
class StubGraphViewComponent {
    readonly data = input<G6GraphData | null>(null);
    readonly display = input<unknown>(null);
    readonly fill = input(false);
    readonly emphasis = input<unknown>(null);
    readonly interaction = input<unknown>(null);
    readonly plugins = input<unknown>(null);
    readonly selection = input<string[] | null>(null);
    readonly selectionChange = output<string[]>();
    readonly nodeClick = output<string>();
    readonly centerOn = vi.fn();
    readonly panBy = vi.fn();
    readonly zoomBy = vi.fn();
    readonly fitView = vi.fn();
}

function useStub() {
    TestBed.overrideComponent(EmbedViewPageComponent, {
        remove: { imports: [GraphViewComponent] },
        add: { imports: [StubGraphViewComponent] },
    });
}

const graphOf = (f: ComponentFixture<EmbedViewPageComponent>) =>
    f.debugElement.query(By.directive(StubGraphViewComponent)).componentInstance as StubGraphViewComponent;

async function render(svc: Partial<EmbedViewService>, view: EmbedView = VIEW) {
    const location = { path: vi.fn(() => '/embed/inv/g1.secret'), replaceState: vi.fn() };
    TestBed.configureTestingModule({
        providers: [
            { provide: EmbedViewService, useValue: { view: () => of(view), ...svc } },
            { provide: Location, useValue: location },
        ],
    });
    useStub();
    const f = TestBed.createComponent(EmbedViewPageComponent);
    f.componentRef.setInput('token', 'g1.secret');
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return { el: f.nativeElement as HTMLElement, f, location };
}

describe('embed view page', () => {
    it('shows the masked view as returned, read-only, with the graph and the Working Set table', async () => {
        const { el, f } = await render({});
        expect(el.textContent).toContain('Ring case');
        expect(el.textContent).toContain('msisdn:****12');
        expect(el.textContent).toContain('seen twice');
        expect(el.textContent).toContain('cdr ****99');
        const g = graphOf(f).data()!;
        expect(g.nodes.map((n) => n.data.label), 'ids drawn exactly as returned (masked)').toEqual(['msisdn:****12', 'b', 'a']);
        expect(g.edges.length).toBe(1);
        expect(el.querySelector('a, form, textarea, select'), 'no link out and no write control').toBeNull();
        expect(el.textContent, 'an embed grant carries no export permission').not.toContain('Export');
        expect(el.querySelector('[tabindex="0"][role="group"] inspecto-graph-view'), 'the canvas sits in a focusable group').not.toBeNull();
        expect(el.querySelectorAll('tbody tr').length).toBe(3);
        await expectNoA11yViolations(el);
    });

    it('draws at most the display.embed_edge_limit links in force, and says so (LA-CONFIG-LIMITS-1)', async () => {
        configureLaLimits({ 'display.embed_edge_limit': 1 });
        try {
            const links = [...VIEW.workingSet.links, { source: 'a', target: 'msisdn:****12', kind: 'calls', count: 1 }];
            const { el, f } = await render({}, { ...VIEW, workingSet: { ...VIEW.workingSet, links } });
            expect(graphOf(f).data()!.edges.length).toBe(1);
            expect(el.textContent).toContain('Only the first 1 links are drawn');
        } finally {
            resetLaLimits();
        }
    });

    it('paints its own scheme background: outside the shell the text tokens were drawn on white (LA-A11Y-AUDIT-1, 2.56:1 live)', async () => {
        const { el } = await render({});
        expect(el.style.background).toContain('--gamma-bg-default');
        expect(el.style.color).toContain('--gamma-text-default');
    });

    it('takes the token out of the address bar once it has been read, and keeps it out of the page', async () => {
        const { el, location } = await render({});
        expect(location.replaceState).toHaveBeenCalledWith(EMBED_SCRUBBED_PATH);
        expect(el.innerHTML).not.toContain('secret');
        expect(document.title).not.toContain('secret');
    });

    it('shows one neutral page for a bad, expired or revoked link and a separate one for an unreachable server', async () => {
        TestBed.resetTestingModule();
        const gone = await render({ view: () => throwError(() => new HttpErrorResponse({ status: 404 })) });
        expect(gone.el.textContent).toContain('This link is not available');
        expect(gone.el.textContent).not.toContain('secret');
        expect(gone.location.replaceState, 'a dead token is scrubbed too').toHaveBeenCalled();
        for (const status of [0, 500]) {
            TestBed.resetTestingModule();
            const off = await render({ view: () => throwError(() => new HttpErrorResponse({ status })) });
            expect(off.el.textContent).toContain('Could not reach the server');
            expect(off.location.replaceState, 'an unreachable page keeps its address so a reload can work').not.toHaveBeenCalled();
        }
    });

    it('honours the link scope: working_set shows no notes or references, with_notes no references', async () => {
        TestBed.resetTestingModule();
        let r = await render({}, { ...VIEW, link: { expiresAt: 'x', scope: 'working_set' } });
        expect(r.el.textContent).not.toContain('Notes (');
        expect(r.el.textContent).not.toContain('References (');
        expect(r.el.textContent).toContain('entities and links only');
        TestBed.resetTestingModule();
        r = await render({}, { ...VIEW, link: { expiresAt: 'x', scope: 'with_notes' } });
        expect(r.el.textContent).toContain('Notes (');
        expect(r.el.textContent).not.toContain('References (');
    });

    it('filters and pages the table and highlights an entity on the graph', async () => {
        TestBed.resetTestingModule();
        const many: EmbedView = { ...VIEW, workingSet: { ...VIEW.workingSet, entities: Array.from({ length: 60 }, (_, i) => ({ entityId: 'e' + i, hop: i % 3 })), links: [{ source: 'e1', target: 'e2', kind: 'calls' }] } };
        const { el, f } = await render({}, many);
        expect(el.querySelectorAll('tbody tr').length).toBe(25);
        expect(el.textContent).toContain('Page 1 of 3');
        const search = el.querySelector('input[type=search]') as HTMLInputElement;
        search.value = 'e5';
        search.dispatchEvent(new Event('input'));
        f.detectChanges();
        expect(el.querySelectorAll('tbody tr').length).toBe(11);
        (el.querySelector('tbody button') as HTMLButtonElement).click();
        f.detectChanges();
        expect(el.querySelector('#embed-sel')!.textContent).toContain('e5');
        expect(graphOf(f).selection()).toEqual(['e5']);
        expect(graphOf(f).centerOn).toHaveBeenCalledWith(['e5']);
    });

    it('gives the read-only toolset - search, lasso, type filter, Hide, Shortest path, keys - and makes no call but the one read', async () => {
        TestBed.resetTestingModule();
        const seen: HttpRequest<unknown>[] = [];
        const interceptor = vi.fn((req: HttpRequest<unknown>, next: (r: HttpRequest<unknown>) => never) => next(req));
        const typed: EmbedView = {
            ...VIEW,
            workingSet: {
                entities: [
                    { entityId: 'a', type: 'sim' },
                    { entityId: 'b', type: 'sim' },
                    { entityId: 'c', type: 'device' },
                ],
                links: [
                    { source: 'a', target: 'b', kind: 'calls' },
                    { source: 'b', target: 'c', kind: 'uses' },
                ],
                excludedCount: 0,
            },
        };
        TestBed.configureTestingModule({
            providers: [
                provideAppEnvironment({ apiBaseUrl: '/api' } as never),
                provideHttpClient(withInterceptors([interceptor as never])),
                {
                    provide: HttpBackend,
                    useValue: {
                        handle: (r: HttpRequest<unknown>) => {
                            seen.push(r);
                            return of(new HttpResponse({ status: 200, body: { data: typed, metadata: { apiVersion: 'v1' } } }));
                        },
                    },
                },
                { provide: Location, useValue: { path: () => '/embed/inv/g1.secret', replaceState: vi.fn() } },
            ],
        });
        useStub();
        const f = TestBed.createComponent(EmbedViewPageComponent);
        f.componentRef.setInput('token', 'g1.secret');
        f.detectChanges();
        await f.whenStable();
        f.detectChanges();
        const c = f.componentInstance;
        const el = f.nativeElement as HTMLElement;
        const g = graphOf(f);

        const find = el.querySelector('input[type=search]') as HTMLInputElement;
        find.value = 'c';
        find.dispatchEvent(new Event('input'));
        find.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
        f.detectChanges();
        expect(g.selection()).toEqual(['c']);

        const canvas = el.querySelector('[role="group"][tabindex="0"]') as HTMLElement;
        canvas.dispatchEvent(new KeyboardEvent('keydown', { key: 'l' }));
        f.detectChanges();
        expect(c.canvasMode()).toBe('lasso');
        expect((g.interaction() as { lasso?: string }).lasso).toBe('drag');
        canvas.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
        canvas.dispatchEvent(new KeyboardEvent('keydown', { key: '+' }));
        expect(g.panBy).toHaveBeenCalled();
        expect(g.zoomBy).toHaveBeenCalled();

        c.toggleKind('device');
        f.detectChanges();
        expect(g.data()!.nodes.map((n) => n.id)).toEqual(['a', 'b']);
        c.toggleKind('device');
        f.detectChanges();

        expect(c.analysisBlocked('shortestPath')).toBe('Select exactly two entities first.');
        g.selectionChange.emit(['a', 'c']); // a lasso round two entities
        c.analyse('shortestPath');
        f.detectChanges();
        expect(g.emphasis()).toEqual({ nodeIds: ['a', 'b', 'c'], edgeIds: ['l0', 'l1'] });
        expect(el.querySelector('[data-testid="embed-result"]')?.textContent).toContain('Shortest path: 2 link(s).');

        c.hideSelected();
        f.detectChanges();
        expect(g.data()!.nodes.map((n) => n.id)).toEqual(['b']);

        expect(seen.map((r) => r.method + ' ' + r.url), 'one read, nothing else').toEqual(['GET /api/v1/public/investigations/g1.secret']);
        expect(interceptor).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });
});

describe('embed view service', () => {
    it('sends no Authorization header, bypasses the interceptors, and unwraps the v1 envelope', async () => {
        const seen: HttpRequest<unknown>[] = [];
        const interceptor = vi.fn((req: HttpRequest<unknown>, next: (r: HttpRequest<unknown>) => never) =>
            next(req.clone({ setHeaders: { Authorization: 'Bearer x' } })),
        );
        const backend = {
            handle: (r: HttpRequest<unknown>) => {
                seen.push(r);
                return of(new HttpResponse({ status: 200, body: { data: VIEW, metadata: { apiVersion: 'v1' } } }));
            },
        };
        TestBed.configureTestingModule({
            providers: [
                provideAppEnvironment({ apiBaseUrl: '/api' } as never),
                provideHttpClient(withInterceptors([interceptor as never])),
                { provide: HttpBackend, useValue: backend },
            ],
        });
        const out = await firstValueFrom(TestBed.inject(EmbedViewService).view('g1.secret'));
        expect(out.investigation.title).toBe('Ring case');
        expect(seen[0].url).toBe('/api/v1/public/investigations/g1.secret');
        expect(seen[0].headers.has('Authorization')).toBe(false);
        expect(interceptor).not.toHaveBeenCalled();
    });
});
