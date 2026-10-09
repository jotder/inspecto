import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { EMPTY, Observable, Subject, of, throwError } from 'rxjs';
import { statusRowClasses } from 'app/inspecto/components/status-badge.component';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    aggregateRecon,
    Reconciliation,
    ReconciliationsService,
    ReconRunQuery,
    ReconRunResult,
} from 'app/inspecto/reconciliation';
import { ReconViewWidgetComponent } from './recon-view-widget.component';
import { ReconExecService } from './recon-exec.service';

const RECON: Reconciliation = {
    id: 'med_vs_bill',
    name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily',
    rightDataset: 'billing_daily',
    keyColumns: ['region'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
};

const RESULT: ReconRunResult = {
    ...aggregateRecon(
        RECON,
        [
            { region: 'EU', amount: 100 },
            { region: 'MEA', amount: 10 },
        ],
        [{ region: 'EU', amount: 100 }],
    ),
    day: '2026-09-26',
    availableDays: ['2026-09-26', '2026-09-25'],
    page: { offset: 0, limit: 50, total: 2 },
};

async function create(
    opts: { viewId?: string; fail?: boolean; page?: (q: ReconRunQuery) => Observable<ReconRunResult> } = {},
) {
    const page = vi.fn((_r: Reconciliation, q: ReconRunQuery) => (opts.page ? opts.page(q) : of(RESULT)));
    TestBed.configureTestingModule({
        imports: [ReconViewWidgetComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: Router,
                useValue: { navigate: vi.fn(), createUrlTree: () => ({}), serializeUrl: () => '', events: EMPTY },
            },
            { provide: ActivatedRoute, useValue: { snapshot: {} } },
            {
                provide: ReconciliationsService,
                useValue: { get: () => (opts.fail ? throwError(() => new Error('nope')) : of(RECON)) },
            },
            { provide: ReconExecService, useValue: { page } },
        ],
    });
    const fixture = TestBed.createComponent(ReconViewWidgetComponent);
    if (opts.viewId) fixture.componentRef.setInput('viewId', opts.viewId);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, page, el: fixture.nativeElement as HTMLElement };
}

describe('ReconViewWidgetComponent', () => {
    it('renders one day of band-tinted rows for the bound reconciliation, with no status column', async () => {
        const { c, el, page } = await create({ viewId: RECON.id });
        expect(c.result()).not.toBeNull();
        expect(page).toHaveBeenCalledWith(RECON, { offset: 0, limit: 50, filter: 'all' });
        const text = el.textContent ?? '';
        expect(text).toContain('2026-09-26');
        expect(text).toContain('1 matched');
        expect(text).toContain('1 missing');
        const rows = Array.from(el.querySelectorAll('tbody tr'));
        expect(rows.map((r) => r.getAttribute('data-band'))).toEqual(['ok', 'structural']);
        // the band is tinted AND named: an accessible name per row, a left edge, a shape icon
        for (const cls of statusRowClasses('error').split(' ')) expect(rows[1].classList).toContain(cls);
        expect(rows[1].querySelector('td')!.className).toContain('border-l-4');
        expect(rows[1].querySelector('.sr-only')?.textContent).toContain('Missing on a side');
        expect(rows[0].querySelector('.sr-only')?.textContent).toContain('Within tolerance');
        const heads = Array.from(el.querySelectorAll('thead th')).map((h) => h.textContent?.trim());
        expect(heads).not.toContain('Status');
        expect(heads.join(' ')).not.toMatch(/RAG|RED|GREEN|AMBER/);
        expect(el.querySelector('[aria-label="Band legend"]')?.textContent).toContain('Breach > 2%');
    });

    it('shows a spinner naming the sides and the day — and NOT the empty state — while the request is in flight', async () => {
        const { el, c } = await create({ viewId: RECON.id, page: () => new Subject<ReconRunResult>() });
        expect(c.pages.loading()).toBe(true);
        const status = el.querySelector('[role="status"]');
        expect(status?.textContent).toContain('Comparing mediation_daily and billing_daily for the latest day…');
        expect(el.querySelector('mat-progress-spinner')).not.toBeNull();
        expect(el.textContent).not.toContain('No saved Reconciliation');
    });

    it('a day, filter or page change issues a new request and cancels the one in flight', async () => {
        const inflight: Subject<ReconRunResult>[] = [];
        const { c, page } = await create({
            viewId: RECON.id,
            page: () => {
                const s = new Subject<ReconRunResult>();
                inflight.push(s);
                return s;
            },
        });
        c.request({ day: '2026-09-25', offset: 0 });
        expect(inflight[0].observed).toBe(false); // cancelled
        c.request({ filter: 'breaks', offset: 0 });
        expect(inflight[1].observed).toBe(false);
        c.request({ offset: 50, limit: 50 });
        expect(page).toHaveBeenCalledTimes(4);
        expect(page.mock.calls[3][1]).toEqual({ offset: 50, limit: 50, filter: 'breaks', day: '2026-09-25' });
        expect(inflight[3].observed).toBe(true);
    });

    it('a failed run shows an error with Retry, which asks again', async () => {
        let calls = 0;
        const { el, fixture, page } = await create({
            viewId: RECON.id,
            page: () =>
                ++calls === 1
                    ? throwError(() => new HttpErrorResponse({ status: 500, error: { error: { message: 'boom' } } }))
                    : of(RESULT),
        });
        expect(el.textContent).toContain('boom');
        const retry = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Retry'))!;
        retry.click();
        fixture.detectChanges();
        expect(page).toHaveBeenCalledTimes(2);
        expect(el.textContent).not.toContain('boom');
    });

    it('shows the empty state when no view is bound', async () => {
        const { el } = await create({});
        expect(el.textContent).toContain('No saved Reconciliation');
    });

    it('shows a warning when the reconciliation cannot be loaded', async () => {
        const { el } = await create({ viewId: 'ghost', fail: true });
        expect(el.textContent).toContain('Could not load');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create({ viewId: RECON.id });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
