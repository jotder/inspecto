import { HttpBackend, HttpErrorResponse, HttpRequest, HttpResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { provideAppEnvironment } from '@inspecto/core/api/app-environment';
import { EmbedViewPageComponent } from './embed-view.page';
import { EmbedViewService } from './embed-view.service';

const VIEW = {
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

async function render(svc: Partial<EmbedViewService>): Promise<HTMLElement> {
    TestBed.configureTestingModule({ providers: [{ provide: EmbedViewService, useValue: svc }] });
    const f = TestBed.createComponent(EmbedViewPageComponent);
    f.componentRef.setInput('token', 'g1.secret');
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f.nativeElement as HTMLElement;
}

describe('embed view page', () => {
    it('shows the masked view as returned, read-only', async () => {
        const el = await render({ view: () => of(VIEW) });
        expect(el.textContent).toContain('Ring case');
        expect(el.textContent).toContain('msisdn:****12');
        expect(el.textContent).toContain('seen twice');
        expect(el.textContent).toContain('cdr ****99');
        expect(el.querySelectorAll('svg circle').length).toBe(3);
        expect(el.querySelectorAll('svg line').length).toBe(1);
        expect(el.querySelector('button, input, a')).toBeNull();
    });

    it('shows one neutral page for any failure and never echoes the token', async () => {
        for (const status of [404, 0, 500]) {
            TestBed.resetTestingModule();
            const el = await render({ view: () => throwError(() => new HttpErrorResponse({ status })) });
            expect(el.textContent).toContain('This link is not available');
            expect(el.textContent).not.toContain('secret');
        }
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
