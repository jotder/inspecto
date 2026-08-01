import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import {
    AuthoredNode,
    CatalogService,
    ComponentDef,
    ComponentsService,
    ConfigService,
    LensService,
    MetadataNode,
    PipelinesService,
} from 'app/inspecto/api';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { EnrichmentEditorComponent } from 'app/inspecto/enrichment/enrichment-editor.component';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NodeConfigData, NodeConfigDialog } from './node-config.dialog';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

const GRAMMARS: ComponentDef[] = [
    { type: 'grammar', name: 'cdr_csv', ref: 'grammar/cdr_csv', content: { delimiter: ',' } },
    { type: 'grammar', name: 'pipe_delimited', ref: 'grammar/pipe_delimited', content: { delimiter: '|' } },
];

const PRODUCED_REF: MetadataNode = {
    id: 'ref:region_dim', kind: 'REFERENCE_DATASET', label: 'REGION_DIM',
    attrs: { pipeline: 'region_dim', active: true },
} as MetadataNode;

async function create(data: Partial<NodeConfigData> = {}, api: Partial<ConfigService> = {}) {
    TestBed.configureTestingModule({
        imports: [NodeConfigDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: {
                    node: { id: 'parse', type: 'parser' },
                    typeLabel: 'parser',
                    categoryLabel: 'Parser',
                    bindKind: 'grammar',
                    ...data,
                },
            },
            { provide: PipelinesService, useValue: { testNode: () => of({}) } },
            { provide: ComponentsService, useValue: { list: () => of(GRAMMARS) } },
            {
                provide: ConfigService,
                useValue: {
                    read: vi.fn(() => throwError(() => ({ status: 404 }))),
                    write: vi.fn((type: string, cfg: Record<string, unknown>) =>
                        of({ type, written: true, path: `${String(cfg['name'])}.toon`, name: String(cfg['name']), bytes: 1, overwritten: false, findings: [] })),
                    registerEnrichment: vi.fn(() => of({ registered: true, name: 'x_enrich', path: 'x_enrich.toon', findings: [] })),
                    ...api,
                },
            },
            { provide: CatalogService, useValue: { references: vi.fn(() => of([PRODUCED_REF])) } },
            { provide: LensService, useValue: { canAuthorWorkbench: () => true } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    await TestBed.compileComponents(); // the shared enrichment editor @defer-loads CodeMirror
    const fixture = TestBed.createComponent(NodeConfigDialog);
    fixture.detectChanges();
    return fixture;
}

/** The shared enrichment editor hosted by the dialog (enrichment nodes only). */
function editor(fixture: ComponentFixture<NodeConfigDialog>): EnrichmentEditorComponent {
    return fixture.debugElement.query(By.directive(EnrichmentEditorComponent)).componentInstance;
}

describe('NodeConfigDialog', () => {
    beforeEach(() => Object.values(TOASTR).forEach((f) => f.mockClear()));

    it('loads existing components of the bound kind for the picker', async () => {
        const c = (await create()).componentInstance;
        expect(c.componentOptions().map((o) => o.name)).toEqual(['cdr_csv', 'pipe_delimited']);
        expect(c.bindLabel).toBe('Grammar');
    });

    it('binds and reads back the chosen component as a <kind>/<id> ref', async () => {
        const c = (await create()).componentInstance;
        c.selectComponent('cdr_csv');
        expect(c.form.value.use).toBe('grammar/cdr_csv');
        expect(c.selectedComponentId()).toBe('cdr_csv');
        c.selectComponent(null);
        expect(c.form.value.use).toBe('');
        expect(c.selectedComponentId()).toBeNull();
    });

    it('falls back to free-text use when no kind is bound', async () => {
        const c = (await create({ bindKind: null })).componentInstance;
        expect(c.componentOptions()).toEqual([]);
    });

    it('uses the free-form config editor for a type with no schema (parser)', async () => {
        const c = (await create()).componentInstance;
        expect(c.specs()).toEqual([]);
        expect(c.freeFormOpen()).toBe(true); // free-form is the primary surface
    });

    it('renders the schema-form for a known type and splits config into schema + free-form', async () => {
        const fixture = await create({
            node: { id: 'w', type: 'sink.persistent', config: { format: 'CSV', compression: 'gzip', custom_flag: 'x' } },
            typeLabel: 'sink.persistent', categoryLabel: 'Sink', bindKind: null,
        });
        const c = fixture.componentInstance;
        expect(c.specs().map((s) => s.key)).toContain('format');
        // schema-known keys seed the schema-form; the unknown key falls to the free-form editor
        expect(c.schemaInitial).toMatchObject({ format: 'CSV', compression: 'gzip' });
        expect(c.schemaInitial['custom_flag']).toBeUndefined();
        expect(c.configRows.length).toBe(1);
        expect(c.freeFormOpen()).toBe(true); // an extra key is present ⇒ shown
    });

    it('merges schema-form values with free-form rows on save', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const fixture = await create({
            node: { id: 'w', type: 'sink.file', config: { format: 'CSV', extra: '1' } },
            typeLabel: 'sink.file', categoryLabel: 'Writer', bindKind: null,
        });
        const c = fixture.componentInstance;
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        fixture.detectChanges(); // instantiate the schema-form ViewChild
        c.save();
        expect(closed?.node.config).toMatchObject({ format: 'CSV', extra: '1' });
    });

    // ── enrichment nodes (W4b): the dialog authors the REAL companion through the shared editor ──

    it('renders the shared enrichment editor + wiring form for an enrichment node', async () => {
        const fixture = await create({
            node: { id: 'enrich1', type: 'enrichment' },
            typeLabel: 'enrichment', categoryLabel: 'Transform', bindKind: null,
        });
        const c = fixture.componentInstance;
        expect(c.isEnrichment).toBe(true);
        expect(editor(fixture)).toBeTruthy();
        expect(c.enrichName.value).toBe('enrich1');
        expect(c.freeFormOpen()).toBe(false); // the editor is the primary surface, not the key/value grid
    });

    it('save writes the companion, registers it, and closes bound by reference — config stays unmirrored', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const write = vi.fn((type: string, cfg: Record<string, unknown>) =>
            of({ type, written: true, path: `${String(cfg['name'])}.toon`, name: String(cfg['name']), bytes: 1, overwritten: false, findings: [] }));
        const registerEnrichment = vi.fn(() => of({ registered: true, name: 'enrich1_enrich', path: 'enrich1.toon', findings: [] }));
        const fixture = await create(
            { node: { id: 'enrich1', type: 'enrichment' }, typeLabel: 'enrichment', categoryLabel: 'Transform', bindKind: null },
            { write, registerEnrichment },
        );
        const c = fixture.componentInstance;
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        const ed = editor(fixture);
        ed.onSqlChange('SELECT * FROM input');
        const wiring = fixture.debugElement.query(By.directive(InspectoSchemaFormComponent))
            .componentInstance as InspectoSchemaFormComponent;
        wiring.form.get('input__database')?.setValue('spaces/demo/data/orders/database');
        wiring.form.get('output__database')?.setValue('spaces/demo/data/enriched/enrich1');
        c.save();

        const [type, draft] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect(type).toBe('enrichment');
        expect(draft['name']).toBe('enrich1');
        expect(draft['transform']).toBe('SELECT * FROM input');
        expect((draft['input'] as Record<string, unknown>)['database']).toBe('spaces/demo/data/orders/database');
        expect(registerEnrichment).toHaveBeenCalledWith('enrich1.toon');
        // The node binds by reference — the companion file is the single truth, never mirrored.
        expect(closed?.node.use).toBe('enrichment/enrich1');
        expect(closed?.node.config).toBeUndefined();
    });

    it('hydrates a bound companion and preserves unmodeled keys (partitions) through a save', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const read = vi.fn(() => of({
            type: 'enrichment', name: 'orders_enrich', path: 'orders_enrich.toon',
            config: {
                name: 'orders_enrich',
                input: { database: 'in/db', format: 'PARQUET', partitions: ['year', 'month'] },
                output: { database: 'out/db', partitions: ['year'] },
                transform: 'SELECT 1 FROM input',
                triggers: { on_pipeline: 'orders' },
            },
        }));
        const write = vi.fn((type: string, cfg: Record<string, unknown>) =>
            of({ type, written: true, path: `${String(cfg['name'])}.toon`, name: String(cfg['name']), bytes: 1, overwritten: false, findings: [] }));
        const fixture = await create(
            {
                node: { id: 'enrich1', type: 'enrichment', use: 'enrichment/orders_enrich' },
                typeLabel: 'enrichment', categoryLabel: 'Transform', bindKind: null,
            },
            { read, write },
        );
        const c = fixture.componentInstance;
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        fixture.detectChanges(); // the async read landed; the hydrate effect sees the editor
        expect(read).toHaveBeenCalledWith('enrichment', 'orders_enrich');
        expect(c.enrichName.value).toBe('orders_enrich');
        expect(editor(fixture).sql()).toBe('SELECT 1 FROM input');

        c.save();
        const [, draft] = write.mock.calls[0] as [string, Record<string, unknown>];
        // The wiring form owns database/format/compression/triggers — partitions travel verbatim.
        expect((draft['input'] as Record<string, unknown>)['partitions']).toEqual(['year', 'month']);
        expect((draft['output'] as Record<string, unknown>)['partitions']).toEqual(['year']);
        expect((draft['triggers'] as Record<string, unknown>)['on_pipeline']).toBe('orders');
        expect(closed?.node.use).toBe('enrichment/orders_enrich');
    });

    it('refuses to overwrite a hand-authored transform_file config', async () => {
        const read = vi.fn(() => of({
            type: 'enrichment', name: 'x_enrich', path: 'x_enrich.toon',
            config: { name: 'x_enrich', input: { database: 'a' }, output: { database: 'b' }, transform_file: 't.sql' },
        }));
        const write = vi.fn();
        const fixture = await create(
            {
                node: { id: 'e', type: 'enrichment', use: 'enrichment/x_enrich' },
                typeLabel: 'enrichment', categoryLabel: 'Transform', bindKind: null,
            },
            { read, write },
        );
        fixture.detectChanges();
        expect(fixture.componentInstance.enrichUneditable()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('transform_file');
        fixture.componentInstance.save();
        expect(write).not.toHaveBeenCalled(); // closing is allowed; writing is not
    });

    it('has no a11y violations (free-form type)', async () => {
        await expectNoA11yViolations((await create()).nativeElement);
    });

    it('has no a11y violations (schema-backed type)', async () => {
        const fixture = await create({
            node: { id: 'w', type: 'sink.file' }, typeLabel: 'sink.file', categoryLabel: 'Writer', bindKind: null,
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('has no a11y violations (enrichment node)', async () => {
        const fixture = await create({
            node: { id: 'e', type: 'enrichment' }, typeLabel: 'enrichment', categoryLabel: 'Transform', bindKind: null,
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
