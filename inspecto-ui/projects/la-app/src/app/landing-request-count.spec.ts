import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SpacesService } from '@inspecto/core/api/spaces.service';
import { SessionService } from '@inspecto/core/auth/session.service';
import { SpaceSwitcherComponent } from 'app/layout/common/space-switcher/space-switcher.component';
import { LandingComponent } from './landing.component';

/**
 * N5: what the landing page asks the server after a sign-in. A real SessionService, SpacesService, SpaceGuideService,
 * ComponentsService, the shell's Space switcher and the landing (with My work) run against a mock backend that serves
 * six Spaces and counts every request per URL. Measured live before the fix: about 27 calls, every Space's
 * `/bootstrap` twice, `/spaces`, `/spaces/_meta`, the component lists and `/entity-lists` twice.
 */
const SPACES = ['la-audit', 'la-bank', 'la-fraud', 'la-health', 'la-telco', 'la-trade'].map((id) => ({
    id,
    displayName: id,
    description: '',
    createdAt: '',
}));
const tick = () => new Promise((r) => setTimeout(r, 0));

class Backend {
    readonly counts = new Map<string, number>();
    constructor(private readonly httpMock: HttpTestingController) {}

    /** Answer every open request (and what the answers trigger) until the page is quiet. */
    async drain(detect: () => void): Promise<void> {
        for (let i = 0; i < 40; i++) {
            detect();
            const open = this.httpMock.match(() => true);
            if (open.length === 0) {
                await tick();
                detect();
                if (this.httpMock.match(() => true).length === 0) return;
                continue;
            }
            for (const r of open) this.answer(r);
            await tick();
        }
    }

    countOf(path: string): number {
        return [...this.counts.entries()].filter(([k]) => k.endsWith(path)).reduce((n, [, v]) => n + v, 0);
    }

    answer(r: TestRequest): void {
        const url = r.request.urlWithParams.replace(/^.*\/v1/, '');
        const key = `${r.request.method} ${url}`;
        this.counts.set(key, (this.counts.get(key) ?? 0) + 1);
        const space = /^\/spaces\/([^/]+)\/bootstrap$/.exec(url);
        if (space) {
            // The first Space holds nothing for this subject; the second one does (D7 fallback picks it).
            return void r.flush({
                session: { authenticated: true, capabilities: space[1] === 'la-bank' ? ['x'] : [] },
            });
        }
        if (url === '/bootstrap')
            return void r.flush({ features: { authMode: 'demo' }, session: { authenticated: true } });
        if (url === '/spaces/_meta') return void r.flush({ multiSpace: true });
        if (url === '/spaces') return void r.flush(SPACES);
        if (url === '/components/dataset') return void r.flush([{ id: 'd1', content: {} }]);
        if (url === '/components/link-analysis-view') return void r.flush([]);
        if (url === '/entity-lists') return void r.flush({ lists: [] });
        if (r.request.method === 'POST' && url === '/auth/exchange') return void r.flush({ accessToken: 'tok-1' });
        r.flush({});
    }
}

describe('the landing page request count (N5)', () => {
    let httpMock: HttpTestingController;
    let backend: Backend;

    beforeEach(() => {
        localStorage.clear();
        sessionStorage.clear();
        TestBed.configureTestingModule({
            imports: [LandingComponent, SpaceSwitcherComponent],
            providers: [
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                provideRouter([]),
                provideNoopAnimations(),
            ],
        });
        httpMock = TestBed.inject(HttpTestingController);
        backend = new Backend(httpMock);
    });
    afterEach(() => localStorage.clear());

    /** Sign in through the real exchange, then mount the shell's switcher and the landing the way the shell does. */
    async function landAfterSignIn(): Promise<void> {
        const session = TestBed.inject(SessionService);
        sessionStorage.setItem('inspecto.pkce.verifier', 'v');
        sessionStorage.setItem('inspecto.pkce.state', 's');
        const done = new Promise<void>((resolve) => session.completeLogin('code', 's').subscribe(() => resolve()));
        await backend.drain(() => undefined);
        await done;
        const switcher = TestBed.createComponent(SpaceSwitcherComponent);
        const landing = TestBed.createComponent(LandingComponent);
        const detect = () => {
            switcher.detectChanges();
            landing.detectChanges();
        };
        detect();
        await backend.drain(detect);
        await backend.drain(detect);
    }

    it('fetches every URL at most once', async () => {
        await landAfterSignIn();
        const dupes = [...backend.counts.entries()].filter(([, n]) => n > 1);
        expect(dupes).toEqual([]);
    });

    it('does not read the Spaces it is not using more than once each, and keeps the signed-in Space and list content', async () => {
        await landAfterSignIn();
        const probes = SPACES.map((s) => backend.countOf(`/spaces/${s.id}/bootstrap`));
        expect(Math.max(...probes)).toBeLessThanOrEqual(1);
        // la-audit holds nothing, la-bank wins: the four Spaces after it are never read.
        for (const id of ['la-fraud', 'la-health', 'la-telco', 'la-trade']) {
            expect(backend.countOf(`/spaces/${id}/bootstrap`)).toBe(0);
        }
        expect(TestBed.inject(SpacesService).currentSpaceId()).toBe('la-bank');
        expect(TestBed.inject(SessionService).authenticated()).toBe(true);
    });
});
