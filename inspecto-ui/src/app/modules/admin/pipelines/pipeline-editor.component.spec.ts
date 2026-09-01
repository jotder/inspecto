import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { MatDialog } from '@angular/material/dialog';
import { PipelineEditorComponent } from './pipeline-editor.component';
import { AuthoredPipeline, ComponentsService, ConfigService, LensService, PipelinesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { StreamTransferService } from 'app/inspecto/transfer/stream-transfer.service';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GAMMA_CONFIG } from '@gamma/services/config/config.constants';

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
        impact: ReturnType<typeof vi.fn>;
        read: ReturnType<typeof vi.fn>;
    };
    let transfer: {
        buildExport: ReturnType<typeof vi.fn>;
        exportPipeline: ReturnType<typeof vi.fn>;
        applyImport: ReturnType<typeof vi.fn>;
        download: ReturnType<typeof vi.fn>;
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
        // The editor persists its open-tab set the same way — a set left by one test must not restore in another.
        localStorage.removeItem('inspecto.pipelines.openTabs');
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
            impact: vi.fn().mockReturnValue(of({ pipeline: 'demo', total: 0, truncated: false, dependents: {} })),
            read: vi.fn().mockReturnValue(of({ config: { name: 'demo' }, path: 'demo_pipeline.toon' })),
        };
        transfer = {
            buildExport: vi.fn().mockReturnValue(of({ bundle: { kind: 'inspecto-stream-config' }, missing: [] })),
            exportPipeline: vi
                .fn()
                .mockReturnValue(of({ bundle: { kind: 'stream', source: { name: 'demo' } }, missing: [] })),
            applyImport: vi.fn().mockReturnValue(of({ path: 'copy_pipeline.toon' })),
            download: vi.fn(),
        };
        dialog = { open: vi.fn() };
        components = { list: vi.fn().mockReturnValue(of([])), create: vi.fn() };
        toast = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() };
        TestBed.configureTestingModule({
            imports: [PipelineEditorComponent],
            providers: [
                provideNoopAnimations(),
                // Item 1 (2026-09-01): New pipeline… navigates to the Catalog onboarding entry.
                provideRouter([]),
                // Rendering the toolbar's create field pulls the shell config service, which walks up
                // to this token — without it the editor cannot be rendered at all in a spec.
                { provide: GAMMA_CONFIG, useValue: {} },
                { provide: PipelinesService, useValue: api },
                { provide: ConfigService, useValue: config },
                { provide: StreamTransferService, useValue: transfer },
                { provide: ComponentsService, useValue: components },
                { provide: ToastrService, useValue: toast },
                {
                    provide: InspectoConfirmService,
                    // P6-b: activate/deactivate now confirm through the NEUTRAL confirm(), not confirmDestructive.
                    useValue: {
                        confirm: vi.fn().mockResolvedValue(true),
                        confirmDestructive: vi.fn().mockResolvedValue(true),
                        // The delete asks with checkboxes now: schema defaults ON, data OFF.
                        confirmDestructiveWith: vi
                            .fn()
                            .mockResolvedValue({ ok: true, checked: { schema: true, data: false } }),
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
    function make(inputs: { guided?: boolean } = {}): PipelineEditorComponent {
        const fixture = TestBed.createComponent(PipelineEditorComponent);
        if (inputs.guided !== undefined) fixture.componentRef.setInput('guided', inputs.guided);
        const c = fixture.componentInstance;
        c.ngOnInit();
        (c as unknown as { canvas: unknown }).canvas = canvasMock();
        return c;
    }
    function canvasOf(c: PipelineEditorComponent) {
        return (c as unknown as { canvas: ReturnType<typeof canvasMock> }).canvas;
    }

    /**
     * P6-e — the stream-config export re-homed. ⛔ Deleting the shell without this would have left the
     * `inspecto-stream-config` format IMPORT-only: the create dialog still reads a bundle, and
     * `StreamTransferService.buildExport` would have had no caller at all.
     */
    describe('export configuration (P6-e)', () => {
        /** The read + kind derivation moved onto `StreamTransferService.exportPipeline` (2026-09-01)
         *  so the Open dialog's per-row export shares it — the editor now delegates by NAME. */
        it('exports the SERVER-held config through the shared by-name seam', () => {
            const c = make();
            c.select('demo');
            c.exportConfig();

            expect(transfer.exportPipeline).toHaveBeenCalledWith('demo');
            expect(transfer.download).toHaveBeenCalled();
            expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('demo'));
        });

        /** ⚠ The export carries the SAVED config — shipping it while the tab shows unapplied edits
         *  produces a file that quietly disagrees with the screen. */
        it('refuses while the tab is dirty', () => {
            const c = make();
            c.select('demo');
            c.dirty.set(true);
            c.exportConfig();

            expect(transfer.exportPipeline).not.toHaveBeenCalled();
            expect(toast.warning).toHaveBeenCalled();
        });
    });

    /** Item 1 (operator batch 2026-09-01): the compliant cheap create — navigate to Catalog
     *  onboarding (⛔ never import the other feature's dialog); it redirects back after the create. */
    describe('new pipeline via onboarding', () => {
        it('navigates to /catalog with the onboard=stream handshake', () => {
            const router = TestBed.inject(Router);
            const nav = vi.spyOn(router, 'navigate').mockResolvedValue(true);
            const c = make();
            c.newPipelineViaOnboarding();

            expect(nav).toHaveBeenCalledWith(['/catalog'], { queryParams: { onboard: 'stream' } });
        });
    });

    /**
     * Item 2 (operator batch 2026-09-01): Duplicate — a runnable copy through the PROVEN
     * stream-bundle retarget path (export → planStreamImport under the new name → applyImport), so
     * satellites, directories and the inactive-draft posture are the import planner's, not new code.
     */
    describe('duplicate pipeline', () => {
        const BUNDLE = {
            format: 'inspecto-stream-config',
            version: 1,
            exportedAt: '2026-09-01T00:00:00.000Z',
            source: { space: null, name: 'demo', contentHash: 'x' },
            kind: 'stream',
            pipeline: { parsing: { frontend: 'delimited' } },
            requires: [],
        };

        it('plans the import under the typed name and opens the copy as a tab', () => {
            transfer.exportPipeline.mockReturnValue(of({ bundle: BUNDLE, missing: [] }));
            dialog.open.mockReturnValue({ afterClosed: () => of({ name: 'demo copy' }) });
            const c = make();
            c.select('demo');
            c.duplicatePipeline();

            expect(transfer.exportPipeline).toHaveBeenCalledWith('demo');
            const plan = transfer.applyImport.mock.calls[0][0];
            // The planner stamps identity from the typed name exactly as a fresh create would.
            expect(plan.pipeline['name']).toBe('demo copy');
            expect(plan.pipeline['id']).toBe('demo_copy');
            expect(plan.pipeline['active']).toBe(false);
            // The copy opens as a tab under its registered id.
            expect(api.pipelineGraphRaw).toHaveBeenCalledWith('demo_copy');
            expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('demo copy'));
        });

        /** Same rule (and toast pattern) as exportConfig: the duplicate reads SERVER state. */
        it('refuses while the tab is dirty', () => {
            const c = make();
            c.select('demo');
            c.dirty.set(true);
            c.duplicatePipeline();

            expect(dialog.open).not.toHaveBeenCalled();
            expect(transfer.exportPipeline).not.toHaveBeenCalled();
            expect(toast.warning).toHaveBeenCalled();
        });

        it('names an unreadable satellite but still writes the copy', () => {
            transfer.exportPipeline.mockReturnValue(of({ bundle: BUNDLE, missing: ['schema "demo_schema"'] }));
            dialog.open.mockReturnValue({ afterClosed: () => of({ name: 'copy' }) });
            const c = make();
            c.select('demo');
            c.duplicatePipeline();

            expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('demo_schema'));
            expect(transfer.applyImport).toHaveBeenCalled();
        });
    });

    /**
     * BACKLOG SATELLITE-WRITE-1. The definition panes read and write the pipeline's satellite configs
     * (its schema, and the `_mapping.csv` the server splits out beside it) and used to pass no `subdir`,
     * so every write landed at the write ROOT — orphaning a duplicate for any pipeline in a
     * subdirectory, and leaving a root-level file that then WINS the read, so the drawer edited a schema
     * the engine never loads. The host resolves the directory once per open pipeline; these pin both
     * arms, because getting the ROOT case wrong would send every UI-created pipeline into a stray subdir.
     */
    describe('the satellite subdir (SATELLITE-WRITE-1)', () => {
        it('is the pipeline config file’s own directory, for a pipeline in a subdirectory', () => {
            config.read.mockReturnValue(of({ config: {}, path: 'csv_example/csv_example_pipeline.toon' }));
            const c = make();
            c.select('csv_example');

            expect(config.read).toHaveBeenCalledWith('pipeline', 'csv_example');
            expect(c.configSubdir()).toBe('csv_example');
        });

        it('is blank for a pipeline at the write root, so the read keeps its server-side fallback', () => {
            config.read.mockReturnValue(of({ config: {}, path: 'demo_orders_pipeline.toon' }));
            const c = make();
            c.select('demo_orders');

            expect(c.configSubdir()).toBe('');
        });

        it('falls back to the write root when the pipeline has no readable config yet', () => {
            config.read.mockReturnValue(throwError(() => ({ status: 404 })));
            const c = make();
            c.select('draft');

            expect(c.configSubdir()).toBe('');
        });

        /** A satellite that could not be read is NAMED — the file downloaded, but re-importing it
         *  would silently lose that piece. */
        it('names an unreadable satellite instead of swallowing it', () => {
            transfer.exportPipeline.mockReturnValue(of({ bundle: {}, missing: ['demo_schema'] }));
            const c = make();
            c.select('demo');
            c.exportConfig();

            expect(transfer.download).toHaveBeenCalled();
            expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('demo_schema'));
        });
    });

    /**
     * P6-e — the wizard's "Discard draft" re-homed. The editor is now the only surface that deletes a
     * guided pipeline, so its bare `remove()` had to grow the three things the wizard did.
     */
    describe('delete (P6-e)', () => {
        function confirmOf() {
            return TestBed.inject(InspectoConfirmService) as unknown as {
                confirmDestructive: ReturnType<typeof vi.fn>;
                confirmDestructiveWith: ReturnType<typeof vi.fn>;
            };
        }

        it('cascades the companion configs so no orphan schema/enrichment lingers', async () => {
            const c = make();
            c.select('demo');
            await c.deletePipeline();

            expect(config.remove).toHaveBeenCalledWith('pipeline', 'demo', undefined, false, false);
            expect(config.remove).toHaveBeenCalledWith('schema', 'demo_schema');
            expect(config.remove).toHaveBeenCalledWith('enrichment', 'demo_enrich');
        });

        it('names the dependents and sends force once the operator has seen them', async () => {
            config.impact.mockReturnValue(
                of({
                    pipeline: 'demo',
                    total: 3,
                    truncated: false,
                    dependents: { dataset: [{ id: 'a' }, { id: 'b' }], widget: [{ id: 'c' }] },
                }),
            );
            const c = make();
            c.select('demo');
            await c.deletePipeline();

            expect(confirmOf().confirmDestructiveWith.mock.calls[0][0]).toContain('2 datasets, 1 widget');
            expect(config.remove).toHaveBeenCalledWith('pipeline', 'demo', undefined, true, false);
        });

        /** The impact read is ADVISORY — the server re-checks and refuses on its own, so a failed
         *  read must not become a delete the operator cannot perform. */
        it('still deletes when the impact read fails', async () => {
            config.impact.mockReturnValue(throwError(() => new Error('boom')));
            const c = make();
            c.select('demo');
            await c.deletePipeline();

            expect(config.remove).toHaveBeenCalledWith('pipeline', 'demo', undefined, false, false);
        });

        /**
         * 🔴 The companion cascade is an OPT-IN now, not automatic. Unticking it must leave the
         * schema and enrichment on disk — an operator who wants to keep a hand-tuned schema and
         * re-point a new pipeline at it has no other way to say so.
         */
        it('keeps the companions when the schema box is unticked', async () => {
            confirmOf().confirmDestructiveWith.mockResolvedValueOnce({
                ok: true,
                checked: { schema: false, data: false },
            });
            const c = make();
            c.select('demo');
            await c.deletePipeline();

            expect(config.remove).toHaveBeenCalledWith('pipeline', 'demo', undefined, false, false);
            expect(config.remove).not.toHaveBeenCalledWith('schema', 'demo_schema');
            expect(config.remove).not.toHaveBeenCalledWith('enrichment', 'demo_enrich');
        });

        /**
         * ⚠ Data deletion is carried as its own flag, never implied by `force`. `force` overrides a
         * dangling-REFERENCE refusal the operator can repair; this destroys written output.
         */
        it('asks for the data only when the data box is ticked', async () => {
            confirmOf().confirmDestructiveWith.mockResolvedValueOnce({
                ok: true,
                checked: { schema: true, data: true },
            });
            const c = make();
            c.select('demo');
            await c.deletePipeline();

            expect(config.remove).toHaveBeenCalledWith('pipeline', 'demo', undefined, false, true);
        });

        it('deletes nothing when the confirm is declined', async () => {
            confirmOf().confirmDestructiveWith.mockResolvedValueOnce({ ok: false, checked: {} });
            const c = make();
            c.select('demo');
            await c.deletePipeline();

            expect(config.remove).not.toHaveBeenCalled();
        });

        /**
         * Per-segment schemas, added 2026-08-17. ⛔ The backlog's cause ("enumerating them needs the
         * parsed block") was wrong: the paths are AUTHORED on the parse node as
         * `parsing.<asn1|plugin>.segments`, which the editor holds in memory at delete time.
         */
        describe('per-segment schemas', () => {
            function graphWithSegments(segments: Record<string, string>, key = 'asn1') {
                return of({
                    name: 'demo',
                    active: false,
                    nodes: [{ id: 'p', type: 'parser', config: { parsing: { [key]: { segments } } } }],
                    edges: [],
                });
            }

            it('sweeps the segment schemas the pipeline authored', async () => {
                api.pipelineGraphRaw.mockReturnValue(
                    graphWithSegments({
                        CALL: 'spaces/default/config/demo_CALL.toon',
                        SMS: 'spaces/default/config/demo_SMS.toon',
                    }),
                );
                const c = make();
                c.select('demo');
                await c.deletePipeline();

                expect(config.remove).toHaveBeenCalledWith('schema', 'demo_CALL');
                expect(config.remove).toHaveBeenCalledWith('schema', 'demo_SMS');
            });

            it('reads a plugin node’s segments too, not just asn1', async () => {
                api.pipelineGraphRaw.mockReturnValue(
                    graphWithSegments({ EVT: 'spaces/default/config/demo_EVT.toon' }, 'plugin'),
                );
                const c = make();
                c.select('demo');
                await c.deletePipeline();

                expect(config.remove).toHaveBeenCalledWith('schema', 'demo_EVT');
            });

            /**
             * 🔴 The convention boundary. A path outside `<id>_` may be shared with another pipeline;
             * deleting it would orphan that one. Leaving it costs at most an unreferenced file.
             */
            it('leaves a foreign schema path alone', async () => {
                api.pipelineGraphRaw.mockReturnValue(
                    graphWithSegments({
                        CALL: 'spaces/default/config/demo_CALL.toon',
                        SHARED: 'spaces/default/config/corporate_cdr.toon',
                    }),
                );
                const c = make();
                c.select('demo');
                await c.deletePipeline();

                expect(config.remove).toHaveBeenCalledWith('schema', 'demo_CALL');
                expect(config.remove).not.toHaveBeenCalledWith('schema', 'corporate_cdr');
            });

            it('does not re-delete the flat companion when a segment names it', async () => {
                api.pipelineGraphRaw.mockReturnValue(
                    graphWithSegments({ ONLY: 'spaces/default/config/demo_schema.toon' }),
                );
                const c = make();
                c.select('demo');
                await c.deletePipeline();

                const schemaCalls = config.remove.mock.calls.filter(
                    (args: unknown[]) => args[0] === 'schema' && args[1] === 'demo_schema',
                );
                expect(schemaCalls).toHaveLength(1);
            });

            /** A failed sweep must never strand the delete — the pipeline is already gone. */
            it('completes the delete when a segment schema removal fails', async () => {
                api.pipelineGraphRaw.mockReturnValue(
                    graphWithSegments({ CALL: 'spaces/default/config/demo_CALL.toon' }),
                );
                config.remove.mockImplementation((kind: string, name: string) =>
                    kind === 'schema' && name === 'demo_CALL' ? throwError(() => new Error('nope')) : of({}),
                );
                const c = make();
                c.select('demo');
                await c.deletePipeline();

                expect(c.selectedId()).toBeNull();
            });
        });
    });

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

        /**
         * The list row carries the `active` chip the Open dialog renders, and `flows` is loaded once —
         * found by driving the editor: a pipeline activated in-session still read inactive everywhere
         * but the toolbar.
         */
        it('marks the list row active, and inactive again on deactivate', async () => {
            const c = make();
            c.flows.set([{ name: 'demo', active: false, nodeCount: 2, edgeCount: 1, produces: [], consumes: [] }]);
            c.select('demo');
            await c.activate();
            expect(c.flows()[0].active).toBe(true);

            await c.deactivate();
            expect(c.flows()[0].active).toBe(false);
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

        /**
         * P6-d: the readiness gate P6-b left behind. ⛔ Guided only — `validatePipeline` does not
         * require a parse node, so a hand-built collect→sink graph is legitimate and must still
         * activate; the wizard's five stages are the Stream contract, not the editor's.
         */
        it('refuses a guided go-live and NAMES the stages that are not ready', async () => {
            api.nodeTypes.mockReturnValue(
                of([
                    {
                        type: 'acquisition',
                        category: 'SOURCE',
                        label: 'Collect',
                        description: '',
                        accepts: [],
                        emits: ['data'],
                        emitsNamedRoutes: false,
                        lowerable: true,
                    },
                ]),
            );
            const c = make({ guided: true });
            c.select('demo');
            await c.activate();

            expect(api.savePipelineGraph).not.toHaveBeenCalled();
            expect(confirmOf().confirm).not.toHaveBeenCalled();
            const msg = String(toast.error.mock.calls[0][0]);
            expect(msg).toContain('Parse'); // the fixture has neither a parser
            expect(msg).toContain('Publish'); // nor a sink
            expect(msg).not.toContain('Collect'); // but its collector IS configured
        });

        /**
         * ⚠ Every stage resolves through the served node-type catalog, so an unresolved catalog reads
         * as five empty stages. Gating on it would refuse a perfectly ready pipeline and name every
         * stage as missing — the same "don't cry wolf" posture `unsupportedNodes` takes.
         */
        it('does not gate a guided go-live while the node-type catalog is unresolved', async () => {
            api.nodeTypes.mockReturnValue(throwError(() => new Error('no catalog')));
            const c = make({ guided: true });
            c.select('demo');
            await c.activate();

            expect(api.savePipelineGraph).toHaveBeenCalled();
        });

        it('leaves an UNGUIDED pipeline on the old validator gate alone', async () => {
            const c = make();
            c.select('demo'); // the fixture has no parse or sink node at all
            await c.activate();
            expect(api.savePipelineGraph).toHaveBeenCalled();
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

        /**
         * The generic `parser` type joined READ_COMPAT_ONLY 2026-08-20: every per-format subtype
         * exists now, so a NEW generic node is a dead end. It must still load unchanged — the
         * legacy-delimited-implicit and dialog-bound `use: grammar/<id>` paths both carry it.
         */
        it('hides the generic parser type from the palette while a bound one still loads', () => {
            api.nodeTypes.mockReturnValue(
                of([
                    {
                        type: 'parser',
                        category: 'PARSE',
                        label: 'Parser',
                        description: '',
                        accepts: ['data'],
                        emits: ['data', 'unmatched'],
                        emitsNamedRoutes: true,
                        lowerable: true,
                        authorable: false,
                    },
                    {
                        type: 'parser.delimited',
                        category: 'PARSE',
                        label: 'Delimited',
                        description: '',
                        accepts: ['data'],
                        emits: ['data', 'unmatched'],
                        emitsNamedRoutes: true,
                        lowerable: true,
                        authorable: true,
                    },
                ]),
            );
            const c = make();
            expect(c.paletteGroups().flatMap((g) => g.types.map((t) => t.type))).toEqual(['parser.delimited']);

            c.select('demo');
            c.model.update((m) => ({
                ...m!,
                nodes: [...m!.nodes, { id: 'legacy_parse', type: 'parser', use: 'grammar/cdr_csv', config: {} }],
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

        /**
         * S2 — every other node kind now defines in the drawer too. The ONLY surface still on a popup is
         * the Grammar editor, for the parse nodes `isDrawerParse` deliberately refuses (pinned below).
         */
        it('routes every other node kind to the drawer, not a dialog', () => {
            const c = make();
            c.select('demo');
            dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
            c.openNodeConfig(c.model()!.nodes[1]); // transform.filter
            expect(dialog.open).not.toHaveBeenCalled();
            expect(c.definitionNode()?.id).toBe('flt');
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
            ['parser.plugin', { frontend: 'plugin', plugin: { ingester: 'com.example.acme.AcmeFeedIngester' } }],
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

        // U4: the host's saveGrammarAsTemplate is gone — the Grammar CSV export replaced it, and the
        // pane no longer emits a template block at all.

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

        /**
         * BUILDER-1c. The flat config has ONE parse slot, and every new pipeline lifts with a GENERIC
         * `parser` placeholder nobody authored — so "I want CSV, I'll click Delimited" used to drop a
         * floating SECOND parse Step whose only outcome was `MULTI_PARSER` at Save, named after an
         * internal node id. Adding a parse Step re-types the placeholder in place instead, keeping its
         * id and its edges.
         */
        it('re-types the untouched parser placeholder instead of adding a second parse Step', () => {
            api.pipelineGraphRaw.mockReturnValue(
                of({
                    name: 'demo',
                    active: false,
                    nodes: [
                        { id: 'acq', type: 'acquisition', config: { poll: 'in' } },
                        { id: 'parse', type: 'parser', name: 'Parser' },
                        { id: 'sink', type: 'sink.persistent', config: { database: 'db' } },
                    ],
                    edges: [
                        { from: 'acq', rel: 'data', to: 'parse' },
                        { from: 'parse', rel: 'data', to: 'sink' },
                    ],
                }),
            );
            const c = make();
            c.select('demo');
            c.addFromPalette('parser.delimited');

            const parseNodes = c.model()!.nodes.filter((n) => n.type === 'parser' || n.type.startsWith('parser.'));
            expect(parseNodes).toHaveLength(1);
            expect(parseNodes[0].id).toBe('parse'); // same node — so both its edges survive
            expect(parseNodes[0].type).toBe('parser.delimited');
            expect(c.model()!.edges).toHaveLength(2);
        });

        /** A parse Step carrying config is authored work: refuse rather than add a node that can never
         *  be saved, and point at the drawer that CAN change its format. */
        it('refuses a second parse Step when the slot is held by a configured one', () => {
            api.pipelineGraphRaw.mockReturnValue(
                of({
                    name: 'demo',
                    active: false,
                    nodes: [{ id: 'parse', type: 'parser.delimited', config: { parsing: { frontend: 'delimited' } } }],
                    edges: [],
                }),
            );
            const c = make();
            c.select('demo');
            c.addFromPalette('parser.json');

            expect(c.model()!.nodes).toHaveLength(1);
            expect(c.model()!.nodes[0].type).toBe('parser.delimited');
            expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('already has a Parse Step'));
        });

        /** S3: a bound delimited node now reaches the drawer too, materialised into an inline COPY of
         *  its component — and Apply drops the binding (D4, editing migrates it). */
        it('materialises a grammar-bound delimited node into the drawer and migrates it on Apply', () => {
            const c = make();
            c.select('demo');
            c.grammarTemplates.set([
                {
                    name: 'pipes',
                    ref: 'grammar/pipes',
                    type: 'grammar',
                    content: { delimiter: '|', has_header: false },
                },
            ] as never);
            const node = { id: 'parse', type: 'parser.delimited', use: 'grammar/pipes', config: {} };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));

            c.openNodeConfig(node);

            expect(dialog.open).not.toHaveBeenCalled();
            // The drawer shows an inline copy; the MODEL is untouched until Apply.
            expect(c.definitionNode()!.config!['parsing']).toEqual({
                delimited: { delimiter: '|', has_header: false },
            });
            expect(c.model()!.nodes.find((n) => n.id === 'parse')!.use).toBe('grammar/pipes');

            c.onDefinitionApplied({
                id: 'parse',
                type: 'parser.delimited',
                config: { parsing: { frontend: 'delimited' } },
            });
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
        /** A config-less/format-less generic parser maps to nothing — the palette's parse-slot rule
         *  is the intended way to give it a format, so it keeps the dialog (S5's fail-closed arm). */
        it('keeps the dialog for a plain parser carrying no parse config', () => {
            const c = make();
            c.select('demo');
            dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
            const plain = { id: 'pp', type: 'parser', config: { schema_file: 's.toon' } };
            c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, plain] }));
            c.openNodeConfig(plain);
            expect(dialog.open).toHaveBeenCalledTimes(1);
            expect(c.definitionNode()).toBeNull();
        });

        /**
         * S5/D8 — the legacy generic `parser` joins the drawer when its own config maps to a built-in
         * frontend. Grounded first: the engine merges `csv_settings` and `parsing:` into one map
         * (`parsing:` wins), and a lowered parsing-only config keeps its dialect — so the seed folds
         * the legacy map in and the draft converges on the unified spelling.
         */
        describe('legacy generic parser → drawer (S5)', () => {
            it('routes a csv_settings parser to the drawer, re-typed and seeded from the legacy map', () => {
                const c = make();
                c.select('demo');
                const node = {
                    id: 'parse',
                    type: 'parser',
                    config: {
                        csv_settings: { delimiter: '|', has_header: true },
                        schema_file: 'demo_schema.toon',
                    },
                };
                c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
                c.openNodeConfig(node);
                expect(dialog.open).not.toHaveBeenCalled();
                const draft = c.definitionNode()!;
                expect(draft.type).toBe('parser.delimited');
                const parsing = draft.config!['parsing'] as Record<string, unknown>;
                expect(parsing['frontend']).toBe('delimited');
                expect(parsing['delimited']).toEqual({ delimiter: '|', has_header: true });
                // The legacy spelling is folded in and dropped from the draft — the applied node
                // carries only the unified block, which round-trips fully (grounded).
                expect(draft.config!['csv_settings']).toBeUndefined();
                expect(draft.config!['schema_file']).toBe('demo_schema.toon'); // untouched
                // ⚠ The MODEL is untouched until Apply — the draft is presentation only.
                expect(c.model()!.nodes.find((n) => n.id === 'parse')!.type).toBe('parser');
            });

            it('the parsing block wins over csv_settings, mirroring the engine precedence', () => {
                const c = make();
                c.select('demo');
                const node = {
                    id: 'parse',
                    type: 'parser',
                    config: {
                        csv_settings: { delimiter: ',', has_header: false },
                        parsing: { frontend: 'delimited', delimited: { delimiter: ';' } },
                    },
                };
                c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
                c.openNodeConfig(node);
                const parsing = c.definitionNode()!.config!['parsing'] as Record<string, unknown>;
                expect(parsing['delimited']).toEqual({ delimiter: ';', has_header: false });
            });

            it('routes a parsing.frontend=json parser to the drawer as parser.json', () => {
                const c = make();
                c.select('demo');
                const node = {
                    id: 'parse',
                    type: 'parser',
                    config: { parsing: { frontend: 'json', json: { format: 'newline' } } },
                };
                c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
                c.openNodeConfig(node);
                expect(dialog.open).not.toHaveBeenCalled();
                expect(c.definitionNode()!.type).toBe('parser.json');
            });

            it('a BOUND generic parser keeps the dialog — component custody', () => {
                const c = make();
                c.select('demo');
                dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
                const node = {
                    id: 'parse',
                    type: 'parser',
                    use: 'grammar/cdr_csv',
                    config: { csv_settings: { delimiter: ',' } },
                };
                c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
                c.openNodeConfig(node);
                expect(dialog.open).toHaveBeenCalledTimes(1);
                expect(c.definitionNode()).toBeNull();
            });

            it('a BINARY fixed-width generic parser keeps the dialog (P3b)', () => {
                const c = make();
                c.select('demo');
                dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
                const node = {
                    id: 'parse',
                    type: 'parser',
                    config: { parsing: { frontend: 'fixedwidth', fixedwidth: { record: 'bytes' } } },
                };
                c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, node] }));
                c.openNodeConfig(node);
                expect(dialog.open).toHaveBeenCalledTimes(1);
                expect(c.definitionNode()).toBeNull();
            });
        });

        /**
         * P6-c: an enrichment node opened from the editor carries what the host pipeline knows about
         * itself, so its wiring form seeds derived values instead of empty required fields.
         */
        describe('enrichment host context', () => {
            const TYPES = [
                {
                    type: 'enrichment',
                    category: 'TRANSFORM',
                    label: 'Enrichment',
                    description: '',
                    accepts: ['data'],
                    emits: ['data'],
                    emitsNamedRoutes: false,
                    lowerable: true,
                },
                {
                    type: 'sink.persistent',
                    category: 'SINK',
                    label: 'Store',
                    description: '',
                    accepts: ['data'],
                    emits: [],
                    emitsNamedRoutes: false,
                    lowerable: true,
                },
            ];
            const enrich = { id: 'enr', type: 'enrichment', config: {} };

            /**
             * S2 moved the enrichment surface into the drawer, so the host context is no longer packed
             * into dialog data — the template binds `enrichmentHost()` on the pane. The derivation
             * itself is unchanged, and it is the derivation these cases are about.
             */
            function open(sinks: { id: string; type: string; config: Record<string, unknown> }[]) {
                api.nodeTypes.mockReturnValue(of(TYPES));
                const c = make();
                c.select('demo');
                c.model.update((m) => ({ ...m!, nodes: [...m!.nodes, enrich, ...sinks] }));
                c.openNodeConfig(enrich);
                expect(c.definitionNode()?.id).toBe('enr'); // it really is the drawer's pane asking
                return c.enrichmentHost();
            }

            it('sends the pipeline id and its single output store', () => {
                expect(
                    open([{ id: 'out', type: 'sink.persistent', config: { database: 'd/db', format: 'PARQUET' } }]),
                ).toEqual({ pipelineId: 'demo', inputDatabase: 'd/db', inputFormat: 'PARQUET' });
            });

            it('sends NO store when the pipeline has two — there is no single "the output"', () => {
                const host = open([
                    { id: 'a', type: 'sink.persistent', config: { database: 'a/db' } },
                    { id: 'b', type: 'sink.persistent', config: { database: 'b/db' } },
                ]);
                expect(host).toEqual({ pipelineId: 'demo', inputDatabase: undefined, inputFormat: undefined });
            });

            it('ignores a SINK-category node carrying no database (quarantine)', () => {
                const host = open([
                    { id: 'out', type: 'sink.persistent', config: { database: 'd/db' } },
                    { id: 'quarantine', type: 'sink.persistent', config: { dir: 'q/' } },
                ]);
                expect(host.inputDatabase).toBe('d/db');
            });

            /** The template passes it only for an `enrichment` node — every other pane ignores it. */
            it('is bound only for an enrichment node', () => {
                api.nodeTypes.mockReturnValue(of(TYPES));
                const c = make();
                c.select('demo');
                c.openNodeConfig(c.model()!.nodes[1]); // transform.filter
                expect(c.definitionNode()?.type).toBe('transform.filter');
            });
        });

        /**
         * `filenameColumnTarget`/`onSinkFilenameColumnChange` (operator ask 2026-08-22): the Parse
         * pane's Files & metadata tab sets `output.filename_column`, which lives on the SINK node —
         * shares `enrichmentHost`'s "exactly one sink declares `database`" guard rather than a second
         * derivation of "the output" that could drift from the first.
         */
        describe('filenameColumnTarget / onSinkFilenameColumnChange (cross-node lineage field)', () => {
            // `typeCategory('sink.persistent')` only resolves to 'SINK' once `load()`'s nodeTypes()
            // fetch lands — the same setup the enrichment-host-context tests above need.
            const SINK_TYPE = [
                {
                    type: 'sink.persistent',
                    category: 'SINK',
                    label: 'Store',
                    description: '',
                    accepts: ['data'],
                    emits: [],
                    emitsNamedRoutes: false,
                    lowerable: true,
                },
            ];

            it('names the single sink and its current value', () => {
                api.nodeTypes.mockReturnValue(of(SINK_TYPE));
                const c = make();
                c.model.set({
                    name: 'demo',
                    active: false,
                    nodes: [
                        {
                            id: 'out',
                            name: 'Warehouse',
                            type: 'sink.persistent',
                            config: { database: 'd/db', filename_column: 'src_file' },
                        },
                    ],
                    edges: [],
                });
                expect(c.filenameColumnTarget()).toEqual({ value: 'src_file', target: 'Warehouse' });
            });

            it('is null when there is no single sink (none, or more than one)', () => {
                api.nodeTypes.mockReturnValue(of(SINK_TYPE));
                const c = make();
                expect(c.filenameColumnTarget()).toBeNull(); // no model yet

                c.model.set({
                    name: 'demo',
                    active: false,
                    nodes: [
                        { id: 'a', type: 'sink.persistent', config: { database: 'a/db' } },
                        { id: 'b', type: 'sink.persistent', config: { database: 'b/db' } },
                    ],
                    edges: [],
                });
                expect(c.filenameColumnTarget()).toBeNull();
            });

            it('patches the sink node, dirties the model, and no-ops on an unchanged value', () => {
                api.nodeTypes.mockReturnValue(of(SINK_TYPE));
                const c = make();
                c.model.set({
                    name: 'demo',
                    active: false,
                    nodes: [{ id: 'out', type: 'sink.persistent', config: { database: 'd/db' } }],
                    edges: [],
                });

                c.onSinkFilenameColumnChange('src_file');
                expect(c.model()!.nodes[0].config).toEqual({ database: 'd/db', filename_column: 'src_file' });
                expect(c.dirty()).toBe(true);

                c.dirty.set(false);
                c.onSinkFilenameColumnChange('src_file'); // unchanged ⇒ no-op, stays clean
                expect(c.dirty()).toBe(false);

                c.onSinkFilenameColumnChange(null); // blank clears the key entirely (not `''`)
                expect(c.model()!.nodes[0].config).toEqual({ database: 'd/db' });
                expect(c.dirty()).toBe(true);
            });
        });

        /**
         * S4 — a parse pane's options render as TABS, and at the dock's 300px default the labels
         * truncated to "Dialect | Typ…" with scroll arrows while the schema toolbar stacked. The host
         * asks the dock for room. ⚠ Transient: nothing is persisted, so the operator's stored width
         * survives the visit (pinned on the directive itself).
         */
        it('widens the properties dock for a parse pane, and leaves it alone for others', async () => {
            localStorage.removeItem('inspecto.split.pipelines.inspector');
            const fixture = TestBed.createComponent(PipelineEditorComponent);
            fixture.componentRef.setInput('openId', 'demo');
            const c = fixture.componentInstance;
            c.ngOnInit();
            (c as unknown as { canvas: unknown }).canvas = canvasMock();
            fixture.detectChanges();
            const dock = (fixture.nativeElement as HTMLElement).querySelector(
                'aside[aria-label="Properties"]',
            ) as HTMLElement;
            expect(dock.style.width).toBe('300px');

            await c.openDefinition({ id: 'flt', type: 'transform.filter' });
            fixture.detectChanges();
            expect(dock.style.width, 'a flat config pane needs no extra room').toBe('300px');

            await c.openDefinition({ id: 'p', type: 'parser.delimited' });
            fixture.detectChanges();
            expect(dock.style.width).toBe('420px');
        });

        /**
         * S3 — selection and configuration converge, so the common paths cost one click instead of two.        /**
         * S3 — selection and configuration converge, so the common paths cost one click instead of two.
         */
        describe('fewer clicks (S3)', () => {
            it('a Step added from the palette lands in its own config pane', () => {
                const c = make();
                c.select('demo');
                c.addFromPalette('transform.filter');
                const added = c.model()!.nodes.at(-1)!;
                expect(c.definitionNode()?.id).toBe(added.id);
            });

            /**
             * 2026-08-22 third flip (operator ask): selecting a Step opens its CONFIGURATION directly
             * — even an unconfigured one, whose pane is exactly where it gets configured.
             */
            it('selecting an UNCONFIGURED Step opens its config pane directly', async () => {
                const c = make();
                c.select('demo');
                c.addFromPalette('transform.filter');
                const fresh = c.model()!.nodes.at(-1)!;
                c.closeDefinition();
                c.onNodeSelected(fresh.id);
                await Promise.resolve();
                expect(c.definitionNode()?.id).toBe(fresh.id);
            });

            /** 2026-08-22 third flip: a configured Step's selection opens its pane too. */
            it('selecting a configured Step opens its config pane directly', async () => {
                const c = make();
                c.select('demo');
                c.onNodeSelected('flt'); // transform.filter, config: {where: …}
                await Promise.resolve();
                expect(c.definitionNode()?.id).toBe('flt');
            });

            /**
             * The silent bug S3 fixes: the template prefers `definitionNode()` over `selectedNode()`, so
             * selecting Step B while A's pane was open kept showing A's definition with no hint at all.
             */
            it('a CLEAN open pane re-targets to the newly selected Step', async () => {
                const c = make();
                c.select('demo');
                await c.openDefinition(c.model()!.nodes[0]); // 'src'
                expect(c.definitionNode()?.id).toBe('src');
                c.onNodeSelected('flt');
                await Promise.resolve();
                expect(c.definitionNode()?.id).toBe('flt');
            });

            /** A DIRTY pane keeps openDefinition's confirm — that guard is the whole point. */
            it('a DIRTY open pane confirms before following the selection', async () => {
                const confirm = TestBed.inject(InspectoConfirmService) as unknown as {
                    confirmDestructive: ReturnType<typeof vi.fn>;
                };
                confirm.confirmDestructive.mockResolvedValue(false);
                const c = make();
                c.select('demo');
                await c.openDefinition(c.model()!.nodes[0]);
                c.definitionDirty.set(true);
                c.onNodeSelected('flt');
                await new Promise((r) => setTimeout(r)); // the confirm resolves on a microtask chain
                expect(confirm.confirmDestructive).toHaveBeenCalled();
                expect(c.definitionNode()?.id).toBe('src'); // declined ⇒ the pane stays put
            });
        });

        /**
         * S0 — a maximized drawer must OVERLAY the body row, never widen to 100% beside its
         * still-mounted `shrink-0` siblings: the palette aside + its handle stay in the flex row, so
         * `width:100%` overflowed it by exactly the palette's width and clipped the drawer's Apply
         * button off-screen. jsdom cannot do layout, so this pins the class state that decides it.
         */
        it('overlays the body row when the drawer is maximized, instead of widening beside the palette', () => {
            const fixture = TestBed.createComponent(PipelineEditorComponent);
            fixture.componentRef.setInput('openId', 'demo');
            const c = fixture.componentInstance;
            c.ngOnInit();
            (c as unknown as { canvas: unknown }).canvas = canvasMock();
            fixture.detectChanges();

            const el = fixture.nativeElement as HTMLElement;
            const dock = el.querySelector('aside[aria-label="Properties"]') as HTMLElement;
            expect(dock).not.toBeNull();
            expect(dock.classList.contains('absolute')).toBe(false);
            expect(dock.style.width).not.toBe('');

            c.drawerMaximized.set(true);
            fixture.detectChanges();

            expect(dock.classList.contains('absolute')).toBe(true);
            expect(dock.classList.contains('inset-0')).toBe(true);
            // ⛔ no width at all while overlaid — a 100% width is what overflowed the row.
            expect(dock.style.width).toBe('');
            expect((dock.parentElement as HTMLElement).classList.contains('relative')).toBe(true);

            c.drawerMaximized.set(false);
            fixture.detectChanges();
            expect(dock.classList.contains('absolute')).toBe(false);
            expect(dock.style.width).not.toBe(''); // the split width comes back
        });

        /**
         * P6-a: the retired `/catalog/onboard/:name/:stage` route redirects here, so the editor has to
         * open a named pipeline — and land on a named stage — straight off the URL.
         */
        describe('deep link (P6-a)', () => {
            it('opens the named pipeline as a tab on arrival', () => {
                const fixture = TestBed.createComponent(PipelineEditorComponent);
                fixture.componentRef.setInput('openId', 'demo');
                const c = fixture.componentInstance;
                c.ngOnInit();
                (c as unknown as { canvas: unknown }).canvas = canvasMock();
                fixture.detectChanges(); // flush the effect

                expect(c.selectedId()).toBe('demo');
                expect(c.openIds()).toContain('demo');
            });

            /**
             * ⚠ `select()` is a LOAD, not an idempotent setter: re-running it would refetch the graph
             * and throw away the tab's unsaved edits. The effect must consume each id exactly once.
             */
            it('does not re-open — and so cannot discard edits — when an unrelated signal changes', () => {
                const fixture = TestBed.createComponent(PipelineEditorComponent);
                fixture.componentRef.setInput('openId', 'demo');
                const c = fixture.componentInstance;
                c.ngOnInit();
                (c as unknown as { canvas: unknown }).canvas = canvasMock();
                fixture.detectChanges();
                api.pipelineGraphRaw.mockClear();

                c.dirty.set(true);
                fixture.detectChanges();
                expect(api.pipelineGraphRaw).not.toHaveBeenCalled();
                expect(c.dirty()).toBe(true);
            });

            it('opens nothing without the param', () => {
                const c = make();
                expect(c.selectedId()).toBeNull();
                expect(c.openIds()).toEqual([]);
            });
        });

        /** P6-d: the checklist is a view over the SAME graph, and a chip opens its stage's node. */
        describe('guided checklist', () => {
            /**
             * ⚠ Every stage resolves through the SERVED node-type catalog, so the default fixture
             * (which publishes only `transform.filter`) reads as five empty stages. That is the real
             * behaviour, not a test artifact — it is why the go-live gate waits for the catalog.
             */
            function guided(): PipelineEditorComponent {
                api.nodeTypes.mockReturnValue(
                    of([
                        {
                            type: 'acquisition',
                            category: 'SOURCE',
                            label: 'Collect',
                            description: '',
                            accepts: [],
                            emits: ['data'],
                            emitsNamedRoutes: false,
                            lowerable: true,
                        },
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
                    ]),
                );
                return make({ guided: true });
            }

            it('reads the open graph, live — no Validate needed first', () => {
                const c = guided();
                c.select('demo');
                const chips = c.checklist();
                expect(chips.map((s) => s.id)).toEqual(['collect', 'parse', 'schema', 'enrich', 'publish']);
                expect(chips[0].status).toBe('configured'); // the fixture's acquisition node
                expect(chips[4].status).toBe('empty'); // it has no sink
                expect(c.findings()).toEqual([]); // and the dock was never opened
                expect(c.lifecycle()).toBe('Draft');
            });

            it('a chip opens its stage`s node through the ONE open path', () => {
                const c = guided();
                c.select('demo');
                const open = vi.spyOn(c, 'openNodeConfig').mockImplementation(() => {});
                c.openStage(c.checklist()[0]); // Collect → the acquisition node
                expect(open).toHaveBeenCalledWith(expect.objectContaining({ id: 'src' }));
            });

            /**
             * Found in the preview, invisible to every unit test above: `openNodeConfig` is gated on
             * `canAuthor()`, so in View mode the whole strip did nothing at all. Selecting is not
             * gated, so the chip still reveals its Step and only the editing half is withheld.
             */
            it('still reveals the Step when authoring is withheld (View mode / Business lens)', () => {
                const c = guided();
                c.readOnly = true;
                c.select('demo');
                const open = vi.spyOn(c, 'openNodeConfig');
                c.openStage(c.checklist()[0]);
                expect(c.selectedNode()?.id).toBe('src');
                expect(open).toHaveBeenCalled(); // called, and internally a no-op — not skipped here
                expect(c.definitionNode()).toBeNull(); // nothing opened for editing
            });

            it('an empty chip opens nothing', () => {
                const c = guided();
                c.select('demo');
                const open = vi.spyOn(c, 'openNodeConfig').mockImplementation(() => {});
                c.openStage(c.checklist()[4]); // Publish — no sink node exists
                expect(open).not.toHaveBeenCalled();
            });
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

        /**
         * The sample thread is PER TAB (the reason it is a Map and not a `providers:` entry — a single
         * provider on this one-instance-hosts-every-tab component would leak one sample everywhere).
         */
        describe('sample thread', () => {
            it('gives each tab its own, and never shares a captured sample', async () => {
                const c = make();
                c.select('demo');
                api.pipelineGraphRaw.mockReturnValue(of({ name: 'other', active: false, nodes: [], edges: [] }));
                c.select('other');
                c.sampleThread()!.captureSample('other.csv', 'x\n');

                await c.activateTab('demo');
                expect(c.sampleThread()).not.toBeNull();
                expect(c.sampleThread()!.sample()).toBeNull();

                await c.activateTab('other');
                expect(c.sampleThread()!.sample()?.name).toBe('other.csv');
            });

            it('drops the thread with the tab — reopening starts clean', async () => {
                const c = make();
                c.select('demo');
                c.sampleThread()!.captureSample('demo.csv', 'x\n');
                await c.closeTab('demo');
                c.select('demo');

                expect(c.sampleThread()!.sample()).toBeNull();
            });

            it('is null before anything is open', () => {
                expect(make().sampleThread()).toBeNull();
            });
        });

        /**
         * The drawer header names the GLOSSARY's definition stage. ⚠ Three kinds reach the drawer, and
         * the binding used to be a two-way `acquisition ? 'Collector' : 'Parser'` ternary — so the Load
         * drawer announced itself as "PARSER" with the parse icon. Found by driving the preview.
         */
        describe('definition drawer header', () => {
            it('names each of the three kinds that reach the drawer', () => {
                const c = make();
                expect(c.definitionKind({ id: 'a', type: 'acquisition' }).label).toBe('Collector');
                expect(c.definitionKind({ id: 'm', type: 'transform.map' }).label).toBe('Load');
                expect(c.definitionKind({ id: 'p', type: 'parser.delimited' }).label).toBe('Parse');
                expect(c.definitionKind({ id: 'p2', type: 'parser' }).label).toBe('Parse');
            });

            it('gives the Load drawer its own icon, not the parse one', () => {
                const c = make();
                const load = c.definitionKind({ id: 'm', type: 'transform.map' }).icon;
                expect(load).not.toBe(c.definitionKind({ id: 'p', type: 'parser' }).icon);
                expect(load).not.toBe(c.definitionKind({ id: 'a', type: 'acquisition' }).icon);
            });
        });

        /**
         * Phase 4 S4 / D-13 — the per-Step switch. Two things matter: only a sink FED BY THE ROUTE is
         * offered it (the engine parks at exactly that shape), and switching back on DELETES the key
         * rather than writing `enabled: true`, so `PipelineEditable` derives an empty `disabled_steps`.
         */
        describe('per-Step switch (park/drain)', () => {
            const routed: AuthoredPipeline = {
                name: 'p',
                active: false,
                nodes: [
                    { id: 'map', type: 'transform.map' },
                    { id: 'route', type: 'transform.route', config: { mode: 'case' } },
                    { id: 'emea', type: 'sink.persistent', config: { database: '/db/emea' } },
                    { id: 'apac', type: 'sink.persistent', config: { database: '/db/apac' } },
                    { id: 'trunk', type: 'sink.persistent', config: { database: '/db' } },
                ],
                edges: [
                    { from: 'map', rel: 'data', to: 'route' },
                    { from: 'route', rel: 'route:emea', to: 'emea' },
                    { from: 'route', rel: 'route:apac', to: 'apac' },
                    // The primary destination no branch names: fed by the route node, but by a PLAIN
                    // data edge — the engine will not arm a disable on it.
                    { from: 'route', rel: 'data', to: 'trunk' },
                ],
            };

            it('offers the switch only on a sink fed by the route Step', () => {
                const c = make();
                c.model.set(structuredClone(routed));
                expect(c.parkableNode({ id: 'apac', type: 'sink.persistent' })).toBe(true);
                // ⚠ Fed by the route node, but by a plain data edge — no branch names it, so the
                // engine refuses a disable there and the switch must not be offered.
                expect(c.parkableNode({ id: 'trunk', type: 'sink.persistent' })).toBe(false);
                expect(c.parkableNode({ id: 'route', type: 'transform.route' })).toBe(false);
                expect(c.parkableNode({ id: 'map', type: 'transform.map' })).toBe(false);
                expect(c.parkableNode(null)).toBe(false);
            });

            it('offers nothing on a flat pipeline — there is no park boundary without a route', () => {
                const c = make();
                c.model.set({
                    name: 'p',
                    active: false,
                    nodes: [
                        { id: 'map', type: 'transform.map' },
                        { id: 'out', type: 'sink.persistent', config: { database: '/db' } },
                    ],
                    edges: [{ from: 'map', rel: 'data', to: 'out' }],
                });
                expect(c.parkableNode({ id: 'out', type: 'sink.persistent' })).toBe(false);
            });

            it('writes enabled:false on switch-off and DELETES the key on switch-on', () => {
                const c = make();
                c.model.set(structuredClone(routed));
                const apac = () => c.model()?.nodes.find((n) => n.id === 'apac');

                c.setNodeEnabled(apac()!, false);
                expect(apac()?.config?.['enabled']).toBe(false);
                expect(apac()?.config?.['database']).toBe('/db/apac'); // the rest of the config survives
                expect(c.dirty()).toBe(true);

                c.setNodeEnabled(apac()!, true);
                expect(apac()?.config && 'enabled' in apac()!.config!).toBe(false);
            });
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

    /** Rendered fixture with the canvas double injected — the DOM-driven tests below dispatch real events. */
    function makeRendered() {
        const fixture = TestBed.createComponent(PipelineEditorComponent);
        const c = fixture.componentInstance;
        (c as unknown as { canvas: unknown }).canvas = canvasMock();
        fixture.detectChanges(); // runs ngOnInit and attaches the host/window listeners
        return { fixture, c };
    }

    /** Item 1 (2026-09-01 batch) — the browser must warn before discarding ANY tab's unsaved edits. */
    describe('beforeunload guard', () => {
        function fireBeforeUnload(): Event {
            const e = new Event('beforeunload', { cancelable: true });
            window.dispatchEvent(e);
            return e;
        }

        it('arms the warning while the ACTIVE tab is dirty — before the per-tab mirror has flushed', () => {
            const { c } = makeRendered();
            c.select('demo');
            // Dirty through a real mutation, not dirty.set — the guard must see what an edit sets.
            // A config-only mutation keeps the graph recipe-expressible, so the rendered fixture
            // never mounts the real G6 canvas (which jsdom cannot host).
            c.setNodeEnabled(c.model()!.nodes[1], false);
            // Deliberately NO TestBed.tick(): the per-tab dirty mirror is an effect and has not run
            // yet, and a browser unload fires on its own clock — the guard cannot wait for Angular.
            const e = fireBeforeUnload();
            expect(e.defaultPrevented).toBe(true);
        });

        it('arms for a PARKED dirty tab even when the active tab is clean', () => {
            const { c } = makeRendered();
            c.select('demo');
            c.setNodeEnabled(c.model()!.nodes[1], false);
            TestBed.tick(); // flush the per-tab dirty mirror before parking
            api.pipelineGraphRaw.mockReturnValue(of({ name: 'other', active: false, nodes: [], edges: [] }));
            c.select('other');
            expect(c.dirty()).toBe(false); // the active tab is clean…
            const e = fireBeforeUnload();
            expect(e.defaultPrevented).toBe(true); // …but demo's parked edits still arm the warning
        });

        it('stays quiet when nothing is dirty', () => {
            const { c } = makeRendered();
            c.select('demo');
            const e = fireBeforeUnload();
            expect(e.defaultPrevented).toBe(false);
        });
    });

    /** Item 3 — Ctrl+S / Cmd+S saves; the browser's save-page dialog is always suppressed. */
    describe('Ctrl+S / Cmd+S', () => {
        function press(el: HTMLElement, init: KeyboardEventInit = {}): KeyboardEvent {
            const e = new KeyboardEvent('keydown', {
                key: 's',
                ctrlKey: true,
                cancelable: true,
                bubbles: true,
                ...init,
            });
            el.dispatchEvent(e);
            return e;
        }

        it('saves the dirty tab and eats the browser shortcut', () => {
            const { fixture, c } = makeRendered();
            c.select('demo');
            c.setNodeEnabled(c.model()!.nodes[1], false); // config-only mutation — no G6 mount in jsdom
            const e = press(fixture.nativeElement as HTMLElement);
            expect(e.defaultPrevented).toBe(true);
            expect(api.savePipelineGraph).toHaveBeenCalledTimes(1);
        });

        it('Cmd+S (metaKey) is the same gesture', () => {
            const { fixture, c } = makeRendered();
            c.select('demo');
            c.setNodeEnabled(c.model()!.nodes[1], false);
            const e = press(fixture.nativeElement as HTMLElement, { ctrlKey: false, metaKey: true });
            expect(e.defaultPrevented).toBe(true);
            expect(api.savePipelineGraph).toHaveBeenCalledTimes(1);
        });

        it('preventDefaults on a clean tab too, but writes nothing', () => {
            const { fixture, c } = makeRendered();
            c.select('demo');
            const e = press(fixture.nativeElement as HTMLElement);
            expect(e.defaultPrevented).toBe(true); // the browser dialog is never the right answer here
            expect(api.savePipelineGraph).not.toHaveBeenCalled();
        });

        it('never saves in View mode, even over a dirty model — but still suppresses the dialog', () => {
            const { fixture, c } = makeRendered();
            c.readOnly = true;
            c.selectedId.set('demo');
            c.model.set(structuredClone(FLOW));
            c.dirty.set(true); // the gate under test is canAuthor, not the dirty-arming path
            const e = press(fixture.nativeElement as HTMLElement);
            expect(e.defaultPrevented).toBe(true);
            expect(api.savePipelineGraph).not.toHaveBeenCalled();
        });

        it('a plain "s" keystroke is not the gesture', () => {
            const { fixture, c } = makeRendered();
            c.select('demo');
            c.setNodeEnabled(c.model()!.nodes[1], false);
            const e = press(fixture.nativeElement as HTMLElement, { ctrlKey: false });
            expect(e.defaultPrevented).toBe(false);
            expect(api.savePipelineGraph).not.toHaveBeenCalled();
        });
    });

    /** Item 2 — the open-tab SET survives a reload; dirty edits do not (that is the guard's job). */
    describe('open-tab persistence', () => {
        const KEY = 'inspecto.pipelines.openTabs';
        const row = (name: string) => ({
            name,
            active: false,
            nodeCount: 0,
            edgeCount: 0,
            produces: [],
            consumes: [],
        });

        it('persists the open set + selection as tabs change', () => {
            api.list.mockReturnValue(of([row('demo'), row('other')]));
            const c = make();
            c.select('demo');
            TestBed.tick(); // flush the persist mirror
            expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ open: ['demo'], selected: 'demo' });
        });

        it('restores the stored set LAZILY — only the selected tab lifts its graph', () => {
            localStorage.setItem(KEY, JSON.stringify({ open: ['a', 'b'], selected: 'b' }));
            api.list.mockReturnValue(of([row('a'), row('b'), row('c')]));
            const c = make();
            expect(c.openIds()).toEqual(['a', 'b']);
            expect(c.selectedId()).toBe('b');
            // Exactly one lift: 'a' waits for its first activation, exactly as the Open dialog leaves it.
            expect(api.pipelineGraphRaw).toHaveBeenCalledTimes(1);
            expect(api.pipelineGraphRaw).toHaveBeenCalledWith('b');
        });

        it('silently drops names the served list no longer has, falling back to the first survivor', () => {
            localStorage.setItem(KEY, JSON.stringify({ open: ['a', 'ghost'], selected: 'ghost' }));
            api.list.mockReturnValue(of([row('a')]));
            const c = make();
            expect(c.openIds()).toEqual(['a']);
            expect(c.selectedId()).toBe('a');
        });

        it('the ?open= deep link wins the selection over the stored one', () => {
            localStorage.setItem(KEY, JSON.stringify({ open: ['a', 'b'], selected: 'b' }));
            api.list.mockReturnValue(of([row('a'), row('b'), row('c')]));
            const fixture = TestBed.createComponent(PipelineEditorComponent);
            fixture.componentRef.setInput('openId', 'c');
            const c = fixture.componentInstance;
            (c as unknown as { canvas: unknown }).canvas = canvasMock();
            fixture.detectChanges(); // ngOnInit (restore) + the deep-link effect
            expect(c.selectedId()).toBe('c');
            expect(c.openIds()).toEqual(expect.arrayContaining(['a', 'b', 'c']));
        });

        it('ignores a corrupt stored value, and never persists graphs or dirty flags — ids only', () => {
            localStorage.setItem(KEY, '{not json');
            api.list.mockReturnValue(of([row('demo')]));
            const c = make();
            expect(c.openIds()).toEqual([]);
            c.select('demo');
            c.onDropAdd({ type: 'transform.filter', x: 1, y: 2 }); // dirty — must NOT be remembered
            TestBed.tick();
            expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ open: ['demo'], selected: 'demo' });
        });

        it('a failed list fetch must NOT wipe the stored set', () => {
            localStorage.setItem(KEY, JSON.stringify({ open: ['a'], selected: 'a' }));
            api.list.mockReturnValue(throwError(() => new Error('down')));
            make();
            TestBed.tick();
            expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ open: ['a'], selected: 'a' });
        });
    });

    /**
     * Item 4 — live validation while a tab is dirty; the Save button warns but stays enabled.
     *
     * ⚠ Driven through the component's timer SEAM, not clocks: this runner has no fakeAsync
     * ProxyZone, vi.useFakeTimers() recurses ApplicationRef.tick under zone-testing (NG0101), and a
     * real-time await destabilises the zone scheduler the same way. Capturing the armed callback
     * and firing it by hand tests the same wiring deterministically.
     */
    describe('live validation (debounced)', () => {
        /** Replace the timer seam with a capture: `pending` is the armed debounce, null once cancelled. */
        function captureTimer(c: PipelineEditorComponent): { pending: (() => void) | null; armed: number } {
            const state = { pending: null as (() => void) | null, armed: 0 };
            const seam = c as unknown as {
                armValidateTimer(cb: () => void): unknown;
                cancelValidateTimer(handle: unknown): void;
            };
            seam.armValidateTimer = (cb) => {
                state.pending = cb;
                state.armed++;
                return 0;
            };
            seam.cancelValidateTimer = () => (state.pending = null);
            return state;
        }

        it('arms on a graph mutation and, when it fires, validates without opening the dock or touching dirty', () => {
            const c = make();
            const timer = captureTimer(c);
            c.select('demo');
            TestBed.tick(); // a clean tab arms nothing
            expect(timer.pending).toBeNull();
            expect(c.findings()).toEqual([]);
            c.onDropAdd({ type: 'transform.filter', x: 10, y: 20 });
            TestBed.tick(); // the mutation re-runs the effect, arming the timer
            expect(timer.pending).not.toBeNull();
            expect(c.findings()).toEqual([]); // never synchronous — the window is open
            timer.pending!();
            expect(c.findings().length).toBeGreaterThan(0);
            expect(c.bottomTab()).toBeNull(); // the live pass never pops the dock open
            expect(c.dirty()).toBe(true); // and never launders the dirty flag
        });

        it('re-arms on every further mutation — a debounce, not an interval', () => {
            const c = make();
            const timer = captureTimer(c);
            c.select('demo');
            c.onDropAdd({ type: 'transform.filter', x: 10, y: 20 });
            TestBed.tick();
            expect(timer.armed).toBe(1);
            c.onDropAdd({ type: 'transform.filter', x: 30, y: 40 });
            TestBed.tick(); // the second mutation cancels the first window and opens a new one
            expect(timer.armed).toBe(2);
            expect(timer.pending).not.toBeNull();
            timer.pending!();
            expect(c.findings().length).toBeGreaterThan(0);
        });

        it('a pending validate is cancelled by switching tabs, and a stale callback is a no-op', () => {
            const c = make();
            const timer = captureTimer(c);
            c.select('demo');
            c.onDropAdd({ type: 'transform.filter', x: 10, y: 20 });
            TestBed.tick();
            const stale = timer.pending!;
            api.pipelineGraphRaw.mockReturnValue(of({ name: 'other', active: false, nodes: [], edges: [] }));
            c.select('other');
            TestBed.tick(); // the effect re-runs for the clean tab and cancels the timer
            expect(timer.pending).toBeNull(); // nothing armed for a clean tab
            // Belt and braces: even a callback that somehow survived the cancel refuses to fire
            // for a tab that is no longer the dirty one it was armed for.
            stale();
            expect(c.findings()).toEqual([]);
        });

        it('closing the dirty tab cancels the pending validate', async () => {
            const c = make();
            const timer = captureTimer(c);
            c.select('demo');
            c.onDropAdd({ type: 'transform.filter', x: 10, y: 20 });
            TestBed.tick();
            expect(timer.pending).not.toBeNull();
            await c.closeTab('demo'); // the confirm mock approves the discard
            TestBed.tick();
            expect(timer.pending).toBeNull();
            expect(c.findings()).toEqual([]);
        });

        it('the Save button warns (icon + tooltip naming the count) on error findings but stays ENABLED', () => {
            const { fixture, c } = makeRendered();
            c.select('demo');
            // A config-only mutation: dirties the tab while keeping the graph recipe-expressible,
            // so the rendered fixture never mounts the real G6 canvas (which jsdom cannot host).
            c.setNodeEnabled(c.model()!.nodes[1], false);
            c.findings.set([{ severity: 'error', nodeId: 'x', message: 'boom' }]);
            fixture.detectChanges();
            const btn = fixture.nativeElement.querySelector(
                'button[aria-label="Save pipeline"]',
            ) as HTMLButtonElement;
            expect(btn).toBeTruthy();
            expect(btn.disabled).toBe(false); // drafts may save with problems
            expect(btn.querySelector('mat-icon')!.classList.contains('text-warn')).toBe(true);
            expect(c.saveTooltip()).toBe('Save — 1 validation error (drafts may save with problems)');

            // Clearing the errors restores the plain Save affordance.
            c.findings.set([{ severity: 'warning', nodeId: 'x', message: 'meh' }]);
            fixture.detectChanges();
            expect(btn.querySelector('mat-icon')!.classList.contains('text-warn')).toBe(false);
            expect(c.saveTooltip()).toBe('Save');
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

    it('renameSelected patches name/description into the model without invalidating a test outcome', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        const node = c.model()!.nodes[0];
        c.selectedNode.set(node);
        c['testedStatus'].update((m) => new Map(m).set(node.id, 'tested'));

        c.renameSelected({ name: 'Renamed Step', description: 'what it does' });

        const patched = c.model()!.nodes.find((n) => n.id === node.id)!;
        expect(patched.name).toBe('Renamed Step');
        expect(patched.description).toBe('what it does');
        expect(c.selectedNode()!.name).toBe('Renamed Step');
        expect(canvasOf(c).updateNodeLabel).toHaveBeenCalledWith(node.id, 'Renamed Step');
        expect(c.dirty()).toBe(true);
        // A rename is not a config edit — the node's test outcome must survive it.
        expect(c['testedStatus']().get(node.id)).toBe('tested');

        // Blanked values clear rather than persist empty strings; the canvas label falls back to the id.
        c.renameSelected({ name: '', description: '' });
        expect(c.model()!.nodes.find((n) => n.id === node.id)!.name).toBeUndefined();
        expect(canvasOf(c).updateNodeLabel).toHaveBeenCalledWith(node.id, node.id);
    });

    /**
     * 2026-08-22, found driving the preview: the drawer strip's identity fields commit with the
     * node the DRAWER renders, which is not always the selection (stage chip / recipe insert open
     * a pane without selecting). Routing that through the selection was a silent no-op.
     */
    it('renameNode patches the DRAWER node even when a different node is selected', () => {
        const c = make();
        c.model.set(structuredClone(FLOW));
        const [first, second] = c.model()!.nodes;
        c.selectedNode.set(first);
        c.definitionNode.set(second);

        c.renameNode(second, { name: 'Drawer node', description: '' });

        expect(c.model()!.nodes.find((n) => n.id === second.id)!.name).toBe('Drawer node');
        expect(c.definitionNode()!.name).toBe('Drawer node');
        expect(c.selectedNode()!.name, 'the unrelated selection is untouched').toBe(first.name);
        expect(c.dirty()).toBe(true);
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

    /**
     * 🔴 Decision D3 / pipeline spec gap 2. The lift retypes a parse node to its per-format subtype only
     * when `parsing.frontend` names one EXPLICITLY, so a create without it opened the editor on a generic
     * Parse Step the author had to convert through a custody dialog. ⚠ Not defaulted — guessing a format
     * would author one nobody chose and re-create that Step by another route.
     */
    it('refuses to create until a parse format is chosen, and writes it as parsing.frontend', () => {
        const c = make();
        c.startNew();
        c.newName.setValue('orders');
        c.createPipeline();
        expect(config.write).not.toHaveBeenCalled();
        expect(c.newFrontend.touched).toBe(true);

        c.newFrontend.setValue('fixedwidth');
        c.createPipeline();
        expect(config.write).toHaveBeenCalledTimes(1);
        const written = config.write.mock.calls[0][1] as Record<string, unknown>;
        expect(written['parsing']).toEqual({ frontend: 'fixedwidth' });
    });

    it('a 503 on create marks the editor read-only', () => {
        config.write.mockReturnValue(throwError(() => ({ status: 503 })));
        const c = make();
        c.startNew();
        c.newName.setValue('x');
        c.newFrontend.setValue('delimited'); // D3: a create names its parse format
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

    /**
     * Found by driving the editor: the create field had ONE `mat-error`, hard-coded to the `required`
     * message, so a 409 (`duplicate`) printed "A name is required" over a field that visibly held a
     * name — a dead end with nothing to act on.
     */
    /**
     * 🔴 <b>The refusal must be VISIBLE</b> — caught in the preview, and no unit test would have found it.
     * `<inspecto-option-picker>` derives its error from its OWN `required` input and its OWN touched
     * signal, which it sets on interaction; a host validating on submit (`markAsTouched()` on the
     * control) reaches neither. So Create correctly refused while nothing appeared on screen, leaving the
     * author with a button that simply did nothing — the §0.3 failure. The host renders the message.
     */
    it('says why Create refused when no parse format was chosen', () => {
        const fixture = TestBed.createComponent(PipelineEditorComponent);
        const c = fixture.componentInstance;
        c.model.set(structuredClone(FLOW));
        fixture.detectChanges();
        c.startNew();
        c.newName.setValue('orders');
        fixture.detectChanges();
        // Clicked, not called — only a real event marks the OnPush view for check (see below).
        const create = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find(
            (b) => b.textContent?.trim() === 'Create',
        ) as HTMLButtonElement;
        create.click();
        fixture.detectChanges();

        expect(config.write).not.toHaveBeenCalled();
        const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
        expect(alert?.textContent).toContain('Choose the format');
    });

    it('names a duplicate pipeline rather than claiming the field is empty', () => {
        config.write.mockReturnValue(throwError(() => ({ status: 409 })));
        const fixture = TestBed.createComponent(PipelineEditorComponent);
        const c = fixture.componentInstance;
        // A linear model keeps `effectiveMode()` on Recipe. ⚠ Without one, `stepChain()` is null, the
        // template falls back to the G6 canvas, and jsdom's context-less canvas throws an UNHANDLED
        // `clearRect` that exits vitest 1 even though every assertion passes.
        c.model.set(structuredClone(FLOW));
        fixture.detectChanges();
        c.startNew();
        c.newName.setValue('orders');
        c.newFrontend.setValue('delimited'); // D3: a create names its parse format
        c.newName.markAsTouched();
        fixture.detectChanges();
        // Clicked, not called: a plain FormControl is not a signal, so only a real event marks the
        // OnPush view for check — calling the method leaves the message unrendered in the harness.
        const create = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find(
            (b) => b.textContent?.trim() === 'Create',
        ) as HTMLButtonElement;
        create.click();
        expect(c.newName.hasError('duplicate')).toBe(true);
        fixture.detectChanges();

        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('already exists');
        expect(text).not.toContain('A name is required');
    });

    /**
     * The scaffold narrows the typed name into the `id` pattern's alphabet ("my-pipe" → "my_pipe") and the
     * server keys the pipeline by THAT. Selecting by the typed name opened a tab on a pipeline no route
     * could resolve — the row and the load must both use the identity, with the typed value kept as the
     * display label.
     */
    it('selects a new pipeline by its stamped id, not the typed display name', () => {
        const c = make();
        c.startNew();
        c.newName.setValue('my-pipe');
        c.newFrontend.setValue('delimited'); // D3: a create names its parse format
        c.createPipeline();
        expect(config.write).toHaveBeenCalledWith('pipeline', expect.objectContaining({ id: 'my_pipe' }));
        expect(c.selectedId()).toBe('my_pipe');
        expect(c.flows().at(-1)).toMatchObject({ name: 'my_pipe', displayName: 'my-pipe' });
    });

    it('the empty path has no accessibility violations', async () => {
        const fixture = TestBed.createComponent(PipelineEditorComponent);
        fixture.detectChanges(); // no flows → empty-state, the G6 canvas is not mounted
        await expectNoA11yViolations(fixture.nativeElement);
    });

    /**
     * S1 — the selection cluster. Slots are STABLE and disable by selection kind rather than appearing
     * and disappearing, and the gating matrix is exactly what the inspector carried: Run to here and
     * Preview data are READS, so they survive the Business lens; Connect and Delete do not.
     */
    describe('toolbar selection cluster (S1)', () => {
        function toolbar(lens?: 'business') {
            if (lens) TestBed.inject(LensService).selectLens(lens);
            const fixture = TestBed.createComponent(PipelineEditorComponent);
            fixture.componentRef.setInput('openId', 'demo');
            const c = fixture.componentInstance;
            c.ngOnInit();
            (c as unknown as { canvas: unknown }).canvas = canvasMock();
            fixture.detectChanges();
            const btn = (label: string) =>
                (fixture.nativeElement as HTMLElement).querySelector(`[aria-label="${label}"]`) as HTMLButtonElement;
            return { fixture, c, btn };
        }
        const RUN = 'Run the pipeline to the selected Step';
        const PREVIEW = "Preview the selected view's data";
        const CONNECT = 'Connect the selected Step to another';
        const DELETE = 'Delete the selected Step or edge';

        it('renders all four slots, disabled while nothing is selected', () => {
            const { btn } = toolbar();
            for (const label of [RUN, PREVIEW, CONNECT, DELETE]) {
                expect(btn(label), label).toBeTruthy();
                expect(btn(label).disabled, label).toBe(true);
            }
        });

        it('enables the node verbs on a node selection, and Preview only for a view', () => {
            const { fixture, c, btn } = toolbar();
            c.selectedNode.set({ id: 'flt', type: 'transform.filter' });
            fixture.detectChanges();
            expect(btn(RUN).disabled).toBe(false);
            expect(btn(CONNECT).disabled).toBe(false);
            expect(btn(DELETE).disabled).toBe(false);
            expect(btn(PREVIEW).disabled, 'not a view').toBe(true);

            c.selectedNode.set({ id: 'v', type: 'sink.view' });
            fixture.detectChanges();
            expect(btn(PREVIEW).disabled).toBe(false);
        });

        it('an edge selection leaves only Delete enabled', () => {
            const { fixture, c, btn } = toolbar();
            c.selectedEdgeId.set('src->flt:data:1');
            fixture.detectChanges();
            expect(btn(DELETE).disabled).toBe(false);
            expect(btn(RUN).disabled).toBe(true);
            expect(btn(PREVIEW).disabled).toBe(true);
            expect(btn(CONNECT).disabled).toBe(true);
        });

        it('the Business lens keeps the two READS and drops Connect/Delete', () => {
            const { btn } = toolbar('business');
            expect(btn(RUN)).toBeTruthy();
            expect(btn(PREVIEW)).toBeTruthy();
            expect(btn(CONNECT)).toBeNull();
            expect(btn(DELETE)).toBeNull();
        });
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
        localStorage.removeItem('inspecto.pipelines.openTabs');
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
                {
                    provide: ConfigService,
                    useValue: {
                        write: vi.fn(),
                        registerPipeline: vi.fn(),
                        remove: vi.fn(),
                        // `select` reads the pipeline's own config for its satellite subdir.
                        read: vi.fn().mockReturnValue(of({ config: {}, path: 'demo_pipeline.toon' })),
                    },
                },
                { provide: ComponentsService, useValue: { list: vi.fn().mockReturnValue(of([])) } },
                { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
                {
                    provide: InspectoConfirmService,
                    // P6-b: activate/deactivate now confirm through the NEUTRAL confirm(), not confirmDestructive.
                    useValue: {
                        confirm: vi.fn().mockResolvedValue(true),
                        confirmDestructive: vi.fn().mockResolvedValue(true),
                        // The delete asks with checkboxes now: schema defaults ON, data OFF.
                        confirmDestructiveWith: vi
                            .fn()
                            .mockResolvedValue({ ok: true, checked: { schema: true, data: false } }),
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
