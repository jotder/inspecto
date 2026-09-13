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

async function create(
    opts: {
        path?: string;
        breaks?: ReconBreak[];
        promote?: ReturnType<typeof vi.fn>;
        promoted?: ReturnType<typeof vi.fn>;
    } = {},
) {
    let current = recon(opts.breaks ?? []);
    const save = vi.fn((r: Reconciliation) => ((current = r), of(r)));
    const breaks = vi.fn(async (r: Reconciliation, path?: Record<string, string> | null) =>
        reconBreakSets(r, LEFT, RIGHT, path),
    );
    const promote =
        opts.promote ??
        vi.fn(() => of({ incidentId: 'inc-1', deduped: false, reconciliation: current.id, key: 'EU · data' }));
    // ⚠ `promoted` is called on EVERY load (ngOnInit), so the stub must exist or every test in this file
    // fails on an undefined method rather than on what it is asserting.
    const promoted =
        opts.promoted ??
        vi.fn(() => of({ reconciliation: current.id, promoted: {}, total: 0, truncated: false }));
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
            { provide: ReconApiService, useValue: { promote, promoted } },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconciliationDetailComponent);
    fixture.detectChanges(); // ngOnInit — load + compute
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, save, breaks, promote, promoted, toastr };
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
        // ⚠ Identify the promote affordance by its ICON, not by position. This used to assert "no action is
        // invisible" and then read `rowActions[length - 1]` — two positional proxies that both broke when
        // BREAK-INCIDENT-RESOLVE-1 added an "open the Incident" action after it (which is legitimately
        // hidden until a Break HAS an Incident). The intent was always "the promote action is withdrawn".
        const promoteAction = c.rowActions.find((a) => a.icon === 'heroicons_outline:exclamation-triangle');
        expect(promoteAction, 'the promote affordance must exist to be withdrawn').toBeDefined();
        expect(promoteAction!.visible?.(vb)).toBe(true);

        await c.promote(vb);
        fixture.detectChanges();

        expect(c.incidentsUnavailable()).toBe(true);
        expect(toastr.error).not.toHaveBeenCalled();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Incidents are not installed');
        // and the affordance is gone — one that can only explain itself is worse than none
        expect(promoteAction!.visible?.(vb)).toBe(false);
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

describe('promoted Breaks (BREAK-INCIDENT-RESOLVE-1)', () => {
    /**
     * 🔴 The defect: `promoted` used to be an in-memory Set filled only by this tab's own clicks, so a
     * reload forgot every promotion. It is now read from the server on load.
     */
    it('reads the promoted map from the server on load, not from session memory', async () => {
        const { c, promoted } = await create({
            promoted: vi.fn(() =>
                of({ reconciliation: 'r1', promoted: { 'EU · data': 'inc-7' }, total: 1, truncated: false }),
            ),
        });
        expect(promoted).toHaveBeenCalled();
        const b = c.valueBreaks()[0];
        expect(c.isPromoted(b)).toBe(true);
        expect(c.incidentFor(b)).toBe('inc-7');
    });

    it('reports an unpromoted Break as unpromoted and offers no Incident', async () => {
        const { c } = await create();
        const b = c.valueBreaks()[0];
        expect(c.isPromoted(b)).toBe(false);
        expect(c.incidentFor(b)).toBeNull();
    });

    /**
     * ⚠ A 503 means the ops module is absent — a deployment state. It must latch the explained panel, never
     * toast, and must NOT leave the page looking like a healthy board with nothing promoted.
     */
    it('latches the explained panel on 503 instead of toasting', async () => {
        const { c, toastr } = await create({
            promoted: vi.fn(() => throwError(() => ({ status: 503 }))),
        });
        expect(c.incidentsUnavailable()).toBe(true);
        expect(toastr.error).not.toHaveBeenCalled();
    });

    /** ⛔ Any other failure is silent: this is an affordance hint, and a toast on every page load would be
     *  worse than a missing tooltip. */
    it('stays quiet on a non-503 failure', async () => {
        const { c, toastr } = await create({
            promoted: vi.fn(() => throwError(() => ({ status: 500 }))),
        });
        expect(c.incidentsUnavailable()).toBe(false);
        expect(toastr.error).not.toHaveBeenCalled();
    });

    it('follows a promoted Break to its Incident', async () => {
        const { c } = await create({
            promoted: vi.fn(() =>
                of({ reconciliation: 'r1', promoted: { 'EU · data': 'inc-7' }, total: 1, truncated: false }),
            ),
        });
        const router = TestBed.inject(Router);
        c.openIncident(c.valueBreaks()[0]);
        expect(router.navigate).toHaveBeenCalledWith(['/incidents', 'inc-7']);
    });

    /** ⛔ An unpromoted Break must not navigate — `/incidents/null` is a worse answer than none. */
    it('does not navigate for an unpromoted Break', async () => {
        const { c } = await create();
        const router = TestBed.inject(Router);
        c.openIncident(c.valueBreaks()[0]);
        expect(router.navigate).not.toHaveBeenCalled();
    });
});
