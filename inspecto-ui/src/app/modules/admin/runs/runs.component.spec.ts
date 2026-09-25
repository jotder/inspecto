import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import {
    DEFAULT_REFRESH_MS,
    LensService,
    RUN_POLL_BACKOFF_MS,
    RunResult,
    RunsService,
    RunView,
} from 'app/inspecto/api';
import { environment } from 'environments/environment';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RunsComponent } from './runs.component';

const RUN: RunView = { name: 'cdr_ingest', configPath: 'cdr_ingest.toon', paused: false, committedBatches: 5 };
const RESULT: RunResult = { total: 3, failed: 0 };

function create(runs: RunView[] = [RUN], paramMap: Observable<ParamMap> = of(convertToParamMap({}))) {
    TestBed.configureTestingModule({
        imports: [RunsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            // The `/runs(/:name)` param drives the detail side panel (R5).
            { provide: ActivatedRoute, useValue: { paramMap, snapshot: { paramMap: convertToParamMap({}) } } },
            {
                provide: RunsService,
                useValue: {
                    list: () => of(runs),
                    trigger: () => of({ runId: 'run-1' }), // v1 async contract (W5b): 202 + runId
                    runAll: () => of({ cdr_ingest: RESULT }),
                    pause: () => of({ pipeline: 'cdr_ingest', paused: true }),
                    resume: () => of({ pipeline: 'cdr_ingest', paused: false }),
                    // Consumed by the embedded run-detail side panel (initial Batches tab).
                    batches: () => of([]),
                    files: () => of([]),
                    pending: () => of(null),
                },
            },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            {
                provide: ToastrService,
                useValue: { warning: () => undefined, success: () => undefined, error: () => undefined },
            },
        ],
    });
    const fixture = TestBed.createComponent(RunsComponent);
    fixture.detectChanges(); // runs ngOnInit (list load)
    return fixture;
}

describe('RunsComponent', () => {
    // LensService persists to localStorage; clear it so a lens set by one test/file can't leak into another.
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('loads runs on init', () => {
        const c = create().componentInstance;
        expect(c.runs()).toEqual([RUN]);
    });

    it('shows Run all and all row actions in the default (Builder) lens', () => {
        const fixture = create();
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Run all'))).toBe(true);
        expect(
            fixture.componentInstance.rowActions.map((a) => (typeof a.hint === 'function' ? a.hint(RUN) : a.hint)),
        ).toEqual(['Trigger', 'Pause', 'Reprocess batch', 'Open detail']);
    });

    it('hides Run all and every action but Open detail in the Business (read-only) lens', () => {
        const fixture = create();
        TestBed.inject(LensService).selectLens('business');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Run all'))).toBe(false);
        expect(
            fixture.componentInstance.rowActions.map((a) => (typeof a.hint === 'function' ? a.hint(RUN) : a.hint)),
        ).toEqual(['Open detail']);
    });

    it('the Business lens blocks trigger/runAll/togglePause/openReprocess even when called directly', async () => {
        const c = create().componentInstance;
        TestBed.inject(LensService).selectLens('business');
        const spy = vi.spyOn(TestBed.inject(RunsService), 'trigger');
        await c.trigger('cdr_ingest');
        expect(spy).not.toHaveBeenCalled();
        await c.runAll();
        await c.togglePause(RUN);
        expect(RUN.paused).toBe(false);
        c.openReprocess('cdr_ingest');
    });

    it('opens the detail side panel on a name route param and closes it when the param clears (R5)', () => {
        const params = new BehaviorSubject<ParamMap>(convertToParamMap({}));
        const fixture = create([RUN], params);
        const c = fixture.componentInstance;
        const el = fixture.nativeElement as HTMLElement;
        expect(c.detailName()).toBeNull();
        expect(el.querySelector('app-run-detail')).toBeNull();

        params.next(convertToParamMap({ name: 'cdr_ingest' })); // deep link /runs/cdr_ingest
        fixture.detectChanges();
        expect(c.detailName()).toBe('cdr_ingest');
        expect(el.querySelector('app-run-detail')).toBeTruthy();
        expect(c.runs()).toEqual([RUN]); // the list survives the detail opening

        params.next(convertToParamMap({})); // back to /runs
        fixture.detectChanges();
        expect(c.detailName()).toBeNull();
        expect(el.querySelector('app-run-detail')).toBeNull();
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

/** Override the read-only document.hidden getter, then fire visibilitychange. */
function setHidden(hidden: boolean): void {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    document.dispatchEvent(new Event('visibilitychange'));
}

/**
 * Refresh behaviour against the REAL RunsService over a mocked backend (fake timers): the list must catch a
 * triggered run's outcome without a manual refresh, and "Auto" must tick on DEFAULT_REFRESH_MS while visible.
 */
describe('RunsComponent refresh', () => {
    const listUrl = `${environment.apiBaseUrl}/v1/runs`;
    const row = (committedBatches: number): RunView => ({ ...RUN, paused: false, committedBatches });
    let http: HttpTestingController;

    function createLive(): ComponentFixture<RunsComponent> {
        TestBed.configureTestingModule({
            imports: [RunsComponent],
            providers: [
                provideNoopAnimations(),
                provideRouter([]),
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({})) } },
                { provide: MatDialog, useValue: {} },
                { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
                { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
                {
                    provide: ToastrService,
                    useValue: { warning: () => undefined, success: () => undefined, error: () => undefined },
                },
            ],
        });
        http = TestBed.inject(HttpTestingController);
        const fixture = TestBed.createComponent(RunsComponent);
        fixture.detectChanges();
        http.expectOne(listUrl).flush([row(0)]); // the initial load
        return fixture;
    }

    const listRequests = () => http.match((r) => r.method === 'GET' && r.url === listUrl);

    beforeEach(() => {
        localStorage.removeItem('inspecto.currentLens');
        vi.useFakeTimers();
        setHidden(false);
    });

    afterEach(() => {
        http.verify();
        vi.useRealTimers();
        setHidden(false);
    });

    it('after a trigger, polls the run with backoff and re-fetches the list once it leaves RUNNING', async () => {
        const c = createLive().componentInstance;
        c.autoRefresh = false; // prove the post-trigger refresh does not depend on the Auto clock

        await c.trigger('cdr_ingest');
        http.expectOne(`${listUrl}/cdr_ingest/trigger`).flush({ runId: 'r1' });
        listRequests().forEach((r) => r.flush([row(0)])); // an immediate re-fetch races the run: still 0

        vi.advanceTimersByTime(RUN_POLL_BACKOFF_MS[0]);
        http.expectOne(`${listUrl}/runs/r1`).flush({ runId: 'r1', status: 'RUNNING' });
        expect(listRequests()).toHaveLength(0); // still running: no list refresh yet

        vi.advanceTimersByTime(RUN_POLL_BACKOFF_MS[1] - 1);
        http.expectNone(`${listUrl}/runs/r1`); // backoff, not a busy loop
        vi.advanceTimersByTime(1);
        http.expectOne(`${listUrl}/runs/r1`).flush({ runId: 'r1', status: 'SUCCESS' });

        const refresh = listRequests();
        expect(refresh).toHaveLength(1);
        refresh[0].flush([row(1)]);
        expect(c.runs()[0].committedBatches).toBe(1);

        vi.advanceTimersByTime(60_000);
        http.expectNone(`${listUrl}/runs/r1`); // polling stops at the terminal status
    });

    it('stops polling a triggered run when the page is destroyed', async () => {
        const fixture = createLive();
        await fixture.componentInstance.trigger('cdr_ingest');
        http.expectOne(`${listUrl}/cdr_ingest/trigger`).flush({ runId: 'r1' });
        listRequests().forEach((r) => r.flush([row(0)]));

        fixture.destroy();
        vi.advanceTimersByTime(60_000);
        http.expectNone(`${listUrl}/runs/r1`);
        expect(listRequests()).toHaveLength(0);
    });

    it('"Auto" re-fetches every DEFAULT_REFRESH_MS while visible — not when off, not while hidden', () => {
        const c = createLive().componentInstance;

        vi.advanceTimersByTime(DEFAULT_REFRESH_MS - 1);
        expect(listRequests()).toHaveLength(0);
        vi.advanceTimersByTime(1);
        expect(listRequests().map((r) => r.flush([row(1)]))).toHaveLength(1);
        expect(c.runs()[0].committedBatches).toBe(1);

        vi.advanceTimersByTime(DEFAULT_REFRESH_MS);
        expect(listRequests().map((r) => r.flush([row(2)]))).toHaveLength(1);

        c.autoRefresh = false;
        vi.advanceTimersByTime(DEFAULT_REFRESH_MS * 2);
        expect(listRequests()).toHaveLength(0);

        c.autoRefresh = true;
        setHidden(true);
        vi.advanceTimersByTime(DEFAULT_REFRESH_MS * 2);
        expect(listRequests()).toHaveLength(0);

        setHidden(false);
        vi.advanceTimersByTime(DEFAULT_REFRESH_MS);
        expect(listRequests().map((r) => r.flush([row(3)]))).toHaveLength(1);
        expect(c.runs()[0].committedBatches).toBe(3);
    });
});
