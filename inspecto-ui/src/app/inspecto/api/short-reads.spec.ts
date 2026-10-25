import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { environment } from '../../../environments/environment';
import { ShortReads } from './short-reads';
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

describe('ShortReads expiry and in-flight identity', () => {
    afterEach(() => vi.restoreAllMocks());

    it('serves a landed answer for under 2 s and asks again after', () => {
        let now = 1_000_000;
        vi.spyOn(Date, 'now').mockImplementation(() => now);
        const reads = new ShortReads();
        const subjects: Subject<number>[] = [];
        const make = () => {
            const s = new Subject<number>();
            subjects.push(s);
            return s.asObservable();
        };
        reads.read('k', make).subscribe();
        subjects[0].next(1);
        now += 1999;
        reads.read('k', make).subscribe();
        expect(subjects.length).toBe(1);
        now += 2;
        reads.read('k', make).subscribe();
        expect(subjects.length).toBe(2);
    });

    it('an in-flight read of user A is never handed to user B, even when A lands after B started', () => {
        let user = 'A';
        const reads = new ShortReads();
        reads.bindUser(() => user);
        const subj: Record<string, Subject<string>> = {};
        const make = (who: string) => () => (subj[who] = new Subject<string>()).asObservable();
        const gotA: string[] = [];
        reads.read('k', make('A')).subscribe((v) => gotA.push(v));
        user = 'B';
        const gotB: string[] = [];
        reads.read('k', make('B')).subscribe((v) => gotB.push(v));
        subj['A'].next('alice');
        subj['B'].next('bob');
        const gotB2: string[] = [];
        reads.read('k', make('B2')).subscribe((v) => gotB2.push(v));
        expect(gotA).toEqual(['alice']);
        expect(gotB).toEqual(['bob']);
        expect(gotB2).toEqual(['bob']);
        expect(subj['B2']).toBeUndefined();
    });

    it('an in-flight error reaches every sharer and the next read asks again', () => {
        const reads = new ShortReads();
        let calls = 0;
        let s!: Subject<string>;
        const make = () => {
            calls++;
            s = new Subject<string>();
            return s.asObservable();
        };
        const errs: unknown[] = [];
        reads.read('k', make).subscribe({ error: (e) => errs.push(e) });
        reads.read('k', make).subscribe({ error: (e) => errs.push(e) });
        expect(calls).toBe(1);
        s.error('boom');
        expect(errs).toEqual(['boom', 'boom']);
        const got: string[] = [];
        reads.read('k', make).subscribe((v) => got.push(v));
        expect(calls).toBe(2);
        s.next('ok');
        expect(got).toEqual(['ok']);
    });
});
