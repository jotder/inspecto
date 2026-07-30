import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService, ParserDef, ParserPreview, ParsersService } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ParserConfigData, ParserConfigDialog } from './parser-config.dialog';

const TABLE_PREVIEW: ParserPreview = {
    kind: 'table', columns: ['id', 'msisdn'], rows: [{ id: '1', msisdn: 'x' }], rowCount: 1, rejectedRows: 0,
};
const TREE_PREVIEW: ParserPreview = {
    kind: 'tree', recordCount: 2,
    nodes: [{ label: 'record', type: 'element', children: [{ label: 'id', type: 'element', value: '1001' }] }],
};

/** A served catalog in the real shape (`GET /parsers`): built-ins + the preview-only XML plugin. */
const CATALOG: ParserDef[] = [
    {
        id: 'delimited', label: 'Delimited — CSV / TSV / pipe, one record per line',
        hierarchical: false, ingestable: true,
        grammarSchema: [
            { path: 'delimited.delimiter', label: 'Delimiter', type: 'STRING', defaultValue: ',' },
            { path: 'delimited.has_header', label: 'First line is a header', type: 'BOOL', defaultValue: true },
        ],
    },
    {
        id: 'xml', label: 'XML — XML file format', hierarchical: true, ingestable: false,
        grammarSchema: [{ path: 'xml.record_element', label: 'Record element', type: 'STRING' }],
    },
];

function saved(name: string): ComponentDef {
    return { type: 'grammar', name, ref: `grammar/${name}`, content: {} };
}

async function create(opts: { data?: Partial<ParserConfigData>; grammars?: ComponentDef[]; preview?: ParserPreview } = {}) {
    const close = vi.fn();
    const components = {
        list: () => of(opts.grammars ?? []),
        create: vi.fn((_t: string, c: Record<string, unknown>) => of(saved(String(c['id'])))),
        update: vi.fn((_t: string, id: string) => of(saved(id))),
    };
    const parsers = {
        list: vi.fn(() => of(CATALOG)),
        preview: vi.fn(() => of(opts.preview ?? TABLE_PREVIEW)),
    };
    TestBed.configureTestingModule({
        imports: [ParserConfigDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close, addPanelClass: vi.fn(), removePanelClass: vi.fn() } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: {
                    node: { id: 'parse', type: 'parser.dsv' },
                    typeLabel: 'parser.dsv',
                    categoryLabel: 'Parser',
                    ...opts.data,
                },
            },
            { provide: ComponentsService, useValue: components },
            { provide: ParsersService, useValue: parsers },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {} } },
        ],
    });
    await TestBed.compileComponents(); // the data-table pro tier @defer-loads its SQL editor
    const fixture = TestBed.createComponent(ParserConfigDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, close, components, parsers };
}

describe('ParserConfigDialog', () => {
    it('renders the SERVED catalog and its grammar schema as the property sheet', async () => {
        const { c, fixture } = await create();
        expect(c.parserType()).toBe('delimited');
        expect(c.isHierarchical()).toBe(false);
        expect(c.parserDefs().map((p) => p.id)).toEqual(['delimited', 'xml']);
        expect(c.schemaFormSpecs().map((s) => s.key)).toEqual(['delimited__delimiter', 'delimited__has_header']);
        fixture.detectChanges();
        expect(c.schemaForm.form.get('delimited__delimiter')!.value).toBe(','); // served default applied
    });

    it('rebuilds the sheet and flags hierarchical output on type change', async () => {
        const { c, fixture } = await create();
        c.onTypeChange('xml');
        fixture.detectChanges();
        expect(c.parserType()).toBe('xml');
        expect(c.isHierarchical()).toBe(true);
        expect(c.schemaFormSpecs().map((s) => s.key)).toEqual(['xml__record_element']);
        expect(c.sampleText()).toContain('<records>');
    });

    it('loads a bound grammar (type + nested props) and skips the two-step save', async () => {
        const bound: ComponentDef = {
            type: 'grammar', name: 'cdr_csv', ref: 'grammar/cdr_csv',
            content: { parser_type: 'delimited', delimited: { delimiter: '|' } },
        };
        const { c, fixture, components } = await create({
            data: { node: { id: 'parse', type: 'parser.dsv', use: 'grammar/cdr_csv' } },
            grammars: [bound],
        });
        fixture.detectChanges();
        expect(c.boundGrammarId()).toBe('cdr_csv');
        expect(c.parserType()).toBe('delimited');
        expect(c.schemaForm.form.get('delimited__delimiter')!.value).toBe('|');
        c.save(); // bound ⇒ straight-through update, no save step
        expect(components.update).toHaveBeenCalledWith('grammar', 'cdr_csv',
            expect.objectContaining({ parser_type: 'delimited' }));
    });

    it('two-step create: config advances to a pre-filled name, then persists the NESTED grammar', async () => {
        const { c, fixture, components, close } = await create();
        fixture.detectChanges();
        c.save();
        expect(c.step()).toBe('save');
        expect(c.saveForm.controls.name.value).toBe('delimited_grammar');
        c.save();
        expect(components.create).toHaveBeenCalledWith('grammar', expect.objectContaining({
            id: 'delimited_grammar',
            parser_type: 'delimited',
            delimited: { delimiter: ',', has_header: true },
        }));
        expect(close).toHaveBeenCalledWith({ node: expect.objectContaining({ use: 'grammar/delimited_grammar' }) });
    });

    it('blocks the save step on a duplicate name', async () => {
        const { c, fixture, components } = await create({ grammars: [saved('taken')] });
        fixture.detectChanges();
        c.save();
        c.saveForm.patchValue({ name: 'taken' });
        c.save();
        expect(c.saveForm.controls.name.hasError('duplicate')).toBe(true);
        expect(components.create).not.toHaveBeenCalled();
    });

    it('Test parse posts the nested grammar to the REAL preview endpoint and feeds the data table', async () => {
        const { c, fixture, parsers } = await create();
        fixture.detectChanges();
        c.test();
        expect(parsers.preview).toHaveBeenCalledWith('delimited',
            { delimited: { delimiter: ',', has_header: true } }, c.sampleText());
        expect(c.preview()?.kind).toBe('table');
        expect(c.gridRows().length).toBe(1);
        fixture.detectChanges();
        // The shared data-table (pro tier) over the parsed rows — its SQL editor seeds
        // `FROM parsed`, so the author queries the sample without knowing any table name.
        expect(fixture.nativeElement.querySelector('inspecto-data-table')).toBeTruthy();
    });

    it('a hierarchical preview renders the record tree', async () => {
        const { c, fixture } = await create({ preview: TREE_PREVIEW });
        c.onTypeChange('xml');
        fixture.detectChanges();
        c.test();
        fixture.detectChanges();
        expect(c.preview()?.kind).toBe('tree');
        expect(fixture.nativeElement.querySelector('app-parser-tree')).toBeTruthy();
        expect(fixture.nativeElement.textContent).toContain('2 record(s)');
    });

    it('loads a chosen file into the sample content and resets the previous preview', async () => {
        const { c } = await create();
        c.test();
        expect(c.preview()).toBeTruthy();
        const file = new File(['x|y\n1|2\n'], 's.psv');
        c.onSampleFile({ 0: file, length: 1 } as unknown as FileList);
        await new Promise((r) => setTimeout(r));
        expect(c.sampleText()).toBe('x|y\n1|2\n');
        expect(c.preview()).toBeNull();
    });

    it('maximizes a pane and restores the layout', async () => {
        const { c } = await create();
        expect(c.paneVisible('props')).toBe(true);
        c.toggleMaximize('sample');
        expect(c.maximized()).toBe('sample');
        expect(c.paneVisible('props')).toBe(false);
        expect(c.paneVisible('sample')).toBe(true);
        c.toggleMaximize('sample');
        expect(c.maximized()).toBeNull();
    });

    it('toggles full screen via a dialog panel class', async () => {
        const { c } = await create();
        const ref = TestBed.inject(MatDialogRef);
        c.toggleFullscreen();
        expect(ref.addPanelClass).toHaveBeenCalledWith('dialog-fullscreen');
        c.toggleFullscreen();
        expect(ref.removePanelClass).toHaveBeenCalledWith('dialog-fullscreen');
    });

    it('backToConfig() returns from the save step without losing the typed name', async () => {
        const { c, fixture } = await create();
        fixture.detectChanges();
        c.save();
        c.saveForm.patchValue({ name: 'my_grammar' });
        c.backToConfig();
        expect(c.step()).toBe('config');
        expect(c.saveForm.controls.name.value).toBe('my_grammar');
    });

    it('has no a11y violations on the config step or the save step', async () => {
        const { c, fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
        c.save();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
