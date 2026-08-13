import { ChangeDetectionStrategy, Component, OnDestroy, ViewChild, computed, inject, signal } from '@angular/core';
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
import { Observable, catchError, forkJoin, map, of, switchMap, tap, throwError } from 'rxjs';
import { GrammarEditorComponent, PARSING_FRONTENDS } from 'app/inspecto/grammar';
import { mergeBlock } from 'app/inspecto/component-model';
import { OnboardingSamplePanelComponent } from './sample-panel.component';
import { OnboardingSegmentsEditorComponent } from './segments-editor.component';
import { SegmentDraft, schemaDraftFor, schemaNameFromPath, segmentDraftFrom } from './segment-drafts';
import { OnboardingStateService } from './onboarding-state.service';

/**
 * Parsing stage — a thin HOST over the shared `<inspecto-grammar-editor>` (2026-08-04). The format
 * catalog, options form, sample sniffing, Test parse, the fixed-width slices and the parsed result all
 * live in the editor, which the Pipelines parse node shares. This pane owns only what is genuinely
 * Onboarding's:
 *
 * <ul>
 *   <li>the cross-stage sample strip and the `parsePreview`/`parseError` state the Sample panel and
 *       the Schema stage read — so a BUILT-IN Test parse must keep feeding them ({@link previewFn});</li>
 *   <li>the stage-nav dirty registry and the workbench lens gate;</li>
 *   <li>the two WRITE paths: a built-in patches the `parsing:` block; an ingestable plugin writes one
 *       schema `.toon` per segment FIRST and only then references them.</li>
 * </ul>
 *
 * <p>The segments editor stays here, projected into the shared editor as `[grammarExtras]`: it is
 * inseparable from that second write path, and the shared editor deliberately has no write path.
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
export class OnboardingParsingPaneComponent implements OnDestroy {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private configApi = inject(ConfigService);
    private parsersApi = inject(ParsersService);
    private toastr = inject(ToastrService);
    private spaces = inject(SpacesService);

    @ViewChild(GrammarEditorComponent) grammar?: GrammarEditorComponent;
    @ViewChild(OnboardingSegmentsEditorComponent) segmentsEditor?: OnboardingSegmentsEditorComponent;

    readonly parsingBlock = this.state.block('parsing') ?? {};

    /**
     * FQCN this config parses through, from either key the engine accepts (`parsing.plugin.ingester`
     * or legacy `processing.ingester`) — the handle the editor re-selects the served parser by.
     */
    readonly configuredIngester: string = String(
        (this.parsingBlock['plugin'] as Record<string, unknown> | undefined)?.['ingester'] ??
            this.state.block('processing')?.['ingester'] ??
            '',
    ).trim();

    readonly sampleText = computed(() => this.state.sample()?.text ?? '');
    readonly saving = signal(false);

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

    private readonly dirtyCheck = (): boolean =>
        (this.grammar?.isDirty() ?? false) || (this.segmentsEditor?.isDirty() ?? false);

    constructor() {
        this.state.registerDirtyCheck(this.dirtyCheck);
        this.loadSavedSegments();
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
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

        const draft = mergeBlock(this.state.config() ?? {}, { parsing: this.grammar?.value() ?? {} });
        this.state.parseError.set(null);
        return this.configApi.previewParsing(draft, text).pipe(
            tap((p) => {
                this.state.parsePreview.set(p);
                // Re-parsing invalidates any schema cast-check computed against the old rows.
                this.state.schemaPreview.set(null);
                this.state.schemaError.set(null);
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
                this.state.parsePreview.set(null);
                this.state.parseError.set(apiErrorMessage(e, 'The sample does not parse with these settings.'));
                return throwError(() => e);
            }),
        );
    };

    // ── plugin (segments) save ───────────────────────────────────────────────────

    private readonly pipelineName = String((this.state.config() ?? {})['name'] ?? '');

    private base(): string {
        return this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
    }
    /** One schema toon per segment, named by convention — the Schema stage's `conventionPath` idiom. */
    private schemaNameFor(segmentKey: string): string {
        return `${this.pipelineName}_${segmentKey}`.replace(/[^A-Za-z0-9_]+/g, '_');
    }
    private schemaPathFor(segmentKey: string): string {
        return `${this.base()}/config/${this.schemaNameFor(segmentKey)}.toon`;
    }

    /** `segment key → schema-toon path` as stored in `parsing.plugin.segments`. */
    private savedSegmentPaths(): Record<string, unknown> {
        const plugin = this.parsingBlock['plugin'] as Record<string, unknown> | undefined;
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
    readonly initialSegments = signal<SegmentDraft[]>(
        Object.keys(this.savedSegmentPaths()).map((key) => ({ key, columns: [] })),
    );
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
     * Persist an ingestable plugin parser: write each segment's schema toon, then point
     * `parsing.plugin` at them. Two hops, same as the Schema stage (write the schema, then
     * reference it) — the pipeline must never name a schema file that does not exist yet, which is
     * why the block is patched only after every write resolves.
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

        this.saving.set(true);
        const writes = drafts.map((s) =>
            this.configApi.write('schema', schemaDraftFor(s, this.schemaNameFor(s.key)), { overwrite: true }),
        );
        forkJoin(writes)
            .pipe(
                switchMap(() => {
                    const segmentPaths: Record<string, string> = {};
                    for (const s of drafts) segmentPaths[s.key] = this.schemaPathFor(s.key);
                    return this.state.saveBlock({
                        parsing: {
                            frontend: 'plugin',
                            plugin: {
                                ingester: plugin.ingesterClass,
                                segments: segmentPaths,
                                ingester_config: ingesterConfig,
                            },
                        },
                    });
                }),
            )
            .subscribe({
                next: () => {
                    this.saving.set(false);
                    grammarEditor.markPristine();
                    segments.markPristine();
                    this.toastr.success(`Parsing saved — ${drafts.length} segment(s)`);
                },
                error: (e) => {
                    this.saving.set(false);
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
        this.saving.set(true);
        this.state.saveBlock({ parsing: grammarEditor.value() }).subscribe({
            next: () => {
                this.saving.set(false);
                grammarEditor.markPristine();
                this.toastr.success('Parsing saved');
            },
            error: () => this.saving.set(false),
        });
    }
}
