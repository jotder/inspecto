import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { JobsService } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobDetailComponent } from './job-detail.component';

const JOB = {
    name: 'cdr_ingest_daily',
    type: 'ingest',
    cron: '0 0 6 * * *',
    onPipeline: null,
    enabled: true,
    lastStatus: 'SUCCESS',
    nextFire: null,
    params: { source: 'cdr_sftp' },
};
const RUNS = [
    {
        jobName: 'cdr_ingest_daily',
        runId: 'run-1',
        status: 'SUCCESS',
        startTime: '2026-06-28T06:00:00Z',
        endTime: '2026-06-28T06:02:00Z',
        durationMs: 120_000,
        triggerType: 'CRON',
        error: null,
    },
];
const LOGS = {
    logs: [{ ts: '2026-06-28T06:00:00Z', level: 'INFO', message: 'Started.' }],
    events: [{ ts: '2026-06-28T06:00:00Z', type: 'JOB_STARTED', message: 'fired' }],
};

/** `inputs` exercises the embedded side-panel mode (R5); without it the route snapshot drives the name. */
function create(inputs?: { name: string; embedded: boolean }) {
    TestBed.configureTestingModule({
        imports: [JobDetailComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'cdr_ingest_daily' } } } },
            {
                provide: JobsService,
                useValue: {
                    get: () => of(JOB),
                    list: () =>
                        of([
                            {
                                name: 'cdr_ingest_daily',
                                type: 'ingest',
                                enabled: true,
                                nextFire: '2026-10-15 02:00:00',
                            },
                        ]),
                    runs: () => of(RUNS),
                    runLogs: () => of(LOGS),
                },
            },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            {
                provide: InspectoConfirmService,
                useValue: { confirm: () => Promise.resolve(false), confirmDestructive: () => Promise.resolve(false) },
            },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(JobDetailComponent);
    if (inputs) {
        fixture.componentRef.setInput('name', inputs.name);
        fixture.componentRef.setInput('embedded', inputs.embedded);
    }
    return fixture;
}

describe('JobDetailComponent', () => {
    it('loads the job + its runs and selects the latest run for the logs tab', () => {
        const fixture = create();
        fixture.detectChanges(); // ngOnInit → load() (synchronous `of` stubs resolve immediately)
        const c = fixture.componentInstance;
        expect(c.job()?.name).toBe('cdr_ingest_daily');
        expect(c.runs().length).toBe(1);
        expect(c.selectedRunId()).toBe('run-1');
        expect(c.logs()?.logs.length).toBe(1);
        expect(c.whatScheduled()).toBe('Ingest · cdr_sftp');
        expect(c.job()?.nextFire).toBe('2026-10-15 02:00:00'); // borrowed from the list row
    });

    it('embedded mode hides the page chrome, reloads on a name change, and emits closed on X (R5)', async () => {
        const fixture = create({ name: 'cdr_ingest_daily', embedded: true });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('h1')).toBeNull(); // full-page breadcrumb/header chrome hidden
        expect(el.querySelector('h2')?.textContent).toContain('cdr_ingest_daily');

        const spy = vi.spyOn(TestBed.inject(JobsService), 'get');
        fixture.componentRef.setInput('name', 'other_job');
        fixture.detectChanges(); // flushes the reload effect (panel stays mounted across jobs)
        expect(c.name).toBe('other_job');
        expect(spy).toHaveBeenCalledWith('other_job');

        const closed = vi.fn();
        c.closed.subscribe(closed);
        (el.querySelector('button[aria-label="Close panel"]') as HTMLButtonElement).click();
        expect(closed).toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('the embedded panel offers Run with parameters…, opens the dialog and reloads + toasts on a started run', () => {
        const fixture = create({ name: 'cdr_ingest_daily', embedded: true });
        const open = vi.fn(() => ({ afterClosed: () => of({ runId: 'r9' }) }));
        fixture.debugElement.injector.get(MatDialog).open = open as never;
        const toast = vi.spyOn(TestBed.inject(ToastrService), 'success');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const btn = Array.from(el.querySelectorAll('button')).find((b) =>
            b.textContent?.includes('Run with parameters'),
        ) as HTMLButtonElement;
        expect(btn).toBeTruthy();
        const get = vi.spyOn(TestBed.inject(JobsService), 'get');
        btn.click();
        expect(open).toHaveBeenCalledWith(expect.anything(), {
            width: '560px',
            data: { name: 'cdr_ingest_daily', type: 'ingest' },
        });
        expect(toast).toHaveBeenCalledWith('Job "cdr_ingest_daily" run started.');
        expect(get).toHaveBeenCalled(); // load() after the run
    });

    it('hides Run with parameters… without the canOperateRuns capability', () => {
        const fixture = create({ name: 'cdr_ingest_daily', embedded: true });
        const session = TestBed.inject(SessionService);
        session.authMode.set('oidc');
        session.capabilities.set(['canAuthorWorkbench']);
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).not.toContain('Run with parameters');
    });

    it('shows a structured parameter as JSON, not [object Object]', () => {
        const fixture = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.job.set({ ...JOB, params: { day: '$yesterday', incremental: { by: 'day', lookback: 1 } } } as never);
        expect(c.paramRows()).toEqual([
            { key: 'day', value: '$yesterday' },
            { key: 'incremental', value: '{"by":"day","lookback":1}' },
        ]);
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
