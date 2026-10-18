import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ReconApiService } from 'app/inspecto/api';
import { ReconRunResult } from 'app/inspecto/reconciliation';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from '../studio/datasets/dataset-types';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { ReconciliationFormDialog, ReconciliationFormResult } from './reconciliation-form.dialog';

const DS = (id: string): Dataset => ({
    id,
    name: id,
    kind: 'physical',
    sourceName: id,
    physicalRef: id,
    columns: [
        { name: 'id', type: 'number', role: 'dimension' },
        { name: 'cost_usd', type: 'number', role: 'measure' },
    ],
    measures: [],
    calculated: [],
});

const COLS = (extra: { name: string; type: string; numeric: boolean }[] = []) => [
    { name: 'id', type: 'BIGINT', numeric: true },
    { name: 'cost_usd', type: 'DOUBLE', numeric: true },
    ...extra,
];

/** `/recon/columns` as the server inventories it: the right side lacks `region` and has a text `cost_usd`. */
const inventory = () => ({
    datasets: [
        { dataset: 'switch_cdr', columns: COLS([{ name: 'region', type: 'VARCHAR', numeric: false }]) },
        {
            dataset: 'billing_cdr',
            columns: [
                { name: 'id', type: 'BIGINT', numeric: true },
                { name: 'cost_usd', type: 'VARCHAR', numeric: false },
            ],
        },
    ],
    matches: [],
});

const RUN = {
    summary: { groups: 40, matchedKeys: 37, byType: { missing_left: 1, missing_right: 2, value_break: 0 } },
    statistics: { rowCount: 1, elapsedMs: 12, truncated: false },
    sample: { sampled: true, size: 1000, keys: 40, totalKeys: 40 },
} as unknown as ReconRunResult;

function create(api: Partial<ReconApiService> = {}) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ReconciliationFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: DatasetsService, useValue: { list: () => of([DS('switch_cdr'), DS('billing_cdr')]) } },
            { provide: ReconApiService, useValue: { columns: () => of(inventory()), run: () => of(RUN), ...api } },
        ],
    });
    const fixture = TestBed.createComponent(ReconciliationFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('ReconciliationFormDialog', () => {
    it('is invalid until name + both datasets + a key column are chosen, then emits the config', () => {
        const clean = {
            datasets: ['switch_cdr', 'billing_cdr'].map((dataset) => ({ dataset, columns: COLS() })),
            matches: [],
        };
        const { c, ref } = create({ columns: () => of(clean) });
        expect(c.form.valid).toBe(false);
        c.form.patchValue({ name: 'switch vs billing', leftDataset: 'switch_cdr', rightDataset: 'billing_cdr' });
        c.onLeftChange('switch_cdr'); // populates the column pickers
        c.form.patchValue({ keyColumns: ['id'] });
        c.addCompare();
        c.compareRowsArray.at(0).patchValue({ column: 'cost_usd', toleranceType: 'absolute', tolerance: 0.02 });
        expect(c.form.valid).toBe(true);
        c.submit();
        const result = ref.close.mock.calls[0][0] as ReconciliationFormResult;
        expect(result).toEqual({
            name: 'switch vs billing',
            leftDataset: 'switch_cdr',
            rightDataset: 'billing_cdr',
            keyColumns: ['id'],
            compareColumns: [{ column: 'cost_usd', agg: 'sum', toleranceType: 'absolute', tolerance: 0.02 }],
            bands: { warnPct: 1, breachPct: 2 },
        });
    });

    it('flags the breach threshold when it drops below the warn threshold', () => {
        const { c } = create();
        c.form.patchValue({ warnPct: 5, breachPct: 2 });
        expect(c.form.controls['breachPct'].hasError('belowWarn')).toBe(true);
        c.form.patchValue({ breachPct: 10 });
        expect(c.form.controls['breachPct'].hasError('belowWarn')).toBe(false);
    });

    it('prefills from an existing reconciliation in edit mode and keeps its bands', () => {
        const ref = { close: vi.fn() };
        TestBed.configureTestingModule({
            imports: [ReconciliationFormDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MatDialogRef, useValue: ref },
                { provide: DatasetsService, useValue: { list: () => of([DS('switch_cdr'), DS('billing_cdr')]) } },
                { provide: ReconApiService, useValue: { columns: () => of({ datasets: [], matches: [] }) } },
                {
                    provide: MAT_DIALOG_DATA,
                    useValue: {
                        recon: {
                            id: 'r1',
                            name: 'edit me',
                            leftDataset: 'switch_cdr',
                            rightDataset: 'billing_cdr',
                            keyColumns: ['id'],
                            compareColumns: [
                                { column: 'cost_usd', agg: 'count', toleranceType: 'percent', tolerance: 0.5 },
                            ],
                            bands: { warnPct: 0.5, breachPct: 3 },
                        },
                    },
                },
            ],
        });
        const fixture = TestBed.createComponent(ReconciliationFormDialog);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.editing).toBe(true);
        expect(c.form.controls['name'].value).toBe('edit me');
        expect(c.form.controls['keyColumns'].value).toEqual(['id']);
        expect(c.compareRowsArray.at(0).get('agg')?.value).toBe('count');
        expect(c.form.controls['warnPct'].value).toBe(0.5);
        expect(c.form.controls['breachPct'].value).toBe(3);
        expect(c.form.valid).toBe(true);
    });

    it('offers the left dataset columns as key/compare options after selection', () => {
        const { c } = create();
        c.onLeftChange('switch_cdr');
        expect(c.leftColumns()).toEqual(['id', 'cost_usd']);
    });

    function pickSides(c: ReconciliationFormDialog) {
        c.form.patchValue({ name: 'n', leftDataset: 'switch_cdr', rightDataset: 'billing_cdr' });
    }

    it('offers the columns of every side and shows per-side availability', () => {
        const { c, fixture } = create();
        pickSides(c);
        fixture.detectChanges();
        expect(c.columnOptions()).toEqual(['id', 'cost_usd', 'region']);
        expect(c.sideSummary().map((s) => s.text)).toEqual(['3 columns (2 numeric)', '2 columns (1 numeric)']);
        expect(fixture.nativeElement.textContent).toContain('Right 2 columns (1 numeric)');
    });

    it('flags a key missing on a side, and blocks Save', () => {
        const { c, ref, fixture } = create();
        pickSides(c);
        c.form.patchValue({ keyColumns: ['region'] });
        fixture.detectChanges();
        expect(c.keyIssues()).toEqual(['Key "region" is not a column of Right (billing_cdr).']);
        expect(fixture.nativeElement.querySelector('p[role="alert"]')?.textContent).toContain('region');
        const save = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
        expect(save.disabled).toBe(true);
        c.submit();
        expect(ref.close).not.toHaveBeenCalled();
        c.form.patchValue({ keyColumns: ['id'] });
        expect(c.hasIssues()).toBe(false);
    });

    it('flags a sum over a column that is not numeric on a side, but allows count', () => {
        const { c, fixture } = create();
        pickSides(c);
        c.form.patchValue({ keyColumns: ['id'] });
        c.addCompare();
        c.compareRowsArray.at(0).patchValue({ column: 'cost_usd', agg: 'sum' });
        fixture.detectChanges();
        expect(c.compareIssues()[0][0]).toContain('not numeric on Right (billing_cdr)');
        expect(c.hasIssues()).toBe(true);
        c.compareRowsArray.at(0).patchValue({ agg: 'count' });
        expect(c.hasIssues()).toBe(false);
    });

    it('does not block when the columns of a side cannot be read', () => {
        const { c, fixture } = create({ columns: () => throwError(() => new Error('503')) });
        pickSides(c);
        c.form.patchValue({ keyColumns: ['id'] });
        fixture.detectChanges();
        expect(c.sideSummary()[0].text).toContain('not checked');
        expect(c.hasIssues()).toBe(false);
    });

    it('Test definition runs /recon/run on a small sample and shows Breaks by type', () => {
        const run = vi.fn(() => of(RUN));
        const { c, fixture } = create({ run });
        pickSides(c);
        c.form.patchValue({ keyColumns: ['id'] });
        c.addCompare();
        c.compareRowsArray.at(0).patchValue({ column: 'id', agg: 'sum' });
        fixture.detectChanges();
        const btn = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
            (b) => b.textContent?.includes('Test definition'),
        )!;
        expect(btn.disabled).toBe(false);
        btn.click();
        fixture.detectChanges();
        const [config, query] = run.mock.calls[0] as unknown as [{ datasets: string[]; keyColumns: string[] }, object];
        expect(config.datasets).toEqual(['switch_cdr', 'billing_cdr']);
        expect(config.keyColumns).toEqual(['id']);
        expect(query).toMatchObject({ sample: 1000, limit: 1, offset: 0 });
        const text = fixture.nativeElement.querySelector('[role="status"]')?.textContent ?? '';
        expect(text).toContain('40 key groups compared');
        expect(text).toContain('Missing on right: 2');
        expect(text).toContain('Value: 0');
    });

    it('Test definition shows the error with a fix hint', () => {
        const { c, fixture } = create({ run: () => throwError(() => new Error('boom')) });
        pickSides(c);
        c.form.patchValue({ keyColumns: ['id'] });
        c.testDefinition();
        fixture.detectChanges();
        expect(c.testResult()).toBeNull();
        expect(fixture.nativeElement.querySelector('inspecto-alert')?.textContent).toContain('exists on each dataset');
    });

    it('an edit during Test definition discards the old answer and re-enables the button', () => {
        const pending = new Subject<ReconRunResult>();
        const { c, fixture } = create({ run: () => pending });
        pickSides(c);
        c.form.patchValue({ keyColumns: ['id'] });
        c.testDefinition();
        expect(c.testing()).toBe(true);
        c.form.patchValue({ name: 'edited while testing' });
        expect(c.testing()).toBe(false);
        pending.next(RUN);
        fixture.detectChanges();
        expect(c.testResult()).toBeNull();
        expect(c.testing()).toBe(false);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
