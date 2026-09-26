import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { EMPTY, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { MatDialog } from '@angular/material/dialog';
import { LensService, ReconApiService, SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    breakId,
    Reconciliation,
    buildReconciliation,
    ReconciliationsService,
    ReconBreak,
    ReconBreakSets,
    reconBreakSets,
} from 'app/inspecto/reconciliation';
import { fieldDiff, ReconciliationDetailComponent } from './reconciliation-detail.component';
import { ReconExecService, serverConfig } from './recon-exec.service';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { Dataset } from '../studio/datasets/dataset-types';
import { formatNumber } from 'app/inspecto/viz/number-format';
import { fmtDateTime } from 'app/inspecto/format';

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

const recon = (): Reconciliation => ({
    id: 'med_vs_bill',
    name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily',
    rightDataset: 'billing_daily',
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
});

/** A `/recon/rows` answer — the raw rows behind one key. */
const ROWS = {
    key: { msisdn: 'm9' },
    a: { rows: [{ msisdn: 'm9', fee: 50 }], rowCount: 1, truncated: false },
    b: {
        rows: [
            { msisdn: 'm9', fee: 50 },
            { msisdn: 'm9', fee: 50 },
        ],
        rowCount: 2,
        truncated: false,
    },
};

async function create(
    opts: {
        path?: string;
        /** The server-RECORDED lifecycle (`GET /recon/{id}/state`, R2-03). */
        breaks?: ReconBreak[];
        lastRunAt?: string | null;
        promote?: ReturnType<typeof vi.fn>;
        promoted?: ReturnType<typeof vi.fn>;
        patch?: Partial<Reconciliation>;
        left?: Record<string, unknown>[];
        right?: Record<string, unknown>[];
        /** A literal `/recon/breaks` payload instead of the offline mirror (which carries no `impact`). */
        sets?: ReconBreakSets;
        rows?: ReturnType<typeof vi.fn>;
        datasets?: Partial<Dataset>[];
        /** The Lens' `canOperateRuns` (the Assign gate); default true. */
        canOperateRuns?: boolean;
        /** What the Assign dialog closes with; when set, MatDialog is stubbed. */
        dialogResult?: string;
    } = {},
) {
    const current: Reconciliation = { ...recon(), ...opts.patch };
    const save = vi.fn((r: Reconciliation) => of(r));
    const state = vi.fn(() =>
        of({ reconciliation: current.id, lastRunAt: opts.lastRunAt ?? null, runs: 1, breaks: opts.breaks ?? [] }),
    );
    // Echo the server: the Break by identity with the new status and (trimmed) note.
    const setBreakStatus = vi.fn(
        (_id: string, b: ReconBreak, status: 'resolved' | 'open' | 'assigned', note?: string, assignee?: string) =>
            of({
                reconciliation: current.id,
                break: { ...b, status, note: note || undefined, ...(assignee ? { assignee } : {}) },
            }),
    );
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of(opts.dialogResult) })) };
    const breaks = vi.fn(
        async (r: Reconciliation, path?: Record<string, string> | null) =>
            opts.sets ?? reconBreakSets(r, opts.left ?? LEFT, opts.right ?? RIGHT, path),
    );
    const promote =
        opts.promote ??
        vi.fn(() => of({ incidentId: 'inc-1', deduped: false, reconciliation: current.id, key: 'EU · data' }));
    // ⚠ `promoted` is called on EVERY load (ngOnInit), so the stub must exist or every test in this file
    // fails on an undefined method rather than on what it is asserting.
    const promoted =
        opts.promoted ?? vi.fn(() => of({ reconciliation: current.id, promoted: {}, total: 0, truncated: false }));
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
            { provide: ReconExecService, useValue: { breaks, rows: opts.rows ?? vi.fn(async () => ROWS) } },
            { provide: DatasetsService, useValue: { list: () => of(opts.datasets ?? []) } },
            { provide: ToastrService, useValue: toastr },
            { provide: ReconApiService, useValue: { promote, promoted, state, setBreakStatus } },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: LensService, useValue: { canOperateRuns: () => opts.canOperateRuns ?? true } },
            { provide: SessionService, useValue: { actor: () => 'me' } },
        ],
    });
    // The data-table injects the real MatDialog, so a plain provider would be silently ignored.
    if (opts.dialogResult !== undefined) TestBed.overrideProvider(MatDialog, { useValue: dialog });
    const fixture = TestBed.createComponent(ReconciliationDetailComponent);
    fixture.detectChanges(); // ngOnInit — load + compute
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, save, breaks, promote, promoted, toastr, setBreakStatus, dialog };
}

describe('ReconciliationDetailComponent (Breaks page)', () => {
    /** ASSURE-BREAK-LIFECYCLE-1: the recorded counters, server age and assignee overlay onto the live Break. */
    it('shows the recorded age, occurrences, recurrence and assignee of a Break', async () => {
        const { fixture, c } = await create({
            breaks: [
                {
                    pair: 'AB',
                    key: 'MEA · voice',
                    type: 'missing_right',
                    status: 'assigned',
                    assignee: 'dana',
                    firstSeenAt: '2026-07-01T00:00:00Z',
                    lastSeenAt: '2026-09-01T00:00:00Z',
                    occurrences: 4,
                    recurrences: 1,
                    ageDays: 87,
                },
            ],
        });
        const mea = c.missingA()[0];
        expect(mea).toMatchObject({
            status: 'assigned',
            assignee: 'dana',
            occurrences: 4,
            recurrences: 1,
            ageDays: 87,
        });
        expect(c.seenText(mea)).toBe('4 runs · recurred 1×');
        expect(c.seenText(c.missingB()[0])).toBe('—');
        expect(c.missingColumns().map((col) => col.colId ?? col.field)).toEqual(
            expect.arrayContaining(['age', 'seen', 'assignee']),
        );
        await fixture.whenStable();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('an auto-closed record overlays its counters but not its assignee — the live Break is open again', async () => {
        const { c } = await create({
            breaks: [
                {
                    pair: 'AB',
                    key: 'MEA · voice',
                    type: 'missing_right',
                    status: 'auto_closed',
                    assignee: 'dana',
                    occurrences: 2,
                    recurrences: 0,
                },
            ],
        });
        expect(c.missingA()[0]).toMatchObject({ status: 'open', occurrences: 2 });
        expect(c.missingA()[0].assignee).toBeUndefined();
    });

    it('assigns a Break through the status route, keeping its note', async () => {
        const { c, setBreakStatus, dialog, save } = await create({
            dialogResult: 'lee',
            breaks: [
                { pair: 'AB', key: 'EU · data', type: 'value_break', column: 'amount', status: 'open', note: 'FX' },
            ],
        });
        c.assign(c.valueBreaks()[0]);
        expect(dialog.open).toHaveBeenCalledWith(
            expect.anything(),
            expect.objectContaining({ data: expect.objectContaining({ assignee: 'me' }) }),
        );
        expect(setBreakStatus).toHaveBeenCalledWith(
            'med_vs_bill',
            expect.objectContaining({ key: 'EU · data' }),
            'assigned',
            'FX',
            'lee',
        );
        expect(c.valueBreaks()[0]).toMatchObject({ status: 'assigned', assignee: 'lee' });
        expect(save).not.toHaveBeenCalled();
    });

    it('offers Assign only to a user who may operate runs — the route is canOperateRuns', async () => {
        const { c, setBreakStatus } = await create({ canOperateRuns: false, dialogResult: 'lee' });
        const assign = c.rowActions.find((a) => a.icon === 'heroicons_outline:user-plus')!;
        expect(assign.visible!(c.valueBreaks()[0])).toBe(false);
        c.assign(c.valueBreaks()[0]);
        expect(setBreakStatus).not.toHaveBeenCalled();
    });

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

    /**
     * R2-03: a resolve / re-open is the server's `canOperateRuns` status route, never a config save — the
     * authoring PUT 403'd for an operations-only user, so a Break could not be resolved by the people working it.
     */
    it('resolves and re-opens a Break through the status route, never a config save', async () => {
        const { c, save, setBreakStatus } = await create();
        const vb = c.valueBreaks()[0];
        expect(vb.status).toBe('open');

        await c.toggleResolve(vb); // first touch: the server appends the live Break as resolved
        expect(setBreakStatus).toHaveBeenCalledWith(
            'med_vs_bill',
            expect.objectContaining({ key: 'EU · data' }),
            'resolved',
        );
        expect(c.valueBreaks()[0].status).toBe('resolved');

        await c.toggleResolve(c.valueBreaks()[0]); // re-open replaces the recorded entry by identity
        expect(setBreakStatus).toHaveBeenLastCalledWith('med_vs_bill', expect.anything(), 'open');
        expect(c.valueBreaks()[0].status).toBe('open');
        expect(c.state()?.breaks).toHaveLength(1);
        expect(save).not.toHaveBeenCalled();
    });

    it('toasts a status change the server refused and leaves the Break as it was', async () => {
        const { c, setBreakStatus, toastr } = await create();
        setBreakStatus.mockReturnValueOnce(throwError(() => ({ status: 403 })));
        await c.toggleResolve(c.valueBreaks()[0]);
        expect(toastr.error).toHaveBeenCalled();
        expect(c.valueBreaks()[0].status).toBe('open');
    });

    it('overlays the recorded first sighting, so the Breaks page ages what the Board recorded', async () => {
        const firstSeenAt = new Date(Date.now() - 45 * 86_400_000).toISOString();
        const { c } = await create({
            breaks: [{ key: 'EU · data', type: 'value_break', column: 'amount', status: 'open', firstSeenAt }],
        });
        expect(c.valueBreaks()[0].firstSeenAt).toBe(firstSeenAt);
        expect(c.ageText(c.valueBreaks()[0])).toBe('45d');
        // MEA/APAC are live but unrecorded — no sighting, so 'unknown', never 0 days
        expect(c.ageBuckets()).toEqual([
            { bucket: '30-60', count: 1 },
            { bucket: 'unknown', count: 2 },
        ]);
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

    it('names the last RECORDED run as the promoted evidence', async () => {
        const { c, promote } = await create({ lastRunAt: '2026-09-26T08:00:00.000Z' });
        await c.promote(c.valueBreaks()[0]);
        expect(promote).toHaveBeenCalledWith(
            'med_vs_bill',
            'EU · data',
            'value_break',
            'amount',
            '2026-09-26T08:00:00.000Z',
        );
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

    /**
     * A 3-way Reconciliation records its A-vs-C Breaks too, and the "A vs C" tab overlays them — by the
     * LIFECYCLE identity, pair included, so the A-vs-B record on the same key and column never bleeds across.
     * (The exec stub answers the same sets for either side, so both tabs show the same live `EU · data` Break.)
     */
    it('overlays the recorded state per pair on a 3-way Reconciliation, and resolves an A vs C Break as AC', async () => {
        const abSeen = new Date(Date.now() - 45 * 86_400_000).toISOString();
        const acSeen = new Date(Date.now() - 5 * 86_400_000).toISOString();
        const { c, breaks, setBreakStatus } = await create({
            patch: { thirdDataset: 'crm_daily' },
            breaks: [
                {
                    key: 'EU · data',
                    type: 'value_break',
                    column: 'amount',
                    status: 'resolved',
                    note: 'B lag',
                    firstSeenAt: abSeen,
                },
                {
                    pair: 'AC',
                    key: 'EU · data',
                    type: 'value_break',
                    column: 'amount',
                    status: 'open',
                    firstSeenAt: acSeen,
                },
            ],
        });
        // A vs B: the pair-less record reads as AB
        expect(c.valueBreaks()[0]).toMatchObject({
            pair: 'AB',
            status: 'resolved',
            note: 'B lag',
            firstSeenAt: abSeen,
        });

        c.setSide('c');
        await vi.waitFor(() => expect(c.computing()).toBe(false));
        expect(breaks).toHaveBeenLastCalledWith(expect.anything(), c.path(), null, 'c');
        expect(c.valueBreaks()[0]).toMatchObject({ pair: 'AC', status: 'open', firstSeenAt: acSeen });
        expect(c.valueBreaks()[0].note).toBeUndefined();
        expect(c.ageText(c.valueBreaks()[0])).toBe('5d');

        await c.toggleResolve(c.valueBreaks()[0]);
        expect(setBreakStatus).toHaveBeenCalledWith('med_vs_bill', expect.objectContaining({ pair: 'AC' }), 'resolved');
        expect(c.valueBreaks()[0].status).toBe('resolved');
        expect(c.state()?.breaks).toHaveLength(2);

        // and the A-vs-B record is exactly as it was
        c.setSide('b');
        await vi.waitFor(() => expect(c.computing()).toBe(false));
        expect(c.valueBreaks()[0]).toMatchObject({
            pair: 'AB',
            status: 'resolved',
            note: 'B lag',
            firstSeenAt: abSeen,
        });
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
     *
     * ⚠ The stub's map is keyed by the Break IDENTITY the server actually returns — `type|key|column`
     * (`BREAK-DEDUPE-GRAIN-1`), not the bare key it used to send. The literal spelling is deliberate:
     * this is a wire contract shared with `ReconRoutes.breakIdentity()`, so computing it with `breakId()`
     * here would let both sides drift together and still agree.
     */
    it('reads the promoted map from the server on load, not from session memory', async () => {
        const { c, promoted } = await create({
            promoted: vi.fn(() =>
                of({
                    reconciliation: 'r1',
                    promoted: { 'value_break|EU · data|amount': 'inc-7' },
                    total: 1,
                    truncated: false,
                }),
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
                of({
                    reconciliation: 'r1',
                    promoted: { 'value_break|EU · data|amount': 'inc-7' },
                    total: 1,
                    truncated: false,
                }),
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

describe('Breaks page for an analyst (UIE-10)', () => {
    /** Two compared fields: EU·data differs on amount only, US·voice on units only. */
    const LEFT2 = LEFT.map((r) => ({ ...r, units: r.region === 'US' ? 3 : 5 }));
    const RIGHT2 = RIGHT.map((r) => ({ ...r, units: r.region === 'US' ? 4 : 5 }));
    const twoFields: Partial<Reconciliation> = {
        compareColumns: [
            { column: 'amount', toleranceType: 'percent', tolerance: 0.5 },
            { column: 'units', toleranceType: 'exact', tolerance: 0 },
        ],
    };
    const text = (el: HTMLElement) => el.textContent ?? '';

    it('titles the page with the description, not the code', async () => {
        const { fixture } = await create({ patch: { description: 'Mediation vs Billing — daily revenue' } });
        const h1 = (fixture.nativeElement as HTMLElement).querySelector('h1');
        expect(h1?.textContent).toContain('Mediation vs Billing — daily revenue');
        expect(h1?.textContent).not.toContain('med_vs_bill');
    });

    it('falls back to the name when no description is declared', async () => {
        const { c } = await create();
        expect(c.title()).toBe('Mediation vs Billing');
    });

    it('renders a value break as `field: A → B`', async () => {
        const { c } = await create();
        expect(fieldDiff(c.valueBreaks()[0])).toBe('Amount: 118 → 114');
        const col = c.valueColumns().find((d) => d.colId === 'fieldDiff');
        expect(col?.headerName).toBe('Field diff (mediation_daily → billing_daily)');
    });

    it('signs Δ along the Field diff arrow: 118 → 114 is ▼ -4 (B − A)', async () => {
        const { c } = await create();
        const b = c.valueBreaks()[0];
        expect(fieldDiff(b)).toBe('Amount: 118 → 114');
        expect(b.diff).toBe(-4);
        const col = c.valueColumns().find((d) => d.field === 'diff')!;
        const render = col.cellRenderer as (p: { value: unknown }) => string;
        expect(render({ value: b.diff })).toBe('<span class="text-red-600 dark:text-red-400">▼ -4</span>');
    });

    it('shows the evaluation date as well as the time', async () => {
        const { fixture, c } = await create();
        const at = new Date(2026, 8, 20, 0, 0, 48);
        c.lastEvaluated.set(at);
        fixture.detectChanges();
        const squash = (t: string) => t.replace(/\s+/g, ' ');
        const text = squash((fixture.nativeElement as HTMLElement).textContent ?? '');
        expect(text).toContain(squash(`Last evaluated: ${fmtDateTime(at)} ·`));
        // R3-01: the app's one spelling, never the host's `9/20/2026, 12:00:48 AM`.
        expect(text).toContain('Last evaluated: 20 Sep 2026, 00:00:48');
    });

    it('shows only the mismatched fields of the selected Break', async () => {
        const { fixture, c } = await create({ patch: twoFields, left: LEFT2, right: RIGHT2 });
        const eu = c.valueBreaks().find((b) => b.key === 'EU · data')!;
        c.select(eu as unknown as Record<string, unknown>);
        fixture.detectChanges();
        expect(c.selectedFields()).toEqual([{ field: 'amount', label: 'Amount', left: '118', right: '114' }]);
        const table = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="field-diff"]')!;
        expect(text(table as HTMLElement)).toContain('Amount');
        expect(text(table as HTMLElement)).not.toContain('Units');
        // R3-03: the stored column name stays one hover away.
        expect((table.querySelector('tbody td') as HTMLElement).title).toBe('amount');

        const us = c.valueBreaks().find((b) => b.key === 'US · voice')!;
        c.select(us as unknown as Record<string, unknown>);
        expect(c.selectedFields()).toEqual([{ field: 'units', label: 'Units', left: '3', right: '4' }]);
    });

    // R3-03: the Field diff printed the raw column (`monthly_fee_sar: 149 → 99`); it reads as the Board names
    // columns, with the raw name in the cell tooltip.
    it('humanises the Field diff column name and keeps the raw name in the tooltip', async () => {
        const { c } = await create();
        const b = { ...c.valueBreaks()[0], column: 'monthly_fee_sar', leftValue: 149, rightValue: 99 };
        expect(fieldDiff(b)).toBe('Monthly fee (SAR): 149 → 99');
        const col = c.valueColumns().find((d) => d.colId === 'fieldDiff')!;
        expect((col.tooltipValueGetter as (p: { data: unknown }) => string)({ data: b })).toBe('monthly_fee_sar');
    });

    it('shows the impact per Break in the declared currency', async () => {
        const { fixture, c } = await create({ patch: { impact: { column: 'amount', currency: 'SAR' } } });
        const sar = (v: number) => formatNumber(v, { style: 'currency', currency: 'SAR' });
        expect(c.impactHeader()).toBe('Impact (SAR)');
        expect(c.impactText(c.valueBreaks()[0])).toBe(sar(4));
        expect(c.impactText(c.missingA()[0])).toBe(sar(10));
        expect(c.impactText(c.missingB()[0])).toBe(sar(7));
        expect(sar(4)).toContain('SAR');
        expect(c.missingColumns().some((d) => d.colId === 'impact')).toBe(true);

        c.select(c.valueBreaks()[0] as unknown as Record<string, unknown>);
        fixture.detectChanges();
        const shown = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="selected-impact"]');
        expect(text(shown as HTMLElement)).toContain(sar(4));
    });

    it('shows a CARRIED (non-compared) impact column as the value on the side that has it', async () => {
        // MEA active only in A (fee 199), APAC only in B (fee 30), EU/data differs with both fees 149:
        // `fee` is not compared, so the server carried it as impact: {a, b}.
        const sets: ReconBreakSets = {
            missing_right: {
                rows: [{ key: { region: 'MEA', product: 'voice' }, a: { amount: 10 }, impact: { a: 199, b: null } }],
                rowCount: 1,
                truncated: false,
            },
            missing_left: {
                rows: [{ key: { region: 'APAC', product: 'sms' }, b: { amount: 7 }, impact: { a: null, b: 30 } }],
                rowCount: 1,
                truncated: false,
            },
            value_break: {
                rows: [
                    {
                        key: { region: 'EU', product: 'data' },
                        a: { amount: 118 },
                        b: { amount: 114 },
                        impact: { a: 149, b: 149 },
                    },
                ],
                rowCount: 1,
                truncated: false,
            },
        };
        const { breaks, c } = await create({ sets, patch: { impact: { column: 'fee', currency: 'SAR' } } });
        const sar = (v: number) => formatNumber(v, { style: 'currency', currency: 'SAR' });
        expect(c.impactHeader()).toBe('Impact (SAR)');
        expect(c.impactText(c.missingA()[0])).toBe(sar(199));
        expect(c.impactText(c.missingB()[0])).toBe(sar(30));
        expect(c.impactText(c.valueBreaks()[0])).toBe(sar(149));
        // the server is told the column, or it has nothing to carry
        expect(serverConfig(breaks.mock.calls[0][0] as Reconciliation).impact).toEqual({
            column: 'fee',
            currency: 'SAR',
        });
    });

    it('sends the stored cardinality, column map and filters to the server (they were dropped)', () => {
        const recon = {
            ...buildReconciliation('r1', 'hlr', 'cbs', ['msisdn'], []),
            raw: {
                cardinality: 'one-to-one',
                columnMap: { cbs: { msisdn: 'MSISDN' } },
                filters: { hlr: "status <> 'X'" },
                includeRecordCount: false,
            },
        } as Reconciliation;
        expect(serverConfig(recon)).toMatchObject({
            cardinality: 'one-to-one',
            columnMap: { cbs: { msisdn: 'MSISDN' } },
            filters: { hlr: "status <> 'X'" },
            includeRecordCount: false,
        });
        const plain = serverConfig(buildReconciliation('r2', 'a', 'b', ['id'], []));
        expect(plain.includeRecordCount).toBe(true);
        expect(plain).not.toHaveProperty('cardinality');
        expect(plain).not.toHaveProperty('columnMap');
    });

    it('carries no impact column when none is declared', async () => {
        const { c } = await create();
        expect(c.impactHeader()).toBeNull();
        expect(c.valueColumns().some((d) => d.colId === 'impact')).toBe(false);
        expect(c.impactText(c.valueBreaks()[0])).toBe('—');
    });

    it('offers a labelled Promote to Incident button that promotes the selected Break', async () => {
        const { fixture, c, promote } = await create();
        c.select(c.valueBreaks()[0] as unknown as Record<string, unknown>);
        fixture.detectChanges();
        const panel = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="selected-break"]')!;
        const btn = [...panel.querySelectorAll('button')].find((b) => b.textContent?.includes('Promote to Incident'));
        expect(btn, 'a visible, labelled promote action').toBeDefined();

        btn!.click();
        await fixture.whenStable();
        expect(promote).toHaveBeenCalledWith('med_vs_bill', 'EU · data', 'value_break', 'amount', null);
    });

    it('withdraws the promote button once Incidents are known to be absent', async () => {
        const promoted = vi.fn(() => throwError(() => ({ status: 503 })));
        const { fixture, c } = await create({ promoted });
        c.select(c.valueBreaks()[0] as unknown as Record<string, unknown>);
        fixture.detectChanges();
        expect(text(fixture.nativeElement)).not.toContain('Promote to Incident');
    });

    it('renders the selected Break with no a11y violations', async () => {
        const { fixture, c } = await create({ patch: { impact: { column: 'amount', currency: 'SAR' } } });
        c.select(c.valueBreaks()[0] as unknown as Record<string, unknown>);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('Duplicate keys — cardinality Breaks on the Breaks page', () => {
    /** RA-C01's shape: one_to_one on msisdn, status compared, the fee carried; m9 is billed twice on B. */
    const SUBS: Partial<Reconciliation> = {
        id: 'ra_c01',
        name: 'ra_c01',
        leftDataset: 'hlr_subscribers',
        rightDataset: 'cbs_subscribers',
        keyColumns: ['msisdn'],
        compareColumns: [{ column: 'active_flag', toleranceType: 'exact', tolerance: 0 }],
        impact: { column: 'fee', currency: 'SAR' },
        raw: { cardinality: 'one_to_one' },
    };
    const sets: ReconBreakSets = {
        missing_right: {
            rows: [{ key: { msisdn: 'm3' }, a: { active_flag: 1 }, impact: { a: 99, b: null } }],
            rowCount: 1,
            truncated: false,
        },
        value_break: {
            // the duplicate also sums active_flag to 2 on B — the same key is a value break too
            rows: [
                {
                    key: { msisdn: 'm9' },
                    a: { active_flag: 1 },
                    b: { active_flag: 2 },
                    impact: { a: 50, b: 100 },
                },
            ],
            rowCount: 1,
            truncated: false,
        },
        cardinality_break: {
            rows: [
                {
                    key: { msisdn: 'm9' },
                    a: { active_flag: 1, __records: 1 },
                    b: { active_flag: 2, __records: 2 },
                    impact: { a: 50, b: 100 },
                },
            ],
            rowCount: 1,
            truncated: false,
        },
    };
    const sar = (v: number) => formatNumber(v, { style: 'currency', currency: 'SAR' });
    const el = (f: { nativeElement: unknown }) => f.nativeElement as HTMLElement;

    it('maps the cardinality set into its own table with the records on each side', async () => {
        const { fixture, c } = await create({ sets, patch: SUBS });
        expect(c.duplicates().map((b) => b.key)).toEqual(['m9']);
        expect(c.recordsText(c.duplicates()[0])).toBe('A 1 · B 2');
        expect(c.duplicatedSideText(c.duplicates()[0])).toBe('B — cbs_subscribers');
        expect(c.duplicateColumns().map((d) => d.headerName)).toEqual([
            'Key',
            'Repeated on',
            'Records',
            'Impact (SAR)',
            'Age',
            'Seen',
            'Assignee',
            'Status',
        ]);
        const section = el(fixture).querySelector('[data-testid="duplicate-keys"]');
        expect(section, 'the Duplicate keys table renders').not.toBeNull();
        expect(section!.textContent).toContain('Duplicate keys — one_to_one (1)');
        expect(el(fixture).querySelector('[data-testid="duplicates-card"]')?.textContent).toContain('1');
    });

    it('prices a duplicate by its extra copies, separately from the value break at the same key', async () => {
        const { c } = await create({ sets, patch: SUBS });
        // B carries 100 summed over 2 records → one extra copy worth 50; the value break keeps the anchor's fee.
        expect(c.impactText(c.duplicates()[0])).toBe(sar(50));
        expect(c.impactText(c.valueBreaks()[0])).toBe(sar(50));
        expect(c.impactText(c.missingA()[0])).toBe(sar(99));
    });

    it('offers Promote to Incident and the rows behind the key on a duplicate', async () => {
        const { c, promote } = await create({ sets, patch: SUBS });
        const dup = c.duplicates()[0];
        const hints = c.duplicateActions.filter((a) => !a.visible || a.visible(dup)).map((a) => a.hint);
        const text = hints.map((h) => (typeof h === 'function' ? h(dup) : h));
        expect(text).toContain('Promote to Incident');
        expect(text).toContain('Show the rows behind this break');
        await c.promote(dup);
        expect(promote).toHaveBeenCalledWith('ra_c01', 'm9', 'cardinality_break', null, null);
    });

    it('selecting a duplicate opens the rows behind it', async () => {
        const rows = vi.fn(async () => ROWS);
        const { fixture, c } = await create({ sets, patch: SUBS, rows });
        c.select(c.duplicates()[0] as unknown as Record<string, unknown>);
        await vi.waitFor(() => expect(c.breakRows()).not.toBeNull());
        fixture.detectChanges();
        expect(rows).toHaveBeenCalledWith(expect.anything(), { msisdn: 'm9' }, 'b');
        expect(el(fixture).querySelector('[data-testid="break-rows"]')?.textContent).toContain('2 on');
        expect(el(fixture).querySelector('[data-testid="selected-break"]')?.textContent).toContain('duplicate key');
    });

    it('hides the table and the card when the Reconciliation declares no cardinality', async () => {
        const { fixture, c } = await create({ sets, patch: { ...SUBS, raw: { cardinality: 'many_to_many' } } });
        expect(c.cardinality()).toBeNull();
        expect(c.duplicates()).toEqual([]);
        expect(el(fixture).querySelector('[data-testid="duplicate-keys"]')).toBeNull();
        expect(el(fixture).querySelector('[data-testid="duplicates-card"]')).toBeNull();
    });

    it('renders the Duplicate keys table with no a11y violations', async () => {
        const { fixture } = await create({ sets, patch: SUBS });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('ReconciliationDetailComponent — readable side names (R2-16)', () => {
    const DATASETS: Partial<Dataset>[] = [
        { id: 'mediation_daily', name: 'mediation_daily', description: 'Mediation daily extract' },
        { id: 'billing_daily', name: 'Billing daily' },
    ];
    const squash = (el: HTMLElement) => (el.textContent ?? '').replace(/\s+/g, ' ');
    const onlyInA = (el: HTMLElement) => el.querySelector('section[aria-label="Only in A"] h2') as HTMLElement;

    it('names the sides by their Dataset in the subtitle, the Field diff and the record-set headings', async () => {
        const { fixture, c } = await create({ datasets: DATASETS });
        const el = fixture.nativeElement as HTMLElement;
        expect(squash(el)).toContain('Breaks · Mediation vs Billing · A: Mediation daily extract vs B: Billing daily');
        const diff = c.valueColumns().find((d) => d.colId === 'fieldDiff')!;
        expect(diff.headerName).toBe('Field diff (Mediation daily extract → Billing daily)');
        expect(diff.headerTooltip).toBe('A = mediation_daily → B = billing_daily');
        expect(onlyInA(el).textContent?.trim()).toBe('Only in A — Mediation daily extract (1)');
        expect(onlyInA(el).title).toBe('A = mediation_daily');
        expect(c.treeColumns().map((t) => t.headerName)).toContain('Billing daily');
    });

    it('falls back to the Dataset id when the Dataset is not found', async () => {
        const { fixture, c } = await create();
        const el = fixture.nativeElement as HTMLElement;
        expect(squash(el)).toContain('Breaks · Mediation vs Billing · A: mediation_daily vs B: billing_daily');
        expect(onlyInA(el).textContent?.trim()).toBe('Only in A — mediation_daily (1)');
        expect(c.valueColumns().find((d) => d.colId === 'fieldDiff')?.headerName).toBe(
            'Field diff (mediation_daily → billing_daily)',
        );
    });

    it('titles through reconciliationTitle — a blank description falls back to the name', async () => {
        const { c } = await create({ patch: { description: '   ' } });
        expect(c.title()).toBe('Mediation vs Billing');
    });

    it('renders the named sides with no a11y violations', async () => {
        const { fixture } = await create({ datasets: DATASETS });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
