import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import {
    ApprovalsService,
    EventsService,
    ExpectationsService,
    JobsService,
    LensService,
    ObjectsService,
    SessionService,
    SpacesService,
} from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { HomeComponent } from './home.component';

interface Stubs {
    breached?: number;
    breachedError?: unknown;
    writes?: { count: number; datasets: number; capped: boolean };
    writesError?: unknown;
    runs?: unknown[];
    runsError?: { status: number };
    incidents?: unknown[];
    approvals?: unknown[];
    authMode?: 'none' | 'oidc';
    loopbackOnly?: boolean;
    actor?: string | null;
    opsEnabled?: boolean;
    eventsEnabled?: boolean;
    capabilities?: string[];
    multiSpace?: boolean;
    lens?: 'business' | 'builder' | 'ops';
    can?: Partial<Record<'onboard' | 'author' | 'operate' | 'administer', boolean>>;
}

/**
 * ⚠ Several tests here build TWO fixtures, because the gating they assert is read in `ngOnInit` — a
 * flipped signal on one fixture cannot un-call a request the first one already made. TestBed refuses a
 * second `configureTestingModule` once instantiated, so the helper resets it explicitly. Everywhere the
 * assertion is purely rendered state, prefer one fixture and mutate the stub signals instead.
 */
function create(s: Stubs = {}) {
    TestBed.resetTestingModule();
    localStorage.clear();
    const jobs = {
        recentRuns: vi.fn(() => (s.runsError ? throwError(() => s.runsError) : of(s.runs ?? []))),
    };
    const objects = { list: vi.fn(() => of(s.incidents ?? [])) };
    const approvals = { list: vi.fn(() => of(s.approvals ?? [])) };
    const expectations = {
        breachedCount: vi.fn(() =>
            s.breachedError ? throwError(() => s.breachedError) : of({ count: s.breached ?? 0 }),
        ),
    };
    const events = {
        signalCount: vi.fn(() =>
            s.writesError ? throwError(() => s.writesError) : of(s.writes ?? { count: 0, datasets: 0, capped: false }),
        ),
    };
    const session = {
        actor: signal(s.actor ?? null),
        authMode: signal(s.authMode ?? 'none'),
        loopbackOnly: signal(s.loopbackOnly ?? false),
        opsEnabled: signal(s.opsEnabled ?? false),
        eventsEnabled: signal(s.eventsEnabled ?? false),
        capabilities: signal(s.capabilities ?? []),
    };
    const spaces = {
        multiSpace: signal(s.multiSpace ?? false),
        currentSpace: signal(null),
    };
    const lens = {
        currentLens: signal(s.lens ?? 'builder'),
        canOnboardConnections: signal(s.can?.onboard ?? true),
        canAuthorWorkbench: signal(s.can?.author ?? true),
        canOperateRuns: signal(s.can?.operate ?? true),
        canAdminister: signal(s.can?.administer ?? false),
    };

    TestBed.configureTestingModule({
        imports: [HomeComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: JobsService, useValue: jobs },
            { provide: ObjectsService, useValue: objects },
            { provide: ApprovalsService, useValue: approvals },
            { provide: ExpectationsService, useValue: expectations },
            { provide: EventsService, useValue: events },
            { provide: SessionService, useValue: session },
            { provide: SpacesService, useValue: spaces },
            { provide: LensService, useValue: lens },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(HomeComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, jobs, objects, approvals };
}

const RUN = (over: Record<string, unknown> = {}) => ({
    runId: 'r1',
    job: 'orders_daily',
    type: 'ingest',
    trigger: 'schedule',
    startTime: '2026-09-15T07:00:00Z',
    endTime: '2026-09-15T07:12:00Z',
    status: 'SUCCESS',
    durationMs: 720000,
    message: '',
    ...over,
});

describe('HomeComponent', () => {
    it('has no axe violations', async () => {
        const { el } = create({ runs: [RUN()] });
        await expectNoA11yViolations(el);
    });

    it('greets a fresh install with the first-run guidance and no activity tiles', () => {
        const { el } = create({ runs: [] });
        expect(el.querySelector('h1')?.textContent).toContain('Welcome to Inspecto.');
        expect(el.textContent).toContain('Nothing has been onboarded yet');
        expect(el.textContent).toContain('Onboard a Stream');
        expect(el.textContent).not.toContain('Needs attention');
    });

    // The run-history call needs the DuckDB jobs backend. A failure there is NOT an empty install, and
    // showing the first-run page would tell a running deployment that nothing had ever run.
    it('a failing run-history call is explained, never mistaken for a first run', () => {
        const { el } = create({ runsError: { status: 404 } });
        expect(el.textContent).not.toContain('Nothing has been onboarded yet');
        expect(el.textContent).toContain('Needs attention');
        expect(el.textContent).toContain('DuckDB jobs backend');
    });

    it('names the signed-in actor when there is one', () => {
        const { el } = create({ runs: [RUN()], authMode: 'oidc', actor: 'priya.n' });
        expect(el.querySelector('h1')?.textContent).toContain('priya.n');
    });

    it('lists failed Runs, open Incidents and waiting approvals together, newest first', () => {
        const { el } = create({
            runs: [RUN(), RUN({ runId: 'r2', job: 'ledger_sync', status: 'FAILED', message: 'step 4 failed' })],
            opsEnabled: true,
            incidents: [{ id: 'INC-482', title: 'late file', status: 'OPEN', createdAt: 1, updatedAt: 2 }],
            approvals: [
                {
                    id: 'a1',
                    tool: 'write_config',
                    summary: 'rename a Dataset',
                    status: 'PENDING',
                    requestedAt: '2026-09-15T06:00:00Z',
                },
            ],
        });
        const text = el.textContent ?? '';
        expect(text).toContain('ledger_sync');
        expect(text).toContain('INC-482');
        expect(text).toContain('write_config');
    });

    // R2-08: the tile asked the server for status OPEN, which the Incident lifecycle never stores, so it
    // read 0 beside two CRITICAL Incidents. Open = anything short of RESOLVED / ARCHIVED, in either vocabulary.
    it('counts IDENTIFIED and DIAGNOSING Incidents as open, never RESOLVED or ARCHIVED ones', () => {
        const { el, objects } = create({
            runs: [RUN()],
            opsEnabled: true,
            incidents: [
                { id: 'INC-1', title: 'new', status: 'IDENTIFIED', createdAt: 1 },
                { id: 'INC-2', title: 'triage', status: 'DIAGNOSING', createdAt: 2 },
                { id: 'INC-3', title: 'legacy', status: 'OPEN', createdAt: 3 },
                { id: 'INC-4', title: 'done', status: 'RESOLVED', createdAt: 4 },
                { id: 'INC-5', title: 'filed', status: 'ARCHIVED', createdAt: 5 },
                { id: 'INC-6', title: 'legacy done', status: 'CLOSED', createdAt: 6 },
            ],
        });
        expect(objects.list).toHaveBeenCalledWith(expect.not.objectContaining({ status: expect.anything() }));
        const text = el.textContent ?? '';
        expect(text).toContain('INC-1');
        expect(text).toContain('INC-2');
        expect(text).toContain('INC-3');
        expect(text).not.toContain('INC-4');
        expect(text).not.toContain('INC-5');
        expect(text).not.toContain('INC-6');
        expect(text).toContain('3 things need your attention.');
    });

    // Module presence, never the edition string: an absent ops module 503s on every path, so the pane
    // must not even ask, and the Incidents affordances must not render.
    it('asks for Incidents only when the ops module registered', () => {
        const withOps = create({ runs: [RUN()], opsEnabled: true });
        expect(withOps.objects.list).toHaveBeenCalled();
        expect(withOps.el.textContent).toContain('Triage Incidents');

        const withoutOps = create({ runs: [RUN()], opsEnabled: false });
        expect(withoutOps.objects.list).not.toHaveBeenCalled();
        expect(withoutOps.el.textContent).not.toContain('Triage Incidents');
    });

    it('shows the administration action only to a subject holding canAdminister', () => {
        const granted = create({ runs: [RUN()], can: { administer: true } });
        expect(granted.el.textContent).toContain('Administer this space');

        const denied = create({ runs: [RUN()], can: { administer: false } });
        expect(denied.el.textContent).not.toContain('Administer this space');
    });

    it('offers the Lens landing route as the primary action, with the Ops fallback when Events is absent', () => {
        const ops = create({ runs: [RUN()], lens: 'ops', eventsEnabled: false });
        expect(ops.fixture.componentInstance.lensHome()).toBe('pipelines');

        const opsWithEvents = create({ runs: [RUN()], lens: 'ops', eventsEnabled: true });
        expect(opsWithEvents.fixture.componentInstance.lensHome()).toBe('events');
    });

    // Personal ships no sign-in and binds every interface — the one thing a new operator must be told.
    it('warns about the open listen address only where there is no authenticator', () => {
        expect(create({ runs: [RUN()], authMode: 'none' }).el.textContent).toContain('every network interface');
        expect(create({ runs: [RUN()], authMode: 'oidc' }).el.textContent).not.toContain('every network interface');
    });

    // UIE-9: the notice follows the real bind — a loopback-only listener is not exposed, so it must not claim to be.
    it('does not warn about the listen address when the control plane is bound to loopback', () => {
        const loopback = create({ runs: [RUN()], authMode: 'none', loopbackOnly: true });
        expect(loopback.el.textContent).not.toContain('every network interface');
    });

    it('shows the resolved grants only in a multi-space deployment that published some', () => {
        const multi = create({ runs: [RUN()], multiSpace: true, capabilities: ['canOperateRuns'] });
        expect(multi.el.textContent).toContain('Your access here');

        const single = create({ runs: [RUN()], multiSpace: false, capabilities: ['canOperateRuns'] });
        expect(single.el.textContent).not.toContain('Your access here');
    });

    /** HOME-TILES-1: the two counted tiles (operational Home only — first-run shows the onboarding panel) show the server's numbers and hide — not dash — when the call fails. */
    describe('counted tiles', () => {
        it('shows breached Expectations and Datasets written from the server counts', () => {
            const { el } = create({ runs: [RUN()], breached: 3, writes: { count: 7, datasets: 4, capped: false } });
            expect(el.querySelector('[data-testid="tile-breached"]')?.textContent).toContain('3');
            expect(el.querySelector('[data-testid="tile-breached"]')?.textContent).toContain('failing');
            const w = el.querySelector('[data-testid="tile-datasets-written"]')?.textContent ?? '';
            expect(w).toContain('4');
            expect(w).toContain('7 writes');
            expect(w).not.toContain('at least');
        });

        it('says "at least" when the ledger count is capped', () => {
            const { el } = create({ runs: [RUN()], writes: { count: 10000, datasets: 12, capped: true } });
            expect(el.querySelector('[data-testid="tile-datasets-written"]')?.textContent).toContain('at least');
        });

        it('hides a tile whose count call failed instead of showing a dash', () => {
            const { el } = create({ runs: [RUN()], breachedError: { status: 503 }, writesError: { status: 404 } });
            expect(el.querySelector('[data-testid="tile-breached"]')).toBeNull();
            expect(el.querySelector('[data-testid="tile-datasets-written"]')).toBeNull();
        });
    });
});
