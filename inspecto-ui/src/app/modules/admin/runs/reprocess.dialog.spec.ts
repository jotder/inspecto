import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit, penError } from 'app/inspecto/testing/pen-field';
import { ReprocessDialog } from './reprocess.dialog';

function create() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ReprocessDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { pipeline: 'orders' } },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(ReprocessDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('ReprocessDialog', () => {
    it('blocks submit on a blank batch id, then closes with the trimmed value', () => {
        const { c, ref } = create();
        c.batchId.setValue('   ');
        c.submit();
        expect(ref.close).not.toHaveBeenCalled();

        c.batchId.setValue('  batch-42 ');
        c.submit();
        expect(ref.close).toHaveBeenCalledWith('batch-42');
    });

    it('Enter in the batch id row reprocesses; an empty row shows why it did not', () => {
        const { fixture, ref } = create();
        const host = penByLabel(fixture.nativeElement, 'Batch id')!;
        expect(penError(host), 'nothing red on a fresh dialog').toBe('');
        penEdit(fixture, host, '');
        expect(ref.close).not.toHaveBeenCalled();
        expect(penError(host)).toBe('Batch id is required.');
        penEdit(fixture, host, 'batch-7');
        expect(ref.close).toHaveBeenCalledWith('batch-7');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
