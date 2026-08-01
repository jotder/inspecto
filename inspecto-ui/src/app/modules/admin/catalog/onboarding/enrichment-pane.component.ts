import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, effect, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { CatalogService, ConfigService, DbBrowserService, EnrichmentPreview, LensService, SpacesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { EnrichmentEditorComponent } from 'app/inspecto/enrichment/enrichment-editor.component';
import { OnboardingStateService } from './onboarding-state.service';

/**
 * Enrichment stage (optional, Streams) — authors the companion `EnrichmentConfig`
 * (`<pipeline>_enrich`) through the SHARED `<inspecto-enrichment-editor>` (W4b: one editor, two
 * adopters — the Pipelines `enrichment` node dialog is the other). This host derives everything
 * the guided flow can know instead of asking: input = this pipeline's Stage-1 output, trigger =
 * `on_pipeline` with the engine's normalized id (what `BatchEvent.pipeline()` carries), output =
 * the space's `enriched/` convention. Saves write the config AND re-register it
 * (`POST /enrichment`) — enrichments do not hot-reload by mtime, so registration is what makes a
 * save apply to the running service.
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
export class OnboardingEnrichmentPaneComponent implements OnInit, OnDestroy {
    /** Rows of the Stage-1 output sampled to seed the `input` view in a preview. */
    private static readonly SAMPLE_LIMIT = 200;

    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private configApi = inject(ConfigService);
    private catalogApi = inject(CatalogService);
    private db = inject(DbBrowserService);
    private spaces = inject(SpacesService);
    private toastr = inject(ToastrService);

    /** The shared editor (rendered only once the author opts in — the stage is optional). */
    private readonly editor = viewChild(EnrichmentEditorComponent);

    readonly started = signal(this.state.enrichmentConfig() !== null);
    readonly saving = signal(false);
    // ── transform preview (stateless dry-run over a sample of the Stage-1 output) ──
    readonly previewing = signal(false);
    readonly previewResult = signal<EnrichmentPreview | null>(null);
    readonly previewError = signal<string | null>(null);
    readonly previewRows = computed<Record<string, unknown>[]>(() => this.previewResult()?.rows ?? []);
    /** Produced Reference Datasets bindable by name (id = the producer's normalized pipeline id). */
    readonly referenceOptions = signal<{ id: string; label: string }[]>([]);

    private readonly dirtyCheck = (): boolean => this.editor()?.isDirty() ?? false;

    constructor() {
        this.state.registerDirtyCheck(this.dirtyCheck);
        // Hydrate from the server-held companion — including when its (async) read lands after
        // this pane mounted (and when the editor itself mounts a tick after `started` flips).
        // Never clobber unsaved edits.
        effect(() => {
            const cfg = this.state.enrichmentConfig();
            if (!cfg) return;
            this.started.set(true);
            const editor = this.editor();
            if (editor && !this.dirtyCheck()) editor.hydrate(cfg);
        });
    }

    ngOnInit(): void {
        this.catalogApi.references().subscribe({
            next: (nodes) => {
                const self = this.state.normalizedName();
                this.referenceOptions.set(nodes
                    .filter((n) => typeof n.attrs?.['pipeline'] === 'string' && n.attrs['pipeline'] !== self)
                    .map((n) => ({ id: String(n.attrs!['pipeline']), label: n.label })));
            },
            error: () => this.referenceOptions.set([]),
        });
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
    }

    start(): void {
        this.started.set(true);
    }

    // ── derived plumbing (shown, never asked) ────────────────────────────────────

    private base(): string {
        return this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
    }

    /** Input = this pipeline's Stage-1 output (kept verbatim on resume — the file is the truth). */
    inputBlock(): Record<string, unknown> {
        const existing = this.state.enrichmentConfig();
        if (existing?.['input']) return existing['input'] as Record<string, unknown>;
        const dirs = this.state.block('dirs') ?? {};
        const output = this.state.block('output') ?? {};
        return {
            database: String(dirs['database'] ?? ''),
            format: String(output['format'] ?? 'PARQUET').toUpperCase(),
            partitions: ['year', 'month', 'day'],
        };
    }

    outputBlock(): Record<string, unknown> {
        const existing = this.state.enrichmentConfig();
        if (existing?.['output']) return existing['output'] as Record<string, unknown>;
        return {
            database: `${this.base()}/data/enriched/${this.state.enrichName()}`,
            format: 'PARQUET',
            partitions: ['year', 'month', 'day'],
        };
    }

    /** The full enrichment draft from the current form, or null on a blocking validation problem. */
    private buildDraft(): Record<string, unknown> | null {
        const parts = this.editor()?.build();
        if (!parts) return null;
        const draft: Record<string, unknown> = {
            name: this.state.enrichName(),
            input: this.inputBlock(),
            output: this.outputBlock(),
            transform: parts.transform,
            triggers: { on_pipeline: this.state.normalizedName() },
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
        this.db.table({ name: this.state.normalizedName(), limit: OnboardingEnrichmentPaneComponent.SAMPLE_LIMIT })
            .subscribe({
                next: (res) => this.runPreview(draft, res.rows),
                error: () => this.runPreview(draft, []),
            });
    }

    private runPreview(draft: Record<string, unknown>, sampleRows: Record<string, unknown>[]): void {
        if (sampleRows.length === 0) {
            this.previewing.set(false);
            this.previewResult.set(null);
            this.toastr.warning(
                `No data in "${this.state.normalizedName()}" yet to preview against — run the stream first.`);
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

        this.saving.set(true);
        this.configApi.write('enrichment', draft, { overwrite: true }).subscribe({
            next: (written) => {
                // Register every save: enrichments do NOT hot-reload by mtime (unlike the pipeline).
                this.configApi.registerEnrichment(written.path).subscribe({
                    next: () => {
                        this.saving.set(false);
                        this.finishSave(draft);
                        this.toastr.success('Enrichment saved — runs after every committed batch');
                    },
                    error: (e) => {
                        this.saving.set(false);
                        this.finishSave(draft);
                        this.toastr.warning(apiErrorMessage(e,
                            'Saved, but registering failed — it will load on the next service restart.'));
                    },
                });
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the enrichment.'));
            },
        });
    }

    private finishSave(draft: Record<string, unknown>): void {
        this.state.enrichmentConfig.set(draft);
        this.editor()?.markSaved();
    }
}
