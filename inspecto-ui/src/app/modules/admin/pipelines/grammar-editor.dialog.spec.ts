import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import {
    AuthoredNode,
    ComponentDef,
    ComponentsService,
    ConfigService,
    ParserDef,
    ParserPreview,
    ParsersService,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { GrammarEditorComponent } from 'app/inspecto/grammar';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GRAMMAR_DIALOG_SAMPLE_NAME, GrammarEditorDialog, GrammarEditorDialogData } from './grammar-editor.dialog';

const TABLE_PREVIEW: ParserPreview = {
    kind: 'table',
    columns: ['id'],
    rows: [{ id: '1' }],
    rowCount: 1,
    rejectedRows: 0,
};

/** A served catalog in the real shape (`GET /parsers`): a built-in + the tree-shaped XML plugin. */
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
        ingestable: true,
        ingesterClass: 'com.gamma.ingester.XmlRecordIngester',
        grammarSchema: [{ path: 'ingester_config.record_element', label: 'Record element', type: 'STRING' }],
    },
];

function saved(name: string, content: Record<string, unknown> = {}): ComponentDef {
    return { type: 'grammar', name, ref: `grammar/${name}`, content };
}

async function create(
    opts: {
        node?: AuthoredNode;
        grammars?: ComponentDef[];
        dialogOpen?: ReturnType<typeof vi.fn>;
        thread?: DefinitionStateService;
        preview?: ReturnType<typeof vi.fn>;
        pipeline?: string;
        write?: ReturnType<typeof vi.fn>;
    } = {},
) {
    const close = vi.fn();
    const components = {
        list: () => of(opts.grammars ?? []),
        create: vi.fn(() => of(saved('x'))),
        update: vi.fn((_t: string, id: string) => of(saved(id))),
    };
    const parsers = { list: vi.fn(() => of(CATALOG)), preview: opts.preview ?? vi.fn(() => of(TABLE_PREVIEW)) };
    const data: GrammarEditorDialogData = {
        node: opts.node ?? { id: 'parse', type: 'parser.dsv' },
        typeLabel: 'parser.dsv',
        categoryLabel: 'Parser',
        sampleThread: opts.thread,
        pipeline: opts.pipeline,
    };
    // The output-schema write Save makes: the re-read 404s (a new schema), then the write lands.
    const config = {
        read: vi.fn(() => throwError(() => ({ status: 404 }))),
        write: opts.write ?? vi.fn(() => of({ written: true, path: 'x.toon', name: 'x', etag: '"1"' })),
    };
    TestBed.configureTestingModule({
        imports: [GrammarEditorDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close, addPanelClass: vi.fn(), removePanelClass: vi.fn() } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: ComponentsService, useValue: components },
            { provide: ParsersService, useValue: parsers },
            { provide: ConfigService, useValue: config },
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
    return { fixture, c: fixture.componentInstance, close, components, parsers, editor, config };
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

    /**
     * S3 — the inverse of what this asserted before 2026-08-15. A bound node used to save straight
     * back to the shared component, changing every pipeline bound to it. It now MIGRATES to an
     * independent inline copy: nothing in the UI writes a `grammar` component in place any more.
     */
    it('a bound node loads the component block and MIGRATES it inline on save', async () => {
        const node: AuthoredNode = { id: 'parse', type: 'parser.dsv', use: 'grammar/cdr_csv' };
        const { c, close, components, editor, fixture } = await create({
            node,
            grammars: [saved('cdr_csv', { frontend: 'json' })],
        });
        fixture.detectChanges();

        expect(c.boundGrammarId()).toBe('cdr_csv');
        expect(editor.frontend()).toBe('json');

        c.save();
        expect(components.update).not.toHaveBeenCalled();
        const closed = close.mock.calls[0][0];
        expect(closed.node.use).toBeUndefined();
        expect(closed.node.config['parsing']).toEqual(expect.objectContaining({ frontend: 'json' }));
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

    /**
     * ⚠ Regression: a LEGACY FLAT component keeps its csv settings at top level, where they match no
     * `delimited__*` spec key — so the property sheet fell back to its DEFAULTS and this dialog showed
     * (and would have re-saved) `delimiter: ','` for a component storing `|`. Silent data loss that
     * looked like a successful load.
     */
    it('reads a legacy FLAT component without losing its stored csv settings', async () => {
        const node: AuthoredNode = { id: 'parse', type: 'parser.dsv', use: 'grammar/flat' };
        const { editor, fixture } = await create({
            node,
            grammars: [saved('flat', { delimiter: '|', has_header: false })],
        });
        fixture.detectChanges();
        expect(editor.frontend()).toBe('delimited');
        // R4 (2026-09-04): the engine defaults now materialize as real values beside the stored keys.
        expect(editor.value()['delimited']).toMatchObject({ delimiter: '|', has_header: false, quote: '"' });
    });

    // U4: the extract/name-step path is gone — the Grammar CSV export is the portable template.
    // Its round-trip is pinned framework-free in `inspecto/grammar/grammar-csv.spec.ts`.

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
        // `home: 'config'` is load-bearing, not incidental (SCHEMA-DIALOG-CREATE-HOME-1): this opener
        // drafts a pipeline SATELLITE schema, which a parse node references by bare `<name>.toon`. The
        // Components pane passes 'registry' instead, and a schema created under the wrong home lands in
        // a file the opener can never read back.
        expect(open).toHaveBeenCalledWith(
            expect.anything(),
            expect.objectContaining({ data: { sampleRows: [{ id: '1' }], home: 'config' } }),
        );

        c.onPreviewed({ kind: 'tree', recordCount: 1, nodes: [] });
        expect(c.previewRows()).toEqual([]);
    });

    /**
     * SAMPLE-FLOW (driven as a first-time builder, 2026-09-25): a sample pasted + test-parsed here was
     * lost on Save — the drawer's Sample card was empty and the Record Transformer had 0 fields until
     * the sample was pasted AGAIN. The dialog now reads and writes the TAB's thread.
     */
    describe('the tab sample thread', () => {
        const SAMPLE = 'id\n1\n';

        it('a table Test parse captures the sample AND the parsed rows into the thread', async () => {
            const thread = new DefinitionStateService();
            const { fixture, editor } = await create({ thread });
            editor.onSampleText(SAMPLE);
            editor.test();
            fixture.detectChanges();
            expect(thread.sample()).toEqual({ name: GRAMMAR_DIALOG_SAMPLE_NAME, text: SAMPLE });
            // What the Transformer's field list reads (upstreamSchemaColumns → parsedRows).
            expect(thread.parsedRows()).toEqual(TABLE_PREVIEW.rows);
            expect(thread.parsePreview()?.columns).toEqual(['id']);
        });

        it('Save carries an UNPARSED sample into the thread too', async () => {
            const thread = new DefinitionStateService();
            const { c, editor, close } = await create({ thread });
            editor.onSampleText(SAMPLE);
            c.save();
            expect(close).toHaveBeenCalled();
            expect(thread.sample()?.text).toBe(SAMPLE);
        });

        it('opens with the thread sample in its sample box, and re-saving it keeps the parsed rows', async () => {
            const thread = new DefinitionStateService();
            thread.captureSample('orders.csv', SAMPLE);
            thread.parsePreview.set({ frontend: 'delimited', columns: ['id'], rows: [{ id: '1' }], rowCount: 1, rejectedRows: 0 });
            const { c, editor, fixture } = await create({ thread });
            expect(editor.sampleText()).toBe(SAMPLE);
            expect((fixture.nativeElement as HTMLElement).querySelector('textarea')?.value).toBe(SAMPLE);
            c.save();
            // Same sample ⇒ not re-captured: the name and the downstream result survive.
            expect(thread.sample()?.name).toBe('orders.csv');
            expect(thread.parsedRows()).toEqual([{ id: '1' }]);
        });

        it('a FAILED re-parse clears the parsed hop rather than leaving stale columns downstream', async () => {
            const thread = new DefinitionStateService();
            thread.captureSample('orders.csv', SAMPLE);
            thread.parsePreview.set({ frontend: 'delimited', columns: ['id'], rows: [{ id: '1' }], rowCount: 1, rejectedRows: 0 });
            const preview = vi.fn(() => throwError(() => ({ status: 422, error: { error: { message: 'bad quote' } } })));
            const { editor } = await create({ thread, preview });
            editor.test();
            expect(thread.parsePreview()).toBeNull();
            expect(thread.parseError()).toBeTruthy();
        });
    });

    /**
     * 🔴 SCHEMA DEAD END (driving a new Pipeline `web_orders`, 2026-09-25): Test parse (6 cols) → Save
     * patched the node in memory ONLY. The stage strip kept "Schema: Not configured", Activate was
     * refused on Schema, and the drawer's Apply was disabled — no visible way to create the schema.
     * Save must now do what the drawer's Apply does: write `<pipeline>_schema` FIRST, then name it.
     */
    describe('Save writes the output schema (the same write as the Parse drawer Apply)', () => {
        const TYPED: ParserPreview = {
            kind: 'table',
            columns: ['order_id', 'amount'],
            rows: [{ order_id: '1', amount: '12.5' }],
            rowCount: 1,
            rejectedRows: 0,
            columnTypes: [
                { name: 'order_id', type: 'BIGINT' },
                { name: 'amount', type: 'DOUBLE' },
            ],
        };
        const NEW_NODE: AuthoredNode = { id: 'parse', type: 'parser', config: { parsing: { frontend: 'delimited' } } };

        it('after a table Test parse: writes <pipeline>_schema, then closes naming it in schema_file', async () => {
            const { c, editor, close, config } = await create({
                node: NEW_NODE,
                pipeline: 'web_orders',
                preview: vi.fn(() => of(TYPED)),
            });
            editor.onSampleText('order_id,amount\n1,12.5\n');
            editor.test();
            c.save();

            expect(config.write).toHaveBeenCalledTimes(1);
            const [type, draft, opts] = config.write.mock.calls[0] as unknown as [
                string,
                Record<string, Record<string, unknown>>,
                Record<string, unknown>,
            ];
            expect(type).toBe('schema');
            // SCHEMA-FILE-NAME-1: the FILE is the one the node will name; the declared names are the pipeline's.
            expect(opts).toMatchObject({ overwrite: true, file: 'web_orders_schema' });
            expect(draft['raw']['name']).toBe('web_orders');
            expect(draft['raw']['types']).toBe('auto');
            expect(draft['raw']['fields']).toEqual([
                { name: 'ORDER_ID', selector: '0', type: 'BIGINT' },
                { name: 'AMOUNT', selector: '1', type: 'DOUBLE' },
            ]);
            expect(draft['mapping']['canonicalName']).toBe('web_orders');
            const closed = close.mock.calls[0][0];
            expect(closed.node.config['schema_file']).toBe('web_orders_schema.toon');
        });

        it('a failed schema write keeps the dialog open and says why — the node never names a missing file', async () => {
            const write = vi.fn(() => throwError(() => ({ status: 500, error: { error: { message: 'disk full' } } })));
            const { c, editor, close, fixture } = await create({
                node: NEW_NODE,
                pipeline: 'web_orders',
                preview: vi.fn(() => of(TYPED)),
                write,
            });
            editor.onSampleText('order_id,amount\n1,12.5\n');
            editor.test();
            c.save();
            fixture.detectChanges();

            expect(close).not.toHaveBeenCalled();
            expect((fixture.nativeElement as HTMLElement).textContent).toContain('The output schema was not saved');
        });

        it('a node that already names a schema keeps it: no write, even after a Test parse', async () => {
            const node: AuthoredNode = { ...NEW_NODE, config: { ...NEW_NODE.config, schema_file: 'web_orders_schema.toon' } };
            const { c, editor, close, config } = await create({ node, pipeline: 'web_orders', preview: vi.fn(() => of(TYPED)) });
            editor.onSampleText('order_id,amount\n1,12.5\n');
            editor.test();
            c.save();
            expect(config.write).not.toHaveBeenCalled();
            expect(close.mock.calls[0][0].node.config['schema_file']).toBe('web_orders_schema.toon');
        });

        it('with no Test parse there is nothing to derive from — the Grammar saves alone', async () => {
            const { c, close, config } = await create({ node: NEW_NODE, pipeline: 'web_orders' });
            c.save();
            expect(config.write).not.toHaveBeenCalled();
            expect(close.mock.calls[0][0].node.config['schema_file']).toBeUndefined();
        });
    });

    it('has no a11y violations', async () => {
        const { fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
