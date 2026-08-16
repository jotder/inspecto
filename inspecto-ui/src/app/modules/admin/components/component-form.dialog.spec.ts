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

/** Build the dialog. Pass `def` for edit mode — the id locks and the stored content seeds the form. */
function create(kind: ComponentDef['type'] = 'grammar', def?: ComponentDef) {
    const ref = { close: vi.fn() };
    const api = {
        create: vi.fn(() => of(SAVED)),
        update: vi.fn(() => of(SAVED)),
    };
    // <inspecto-ai-assist> (A5.2) injected these three when the schema kind rendered it. The kind is
    // retired (W1) so nothing renders it now, but the stubs stay: cheaper than proving absence, and this
    // spec is about the dialog — the surface has its own specs.
    localStorage.removeItem('inspecto.currentLens');
    TestBed.resetTestingModule(); // some cases build a second dialog to compare two stored shapes
    TestBed.configureTestingModule({
        imports: [ComponentFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { kind, def } },
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
        expect(api.create).toHaveBeenCalledWith(
            'grammar',
            expect.objectContaining({ id: 'csv-basic', has_header: true }),
        );
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

    // The engine's SinkPartitions reader accepts both a bare column name and a {column, source} map, so a
    // string-only write is legal but lossy: `source` names the column the value is derived FROM.
    it('round-trips a sink partitions entry that carries a source', () => {
        const ref = { close: vi.fn() };
        const api = { create: vi.fn(() => of(SAVED)), update: vi.fn(() => of(SAVED)) };
        const def: ComponentDef = {
            type: 'sink',
            name: 'my-sink',
            ref: 'sink:my-sink',
            content: {
                type: 'sink.persistent',
                store: 'warehouse',
                partitions: ['tenant', { column: 'day', source: 'event_time' }],
            },
        };
        TestBed.configureTestingModule({
            imports: [ComponentFormDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MAT_DIALOG_DATA, useValue: { kind: 'sink', def } },
                { provide: MatDialogRef, useValue: ref },
                { provide: ComponentsService, useValue: api },
                { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            ],
        });
        const fixture = TestBed.createComponent(ComponentFormDialog);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.form.controls['partitions'].value).toEqual(['tenant', 'day']);

        c.submit();
        expect(api.update).toHaveBeenCalledWith(
            'sink',
            'my-sink',
            expect.objectContaining({
                partitions: ['tenant', { column: 'day', source: 'event_time' }],
            }),
        );

        // A newly typed chip is still a bare string, and dropping the mapped chip drops its map with it.
        api.update.mockClear();
        c.removePartition('day');
        c.addPartition({ value: 'region', chipInput: { clear: vi.fn() } } as never);
        c.submit();
        expect(api.update).toHaveBeenCalledWith(
            'sink',
            'my-sink',
            expect.objectContaining({
                partitions: ['tenant', 'region'],
            }),
        );
    });

    // AI drafting was offered ONLY for the `schema` kind and was removed with it (unification W1,
    // 2026-07-31): a schema is no longer a registry component. The specs that covered `applySchemaDraft`
    // and the field-row FormArray went with the code. What survives is the rule that made the affordance
    // conditional in the first place — a kind with no structural ConfigSpec must not render the button,
    // because every use would answer "no structural spec for kind".
    it('offers no AI drafting on a kind component_draft cannot validate', () => {
        expect(create('grammar').fixture.nativeElement.querySelector('inspecto-ai-assist')).toBeNull();
    });

    // A Grammar component has TWO stored shapes (grammar-block.ts): the legacy flat csv map, and the
    // nested `parsing:` block a Parse drawer writes. This form authors delimited settings only, and
    // `PUT /components` REPLACES content (the server carries over just owner/shares), so reading the
    // wrong shape here does not merely mis-display — it destroys the stored parser on Save.
    describe('grammar content shapes (A9)', () => {
        const grammarDialog = (content: Record<string, unknown>) =>
            create('grammar', { type: 'grammar', name: 'vendor-x', ref: 'grammar:vendor-x', content });

        it('seeds from a nested delimited block instead of showing DSV defaults', () => {
            const { c } = grammarDialog({ frontend: 'delimited', delimited: { delimiter: '|', has_header: true } });
            expect(c.form.getRawValue().delimiter).toBe('|');
            expect(c.form.getRawValue().hasHeader).toBe(true);
        });

        it('saves a nested component back nested, keeping the keys it cannot author', () => {
            const { c, api } = grammarDialog({
                frontend: 'delimited',
                delimited: { delimiter: '|', has_header: true, null_strings: ['\\N'] },
            });
            c.form.patchValue({ delimiter: ';' });
            c.submit();
            expect(api.update).toHaveBeenCalledWith('grammar', 'vendor-x', {
                frontend: 'delimited',
                delimited: { delimiter: ';', has_header: true, null_strings: ['\\N'] },
            });
        });

        it('saves a legacy flat component back flat — an edit here is not a migration', () => {
            const { c, api } = grammarDialog({ delimiter: '|', has_header: true, null_strings: ['\\N'] });
            c.form.patchValue({ delimiter: ';' });
            c.submit();
            expect(api.update).toHaveBeenCalledWith('grammar', 'vendor-x', {
                delimiter: ';',
                has_header: true,
                null_strings: ['\\N'],
            });
        });

        it('removes an optional key the operator cleared', () => {
            const { c, api } = grammarDialog({ delimiter: ',', has_header: false, quote: '"' });
            expect(c.form.getRawValue().quote).toBe('"');
            c.form.patchValue({ quote: '' });
            c.submit();
            expect(api.update).toHaveBeenCalledWith('grammar', 'vendor-x', { delimiter: ',', has_header: false });
        });

        it('refuses a plugin Grammar rather than replacing it with DSV settings', () => {
            const { c, api, fixture } = grammarDialog({
                frontend: 'plugin',
                plugin: { ingesterClass: 'com.gamma.Asn1RecordIngester', segments: { cdr: 'x_cdr.toon' } },
            });
            expect(c.grammarUnauthorable).toBe('plugin');
            c.submit();
            expect(api.update).not.toHaveBeenCalled();

            const text = fixture.nativeElement.textContent as string;
            expect(text).toContain('cannot author');
            const save = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
            expect(save.disabled).toBe(true);
        });

        it('refuses a Grammar whose frontend is not delimited', () => {
            expect(grammarDialog({ frontend: 'fixedwidth' }).c.grammarUnauthorable).toBe('fixedwidth');
            expect(grammarDialog({ fixedwidth: { columns: [] } }).c.grammarUnauthorable).toBe('fixedwidth');
            expect(grammarDialog({ parser_type: 'asn1' }).c.grammarUnauthorable).toBe('asn1');
        });

        it('still treats an undeclared legacy component as authorable delimited', () => {
            expect(grammarDialog({ delimiter: ',', has_header: true }).c.grammarUnauthorable).toBeNull();
            expect(create('grammar').c.grammarUnauthorable).toBeNull();
        });
    });
});
