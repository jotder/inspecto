import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { EMPTY, Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { LensService, ReconApiService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    aggregateRecon,
    Reconciliation,
    ReconciliationsService,
    ReconRunResult,
    ReconState,
} from 'app/inspecto/reconciliation';
import { ReconBoardComponent } from './recon-board.component';
import { ReconExecService } from './recon-exec.service';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { Dataset } from '../studio/datasets/dataset-types';

const RECON: Reconciliation = {
    id: 'med_vs_bill',
    name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily',
    rightDataset: 'billing_daily',
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
};

/** What `POST /recon/{id}/record` answers: the server-merged lifecycle, one Break 100 days old. */
const DAY = 86_400_000;
const RECORDED: ReconState = {
    reconciliation: 'med_vs_bill',
    lastRunAt: '2026-09-26T08:00:00.000Z',
    runs: 4,
    breaks: [
        {
            key: 'MEA · voice',
            type: 'missing_right',
            status: 'open',
            firstSeenAt: new Date(Date.now() - 100 * DAY).toISOString(),
        },
        {
            key: 'EU · data',
            type: 'value_break',
            column: 'amount',
            status: 'resolved',
            firstSeenAt: '2026-09-01T00:00:00Z',
        },
    ],
};

const LEFT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 118 },
    { region: 'MEA', product: 'voice', amount: 10 },
];
const RIGHT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 114 },
];
const RESULT = aggregateRecon(RECON, LEFT, RIGHT);

async function create(
    opts: {
        patch?: Partial<Reconciliation>;
        result?: ReconRunResult;
        datasets?: Partial<Dataset>[];
        record?: () => Observable<ReconState>;
        canOperateRuns?: boolean;
    } = {},
) {
    const recon: Reconciliation = { ...RECON, ...opts.patch };
    const navigate = vi.fn();
    const save = vi.fn((r: Reconciliation) => of(r));
    const record = vi.fn(opts.record ?? (() => of(RECORDED)));
    const state = vi.fn(() => of({ ...RECORDED, runs: 3 }));
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ReconBoardComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: RECON.id }) } } },
            {
                provide: Router,
                useValue: { navigate, createUrlTree: () => ({}), serializeUrl: () => '', events: EMPTY },
            },
            { provide: ReconciliationsService, useValue: { get: () => of(recon), save } },
            { provide: ReconApiService, useValue: { record, state } },
            { provide: LensService, useValue: { canOperateRuns: () => opts.canOperateRuns ?? true } },
            { provide: ReconExecService, useValue: { run: vi.fn(async () => opts.result ?? RESULT) } },
            { provide: DatasetsService, useValue: { list: () => of(opts.datasets ?? []) } },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconBoardComponent);
    fixture.detectChanges(); // ngOnInit — load + auto-run
    const c = fixture.componentInstance;
    // Zoneless CD: run()'s promise chain is pure microtasks, which whenStable() (pending-task
    // based) cannot see — it may resolve before the result lands. Poll for it instead, then
    // commit the rendered board.
    await vi.waitFor(() => expect(c.result()).not.toBeNull());
    fixture.detectChanges();
    return { fixture, c, navigate, save, record, state, toastr };
}

describe('ReconBoardComponent', () => {
    it('loads, auto-runs, and renders the summary + TOTAL strip + board tree', async () => {
        const { fixture, c } = await create();
        expect(c.result()).not.toBeNull();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Mediation vs Billing');
        expect(text).toContain('2 matched');
        expect(text).toContain('only in A: 1');
        expect(text).toContain('value breaks: 1');
        expect(text).toContain('Total');

        // the tree folds region → product, worst-severity first (MEA is structural: only in A)
        expect(c.treeNodes().map((n) => n.label)).toEqual(['MEA', 'EU']);
        expect(c.treeColumns().map((col) => col.field)).toEqual([
            'a_amount',
            'b_amount',
            'pct_b_amount',
            'a___records',
            'b___records',
            'pct_b___records',
        ]);
    });

    /**
     * R2-03: a run is RECORDED server-side (`canOperateRuns`), never saved through the authoring PUT — an
     * operations-only user got a 403 there and no run was ever recorded.
     */
    it('records the run server-side and never saves the config', async () => {
        const { fixture, c, save, record } = await create();
        await vi.waitFor(() => expect(c.state()).not.toBeNull());
        fixture.detectChanges();
        expect(record).toHaveBeenCalledWith('med_vs_bill');
        expect(save).not.toHaveBeenCalled();
        expect(c.state()?.runs).toBe(4);
        // the aging strip reads the RECORDED lifecycle: one open Break, 100 days old (the resolved one is settled)
        expect(c.ageBuckets()).toEqual([{ bucket: '90+', count: 1 }]);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('90+ days');
    });

    it('counts the open Breaks of BOTH pairs of a 3-way Reconciliation in the aging strip', async () => {
        const old = new Date(Date.now() - 100 * DAY).toISOString();
        const recent = new Date(Date.now() - 10 * DAY).toISOString();
        const threeWay: ReconState = {
            ...RECORDED,
            breaks: [
                { pair: 'AB', key: 'MEA · voice', type: 'missing_right', status: 'open', firstSeenAt: old },
                { pair: 'AC', key: 'MEA · voice', type: 'missing_right', status: 'open', firstSeenAt: recent },
                { pair: 'AC', key: 'EU · data', type: 'value_break', column: 'amount', status: 'resolved' },
            ],
        };
        const { c } = await create({ patch: { thirdDataset: 'crm_daily' }, record: () => of(threeWay) });
        await vi.waitFor(() => expect(c.state()).toEqual(threeWay));
        expect(c.ageBuckets()).toEqual([
            { bucket: '0-30', count: 1 },
            { bucket: '90+', count: 1 },
        ]);
    });

    it('toasts a run it could not record and keeps the last recorded lifecycle', async () => {
        const { c, toastr, state } = await create({
            record: () => throwError(() => ({ status: 403, error: { error: { message: 'requires canOperateRuns' } } })),
        });
        await vi.waitFor(() => expect(c.state()).not.toBeNull());
        expect(toastr.error).toHaveBeenCalled();
        expect(state).toHaveBeenCalledWith('med_vs_bill');
        expect(c.state()?.runs).toBe(3);
        expect(c.result()).not.toBeNull(); // the Board itself still renders
    });

    // A manager (no canOperateRuns) opens a Board: the comparison and the recorded lifecycle show, nothing is recorded,
    // and no refusal toast greets them on every open.
    it('a viewer who may not operate runs reads the recorded lifecycle without recording or toasting', async () => {
        const { c, record, state, toastr } = await create({ canOperateRuns: false });
        await vi.waitFor(() => expect(c.state()).not.toBeNull());
        expect(record).not.toHaveBeenCalled();
        expect(state).toHaveBeenCalledWith('med_vs_bill');
        expect(toastr.error).not.toHaveBeenCalled();
        expect(c.result()).not.toBeNull();
    });

    it('the details action navigates to the Breaks page with the encoded path', async () => {
        const { c, navigate } = await create();
        c.rowActions[0].onClick!({
            __id: 'x',
            __depth: 0,
            __hasChildren: false,
            __expanded: false,
            __label: 'EU',
            __path: 'region:EU',
        });
        expect(navigate).toHaveBeenCalledWith(['/reconciliation', RECON.id, 'breaks'], {
            queryParams: { path: 'region:EU' },
        });
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('ReconBoardComponent — title rule and duplicate keys', () => {
    /** RA-C01's shape: 3-way, `one_to_one`, the duplicates on C (CBS) only. */
    const withCardinality = (): ReconRunResult => ({
        ...RESULT,
        summary: {
            ...RESULT.summary,
            byType: { ...RESULT.summary.byType, cardinality_break: 0 },
            pairs: [
                { side: 'b', matchedKeys: 2, byType: { ...RESULT.summary.byType, cardinality_break: 0 } },
                { side: 'c', matchedKeys: 2, byType: { ...RESULT.summary.byType, cardinality_break: 4 } },
            ],
        },
    });

    it('titles the Board with the description and keeps the code in the subtitle', async () => {
        const { fixture, c } = await create({ patch: { description: 'Mediation vs Billing — daily revenue' } });
        const el = fixture.nativeElement as HTMLElement;
        expect(c.title()).toBe('Mediation vs Billing — daily revenue');
        expect(el.querySelector('h1')?.textContent).toContain('Mediation vs Billing — daily revenue');
        expect(el.querySelector('h1')?.textContent).not.toContain('Mediation vs Billing ·');
        expect(el.textContent).toContain('Mediation vs Billing · A: mediation_daily');
    });

    it('falls back to the name, without repeating it in the subtitle', async () => {
        const { fixture, c } = await create();
        expect(c.title()).toBe('Mediation vs Billing');
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Mediation vs Billing · A');
    });

    it('counts duplicate keys per compared side when the server reports them', async () => {
        const { fixture, c } = await create({ result: withCardinality() });
        expect(c.duplicateCounts()).toEqual([
            { label: 'duplicate keys A·B', count: 0 },
            { label: 'duplicate keys A·C', count: 4 },
        ]);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('duplicate keys A·C: 4');
    });

    it('shows no duplicate-key count for a Reconciliation without a cardinality', async () => {
        const { fixture, c } = await create();
        expect(c.duplicateCounts()).toEqual([]);
        expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="board-duplicates"]')).toBeNull();
    });

    it('renders the duplicate-key counts with no a11y violations', async () => {
        const { fixture } = await create({ result: withCardinality(), patch: { description: 'Subscriber status' } });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('ReconBoardComponent — readable side and column names (R2-16)', () => {
    const DATASETS: Partial<Dataset>[] = [
        { id: 'mediation_daily', name: 'mediation_daily', description: 'Mediation daily extract' },
        { id: 'billing_daily', name: 'Billing daily' },
    ];
    const heads = (c: ReconBoardComponent) => c.treeColumns().map((col) => col.headerName);
    const BANDS = ' · tree Region › Product · bands ok < 1% · warn 1–2% · breach > 2%';

    it('names the sides by their Dataset and humanises the columns', async () => {
        const { fixture, c } = await create({ datasets: DATASETS });
        expect(c.subtitle()).toBe('A: Mediation daily extract ⇄ B: Billing daily' + BANDS);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain(
            'A: Mediation daily extract ⇄ B: Billing daily',
        );
        expect(heads(c)).toEqual([
            'Mediation daily extract · Amount',
            'Billing daily · Amount',
            'Δ% Amount',
            'Mediation daily extract · Records',
            'Billing daily · Records',
            'Δ% Records',
        ]);
        // the builder's ids stay one hover away
        expect(c.treeColumns()[0].headerTooltip).toBe('A = mediation_daily · amount');
        expect(c.keyColumnsLabel()).toBe('Region › Product');
    });

    it('falls back to the Dataset id when the Dataset is not found', async () => {
        const { c } = await create();
        expect(c.subtitle()).toBe('A: mediation_daily ⇄ B: billing_daily' + BANDS);
        expect(heads(c)).toEqual([
            'mediation_daily · Amount',
            'billing_daily · Amount',
            'Δ% Amount',
            'mediation_daily · Records',
            'billing_daily · Records',
            'Δ% Records',
        ]);
    });

    it('humanises the TOTAL strip measures', async () => {
        const { c } = await create();
        expect(c.totalLines().map((t) => t.label)).toEqual(['Amount', 'Records']);
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
