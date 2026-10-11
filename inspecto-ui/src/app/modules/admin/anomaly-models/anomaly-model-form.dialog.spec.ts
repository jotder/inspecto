import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AnomalyModelsService, AnomalyScorePreview } from 'app/inspecto/api/anomaly-models.service';
import { ComponentDef } from 'app/inspecto/api/components.service';
import { LensService } from 'app/inspecto/api/lens.service';
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

/** A stored model whose Feature carries `filters` and `unit` (values typed as the server stores them). */
const WITH_FILTERS: ComponentDef = {
    ...EXISTING,
    content: {
        ...EXISTING.content,
        features: [
            {
                id: 'data_mb',
                dataset: 'cdr',
                key: 'msisdn',
                time: 'event_time',
                measure: 'sum(mb)',
                weight: 1,
                filters: [{ field: 'mb', op: '>', value: 0 }],
                unit: 5,
            },
        ],
    },
};

const DRAFT_PREVIEW: AnomalyScorePreview = {
    model: 'usage',
    entityType: 'subscriber',
    entityKey: '966501234567',
    found: true,
    periodStart: '2026-10-10',
    score: 12,
    band: 'normal',
    raw: 0.4,
    elevatedThreshold: 60,
    highThreshold: 80,
    insufficientCount: 0,
    saved: false,
    features: [],
};

const flush = async () => {
    for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r, 0));
};

function setup(existing?: ComponentDef, write?: () => unknown, opts: { work?: boolean } = {}) {
    const close = vi.fn();
    const result = write ?? (() => of({ ...EXISTING }));
    const create = vi.fn(result);
    const update = vi.fn(result);
    const preview = vi.fn(() => of(DRAFT_PREVIEW));
    const previewContent = vi.fn(() => of(DRAFT_PREVIEW));
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
            { provide: AnomalyModelsService, useValue: { create, update, preview, previewContent } },
            { provide: LensService, useValue: { canWorkIncidents: signal(opts.work ?? false) } },
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
    return { fixture, cmp: fixture.componentInstance, close, create, update, preview, previewContent };
}

/** Type a key into the draft preview box and submit it. */
function submitPreview(el: HTMLElement, key: string): void {
    const input = el.querySelector('app-anomaly-model-preview input') as HTMLInputElement;
    input.value = key;
    input.dispatchEvent(new Event('input'));
    (el.querySelector('app-anomaly-model-preview form') as HTMLFormElement).dispatchEvent(new Event('submit'));
}

const savedFeature = (update: ReturnType<typeof vi.fn>, call = 0): Record<string, unknown> =>
    ((update.mock.calls[call][1] as Record<string, unknown>)['features'] as Record<string, unknown>[])[0];

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

    it('shows a stored Feature’s filters and unit, and saves them back unchanged', async () => {
        const { fixture, cmp, update } = setup(WITH_FILTERS);
        await flush();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[role="group"][aria-label="Feature 1 filter 1"]')).toBeTruthy();
        const row = cmp.featureEditor!.rows.at(0);
        expect(row.controls['unit'].value).toBe('5');
        expect(cmp.featureEditor!.filtersOf(row).at(0).value).toEqual(
            expect.objectContaining({ field: 'mb', op: '>', value: '0' }),
        );
        await expectNoA11yViolations(el);
        cmp.save();
        const f = savedFeature(update);
        expect(f['filters']).toEqual([{ field: 'mb', op: '>', value: 0 }]); // still the number 0
        expect(f['unit']).toBe(5);
    });

    it('writes edited filters per operator and leaves no key for an empty list or a blank unit', async () => {
        const { fixture, cmp, update } = setup(EXISTING);
        await flush();
        fixture.detectChanges();
        const editor = cmp.featureEditor!;
        const row = editor.rows.at(0);
        for (let n = 0; n < 3; n++) editor.addFilter(row);
        const filters = editor.filtersOf(row);
        filters.at(0).patchValue({ field: 'msisdn', op: 'in', value: 'a, b' });
        filters.at(1).patchValue({ field: 'mb', op: 'isNull', value: '' });
        filters.at(2).patchValue({ field: 'mb', op: '>=', value: '10' });
        row.controls['unit'].setValue('2.5');
        cmp.save();
        expect(savedFeature(update)['filters']).toEqual([
            { field: 'msisdn', op: 'in', value: ['a', 'b'] },
            { field: 'mb', op: 'isNull' },
            { field: 'mb', op: '>=', value: '10' },
        ]);
        expect(savedFeature(update)['unit']).toBe(2.5);

        for (let n = 0; n < 3; n++) editor.removeFilter(row, 0);
        row.controls['unit'].setValue('');
        cmp.save();
        expect(savedFeature(update, 1)).not.toHaveProperty('filters');
        expect(savedFeature(update, 1)).not.toHaveProperty('unit');
    });

    it('refuses a filter without a column and a unit of 0 before calling the server', async () => {
        const { fixture, cmp, update } = setup(EXISTING);
        await flush();
        fixture.detectChanges();
        const row = cmp.featureEditor!.rows.at(0);
        cmp.featureEditor!.addFilter(row);
        cmp.featureEditor!.filtersOf(row).at(0).patchValue({ op: '=', value: 'x' });
        row.controls['unit'].setValue('0');
        cmp.save();
        fixture.detectChanges();
        expect(update).not.toHaveBeenCalled();
        const el = fixture.nativeElement as HTMLElement;
        const alerts = Array.from(el.querySelectorAll('[role="group"] [role="alert"]')).map((a) => a.textContent);
        expect(alerts.join(' ')).toContain('Choose: Column');
        const errors = Array.from(el.querySelectorAll('.mat-mdc-form-field-subscript-wrapper mat-error'));
        expect(errors.map((e) => e.textContent).join(' ')).toContain('Spread floor must be a number above 0');
    });

    it('previews the UNSAVED draft by sending its content, not the saved id', async () => {
        const { fixture, cmp, preview, previewContent } = setup(EXISTING, undefined, { work: true });
        await flush();
        fixture.detectChanges();
        cmp.featureEditor!.rows.at(0).controls['measure'].setValue('count');
        const el = fixture.nativeElement as HTMLElement;
        submitPreview(el, '966501234567');
        fixture.detectChanges();
        expect(preview).not.toHaveBeenCalled();
        expect(previewContent).toHaveBeenCalledWith(
            expect.objectContaining({ id: 'usage', features: [expect.objectContaining({ measure: 'count' })] }),
            '966501234567',
            undefined,
        );
        expect(el.querySelector('[data-testid="anomaly-preview"]')?.textContent).toContain(
            'Scored under the unsaved draft',
        );
        await expectNoA11yViolations(el);
    });

    it('does not preview an invalid draft and says why', async () => {
        const { fixture, cmp, previewContent } = setup(EXISTING, undefined, { work: true });
        await flush();
        fixture.detectChanges();
        cmp.featureEditor!.rows.at(0).controls['measure'].setValue('total(mb)');
        const el = fixture.nativeElement as HTMLElement;
        submitPreview(el, '966501234567');
        fixture.detectChanges();
        expect(previewContent).not.toHaveBeenCalled();
        expect(el.querySelector('app-anomaly-model-preview [role="alert"]')?.textContent).toContain(
            'Correct the highlighted fields',
        );
    });

    it('hides the draft preview without canWorkIncidents', async () => {
        const { fixture } = setup(EXISTING);
        await flush();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('app-anomaly-model-preview')).toBeNull();
    });

    it('reads a maker-checker hold as held, not saved', () => {
        expect(heldAnomalyChange({ status: 'pending', pendingChange: { id: 'pc-1' } })).toEqual({ id: 'pc-1' });
        expect(heldAnomalyChange({ name: 'usage' })).toBeNull();
    });
});
