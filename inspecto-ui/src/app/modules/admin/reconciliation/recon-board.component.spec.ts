import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { EMPTY, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    aggregateRecon,
    Reconciliation,
    ReconciliationsService,
    reconBreakSets,
    ReconRunResult,
} from 'app/inspecto/reconciliation';
import { ReconBoardComponent } from './recon-board.component';
import { ReconExecService } from './recon-exec.service';

const RECON: Reconciliation = {
    id: 'med_vs_bill',
    name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily',
    rightDataset: 'billing_daily',
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
    breaks: [],
    lastRunAt: null,
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

async function create(opts: { patch?: Partial<Reconciliation>; result?: ReconRunResult } = {}) {
    const recon: Reconciliation = { ...RECON, ...opts.patch };
    const navigate = vi.fn();
    const save = vi.fn((r: Reconciliation) => of(r));
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
            {
                provide: ReconExecService,
                useValue: {
                    run: vi.fn(async () => opts.result ?? RESULT),
                    breaks: vi.fn(async () => reconBreakSets(RECON, LEFT, RIGHT)),
                },
            },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
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
    return { fixture, c, navigate, save };
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

    it('a run refreshes the persisted break lifecycle (C9 merge semantics)', async () => {
        const { c, save } = await create();
        expect(save).toHaveBeenCalledTimes(1);
        const persisted = save.mock.calls[0][0] as Reconciliation;
        // MEA/voice only in A + EU/data amount outside 0.5% — both fresh, both open.
        expect(persisted.breaks.map((b) => b.type).sort()).toEqual(['missing_right', 'value_break']);
        expect(persisted.lastRunAt).toBeTruthy();
        expect(c.recon()?.breaks).toHaveLength(2);
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
        expect(el.textContent).toContain('Mediation vs Billing · A mediation_daily');
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
