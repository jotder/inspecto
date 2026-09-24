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
    // <inspecto-ai-assist> injects these three; the `transform` kind renders it (AI drafting S4).
    const runTool = vi.fn((..._args: unknown[]) => of({}));
    const toastr = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    localStorage.removeItem('inspecto.currentLens');
    TestBed.resetTestingModule(); // some cases build a second dialog to compare two stored shapes
    TestBed.configureTestingModule({
        imports: [ComponentFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { kind, def } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ComponentsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: AgentService, useValue: { runTool, deriveTool: () => of({}) } },
            { provide: LensService, useValue: { canAuthorWorkbench: () => true } },
        ],
    });
    const fixture = TestBed.createComponent(ComponentFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api, runTool, toastr };
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
                { provide: AgentService, useValue: { runTool: () => of({}), deriveTool: () => of({}) } },
                { provide: LensService, useValue: { canAuthorWorkbench: () => true } },
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

    // A kind with no structural spec must not render the button, because every use would answer "no
    // structural spec for kind". Of this dialog's kinds only `transform` has one (ComponentSpecs, D1).
    it('offers no AI drafting on a kind component_draft cannot validate', () => {
        expect(create('grammar').fixture.nativeElement.querySelector('inspecto-ai-assist')).toBeNull();
        expect(create('sink').fixture.nativeElement.querySelector('inspecto-ai-assist')).toBeNull();
    });

    // AI drafting on the transform kind (design ai-drafting-non-schema-design.md S4, D6/D7).
    describe('transform AI drafting (S4)', () => {
        const aiButton = (fixture: { nativeElement: HTMLElement }) =>
            fixture.nativeElement.querySelector('inspecto-ai-assist button') as HTMLButtonElement;

        it('sends the form draft and the Test sample as component_draft args', () => {
            const { fixture, c, runTool } = create('transform');
            c.form.patchValue({ subtype: 'transform.filter', config: '{ "where": "CAST(amt AS INT) >= 100" }' });
            c.sampleRows.set('[{ "id": "1", "amt": "150" }]');
            fixture.detectChanges();

            aiButton(fixture).click();
            expect(runTool).toHaveBeenCalledWith('component_draft', {
                kind: 'transform',
                config: { type: 'transform.filter', where: 'CAST(amt AS INT) >= 100' },
                sampleRows: [{ id: '1', amt: '150' }],
            });
        });

        it('offers the sample box on create too, because the check is judged by a preview', () => {
            const { fixture } = create('transform');
            const labels = Array.from(fixture.nativeElement.querySelectorAll('mat-label') as NodeListOf<HTMLElement>);
            expect(labels.some((l) => l.textContent?.includes('Sample rows'))).toBe(true);
        });

        it('blocks the check with a reason while the config or the sample is not valid JSON', () => {
            const { fixture, c, runTool } = create('transform');
            c.form.patchValue({ config: '{ not json' });
            fixture.detectChanges();
            expect(c.aiBlockedReason()).toContain('Config');
            expect(aiButton(fixture).disabled).toBe(true);

            c.form.patchValue({ config: '{}' });
            c.sampleRows.set('[oops');
            fixture.detectChanges();
            expect(c.aiBlockedReason()).toContain('Sample rows');
            expect(aiButton(fixture).disabled).toBe(true);
            expect(runTool).not.toHaveBeenCalled();
        });

        it('applies a draft into the operator picker and the config JSON, marks dirty, and never saves', () => {
            const { c, api } = create('transform');
            c.applyTransformDraft({
                label: 'transform',
                config: { type: 'transform.route', branches: [{ key: 'big', where: 'amt > 100' }] },
                clean: true,
                findings: [],
            });
            expect(c.form.getRawValue().subtype).toBe('transform.route');
            expect(JSON.parse(c.form.getRawValue().config)).toEqual({ branches: [{ key: 'big', where: 'amt > 100' }] });
            expect(c.form.dirty).toBe(true);
            expect(api.create).not.toHaveBeenCalled();
            expect(api.update).not.toHaveBeenCalled();
        });

        it('refuses a draft that does not name a transform operator', () => {
            const { c, toastr } = create('transform');
            const before = c.form.getRawValue();
            c.applyTransformDraft({ label: 'x', config: { type: 'sink.view' }, clean: false, findings: [] });
            expect(c.form.getRawValue()).toEqual(before);
            expect(c.form.dirty).toBe(false);
            expect(toastr.error).toHaveBeenCalled();
        });

        it('renders with no a11y violations', async () => {
            const { fixture } = create('transform');
            await expectNoA11yViolations(fixture.nativeElement);
        });
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
