import {
    ChangeDetectionStrategy,
    Component,
    HostListener,
    OnInit,
    ViewChild,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';
import {
    ConfigService,
    LensService,
    ParserDef,
    ParserPreview,
    ParserTreeNode,
    ParsersService,
    SpacesService,
    apiErrorMessage,
} from 'app/inspecto/api';
import { Observable, catchError, forkJoin, map, of, tap, throwError } from 'rxjs';
import { GrammarEditorComponent, PARSING_FRONTENDS } from 'app/inspecto/grammar';
import { mergeBlock } from 'app/inspecto/component-model';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { OnboardingSamplePanelComponent } from './sample-panel.component';
import { OnboardingSegmentsEditorComponent } from './segments-editor.component';
import { SegmentDraft, schemaDraftFor, schemaNameFromPath, segmentDraftFrom } from './segment-drafts';

/**
 * Parsing stage — a thin HOST over the shared `<inspecto-grammar-editor>` (2026-08-04). The format
 * catalog, options form, sample sniffing, Test parse, the fixed-width slices and the parsed result all
 * live in the editor, which the Pipelines parse node shares. This pane owns only what is genuinely
 * Onboarding's:
 *
 * <ul>
 *   <li>the cross-stage sample strip and the `parsePreview`/`parseError` state the Sample panel and
 *       the Schema stage read — the shared {@link DefinitionStateService} thread, so a BUILT-IN Test
 *       parse must keep feeding it ({@link previewFn});</li>
 *   <li>the workbench lens gate;</li>
 *   <li>the plugin write path's FIRST hop: an ingestable plugin writes one schema `.toon` per segment
 *       before anything may reference them.</li>
 * </ul>
 *
 * <p>Pure (definition-surface unification D2): it reads the draft from `config` and emits the
 * `parsing:` block to persist on `applied` — the HOST owns the pipeline write. The one deliberate
 * asymmetry is the plugin path's per-segment schema toons: those are companion artifacts of THIS
 * stage (like Enrichment's companion config), so the pane still writes them itself, and only then
 * emits the block that references them — the pipeline must never name a schema file that does not
 * exist yet.
 *
 * <p>The segments editor stays here, projected into the shared editor as `[grammarExtras]`: it is
 * inseparable from that write path, and the shared editor deliberately has no write path.
 */
@Component({
    selector: 'app-onboarding-parsing-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatProgressSpinnerModule,
        GrammarEditorComponent,
        OnboardingSamplePanelComponent,
        OnboardingSegmentsEditorComponent,
    ],
    templateUrl: './parsing-pane.component.html',
})
export class OnboardingParsingPaneComponent implements OnInit {
    protected readonly lens = inject(LensService);
    private readonly definition = inject(DefinitionStateService);
    private configApi = inject(ConfigService);
    private parsersApi = inject(ParsersService);
    private toastr = inject(ToastrService);
    private spaces = inject(SpacesService);

    /** The server-held pipeline draft — the block this stage edits plus the context a preview merges over. */
    readonly config = input<Record<string, unknown> | null>(null);
    /** Host-owned: a save of the emitted block is in flight. */
    readonly saving = input(false);

    /** The `parsing:` block to persist. The host writes it. */
    readonly applied = output<Record<string, unknown>>();
    /** Whether the pane holds edits against the `config` input it was last seeded with. */
    readonly dirtyChange = output<boolean>();

    @ViewChild(GrammarEditorComponent) grammar?: GrammarEditorComponent;
    @ViewChild(OnboardingSegmentsEditorComponent) segmentsEditor?: OnboardingSegmentsEditorComponent;

    private blockOf(name: string): Record<string, unknown> {
        const v = (this.config() ?? {})[name];
        return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
    }

    readonly parsingBlock = computed(() => this.blockOf('parsing'));

    /**
     * FQCN this config parses through, from either key the engine accepts (`parsing.plugin.ingester`
     * or legacy `processing.ingester`) — the handle the editor re-selects the served parser by.
     */
    readonly configuredIngester = computed(() =>
        String(
            (this.parsingBlock()['plugin'] as Record<string, unknown> | undefined)?.['ingester'] ??
                this.blockOf('processing')['ingester'] ??
                '',
        ).trim(),
    );

    readonly sampleText = computed(() => this.definition.sample()?.text ?? '');
    /** The pane's OWN in-flight work — the per-segment schema writes that precede the emit. */
    readonly writing = signal(false);
    /** Anything in flight, either side of the seam — what the Save button disables on. */
    readonly busy = computed(() => this.writing() || this.saving());

    /**
     * The editor's selected plugin, mirrored from `(pluginChange)` rather than read off the
     * `@ViewChild` in the template — a view query is not resolved during the first render, and the
     * segments editor is projected INTO the editor, so reading it there would evaluate too early.
     */
    readonly plugin = signal<ParserDef | null>(null);
    /** Only an ingestable plugin can author segments — a preview-only one has nothing to save. */
    readonly pluginIngestable = computed(() => {
        const p = this.plugin();
        return !!p && p.ingestable && !!p.ingesterClass;
    });
    /** The decoded record forest the segments editor derives its proposals from. */
    readonly pluginTree = signal<ParserTreeNode[] | null>(null);

    onPreviewed(p: ParserPreview): void {
        this.pluginTree.set(p.kind === 'tree' ? p.nodes : null);
    }

    private lastDirty = false;

    constructor() {
        /**
         * Re-seeding is how this pane returns to pristine — there is no host→pane method call.
         * A successful save updates the host's config, which hands back a NEW `config` object and
         * re-runs this; a FAILED save leaves the object identical, so the pane correctly stays dirty
         * and the unsaved-changes guard still fires. (The editor's `initial` setter re-seeds off the
         * computed bindings; this only has to reset the dirty baseline.)
         */
        effect(() => {
            this.config();
            this.grammar?.markPristine();
            this.segmentsEditor?.markPristine();
            this.emitDirty();
        });
    }

    ngOnInit(): void {
        // Inputs are bound by now (never in the constructor) — seed the saved segment keys and read
        // their columns back.
        this.initialSegments.set(Object.keys(this.savedSegmentPaths()).map((key) => ({ key, columns: [] })));
        this.loadSavedSegments();
    }

    /**
     * Dirty is derived on interaction, not streamed: the editors expose `isDirty()` as a method with
     * no output, so re-derive after any input/click inside the pane and report only the transitions —
     * the same contract as the Collection pane (P2-2).
     */
    @HostListener('input')
    @HostListener('click')
    onInteraction(): void {
        this.emitDirty();
    }

    private emitDirty(): void {
        const dirty = (this.grammar?.isDirty() ?? false) || (this.segmentsEditor?.isDirty() ?? false);
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    /**
     * Route a BUILT-IN Test parse through `POST /config/preview/parsing` instead of the editor's
     * default stateless endpoint, because Onboarding's parsed hop is cross-stage state: the Sample
     * panel renders it and the Schema stage's `hasSource` gate depends on it. A PLUGIN preview stays
     * on the stateless route and pane-local — the sample thread is fed only by the built-ins a draft
     * can actually go live with.
     */
    readonly previewFn = (type: string, grammar: Record<string, unknown>, text: string): Observable<ParserPreview> => {
        if (!PARSING_FRONTENDS.some((f) => f.id === type)) return this.parsersApi.preview(type, grammar, text);

        const draft = mergeBlock(this.config() ?? {}, { parsing: this.grammar?.value() ?? {} });
        this.definition.parseError.set(null);
        return this.configApi.previewParsing(draft, text).pipe(
            tap((p) => {
                this.definition.parsePreview.set(p);
                // Re-parsing invalidates any schema cast-check computed against the old rows.
                this.definition.schemaPreview.set(null);
                this.definition.schemaError.set(null);
            }),
            map(
                (p): ParserPreview => ({
                    kind: 'table',
                    columns: p.columns,
                    rows: p.rows,
                    rowCount: p.rowCount,
                    rejectedRows: p.rejectedRows,
                }),
            ),
            catchError((e) => {
                this.definition.parsePreview.set(null);
                this.definition.parseError.set(apiErrorMessage(e, 'The sample does not parse with these settings.'));
                return throwError(() => e);
            }),
        );
    };

    // ── plugin (segments) save ───────────────────────────────────────────────────

    private pipelineName(): string {
        return String((this.config() ?? {})['name'] ?? '');
    }

    private base(): string {
        return this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
    }
    /** One schema toon per segment, named by convention — the Schema stage's `conventionPath` idiom. */
    private schemaNameFor(segmentKey: string): string {
        return `${this.pipelineName()}_${segmentKey}`.replace(/[^A-Za-z0-9_]+/g, '_');
    }
    private schemaPathFor(segmentKey: string): string {
        return `${this.base()}/config/${this.schemaNameFor(segmentKey)}.toon`;
    }

    /** `segment key → schema-toon path` as stored in `parsing.plugin.segments`. */
    private savedSegmentPaths(): Record<string, unknown> {
        const plugin = this.parsingBlock()['plugin'] as Record<string, unknown> | undefined;
        const segs = plugin?.['segments'];
        return segs && typeof segs === 'object' ? (segs as Record<string, unknown>) : {};
    }

    /**
     * The segments already saved in `parsing.plugin`, re-hydrated for the editor — keys AND columns.
     *
     * <p>The pipeline stores only `segment key → schema-toon path`; the columns live in those toons,
     * so each one is read back ({@link loadSavedSegments}). Starts at the keys-only shape so the
     * editor renders immediately and degrades to the previous behavior if a read fails.
     */
    readonly initialSegments = signal<SegmentDraft[]>([]);
    readonly segmentsLoading = signal(false);

    /**
     * Read each saved segment's schema toon back so re-editing an existing stream does not require a
     * destructive re-derive. Failures are per-segment and non-fatal: that segment stays keys-only
     * (the operator re-derives), which is exactly the behavior before this read existed.
     *
     * <p>A 404 is expected and silent — the pipeline can legitimately reference a schema that was
     * never written (interrupted save), and the Schema stage treats 404 the same way.
     */
    private loadSavedSegments(): void {
        const paths = this.savedSegmentPaths();
        const keys = Object.keys(paths);
        if (keys.length === 0) return;
        const reads = keys.map((key) => {
            const name = schemaNameFromPath(paths[key]);
            if (!name) return of<SegmentDraft>({ key, columns: [] });
            return this.configApi.read('schema', name).pipe(
                map((r) => segmentDraftFrom(key, r.config)),
                catchError((e) => {
                    if (e?.status !== 404) {
                        this.toastr.warning(`Could not load the saved columns for segment "${key}".`);
                    }
                    return of<SegmentDraft>({ key, columns: [] });
                }),
            );
        });
        this.segmentsLoading.set(true);
        forkJoin(reads).subscribe((drafts) => {
            this.segmentsLoading.set(false);
            // The editor's `initial` setter REBUILDS the FormArray, so applying a late read over
            // edits the operator already made would silently discard them. They chose to start from
            // scratch — the saved columns are no longer what they want.
            if (this.segmentsEditor?.isDirty()) return;
            this.initialSegments.set(drafts);
        });
    }

    /**
     * Persist an ingestable plugin parser: write each segment's schema toon, then emit the
     * `parsing.plugin` block that points at them. Two hops, same as the Schema stage (write the
     * schema, then reference it) — the pipeline must never name a schema file that does not exist
     * yet, which is why the block is emitted only after every write resolves.
     */
    private savePlugin(plugin: ParserDef): void {
        const segments = this.segmentsEditor;
        if (!segments || !segments.validate()) {
            this.toastr.warning(segments?.problem() ?? 'Add at least one segment.');
            return;
        }
        const grammarEditor = this.grammar;
        if (!grammarEditor?.validate()) return;
        const drafts = segments.value();
        // The parser's grammar IS the ingester's config, minus its id namespace — so preview and
        // ingest are driven by the same authored values and cannot drift.
        const ingesterConfig = (grammarEditor.grammar()[plugin.id] ?? {}) as Record<string, unknown>;

        this.writing.set(true);
        const writes = drafts.map((s) =>
            this.configApi.write('schema', schemaDraftFor(s, this.schemaNameFor(s.key)), { overwrite: true }),
        );
        forkJoin(writes).subscribe({
            next: () => {
                this.writing.set(false);
                const segmentPaths: Record<string, string> = {};
                for (const s of drafts) segmentPaths[s.key] = this.schemaPathFor(s.key);
                this.applied.emit({
                    frontend: 'plugin',
                    plugin: {
                        ingester: plugin.ingesterClass,
                        segments: segmentPaths,
                        ingester_config: ingesterConfig,
                    },
                });
            },
            error: (e) => {
                this.writing.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the segments.'));
            },
        });
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        const grammarEditor = this.grammar;
        if (!grammarEditor) return;
        const plugin = grammarEditor.pluginDef();
        if (plugin) {
            if (grammarEditor.pluginIngestable()) this.savePlugin(plugin);
            return; // a preview-only plugin has nothing honest to save
        }
        if (!grammarEditor.validate()) return;
        this.applied.emit(grammarEditor.value());
    }
}
