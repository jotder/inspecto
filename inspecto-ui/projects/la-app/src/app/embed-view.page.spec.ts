import { Location } from '@angular/common';
import { HttpBackend, HttpErrorResponse, HttpRequest, HttpResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { provideAppEnvironment } from '@inspecto/core/api/app-environment';
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

async function render(svc: Partial<EmbedViewService>, view: EmbedView = VIEW) {
    const location = { path: vi.fn(() => '/embed/inv/g1.secret'), replaceState: vi.fn() };
    TestBed.configureTestingModule({
        providers: [
            { provide: EmbedViewService, useValue: { view: () => of(view), ...svc } },
            { provide: Location, useValue: location },
        ],
    });
    const f = TestBed.createComponent(EmbedViewPageComponent);
    f.componentRef.setInput('token', 'g1.secret');
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return { el: f.nativeElement as HTMLElement, f, location };
}

describe('embed view page', () => {
    it('shows the masked view as returned, read-only, with the graph and the Working Set table', async () => {
        const { el } = await render({});
        expect(el.textContent).toContain('Ring case');
        expect(el.textContent).toContain('msisdn:****12');
        expect(el.textContent).toContain('seen twice');
        expect(el.textContent).toContain('cdr ****99');
        expect(el.querySelectorAll('svg circle').length).toBe(3);
        expect(el.querySelectorAll('svg line').length).toBe(1);
        expect(el.querySelector('a, form, textarea, select'), 'no link out and no write control').toBeNull();
        expect(el.querySelectorAll('tbody tr').length).toBe(3);
        await expectNoA11yViolations(el);
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
