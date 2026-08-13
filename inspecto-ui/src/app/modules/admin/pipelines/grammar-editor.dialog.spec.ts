import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import {
    AuthoredNode,
    ComponentDef,
    ComponentsService,
    ParserDef,
    ParserPreview,
    ParsersService,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { GrammarEditorComponent } from 'app/inspecto/grammar';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GrammarEditorDialog, GrammarEditorDialogData } from './grammar-editor.dialog';

const TABLE_PREVIEW: ParserPreview = {
    kind: 'table',
    columns: ['id'],
    rows: [{ id: '1' }],
    rowCount: 1,
    rejectedRows: 0,
};

/** A served catalog in the real shape (`GET /parsers`): a built-in + the preview-only XML plugin. */
const CATALOG: ParserDef[] = [
    {
        id: 'delimited',
        label: 'Delimited — CSV / TSV / pipe',
        hierarchical: false,
        ingestable: true,
        grammarSchema: [],
    },
    {
        id: 'xml',
        label: 'XML — XML file format',
        hierarchical: true,
        ingestable: false,
        grammarSchema: [{ path: 'xml.record_element', label: 'Record element', type: 'STRING' }],
    },
];

function saved(name: string, content: Record<string, unknown> = {}): ComponentDef {
    return { type: 'grammar', name, ref: `grammar/${name}`, content };
}

async function create(
    opts: { node?: AuthoredNode; grammars?: ComponentDef[]; dialogOpen?: ReturnType<typeof vi.fn> } = {},
) {
    const close = vi.fn();
    const components = {
        list: () => of(opts.grammars ?? []),
        create: vi.fn((_t: string, _c: Record<string, unknown>) => of(saved('x'))),
        update: vi.fn((_t: string, id: string, _c: Record<string, unknown>) => of(saved(id))),
    };
    const parsers = { list: vi.fn(() => of(CATALOG)), preview: vi.fn(() => of(TABLE_PREVIEW)) };
    const data: GrammarEditorDialogData = {
        node: opts.node ?? { id: 'parse', type: 'parser.dsv' },
        typeLabel: 'parser.dsv',
        categoryLabel: 'Parser',
    };
    TestBed.configureTestingModule({
        imports: [GrammarEditorDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close, addPanelClass: vi.fn(), removePanelClass: vi.fn() } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: ComponentsService, useValue: components },
            { provide: ParsersService, useValue: parsers },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(() => Promise.resolve(true)) } },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {} } },
        ],
    });
    // MatDialogModule's own provider wins over a plain useValue in the same TestBed (the
    // data-table trap) — overrideProvider is the documented fix when a test spies on open().
    if (opts.dialogOpen) TestBed.overrideProvider(MatDialog, { useValue: { open: opts.dialogOpen } });
    await TestBed.compileComponents(); // the data-table pro tier @defer-loads its SQL editor
    const fixture = TestBed.createComponent(GrammarEditorDialog);
    fixture.detectChanges();
    const editor = fixture.debugElement.query(By.directive(GrammarEditorComponent))
        .componentInstance as GrammarEditorComponent;
    return { fixture, c: fixture.componentInstance, close, components, parsers, editor };
}

describe('GrammarEditorDialog', () => {
    it('defaults to INLINE: the node keeps its own parsing: block and no component is written', async () => {
        const node: AuthoredNode = { id: 'parse', type: 'parser.dsv', config: { parsing: { frontend: 'delimited' } } };
        const { c, close, components, editor } = await create({ node });

        expect(c.boundGrammarId()).toBeNull();
        expect(editor.frontend()).toBe('delimited');

        editor.setFrontend('json');
        c.save();

        expect(components.create).not.toHaveBeenCalled();
        expect(components.update).not.toHaveBeenCalled();
        const closed = close.mock.calls[0][0];
        expect(closed.node.use).toBeUndefined();
        expect((closed.node.config['parsing'] as Record<string, unknown>)['frontend']).toBe('json');
    });

    it('an unmodelled node config key survives the save', async () => {
        const node: AuthoredNode = { id: 'parse', type: 'parser.dsv', config: { schema_file: 'cdr.toon' } };
        const { c, close } = await create({ node });
        c.save();
        expect(close.mock.calls[0][0].node.config['schema_file']).toBe('cdr.toon');
    });

    it('a bound node loads the component block and saves straight back to that component', async () => {
        const node: AuthoredNode = { id: 'parse', type: 'parser.dsv', use: 'grammar/cdr_csv' };
        const { c, close, components, editor, fixture } = await create({
            node,
            grammars: [saved('cdr_csv', { frontend: 'json' })],
        });
        fixture.detectChanges();

        expect(c.boundGrammarId()).toBe('cdr_csv');
        expect(editor.frontend()).toBe('json');

        c.save();
        expect(components.update).toHaveBeenCalledWith(
            'grammar',
            'cdr_csv',
            expect.objectContaining({ frontend: 'json' }),
        );
        expect(close.mock.calls[0][0].node.use).toBe('grammar/cdr_csv');
    });

    it('reads a pre-unification component: parser_type stands in for frontend', async () => {
        const node: AuthoredNode = { id: 'parse', type: 'parser.dsv', use: 'grammar/legacy' };
        const { editor, fixture } = await create({
            node,
            grammars: [saved('legacy', { parser_type: 'json', json: { root_path: '$' } })],
        });
        fixture.detectChanges();
        expect(editor.frontend()).toBe('json');
    });

    it('extract asks for a name, writes the component, and MOVES the block off the node', async () => {
        const node: AuthoredNode = {
            id: 'parse',
            type: 'parser.dsv',
            config: { parsing: { frontend: 'delimited' }, schema_file: 'cdr.toon' },
        };
        const { c, fixture, components, close } = await create({ node });

        c.extract();
        expect(c.step()).toBe('name');
        expect(c.nameForm.controls.name.value).toBe('delimited_grammar');
        fixture.detectChanges();

        c.save();
        expect(components.create).toHaveBeenCalledWith(
            'grammar',
            expect.objectContaining({ id: 'delimited_grammar', frontend: 'delimited' }),
        );
        const closed = close.mock.calls[0][0];
        expect(closed.node.use).toBe('grammar/delimited_grammar');
        expect(closed.node.config['parsing']).toBeUndefined(); // one home, never two
        expect(closed.node.config['schema_file']).toBe('cdr.toon');
    });

    it('blocks the name step on a duplicate id', async () => {
        const { c, fixture, components } = await create({ grammars: [saved('taken')] });
        c.extract();
        fixture.detectChanges();
        c.nameForm.patchValue({ name: 'taken' });
        c.save();
        expect(c.nameForm.controls.name.hasError('duplicate')).toBe(true);
        expect(components.create).not.toHaveBeenCalled();
    });

    it('switching a bound node back to Inline drops the use: binding on save', async () => {
        const node: AuthoredNode = { id: 'parse', type: 'parser.dsv', use: 'grammar/cdr_csv' };
        const { c, close, components, fixture } = await create({
            node,
            grammars: [saved('cdr_csv', { frontend: 'json' })],
        });
        fixture.detectChanges();

        c.onGrammarChange('');
        c.save();

        expect(components.update).not.toHaveBeenCalled();
        const closed = close.mock.calls[0][0];
        expect(closed.node.use).toBeUndefined();
        expect(closed.node.config['parsing']).toBeTruthy();
    });

    it('refuses to save a plugin Grammar and says where segments are authored', async () => {
        const { c, fixture, editor, close } = await create();
        editor.setType('xml');
        fixture.detectChanges();

        expect(c.pluginBlocked()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Parsing stage');
        c.save();
        expect(close).not.toHaveBeenCalled();
    });

    it('a table test-parse arms the Draft Schema link with the parsed rows; a tree preview disarms it', async () => {
        const open = vi.fn();
        const { c } = await create({ dialogOpen: open });

        c.onPreviewed(TABLE_PREVIEW);
        expect(c.previewRows()).toEqual([{ id: '1' }]);

        c.openSchemaEditor();
        expect(open).toHaveBeenCalledWith(
            expect.anything(),
            expect.objectContaining({ data: { sampleRows: [{ id: '1' }] } }),
        );

        c.onPreviewed({ kind: 'tree', recordCount: 1, nodes: [] });
        expect(c.previewRows()).toEqual([]);
    });

    it('has no a11y violations on the editor step or the name step', async () => {
        const { c, fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
        c.extract();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
