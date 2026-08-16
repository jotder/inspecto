import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ParsingPreview, SchemaPreview, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingSchemaMappingPaneComponent } from './schema-mapping-pane.component';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';

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
    kind: 'stream' | 'reference' = 'stream',
) {
    TestBed.configureTestingModule({
        imports: [OnboardingSchemaMappingPaneComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            DefinitionStateService,
            {
                provide: ConfigService,
                useValue: {
                    write: vi.fn((type: string) => of(WRITE_OK(type))), // companion schema file
                    read: vi.fn(() => throwError(() => ({ status: 404 }))),
                    previewSchema: vi.fn(() => of(SCHEMA_PREVIEW)),
                    // D4: types now come from the SERVER's inference. This stub mirrors
                    // `SchemaSuggest`'s vote closely enough to exercise the narrowing — it returns
                    // BIGINT for all-integer columns, which the grid narrows to DOUBLE because
                    // TransformCompiler.direct() has no integer cast.
                    suggestSchema: vi.fn((rows: Record<string, unknown>[]) => {
                        const columns = [...new Set(rows.flatMap((r) => Object.keys(r)))];
                        return of({
                            fields: columns.map((name) => {
                                const values = rows
                                    .map((r) => String(r[name] ?? '').trim())
                                    .filter((v) => v !== '');
                                const allInt = values.length > 0 && values.every((v) => /^[+-]?\d+$/.test(v));
                                return { name, selector: name, type: allInt ? 'BIGINT' : 'VARCHAR' };
                            }),
                            mapping: { rules: [] },
                        });
                    }),
                    ...api,
                },
            },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'demo' } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const definition = TestBed.inject(DefinitionStateService);
    if (parsePreview) definition.parsePreview.set(parsePreview);
    await TestBed.compileComponents(); // the shared data-table pulls in @defer-loaded blocks
    const fixture = TestBed.createComponent(OnboardingSchemaMappingPaneComponent);
    fixture.componentRef.setInput('config', config);
    fixture.componentRef.setInput('kind', kind);
    // The pure contract is observed through the outputs — the HOST persists the emitted block.
    const applied: Record<string, unknown>[] = [];
    const dirty: boolean[] = [];
    fixture.componentInstance.applied.subscribe((v) => applied.push(v));
    fixture.componentInstance.dirtyChange.subscribe((v) => dirty.push(v));
    fixture.detectChanges();
    return { fixture, definition, applied, dirty, api: TestBed.inject(ConfigService) };
}

describe('OnboardingSchemaMappingPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('derives fields from the parsed sample using the index selector for delimited', async () => {
        const { fixture } = await create({ name: 'orders_feed' });
        const c = fixture.componentInstance;
        expect(c.grid()!.fieldRows.length).toBe(2);
        expect(c.grid()!.fieldRows.at(0).get('name')?.value).toBe('ORDER_ID');
        expect(c.grid()!.fieldRows.at(0).get('selector')?.value).toBe('0');
        expect(c.grid()!.fieldRows.at(1).get('selector')?.value).toBe('1');
    });

    it('prefills suggested types from the sample values and shows the suggested-types note', async () => {
        const { fixture } = await create({ name: 'orders_feed' });
        const c = fixture.componentInstance;
        expect(c.grid()!.fieldRows.at(0).get('type')?.value).toBe('DOUBLE'); // ORDER_ID: '1001', '1002'
        expect(c.grid()!.fieldRows.at(1).get('type')?.value).toBe('DOUBLE'); // QUANTITY: '3', '5'
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
        expect(c.grid()!.fieldRows.at(0).get('type')?.value).toBe('VARCHAR');
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
        expect(c.grid()!.fieldRows.at(0).get('selector')?.value).toBe('orderId');
        expect(c.grid()!.fieldRows.at(0).get('name')?.value).toBe('ORDERID'); // sanitized identifier, not the raw key
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
        expect(c.grid()!.fieldRows.length).toBe(1);
        expect(c.grid()!.fieldRows.at(0).get('name')?.value).toBe('ORDER_ID');
        expect(c.partitionKeyControl.value).toBe('ORDER_DATE');
        expect(c.grid()!.form.dirty).toBe(false);
    });

    it('validate types casts only the included rows against the parsed rows', async () => {
        const previewSchema = vi.fn((_config: Record<string, unknown>, _rows: Record<string, unknown>[]) =>
            of(SCHEMA_PREVIEW),
        );
        const { fixture, definition } = await create({ name: 'orders_feed' }, { previewSchema });
        const c = fixture.componentInstance;
        c.grid()!.fieldRows.at(1).get('include')?.setValue(false); // drop QUANTITY
        c.testTypes();
        const [content, rows] = previewSchema.mock.calls[0] as [{ raw: { fields: { name: string }[] } }, unknown[]];
        expect(content.raw.fields.map((f) => f.name)).toEqual(['ORDER_ID']);
        expect(rows).toEqual(delimitedPreview().rows);
        expect(definition.schemaPreview()).toEqual(SCHEMA_PREVIEW);
    });

    it('writes the companion schema config, THEN emits the block naming it', async () => {
        // Two persistence paths, one of them no longer the pane's: the companion schema FILE goes
        // through /config/write here; the pipeline draft's processing.schema_file link is emitted
        // for the HOST to save (D2) — and only after that write lands, so the draft can never name
        // a file that does not exist.
        const order: string[] = [];
        const write = vi.fn((type: string, _config: Record<string, unknown>, _opts?: unknown) => {
            order.push('write');
            return of(WRITE_OK(type));
        });
        const { fixture, applied } = await create({ name: 'orders_feed', dirs: { poll: 'in' } }, { write });
        fixture.componentInstance.applied.subscribe(() => order.push('applied'));
        fixture.componentInstance.save();
        expect(write).toHaveBeenCalledTimes(1);
        const [schemaType, schemaDraft] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect(schemaType).toBe('schema');
        expect((schemaDraft['raw'] as Record<string, unknown>)['name']).toBe('orders_feed_schema');
        expect((schemaDraft['mapping'] as Record<string, unknown>)['rules']).toHaveLength(2);
        expect(order).toEqual(['write', 'applied']);
        expect(applied).toEqual([{ schema_file: CONVENTION_PATH }]);
    });

    it('emits nothing when the companion schema write fails', async () => {
        TOASTR.error.mockClear();
        const write = vi.fn(() => throwError(() => ({ status: 500 })));
        const { fixture, applied } = await create({ name: 'orders_feed' }, { write });
        fixture.componentInstance.save();
        expect(applied).toHaveLength(0);
        expect(TOASTR.error).toHaveBeenCalled();
        expect(fixture.componentInstance.writing()).toBe(false);
    });

    it('re-seeding the config input returns the pane to pristine; a failed save leaves it dirty', async () => {
        const { fixture, dirty } = await create({ name: 'orders_feed' });
        fixture.componentInstance.grid()!.fieldRows.at(0).get('name')?.setValue('ORDER_REF');
        fixture.componentInstance.grid()!.fieldRows.at(0).get('name')?.markAsDirty();
        fixture.componentInstance.onInteraction();
        expect(dirty).toEqual([true]);

        // A FAILED save never advances the host's config — nothing re-seeds, the guard still fires.
        fixture.detectChanges();
        expect(dirty).toEqual([true]);

        // A SUCCESSFUL one hands back the newly-persisted draft.
        fixture.componentRef.setInput('config', {
            name: 'orders_feed',
            processing: { schema_file: CONVENTION_PATH },
        });
        fixture.detectChanges();
        expect(dirty).toEqual([true, false]);
    });

    it('blocks save on a duplicate field name', async () => {
        const write = vi.fn((type: string) => of(WRITE_OK(type)));
        const { fixture } = await create({ name: 'orders_feed' }, { write });
        const c = fixture.componentInstance;
        c.grid()!.fieldRows.at(1).get('name')?.setValue('ORDER_ID');
        c.save();
        expect(write).not.toHaveBeenCalled();
        expect(TOASTR.warning).toHaveBeenCalled();
    });

    it('shows the honest full-replace load-policy note when serving the Reference keys stage', async () => {
        const { fixture } = await create(
            { name: 'region_dim', produces: 'reference' },
            {},
            delimitedPreview(),
            'reference',
        );
        expect(fixture.nativeElement.textContent).toContain('Load policy: full replace');
    });

    it('save is blocked by an invalid row the grid reveals, and the reason reaches the operator', async () => {
        const write = vi.fn((type: string) => of(WRITE_OK(type)));
        const { fixture } = await create({ name: 'cdr_feed' }, { write }, widePreview(120));
        const c = fixture.componentInstance;
        // Reach through the grid: the row window and its validation are the shared editor's job now
        // (P4-2a-i), and its own spec pins the reveal. What this pane owes is refusing to write.
        c.grid()!.fieldRows.at(100).get('name')?.setValue('9bad'); // starts with a digit → invalid
        c.save();
        expect(write).not.toHaveBeenCalled();
        expect(String(TOASTR.warning.mock.lastCall?.[0])).toContain('101');
    });


    it('has no a11y violations', async () => {
        const { fixture } = await create({ name: 'orders_feed' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
