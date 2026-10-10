import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AnomalyModelsService } from 'app/inspecto/api/anomaly-models.service';
import { ComponentDef } from 'app/inspecto/api/components.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { AnomalyModelFormDialog, heldAnomalyChange } from './anomaly-model-form.dialog';

const EXISTING: ComponentDef = {
    type: 'anomaly-model',
    name: 'usage',
    ref: 'anomaly-model/usage',
    contentHash: 'abc123',
    content: {
        id: 'usage',
        owner: 'ana',
        entityType: 'subscriber',
        window: 28,
        seasonality: 'none',
        elevatedThreshold: 60,
        highThreshold: 80,
        features: [{ id: 'data_mb', dataset: 'cdr', key: 'msisdn', time: 'event_time', measure: 'sum(mb)', weight: 1 }],
    },
};

const flush = async () => {
    for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r, 0));
};

function setup(existing?: ComponentDef, write?: () => unknown) {
    const close = vi.fn();
    const result = write ?? (() => of({ ...EXISTING }));
    const create = vi.fn(result);
    const update = vi.fn(result);
    TestBed.configureTestingModule({
        imports: [AnomalyModelFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { existing } },
            {
                provide: MatDialogRef,
                useValue: {
                    close,
                    disableClose: false,
                    backdropClick: () => new Subject(),
                    keydownEvents: () => new Subject(),
                },
            },
            { provide: AnomalyModelsService, useValue: { create, update } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => true) } },
            {
                provide: DatasetsService,
                useValue: {
                    list: () => of([{ id: 'cdr' }, { id: 'no_schema' }]),
                    get: (id: string) => of({ id, sourceName: id }),
                },
            },
            {
                provide: DatasetRowsService,
                useValue: {
                    columns: async (ds: { id: string }) =>
                        ds.id === 'cdr'
                            ? [
                                  { name: 'msisdn', type: 'string' },
                                  { name: 'event_time', type: 'timestamp' },
                                  { name: 'mb', type: 'number' },
                              ]
                            : [],
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(AnomalyModelFormDialog);
    fixture.detectChanges();
    return { fixture, cmp: fixture.componentInstance, close, create, update };
}

describe('AnomalyModelFormDialog', () => {
    it('offers only Datasets with a readable Schema, and only time columns for Time', async () => {
        const { fixture, cmp } = setup(EXISTING);
        await flush();
        fixture.detectChanges();
        expect(cmp.datasets().map((o) => o.value)).toEqual(['cdr']);
        expect(cmp.featureEditor!.timeOptions(0).map((o) => o.value)).toEqual(['event_time']);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('saves an edit with If-Match, keeping the envelope keys', async () => {
        const { fixture, cmp, update, close } = setup(EXISTING);
        await flush();
        fixture.detectChanges();
        cmp.save();
        expect(update).toHaveBeenCalledWith(
            'usage',
            expect.objectContaining({ id: 'usage', owner: 'ana', window: 28, highThreshold: 80 }),
            'abc123',
        );
        expect(close).toHaveBeenCalledWith({ saved: expect.objectContaining({ name: 'usage' }) });
    });

    it('refuses a malformed Measure before calling the server', async () => {
        const { fixture, cmp, update } = setup(EXISTING);
        await flush();
        fixture.detectChanges();
        cmp.featureEditor!.rows.at(0).controls['measure'].setValue('total(mb)');
        cmp.save();
        fixture.detectChanges();
        expect(update).not.toHaveBeenCalled();
        expect(fixture.nativeElement.querySelector('mat-error')?.textContent).toContain('is not an aggregate');
    });

    it('lands a 422 on the Feature row it names, verbatim', async () => {
        const msg = "anomaly-model.features[0].time 'event_time' is not a TIMESTAMP or DATE column";
        const { fixture, cmp, close } = setup(EXISTING, () =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 422,
                        error: { error: { code: 'CONFIG_VALIDATION_FAILED', message: msg } },
                    }),
            ),
        );
        await flush();
        fixture.detectChanges();
        cmp.save();
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        const rowError = fixture.nativeElement.querySelector('[data-testid="feature-error"]');
        expect(rowError?.textContent).toContain('is not a TIMESTAMP or DATE column');
        expect(fixture.nativeElement.querySelector('inspecto-alert')?.textContent).toContain('Not saved');
    });

    it('reads a maker-checker hold as held, not saved', () => {
        expect(heldAnomalyChange({ status: 'pending', pendingChange: { id: 'pc-1' } })).toEqual({ id: 'pc-1' });
        expect(heldAnomalyChange({ name: 'usage' })).toBeNull();
    });
});
