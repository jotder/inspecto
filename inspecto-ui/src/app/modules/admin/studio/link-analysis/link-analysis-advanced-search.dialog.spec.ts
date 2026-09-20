import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { of } from 'rxjs';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from '../datasets/dataset-types';
import { LinkAnalysisAdvancedSearchDialog } from './link-analysis-advanced-search.dialog';

const DS: Dataset = {
    id: 'tx',
    name: 'Transactions',
    kind: 'physical',
    sourceName: 'transactions',
    columns: [],
    measures: [],
    calculated: [],
};
const SAMPLE = [
    { payer_id: 'A', payee_id: 'B', amount: 500 },
    { payer_id: 'A', payee_id: 'B', amount: 700 },
    { payer_id: 'B', payee_id: 'C', amount: 20000 },
];

async function create() {
    const rows = vi.fn(async () => ({ rows: SAMPLE, columns: [], truncated: false }));
    const sql = vi.fn(async () => ({ rows: SAMPLE.slice(2), columns: [], truncated: true }));
    const close = vi.fn();
    TestBed.configureTestingModule({
        imports: [LinkAnalysisAdvancedSearchDialog],
        providers: [
            provideNoopAnimations(),
            { provide: DatasetRowsService, useValue: { rows, sql } },
            { provide: MatDialogRef, useValue: { close } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: {
                    dataset: DS,
                    projection: { datasetId: 'tx', sourceCol: 'payer_id', targetCol: 'payee_id' },
                },
            },
        ],
    });
    await TestBed.compileComponents(); // the pro tier @defer-loads the SQL editor
    const fixture = TestBed.createComponent(LinkAnalysisAdvancedSearchDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, rows, sql, close };
}

describe('LinkAnalysisAdvancedSearchDialog', () => {
    it('seeds the table from a Dataset sample, runs server SQL through the guarded route, and projects the result rows', async () => {
        const { fixture, rows, sql, close } = await create();
        const c = fixture.componentInstance;
        expect(rows).toHaveBeenCalledWith(DS, 500);
        expect(c.rows()).toHaveLength(3);
        expect(c.status()).toBe('3 rows · sample');

        c.runOnServer('SELECT * FROM "transactions" WHERE amount >= 10000');
        await fixture.whenStable();
        expect(sql).toHaveBeenCalledWith('transactions', 'SELECT * FROM "transactions" WHERE amount >= 10000', 20000);
        expect(c.rows()).toHaveLength(1);
        expect(c.status()).toBe('1 rows · server result · truncated');

        c.project(); // folds the RESULT relation with the mapping — SQL text never leaves this dialog
        expect(close).toHaveBeenCalledTimes(1);
        const g = close.mock.calls[0][0] as { nodes: { id: string }[]; edges: { data: { count?: number } }[] };
        expect(g.nodes.map((n) => n.id).sort()).toEqual(['entity:B', 'entity:C']);
        expect(g.edges).toHaveLength(1);
        expect(g.edges[0].data.count).toBe(1);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('surfaces a projection error inline instead of closing', async () => {
        const { fixture, close } = await create();
        const c = fixture.componentInstance;
        c.rows.set([{ x: 1 }]);
        c.project();
        expect(close).not.toHaveBeenCalled();
        expect(c.error()).toMatch(/payer_id/);
    });
});
