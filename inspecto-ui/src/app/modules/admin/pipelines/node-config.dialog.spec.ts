import { HttpErrorResponse } from '@angular/common/http';
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
    ConnectionsService,
    LensService,
    MetadataNode,
    SpacesService,
} from 'app/inspecto/api';
import { CollectorConfigComponent } from 'app/inspecto/collector/collector-config.component';
import type { AttributeSpec } from 'app/inspecto/component-model';
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
    id: 'ref:region_dim',
    kind: 'REFERENCE_DATASET',
    label: 'REGION_DIM',
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
            { provide: ComponentsService, useValue: { list: () => of(GRAMMARS), get: () => of(GRAMMARS[0]) } },
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
            // The shared collector component loads these — `connector` included, since it DERIVES
            // `collector.connector` from the picked profile rather than asking for it.
            {
                provide: ConnectionsService,
                useValue: {
                    list: () =>
                        of([
                            { id: 'prod_sftp', connector: 'sftp' },
                            { id: 'lake_blob', connector: 'azure' },
                        ]),
                    test: vi.fn(() => of({ reachable: true, detail: 'ok' })),
                },
            },
            { provide: LensService, useValue: { canAuthorWorkbench: () => true } },
            // The derived `output.database` convention is space-relative (P6-c).
            { provide: SpacesService, useValue: { currentSpaceId: () => 'demo' } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    await TestBed.compileComponents(); // the shared enrichment editor @defer-loads CodeMirror
    const fixture = TestBed.createComponent(NodeConfigDialog);
    fixture.detectChanges();
    return fixture;
}

/** The dialog's config schema-form (the only one, on a non-enrichment node). */
function form(fixture: ComponentFixture<NodeConfigDialog>): InspectoSchemaFormComponent {
    return fixture.debugElement.query(By.directive(InspectoSchemaFormComponent)).componentInstance;
}

/**
 * The shared collector surface hosted by the dialog (acquisition nodes only). `form()` above still
 * works on one of these — a `By.directive` query descends into child views — and resolves to the
 * schema form INSIDE this component, which is exactly the control set those tests mean.
 */
function collector(fixture: ComponentFixture<NodeConfigDialog>): CollectorConfigComponent {
    return fixture.debugElement.query(By.directive(CollectorConfigComponent)).componentInstance;
}

/** Put the collector surface in Connection mode and let its schema form rebuild for the new specs. */
function pickConnectionMode(fixture: ComponentFixture<NodeConfigDialog>): void {
    collector(fixture).setMode('connection');
    fixture.detectChanges();
}

/** The shared enrichment editor hosted by the dialog (enrichment nodes only). */
function editor(fixture: ComponentFixture<NodeConfigDialog>): EnrichmentEditorComponent {
    return fixture.debugElement.query(By.directive(EnrichmentEditorComponent)).componentInstance;
}

describe('NodeConfigDialog', () => {
    beforeEach(() => Object.values(TOASTR).forEach((f) => f.mockClear()));

    it('offers the test action only once the node binds a registered component', async () => {
        const f = await create({ node: { id: 'parse', type: 'parser', use: 'grammar/cdr_csv' } as never });
        expect(f.componentInstance.testableComponentId()).toBe('cdr_csv');
        expect(f.nativeElement.textContent).toContain('Test cdr_csv');
    });

    it('offers no test action for a node holding only inline config', async () => {
        // No `use` ⇒ no registered component ⇒ nothing the /components/{kind}/{id}/test route can look up.
        const f = await create();
        expect(f.componentInstance.testableComponentId()).toBeNull();
        expect(f.nativeElement.textContent).not.toContain('Test ');
    });

    it('offers no test action for a kind the backend cannot dry-run', async () => {
        // schema/mapping have no /test route — a bound component alone is not enough.
        const f = await create({
            bindKind: 'schema',
            node: { id: 'cast', type: 'schema', use: 'schema/mini' } as never,
        });
        expect(f.componentInstance.testableComponentId()).toBeNull();
    });

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
            node: {
                id: 'w',
                type: 'sink.persistent',
                config: { format: 'CSV', compression: 'gzip', custom_flag: 'x' },
            },
            typeLabel: 'sink.persistent',
            categoryLabel: 'Sink',
            bindKind: null,
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
            typeLabel: 'sink.file',
            categoryLabel: 'Sink',
            bindKind: null,
        });
        const c = fixture.componentInstance;
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        fixture.detectChanges(); // instantiate the schema-form ViewChild
        c.save();
        expect(closed?.node.config).toMatchObject({ format: 'CSV', extra: '1' });
    });

    /**
     * D4 regression (2026-08-03). Spec keys are flat (`__` = nesting), but the flat pipeline's
     * `collector:` block reads `duplicate`/`stability`/`post_action` as nested MAPS
     * (`PipelineConfigParser.java:450,459,518`), so the generic save must run `nestKeys` — it did not, and
     * a literal `duplicate__mode` key was read by nothing. `include`/`exclude` are LIST_KEYS, so they also
     * split to the list shape the seeds use. The enrichment branch and the onboarding panes always nested;
     * only this path was missing it.
     */
    it('nests flat `__` spec keys before they reach node.config', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const fixture = await create({
            node: {
                id: 'acq',
                type: 'acquisition',
                config: {
                    post_action: { on_success: 'MOVE', tags: ['done'] },
                    stability: { window: '30s', size_checks: 3 },
                    include: ['glob:**/*.csv'],
                },
            },
            typeLabel: 'acquisition',
            categoryLabel: 'Collector',
            bindKind: null,
        });
        const c = fixture.componentInstance;
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        fixture.detectChanges();
        c.save();
        const cfg = closed?.node.config as Record<string, unknown>;
        expect(cfg['stability']).toMatchObject({ window: '30s' });
        expect(cfg['include']).toEqual(['glob:**/*.csv']);
        // The flat forms must be gone — they are form-transport spellings, never config keys.
        expect(Object.keys(cfg).filter((k) => k.includes('__'))).toEqual([]);
        // Sub-keys with no AttributeSpec are engine-read and must survive a guided save
        // (`post_action.tags`, `stability.size_checks` — PipelineConfigParser.java:449-470,516-527).
        expect(cfg['post_action']).toEqual({ on_success: 'MOVE', tags: ['done'] });
        expect((cfg['stability'] as Record<string, unknown>)['size_checks']).toBe(3);
    });

    /**
     * `duplicate:` is declared on the ACQUISITION node since the 2026-08-04 fold (D9 had split it
     * onto a `transform.dedup.fingerprint` node, removed because file dedup executes in the poll
     * cycle) — so the nesting + unmodeled-sub-key guarantees have to hold there. `algorithm` is
     * engine-read (`PipelineConfigParser.java:456`) with no spec.
     */
    it('nests and preserves the duplicate block on the acquisition node', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const fixture = await create({
            node: {
                id: 'acq',
                type: 'acquisition',
                config: { duplicate: { mode: 'checksum', algorithm: 'SHA256' } },
            },
            typeLabel: 'acquisition',
            categoryLabel: 'Collector',
            bindKind: null,
        });
        const c = fixture.componentInstance;
        expect(c.schemaInitial['duplicate__mode']).toBe('checksum');
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        fixture.detectChanges();
        c.save();
        expect((closed?.node.config as Record<string, unknown>)['duplicate']).toEqual({
            mode: 'checksum',
            algorithm: 'SHA256',
        });
    });

    /** The nested block must reach the schema form, not the free-form escape hatch (D4, load half). */
    it('seeds the schema form from a nested block instead of stringifying it into free-form', async () => {
        const c = (
            await create({
                node: { id: 'acq', type: 'acquisition', config: { stability: { window: '30s' }, mystery: { a: 1 } } },
                typeLabel: 'acquisition',
                categoryLabel: 'Collector',
                bindKind: null,
            })
        ).componentInstance;
        expect(c.schemaInitial['stability__window']).toBe('30s');
        // Only the genuinely unknown root is free-form — and it stays literal there.
        expect(c.configRows.value).toEqual([{ key: 'mystery', value: '{"a":1}' }]);
    });

    // ── §3.1: the SERVED vocabulary drives the form; the local table is only a fallback ──

    it('prefers the server-published attributes over the local table', async () => {
        const served: AttributeSpec[] = [
            { key: 'served_only', label: 'Served only', type: 'string', tier: 'required' },
        ];
        const c = (
            await create({
                node: { id: 'w', type: 'sink.persistent' },
                typeLabel: 'sink.persistent',
                categoryLabel: 'Sink',
                bindKind: null,
                attributes: served,
            })
        ).componentInstance;
        expect(c.specs()).toBe(served);
        expect(c.specs().map((s) => s.key)).not.toContain('format'); // the local table's key
    });

    /** Before the catalog resolves (and in the offline build) the client table must still drive the form. */
    it('falls back to the local table when the server said nothing', async () => {
        const c = (
            await create({
                node: { id: 'w', type: 'sink.persistent' },
                typeLabel: 'sink.persistent',
                categoryLabel: 'Sink',
                bindKind: null,
            })
        ).componentInstance;
        expect(c.specs().map((s) => s.key)).toEqual([
            'database',
            'format',
            'compression',
            'filename_column',
            'batch__max_files',
            'batch__max_bytes',
            'batch__order',
        ]);
    });

    /**
     * A served EMPTY list is the server stating "this type has no schema" — it must NOT silently re-enable
     * the client table, or a type the server deliberately unspecced would keep drawing a stale form.
     */
    it('honours a served empty list instead of falling back', async () => {
        const c = (
            await create({
                node: { id: 'w', type: 'sink.persistent' },
                typeLabel: 'sink.persistent',
                categoryLabel: 'Sink',
                bindKind: null,
                attributes: [],
            })
        ).componentInstance;
        expect(c.specs()).toEqual([]);
        expect(c.freeFormOpen()).toBe(true); // free-form becomes the only surface
    });

    // ── acquisition's Connection is a BINDING, not config (D3-remainder, 2026-08-04) ──

    /**
     * `PipelineEditable` carries the Connection on `use: connection/<name>` and strips a cfg-level one on
     * both lift (`:125`) and lower (`:248`), so the declared `connection` attribute was discarded on every
     * save. It is NOT wired as a `bindKind` — the component picker calls `GET /components/{kind}` and a
     * Connection is not a `ComponentType` — so the attribute itself becomes the binding surface.
     */
    it('saves an acquisition Connection onto use:, never into config', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const fixture = await create({
            node: { id: 'acq', type: 'acquisition', config: { include: ['*.csv'] } },
            typeLabel: 'acquisition',
            categoryLabel: 'Collector',
            bindKind: null,
        });
        const c = fixture.componentInstance;
        pickConnectionMode(fixture);
        form(fixture).form.patchValue({ connection: 'prod_sftp' });
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        c.save();
        expect(closed?.node.use).toBe('connection/prod_sftp');
        expect((closed?.node.config as Record<string, unknown>)?.['connection']).toBeUndefined();
        // The presentation half of the unification (2026-08-04): the node now also writes the DERIVED
        // connector, instead of leaving whatever the file happened to hold to disagree with the Connection.
        expect((closed?.node.config as Record<string, unknown>)?.['connector']).toBe('sftp');
    });

    /** Collecting from the local inbox is a real answer, and it derives `connector: local`. */
    it('writes connector "local" when the node collects from the inbox', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const fixture = await create({
            node: { id: 'acq', type: 'acquisition', config: { include: ['*.csv'] } },
            typeLabel: 'acquisition',
            categoryLabel: 'Collector',
            bindKind: null,
        });
        const c = fixture.componentInstance;
        expect(collector(fixture).mode()).toBe('local');
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        c.save();
        expect((closed?.node.config as Record<string, unknown>)['connector']).toBe('local');
        expect(closed?.node.use).toBeUndefined();
    });

    /**
     * A Connection id that names no saved profile is REFUSED, not written — the engine hands the
     * connector factory a profile it looks up by this name, so a phantom id fails at runtime instead.
     */
    it('refuses to save an acquisition node naming an unsaved Connection', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const fixture = await create({
            node: { id: 'acq', type: 'acquisition' },
            typeLabel: 'acquisition',
            categoryLabel: 'Collector',
            bindKind: null,
        });
        const c = fixture.componentInstance;
        pickConnectionMode(fixture);
        form(fixture).form.patchValue({ connection: 'ghost' });
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        c.save();
        expect(closed).toBeUndefined();
        expect(collector(fixture).error()).toContain('"ghost" is not a saved Connection');
    });

    /** Round-trip: the binding has to come BACK out of `use`, or reopening the dialog shows it blank. */
    it('seeds the Connection attribute from an existing use: binding', async () => {
        const c = (
            await create({
                node: { id: 'acq', type: 'acquisition', use: 'connection/lake_blob' },
                typeLabel: 'acquisition',
                categoryLabel: 'Collector',
                bindKind: null,
            })
        ).componentInstance;
        expect(c.schemaInitial['connection']).toBe('lake_blob');
    });

    /**
     * Switching back to the local inbox clears the binding — not "keeps the old one".
     *
     * <p>⚠ Since the 2026-08-04 unification the way to un-bind is the MODE TOGGLE, not blanking the
     * text: an empty box while still in Connection mode is a refusal ("Pick a Connection — or switch
     * to Local inbox"), exactly as on Onboarding's Collection stage. Silently writing a
     * Connection-less non-local collector is the state that used to fail at run time.
     */
    it('clears the binding when the node switches back to the local inbox', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const fixture = await create({
            node: { id: 'acq', type: 'acquisition', use: 'connection/lake_blob' },
            typeLabel: 'acquisition',
            categoryLabel: 'Collector',
            bindKind: null,
        });
        const c = fixture.componentInstance;
        expect(collector(fixture).mode()).toBe('connection');
        collector(fixture).setMode('local');
        fixture.detectChanges();
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        c.save();
        expect(closed?.node.use).toBeUndefined();
        expect((closed?.node.config as Record<string, unknown>)['connector']).toBe('local');
    });

    /** Blanking the picker without switching mode refuses, rather than writing a broken collector. */
    it('refuses a Connection-mode save with the Connection blanked', async () => {
        let closed: { node: AuthoredNode } | undefined;
        const fixture = await create({
            node: { id: 'acq', type: 'acquisition', use: 'connection/lake_blob' },
            typeLabel: 'acquisition',
            categoryLabel: 'Collector',
            bindKind: null,
        });
        const c = fixture.componentInstance;
        form(fixture).form.patchValue({ connection: '' });
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: (r) => (closed = r) };
        c.save();
        expect(closed).toBeUndefined();
        expect(collector(fixture).error()).toContain('Pick a Connection');
    });

    /**
     * The unification's structural guarantee: this dialog renders the SAME component Onboarding's
     * Collection stage renders — asserted by presence of the component, so a forked copy of the
     * chrome fails here even if it looks identical on screen. Every other node type keeps the
     * generic schema form.
     */
    it('renders the shared collector surface for an acquisition node', async () => {
        const fixture = await create({
            node: { id: 'acq', type: 'acquisition' },
            typeLabel: 'acquisition',
            categoryLabel: 'Collector',
            bindKind: null,
        });
        expect(fixture.debugElement.query(By.directive(CollectorConfigComponent))).not.toBeNull();
    });

    it('keeps the generic schema form for every other node type', async () => {
        const fixture = await create({
            node: { id: 'w', type: 'sink.persistent' },
            typeLabel: 'sink.persistent',
            categoryLabel: 'Sink',
            bindKind: null,
        });
        expect(fixture.debugElement.query(By.directive(CollectorConfigComponent))).toBeNull();
        expect(form(fixture).form.contains('format')).toBe(true);
    });

    /**
     * There is no free-text `use` box at all any more. Two controls must never both write `use`
     * (acquisition owns it via its Connection attribute) — and for every OTHER kind that reaches this
     * dialog the flat config has no home for a ref, so anything typed in it was refused at save with
     * `UNSUPPORTED_BINDING`. Its placeholder advertised the refused `transform/my_component` shape.
     */
    // ⚠ One `create()` per test — it configures the TestBed, which cannot be reconfigured once
    // instantiated, so these are two `it`s rather than a loop inside one.
    for (const data of [
        { node: { id: 'acq', type: 'acquisition' }, typeLabel: 'acquisition', categoryLabel: 'Collector' },
        { node: { id: 'f', type: 'transform.filter' }, typeLabel: 'transform.filter', categoryLabel: 'Transform' },
    ]) {
        it(`has no free-text use box on a ${data.node.type} node`, async () => {
            const fixture = await create({ ...data, bindKind: null });
            const labels = Array.from(fixture.nativeElement.querySelectorAll('mat-label')).map((l) =>
                (l as HTMLElement).textContent?.trim(),
            );
            expect(labels).not.toContain('Use (component ref)');
        });
    }

    // ── enrichment nodes (W4b): the dialog authors the REAL companion through the shared editor ──

    it('renders the shared enrichment editor + wiring form for an enrichment node', async () => {
        const fixture = await create({
            node: { id: 'enrich1', type: 'enrichment' },
            typeLabel: 'enrichment',
            categoryLabel: 'Transform',
            bindKind: null,
        });
        const c = fixture.componentInstance;
        expect(c.isEnrichment).toBe(true);
        expect(editor(fixture)).toBeTruthy();
        expect(c.enrichName.value).toBe('enrich1');
        expect(c.freeFormOpen()).toBe(false); // the editor is the primary surface, not the key/value grid
    });

    it('seeds a FRESH companion from the host pipeline instead of asking (P6-c)', async () => {
        const fixture = await create({
            node: { id: 'enrich1', type: 'enrichment' },
            typeLabel: 'enrichment',
            categoryLabel: 'Transform',
            bindKind: null,
            enrichmentHost: {
                pipelineId: 'orders',
                inputDatabase: 'spaces/demo/data/orders/database',
                inputFormat: 'PARQUET',
            },
        });
        const wiring = fixture.debugElement.query(By.directive(InspectoSchemaFormComponent))
            .componentInstance as InspectoSchemaFormComponent;
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
            typeLabel: 'enrichment',
            categoryLabel: 'Transform',
            bindKind: null,
            enrichmentHost: { pipelineId: 'orders' },
        });
        const wiring = fixture.debugElement.query(By.directive(InspectoSchemaFormComponent))
            .componentInstance as InspectoSchemaFormComponent;
        expect(wiring.form.get('input__database')?.value).toBe('');
        expect(wiring.form.get('triggers__on_pipeline')?.value).toBe('orders');
    });

    it('a host that supplies no pipeline context still opens the wiring form blank', async () => {
        const fixture = await create({
            node: { id: 'enrich1', type: 'enrichment' },
            typeLabel: 'enrichment',
            categoryLabel: 'Transform',
            bindKind: null,
        });
        const wiring = fixture.debugElement.query(By.directive(InspectoSchemaFormComponent))
            .componentInstance as InspectoSchemaFormComponent;
        expect(wiring.form.get('input__database')?.value).toBeFalsy();
        expect(wiring.form.get('triggers__on_pipeline')?.value).toBeFalsy();
    });

    it('save writes the companion, registers it, and closes bound by reference — config stays unmirrored', async () => {
        let closed: { node: AuthoredNode } | undefined;
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
        const fixture = await create(
            {
                node: { id: 'enrich1', type: 'enrichment' },
                typeLabel: 'enrichment',
                categoryLabel: 'Transform',
                bindKind: null,
            },
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

    it('hydrates a bound companion and round-trips its partition lists through a save', async () => {
        let closed: { node: AuthoredNode } | undefined;
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
            {
                node: { id: 'enrich1', type: 'enrichment', use: 'enrichment/orders_enrich' },
                typeLabel: 'enrichment',
                categoryLabel: 'Transform',
                bindKind: null,
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
        // The form now OWNS partitions (specced as `list` chips, 2026-08-13) — they survive because the
        // form hydrated them, not because they were unmodeled and copied past it.
        expect((draft['input'] as Record<string, unknown>)['partitions']).toEqual(['year', 'month']);
        expect((draft['output'] as Record<string, unknown>)['partitions']).toEqual(['year']);
        expect((draft['triggers'] as Record<string, unknown>)['on_pipeline']).toBe('orders');
        expect(closed?.node.use).toBe('enrichment/orders_enrich');
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
                typeLabel: 'enrichment',
                categoryLabel: 'Transform',
                bindKind: null,
                enrichmentHost: { pipelineId: 'orders', inputDatabase: 'derived/db', inputFormat: 'PARQUET' },
            },
            { read },
        );
        fixture.detectChanges(); // the async read landed
        const wiring = fixture.debugElement.query(By.directive(InspectoSchemaFormComponent))
            .componentInstance as InspectoSchemaFormComponent;
        expect(wiring.form.get('input__database')?.value).toBe('in/db');
        expect(wiring.form.get('input__partitions')?.value).toEqual(['dt']);
        // Even a trigger naming a DIFFERENT pipeline stands: this dialog edits the companion, and
        // re-pointing it at its host would be an unasked-for change to a deployed enrichment.
        expect(wiring.form.get('triggers__on_pipeline')?.value).toBe('other_pipeline');
    });

    // `EnrichmentConfig.fromMap` THROWS `Missing or invalid list` when either partitions key is absent,
    // so before they were specced a fresh enrichment authored here wrote a config that could never load
    // — it saved, then failed to register. An empty list is the legal "unpartitioned" value.
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
        const fixture = await create(
            {
                node: { id: 'enrich1', type: 'enrichment' },
                typeLabel: 'enrichment',
                categoryLabel: 'Transform',
                bindKind: null,
            },
            { write },
        );
        const c = fixture.componentInstance;
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: () => {} };
        editor(fixture).onSqlChange('SELECT 1 FROM input');
        const wiring = fixture.debugElement.query(By.directive(InspectoSchemaFormComponent))
            .componentInstance as InspectoSchemaFormComponent;
        wiring.form.get('input__database')?.setValue('in/db');
        wiring.form.get('output__database')?.setValue('out/db');

        c.save();
        const [, draft] = write.mock.calls[0] as [string, Record<string, unknown>];
        expect((draft['input'] as Record<string, unknown>)['partitions']).toEqual([]);
        expect((draft['output'] as Record<string, unknown>)['partitions']).toEqual([]);
    });

    // An `output.partitions` entry may be `{column, source}` (the sink shape, 2026-08-11) where `source`
    // declares event time and drives the recorded bounds. The chips control is string[]-only, and a
    // specced key is replaced wholesale — so without the re-marry, authoring the grain would silently
    // drop `source` and the enrichment would stop recording bounds with no feedback.
    it('keeps an output partition source across a save that only touches other fields', async () => {
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
            {
                node: { id: 'e', type: 'enrichment', use: 'enrichment/orders_enrich' },
                typeLabel: 'enrichment',
                categoryLabel: 'Transform',
                bindKind: null,
            },
            { read, write },
        );
        const c = fixture.componentInstance;
        (c as unknown as { ref: { close: (r: { node: AuthoredNode }) => void } }).ref = { close: () => {} };
        fixture.detectChanges();

        c.save();
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
            {
                node: { id: 'e', type: 'enrichment', use: 'enrichment/x_enrich' },
                typeLabel: 'enrichment',
                categoryLabel: 'Transform',
                bindKind: null,
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

    /**
     * The inline test (`POST /components/{family}/preview`). ⚠ Its predecessor — "Test <component>…" —
     * was DEAD UI: it gated on `data.bindKind`, which is `bindKindFor(category)` and therefore null for
     * every node that actually reaches this dialog (PARSE goes to the grammar editor). The gate is the
     * node's own TYPE now, plus rows to run over.
     */
    describe('inline test', () => {
        /** Stub the preview arm under test; `list`/`get` are load-bearing scaffolding the dialog constructs with. */
        const withPreview = (over: Record<string, unknown>): void => {
            TestBed.overrideProvider(ComponentsService, {
                useValue: { list: () => of([]), get: () => of(GRAMMARS[0]), ...over },
            });
        };

        const filter = (): Partial<NodeConfigData> => ({
            node: { id: 'flt', type: 'transform.filter' },
            typeLabel: 'transform.filter',
            categoryLabel: 'Transformer',
            bindKind: null,
            sampleRows: [{ qty: '2' }, { qty: '1' }],
        });

        it('is not offered without rows — a test over no data would report success over nothing', async () => {
            const fixture = await create({ ...filter(), sampleRows: [] });
            expect(fixture.componentInstance.canTestInline).toBe(false);
            expect(fixture.nativeElement.textContent).not.toContain('Test this Step');
        });

        it('is not offered for a family the route has no preview for', async () => {
            const fixture = await create({
                node: { id: 'e', type: 'enrichment' },
                typeLabel: 'enrichment',
                categoryLabel: 'Transform',
                bindKind: null,
                sampleRows: [{ qty: '2' }],
            });
            expect(fixture.componentInstance.testFamily).toBeNull();
        });

        it('sends the node type inside config and renders the per-relation counts', async () => {
            const previewTransform = vi.fn(() =>
                of({ inputColumns: ['qty'], relations: [{ rel: 'data', rowCount: 1, rows: [{ qty: '2' }] }] }),
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
            expect(fixture.nativeElement.textContent).toContain("out 'data': 1 row(s)");
        });

        it('reports a sink preview as its store plus any warnings', async () => {
            const previewSink = vi.fn(() =>
                of({ store: null, rowCount: 2, rows: [], warnings: ["sink declares no 'store' name"] }),
            );
            withPreview({ previewSink });
            const fixture = await create({
                node: { id: 'out', type: 'sink.persistent' },
                typeLabel: 'sink.persistent',
                categoryLabel: 'Sink',
                bindKind: null,
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

    it('has no a11y violations (schema-backed type)', async () => {
        const fixture = await create({
            node: { id: 'w', type: 'sink.file' },
            typeLabel: 'sink.file',
            categoryLabel: 'Sink',
            bindKind: null,
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('has no a11y violations (enrichment node)', async () => {
        const fixture = await create({
            node: { id: 'e', type: 'enrichment' },
            typeLabel: 'enrichment',
            categoryLabel: 'Transform',
            bindKind: null,
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
