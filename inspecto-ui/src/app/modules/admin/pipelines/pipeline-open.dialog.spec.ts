import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { PipelineSummary } from 'app/inspecto/api';
import { StreamTransferService } from 'app/inspecto/transfer/stream-transfer.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineOpenDialog, PipelineOpenData } from './pipeline-open.dialog';

const ROW = (name: string): PipelineSummary =>
    ({ name, active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] }) as unknown as PipelineSummary;

describe('PipelineOpenDialog', () => {
    function make(data: Partial<PipelineOpenData> = {}) {
        const ref = { close: vi.fn() };
        const transfer = {
            exportPipeline: vi.fn().mockReturnValue(of({ bundle: { source: { name: 'a' } }, missing: [] })),
            download: vi.fn(),
        };
        const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };
        TestBed.configureTestingModule({
            imports: [PipelineOpenDialog],
            providers: [
                provideNoopAnimations(),
                provideRouter([]),
                { provide: MatDialogRef, useValue: ref },
                { provide: StreamTransferService, useValue: transfer },
                { provide: ToastrService, useValue: toast },
                {
                    provide: MAT_DIALOG_DATA,
                    useValue: { pipelines: [ROW('a'), ROW('b')], open: ['b'], ...data },
                },
            ],
        });
        const fixture = TestBed.createComponent(PipelineOpenDialog);
        fixture.detectChanges();
        return { fixture, c: fixture.componentInstance, ref, transfer, toast };
    }

    /** Item 1: the footer create — the dialog CLOSES first, then navigates to Catalog onboarding
     *  (⛔ a feature may not import the other feature's create dialog). */
    it('New pipeline… closes the dialog, then navigates to the onboarding entry', () => {
        const { c, ref } = make();
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

        c.newPipeline();

        expect(ref.close).toHaveBeenCalled();
        expect(nav).toHaveBeenCalledWith(['/catalog'], { queryParams: { onboard: 'stream' } });
        // close carried NO result — the editor's afterClosed must treat this as a cancel.
        expect(ref.close).toHaveBeenCalledWith();
    });

    /** Item 4: the per-row export — a READ of the saved server config, no tab needed. */
    it('exports a row through the shared by-name seam without touching the open set', () => {
        const { c, transfer, toast } = make();

        c.exportRow(ROW('a'), new Event('click'));

        expect(transfer.exportPipeline).toHaveBeenCalledWith('a');
        expect(transfer.download).toHaveBeenCalled();
        expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('a'));
        expect(c.picked().has('a')).toBe(false); // the click must not tick the row's checkbox
    });

    /** A row that IS open with unsaved edits keeps the editor's refusal — the export would quietly
     *  disagree with that tab's screen. A closed row has no edits to disagree with, so no gate. */
    it('refuses a dirty OPEN row with the same toast pattern the editor uses', () => {
        const { c, transfer, toast } = make({ dirty: ['b'] });

        c.exportRow(ROW('b'), new Event('click'));

        expect(transfer.exportPipeline).not.toHaveBeenCalled();
        expect(toast.warning).toHaveBeenCalled();
    });

    it('names an unreadable satellite and surfaces an export error as a toast', () => {
        const { c, transfer, toast } = make();
        transfer.exportPipeline.mockReturnValue(of({ bundle: {}, missing: ['schema "a_schema"'] }));
        c.exportRow(ROW('a'), new Event('click'));
        expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('a_schema'));

        transfer.exportPipeline.mockReturnValue(throwError(() => ({ status: 500 })));
        c.exportRow(ROW('a'), new Event('click'));
        expect(toast.error).toHaveBeenCalled();
        expect(c.exporting().has('a')).toBe(false); // in-flight marker cleared on error
    });

    it('confirm returns the full desired open set in listed order', () => {
        const { c, ref } = make();
        c.toggle('a');
        c.confirm();
        expect(ref.close).toHaveBeenCalledWith(['a', 'b']);
    });

    it('renders accessibly', async () => {
        const { fixture } = make();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
