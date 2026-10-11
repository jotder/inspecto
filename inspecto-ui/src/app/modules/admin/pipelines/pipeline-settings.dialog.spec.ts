import { ChangeDetectorRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { PipelineSettingsDialog, PipelineSettingsData } from './pipeline-settings.dialog';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';

function make(data: Partial<PipelineSettingsData> = {}) {
    const ref = { close: vi.fn(), disableClose: false };
    TestBed.configureTestingModule({
        imports: [PipelineSettingsDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn().mockResolvedValue(true) } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: { id: 'orders', settings: { produces: 'stream', reference: null }, ...data },
            },
        ],
    });
    const fixture = TestBed.createComponent(PipelineSettingsDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('PipelineSettingsDialog', () => {
    it('closes with reference: null when produces stays stream', () => {
        const { c, ref } = make();
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ produces: 'stream', reference: null, description: '' });
    });

    it('seeds the description from the served settings and sends it trimmed', () => {
        const { c, ref } = make({ settings: { produces: 'stream', reference: null, description: 'Daily orders' } });
        expect(c.form.controls.description.value).toBe('Daily orders');
        c.form.controls.description.setValue('  Retail orders (EU)  ');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({
            produces: 'stream',
            reference: null,
            description: 'Retail orders (EU)',
        });
    });

    /** The POST contract: an empty/blank description CLEARS the stored one — so blank is still SENT. */
    it('sends a blank description as the empty string, which clears it server-side', () => {
        const { c, ref } = make({ settings: { produces: 'stream', reference: null, description: 'old' } });
        c.form.controls.description.setValue('   ');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ produces: 'stream', reference: null, description: '' });
    });

    it('seeds the form from an already-saved reference block', () => {
        const { c } = make({ settings: { produces: 'reference', reference: { load: 'scd2', key: ['msisdn'] } } });
        expect(c.form.controls.produces.value).toBe('reference');
        expect(c.form.controls.load.value).toBe('scd2');
        expect(c.form.controls.key.value).toBe('msisdn');
    });

    it('splits the key field on commas and trims each column', () => {
        const { c, ref } = make();
        c.form.controls.produces.setValue('reference');
        c.form.controls.load.setValue('upsert');
        c.form.controls.key.setValue(' msisdn , event_date ');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({
            produces: 'reference',
            reference: { load: 'upsert', key: ['msisdn', 'event_date'], refresh_seconds: 0 },
            description: '',
        });
    });

    it('round-trips a stored delete/order_by block and keeps reference keys it does not model', () => {
        const { c, ref } = make({
            settings: {
                produces: 'reference',
                reference: {
                    load: 'upsert',
                    key: ['id'],
                    order_by: 'updated_at',
                    delete: { column: 'op', values: ['D'] },
                    future_key: 7,
                } as never,
            },
        });
        c.save();
        expect(ref.close).toHaveBeenCalledWith({
            produces: 'reference',
            reference: {
                load: 'upsert',
                key: ['id'],
                refresh_seconds: 0,
                order_by: 'updated_at',
                delete: { column: 'op', values: ['D'] },
                future_key: 7,
            },
            description: '',
        });
    });

    it('seeds the order by and delete fields, rendering non-string markers as text', () => {
        const { c, fixture } = make({
            settings: {
                produces: 'reference',
                reference: {
                    load: 'scd2',
                    key: ['id'],
                    order_by: 'seq',
                    delete: { column: 'deleted', values: [true, 'Y'] as unknown as string[] },
                },
            },
        });
        expect(c.form.controls.orderBy.value).toBe('seq');
        expect(c.form.controls.deleteColumn.value).toBe('deleted');
        expect(c.form.controls.deleteValues.value).toBe('true, Y');
        const labels = Array.from(fixture.nativeElement.querySelectorAll('mat-label')).map((l) =>
            (l as HTMLElement).textContent?.trim(),
        );
        expect(labels).toEqual(expect.arrayContaining(['Order by column', 'Delete marker column', 'Delete values']));
    });

    it('writes edited order by and delete fields, splitting the values on commas', () => {
        const { c, ref } = make({ settings: { produces: 'reference', reference: { load: 'upsert', key: ['id'] } } });
        c.form.controls.orderBy.setValue(' updated_at ');
        c.form.controls.deleteColumn.setValue(' op ');
        c.form.controls.deleteValues.setValue(' D , DELETE ,');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({
            produces: 'reference',
            reference: {
                load: 'upsert',
                key: ['id'],
                refresh_seconds: 0,
                order_by: 'updated_at',
                delete: { column: 'op', values: ['D', 'DELETE'] },
            },
            description: '',
        });
    });

    it('removes delete and order_by when their fields are cleared', () => {
        const { c, ref } = make({
            settings: {
                produces: 'reference',
                reference: { load: 'upsert', key: ['id'], order_by: 'seq', delete: { column: 'op', values: ['D'] } },
            },
        });
        c.form.controls.orderBy.setValue('');
        c.form.controls.deleteColumn.setValue('  ');
        c.form.controls.deleteValues.setValue('');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({
            produces: 'reference',
            reference: { load: 'upsert', key: ['id'], refresh_seconds: 0 },
            description: '',
        });
    });

    /** The validator refuses delete/order_by on replace (`reference-delete-order-by-require-versioned-load`). */
    it('drops delete and order_by when the load mode is switched to replace', () => {
        const { c, ref } = make({
            settings: {
                produces: 'reference',
                reference: { load: 'upsert', key: ['id'], order_by: 'seq', delete: { column: 'op', values: ['D'] } },
            },
        });
        c.form.controls.load.setValue('replace');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({
            produces: 'reference',
            reference: { load: 'replace', key: ['id'], refresh_seconds: 0 },
            description: '',
        });
    });

    it('refuses a delete column with no values and shows the error only after save', () => {
        const { c, ref, fixture } = make({
            settings: { produces: 'reference', reference: { load: 'upsert', key: ['id'] } },
        });
        c.form.controls.deleteColumn.setValue('op');
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('mat-error')).toBeNull();
        c.save();
        fixture.debugElement.injector.get(ChangeDetectorRef).markForCheck();
        fixture.detectChanges();
        expect(ref.close).not.toHaveBeenCalled();
        expect(c.form.controls.deleteValues.hasError('required')).toBe(true);
        const err = fixture.nativeElement.querySelector('mat-error') as HTMLElement;
        expect(err?.textContent).toContain('Give at least one marker value');
        expect(err.closest('.mat-mdc-form-field-subscript-wrapper')).not.toBeNull();
    });

    it('refuses delete values with no column', () => {
        const { c, ref } = make({ settings: { produces: 'reference', reference: { load: 'upsert', key: ['id'] } } });
        c.form.controls.deleteValues.setValue('D');
        c.save();
        expect(c.form.controls.deleteColumn.hasError('required')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    /** `reference-delete-column-not-a-key`: the marker is dropped before the key is hashed. */
    it('refuses a delete column that is also a key column', () => {
        const { c, ref } = make({
            settings: { produces: 'reference', reference: { load: 'upsert', key: ['id', 'op'] } },
        });
        c.form.controls.deleteColumn.setValue('op');
        c.form.controls.deleteValues.setValue('D');
        c.save();
        expect(c.form.controls.deleteColumn.hasError('isKey')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    /** `reference-delete-without-order-by` is a WARNING: shown, but the save still goes through. */
    it('warns about a delete without an order by column but still saves', () => {
        const { c, ref, fixture } = make({
            settings: { produces: 'reference', reference: { load: 'upsert', key: ['id'] } },
        });
        expect(fixture.nativeElement.textContent).not.toContain('resolve arbitrarily');
        c.form.controls.deleteColumn.setValue('op');
        c.form.controls.deleteValues.setValue('D');
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('resolve arbitrarily');
        c.form.controls.orderBy.setValue('seq');
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).not.toContain('resolve arbitrarily');
        c.form.controls.orderBy.setValue('');
        c.save();
        expect(ref.close).toHaveBeenCalled();
    });

    it('refuses upsert/scd2 with no key column', () => {
        const { c, ref } = make();
        c.form.controls.produces.setValue('reference');
        c.form.controls.load.setValue('upsert');
        c.form.controls.key.setValue('   ');
        c.save();
        expect(c.form.controls.key.hasError('required')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('allows replace with no key column', () => {
        const { c, ref } = make();
        c.form.controls.produces.setValue('reference');
        c.form.controls.load.setValue('replace');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({
            produces: 'reference',
            reference: { load: 'replace', key: [], refresh_seconds: 0 },
            description: '',
        });
    });

    it('renders accessibly', async () => {
        const { fixture } = make();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders the Reference fields accessibly, warning included', async () => {
        const { fixture } = make({
            settings: {
                produces: 'reference',
                reference: { load: 'upsert', key: ['id'], delete: { column: 'op', values: ['D'] } },
            },
        });
        expect(fixture.nativeElement.textContent).toContain('resolve arbitrarily');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
