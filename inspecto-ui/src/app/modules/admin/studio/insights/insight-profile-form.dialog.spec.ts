import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { InsightPreview, InsightsService } from 'app/inspecto/api/insights.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { InsightProfileFormDialog } from './insight-profile-form.dialog';

const EXISTING: ComponentDef = {
    type: 'insight-profile',
    name: 'sales',
    ref: 'insight-profile/sales',
    contentHash: 'abc',
    content: {
        id: 'sales',
        dataset: 'orders',
        measure: { agg: 'sum', field: 'amount' },
        field: 'order_date',
        grain: 'week',
        dimensions: [],
        types: ['anomaly'],
        filters: [{ field: 'x', op: '=', value: 1 }],
    },
};

const PREVIEW: InsightPreview = {
    periods: 12,
    perPeriod: [
        { period: '2026-10-05', count: 2 },
        { period: '2026-10-12', count: 0 },
    ],
    total: 2,
    perPeriodAverage: 1,
    insights: [
        {
            insight_id: 'p1',
            insight_type: 'anomaly',
            dimension: '*',
            slice_value: null,
            period: '2026-10-05',
            score: 55,
            band: 'elevated',
            explanation: { sentence: 'Sales were 30% above usual.' },
            asOf: '2026-10-05',
        },
    ],
    skipped: [],
};

function create(update = vi.fn(() => of(EXISTING))) {
    const preview = vi.fn(() => of(PREVIEW));
    const close = vi.fn();
    TestBed.configureTestingModule({
        imports: [InsightProfileFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { existing: EXISTING } },
            {
                provide: MatDialogRef,
                useValue: { close, disableClose: false, backdropClick: () => of(), keydownEvents: () => of() },
            },
            { provide: ComponentsService, useValue: { update, create: vi.fn(), list: () => of([]) } },
            { provide: InsightsService, useValue: { preview } },
            {
                provide: DatasetsService,
                useValue: {
                    get: () =>
                        of({
                            id: 'orders',
                            columns: [
                                { name: 'region', role: 'dimension' },
                                { name: 'channel', role: 'dimension' },
                                { name: 'order_date', role: 'temporal' },
                            ],
                        }),
                },
            },
            { provide: DatasetRowsService, useValue: { columns: async () => [] } },
            { provide: InspectoConfirmService, useValue: { confirm: vi.fn(async () => true) } },
        ],
    });
    TestBed.overrideComponent(InsightProfileFormDialog, { set: { changeDetection: ChangeDetectionStrategy.Eager } });
    const fixture = TestBed.createComponent(InsightProfileFormDialog);
    fixture.detectChanges();
    return {
        fixture,
        cmp: fixture.componentInstance,
        el: fixture.nativeElement as HTMLElement,
        preview,
        close,
        update,
    };
}

const button = (el: HTMLElement, text: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === text)!;

describe('InsightProfileFormDialog', () => {
    it('replays the unsaved content and shows counts, the summary and top Insights; nothing is saved', async () => {
        const { fixture, el, preview, update } = create();
        button(el, 'Add calendar entry').click();
        fixture.detectChanges();
        fixture.componentInstance.patch(0, 'from', '2026-12-25');
        fixture.componentInstance.patch(0, 'label', 'Christmas');
        button(el, 'Preview on history').click();
        fixture.detectChanges();
        const body = (preview.mock.calls[0] as unknown[])[0] as Record<string, unknown>;
        expect(body).toMatchObject({ id: 'sales', dataset: 'orders', filters: EXISTING.content['filters'] });
        expect(body['calendar']).toEqual([{ from: '2026-12-25', label: 'Christmas', mode: 'expected' }]);
        expect((preview.mock.calls[0] as unknown[])[1]).toBe(12);
        expect(el.querySelector('[data-testid="preview-summary"]')?.textContent).toContain(
            'You would have seen 2 Insights in the last 12 periods',
        );
        expect(el.querySelector('canvas')?.getAttribute('aria-label')).toContain('2026-10-05 2');
        expect(el.textContent).toContain('Sales were 30% above usual.');
        expect(update).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('proposes dimensions from the Dataset column roles', async () => {
        const { fixture, cmp } = create();
        await cmp.proposeDimensions();
        fixture.detectChanges();
        expect(cmp.schemaForm!.form.get('dimensions')!.value).toEqual(['region', 'channel']);
        expect(fixture.nativeElement.textContent).toContain('Proposed 2 dimensions: region, channel.');
    });

    it('saves through the component route with If-Match, and shows a 422 in the server words', () => {
        const update = vi.fn(() =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 422,
                        error: {
                            error: {
                                message: "insight-profile 'sales': 'types' must be a non-empty subset of [anomaly]",
                            },
                        },
                    }),
            ),
        );
        const { fixture, el, close } = create(update);
        button(el, 'Save').click();
        fixture.detectChanges();
        expect(update).toHaveBeenCalledWith('insight-profile', 'sales', expect.objectContaining({ id: 'sales' }), {
            ifMatch: 'abc',
        });
        expect(close).not.toHaveBeenCalled();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain("'types' must be a non-empty subset");
    });
});
