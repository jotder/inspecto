import {
    ChangeDetectionStrategy,
    Component,
    HostListener,
    OnInit,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import {
    CatalogService,
    ConfigService,
    DbBrowserService,
    EnrichmentPreview,
    LensService,
    SpacesService,
    apiErrorMessage,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { EnrichmentEditorComponent } from 'app/inspecto/enrichment/enrichment-editor.component';
import { EnrichmentWiring, enrichmentWiringDefaults } from 'app/inspecto/enrichment/enrichment-wiring';

/**
 * Enrichment stage (optional, Streams) — authors the companion `EnrichmentConfig`
 * (`<pipeline>_enrich`) through the SHARED `<inspecto-enrichment-editor>` (W4b: one editor, two
 * adopters — the Pipelines `enrichment` node dialog is the other). This host derives everything
 * the guided flow can know instead of asking: input = this pipeline's Stage-1 output, trigger =
 * `on_pipeline` with the engine's normalized id (what `BatchEvent.pipeline()` carries), output =
 * the space's `enriched/` convention.
 *
 * <p>Pure (definition-surface unification D2): unlike the block-authoring stages this one has no
 * pipeline block at all — its whole deliverable IS the companion, and the companion is what the
 * HOST holds and what stage readiness reads. So the pane emits the finished draft on `applied` and
 * the host owns both hops of the write (`POST /config/write type=enrichment` THEN `POST
 * /enrichment` — enrichments do not hot-reload by mtime, so registration is what makes a save apply
 * to the running service). The pane returns to pristine when the host hands the very draft it
 * emitted back on `enrichment`; a failed save never does, so the unsaved-changes guard still fires.
 */
@Component({
    selector: 'app-onboarding-enrichment-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        EnrichmentEditorComponent,
        DataTableComponent,
    ],
    templateUrl: './enrichment-pane.component.html',
})
export class OnboardingEnrichmentPaneComponent implements OnInit {
    /** Rows of the Stage-1 output sampled to seed the `input` view in a preview. */
    private static readonly SAMPLE_LIMIT = 200;

    protected readonly lens = inject(LensService);
    private configApi = inject(ConfigService);
    private catalogApi = inject(CatalogService);
    private db = inject(DbBrowserService);
    private spaces = inject(SpacesService);
    private toastr = inject(ToastrService);

    /** The shared editor (rendered only once the author opts in — the stage is optional). */
    private readonly editor = viewChild(EnrichmentEditorComponent);

    /** The server-held pipeline draft — the `dirs`/`output` blocks the wiring is derived from. */
    readonly config = input<Record<string, unknown> | null>(null);
    /** The server-held companion `EnrichmentConfig`, or null when none is authored yet. */
    readonly enrichment = input<Record<string, unknown> | null>(null);
    /** Companion identity (`<pipeline>_enrich`) — the host owns the naming convention. */
    readonly enrichName = input('');
    /** The engine's normalized pipeline id — what `BatchEvent.pipeline()` carries. */
    readonly pipelineId = input('');
    /** Host-owned: a save of the emitted draft is in flight. */
    readonly saving = input(false);

    /** The finished companion draft to persist AND register. The host writes it. */
    readonly applied = output<Record<string, unknown>>();
    /** Whether the pane holds edits against the `enrichment` input it was last seeded with. */
    readonly dirtyChange = output<boolean>();

    readonly started = signal(false);
    // ── transform preview (stateless dry-run over a sample of the Stage-1 output) ──
    readonly previewing = signal(false);
    readonly previewResult = signal<EnrichmentPreview | null>(null);
    readonly previewError = signal<string | null>(null);
    readonly previewRows = computed<Record<string, unknown>[]>(() => this.previewResult()?.rows ?? []);
    /** Produced Reference Datasets bindable by name (id = the producer's normalized pipeline id). */
    readonly referenceOptions = signal<{ id: string; label: string }[]>([]);

    private readonly dirtyCheck = (): boolean => this.editor()?.isDirty() ?? false;

    /** The draft this pane emitted and is waiting to see handed back — the save-succeeded signal. */
    private pendingDraft: Record<string, unknown> | null = null;
    private lastDirty = false;

    constructor() {
        // Hydrate from the server-held companion — including when its (async) read lands after
        // this pane mounted (and when the editor itself mounts a tick after `started` flips).
        // Never clobber unsaved edits.
        effect(() => {
            const cfg = this.enrichment();
            if (!cfg) return;
            this.started.set(true);
            const editor = this.editor();
            if (!editor) return;
            if (cfg === this.pendingDraft) {
                // Our OWN save came back from the host: the emitted value is the new baseline.
                // Re-hydrating instead would rebuild the form from a value it already holds.
                this.pendingDraft = null;
                editor.markSaved();
            } else if (!this.dirtyCheck()) {
                editor.hydrate(cfg);
            }
            this.emitDirty();
        });
    }

    /**
     * Dirty is derived on interaction, not streamed — the same contract as the Collection (P2-2),
     * Parsing (P2-3) and Schema (P2-4) panes.
     */
    @HostListener('input')
    @HostListener('click')
    onInteraction(): void {
        this.emitDirty();
    }

    private emitDirty(): void {
        const dirty = this.dirtyCheck();
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    ngOnInit(): void {
        this.catalogApi.references().subscribe({
            next: (nodes) => {
                const self = this.pipelineId();
                this.referenceOptions.set(
                    nodes
                        .filter((n) => typeof n.attrs?.['pipeline'] === 'string' && n.attrs['pipeline'] !== self)
                        .map((n) => ({ id: String(n.attrs!['pipeline']), label: n.label })),
                );
            },
            error: () => this.referenceOptions.set([]),
        });
    }

    start(): void {
        this.started.set(true);
    }

    // ── derived plumbing (shown, never asked) ────────────────────────────────────

    private base(): string {
        return this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
    }

    private blockOf(name: string): Record<string, unknown> {
        const v = (this.config() ?? {})[name];
        return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
    }

    /** The derived wiring, from the shared convention both hosts author against (P6-c). */
    private derived(): EnrichmentWiring {
        return enrichmentWiringDefaults({
            enrichName: this.enrichName(),
            pipelineId: this.pipelineId(),
            base: this.base(),
            inputDatabase: String(this.blockOf('dirs')['database'] ?? ''),
            inputFormat: String(this.blockOf('output')['format'] ?? ''),
        });
    }

    /** Input = this pipeline's Stage-1 output (kept verbatim on resume — the file is the truth). */
    inputBlock(): Record<string, unknown> {
        const existing = this.enrichment();
        if (existing?.['input']) return existing['input'] as Record<string, unknown>;
        return this.derived().input;
    }

    outputBlock(): Record<string, unknown> {
        const existing = this.enrichment();
        if (existing?.['output']) return existing['output'] as Record<string, unknown>;
        return this.derived().output;
    }

    /** The full enrichment draft from the current form, or null on a blocking validation problem. */
    private buildDraft(): Record<string, unknown> | null {
        const parts = this.editor()?.build();
        if (!parts) return null;
        const draft: Record<string, unknown> = {
            name: this.enrichName(),
            input: this.inputBlock(),
            output: this.outputBlock(),
            transform: parts.transform,
            triggers: this.derived().triggers,
        };
        if (Object.keys(parts.references).length > 0) draft['references'] = parts.references;
        return draft;
    }

    /**
     * Dry-run the transform: fetch a bounded sample of this stream's Stage-1 output (the `input`
     * view) and run the draft against it — stateless, nothing persisted. Read-only, so it stays
     * available in every lens. A stream with no ingested data yet has nothing to preview against.
     */
    preview(): void {
        const draft = this.buildDraft();
        if (!draft) return;
        this.previewing.set(true);
        this.previewError.set(null);
        this.db.table({ name: this.pipelineId(), limit: OnboardingEnrichmentPaneComponent.SAMPLE_LIMIT }).subscribe({
            next: (res) => this.runPreview(draft, res.rows),
            error: () => this.runPreview(draft, []),
        });
    }

    private runPreview(draft: Record<string, unknown>, sampleRows: Record<string, unknown>[]): void {
        if (sampleRows.length === 0) {
            this.previewing.set(false);
            this.previewResult.set(null);
            this.toastr.warning(`No data in "${this.pipelineId()}" yet to preview against — run the stream first.`);
            return;
        }
        this.configApi.previewEnrichment(draft, sampleRows).subscribe({
            next: (p) => {
                this.previewing.set(false);
                this.previewResult.set(p);
            },
            error: (e) => {
                this.previewing.set(false);
                this.previewResult.set(null);
                this.previewError.set(apiErrorMessage(e, 'The transform failed on the sample.'));
            },
        });
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        const draft = this.buildDraft();
        if (draft === null) return;
        // Remembered by IDENTITY: the pane goes pristine only when the host hands back THIS object,
        // which is exactly "the save landed" — a failure hands back nothing and it stays dirty.
        this.pendingDraft = draft;
        this.applied.emit(draft);
    }
}
