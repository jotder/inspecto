import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { firstValueFrom } from 'rxjs';
import {
    ComponentsService,
    ConfigImpact,
    ConfigService,
    DatasetRegistrationService,
    LensService,
    apiErrorMessage,
    datasetManualHint,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoBreadcrumbComponent } from 'app/inspecto/components/breadcrumb.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { StreamTransferService } from 'app/inspecto/transfer/stream-transfer.service';
import { OnboardingCollectionPaneComponent } from './collection-pane.component';
import { OnboardingEnrichmentPaneComponent } from './enrichment-pane.component';
import { OnboardingParsingPaneComponent } from './parsing-pane.component';
import { OnboardingPlaceholderPaneComponent } from './placeholder-pane.component';
import { OnboardingPublishPaneComponent } from './publish-pane.component';
import { OnboardingSchemaMappingPaneComponent } from './schema-mapping-pane.component';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { OnboardingStage, OnboardingStageId, OnboardingStateService } from './onboarding-state.service';

/**
 * "2 datasets, 1 widget" — the impact report as a phrase for a confirm dialog. Names kinds and
 * counts rather than listing every id: the dialog has to be readable, and the operator only needs
 * to know the shape of what breaks before deciding.
 */
function describeDependents(impact: ConfigImpact): string {
    return Object.entries(impact.dependents)
        .map(([kind, items]) => `${items.length} ${kind}${items.length === 1 ? '' : 's'}`)
        .join(', ');
}

/**
 * Stream/Reference onboarding shell (`/catalog/onboard/:name/:stage?`) — a stage RAIL over the
 * server-held pipeline draft, not a locked stepper: the rail mirrors the data path (Collect →
 * Parse → Shape → Publish), every stage is jumpable, readiness is computed from the config
 * blocks, and the whole session is resumable because the draft IS the server state (D3).
 * Opening without a stage lands on the first incomplete one. ONE captured sample threads through
 * the stages (§4.3) — session-held in the shared {@link DefinitionStateService} this shell
 * provides, but the capture UI lives in the Parsing stage, the stage that actually consumes it.
 *
 * <p>Since D2 every stage pane is PURE: the shell hands it the draft and owns every write, so all
 * persistence for this surface is in this one class.
 */
@Component({
    selector: 'app-onboarding-shell',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    providers: [DefinitionStateService, OnboardingStateService],
    imports: [
        OnboardingCollectionPaneComponent,
        OnboardingParsingPaneComponent,
        OnboardingSchemaMappingPaneComponent,
        OnboardingEnrichmentPaneComponent,
        OnboardingPublishPaneComponent,
        OnboardingPlaceholderPaneComponent,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoBreadcrumbComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './onboarding-shell.component.html',
})
export class OnboardingShellComponent {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private confirm = inject(InspectoConfirmService);
    private configApi = inject(ConfigService);
    private components = inject(ComponentsService);
    private datasets = inject(DatasetRegistrationService);
    private toastr = inject(ToastrService);
    private destroyRef = inject(DestroyRef);
    private transfer = inject(StreamTransferService);

    readonly exporting = signal(false);

    /** Unsaved changes in the active stage pane — every pane is pure (D2) and reports it as an
     *  output, so this signal is the whole contract. */
    readonly paneDirty = signal(false);
    /** A pane-initiated save is in flight — the pure panes are told, they no longer own it. */
    readonly savingPane = signal(false);

    /** The `:stage` URL param (null = land on the first incomplete stage once loaded). */
    private readonly stageParam = signal<string | null>(null);
    /** Landing happens ONCE per opened draft — a stage save must never yank the user elsewhere. */
    private landed = false;

    readonly activeStage = computed<OnboardingStage>(() => {
        const stages = this.state.stages();
        return stages.find((s) => s.id === this.state.activeStageId()) ?? stages[0];
    });

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((p) => {
            const name = p.get('name') ?? '';
            const stage = p.get('stage');
            this.stageParam.set(stage);
            if (name && name !== this.state.name()) {
                this.landed = false;
                this.state.load(name);
            }
            if (stage && this.isStage(stage)) {
                // A pure pane is destroyed on a stage switch and never emits a closing `false`,
                // so a stale `true` here would make the NEXT stage refuse to leave.
                if (stage !== this.state.activeStageId()) this.paneDirty.set(false);
                this.state.activeStageId.set(stage);
            }
        });
        // No :stage in the URL → land on the first incomplete stage ONCE the draft has loaded
        // (once only — later config saves must not re-run the landing and move the user).
        effect(() => {
            const cfg = this.state.config();
            if (!cfg || this.stageParam() || this.landed) return;
            this.landed = true;
            this.state.activeStageId.set(this.state.firstOpenStage());
        });
    }

    private isStage(id: string): id is OnboardingStageId {
        return this.state.stages().some((s) => s.id === id);
    }

    /** Persist a stage-owned block. The pane emits it; the host owns the write (D2). */
    private saveStage(patch: Record<string, unknown>, toast: string): void {
        this.savingPane.set(true);
        this.state.saveBlock(patch).subscribe({
            next: () => {
                this.savingPane.set(false);
                this.toastr.success(toast);
            },
            error: () => this.savingPane.set(false),
        });
    }

    saveCollector(collector: Record<string, unknown>): void {
        this.saveStage({ collector }, 'Collection saved');
    }

    saveParsing(parsing: Record<string, unknown>): void {
        this.saveStage({ parsing }, 'Parsing saved');
    }

    /** The Schema stage writes its own `<name>_schema` toon, then hands back the block naming it. */
    saveSchemaFile(processing: Record<string, unknown>): void {
        this.saveStage({ processing }, 'Schema saved');
    }

    saveOutput(output: Record<string, unknown>): void {
        this.saveStage({ output }, 'Output saved');
    }

    /**
     * Go live / take offline. Activation is nothing more than the `active` flag — the running
     * service re-reads it every poll cycle — so this is the same stage write as every other, plus
     * the Dataset registration that going live implies.
     */
    setActive(active: boolean): void {
        this.savingPane.set(true);
        this.state.saveBlock({ active }).subscribe({
            next: () => {
                this.savingPane.set(false);
                this.toastr.success(`"${this.state.name()}" is ${active ? 'live' : 'offline'}`);
                if (active) this.ensureDataset();
            },
            error: () => this.savingPane.set(false),
        });
    }

    /** Going live also registers the Dataset over the stream's store (physicalRef = the normalized
     *  pipeline name), so the Stream→Dataset hop is no longer manual — the hop itself now lives in
     *  the shared {@link DatasetRegistrationService} (P6-b), because the Pipelines editor's toolbar
     *  activation performs the identical one and this shell is deleted in P6-e. Streams only: a
     *  Reference's store is consumed by name in enrichments (and its upsert/SCD2 layouts carry
     *  system columns), not queried as a raw Dataset — that call stays HERE, since each host reads
     *  stream-vs-reference from a different place. */
    private ensureDataset(): void {
        if (this.state.kind() !== 'stream') return;
        const store = this.state.normalizedName();
        // The display label lives in the config (identity/display split); the route-set signal is
        // the fallback — the same precedence normalizedName() itself uses.
        const display = String((this.state.config() ?? {})['name'] ?? this.state.name());
        this.datasets.ensure(store, display).subscribe((res) => {
            if (res.status === 'created')
                this.toastr.success(`Dataset "${store}" registered — queryable under Catalog ▸ Datasets`);
            else if (res.status === 'failed')
                this.toastr.warning(
                    `The stream is live, but its Dataset could not be registered — ${datasetManualHint(store)}`,
                );
        });
    }

    /**
     * Persist the Enrichment stage's companion config. Two hops, always both: the write, then the
     * REGISTER — enrichments do not hot-reload by mtime, so an unregistered save would look applied
     * and do nothing until the service restarts. A failed registration is a warning, not a failure:
     * the config IS on disk, so the draft advances either way, which is also what returns the pane
     * to pristine (it recognises the object it emitted).
     */
    saveEnrichment(draft: Record<string, unknown>): void {
        this.savingPane.set(true);
        this.configApi.write('enrichment', draft, { overwrite: true }).subscribe({
            next: (written) => {
                this.configApi.registerEnrichment(written.path).subscribe({
                    next: () => {
                        this.savingPane.set(false);
                        this.state.enrichmentConfig.set(draft);
                        this.toastr.success('Enrichment saved — runs after every committed batch');
                    },
                    error: (e) => {
                        this.savingPane.set(false);
                        this.state.enrichmentConfig.set(draft);
                        this.toastr.warning(
                            apiErrorMessage(
                                e,
                                'Saved, but registering failed — it will load on the next service restart.',
                            ),
                        );
                    },
                });
            },
            error: (e) => {
                this.savingPane.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the enrichment.'));
            },
        });
    }

    /** A pane asking to move the operator on (the Schema stage's "Go to Parsing"). Unguarded on
     *  purpose: the pane only offers it when it has nothing to lose (no parsed sample to work from). */
    goToStage(stage: OnboardingStageId): void {
        this.router.navigate(['/catalog', 'onboard', this.state.name(), stage]);
    }

    /** Rail click: guarded by the active pane's unsaved changes; the URL is the source of truth. */
    async select(stage: OnboardingStage): Promise<void> {
        if (stage.id === this.state.activeStageId()) return;
        if (this.paneDirty()) {
            const ok = await this.confirm.confirm(
                'This stage has unsaved changes — switch anyway and discard them?',
                'Unsaved changes',
            );
            if (!ok) return;
        }
        this.router.navigate(['/catalog', 'onboard', this.state.name(), stage.id]);
    }

    /** Route CanDeactivate — same guard when leaving the shell entirely. */
    canLeave(): Promise<boolean> | boolean {
        if (!this.paneDirty()) return true;
        return this.confirm.confirm('Leave onboarding and discard the unsaved stage changes?', 'Unsaved changes');
    }

    /** Open this data origin in the Catalog's Lineage graph. The origin node id is the engine's
     *  normalized pipeline id under the kind's token (`stream:` / `ref:`); the graph lifts draft
     *  pipelines too, so it works before go-live (just with few neighbours). Read-only — every lens. */
    viewAsGraph(): void {
        const id = (this.state.kind() === 'reference' ? 'ref:' : 'stream:') + this.state.normalizedName();
        this.router.navigate(['/catalog'], { queryParams: { tab: 'graph', from: id } });
    }

    /**
     * Download this Stream's whole configuration as a portable JSON file — the pipeline body plus
     * its schema, any per-segment plugin schemas and the enrichment companion. Read-only, so it is
     * available in EVERY lens (a Business-lens operator handing a config to support is the point);
     * the import side is what needs authoring rights.
     *
     * <p><b>An in-flight draft cannot be exported</b> (2026-08-01). What travels is the SERVER-held
     * config — `state.config()` only advances on a stage save — and the satellites are read back off
     * the server too. So exporting with unsaved stage edits on screen silently produced a file of the
     * LAST SAVED state: it looked like an export of what the operator was looking at, and was not.
     * Refusing beats a file that quietly disagrees with the screen; save the stage, then export.
     */
    exportConfig(): void {
        const config = this.state.config();
        if (!config || this.exporting()) return;
        if (this.paneDirty()) {
            this.toastr.warning('Save this stage before exporting — an export carries the saved configuration.');
            return;
        }
        this.exporting.set(true);
        this.transfer.buildExport(this.state.name(), this.state.kind(), config).subscribe({
            next: ({ bundle, missing }) => {
                this.exporting.set(false);
                this.transfer.download(bundle);
                // A satellite that could not be read is named, not swallowed — the file downloaded,
                // but it is incomplete and re-importing it would silently lose that piece.
                if (missing.length) {
                    this.toastr.warning(`Exported without ${missing.join(', ')} — could not be read.`);
                } else {
                    this.toastr.success(`Exported "${this.state.name()}" configuration`);
                }
            },
            error: (e) => {
                this.exporting.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not export the configuration.'));
            },
        });
    }

    /**
     * Discard, after telling the operator what it would break. The impact read is advisory — the
     * server re-checks and 409s on its own — so a failed read still lets the discard proceed and be
     * refused there. Confirming with dependents present is what sends `force`: the operator has been
     * shown the list and chosen anyway.
     */
    async discard(): Promise<void> {
        if (!this.lens.canAuthorWorkbench() || this.state.active()) return;
        const name = this.state.name();
        const impact = await firstValueFrom(this.state.draftImpact());
        const breaks = impact?.total ?? 0;
        const ok = await this.confirm.confirmDestructive(
            breaks === 0
                ? `Delete the draft "${name}" and its config file? This cannot be undone.`
                : `"${name}" is still referenced by ${breaks} config${breaks === 1 ? '' : 's'}: ` +
                      `${describeDependents(impact!)}. Deleting it leaves ${breaks === 1 ? 'that reference' : 'those references'} ` +
                      `pointing at nothing. Delete anyway? This cannot be undone.`,
            { title: 'Discard draft', confirmText: 'Discard draft' },
        );
        if (!ok) return;
        this.state.discardDraft(breaks > 0).subscribe({
            next: () => {
                this.toastr.success(`Draft "${name}" discarded`);
                this.router.navigate(['/catalog']);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not discard the draft.')),
        });
    }
}
