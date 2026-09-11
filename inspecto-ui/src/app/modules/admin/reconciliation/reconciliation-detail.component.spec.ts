import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { EMPTY, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { ReconApiService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    breakId,
    Reconciliation,
    ReconciliationsService,
    ReconBreak,
    reconBreakSets,
} from 'app/inspecto/reconciliation';
import { ReconciliationDetailComponent } from './reconciliation-detail.component';
import { ReconExecService } from './recon-exec.service';

/** Same reference fixture as the Board/pure-engine specs: MEA only-A, APAC only-B, EU/data value break. */
const LEFT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 118 },
    { region: 'US', product: 'voice', amount: 50 },
    { region: 'MEA', product: 'voice', amount: 10 },
];
const RIGHT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 114 },
    { region: 'US', product: 'voice', amount: 50 },
    { region: 'APAC', product: 'sms', amount: 7 },
];

const recon = (breaks: ReconBreak[] = []): Reconciliation => ({
    id: 'med_vs_bill',
    name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily',
    rightDataset: 'billing_daily',
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
    breaks,
    lastRunAt: null,
});

async function create(opts: { path?: string; breaks?: ReconBreak[]; promote?: ReturnType<typeof vi.fn> } = {}) {
    let current = recon(opts.breaks ?? []);
    const save = vi.fn((r: Reconciliation) => ((current = r), of(r)));
    const breaks = vi.fn(async (r: Reconciliation, path?: Record<string, string> | null) =>
        reconBreakSets(r, LEFT, RIGHT, path),
    );
    const promote =
        opts.promote ??
        vi.fn(() => of({ incidentId: 'inc-1', deduped: false, reconciliation: current.id, key: 'EU · data' }));
    const toastr = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ReconciliationDetailComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ActivatedRoute,
                useValue: {
                    snapshot: {
                        paramMap: convertToParamMap({ id: current.id }),
                        queryParamMap: convertToParamMap(opts.path ? { path: opts.path } : {}),
                    },
                },
            },
            {
                provide: Router,
                useValue: { navigate: vi.fn(), createUrlTree: () => ({}), serializeUrl: () => '', events: EMPTY },
            },
            { provide: ReconciliationsService, useValue: { get: () => of(current), save } },
            { provide: ReconExecService, useValue: { breaks } },
            { provide: ToastrService, useValue: toastr },
            { provide: ReconApiService, useValue: { promote } },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconciliationDetailComponent);
    fixture.detectChanges(); // ngOnInit — load + compute
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, save, breaks, promote, toastr };
}

describe('ReconciliationDetailComponent (Breaks page)', () => {
    it('computes the three live record sets from the exec seam', async () => {
        const { fixture, c } = await create();
        expect(c.missingA().map((b) => b.key)).toEqual(['MEA · voice']);
        expect(c.missingB().map((b) => b.key)).toEqual(['APAC · sms']);
        expect(c.valueBreaks()).toHaveLength(1);
        expect(c.valueBreaks()[0]).toMatchObject({
            key: 'EU · data',
            column: 'amount',
            leftValue: 118,
            rightValue: 114,
        });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Only in A');
        expect(text).toContain('Matched, different');
    });

    it('scopes to the Board dimension path from ?path=', async () => {
        const { c, breaks } = await create({ path: 'region:EU' });
        expect(breaks).toHaveBeenCalledWith(expect.anything(), { region: 'EU' }, null, 'b');
        expect(c.missingA()).toHaveLength(0);
        expect(c.valueBreaks()).toHaveLength(1);
    });

    it('overlays the persisted lifecycle and resolve/re-open persists by identity', async () => {
        const { c, save } = await create();
        const vb = c.valueBreaks()[0];
        expect(vb.status).toBe('open');

        await c.toggleResolve(vb); // first touch appends the live break as resolved
        expect(save).toHaveBeenCalledTimes(1);
        expect(c.valueBreaks()[0].status).toBe('resolved');

        await c.toggleResolve(c.valueBreaks()[0]); // re-open via resolveBreak on the persisted entry
        expect(c.valueBreaks()[0].status).toBe('open');
    });

    // ── BREAK-INCIDENT-1: promote a Break to an Incident ──────────────────────────────

    it('promotes a break with the reconciliation, key and run as evidence', async () => {
        const { c, promote, toastr } = await create();
        const vb = c.valueBreaks()[0];

        await c.promote(vb);

        expect(promote).toHaveBeenCalledWith('med_vs_bill', 'EU · data', 'value_break', 'amount', null);
        expect(toastr.success).toHaveBeenCalled();
        expect(c.isPromoted(vb)).toBe(true);
    });

    it('reports a deduped promotion as information, never as an error', async () => {
        // The server suppressed a second Incident because one is already open for this Break. Nothing
        // went wrong, so an error toast here would teach the operator that a working feature is broken.
        const promote = vi.fn(() =>
            of({ incidentId: null, deduped: true, reconciliation: 'med_vs_bill', key: 'EU · data' }),
        );
        const { c, toastr } = await create({ promote });

        await c.promote(c.valueBreaks()[0]);

        expect(toastr.info).toHaveBeenCalled();
        expect(toastr.error).not.toHaveBeenCalled();
    });

    it('explains a 503 in place and withdraws the action instead of toasting', async () => {
        // A Personal bundle carries no inspecto-ops module, so Incidents do not exist. That is a
        // deployment state, not a failure — the Approvals-inbox lesson.
        const promote = vi.fn(() => throwError(() => ({ status: 503 })));
        const { fixture, c, toastr } = await create({ promote });
        const vb = c.valueBreaks()[0];
        expect(c.rowActions.some((a) => a.visible?.(vb) === false)).toBe(false);

        await c.promote(vb);
        fixture.detectChanges();

        expect(c.incidentsUnavailable()).toBe(true);
        expect(toastr.error).not.toHaveBeenCalled();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Incidents are not installed');
        // and the affordance is gone — one that can only explain itself is worse than none
        const promoteAction = c.rowActions[c.rowActions.length - 1];
        expect(promoteAction.visible?.(vb)).toBe(false);
    });

    it('still toasts a genuine failure', async () => {
        const promote = vi.fn(() => throwError(() => ({ status: 500 })));
        const { c, toastr } = await create({ promote });

        await c.promote(c.valueBreaks()[0]);

        expect(toastr.error).toHaveBeenCalled();
        expect(c.incidentsUnavailable()).toBe(false);
    });

    it('shows a pre-persisted resolution without any interaction', async () => {
        const resolved: ReconBreak = {
            key: 'EU · data',
            type: 'value_break',
            column: 'amount',
            status: 'resolved',
            note: 'known billing lag',
        };
        const { c } = await create({ breaks: [resolved] });
        expect(breakId(c.valueBreaks()[0])).toBe(breakId(resolved));
        expect(c.valueBreaks()[0].status).toBe('resolved');
        expect(c.valueBreaks()[0].note).toBe('known billing lag');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
