import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ParsingPreview, SchemaPreview, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingSchemaMappingPaneComponent } from './schema-mapping-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const CONVENTION_PATH = 'spaces/demo/config/orders_feed_schema.toon';
const WRITE_OK = (type: string) => ({
    type,
    written: true,
    path: 'x.toon',
    name: 'x',
    bytes: 1,
    overwritten: false,
    findings: [],
});
const SCHEMA_PREVIEW: SchemaPreview = { columns: ['ORDER_ID'], okCount: 2, rejectedCount: 0, rejectedRows: [] };

function delimitedPreview(): ParsingPreview {
    return {
        frontend: 'delimited',
        columns: ['ORDER_ID', 'QUANTITY'],
        rowCount: 2,
        rows: [
            { ORDER_ID: '1001', QUANTITY: '3' },
            { ORDER_ID: '1002', QUANTITY: '5' },
        ],
        rejectedRows: 0,
    };
}

/** A CDR-scale sample: n text columns (col_0 … col_n-1), one row, all VARCHAR. */
function widePreview(n: number): ParsingPreview {
    const columns = Array.from({ length: n }, (_, i) => `col_${i}`);
    const row: Record<string, unknown> = {};
    for (const c of columns) row[c] = 'x';
    return { frontend: 'json', columns, rowCount: 1, rows: [row], rejectedRows: 0 };
}

async function create(
    config: Record<string, unknown>,
    api: Partial<ConfigService> = {},
    parsePreview: ParsingPreview | null = delimitedPreview(),
) {
    TestBed.configureTestingModule({
        imports: [OnboardingSchemaMappingPaneComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            OnboardingStateService,
            {
                provide: ConfigService,
                useValue: {
                    write: vi.fn((type: string) => of(WRITE_OK(type))), // companion schema file
                    patch: vi.fn((type: string) => of(WRITE_OK(type))), // pipeline stage save
                    read: vi.fn(() => throwError(() => ({ status: 404 }))),
                    previewSchema: vi.fn(() => of(SCHEMA_PREVIEW)),
                    ...api,
                },
            },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'demo' } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    if (parsePreview) state.parsePreview.set(parsePreview);
    await TestBed.compileComponents(); // the shared data-table pulls in @defer-loaded blocks
    const fixture = TestBed.createComponent(OnboardingSchemaMappingPaneComponent);
    fixture.detectChanges();
    return { fixture, state, api: TestBed.inject(ConfigService) };
}

describe('OnboardingSchemaMappingPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('derives fields from the parsed sample using the index selector for delimited', async () => {
        const { fixture } = await create({ name: 'orders_feed' });
        const c = fixture.componentInstance;
        expect(c.fieldRows.length).toBe(2);
        expect(c.fieldRows.at(0).get('name')?.value).toBe('ORDER_ID');
        expect(c.fieldRows.at(0).get('selector')?.value).toBe('0');
        expect(c.fieldRows.at(1).get('selector')?.value).toBe('1');
    });

    it('prefills suggested types from the sample values and shows the suggested-types note', async () => {
        const { fixture } = await create({ name: 'orders_feed' });
        const c = fixture.componentInstance;
        expect(c.fieldRows.at(0).get('type')?.value).toBe('DOUBLE'); // ORDER_ID: '1001', '1002'
        expect(c.fieldRows.at(1).get('type')?.value).toBe('DOUBLE'); // QUANTITY: '3', '5'
        expect(c.typesSuggested()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('suggested from your parsed sample');
    });

    it('stays VARCHAR with no note when nothing is confidently typed', async () => {
        const preview: ParsingPreview = {
            frontend: 'json',
            columns: ['label'],
            rowCount: 1,
            rows: [{ label: 'ab' }],
            rejectedRows: 0,
        };
        const { fixture } = await create({ name: 'orders_feed' }, {}, preview);
        const c = fixture.componentInstance;
        expect(c.fieldRows.at(0).get('type')?.value).toBe('VARCHAR');
        expect(c.typesSuggested()).toBe(false);
    });

    it('derives the verbatim key as the selector for a json/text_regex sample', async () => {
        const preview: ParsingPreview = {
            frontend: 'json',
            columns: ['orderId'],
            rowCount: 1,
            rows: [{ orderId: '1' }],
            rejectedRows: 0,
        };
        const { fixture } = await create({ name: 'orders_feed' }, {}, preview);
        const c = fixture.componentInstance;
        expect(c.fieldRows.at(0).get('selector')?.value).toBe('orderId');
        expect(c.fieldRows.at(0).get('name')?.value).toBe('ORDERID'); // sanitized identifier, not the raw key
    });

    it('shows a foreign-managed banner instead of the editor for a schema_file outside its convention', async () => {
        const { fixture } = await create({
            name: 'orders_feed',
            processing: { schema_file: 'spaces/demo/config/hand_authored.toon' },
        });
        expect(fixture.componentInstance.foreignManaged()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Schema managed elsewhere');
    });

    it('resumes by reading back a previously saved schema, pristine', async () => {
        const read = vi.fn(() =>
            of({
                type: 'schema',
                name: 'orders_feed_schema',
                path: 'orders_feed_schema.toon',
                config: {
                    partitionKey: 'ORDER_DATE',
                    raw: {
                        name: 'orders_feed_schema',
                        format: 'CSV',
                        fields: [{ name: 'ORDER_ID', selector: '0', type: 'VARCHAR' }],
                    },
                },
            }),
        );
        const { fixture } = await create(
            { name: 'orders_feed', processing: { schema_file: CONVENTION_PATH } },
            { read },
        );
        const c = fixture.componentInstance;
        expect(c.fieldRows.length).toBe(1);
        expect(c.fieldRows.at(0).get('name')?.value).toBe('ORDER_ID');
        expect(c.partitionKeyControl.value).toBe('ORDER_DATE');
        expect(c.fieldsForm.dirty).toBe(false);
    });

    it('validate types casts only the included rows against the parsed rows', async () => {
        const previewSchema = vi.fn((_config: Record<string, unknown>, _rows: Record<string, unknown>[]) =>
            of(SCHEMA_PREVIEW),
        );
        const { fixture, state } = await create({ name: 'orders_feed' }, { previewSchema });
        const c = fixture.componentInstance;
        c.fieldRows.at(1).get('include')?.setValue(false); // drop QUANTITY
        c.testTypes();
        const [content, rows] = previewSchema.mock.calls[0] as [{ raw: { fields: { name: string }[] } }, unknown[]];
        expect(content.raw.fields.map((f) => f.name)).toEqual(['ORDER_ID']);
        expect(rows).toEqual(delimitedPreview().rows);
        expect(state.schemaPreview()).toEqual(SCHEMA_PREVIEW);
    });

    it('save writes the schema config then links it into the pipeline draft', async () => {
        // Two persistence paths: the companion schema FILE goes through /config/write; the
        // pipeline draft's processing.schema_file link is a stage save → /config/patch.
        const write = vi.fn((type: string, _config: Record<string, unknown>, _opts?: unknown) => of(WRITE_OK(type)));
        const patch = vi.fn((type: string, _name: string, _patch: Record<string, unknown>) => of(WRITE_OK(type)));
        const { fixture } = await create({ name: 'orders_feed', dirs: { poll: 'in' } }, { write, patch });
        fixture.componentInstance.save();
        expect(write).toHaveBeenCalledTimes(1);
        const [schemaType, schemaDraft] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect(schemaType).toBe('schema');
        expect((schemaDraft['raw'] as Record<string, unknown>)['name']).toBe('orders_feed_schema');
        expect((schemaDraft['mapping'] as Record<string, unknown>)['rules']).toHaveLength(2);
        expect(patch).toHaveBeenCalledTimes(1);
        const [pipelineType, , pipelinePatch] = patch.mock.calls[0] as [string, string, Record<string, unknown>];
        expect(pipelineType).toBe('pipeline');
        expect((pipelinePatch['processing'] as Record<string, unknown>)['schema_file']).toBe(CONVENTION_PATH);
    });

    it('blocks save on a duplicate field name', async () => {
        const write = vi.fn((type: string) => of(WRITE_OK(type)));
        const { fixture } = await create({ name: 'orders_feed' }, { write });
        const c = fixture.componentInstance;
        c.fieldRows.at(1).get('name')?.setValue('ORDER_ID');
        c.save();
        expect(write).not.toHaveBeenCalled();
        expect(TOASTR.warning).toHaveBeenCalled();
    });

    it('shows the honest full-replace load-policy note when serving the Reference keys stage', async () => {
        const { fixture, state } = await create({ name: 'region_dim', produces: 'reference' });
        expect(state.kind()).toBe('reference');
        expect(fixture.nativeElement.textContent).toContain('Load policy: full replace');
    });

    it('renders only one page of a wide sample and pages through the rest', async () => {
        const { fixture } = await create({ name: 'cdr_feed' }, {}, widePreview(120));
        const c = fixture.componentInstance;
        expect(c.totalCount()).toBe(120);
        expect(c.pagedEntries().length).toBe(50); // default page size, not 120 DOM rows
        expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(50);
        c.onPage({ pageIndex: 2, pageSize: 50, length: 120, previousPageIndex: 0 });
        expect(c.pagedEntries().length).toBe(20);
        expect(c.pagedEntries()[0].index).toBe(100);
    });

    it('search filters by name or source across all pages', async () => {
        const { fixture } = await create({ name: 'cdr_feed' }, {}, widePreview(30));
        const c = fixture.componentInstance;
        c.setSearch('col_1'); // col_1 + col_10..col_19
        expect(c.filteredEntries().length).toBe(11);
        expect(c.pageIndex()).toBe(0); // search resets paging
        c.setSearch('no_such_column');
        expect(c.filteredEntries().length).toBe(0);
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('No fields match');
    });

    it('type filter narrows to fields of the chosen type', async () => {
        const { fixture } = await create({ name: 'cdr_feed' }, {}, widePreview(10));
        const c = fixture.componentInstance;
        c.fieldRows.at(3).get('type')?.setValue('DATE');
        c.setTypeFilter('DATE');
        expect(c.filteredEntries().length).toBe(1);
        expect(c.filteredEntries()[0].index).toBe(3);
    });

    it('sorts by name and flips direction on a second click', async () => {
        const preview: ParsingPreview = {
            frontend: 'json',
            columns: ['banana', 'apple'],
            rowCount: 1,
            rows: [{ banana: 'x', apple: 'y' }],
            rejectedRows: 0,
        };
        const { fixture } = await create({ name: 'orders_feed' }, {}, preview);
        const c = fixture.componentInstance;
        expect(c.pagedEntries()[0].index).toBe(0); // source order first
        c.sortBy('name');
        expect(c.pagedEntries()[0].group.get('name')?.value).toBe('APPLE');
        expect(c.ariaSort('name')).toBe('ascending');
        c.sortBy('name');
        expect(c.pagedEntries()[0].group.get('name')?.value).toBe('BANANA');
        expect(c.ariaSort('name')).toBe('descending');
    });

    it('master checkbox includes/excludes exactly the filtered rows', async () => {
        const { fixture } = await create({ name: 'cdr_feed' }, {}, widePreview(20));
        const c = fixture.componentInstance;
        c.setSearch('col_1'); // 11 of 20
        c.toggleAllVisible(false);
        expect(c.includedNames().length).toBe(9); // only the matching rows were excluded
        expect(c.visibleIncludeState()).toBe('none');
        c.setSearch('');
        expect(c.visibleIncludeState()).toBe('some');
        expect(c.fieldsForm.dirty).toBe(true);
    });

    it('save reveals the page holding an invalid row hidden by search + paging', async () => {
        const write = vi.fn((type: string) => of(WRITE_OK(type)));
        const { fixture } = await create({ name: 'cdr_feed' }, { write }, widePreview(120));
        const c = fixture.componentInstance;
        c.fieldRows.at(100).get('name')?.setValue('9bad'); // starts with a digit → invalid
        c.setSearch('col_2'); // the invalid row is now hidden entirely
        c.save();
        expect(write).not.toHaveBeenCalled();
        expect(c.search()).toBe(''); // filters cleared…
        expect(c.pageIndex()).toBe(2); // …and jumped to the page holding row 101
        expect(String(TOASTR.warning.mock.lastCall?.[0])).toContain('101');
    });

    it('renders each type with its data-format icon', async () => {
        const { fixture } = await create({ name: 'orders_feed' });
        const c = fixture.componentInstance;
        expect(c.typeIcon('VARCHAR')).toContain('bars-3-bottom-left');
        expect(c.typeIcon('DOUBLE')).toContain('hashtag');
        expect(c.typeIcon('DATE')).toContain('calendar');
        expect(c.typeIcon('TIMESTAMP')).toContain('clock');
        expect(c.typeIcon(null)).toContain('bars-3-bottom-left'); // unknown falls back to text
        // Every offered type has its own icon (a new type must bring one, not inherit text's).
        const icons = ['VARCHAR', 'DOUBLE', 'DATE', 'TIMESTAMP'].map((t) => c.typeIcon(t));
        expect(new Set(icons).size).toBe(4);
        // NOTE: rendered <mat-icon> presence is NOT asserted here — jsdom has no icon sprite, the
        // registry error aborts the trigger view, and the count reads 0 even though the browser
        // renders it. The visual check lives in the preview, not this spec.
    });

    it('has no a11y violations', async () => {
        const { fixture } = await create({ name: 'orders_feed' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
