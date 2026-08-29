import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AuditRow, CatalogService, RunsService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BatchDetailDialog } from './batch-detail.dialog';

const BATCHES: AuditRow[] = [
    { consignment_id: 'b-1', status: 'SUCCESS', member_count: '2', output_table: 'cdr_output' },
    { consignment_id: 'b-2', status: 'FAILED', member_count: '1', output_table: '' },
];
const FILES: AuditRow[] = [
    { consignment_id: 'b-1', file: 'a.csv', rows: '10' },
    { consignment_id: 'b-1', file: 'b.csv', rows: '20' },
    { consignment_id: 'b-2', file: 'c.csv', rows: '5' },
];
const LINEAGE: AuditRow[] = [{ input_file: 'a.csv', output: 'cdr/part-0.parquet' }];

const OUTPUTS = [
    {
        tableName: 'totals__derived',
        partitionKey: 'grp=even',
        recordDay: null,
        rows: 7,
        bytes: 900,
        state: 'LIVE',
        producer: 'step_a',
        writtenAt: '2026-08-29T00:00:00Z',
        path: '/w/_derived/totals/grp=even/c1.parquet',
    },
];

function create(
    resolved: { id: string; label: string } | null = { id: 'event:cdr/main', label: 'cdr_output' },
    batchId = 'b-1',
    outputs: unknown = { enabled: true, consignmentId: 'b-1', outputs: OUTPUTS },
) {
    const stub = {
        batches: vi.fn(() => of(BATCHES)),
        files: vi.fn(() => of(FILES)),
        lineage: vi.fn(() => of(LINEAGE)),
        consignmentOutputs: vi.fn(() => (outputs === 'error' ? throwError(() => ({ status: 404 })) : of(outputs))),
    };
    const catalog = {
        resolveTable: vi.fn(() => (resolved ? of(resolved) : throwError(() => ({ status: 404 })))),
    };
    TestBed.configureTestingModule({
        imports: [BatchDetailDialog],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: CatalogService, useValue: catalog },
            { provide: MAT_DIALOG_DATA, useValue: { pipeline: 'cdr', batchId } },
            { provide: MatDialogRef, useValue: { close: vi.fn() } },
            { provide: RunsService, useValue: stub },
            InspectoGridThemeService,
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(BatchDetailDialog);
    fixture.detectChanges(); // runs ngOnInit (loads batches/files/lineage)
    return { fixture, stub, catalog };
}

describe('BatchDetailDialog', () => {
    /** The post-sync lane made visible: what a step derived onto this Consignment, and who made it. */
    it('shows the Consignment registered outputs, including a derived table', () => {
        const { fixture, stub } = create();
        const c = fixture.componentInstance;
        expect(stub.consignmentOutputs).toHaveBeenCalledWith('cdr', 'b-1');
        expect(c.batchOutputs().length).toBe(1);
        expect(c.outputsNote()).toBeNull();
        const text = fixture.nativeElement.textContent ?? '';
        expect(text).toContain('Registered outputs (1)');
    });

    /**
     * ⚠ "registry off" and "wrote nothing" must NOT render the same. An empty table with the registry
     * disabled would assert something false about the Consignment.
     */
    it('explains a disabled registry rather than showing an empty table', () => {
        const { fixture } = create(null, 'b-1', { enabled: false, consignmentId: 'b-1', outputs: [] });
        const c = fixture.componentInstance;
        expect(c.outputsNote()).toContain('switched off');
        expect(c.outputsNote()).toContain('not the same');
        expect(fixture.nativeElement.querySelector('inspecto-alert')).not.toBeNull();
    });

    /** An older backend without the route must degrade, not blank a dialog that already has data. */
    it('degrades when the route is absent, keeping the rest of the dialog', () => {
        const { fixture } = create(null, 'b-1', 'error');
        const c = fixture.componentInstance;
        expect(c.outputsNote()).toContain('does not serve');
        expect(c.batchRow()?.['consignment_id']).toBe('b-1');
        expect(c.batchLineage().length).toBe(1);
    });

    it('resolves the summary row, member files and lineage for the batch', () => {
        const { fixture, stub } = create();
        const c = fixture.componentInstance;
        expect(stub.lineage).toHaveBeenCalledWith('cdr', 'b-1');
        expect(c.loading()).toBe(false);
        expect(c.batchRow()?.['consignment_id']).toBe('b-1');
        expect(c.batchFiles().map((f) => f['file'])).toEqual(['a.csv', 'b.csv']);
        expect(c.batchLineage()).toEqual(LINEAGE);
        expect(c.batchSummary.map((kv) => kv.key)).toContain('status');
    });

    it('offers the Catalog jump for the store the batch wrote', () => {
        const { fixture, catalog } = create();
        fixture.detectChanges();
        expect(catalog.resolveTable).toHaveBeenCalledWith('cdr_output');
        expect(fixture.componentInstance.catalogNodeId()).toBe('event:cdr/main');
        expect(fixture.nativeElement.textContent).toContain('View cdr_output in the Catalog');
    });

    it('shows no link when the store cannot be resolved to one catalog node', () => {
        // a 404 means unknown OR ambiguous — either way a link would point somewhere unproven
        const { fixture } = create(null);
        fixture.detectChanges();
        expect(fixture.componentInstance.catalogNodeId()).toBeNull();
        expect(fixture.nativeElement.textContent).not.toContain('in the Catalog');
    });

    it('does not ask the catalog about a batch that wrote no store', () => {
        const { fixture, catalog } = create({ id: 'x', label: 'x' }, 'b-2');
        fixture.detectChanges();
        expect(catalog.resolveTable).not.toHaveBeenCalled();
        expect(fixture.componentInstance.catalogNodeId()).toBeNull();
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
