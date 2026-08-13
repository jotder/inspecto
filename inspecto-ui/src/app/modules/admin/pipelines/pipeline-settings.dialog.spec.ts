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
        expect(ref.close).toHaveBeenCalledWith({ produces: 'stream', reference: null });
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
        });
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
        });
    });

    it('renders accessibly', async () => {
        const { fixture } = make();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
