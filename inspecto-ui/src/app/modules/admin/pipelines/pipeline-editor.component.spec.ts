import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { MatDialog } from '@angular/material/dialog';
import { PipelineEditorComponent } from './pipeline-editor.component';
import { AuthoredPipeline, ComponentsService, ConfigService, LensService, PipelinesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';

/** A canvas double — the real G6 host can't instantiate in jsdom, so we assert the mutation calls. */
function canvasMock() {
    return {
        addNode: vi.fn(),
        addNodeAtCenter: vi.fn(),
        addEdge: vi.fn(),
        removeElement: vi.fn(),
        updateNodeLabel: vi.fn(),
        setNodeStatus: vi.fn(),
    };
}

const FLOW: AuthoredPipeline = {
    name: 'demo',
    active: false,
    nodes: [
        { id: 'src', type: 'acquisition', config: { source_store: 'events' } },
        { id: 'flt', type: 'transform.filter', config: { where: 'amt >= 100' } },
    ],
    edges: [{ from: 'src', rel: 'data', to: 'flt' }],
};

describe('PipelineEditorComponent', () => {
    let api: {
        list: ReturnType<typeof vi.fn>;
        nodeTypes: ReturnType<typeof vi.fn>;
        stepTypes: ReturnType<typeof vi.fn>;
        pipelineGraphRaw: ReturnType<typeof vi.fn>;
        savePipelineGraph: ReturnType<typeof vi.fn>;
        provenanceBatches: ReturnType<typeof vi.fn>;
        provenance: ReturnType<typeof vi.fn>;
        saveAsTemplate: ReturnType<typeof vi.fn>;
        label: ReturnType<typeof vi.fn>;
        rename: ReturnType<typeof vi.fn>;
        document: ReturnType<typeof vi.fn>;
        documentFingerprint: ReturnType<typeof vi.fn>;
        settings: ReturnType<typeof vi.fn>;
    };
    let config: {
        write: ReturnType<typeof vi.fn>;
        registerPipeline: ReturnType<typeof vi.fn>;
        remove: ReturnType<typeof vi.fn>;
    };
    let dialog: { open: ReturnType<typeof vi.fn> };
    let components: { list: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn> };
    let toast: {
        success: ReturnType<typeof vi.fn>;
        error: ReturnType<typeof vi.fn>;
        info: ReturnType<typeof vi.fn>;
        warning: ReturnType<typeof vi.fn>;
    };

    beforeEach(() => {
        // LensService persists to localStorage; clear it so a lens set by one test/file can't leak into another.
        localStorage.removeItem('inspecto.currentLens');
        api = {
            list: vi.fn().mockReturnValue(of([])),
            nodeTypes: vi.fn().mockReturnValue(
                of([
                    {
                        type: 'transform.filter',
                        category: 'TRANSFORM',
                        label: 'Filter',
                        description: '',
                        accepts: ['data'],
                        emits: ['data'],
                        emitsNamedRoutes: false,
                        lowerable: true,
                    },
                    // Unlowerable — must be kept OUT of the palette but IN the type maps (unsupported()).
                    {
                        type: 'adapter',
                        category: 'TRANSFORM',
                        label: 'Adapter',
                        description: '',
                        accepts: ['data'],
                        emits: ['data'],
                        emitsNamedRoutes: false,
                        lowerable: false,
                    },
                ]),
            ),
            // S4 dual-read: an "old server" by default — the editor must fall back to RECIPE_VERBS.
            stepTypes: vi.fn().mockReturnValue(throwError(() => new Error('404'))),
            pipelineGraphRaw: vi.fn().mockReturnValue(of(structuredClone(FLOW))),
            savePipelineGraph: vi
                .fn()
                .mockReturnValue(of({ written: true, path: 'demo_pipeline.toon', name: 'demo', findings: [] })),
            provenanceBatches: vi.fn().mockReturnValue(of([])),
            provenance: vi.fn().mockReturnValue(of([])),
            saveAsTemplate: vi.fn().mockReturnValue(
                of({
                    written: true,
                    path: 'demo_copy_pipeline.toon',
                    id: 'demo_copy',
                    source: 'demo',
                    template: true,
                    notes: [],
                }),
            ),
            label: vi
                .fn()
                .mockReturnValue(
                    of({ written: true, path: 'demo_pipeline.toon', id: 'demo', name: 'Demo (EU)', stampedId: true }),
                ),
            rename: vi.fn().mockReturnValue(
                of({
                    written: true,
                    oldId: 'demo',
                    id: 'demo_eu',
                    name: 'demo',
                    path: 'demo_eu_pipeline.toon',
                    ledgerRowsMoved: 0,
                    auditFilesRenamed: 0,
                    dependentsRewritten: 0,
                }),
            ),
            document: vi.fn().mockReturnValue(of({ body: new Blob(['# Pipeline: demo']) })),
            documentFingerprint: vi.fn().mockReturnValue('0123456789abcdef0123'),
            // P6-b: go-live reads stream-vs-reference from the D8 settings endpoint — NOT from
            // PipelineSummary.produces, which is the list of stores the pipeline produces.
            settings: vi.fn().mockReturnValue(of({ produces: 'stream', reference: null })),
        };
        config = {
            write: vi.fn().mockReturnValue(of({ written: true, path: 'x_pipeline.toon', name: 'x' })),
            registerPipeline: vi.fn().mockReturnValue(of({ registered: true })),
            remove: vi.fn().mockReturnValue(of({ deleted: true })),
        };
        dialog = { open: vi.fn() };
        components = { list: vi.fn().mockReturnValue(of([])), create: vi.fn() };
        toast = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() };
        TestBed.configureTestingModule({
            imports: [PipelineEditorComponent],
            providers: [
                provideNoopAnimations(),
                { provide: PipelinesService, useValue: api },
                { provide: ConfigService, useValue: config },
                { provide: ComponentsService, useValue: components },
                { provide: ToastrService, useValue: toast },
                {
                    provide: InspectoConfirmService,
                    // P6-b: activate/deactivate now confirm through the NEUTRAL confirm(), not confirmDestructive.
                    useValue: {
                        confirm: vi.fn().mockResolvedValue(true),
                        confirmDestructive: vi.fn().mockResolvedValue(true),
                    },
                },
                { provide: MatDialog, useValue: dialog },
            ],
        });
        // ⚠ Must be an OVERRIDE, not just the provider above: since P3a the editor imports the Parse
        // definition pane → the shared Grammar editor → `<inspecto-data-table>`, which injects the
        // REAL MatDialog. A plain `provide` is then silently ignored and every dialog.open in this
        // suite dies inside Material with "Cannot read properties of undefined (reading 'push')".
        TestBed.overrideProvider(MatDialog, { useValue: dialog });
    });

    /** Build the component, run ngOnInit, and inject a canvas double (no live G6). */
    function make(): PipelineEditorComponent {
        const c = TestBed.createComponent(PipelineEditorComponent).componentInstance;
        c.ngOnInit();
        (c as unknown as { canvas: unknown }).canvas = canvasMock();
        return c;
    }
    function canvasOf(c: PipelineEditorComponent) {
        return (c as unknown as { canvas: ReturnType<typeof canvasMock> }).canvas;
    }

    /**
     * P6-b — publish as a toolbar action. The editor could already flip `active`, but the wizard's
     * go-live did three more things, and the Dataset hop is the one whose absence is SILENT: without
     * it a stream goes live and its landed data is simply never queryable in the Catalog.
     */
    describe('go-live (P6-b)', () => {
        function confirmOf() {
            return TestBed.inject(InspectoConfirmService) as unknown as { confirm: ReturnType<typeof vi.fn> };
        }

        it('confirms, then registers the Dataset over the landed store', async () => {
            const c = make();
            c.select('demo');
            await c.activate();

            expect(confirmOf().confirm).toHaveBeenCalled();
            expect(api.savePipelineGraph).toHaveBeenCalledWith('demo', expect.objectContaining({ active: true }));
            expect(components.create).toHaveBeenCalledWith('dataset', expect.objectContaining({ physicalRef: 'demo' }));
        });

        it('does not activate when the confirm is declined', async () => {
            const c = make();
            c.select('demo');
            confirmOf().confirm.mockResolvedValueOnce(false);
            await c.activate();

            expect(api.savePipelineGraph).not.toHaveBeenCalled();
            expect(components.create).not.toHaveBeenCalled();
        });

        /** ⚠ A Reference's store is consumed by name in enrichments, not queried as a raw Dataset. */
        it('registers nothing for a reference pipeline', async () => {
            api.settings.mockReturnValue(of({ produces: 'reference', reference: null }));
            const c = make();
            c.select('demo');
            await c.activate();

            expect(api.savePipelineGraph).toHaveBeenCalled(); // it still goes live
            expect(components.create).not.toHaveBeenCalled();
        });

        /** ⛔ Unknown kind ⇒ do not guess: registering over a Reference's store pollutes the Catalog. */
        it('warns and registers nothing when the kind cannot be read', async () => {
            api.settings.mockReturnValue(throwError(() => new Error('boom')));
            const c = make();
            c.select('demo');
            await c.activate();

            expect(components.create).not.toHaveBeenCalled();
            expect(toast.warning).toHaveBeenCalled();
        });

        it('deactivation confirms but leaves the Dataset registered', async () => {
            const c = make();
            c.select('demo');
            await c.deactivate();

            expect(confirmOf().confirm).toHaveBeenCalled();
            expect(api.savePipelineGraph).toHaveBeenCalledWith('demo', expect.objectContaining({ active: false }));
            expect(components.create).not.toHaveBeenCalled();
        });
    });

    describe('palette catalog', () => {
        it('offers only lowerable types, while the type maps keep the full catalog', () => {
            const c = make();
            const offered = c.paletteGroups().flatMap((g) => g.types.map((t) => t.type));
            expect(offered).toEqual(['transform.filter']);
            // The grandfathered `adapter` node still renders + flags on an opened graph: the
            // unsupported-nodes banner derives from the FULL catalog, not the filtered palette.
            c.select('demo');
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, { id: 'legacy', type: 'adapter', config: {} }] }));
            expect(c.unsupportedNodes().map((n) => n.id)).toEqual(['legacy']);
        });

        /**
         * P5-b: a RETIRED type is a third case, and the reason `authorable` exists. It is still
         * lowerable — an editor opened before P5-a holds a graph carrying a `transform.dedup.marker`,
         * and that graph must still save — so it must NOT be flagged unsupported, yet the palette
         * must never offer another. Filtering the palette on `lowerable` cannot express both.
         */
        it('hides a retired type from the palette while keeping it saveable', () => {
            api.nodeTypes.mockReturnValue(
                of([
                    {
                        type: 'transform.filter',
                        category: 'TRANSFORM',
                        label: 'Filter',
                        description: '',
                        accepts: ['data'],
                        emits: ['data'],
                        emitsNamedRoutes: false,
                        lowerable: true,
                        authorable: true,
                    },
                    {
                        type: 'transform.dedup.marker',
                        category: 'TRANSFORM',
                        label: 'Dedup (marker)',
                        description: '',
                        accepts: ['data'],
                        emits: ['data'],
                        emitsNamedRoutes: false,
                        lowerable: true,
                        authorable: false,
                    },
                ]),
            );
            const c = make();
            expect(c.paletteGroups().flatMap((g) => g.types.map((t) => t.type))).toEqual(['transform.filter']);

            c.select('demo');
            c.model.update((m) => ({
                ...m!,
                nodes: [...m!.nodes, { id: 'dedup_marker', type: 'transform.dedup.marker', config: {} }],
            }));
            expect(c.unsupportedNodes()).toEqual([]);
        });
    });

    /**
     * Definition-surface P1: the collector path opens in the right-dock definition drawer, never in
     * a popup — the first slice of the one-host plan. Every other kind keeps its dialog for now.
     */
    describe('definition drawer (collector path)', () => {
        it('routes an acquisition node to the drawer, not a dialog', async () => {
            const c = make();
            c.select('demo');
            await c.openDefinition(c.model()!.nodes[0]);
            c.openNodeConfig(c.model()!.nodes[0]); // the double-click path lands in the same place
            expect(dialog.open).not.toHaveBeenCalled();
            expect(c.definitionNode()?.id).toBe('src');
            expect(c.rightTab()).toBe('properties');
            expect(c.inspectorOpen()).toBe(true);
        });

        it('keeps the dialog for every other node kind', () => {
            const c = make();
            c.select('demo');
            dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
            c.openNodeConfig(c.model()!.nodes[1]); // transform.filter
            expect(dialog.open).toHaveBeenCalled();
            expect(c.definitionNode()).toBeNull();
        });

        it('an applied definition patches the model in memory and never persists (D2)', async () => {
            const c = make();
            c.select('demo');
            await c.openDefinition(c.model()!.nodes[0]);
            c.onDefinitionApplied({ id: 'src', type: 'acquisition', config: { connector: 'local' } });
            expect(c.model()!.nodes.find((n) => n.id === 'src')!.config).toEqual({ connector: 'local' });
            expect(c.dirty()).toBe(true);
            expect(api.savePipelineGraph).not.toHaveBeenCalled();
            expect(c.definitionDirty()).toBe(false); // Apply consumed the edits; the drawer stays open
            expect(c.definitionNode()?.config).toEqual({ connector: 'local' });
        });

        it('discard recreates the pane (epoch bump) and clears the dirty flag', async () => {
            const c = make();
            c.select('demo');
            await c.openDefinition(c.model()!.nodes[0]);
            const before = c.definitionEpoch();
            c.definitionDirty.set(true);
            c.discardDefinition();
            expect(c.definitionEpoch()).toBe(before + 1);
            expect(c.definitionDirty()).toBe(false);
        });

        /** P3a: the delimited parser defines in the drawer too — when its Grammar lives inline. */
        it('routes an inline parser.delimited node to the drawer, not the grammar dialog', () => {
            const c = make();
            c.select('demo');
            const node = { id: 'parse', type: 'parser.delimited', config: { parsing: { frontend: 'delimited' } } };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
            c.openNodeConfig(node);
            expect(dialog.open).not.toHaveBeenCalled();
            expect(c.definitionNode()?.id).toBe('parse');
        });

        /** P3b: the same routing for the second per-format subtype — one predicate, one pane. */
        it('routes an inline parser.fixedwidth node to the drawer, not the grammar dialog', () => {
            const c = make();
            c.select('demo');
            const node = {
                id: 'parse',
                type: 'parser.fixedwidth',
                config: { parsing: { frontend: 'fixedwidth', fixedwidth: { fields: [] } } },
            };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
            c.openNodeConfig(node);
            expect(dialog.open).not.toHaveBeenCalled();
            expect(c.definitionNode()?.id).toBe('parse');
        });

        /**
         * P3d: the two remaining built-in frontends route the same way. Pinned per type rather than
         * per format because the template no longer enumerates the subtypes at all — `isDrawerParse`
         * over `PARSE_NODE_FRONTENDS` is the whole rule, and this is what proves a new entry reaches
         * the pane without a template change.
         */
        it.each([
            ['parser.json', { frontend: 'json', json: { format: 'newline' } }],
            ['parser.text_regex', { frontend: 'text_regex', text_regex: { pattern: '(?<ID>\\w+)' } }],
            // P3d slice D: the generic plugin subtype routes the same way — no dedicated case needed,
            // which is exactly the point of the collapsed template.
            [
                'parser.plugin',
                { frontend: 'plugin', plugin: { ingester: 'com.example.acme.AcmeFeedIngester' } },
            ],
        ])('routes an inline %s node to the drawer, not the grammar dialog', (type, parsing) => {
            const c = make();
            c.select('demo');
            const node = { id: 'parse', type, config: { parsing } };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
            c.openNodeConfig(node);
            expect(dialog.open).not.toHaveBeenCalled();
            expect(c.definitionNode()?.id).toBe('parse');
        });

        /**
         * P3b operator decision: a BINARY fixed-width node lifts to `parser.fixedwidth` like any other,
         * but its geometry lives in `processing.ingester_config` and is executed by
         * `FixedWidthRecordIngester` — the pane's `fixedwidth.fields[]` slice table would govern
         * nothing. It keeps the dialog. ⚠ Same node TYPE as the case above; only `record` differs.
         */
        it('keeps a BINARY (record: bytes) fixed-width node on the dialog', () => {
            const c = make();
            c.select('demo');
            dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
            const node = {
                id: 'parse',
                type: 'parser.fixedwidth',
                config: {
                    parsing: { frontend: 'fixedwidth', fixedwidth: { record: 'bytes', record_length: 24 } },
                },
            };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
            c.openNodeConfig(node);
            expect(dialog.open).toHaveBeenCalledTimes(1);
            expect(c.definitionNode()).toBeNull();
        });

        /** S1: the HOST owns the template write (the pane only emits), and it must leave the node
         *  completely alone — a template is a copy, never a `use: grammar/<id>` binding. */
        it('saves a Grammar template without touching the node', () => {
            const c = make();
            c.select('demo');
            const node = { id: 'parse', type: 'parser.delimited', config: { parsing: { frontend: 'delimited' } } };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
            c.openNodeConfig(node);
            dialog.open.mockReturnValue({ afterClosed: () => of({ id: 'pipe_delimited' }) });
            components.create.mockReturnValue(of({ ref: 'grammar/pipe_delimited', name: 'pipe_delimited' }));

            c.saveGrammarAsTemplate({ frontend: 'delimited', delimited: { delimiter: '|' } });

            expect(components.create).toHaveBeenCalledWith('grammar', {
                id: 'pipe_delimited',
                frontend: 'delimited',
                delimited: { delimiter: '|' },
            });
            const saved = c.model()!.nodes.find((n) => n.id === 'parse')!;
            expect(saved.use).toBeUndefined();
            expect(saved.config!['parsing']).toEqual({ frontend: 'delimited' });
            expect(c.dirty()).toBe(false); // a template write is not a graph edit
        });

        /** The palette seeds `{id, type}` and nothing else — the drawer predicate keys on the type
         *  and the absence of a `grammar/` use, so a config-less fresh drop reaches the drawer too. */
        it('routes a palette-fresh parser.delimited node to the drawer', () => {
            const c = make();
            c.select('demo');
            c.addFromPalette('parser.delimited');
            const node = c.model()!.nodes.at(-1)!;
            expect(node.config).toBeUndefined();
            c.openNodeConfig(node);
            expect(dialog.open).not.toHaveBeenCalled();
            expect(c.definitionNode()?.id).toBe(node.id);
        });

        /** S3: a bound delimited node now reaches the drawer too, materialised into an inline COPY of
         *  its component — and Apply drops the binding (D4, editing migrates it). */
        it('materialises a grammar-bound delimited node into the drawer and migrates it on Apply', () => {
            const c = make();
            c.select('demo');
            c.grammarTemplates.set([
                { name: 'pipes', ref: 'grammar/pipes', type: 'grammar', content: { delimiter: '|', has_header: false } },
            ] as never);
            const node = { id: 'parse', type: 'parser.delimited', use: 'grammar/pipes', config: {} };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));

            c.openNodeConfig(node);

            expect(dialog.open).not.toHaveBeenCalled();
            // The drawer shows an inline copy; the MODEL is untouched until Apply.
            expect(c.definitionNode()!.config!['parsing']).toEqual({ delimited: { delimiter: '|', has_header: false } });
            expect(c.model()!.nodes.find((n) => n.id === 'parse')!.use).toBe('grammar/pipes');

            c.onDefinitionApplied({ id: 'parse', type: 'parser.delimited', config: { parsing: { frontend: 'delimited' } } });
            expect(c.model()!.nodes.find((n) => n.id === 'parse')!.use).toBeUndefined();
        });

        /** A binding with nothing behind it has no faithful copy to migrate to — seeding the drawer
         *  with defaults would invent a Grammar, so it stays on the dialog. */
        it('keeps a DANGLING grammar binding on the dialog', () => {
            const c = make();
            c.select('demo');
            c.grammarTemplates.set([]);
            dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
            const node = { id: 'parse', type: 'parser.delimited', use: 'grammar/missing', config: {} };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));

            c.openNodeConfig(node);

            expect(dialog.open).toHaveBeenCalledTimes(1);
            expect(c.definitionNode()).toBeNull();
        });

        /** ⚠ Since S3 the ONLY parse node left on the dialog is the plain `parser` type — it has no
         *  drawer pane yet (that is P3d's slice, which then retires the dialog entirely). A bound
         *  `parser.delimited` no longer belongs here; it has its own migration case above. */
        it('keeps the dialog for the plain parser type', () => {
            const c = make();
            c.select('demo');
            dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
            const plain = { id: 'pp', type: 'parser', config: { schema_file: 's.toon' } };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, plain] }));
            c.openNodeConfig(plain);
            expect(dialog.open).toHaveBeenCalledTimes(1);
            expect(c.definitionNode()).toBeNull();
        });

        it('switching tabs guards unapplied drawer edits and closes the drawer', async () => {
            const confirm = TestBed.inject(InspectoConfirmService) as unknown as {
                confirmDestructive: ReturnType<typeof vi.fn>;
            };
            const c = make();
            c.select('demo');
            api.pipelineGraphRaw.mockReturnValue(of({ name: 'other', active: false, nodes: [], edges: [] }));
            c.select('other');
            await c.activateTab('demo');
            await c.openDefinition(c.model()!.nodes[0]);
            c.definitionDirty.set(true);

            confirm.confirmDestructive.mockResolvedValueOnce(false);
            await c.activateTab('other');
            expect(c.selectedId()).toBe('demo'); // refused — nothing moved
            expect(c.definitionNode()).not.toBeNull();

            confirm.confirmDestructive.mockResolvedValueOnce(true);
            await c.activateTab('other');
            expect(c.selectedId()).toBe('other');
            expect(c.definitionNode()).toBeNull();
        });
    });

    /**
     * The Pipeline Document export (ELT amendment §5.1, S6a). What matters here is that the sign-off
     * fingerprint actually reaches the operator, and that a READ failing never latches the editor into
     * the writes-disabled state a WRITE failure sets — they take different error paths for that reason.
     */
    describe('export document', () => {
        function stubDownload() {
            URL.createObjectURL = vi.fn().mockReturnValue('blob:doc');
            URL.revokeObjectURL = vi.fn();
            // spyOn a prototype method returns the SAME spy across tests in this file — clear it, or
            // one test sees the clicks of the ones before it.
            const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
            click.mockClear();
            return click;
        }

        it('downloads the markdown and reports the config fingerprint that binds it', () => {
            const click = stubDownload();
            const c = make();
            c.select('demo');
            c.exportDocument();

            expect(api.document).toHaveBeenCalledWith('demo');
            expect(click).toHaveBeenCalled();
            expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:doc');
            expect(toast.success).toHaveBeenCalledWith("Exported 'demo.md' — config fingerprint 0123456789ab");
            expect(c.exportingDocument()).toBe(false);
        });

        it('exports nothing when no pipeline is selected', () => {
            const c = make();
            c.exportDocument();
            expect(api.document).not.toHaveBeenCalled();
        });

        it('a server that sends no fingerprint still exports, without inventing one', () => {
            stubDownload();
            api.documentFingerprint.mockReturnValue(null);
            const c = make();
            c.select('demo');
            c.exportDocument();
            expect(toast.success).toHaveBeenCalledWith("Exported 'demo.md'");
        });

        it('an empty body is an error, not a zero-byte download', () => {
            const click = stubDownload();
            api.document.mockReturnValue(of({ body: null }));
            const c = make();
            c.select('demo');
            c.exportDocument();
            expect(click).not.toHaveBeenCalled();
            expect(toast.error).toHaveBeenCalledWith('The server returned an empty document');
        });

        it('a failed export toasts but must NOT mark the editor read-only — it is a read', () => {
            api.document.mockReturnValue(throwError(() => ({ status: 503 })));
            const c = make();
            c.select('demo');
            c.exportDocument();

            expect(toast.error).toHaveBeenCalled();
            expect(c.unavailable()).toBe(false);
            expect(c.exportingDocument()).toBe(false);
        });
    });

    describe('open set / tabs', () => {
        it('opens nothing on arrival — listing is cheap, lifting a graph is not', () => {
            api.list.mockReturnValue(
                of([
                    { name: 'a', active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] },
                    { name: 'b', active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] },
                ]),
            );
            const c = make();
            expect(c.flows()).toHaveLength(2);
            expect(c.openIds()).toEqual([]);
            expect(c.selectedId()).toBeNull();
            expect(api.pipelineGraphRaw).not.toHaveBeenCalled();
        });

        it('select opens a tab and makes it active', () => {
            const c = make();
            c.select('demo');
            expect(c.openIds()).toEqual(['demo']);
            expect(c.selectedId()).toBe('demo');
            expect(api.pipelineGraphRaw).toHaveBeenCalledWith('demo');
        });

        it("switching tabs keeps each tab's unsaved edits — the data-loss trap", () => {
            const c = make();
            c.select('demo');
            c.model.update((m) => ({
                ...m!,
                nodes: [...m!.nodes, { id: 'extra', type: 'transform.filter', config: {} }],
            }));
            c.dirty.set(true);
            TestBed.tick(); // flush the per-tab dirty effect

            api.pipelineGraphRaw.mockReturnValue(of({ name: 'other', active: false, nodes: [], edges: [] }));
            c.select('other');
            expect(c.selectedId()).toBe('other');
            expect(c.model()!.nodes).toHaveLength(0);
            expect(c.dirty()).toBe(false);

            // Back to the first tab: the edit is still there, and still flagged dirty.
            c.activateTab('demo');
            expect(c.selectedId()).toBe('demo');
            expect(c.model()!.nodes.some((n) => n.id === 'extra')).toBe(true);
            expect(c.dirty()).toBe(true);
            // Restoring from cache must not refetch.
            expect(api.pipelineGraphRaw).toHaveBeenCalledTimes(2);
        });

        it('closing a clean tab drops it and activates a neighbour', () => {
            const c = make();
            c.select('demo');
            api.pipelineGraphRaw.mockReturnValue(of({ name: 'other', active: false, nodes: [], edges: [] }));
            c.select('other');
            expect(c.openIds()).toEqual(['demo', 'other']);

            void c.closeTab('other');
            expect(c.openIds()).toEqual(['demo']);
            expect(c.selectedId()).toBe('demo');
        });

        it('closing the last tab returns to the empty canvas', () => {
            const c = make();
            c.select('demo');
            void c.closeTab('demo');
            expect(c.openIds()).toEqual([]);
            expect(c.selectedId()).toBeNull();
            expect(c.model()).toBeNull();
        });

        it('re-listing after a save leaves open tabs alone but drops deleted ones', () => {
            const c = make();
            c.select('demo');
            c.select('gone');
            api.list.mockReturnValue(
                of([{ name: 'demo', active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] }]),
            );
            c.load();
            expect(c.openIds()).toEqual(['demo']);
        });
    });

    it('readOnly withholds authoring even when the lens would allow it', () => {
        const c = make();
        c.readOnly = true;
        c.model.set(structuredClone(FLOW));
        expect(c.canAuthor()).toBe(false);

        c.onDropAdd({ type: 'transform.filter', x: 10, y: 20 });
        expect(c.model()!.nodes).toHaveLength(2); // unchanged
        expect(c.dirty()).toBe(false);
    });

    it('dropping a palette node adds it to the model and the canvas', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        c.onDropAdd({ type: 'transform.filter', x: 10, y: 20 });
        const m = c.model()!;
        expect(m.nodes).toHaveLength(3);
        expect(m.nodes[2].type).toBe('transform.filter');
        expect(c.dirty()).toBe(true);
        expect(canvasOf(c).addNode).toHaveBeenCalled();
    });

    it('clicking a palette node adds it at the canvas centre (the no-mouse path)', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        c.addFromPalette('transform.filter');
        const m = c.model()!;
        expect(m.nodes).toHaveLength(3);
        expect(m.nodes[2].type).toBe('transform.filter');
        expect(canvasOf(c).addNodeAtCenter).toHaveBeenCalled();
        expect(c.dirty()).toBe(true);
    });

    it('two-click connect adds a new edge between the two nodes', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        c.selectedNode.set(c.model()!.nodes[1]); // flt
        c.armConnect();
        c.onNodeSelected('src'); // flt -> src is not the existing src -> flt edge
        expect(c.model()!.edges.filter((e) => e.from === 'flt' && e.to === 'src')).toHaveLength(1);
        expect(c.connectFrom()).toBeNull();
        expect(canvasOf(c).addEdge).toHaveBeenCalled();
    });

    it('deleting a selected node drops it and its edges', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        c.selectedNode.set(c.model()!.nodes[0]); // src (has edge src->flt)
        c.onDeleteKey();
        const m = c.model()!;
        expect(m.nodes.find((n) => n.id === 'src')).toBeUndefined();
        expect(m.edges).toHaveLength(0);
        expect(canvasOf(c).removeElement).toHaveBeenCalledWith('src');
    });

    it('save PUTs the model and clears the dirty flag', () => {
        const c = make();
        c.selectedId.set('demo');
        c.model.set(structuredClone(FLOW));
        c.dirty.set(true);
        c.save();
        expect(api.savePipelineGraph).toHaveBeenCalledWith('demo', FLOW);
        expect(c.dirty()).toBe(false);
    });

    it('lists every save refusal in the Validation dock, not just the first as a toast', () => {
        const c = make();
        c.selectedId.set('demo');
        c.model.set(structuredClone(FLOW));
        api.savePipelineGraph.mockReturnValue(
            throwError(() => ({
                status: 422,
                error: {
                    error: {
                        details: {
                            refusals: [
                                {
                                    code: 'UNSUPPORTED_NODE',
                                    nodeId: 'a',
                                    message: "no home for a 'transform.split' node",
                                },
                                {
                                    code: 'UNSUPPORTED_NODE',
                                    nodeId: 'b',
                                    message: "no home for a 'transform.merge' node",
                                },
                                { code: 'NO_PARSER', message: 'the pipeline has no parser' },
                            ],
                        },
                    },
                },
            })),
        );

        c.save();

        // All three, persistent — the old behaviour was three save→fix→save cycles.
        expect(c.findings()).toHaveLength(3);
        expect(c.findings().every((f) => f.severity === 'error')).toBe(true);
        expect(c.findings().map((f) => f.nodeId)).toEqual(['a', 'b', undefined]);
        expect(c.bottomTab()).toBe('validation');
    });

    it('flags a grandfathered pipeline whose nodes cannot be lowered, without locking the fix out', () => {
        const c = make();
        c.model.set({
            name: 'legacy',
            active: false,
            nodes: [
                { id: 'src', type: 'acquisition', config: {} },
                { id: 'sp', type: 'transform.split', config: {} },
                { id: 'mg', type: 'transform.merge', config: {} },
            ],
            edges: [],
        });

        // Before the catalog lands there is nothing to judge against — stay quiet rather than cry wolf.
        expect(c.unsupportedNodes()).toHaveLength(0);

        c['typeLowerable'].set(
            new Map([
                ['acquisition', true],
                ['transform.split', false],
                ['transform.merge', false],
            ]),
        );
        expect(c.unsupportedNodes().map((n) => n.id)).toEqual(['sp', 'mg']);
        expect(c.unsupportedTypeList()).toBe('transform.split, transform.merge');
    });

    it('a 503 on create marks the editor read-only', () => {
        config.write.mockReturnValue(throwError(() => ({ status: 503 })));
        const c = make();
        c.startNew();
        c.newName.setValue('x');
        c.createPipeline();
        expect(c.unavailable()).toBe(true);
    });

    it('selecting a flow loads its last-run overlay and paints edge counts (T17)', () => {
        api.provenanceBatches.mockReturnValue(of([{ batchId: 'b2', runTs: '2026-07-18T10:00:00Z', totalRows: 50 }]));
        api.provenance.mockReturnValue(of([{ nodeId: 'src', rel: 'data', rowCount: 50 }]));
        const c = make();
        c.select('demo');
        expect(api.provenanceBatches).toHaveBeenCalledWith('demo');
        expect(api.provenance).toHaveBeenCalledWith('demo', 'b2');
        expect(c.lastRunBatch()).toEqual({ batchId: 'b2', runTs: '2026-07-18T10:00:00Z', totalRows: 50 });
        const edge = c.g6Data()!.edges.find((e) => e.source === 'src' && e.target === 'flt');
        expect(edge!.data).toEqual({ kind: 'data · 50', weight: 50 });
        c.selectedNode.set(c.model()!.nodes[0]); // src
        expect(c.selectedNodeLastRun()).toEqual({ rowCount: 50, runTs: '2026-07-18T10:00:00Z' });
    });

    it('a node absent from the last run has no overlay (null, not zero)', () => {
        api.provenanceBatches.mockReturnValue(of([{ batchId: 'b2', runTs: '2026-07-18T10:00:00Z', totalRows: 50 }]));
        api.provenance.mockReturnValue(of([{ nodeId: 'src', rel: 'data', rowCount: 50 }]));
        const c = make();
        c.select('demo');
        c.selectedNode.set(c.model()!.nodes[1]); // flt — never emitted in this run
        expect(c.selectedNodeLastRun()).toBeNull();
    });

    it('no recorded run (empty batch list) leaves the overlay off, no error', () => {
        const c = make();
        c.select('demo');
        expect(c.lastRunBatch()).toBeNull();
        c.selectedNode.set(c.model()!.nodes[0]);
        expect(c.selectedNodeLastRun()).toBeNull();
    });

    it('a 404 (no provenance backend configured) degrades silently, no toast', () => {
        api.provenanceBatches.mockReturnValue(throwError(() => ({ status: 404 })));
        const c = make();
        c.select('demo');
        expect(c.lastRunBatch()).toBeNull();
        expect(toast.error).not.toHaveBeenCalled();
    });

    it('the empty path has no accessibility violations', async () => {
        const fixture = TestBed.createComponent(PipelineEditorComponent);
        fixture.detectChanges(); // no flows → empty-state, the G6 canvas is not mounted
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('hides New/Save/delete-selected in the Business (read-only) lens', () => {
        TestBed.inject(LensService).selectLens('business');
        const fixture = TestBed.createComponent(PipelineEditorComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[aria-label="New pipeline"]')).toBeNull();
        expect(el.querySelector('[aria-label="Save pipeline"]')).toBeNull();
        expect(el.querySelector('[aria-label="Delete the selected Step or edge"]')).toBeNull();
    });

    it('the Business lens blocks model mutation even when called directly (defense-in-depth)', () => {
        TestBed.inject(LensService).selectLens('business');
        const c = make();
        c.model.set(structuredClone(FLOW));

        c.onDropAdd({ type: 'transform.filter', x: 10, y: 20 });
        expect(c.model()!.nodes).toHaveLength(2); // unchanged

        c.addFromPalette('transform.filter');
        expect(c.model()!.nodes).toHaveLength(2); // unchanged

        c.selectedNode.set(c.model()!.nodes[1]);
        c.onDeleteKey();
        expect(c.model()!.nodes).toHaveLength(2); // unchanged

        c.onEdgeCreated({ source: 'flt', target: 'src' });
        expect(c.model()!.edges.filter((e) => e.from === 'flt' && e.to === 'src')).toHaveLength(0);

        c.openNodeConfig(c.model()!.nodes[0]);
        expect(dialog.open).not.toHaveBeenCalled();
    });

    // ─── AGT-6a A2: adopting a checked topology from the inline surface ───

    it('applying a pipeline draft replaces the graph and marks it dirty without saving (D2)', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        c.selectedId.set('demo');
        c.dirty.set(false);

        c.applyPipelineDraft({
            label: 'demo',
            clean: true,
            findings: [],
            config: {
                name: 'renamed_by_tool',
                active: true,
                nodes: [{ id: 'src', type: 'acquisition', config: {} }],
                edges: [],
            },
        });

        const m = c.model()!;
        expect(m.nodes).toHaveLength(1);
        // The open pipeline's identity and lifecycle are preserved — adopting a draft must never
        // silently rename or activate a live pipeline.
        expect(m.name).toBe('demo');
        expect(m.active).toBe(false);
        expect(c.dirty()).toBe(true);
        // Nothing persisted: the operator still presses the existing Save.
        expect(api.savePipelineGraph).not.toHaveBeenCalled();
    });

    it('ignores a malformed draft and refuses to apply in a non-authoring lens', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));

        c.applyPipelineDraft({ label: 'x', clean: true, findings: [], config: { name: 'x' } });
        expect(c.model()!.nodes).toHaveLength(2); // unchanged — no nodes/edges in the draft
        expect(c.dirty()).toBe(false);
    });

    // ─── AGT-6a A5.3: natural-language authoring ───

    it('sends the graph under `flow`, the only shape the tool accepts', () => {
        // Regression: this pane passed {name, nodes, edges} FLAT, so every real-backend call answered
        // "flow is required and must be an object". Only the offline mock's leniency hid it.
        const c = make();
        c.model.set(structuredClone(FLOW));

        const args = c.aiPipelineArgs() as { flow: AuthoredPipeline };
        expect(Object.keys(args)).toEqual(['flow']);
        expect(args.flow.nodes).toHaveLength(2);
        expect(args.flow.name).toBe('demo');
        // `active` travels or the echoed graph diffs as though the check wanted to deactivate the pipeline.
        expect(args.flow.active).toBe(false);
    });

    it('sends NO pane args on the prompt instance, or the derived topology would be overwritten', () => {
        // Pane args are merged last and win. Passing the open graph here would replace the topology the
        // sentence just produced, and the draft would silently equal what is already on screen.
        expect(make().aiPromptArgs()).toEqual({});
    });

    // ── save-as-template / rename (T4) ──

    it('save-as-template posts the new id and selects the created template', () => {
        const c = make();
        c.flows.set([{ name: 'demo', active: false, nodeCount: 2, edgeCount: 1, produces: [], consumes: [] }]);
        c.selectedId.set('demo');
        dialog.open.mockReturnValue({ afterClosed: () => of({ id: 'demo_copy', displayName: 'Demo copy' }) });

        c.saveAsTemplate();

        expect(api.saveAsTemplate).toHaveBeenCalledWith('demo', 'demo_copy', 'Demo copy');
        // The new row is flagged, so the toolbar hides Activate without waiting for a refetch.
        expect(c.flows().find((f) => f.name === 'demo_copy')?.template).toBe(true);
        expect(c.selectedId()).toBe('demo_copy');
    });

    it('offers the existing ids to the dialog so a duplicate is blocked before the server 409s', () => {
        const c = make();
        c.flows.set([
            { name: 'demo', active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] },
            { name: 'other', active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] },
        ]);
        c.selectedId.set('demo');
        dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });

        c.saveAsTemplate();

        expect(dialog.open.mock.calls[0][1].data).toEqual({ source: 'demo', existingNames: ['demo', 'other'] });
        expect(api.saveAsTemplate).not.toHaveBeenCalled(); // cancelled
    });

    it('a template hides Activate — the server refuses to run it, so the affordance would only fail', () => {
        const c = make();
        c.flows.set([
            { name: 'tpl', active: false, template: true, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] },
        ]);
        c.selectedId.set('tpl');
        expect(c.isTemplate()).toBe(true);

        c.selectedId.set('missing');
        expect(c.isTemplate()).toBe(false);
    });

    it('rename patches only the label — the identity still addresses the pipeline', () => {
        const c = make();
        c.flows.set([{ name: 'demo', active: true, nodeCount: 2, edgeCount: 1, produces: [], consumes: [] }]);
        c.selectedId.set('demo');
        dialog.open.mockReturnValue({ afterClosed: () => of({ name: 'Demo (EU)' }) });

        c.renamePipeline();

        expect(api.label).toHaveBeenCalledWith('demo', 'Demo (EU)');
        const row = c.flows()[0];
        expect(row.name).toBe('demo'); // identity unchanged — run URLs and history still resolve
        expect(row.displayName).toBe('Demo (EU)');
        expect(c.selectedId()).toBe('demo');
    });

    it('change-id moves the identity — unlike rename, the selection follows the new id', () => {
        const c = make();
        c.flows.set([{ name: 'demo', active: false, nodeCount: 2, edgeCount: 1, produces: [], consumes: [] }]);
        c.openIds.set(['demo']);
        c.selectedId.set('demo');
        dialog.open.mockReturnValue({ afterClosed: () => of({ newId: 'demo_eu' }) });
        api.rename.mockReturnValue(
            of({
                written: true,
                oldId: 'demo',
                id: 'demo_eu',
                name: 'demo_eu',
                path: 'demo_eu_pipeline.toon',
                ledgerRowsMoved: 0,
                auditFilesRenamed: 0,
                dependentsRewritten: 0,
            }),
        );

        c.changePipelineId();

        // No custom label ⇒ the display name follows the id, or every tab/list row would keep
        // captioning the pipeline with an identity that no longer exists.
        expect(api.rename).toHaveBeenCalledWith('demo', 'demo_eu', { newName: 'demo_eu' });
        expect(c.flows().some((f) => f.name === 'demo')).toBe(false);
        const row = c.flows().find((f) => f.name === 'demo_eu')!;
        expect(row.displayName).toBeUndefined(); // name === id — mirror configSummary's omission
        expect(c.openIds()).toEqual(['demo_eu']);
        expect(c.selectedId()).toBe('demo_eu');
    });

    it('change-id keeps an explicit label — only the identity moves', () => {
        const c = make();
        c.flows.set([
            {
                name: 'demo',
                active: false,
                displayName: 'Demo (EU)',
                nodeCount: 2,
                edgeCount: 1,
                produces: [],
                consumes: [],
            },
        ]);
        c.openIds.set(['demo']);
        c.selectedId.set('demo');
        dialog.open.mockReturnValue({ afterClosed: () => of({ newId: 'demo_eu' }) });
        api.rename.mockReturnValue(
            of({
                written: true,
                oldId: 'demo',
                id: 'demo_eu',
                name: 'Demo (EU)',
                path: 'demo_eu_pipeline.toon',
                ledgerRowsMoved: 0,
                auditFilesRenamed: 0,
                dependentsRewritten: 0,
            }),
        );

        c.changePipelineId();

        expect(api.rename).toHaveBeenCalledWith('demo', 'demo_eu', {});
        const row = c.flows().find((f) => f.name === 'demo_eu')!;
        expect(row.displayName).toBe('Demo (EU)');
    });

    it('offers the existing ids to the change-id dialog so a duplicate is blocked before the server 409s', () => {
        const c = make();
        c.flows.set([
            { name: 'demo', active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] },
            { name: 'other', active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] },
        ]);
        c.selectedId.set('demo');
        dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });

        c.changePipelineId();

        expect(dialog.open.mock.calls[0][1].data).toEqual({
            id: 'demo',
            displayName: 'demo',
            existingNames: ['demo', 'other'],
        });
        expect(api.rename).not.toHaveBeenCalled(); // cancelled
    });

    it('surfaces a failed change-id instead of leaving a phantom row', () => {
        const c = make();
        c.flows.set([{ name: 'demo', active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] }]);
        c.selectedId.set('demo');
        dialog.open.mockReturnValue({ afterClosed: () => of({ newId: 'taken' }) });
        api.rename.mockReturnValue(throwError(() => ({ status: 409, error: { error: { message: 'taken' } } })));

        c.changePipelineId();

        expect(toast.error).toHaveBeenCalled();
        expect(c.flows().some((f) => f.name === 'taken')).toBe(false);
        expect(c.flows().some((f) => f.name === 'demo')).toBe(true); // unchanged — nothing renamed
    });

    it('surfaces a failed template copy instead of leaving a phantom row', () => {
        const c = make();
        c.flows.set([{ name: 'demo', active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] }]);
        c.selectedId.set('demo');
        dialog.open.mockReturnValue({ afterClosed: () => of({ id: 'taken' }) });
        api.saveAsTemplate.mockReturnValue(throwError(() => ({ status: 409, error: { error: { message: 'taken' } } })));

        c.saveAsTemplate();

        expect(toast.error).toHaveBeenCalled();
        expect(c.flows().some((f) => f.name === 'taken')).toBe(false);
    });
});

describe('PipelineEditorComponent recipe view (UI plan §1, S1)', () => {
    let api: {
        list: ReturnType<typeof vi.fn>;
        nodeTypes: ReturnType<typeof vi.fn>;
        stepTypes: ReturnType<typeof vi.fn>;
        pipelineGraphRaw: ReturnType<typeof vi.fn>;
        provenanceBatches: ReturnType<typeof vi.fn>;
        provenance: ReturnType<typeof vi.fn>;
    };

    beforeEach(() => {
        localStorage.removeItem('inspecto.currentLens');
        localStorage.removeItem('inspecto.pipelines.viewMode');
        api = {
            list: vi.fn().mockReturnValue(of([])),
            nodeTypes: vi.fn().mockReturnValue(of([])),
            stepTypes: vi.fn().mockReturnValue(throwError(() => new Error('404'))),
            pipelineGraphRaw: vi.fn().mockReturnValue(of(structuredClone(FLOW))),
            provenanceBatches: vi.fn().mockReturnValue(of([])),
            provenance: vi.fn().mockReturnValue(of([])),
        };
        TestBed.configureTestingModule({
            imports: [PipelineEditorComponent],
            providers: [
                provideNoopAnimations(),
                { provide: PipelinesService, useValue: api },
                { provide: ConfigService, useValue: { write: vi.fn(), registerPipeline: vi.fn(), remove: vi.fn() } },
                { provide: ComponentsService, useValue: { list: vi.fn().mockReturnValue(of([])) } },
                { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
                {
                    provide: InspectoConfirmService,
                    // P6-b: activate/deactivate now confirm through the NEUTRAL confirm(), not confirmDestructive.
                    useValue: {
                        confirm: vi.fn().mockResolvedValue(true),
                        confirmDestructive: vi.fn().mockResolvedValue(true),
                    },
                },
                { provide: MatDialog, useValue: { open: vi.fn() } },
            ],
        });
    });

    function make(): PipelineEditorComponent {
        const c = TestBed.createComponent(PipelineEditorComponent).componentInstance;
        c.ngOnInit();
        (c as unknown as { canvas: unknown }).canvas = { setNodeStatus: vi.fn() };
        return c;
    }

    it('defaults to Recipe for an expressible graph and detects its chain', () => {
        const c = make();
        c.select('demo');
        expect(c.viewMode()).toBe('recipe');
        expect(c.effectiveMode()).toBe('recipe');
        expect(c.stepChain()?.trunk.map((n) => n.id)).toEqual(['src', 'flt']);
        expect(c.forcedToCanvas()).toBe(false);
    });

    it('forces Canvas and flags it when the open graph is not recipe-expressible (fan-in)', () => {
        api.pipelineGraphRaw.mockReturnValue(
            of({
                name: 'demo',
                active: false,
                nodes: [
                    { id: 'a', type: 'acquisition' },
                    { id: 'b', type: 'acquisition' },
                    { id: 'c', type: 'sink.persistent' },
                ],
                edges: [
                    { from: 'a', rel: 'data', to: 'c' },
                    { from: 'b', rel: 'data', to: 'c' },
                ],
            }),
        );
        const c = make();
        c.select('demo');
        expect(c.stepChain()).toBeNull();
        expect(c.viewMode()).toBe('recipe'); // the preference itself is untouched
        expect(c.effectiveMode()).toBe('canvas'); // but the graph forces the actual view
        expect(c.forcedToCanvas()).toBe(true);
    });

    it('persists the device preference across instances (localStorage, mirrors the lens/space pattern)', () => {
        const c1 = make();
        c1.setViewMode('canvas');
        expect(localStorage.getItem('inspecto.pipelines.viewMode')).toBe('canvas');

        const c2 = make();
        expect(c2.viewMode()).toBe('canvas');
    });

    it('flattens the detected chain into step rows for the cards view', () => {
        const c = make();
        c.select('demo');
        const rows = c.stepRows();
        expect(rows.map((r) => r.rowId)).toEqual(['src', 'flt']);
        expect(rows.every((r) => r.kind === 'node')).toBe(true);
    });
});
