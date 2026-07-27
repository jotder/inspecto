import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { AgentService, ComponentDef, ComponentsService, LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentFormDialog } from './component-form.dialog';

const SAVED: ComponentDef = {
    type: 'grammar',
    name: 'csv-basic',
    ref: 'grammar:csv-basic',
    content: { delimiter: ',', has_header: true },
};

function create(kind: ComponentDef['type'] = 'grammar') {
    const ref = { close: vi.fn() };
    const api = {
        create: vi.fn(() => of(SAVED)),
        update: vi.fn(() => of(SAVED)),
    };
    // The schema kind renders <inspecto-ai-assist> (A5.2), which injects these three. Stubbed rather
    // than wired to real HTTP: this spec is about the dialog, and the surface has its own specs.
    localStorage.removeItem('inspecto.currentLens');
    TestBed.configureTestingModule({
        imports: [ComponentFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { kind } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ComponentsService, useValue: api },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            { provide: AgentService, useValue: { runTool: () => of({}), deriveTool: () => of({}) } },
            { provide: LensService, useValue: { canAuthorWorkbench: () => true } },
        ],
    });
    const fixture = TestBed.createComponent(ComponentFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api };
}

describe('ComponentFormDialog', () => {
    it('blocks submit until the id is valid, then creates and closes with the saved component', () => {
        const { c, ref, api } = create();
        c.submit();
        expect(api.create).not.toHaveBeenCalled();
        expect(ref.close).not.toHaveBeenCalled();

        c.form.patchValue({ id: 'csv-basic', hasHeader: true });
        c.submit();
        expect(api.create).toHaveBeenCalledWith('grammar', expect.objectContaining({ id: 'csv-basic', has_header: true }));
        expect(ref.close).toHaveBeenCalledWith({ saved: SAVED });
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('blocks a transform submit when the config textarea is not valid JSON', () => {
        const ref = { close: vi.fn() };
        const api = { create: vi.fn(() => of(SAVED)), update: vi.fn(() => of(SAVED)) };
        TestBed.configureTestingModule({
            imports: [ComponentFormDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MAT_DIALOG_DATA, useValue: { kind: 'transform' } },
                { provide: MatDialogRef, useValue: ref },
                { provide: ComponentsService, useValue: api },
                { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            ],
        });
        const fixture = TestBed.createComponent(ComponentFormDialog);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.form.patchValue({ id: 'my-transform', config: '{ not json' });
        expect(c.form.controls['config'].hasError('invalidJson')).toBe(true);
        c.submit();
        expect(api.create).not.toHaveBeenCalled();

        c.form.patchValue({ config: '{ "where": "1=1" }' });
        expect(c.form.controls['config'].hasError('invalidJson')).toBe(false);
        c.submit();
        expect(api.create).toHaveBeenCalledWith('transform', expect.objectContaining({ where: '1=1' }));
    });

    it('adds/removes partition chips for a sink', () => {
        const ref = { close: vi.fn() };
        const api = { create: vi.fn(() => of(SAVED)), update: vi.fn(() => of(SAVED)) };
        TestBed.configureTestingModule({
            imports: [ComponentFormDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MAT_DIALOG_DATA, useValue: { kind: 'sink' } },
                { provide: MatDialogRef, useValue: ref },
                { provide: ComponentsService, useValue: api },
                { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            ],
        });
        const fixture = TestBed.createComponent(ComponentFormDialog);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.addPartition({ value: 'year', chipInput: { clear: vi.fn() } } as never);
        c.addPartition({ value: 'month', chipInput: { clear: vi.fn() } } as never);
        expect(c.form.controls['partitions'].value).toEqual(['year', 'month']);
        c.removePartition('year');
        expect(c.form.controls['partitions'].value).toEqual(['month']);

        c.form.patchValue({ id: 'my-sink' });
        c.submit();
        expect(api.create).toHaveBeenCalledWith('sink', expect.objectContaining({ partitions: ['month'] }));
    });

    // ── AGT-6a A5.2 ──────────────────────────────────────────────────────────────────────────────

    // One create() per it(): the helper configures TestBed, which may only happen once per test.
    it('offers AI drafting on the schema kind, the only one with a structural spec', () => {
        expect(create('schema').fixture.nativeElement.querySelector('inspecto-ai-assist')).not.toBeNull();
    });

    it('offers no AI drafting on a kind component_draft cannot validate', () => {
        // grammar has no ConfigSpec, so the tool would answer "no structural spec for kind" every time.
        expect(create('grammar').fixture.nativeElement.querySelector('inspecto-ai-assist')).toBeNull();
    });

    it('applies a drafted schema by replacing the field rows, and marks the form dirty', () => {
        const { c, api } = create('schema');
        c.applySchemaDraft({
            label: 'orders',
            clean: true,
            findings: [],
            config: {
                fields: [
                    { name: 'order_id', type: 'string' },
                    { name: 'amount', type: 'number', format: '0.00' },
                ],
            },
        });

        expect(c.fields.length).toBe(2);
        expect(c.form.dirty).toBe(true);
        // and it round-trips through the pane's OWN save path — the surface never writes
        c.form.patchValue({ id: 'orders' });
        c.submit();
        expect(api.create).toHaveBeenCalledWith('schema', expect.objectContaining({
            fields: [
                { name: 'order_id', type: 'string' },
                { name: 'amount', type: 'number', format: '0.00' },
            ],
        }));
    });

    it('ignores a draft carrying no fields rather than emptying the form', () => {
        const { c } = create('schema');
        const before = c.fields.length;
        c.applySchemaDraft({ label: 'empty', clean: false, findings: [], config: {} });
        expect(c.fields.length).toBe(before);
    });

    it('the schema kind renders with no a11y violations', async () => {
        const { fixture } = create('schema');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
