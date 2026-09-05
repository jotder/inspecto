import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import {
    AuthoredNode,
    CatalogService,
    ComponentsService,
    ConfigService,
    LensService,
    MetadataNode,
    SpacesService,
} from 'app/inspecto/api';
import type { AttributeSpec } from 'app/inspecto/component-model';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { EnrichmentEditorComponent } from 'app/inspecto/enrichment/enrichment-editor.component';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { EnrichmentHostPipeline, PipelineConfigDefinitionComponent } from './pipeline-config-definition.component';
import { PipelineExtraConfigComponent } from './pipeline-extra-config.component';

/**
 * S2 — these are the config-surface cases of the retired `node-config.dialog.spec.ts`, RE-PINNED on the
 * drawer pane that replaced it. ⚠ Its config-key list (the local-table fallback assertion) is one of the
 * FIVE pinned OUTPUT touchpoints; it moves with the pane, it does not silently drop.
 *
 * <p>Two families of the dialog's cases are deliberately absent rather than ported:
 * <ul>
 *   <li>the **acquisition** cases — already re-pinned on `pipeline-collection-definition.component.spec.ts`
 *       when the Collector pane took that path (nesting, unmodeled sub-keys, the Connection binding);</li>
 *   <li>the **component-binding** cases (picker · "New &lt;kind&gt;" · "Test &lt;component&gt;…" · the
 *       free-text `use` box) — that half was dead in production and is deleted with the dialog (D5).</li>
 * </ul>
 */

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

const PRODUCED_REF: MetadataNode = {
    id: 'ref:region_dim',
    kind: 'REFERENCE_DATASET',
    label: 'REGION_DIM',
    attrs: { pipeline: 'region_dim', active: true },
} as MetadataNode;

interface PaneInputs {
    node: AuthoredNode;
    attributes?: AttributeSpec[];
    enrichmentHost?: EnrichmentHostPipeline;
    sampleRows?: Record<string, unknown>[];
    configSubdir?: string;
    pipelineName?: string;
}

async function create(inputs: PaneInputs, api: Partial<ConfigService> = {}) {
    TestBed.configureTestingModule({
        imports: [PipelineConfigDefinitionComponent],
        providers: [
            provideNoopAnimations(),
            // The preview result renders <inspecto-data-table>, whose real theme service walks up to
            // GAMMA_APP_CONFIG — stub it, as the data-table's own spec does.
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: ComponentsService, useValue: { list: () => of([]) } },
            {
                provide: ConfigService,
                useValue: {
                    read: vi.fn(() => throwError(() => ({ status: 404 }))),
                    write: vi.fn((type: string, cfg: Record<string, unknown>) =>
                        of({
                            type,
                            written: true,
                            path: `${String(cfg['name'])}.toon`,
                            name: String(cfg['name']),
                            bytes: 1,
                            overwritten: false,
                            findings: [],
                        }),
                    ),
                    registerEnrichment: vi.fn(() =>
                        of({ registered: true, name: 'x_enrich', path: 'x_enrich.toon', findings: [] }),
                    ),
                    ...api,
                },
            },
            { provide: CatalogService, useValue: { references: vi.fn(() => of([PRODUCED_REF])) } },
            { provide: LensService, useValue: { canAuthorWorkbench: () => true } },
            // The derived `output.database` convention is space-relative (P6-c).
            { provide: SpacesService, useValue: { currentSpaceId: () => 'demo' } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    await TestBed.compileComponents(); // the shared enrichment editor @defer-loads CodeMirror
    const fixture = TestBed.createComponent(PipelineConfigDefinitionComponent);
    fixture.componentRef.setInput('node', inputs.node);
    if (inputs.attributes !== undefined) fixture.componentRef.setInput('attributes', inputs.attributes);
    if (inputs.enrichmentHost) fixture.componentRef.setInput('enrichmentHost', inputs.enrichmentHost);
    if (inputs.sampleRows) fixture.componentRef.setInput('sampleRows', inputs.sampleRows);
    if (inputs.configSubdir !== undefined) fixture.componentRef.setInput('configSubdir', inputs.configSubdir);
    if (inputs.pipelineName !== undefined) fixture.componentRef.setInput('pipelineName', inputs.pipelineName);
    fixture.detectChanges();
    return fixture;
}

/** The pane's schema form — the config one on a plain node, the wiring one on an enrichment. */
function form(fixture: ComponentFixture<PipelineConfigDefinitionComponent>): InspectoSchemaFormComponent {
    return fixture.debugElement.query(By.directive(InspectoSchemaFormComponent)).componentInstance;
}

/** The shared enrichment editor hosted by the pane (enrichment nodes only). */
function editor(fixture: ComponentFixture<PipelineConfigDefinitionComponent>): EnrichmentEditorComponent {
    return fixture.debugElement.query(By.directive(EnrichmentEditorComponent)).componentInstance;
}

/** The typed additional-config editor hosted by the pane. */
function extraEditor(fixture: ComponentFixture<PipelineConfigDefinitionComponent>): PipelineExtraConfigComponent {
    return fixture.debugElement.query(By.directive(PipelineExtraConfigComponent)).componentInstance;
}

/** A second fixture inside one `it` — TestBed can only be configured once, so this resets it first. */
async function createSecond(inputs: PaneInputs) {
    TestBed.resetTestingModule();
    return create(inputs);
}

/** Capture the node the pane emits on Apply. */
function applied(fixture: ComponentFixture<PipelineConfigDefinitionComponent>): () => AuthoredNode | undefined {
    let out: AuthoredNode | undefined;
    fixture.componentInstance.applied.subscribe((n) => (out = n));
    return () => out;
}

describe('PipelineConfigDefinitionComponent', () => {
    beforeEach(() => vi.clearAllMocks());

    it('uses the free-form config editor for a type with no schema', async () => {
        const fixture = await create({ node: { id: 'x', type: 'plugin.unknown' } });
        const c = fixture.componentInstance;
        expect(c.specs()).toEqual([]);
        // free-form is the primary surface: rendered flat (R6 — no disclosure), with add-a-key offered
        expect(c.allowAddExtra()).toBe(true);
        expect(fixture.nativeElement.querySelector('app-pipeline-extra-config')).toBeTruthy();
    });

    it('renders the schema-form for a known type and splits config into schema + free-form', async () => {
        const fixture = await create({
            node: {
                id: 'w',
                type: 'sink.persistent',
                config: { format: 'CSV', compression: 'gzip', custom_flag: 'x' },
            },
        });
        const c = fixture.componentInstance;
        expect(c.specs().map((s) => s.key)).toContain('format');
        // schema-known keys seed the schema-form; the unknown key falls to the free-form editor
        expect(c.split().schemaInitial).toMatchObject({ format: 'CSV', compression: 'gzip' });
        expect(c.split().schemaInitial['custom_flag']).toBeUndefined();
        expect(c.split().extraRows).toEqual([{ key: 'custom_flag', value: 'x' }]);
        // an extra key is present ⇒ the flat "Additional config" section renders (no chevron, R6)
        expect(fixture.nativeElement.querySelector('app-pipeline-extra-config')).toBeTruthy();
        expect(fixture.nativeElement.textContent).toContain('Additional config');
        expect(fixture.nativeElement.querySelector('[aria-expanded]')).toBeNull();
    });

    it('merges schema-form values with free-form rows on apply', async () => {
        const fixture = await create({ node: { id: 'w', type: 'sink.file', config: { format: 'CSV', extra: '1' } } });
        const out = applied(fixture);
        fixture.componentInstance.submit();
        expect(out()?.config).toMatchObject({ format: 'CSV', extra: '1' });
    });

    /**
     * D4: spec keys are FLAT (`__` = nesting) while the engine reads nested MAPS, so the apply has to run
     * `nestKeys` — and sub-keys with no AttributeSpec must survive it. Pinned here on a SINK (`intake__*`;
     * it was `batch__*` until the consignment caps moved to the collector, CONSIGNMENT-HOME-1);
     * the acquisition `collector:` blocks are pinned on the Collector pane.
     */
    it('nests flat `__` spec keys before they reach node.config, keeping unmodeled sub-keys', async () => {
        const fixture = await create({
            node: {
                id: 'w',
                type: 'sink.persistent',
                config: { intake: { max_files_per_cycle: 5, on_partial: 'HOLD' } },
            },
        });
        const c = fixture.componentInstance;
        expect(c.split().schemaInitial['intake__max_files_per_cycle']).toBe(5);
        const out = applied(fixture);
        c.submit();
        const cfg = out()?.config as Record<string, unknown>;
        // The flat forms must be gone — they are form-transport spellings, never config keys.
        expect(Object.keys(cfg).filter((k) => k.includes('__'))).toEqual([]);
        expect(cfg['intake']).toMatchObject({ max_files_per_cycle: 5, on_partial: 'HOLD' });
    });

    /** The nested block must reach the schema form, not the free-form escape hatch (D4, load half). */
    it('seeds the schema form from a nested block instead of stringifying it into free-form', async () => {
        const c = (
            await create({
                node: {
                    id: 'w',
                    type: 'sink.persistent',
                    config: { intake: { max_files_per_cycle: 2 }, mystery: { a: 1 } },
                },
            })
        ).componentInstance;
        expect(c.split().schemaInitial['intake__max_files_per_cycle']).toBe(2);
        // Only the genuinely unknown root lands in the extra editor — TYPED, never stringified.
        expect(c.split().extraRows).toEqual([{ key: 'mystery', value: { a: 1 } }]);
    });

    /**
     * ⚠ The pane asks NO Name/Description (identity is the inspector's rename pencil, principle 5), but
     * `buildConfiguredNode` rebuilds the node from scratch — so both must be carried through explicitly.
     * Omitting them DELETED the node's name on every config apply.
     */
    it('carries the node identity through an apply even though it never asks for it', async () => {
        const fixture = await create({
            node: {
                id: 'flt',
                type: 'transform.filter',
                name: 'Row filter',
                description: 'drops test traffic',
                config: { where: 'a > 1' },
            },
        });
        expect(fixture.nativeElement.textContent).not.toContain('Description');
        const out = applied(fixture);
        fixture.componentInstance.submit();
        expect(out()).toMatchObject({ id: 'flt', name: 'Row filter', description: 'drops test traffic' });
    });

    /**
     * `transform.route`'s `branches` is a list of MAPS and deliberately has no spec, so it travels as an
     * UNTOUCHED free-form row — which must restore verbatim, not as the JSON string it was seeded with.
     * Applying a route node with no edits at all used to replace its routing with text.
     */
    it('restores an untouched unmodeled block verbatim instead of stringifying it', async () => {
        const branches = [{ rel: 'kept', where: 'ok' }];
        const fixture = await create({ node: { id: 'r', type: 'transform.route', config: { branches } } });
        const out = applied(fixture);
        fixture.componentInstance.submit();
        expect((out()?.config as Record<string, unknown>)['branches']).toEqual(branches);
    });

    it('reports dirty transitions and returns pristine after a successful apply', async () => {
        // A no-schema node, so the extra editor is the primary surface with add enabled.
        const fixture = await create({ node: { id: 'x', type: 'plugin.unknown' } });
        const seen: boolean[] = [];
        fixture.componentInstance.dirtyChange.subscribe((d) => seen.push(d));
        const extra = extraEditor(fixture);
        extra.draftKey.setValue('threshold');
        extra.draftKind.setValue('number');
        extra.add();
        fixture.detectChanges();
        fixture.componentInstance.onInteraction();
        expect(seen.at(-1)).toBe(true);
        fixture.componentInstance.submit();
        expect(seen.at(-1)).toBe(false);
    });

    /**
     * 2026-08-21 (second pass): the generic Key/Value grid is GONE — an unmodelled entry renders with
     * its ACTUAL key and a control matching its value TYPE, and an edit round-trips TYPED.
     */
    describe('typed additional config', () => {
        it('renders each extra with its own key and type-matched control', async () => {
            const fixture = await create({
                node: {
                    id: 'x',
                    type: 'plugin.unknown',
                    config: { note: 'hi', retries: 3, enabled: true, block: { a: 1 } },
                },
            });
            const el = fixture.nativeElement as HTMLElement;
            // Property rows (2026-09-05): the key is the row label, the value reads as text until edited.
            const labels = Array.from(el.querySelectorAll('app-pipeline-extra-config .sf-label')).map((l) =>
                l.textContent?.trim(),
            );
            expect(labels).toEqual(['note', 'retries', 'enabled', 'block']);
            // a boolean is its own editor (a toggle), so it has no pencil
            expect(el.querySelector('app-pipeline-extra-config mat-slide-toggle')).toBeTruthy();
            expect(el.querySelector('app-pipeline-extra-config [data-key="enabled"] .sf-pencil')).toBeNull();
            expect(el.querySelector('app-pipeline-extra-config [data-key="retries"] .sf-pencil')).toBeTruthy();
            // editing a row reveals the control that matches its TYPE: number → numeric input, json → textarea
            const extra = extraEditor(fixture);
            extra.startEditing(extra.rows()[1]);
            fixture.detectChanges();
            expect(el.querySelector('app-pipeline-extra-config input[type="number"]')).toBeTruthy();
            extra.startEditing(extra.rows()[3]);
            fixture.detectChanges();
            expect(el.querySelector('app-pipeline-extra-config textarea')).toBeTruthy();
        });

        it('an edited number applies as a NUMBER, validated', async () => {
            const fixture = await create({
                node: { id: 'x', type: 'plugin.unknown', config: { retries: 3 } },
            });
            const extra = extraEditor(fixture);
            const row = extra.rows()[0];
            row.control.setValue('not-a-number');
            row.control.markAsDirty();
            expect(extra.validate()).toBe(false); // refused, not silently stringified

            row.control.setValue('7');
            const out = applied(fixture);
            fixture.componentInstance.submit();
            expect((out()?.config as Record<string, unknown>)['retries']).toBe(7);
        });

        it('an edited JSON block applies PARSED, and refuses unparseable JSON', async () => {
            const fixture = await create({
                node: { id: 'x', type: 'plugin.unknown', config: { block: { a: 1 } } },
            });
            const extra = extraEditor(fixture);
            const row = extra.rows()[0];
            row.control.setValue('{ nope');
            row.control.markAsDirty();
            expect(extra.validate()).toBe(false);

            row.control.setValue('{"a": 2, "b": "x"}');
            const out = applied(fixture);
            fixture.componentInstance.submit();
            expect((out()?.config as Record<string, unknown>)['block']).toEqual({ a: 2, b: 'x' });
        });

        it('offers add only where the editor is the PRIMARY surface (no schema)', async () => {
            const bare = await create({ node: { id: 'x', type: 'plugin.unknown' } });
            expect(extraEditor(bare).allowAdd()).toBe(true);

            const schemad = await createSecond({ node: { id: 'w', type: 'sink.persistent', config: { zz: '1' } } });
            expect(extraEditor(schemad).allowAdd()).toBe(false);
        });
    });

    // ── §3.1: the SERVED vocabulary drives the form; the local table is only a fallback ──

    it('prefers the server-published attributes over the local table', async () => {
        const served: AttributeSpec[] = [
            { key: 'served_only', label: 'Served only', type: 'string', tier: 'required' },
        ];
        const c = (await create({ node: { id: 'w', type: 'sink.persistent' }, attributes: served })).componentInstance;
        expect(c.specs()).toBe(served);
        expect(c.specs().map((s) => s.key)).not.toContain('format'); // the local table's key
    });

    /**
     * Before the catalog resolves (and in the offline build) the client table must still drive the form.
     * ⚠ This key list is a PINNED output touchpoint — it moved here from `node-config.dialog.spec.ts`.
     */
    it('falls back to the local table when the server said nothing', async () => {
        const c = (await create({ node: { id: 'w', type: 'sink.persistent' } })).componentInstance;
        expect(c.specs().map((s) => s.key)).toEqual([
            'database',
            'format',
            'compression',
            'filename_column',
            'priority',
            'intake__max_files_per_cycle',
            'intake__min_files_per_cycle',
            'intake__adaptive',
        ]);
    });

    /**
     * A served EMPTY list is the server stating "this type has no schema" — it must NOT silently re-enable
     * the client table, or a type the server deliberately unspecced would keep drawing a stale form.
     */
    it('honours a served empty list instead of falling back', async () => {
        const fixture = await create({ node: { id: 'w', type: 'sink.persistent' }, attributes: [] });
        const c = fixture.componentInstance;
        expect(c.specs()).toEqual([]);
        expect(c.allowAddExtra()).toBe(true); // free-form becomes the only surface
        expect(fixture.nativeElement.querySelector('app-pipeline-extra-config')).toBeTruthy();
    });

    // ── enrichment (W4b): the pane authors the REAL companion `*_enrich.toon` ──

    it('renders the shared enrichment editor + wiring form for an enrichment node', async () => {
        const fixture = await create({ node: { id: 'enrich1', type: 'enrichment' } });
        const c = fixture.componentInstance;
        expect(c.isEnrichment()).toBe(true);
        expect(editor(fixture)).toBeTruthy();
        expect(c.enrichName.value).toBe('enrich1');
        // the editor is the primary surface, not the key/value grid (no extras ⇒ no Additional config)
        expect(c.allowAddExtra()).toBe(false);
        expect(fixture.nativeElement.querySelector('app-pipeline-extra-config')).toBeNull();
    });

    it('seeds a FRESH companion from the host pipeline instead of asking (P6-c)', async () => {
        const fixture = await create({
            node: { id: 'enrich1', type: 'enrichment' },
            enrichmentHost: {
                pipelineId: 'orders',
                inputDatabase: 'spaces/demo/data/orders/database',
                inputFormat: 'PARQUET',
            },
        });
        const wiring = form(fixture);
        expect(wiring.form.get('input__database')?.value).toBe('spaces/demo/data/orders/database');
        expect(wiring.form.get('output__database')?.value).toBe('spaces/demo/data/enriched/enrich1');
        expect(wiring.form.get('triggers__on_pipeline')?.value).toBe('orders');
        expect(wiring.form.get('input__partitions')?.value).toEqual(['year', 'month', 'day']);
    });

    it('a host with no resolvable output store seeds everything EXCEPT the input store', async () => {
        // Multi-destination (or store-less) pipeline: the editor sends no `inputDatabase` rather than
        // pointing the transform at a store the author never chose.
        const fixture = await create({
            node: { id: 'enrich1', type: 'enrichment' },
            enrichmentHost: { pipelineId: 'orders' },
        });
        expect(form(fixture).form.get('input__database')?.value).toBe('');
        expect(form(fixture).form.get('triggers__on_pipeline')?.value).toBe('orders');
    });

    it('a host that supplies no pipeline context still opens the wiring form blank', async () => {
        const fixture = await create({ node: { id: 'enrich1', type: 'enrichment' } });
        expect(form(fixture).form.get('input__database')?.value).toBeFalsy();
        expect(form(fixture).form.get('triggers__on_pipeline')?.value).toBeFalsy();
    });

    it('apply writes the companion, registers it, and binds by reference — config stays unmirrored', async () => {
        const write = vi.fn((type: string, cfg: Record<string, unknown>) =>
            of({
                type,
                written: true,
                path: `${String(cfg['name'])}.toon`,
                name: String(cfg['name']),
                bytes: 1,
                overwritten: false,
                findings: [],
            }),
        );
        const registerEnrichment = vi.fn(() =>
            of({ registered: true, name: 'enrich1_enrich', path: 'enrich1.toon', findings: [] }),
        );
        const fixture = await create({ node: { id: 'enrich1', type: 'enrichment' } }, { write, registerEnrichment });
        const out = applied(fixture);
        editor(fixture).onSqlChange('SELECT * FROM input');
        const wiring = form(fixture);
        wiring.form.get('input__database')?.setValue('spaces/demo/data/orders/database');
        wiring.form.get('output__database')?.setValue('spaces/demo/data/enriched/enrich1');
        fixture.componentInstance.submit();

        const [type, draft] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect(type).toBe('enrichment');
        expect(draft['name']).toBe('enrich1');
        expect(draft['transform']).toBe('SELECT * FROM input');
        expect((draft['input'] as Record<string, unknown>)['database']).toBe('spaces/demo/data/orders/database');
        expect(registerEnrichment).toHaveBeenCalledWith('enrich1.toon');
        // The node binds by reference — the companion file is the single truth, never mirrored.
        expect(out()?.use).toBe('enrichment/enrich1');
        expect(out()?.config).toBeUndefined();
    });

    it('hydrates a bound companion and round-trips its partition lists through an apply', async () => {
        const read = vi.fn(() =>
            of({
                type: 'enrichment',
                name: 'orders_enrich',
                path: 'orders_enrich.toon',
                config: {
                    name: 'orders_enrich',
                    input: { database: 'in/db', format: 'PARQUET', partitions: ['year', 'month'] },
                    output: { database: 'out/db', partitions: ['year'] },
                    transform: 'SELECT 1 FROM input',
                    triggers: { on_pipeline: 'orders' },
                },
            }),
        );
        const write = vi.fn((type: string, cfg: Record<string, unknown>) =>
            of({
                type,
                written: true,
                path: `${String(cfg['name'])}.toon`,
                name: String(cfg['name']),
                bytes: 1,
                overwritten: false,
                findings: [],
            }),
        );
        const fixture = await create(
            { node: { id: 'enrich1', type: 'enrichment', use: 'enrichment/orders_enrich' } },
            { read, write },
        );
        const out = applied(fixture);
        fixture.detectChanges(); // the async read landed; the hydrate effect sees the editor
        // The 3rd arg is the satellite subdir (SATELLITE-WRITE-1): `undefined` here because this host
        // binds no `configSubdir`, i.e. the pipeline sits at the write root — which deliberately leaves
        // the server's fallback scan available. The set case is pinned in its own test below.
        expect(read).toHaveBeenCalledWith('enrichment', 'orders_enrich', undefined);
        expect(fixture.componentInstance.enrichName.value).toBe('orders_enrich');
        expect(editor(fixture).sql()).toBe('SELECT 1 FROM input');

        fixture.componentInstance.submit();
        const [, draft] = write.mock.calls[0] as [string, Record<string, unknown>];
        // The form OWNS partitions (specced as `list` chips) — they survive because the form hydrated
        // them, not because they were unmodeled and copied past it.
        expect((draft['input'] as Record<string, unknown>)['partitions']).toEqual(['year', 'month']);
        expect((draft['output'] as Record<string, unknown>)['partitions']).toEqual(['year']);
        expect((draft['triggers'] as Record<string, unknown>)['on_pipeline']).toBe('orders');
        expect(out()?.use).toBe('enrichment/orders_enrich');
    });

    /**
     * BACKLOG SATELLITE-WRITE-1. An enrichment is a pipeline SATELLITE — the committed samples put it
     * beside its pipeline (`config/orders/orders_daily_enrich.toon`) — so both the read and the WRITE must
     * carry the pipeline's own directory. This pane passed neither, so every save landed at the write root
     * and orphaned a duplicate. ⚠ The write matters more than the read: the read had a server-side
     * fallback scan to lean on, the write had nothing.
     */
    it('carries the pipeline directory on the enrichment read AND write', async () => {
        const read = vi.fn(() =>
            of({
                type: 'enrichment',
                name: 'orders_enrich',
                path: 'orders/orders_enrich.toon',
                config: {
                    name: 'orders_enrich',
                    input: { database: 'in/db', format: 'CSV' },
                    output: { database: 'out/db' },
                    transform: 'SELECT 1 FROM input',
                },
            }),
        );
        const write = vi.fn((type: string, cfg: Record<string, unknown>, _opts?: { subdir?: string }) =>
            of({
                type,
                written: true,
                path: `orders/${String(cfg['name'])}.toon`,
                name: String(cfg['name']),
                bytes: 1,
                overwritten: false,
                findings: [],
            }),
        );
        const fixture = await create(
            {
                node: { id: 'enrich1', type: 'enrichment', use: 'enrichment/orders_enrich' },
                configSubdir: 'orders',
            },
            { read, write },
        );
        fixture.detectChanges();

        expect(read).toHaveBeenCalledWith('enrichment', 'orders_enrich', 'orders');

        fixture.componentInstance.submit();
        expect(write.mock.calls[0][2]?.subdir).toBe('orders');
    });

    it('a BOUND companion wins over the host-derived seed — the file is the truth', async () => {
        const read = vi.fn(() =>
            of({
                type: 'enrichment',
                name: 'orders_enrich',
                path: 'orders_enrich.toon',
                config: {
                    name: 'orders_enrich',
                    input: { database: 'in/db', format: 'CSV', partitions: ['dt'] },
                    output: { database: 'out/db', partitions: [] },
                    transform: 'SELECT 1 FROM input',
                    triggers: { on_pipeline: 'other_pipeline' },
                },
            }),
        );
        const fixture = await create(
            {
                node: { id: 'enrich1', type: 'enrichment', use: 'enrichment/orders_enrich' },
                enrichmentHost: { pipelineId: 'orders', inputDatabase: 'derived/db', inputFormat: 'PARQUET' },
            },
            { read },
        );
        fixture.detectChanges(); // the async read landed
        const wiring = form(fixture);
        expect(wiring.form.get('input__database')?.value).toBe('in/db');
        expect(wiring.form.get('input__partitions')?.value).toEqual(['dt']);
        // Even a trigger naming a DIFFERENT pipeline stands: this pane edits the companion, and
        // re-pointing it at its host would be an unasked-for change to a deployed enrichment.
        expect(wiring.form.get('triggers__on_pipeline')?.value).toBe('other_pipeline');
    });

    // `EnrichmentConfig.fromMap` THROWS `Missing or invalid list` when either partitions key is absent,
    // so a fresh enrichment must still write `partitions: []` — the legal "unpartitioned" value.
    it('writes both partition keys for a fresh enrichment, so the config can load', async () => {
        const write = vi.fn((type: string, cfg: Record<string, unknown>) =>
            of({
                type,
                written: true,
                path: `${String(cfg['name'])}.toon`,
                name: String(cfg['name']),
                bytes: 1,
                overwritten: false,
                findings: [],
            }),
        );
        const fixture = await create({ node: { id: 'enrich1', type: 'enrichment' } }, { write });
        editor(fixture).onSqlChange('SELECT 1 FROM input');
        const wiring = form(fixture);
        wiring.form.get('input__database')?.setValue('in/db');
        wiring.form.get('output__database')?.setValue('out/db');

        fixture.componentInstance.submit();
        const [, draft] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect((draft['input'] as Record<string, unknown>)['partitions']).toEqual([]);
        expect((draft['output'] as Record<string, unknown>)['partitions']).toEqual([]);
    });

    // An `output.partitions` entry may be `{column, source}` where `source` declares event time and
    // drives the recorded bounds. The chips control is string[]-only and a specced key is replaced
    // wholesale — so without the re-marry, authoring the grain silently drops `source`.
    it('keeps an output partition source across an apply that only touches other fields', async () => {
        const read = vi.fn(() =>
            of({
                type: 'enrichment',
                name: 'orders_enrich',
                path: 'orders_enrich.toon',
                config: {
                    name: 'orders_enrich',
                    input: { database: 'in/db', partitions: ['day'] },
                    output: {
                        database: 'out/db',
                        partitions: [{ column: 'day', source: 'event_ts' }, 'region'],
                    },
                    transform: 'SELECT 1 FROM input',
                },
            }),
        );
        const write = vi.fn((type: string, cfg: Record<string, unknown>) =>
            of({
                type,
                written: true,
                path: `${String(cfg['name'])}.toon`,
                name: String(cfg['name']),
                bytes: 1,
                overwritten: false,
                findings: [],
            }),
        );
        const fixture = await create(
            { node: { id: 'e', type: 'enrichment', use: 'enrichment/orders_enrich' } },
            { read, write },
        );
        fixture.detectChanges();

        fixture.componentInstance.submit();
        const [, draft] = write.mock.calls[0] as [string, Record<string, unknown>];
        // The map entry keeps its source; the bare one stays bare.
        expect((draft['output'] as Record<string, unknown>)['partitions']).toEqual([
            { column: 'day', source: 'event_ts' },
            'region',
        ]);
    });

    it('refuses to overwrite a hand-authored transform_file config', async () => {
        const read = vi.fn(() =>
            of({
                type: 'enrichment',
                name: 'x_enrich',
                path: 'x_enrich.toon',
                config: {
                    name: 'x_enrich',
                    input: { database: 'a' },
                    output: { database: 'b' },
                    transform_file: 't.sql',
                },
            }),
        );
        const write = vi.fn();
        const fixture = await create(
            { node: { id: 'e', type: 'enrichment', use: 'enrichment/x_enrich' } },
            { read, write },
        );
        fixture.detectChanges();
        expect(fixture.componentInstance.enrichUneditable()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('transform_file');
        fixture.componentInstance.submit();
        expect(write).not.toHaveBeenCalled(); // applying the binding is allowed; writing is not
    });

    /**
     * The inline test (`POST /components/{family}/preview`) — gated on the node's own TYPE plus rows to
     * run over. ⚠ Its predecessor, "Test &lt;component&gt;…", gated on `bindKind` and was therefore dead
     * UI on every node that reached this surface; it is not carried here (D5).
     */
    describe('inline test', () => {
        const withPreview = (over: Record<string, unknown>): void => {
            TestBed.overrideProvider(ComponentsService, { useValue: { list: () => of([]), ...over } });
        };
        const filter = (): PaneInputs => ({
            node: { id: 'flt', type: 'transform.filter' },
            sampleRows: [{ qty: '2' }, { qty: '1' }],
        });

        it('is not offered without rows — a test over no data would report success over nothing', async () => {
            const fixture = await create({ node: { id: 'flt', type: 'transform.filter' } });
            expect(fixture.componentInstance.canTestInline()).toBe(false);
            expect(fixture.nativeElement.textContent).not.toContain('Test this Step');
        });

        it('is not offered for a family the route has no preview for', async () => {
            const fixture = await create({ node: { id: 'e', type: 'enrichment' }, sampleRows: [{ qty: '2' }] });
            expect(fixture.componentInstance.testFamily()).toBeNull();
        });

        /**
         * S2: the drawer renders the WHOLE preview — the derived schema and the compiled SQL were always
         * in this response and used to be reduced to two count lines.
         */
        it('sends the node type inside config and renders the derived schema, rows and SQL', async () => {
            const previewTransform = vi.fn(() =>
                of({
                    inputColumns: ['qty'],
                    relations: [
                        {
                            rel: 'data',
                            rowCount: 1,
                            rows: [{ qty: '2' }],
                            columnTypes: [{ name: 'qty', type: 'VARCHAR' }],
                        },
                    ],
                    sql: ['CREATE TABLE preview_flt__data AS SELECT * FROM "preview_input" WHERE qty > 0'],
                }),
            );
            withPreview({ previewTransform });
            const fixture = await create(filter());
            fixture.componentInstance.runInlineTest();
            fixture.detectChanges();

            // ⚠ `type` is mandatory in the body: the route 422s a config that is not `transform.*`.
            expect(previewTransform).toHaveBeenCalledWith(expect.objectContaining({ type: 'transform.filter' }), [
                { qty: '2' },
                { qty: '1' },
            ]);
            const text = fixture.nativeElement.textContent ?? '';
            expect(text).toContain('Derived schema');
            expect(text).toContain('VARCHAR');
            expect(text).toContain('1 row(s)');
            expect(fixture.nativeElement.querySelector('pre')?.textContent).toContain('WHERE qty > 0');
        });

        it('reports a sink preview as its store plus any warnings', async () => {
            const previewSink = vi.fn(() =>
                of({ store: null, rowCount: 2, rows: [], warnings: ["sink declares no 'store' name"] }),
            );
            withPreview({ previewSink });
            const fixture = await create({
                node: { id: 'out', type: 'sink.persistent' },
                sampleRows: [{ qty: '2' }, { qty: '1' }],
            });
            fixture.componentInstance.runInlineTest();
            fixture.detectChanges();

            expect(previewSink).toHaveBeenCalled();
            expect(fixture.nativeElement.textContent).toContain('(none declared)');
            expect(fixture.nativeElement.textContent).toContain("sink declares no 'store' name");
        });

        it('surfaces a refusal instead of a result', async () => {
            // A real HttpErrorResponse: `apiErrorMessage` only reads the server's message off one.
            withPreview({
                previewTransform: () =>
                    throwError(
                        () =>
                            new HttpErrorResponse({
                                status: 422,
                                error: { error: { message: 'preview failed: no such column' } },
                            }),
                    ),
            });
            const fixture = await create(filter());
            fixture.componentInstance.runInlineTest();
            fixture.detectChanges();

            expect(fixture.componentInstance.testResult()).toBeNull();
            expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('no such column');
        });
    });

    it('has no a11y violations (free-form type)', async () => {
        await expectNoA11yViolations((await create({ node: { id: 'x', type: 'plugin.unknown' } })).nativeElement);
    });

    it('has no a11y violations (schema-backed type)', async () => {
        await expectNoA11yViolations((await create({ node: { id: 'w', type: 'sink.file' } })).nativeElement);
    });

    it('has no a11y violations (enrichment node)', async () => {
        await expectNoA11yViolations((await create({ node: { id: 'e', type: 'enrichment' } })).nativeElement);
    });

    /**
     * Partitioning (redesign S5, D4) — moved here from the Parse pane; pure UI relocation, the schema
     * toon's `partitions[]` storage contract is untouched. Renders only for the pipeline's single
     * qualifying output sink (the SAME `database`-key test the host's `primaryOutputSink` applies),
     * reading/writing the companion schema toon directly.
     */
    describe('partitioning (moved from the Parse pane)', () => {
        const SINK_NODE: AuthoredNode = { id: 'out', type: 'sink.file', config: { database: 'spaces/demo/data/x' } };
        const SCHEMA_CONFIG = {
            raw: {
                name: 'record',
                fields: [
                    { name: 'IMSI', selector: '0', type: 'VARCHAR' },
                    { name: 'TXN_DATE', selector: '1', type: 'DATE' },
                ],
            },
            mapping: { canonicalName: 'x', rawName: 'record', rules: [] },
        };

        it('renders nothing for a node that is not the output sink (no database key)', async () => {
            const fixture = await create({ node: { id: 'x', type: 'transform.filter' }, pipelineName: 'x' });
            expect(fixture.nativeElement.textContent).not.toContain('Partitioning');
        });

        it('reads the companion schema toon and offers its field names', async () => {
            const readSpy = vi.fn(() => of({ config: SCHEMA_CONFIG }));
            const fixture = await create(
                { node: SINK_NODE, pipelineName: 'x' },
                { read: readSpy as unknown as ConfigService['read'] },
            );
            expect(readSpy).toHaveBeenCalledWith('schema', 'x_schema', undefined);
            expect(fixture.nativeElement.querySelector('inspecto-schema-partitions-editor')).toBeTruthy();
            expect(fixture.nativeElement.textContent).toContain('Partitioning');
        });

        it('seeds the editor with the stored partitions[]', async () => {
            const fixture = await create(
                { node: SINK_NODE, pipelineName: 'x' },
                {
                    read: vi.fn(() =>
                        of({
                            config: {
                                ...SCHEMA_CONFIG,
                                partitions: [{ column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' }],
                            },
                        }),
                    ) as unknown as ConfigService['read'],
                },
            );
            expect(fixture.componentInstance.partitionSeed()).toEqual([
                { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
            ]);
        });

        it('explains, rather than errors, when no schema has been written yet', async () => {
            const fixture = await create(
                { node: SINK_NODE, pipelineName: 'x' },
                { read: vi.fn(() => throwError(() => ({ status: 404 }))) as unknown as ConfigService['read'] },
            );
            expect(fixture.nativeElement.textContent).toContain('define this pipeline');
            expect(fixture.nativeElement.querySelector('inspecto-schema-partitions-editor')).toBeNull();
        });

        it('Save partitioning writes partitions[] back, carrying every other key verbatim', async () => {
            const writeSpy = vi.fn((type: string, cfg: Record<string, unknown>) =>
                of({
                    type,
                    written: true,
                    path: 'x_schema.toon',
                    name: 'x_schema',
                    bytes: 1,
                    overwritten: true,
                    findings: [],
                }),
            );
            const fixture = await create(
                { node: SINK_NODE, pipelineName: 'x' },
                {
                    read: vi.fn(() => of({ config: SCHEMA_CONFIG })) as unknown as ConfigService['read'],
                    write: writeSpy as unknown as ConfigService['write'],
                },
            );
            const editor = fixture.debugElement.query(By.css('inspecto-schema-partitions-editor'))
                .componentInstance as { addRow: () => void; form: { controls: Record<string, unknown> } };
            // Add one segment through the editor's own API — mirrors how the shared component's own
            // spec drives it, keeping this test decoupled from its internal form shape.
            editor.addRow();
            const group = (editor as unknown as { partitionRows: { controls: { patchValue: (v: unknown) => void }[] } })
                .partitionRows.controls[0];
            group.patchValue({ column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' });
            fixture.detectChanges();

            fixture.componentInstance.savePartitioning();
            fixture.detectChanges();

            expect(writeSpy).toHaveBeenCalledTimes(1);
            const [type, config] = writeSpy.mock.calls[0] as [string, Record<string, unknown>];
            expect(type).toBe('schema');
            expect(config['partitions']).toEqual([{ column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' }]);
            // Every other key from the read rides verbatim — the partitions[]-drop lesson generalised.
            expect(config['raw']).toEqual(SCHEMA_CONFIG.raw);
            expect(config['mapping']).toEqual(SCHEMA_CONFIG.mapping);
        });

        it('refuses to save an invalid segment, with an inline error', async () => {
            const writeSpy = vi.fn();
            const fixture = await create(
                { node: SINK_NODE, pipelineName: 'x' },
                {
                    read: vi.fn(() => of({ config: SCHEMA_CONFIG })) as unknown as ConfigService['read'],
                    write: writeSpy as unknown as ConfigService['write'],
                },
            );
            const editor = fixture.debugElement.query(By.css('inspecto-schema-partitions-editor'))
                .componentInstance as { addRow: () => void };
            editor.addRow(); // a blank row: no column name, no source — invalid
            fixture.detectChanges();

            fixture.componentInstance.savePartitioning();
            fixture.detectChanges();

            expect(writeSpy).not.toHaveBeenCalled();
            expect(fixture.componentInstance.partitionsError()).toContain('Every partition segment needs a name');
        });
    });
});
