import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { RiskScoreFormDialog, heldChange } from './risk-score-form.dialog';

const EXISTING: ComponentDef = {
    type: 'risk-score',
    name: 'sim_box',
    ref: 'risk-score/sim_box',
    contentHash: 'abc123',
    content: {
        id: 'sim_box',
        owner: 'ana',
        entityType: 'subscriber',
        highThreshold: 70,
        factors: [{ id: 'calls', dataset: 'cdr', key: 'a_number', measure: 'count', weight: 3 }],
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
        imports: [RiskScoreFormDialog],
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
            { provide: ComponentsService, useValue: { create, update } },
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
                                  { name: 'a_number', type: 'string' },
                                  { name: 'cell', type: 'string' },
                              ]
                            : [],
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(RiskScoreFormDialog);
    fixture.detectChanges();
    return { fixture, cmp: fixture.componentInstance, close, create, update };
}

describe('RiskScoreFormDialog', () => {
    it('offers only Datasets with a readable Schema (D-RP8)', async () => {
        const { fixture, cmp } = setup(EXISTING);
        await flush();
        fixture.detectChanges();
        expect(cmp.datasets().map((d) => d.value)).toEqual(['cdr']);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('saves an edit with If-Match of the stored hash, keeping envelope keys', async () => {
        const { fixture, cmp, update, close } = setup(EXISTING);
        await flush();
        fixture.detectChanges();
        cmp.save();
        expect(update).toHaveBeenCalledTimes(1);
        const [type, id, body, opts] = update.mock.calls[0] as unknown as [
            string,
            string,
            Record<string, unknown>,
            { ifMatch: string },
        ];
        expect([type, id, opts.ifMatch]).toEqual(['risk-score', 'sim_box', 'abc123']);
        expect(body['owner']).toBe('ana');
        expect(body['factors']).toEqual([
            { id: 'calls', dataset: 'cdr', key: 'a_number', measure: 'count', weight: 3 },
        ]);
        expect(close).toHaveBeenCalledWith({ saved: expect.objectContaining({ name: 'sim_box' }) });
    });

    it('refuses to submit an incomplete create and writes nothing', async () => {
        const { fixture, cmp, create } = setup();
        await flush();
        fixture.detectChanges();
        cmp.save();
        fixture.detectChanges();
        expect(create).not.toHaveBeenCalled();
        expect(fixture.nativeElement.querySelector('mat-error')).not.toBeNull();
    });

    it('lands a 422 on the factor row it names, message verbatim', async () => {
        const msg = 'risk-score.factors[0].cap must be >= 0, got -1';
        const { fixture, cmp, close } = setup(EXISTING, () =>
            throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: msg } } })),
        );
        await flush();
        fixture.detectChanges();
        cmp.save();
        fixture.detectChanges();
        const row = fixture.nativeElement.querySelector('[data-testid="factor-error"]');
        expect(row?.textContent).toContain(msg);
        expect(row.closest('fieldset').getAttribute('aria-label')).toBe('Factor 1');
        expect(close).not.toHaveBeenCalled();
    });

    it('lands a top-level 422 on its field', async () => {
        const msg = 'risk-score.highThreshold must be in (0, 100], got 0';
        const { fixture, cmp } = setup(EXISTING, () =>
            throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: msg } } })),
        );
        await flush();
        fixture.detectChanges();
        cmp.save();
        fixture.detectChanges();
        expect(cmp.schemaForm!.form.get('highThreshold')!.errors).toEqual({ message: msg });
    });

    it('closes with the held Pending Change on a 202', async () => {
        const { fixture, cmp, close } = setup(EXISTING, () =>
            of({ status: 'pending', written: false, pendingChange: { id: 'pc-1' } }),
        );
        await flush();
        fixture.detectChanges();
        cmp.save();
        expect(close).toHaveBeenCalledWith({ held: { id: 'pc-1' } });
    });

    it('reads a plain component as not held', () => {
        expect(heldChange(EXISTING)).toBeNull();
        expect(heldChange(null)).toBeNull();
    });
});
