import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationLogEntry, InvestigationTemplate } from 'app/inspecto/api';
import { ComponentsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    InstantiateTemplateDialog,
    SaveTemplateDialog,
    WatchMeasureDialog,
    instantiateSpecs,
} from './link-analysis-template.dialogs';

const TEMPLATE: InvestigationTemplate = {
    id: 'tpl-1',
    title: 'Burner method',
    owner: 'alice',
    createdAt: '',
    derivedFrom: { investigation: 'inv-1', steps: 4, workingSetHash: 'sha256:h' },
    roles: { dataset: 'calls', sourceCol: 'A', targetCol: 'B', linkKindCol: null, timeCol: null, timeColZone: null },
    parameters: [
        { name: 'seed1', kind: 'seed', entityType: 'msisdn', step: 1 },
        { name: 'window1', kind: 'window', default: { from: '2026-01-01T00:00:00Z', to: null }, step: 3 },
    ],
    ops: [
        { op: 'seed', step: 1, param: 'seed1' },
        { op: 'expand', step: 2 },
    ],
    dropped: [{ step: 3, op: 'exclude', count: 2 }],
    generalised: [{ step: 2, namedFrontier: 1, exact: true }],
};

const ENTRIES = [
    { step: 1, kind: 'op', op: 'seed', params: { ids: ['a'] }, undoneBy: null },
    { step: 2, kind: 'op', op: 'expand', params: { ids: ['a'] }, undoneBy: null },
    { step: 3, kind: 'op', op: 'exclude', params: { ids: ['x', 'y'], reason: 'hub' }, undoneBy: null },
] as InvestigationLogEntry[];

function configure(data: unknown, inv: Partial<Record<keyof InvService, unknown>>) {
    const close = vi.fn();
    TestBed.configureTestingModule({
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: { close } },
            { provide: InvService, useValue: inv },
            { provide: ComponentsService, useValue: { list: () => of([]) } },
        ],
    });
    return close;
}

const http =
    (status: number, error = 'server says no') =>
    () =>
        throwError(() => new HttpErrorResponse({ status, error: { error } }));

describe('SaveTemplateDialog (LA-23)', () => {
    it('previews what will be dropped and generalised, then shows the server answer', async () => {
        const saveInvestigationTemplate = vi.fn(() => of(TEMPLATE));
        configure({ investigationId: 'inv-1', entries: ENTRIES, truncated: false }, { saveInvestigationTemplate });
        const f = TestBed.createComponent(SaveTemplateDialog);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        const preview = el.querySelector('[aria-label="What will change"]')!.textContent!;
        expect(preview).toContain('seed → parameter seed1');
        expect(preview).toContain('exclude of 2 entities → dropped');
        expect(preview).toContain('expand of 1 named entities → expands the whole');
        await expectNoA11yViolations(el);

        f.componentInstance.form.controls.title.setValue('Burner method');
        await f.componentInstance.save();
        f.detectChanges();
        expect(saveInvestigationTemplate).toHaveBeenCalledWith('inv-1', { title: 'Burner method' });
        const answer = el.querySelector('[aria-label="What the server dropped and generalised"]')!.textContent!;
        expect(answer).toContain('exact: that WAS the whole Working Set');
        expect(el.textContent).toContain('tpl-1');
    });

    it('refuses a bad template id locally and shows a 409 from the server', async () => {
        const saveInvestigationTemplate = vi.fn(http(409, "investigation template 'x' already exists"));
        configure({ investigationId: 'inv-1', entries: ENTRIES, truncated: false }, { saveInvestigationTemplate });
        const f = TestBed.createComponent(SaveTemplateDialog);
        f.detectChanges();
        f.componentInstance.form.controls.id.setValue('../x');
        await f.componentInstance.save();
        expect(saveInvestigationTemplate).not.toHaveBeenCalled();
        f.componentInstance.form.controls.id.setValue('x');
        await f.componentInstance.save();
        f.detectChanges();
        expect((f.nativeElement as HTMLElement).textContent).toContain('already exists');
    });
});

describe('InstantiateTemplateDialog (LA-23)', () => {
    it('builds one seed list per parameter and binds the template roles by default', () => {
        const specs = instantiateSpecs({ ...TEMPLATE, roles: { ...TEMPLATE.roles, linkKindCol: 'KIND' } });
        expect(specs.map((s) => s.key)).toEqual([
            'title',
            'param:seed1',
            'dataset',
            'sourceCol',
            'targetCol',
            'linkKindCol',
        ]);
        expect(specs.find((s) => s.key === 'param:seed1')!.type).toBe('list');
        expect(specs.find((s) => s.key === 'dataset')).toMatchObject({ type: 'autocomplete', default: 'calls' });
    });

    it('loads the template by id, then instantiates with params and the Dataset roles', async () => {
        const investigationTemplate = vi.fn(() => of(TEMPLATE));
        const instantiateTemplate = vi.fn(() => of({ id: 'inv-2', header: {}, steps: 2, workingSet: {} }));
        const close = configure(undefined, { investigationTemplate, instantiateTemplate });
        const f = TestBed.createComponent(InstantiateTemplateDialog);
        f.detectChanges();
        const c = f.componentInstance;
        c.templateId.setValue('tpl-1');
        await c.loadTemplate();
        f.detectChanges();
        await f.whenStable();
        f.detectChanges();
        expect(investigationTemplate).toHaveBeenCalledWith('tpl-1');
        const el = f.nativeElement as HTMLElement;
        expect(el.textContent).toContain('Burner method');
        await expectNoA11yViolations(el);

        const form = (
            c as unknown as { schemaForm: () => { form: { patchValue: (v: unknown) => void } } }
        ).schemaForm();
        form.form.patchValue({ 'param:seed1': ['4471', '4480'], dataset: 'calls_2024' });
        await c.instantiate();
        expect(instantiateTemplate).toHaveBeenCalledWith('tpl-1', {
            params: { seed1: ['4471', '4480'] },
            title: undefined,
            dataset: 'calls_2024',
            sourceCol: 'A',
            targetCol: 'B',
            linkKindCol: undefined,
        });
        expect(close).toHaveBeenCalledWith(expect.objectContaining({ id: 'inv-2' }));
    });

    it('explains a 404 template as absent-or-not-yours', async () => {
        configure(undefined, { investigationTemplate: vi.fn(http(404)) });
        const f = TestBed.createComponent(InstantiateTemplateDialog);
        f.detectChanges();
        f.componentInstance.templateId.setValue('tpl-9');
        await f.componentInstance.loadTemplate();
        f.detectChanges();
        expect((f.nativeElement as HTMLElement).textContent).toContain('it does not exist or it is not yours');
    });
});

describe('WatchMeasureDialog (LA-23)', () => {
    const data = {
        investigationId: 'inv-1',
        measure: { name: 'entities', relation: 'entities', measure: 'count', value: 12 },
    };

    it('binds the Measure and shows current value, wouldFire and the backend disclosure', async () => {
        const bindInvestigationAlertRule = vi.fn(() =>
            of({ rule: {}, current: 12, wouldFire: true, disclosure: 'when it fires, the Alert shows this id' }),
        );
        configure(data, { bindInvestigationAlertRule });
        const f = TestBed.createComponent(WatchMeasureDialog);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        await expectNoA11yViolations(el);
        f.componentInstance.form.controls.threshold.setValue(10);
        await f.componentInstance.bind();
        f.detectChanges();
        expect(bindInvestigationAlertRule).toHaveBeenCalledWith('inv-1', {
            name: 'inv-1-entities',
            relation: 'entities',
            measure: 'count',
            comparator: 'gt',
            threshold: 10,
            severity: 'WARNING',
        });
        expect(el.textContent).toContain('it would fire now.');
        expect(el.textContent).toContain('when it fires, the Alert shows this id');
    });

    it('does not bind without a threshold', async () => {
        const bindInvestigationAlertRule = vi.fn();
        configure(data, { bindInvestigationAlertRule });
        const f = TestBed.createComponent(WatchMeasureDialog);
        f.detectChanges();
        await f.componentInstance.bind();
        expect(bindInvestigationAlertRule).not.toHaveBeenCalled();
    });

    it('explains a 503 (no alert engine) as an info alert, and a 403 as the Alert-Rule capability', async () => {
        const bindInvestigationAlertRule = vi.fn(http(503, 'alert engine unavailable'));
        configure(data, { bindInvestigationAlertRule });
        const f = TestBed.createComponent(WatchMeasureDialog);
        f.detectChanges();
        f.componentInstance.form.controls.threshold.setValue(1);
        await f.componentInstance.bind();
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        expect(el.textContent).toContain('alert engine');
        expect(f.componentInstance.unavailable()).toBe(true);

        bindInvestigationAlertRule.mockImplementation(http(403, 'missing canAuthorAlertRules'));
        await f.componentInstance.bind();
        expect(f.componentInstance.error()).toContain('not allowed to author Alert Rules');
        expect(f.componentInstance.unavailable()).toBe(false);
    });
});
