import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import { SessionService } from '../auth/session.service';
import { ComponentsService } from './components.service';
import { SpacesService } from './spaces.service';

const base = environment.apiBaseUrl + '/v1';
const tick = () => new Promise((r) => setTimeout(r, 0));

/**
 * N5 (security): a short-lived read answer is replayed only to the user and Space it was read for. The user is a REAL
 * sign-in through `SessionService.completeLogin` (its access token is the identity), the Space a real `selectSpace`.
 */
describe('ShortReads', () => {
    let http: HttpTestingController;
    let session: SessionService;
    let spaces: SpacesService;
    let components: ComponentsService;

    beforeEach(() => {
        localStorage.clear();
        sessionStorage.clear();
        TestBed.configureTestingModule({
            providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), provideRouter([])],
        });
        http = TestBed.inject(HttpTestingController);
        session = TestBed.inject(SessionService);
        spaces = TestBed.inject(SpacesService);
        components = TestBed.inject(ComponentsService);
        spaces.selectSpace('alpha');
    });

    /** Sign `token`'s user in (the exchange mints it) and let the follow-up session read settle. */
    async function signIn(token: string): Promise<void> {
        sessionStorage.setItem('inspecto.pkce.verifier', 'v');
        sessionStorage.setItem('inspecto.pkce.state', 's');
        const done = new Promise<void>((resolve) => session.completeLogin('c', 's').subscribe(() => resolve()));
        http.expectOne(`${base}/auth/exchange`).flush({ accessToken: token });
        await done;
        await tick();
        for (const r of http.match(() => true)) r.flush({ session: { authenticated: true } });
        await tick();
    }

    const datasets = () => components.list('dataset');
    const open = () => http.match(`${base}/components/dataset`);

    it('shares one in-flight read between two callers', () => {
        const got: unknown[] = [];
        datasets().subscribe((d) => got.push(d));
        datasets().subscribe((d) => got.push(d));
        const reqs = open();
        expect(reqs.length).toBe(1);
        reqs[0].flush([{ id: 'a' }]);
        expect(got).toEqual([[{ id: 'a' }], [{ id: 'a' }]]);
    });

    it('replays a landed answer to a later caller of the same user and Space', () => {
        datasets().subscribe();
        open()[0].flush([]);
        datasets().subscribe();
        expect(open().length).toBe(0);
    });

    it('NEVER replays one user answer to another signed-in user (user switch)', async () => {
        await signIn('tok-alice');
        datasets().subscribe();
        open()[0].flush([{ id: 'alice-only' }]);

        await signIn('tok-bob');
        const got: unknown[] = [];
        datasets().subscribe((d) => got.push(d));
        const reqs = open();
        expect(reqs.length).toBe(1); // asked the server again, as Bob
        reqs[0].flush([{ id: 'bob-only' }]);
        expect(got).toEqual([[{ id: 'bob-only' }]]);
    });

    it('NEVER replays an answer after sign-out (the token is gone)', async () => {
        await signIn('tok-alice');
        datasets().subscribe();
        open()[0].flush([{ id: 'alice-only' }]);
        // sign-out clears the access token synchronously; model it the way logout() does
        (session as unknown as { accessToken: { set(v: string | null): void } }).accessToken.set(null);
        datasets().subscribe();
        expect(open().length).toBe(1);
    });

    it('NEVER replays an answer after a Space switch', () => {
        datasets().subscribe();
        open()[0].flush([{ id: 'in-alpha' }]);
        spaces.selectSpace('beta');
        datasets().subscribe();
        expect(open().length).toBe(1);
    });

    it('never keeps an error: the next caller asks the server again', () => {
        const errors: unknown[] = [];
        datasets().subscribe({ error: (e) => errors.push(e) });
        open()[0].flush('boom', { status: 500, statusText: 'Server Error' });
        expect(errors.length).toBe(1);
        datasets().subscribe();
        expect(open().length).toBe(1);
    });

    it('a write clears the reads: the next list asks the server again', () => {
        datasets().subscribe();
        open()[0].flush([]);
        components.create('dataset', { id: 'new' }).subscribe();
        http.expectOne(`${base}/components/dataset`).flush({ id: 'new' });
        datasets().subscribe();
        expect(open().length).toBe(1);
    });

    it('a Space bootstrap read names its Space, so it is shared across a switch of the active one but not across users', async () => {
        await signIn('tok-alice');
        spaces.spaceBootstrap('beta').subscribe();
        http.expectOne(`${base}/spaces/beta/bootstrap`).flush({});
        spaces.spaceBootstrap('beta').subscribe();
        expect(http.match(`${base}/spaces/beta/bootstrap`).length).toBe(0);
        await signIn('tok-bob');
        spaces.spaceBootstrap('beta').subscribe();
        expect(http.match(`${base}/spaces/beta/bootstrap`).length).toBe(1);
    });
});
