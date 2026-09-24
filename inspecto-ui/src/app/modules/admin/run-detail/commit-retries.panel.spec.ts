import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { CommitRetriesPage, commitRetryErrorMessage, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CommitRetriesPanelComponent } from './commit-retries.panel';

const POLICY = { maxAttempts: 5, initialBackoffMs: 30_000, maxBackoffMs: 7_200_000, bounded: true };

function page(over: Partial<CommitRetriesPage> = {}): CommitRetriesPage {
    return {
        pipeline: 'cdr',
        keepsRetryState: true,
        policy: POLICY,
        total: 2,
        truncated: false,
        retries: [
            {
                file: 'in/a.csv',
                attempts: 2,
                firstFailedAt: '2026-09-25T08:00:00Z',
                lastFailedAt: '2026-09-25T08:05:00Z',
                nextRetryAt: '2026-09-25T09:00:00Z',
                due: false,
                lastError: 'duckdb: disk full',
                inInbox: true,
                readable: true,
            },
            {
                file: 'in/b.csv',
                attempts: 0,
                firstFailedAt: null,
                lastFailedAt: null,
                nextRetryAt: null,
                due: true,
                lastError: null,
                inInbox: true,
                readable: false,
            },
        ],
        ...over,
    };
}

function create(opts: { canOperate?: boolean; confirm?: boolean } = {}) {
    const toastr = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };
    const confirm = {
        confirmDestructive: vi.fn((_m: string, _o?: unknown) => Promise.resolve(opts.confirm ?? true)),
    };
    TestBed.configureTestingModule({
        imports: [CommitRetriesPanelComponent],
        providers: [
            provideHttpClient(withXhr()),
            provideHttpClientTesting(),
            provideNoopAnimations(),
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: confirm },
            { provide: LensService, useValue: { canOperateRuns: () => opts.canOperate ?? true } },
            InspectoGridThemeService,
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    // `<inspecto-data-table>` injects the real MatDialog — a plain useValue provider is silently ignored.
    TestBed.overrideProvider(MatDialog, { useValue: { open: vi.fn() } });
    const fixture = TestBed.createComponent(CommitRetriesPanelComponent);
    fixture.componentRef.setInput('pipeline', 'cdr');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    return { fixture, http, toastr, confirm, el: fixture.nativeElement as HTMLElement };
}

const LIST = (url: string) => url.endsWith('/runs/cdr/retries');
const testid = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`);

function flushList(http: HttpTestingController, body: CommitRetriesPage): void {
    http.expectOne((r) => r.method === 'GET' && LIST(r.url)).flush(body);
}

describe('CommitRetriesPanelComponent (X1 commit retries)', () => {
    it('lists the queue with attempts / max, due, last error and flags unreadable records', async () => {
        const { fixture, http, el } = create();
        flushList(http, page());
        fixture.detectChanges();
        await fixture.whenStable();

        const c = fixture.componentInstance;
        expect(testid(el, 'no-retry-state')).toBeNull();
        expect(testid(el, 'no-retries')).toBeNull();
        expect(testid(el, 'retries-unreadable')!.textContent).toContain('1 retry record(s)');
        expect(testid(el, 'retry-policy')!.textContent).toContain('Up to 5 attempts');
        expect(testid(el, 'retry-reason')).toBeNull();
        const getter = c.columns.find((col) => col.colId === 'attempts')!.valueGetter as (p: unknown) => string;
        expect(getter({ data: page().retries[0] })).toBe('2 / 5');
        const next = c.columns.find((col) => col.colId === 'nextRetry')!.cellRenderer as (p: unknown) => string;
        expect(next({ data: { ...page().retries[0], due: true } })).toContain('due');
        const rec = c.columns.find((col) => col.colId === 'readable')!.cellRenderer as (p: unknown) => string;
        expect(rec({ data: page().retries[1] })).toContain('unreadable');
        expect(rec({ data: page().retries[0] })).toBe('');
        expect(el.textContent).toContain('in/a.csv');
        expect(el.textContent).toContain('duckdb: disk full');
        await expectNoA11yViolations(el);
        http.verify();
    });

    it('tells "keeps no retry state" apart from "no retries pending"', () => {
        const { fixture, http, el } = create();
        flushList(http, page({ keepsRetryState: false, note: 'no dirs.status_dir', total: 0, retries: [] }));
        fixture.detectChanges();
        expect(testid(el, 'no-retry-state')!.textContent).toContain('keeps no retry state');
        expect(testid(el, 'no-retries')).toBeNull();

        fixture.componentInstance.load();
        flushList(http, page({ total: 0, retries: [] }));
        fixture.detectChanges();
        expect(testid(el, 'no-retry-state')).toBeNull();
        expect(testid(el, 'no-retries')!.textContent).toContain('No retries pending');
    });

    it('notes a truncated listing with the true total', () => {
        const { fixture, http, el } = create();
        flushList(http, page({ total: 900, truncated: true }));
        fixture.detectChanges();
        expect(testid(el, 'retries-truncated')!.textContent).toContain('first 2 of 900');
    });

    it('retry now posts the file and shows the server note (attempts kept)', () => {
        const { fixture, http, el, toastr } = create();
        flushList(http, page());
        const c = fixture.componentInstance;
        c.retryNow(page().retries[0]);
        const req = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/runs/cdr/retries/retry-now'));
        expect(req.request.body).toEqual({ file: 'in/a.csv' });
        req.flush({
            pipeline: 'cdr',
            file: 'in/a.csv',
            outcome: 'rescheduled',
            attempts: 2,
            maxAttempts: 5,
            attemptsKept: true,
            note: 'retry-now clears the backoff only; the attempt count is kept (2 of 5 used)',
        });
        flushList(http, page());
        fixture.detectChanges();
        expect(testid(el, 'retry-note')!.textContent).toContain('attempt count is kept (2 of 5 used)');
        expect(toastr.success).toHaveBeenCalled();
        http.verify();
    });

    it('cancel confirms (quarantine as retry_cancelled, not retried again) before posting', async () => {
        const { fixture, http, confirm, toastr } = create();
        flushList(http, page());
        await fixture.componentInstance.cancel(page().retries[0]);
        const message = confirm.confirmDestructive.mock.calls[0][0] as string;
        expect(message).toContain('retry_cancelled');
        expect(message).toContain('not retried again');
        const req = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/runs/cdr/retries/cancel'));
        expect(req.request.body).toEqual({ file: 'in/a.csv' });
        req.flush({
            pipeline: 'cdr',
            file: 'in/a.csv',
            outcome: 'cancelled',
            attempts: 2,
            maxAttempts: 5,
            quarantineReason: 'retry_cancelled',
        });
        flushList(http, page());
        expect(toastr.success.mock.calls[0][0]).toContain('retry_cancelled');
        http.verify();
    });

    it('a dismissed cancel confirm posts nothing', async () => {
        const { fixture, http } = create({ confirm: false });
        flushList(http, page());
        await fixture.componentInstance.cancel(page().retries[0]);
        http.expectNone((r) => r.method === 'POST');
        http.verify();
    });

    it('maps a mid-cycle 409 to a clear try-again message', () => {
        const { fixture, http, toastr } = create();
        flushList(http, page());
        fixture.componentInstance.retryNow(page().retries[0]);
        http.expectOne((r) => r.url.endsWith('/retries/retry-now')).flush(
            {
                error: {
                    message:
                        "'in/a.csv': the pipeline is running a cycle now; nothing was changed — try again when it finishes",
                },
            },
            { status: 409, statusText: 'Conflict' },
        );
        expect(toastr.error.mock.calls[0][0]).toContain('processing a cycle right now');
        expect(testid(fixture.nativeElement, 'retry-note')).toBeNull();
    });

    it('without canOperateRuns the actions are disabled with a reason and do nothing', async () => {
        const { fixture, http, el, confirm } = create({ canOperate: false });
        flushList(http, page());
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(testid(el, 'retry-reason')!.textContent).toContain('canOperateRuns');
        for (const a of c.rowActions) {
            expect(a.disabled!(page().retries[0])).toBe(true);
            expect((a.hint as (r: unknown) => string)(page().retries[0])).toContain('canOperateRuns');
        }
        c.retryNow(page().retries[0]);
        await c.cancel(page().retries[0]);
        expect(confirm.confirmDestructive).not.toHaveBeenCalled();
        http.expectNone((r) => r.method === 'POST');
    });
});

describe('commitRetryErrorMessage', () => {
    const err = (status: number, message: string) => new HttpErrorResponse({ status, error: { error: { message } } });
    it('tells the three 409 causes apart by the server reason, and keeps that reason', () => {
        const noState = commitRetryErrorMessage(
            err(409, "'a.csv': this pipeline keeps no retry state (no dirs.status_dir)"),
            'a.csv',
            'retry-now',
        );
        expect(noState).toContain('Not retried: this pipeline keeps no retry state');
        expect(noState).toContain("('a.csv': this pipeline keeps no retry state");
        const quarantined = commitRetryErrorMessage(
            err(409, "'a.csv': already quarantined under 'retry_exhausted': its fate is decided and it is not retried"),
            'a.csv',
            'cancel',
        );
        expect(quarantined).toContain('Not cancelled: "a.csv" is already quarantined');
        expect(commitRetryErrorMessage(err(409, 'could not act on the retry record: io'), 'a.csv', 'cancel')).toContain(
            'could not be acted on',
        );
    });
    it('names the next step for 404 / 403 and falls back otherwise', () => {
        expect(commitRetryErrorMessage(err(404, 'no retry record'), 'a.csv', 'cancel')).toContain('reload the list');
        expect(commitRetryErrorMessage(err(403, 'x'), 'a.csv', 'retry-now')).toContain('canOperateRuns');
        expect(commitRetryErrorMessage(err(500, ''), 'a.csv', 'retry-now')).toBe('Retry now failed for "a.csv"');
    });
});
