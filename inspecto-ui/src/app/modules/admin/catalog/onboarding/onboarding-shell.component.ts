import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, Type, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { firstValueFrom } from 'rxjs';
import { ConfigImpact, LensService, apiErrorMessage } from 'app/inspecto/api';
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
 * the stages (§4.3) — session-held in {@link OnboardingStateService}, but the capture UI lives in
 * the Parsing stage, the stage that actually consumes it.
 */
@Component({
    selector: 'app-onboarding-shell',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    providers: [DefinitionStateService, OnboardingStateService],
    imports: [
        NgComponentOutlet,
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
    private toastr = inject(ToastrService);
    private destroyRef = inject(DestroyRef);
    private transfer = inject(StreamTransferService);

    readonly exporting = signal(false);

    /** The `:stage` URL param (null = land on the first incomplete stage once loaded). */
    private readonly stageParam = signal<string | null>(null);
    /** Landing happens ONCE per opened draft — a stage save must never yank the user elsewhere. */
    private landed = false;

    readonly activeStage = computed<OnboardingStage>(() => {
        const stages = this.state.stages();
        return stages.find((s) => s.id === this.state.activeStageId()) ?? stages[0];
    });

    readonly activeComponent = computed<Type<unknown>>(() => {
        switch (this.activeStage().id) {
            case 'collection':
                return OnboardingCollectionPaneComponent;
            case 'parsing':
                return OnboardingParsingPaneComponent;
            case 'schema':
            case 'keys': // the Reference "Keys & Load" stage authors the same schema artifact
                return OnboardingSchemaMappingPaneComponent;
            case 'enrichment':
                return OnboardingEnrichmentPaneComponent;
            case 'publish':
                return OnboardingPublishPaneComponent;
            default:
                return OnboardingPlaceholderPaneComponent;
        }
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
            if (stage && this.isStage(stage)) this.state.activeStageId.set(stage);
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

    /** Rail click: guarded by the active pane's unsaved changes; the URL is the source of truth. */
    async select(stage: OnboardingStage): Promise<void> {
        if (stage.id === this.state.activeStageId()) return;
        if (this.state.isDirty()) {
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
        if (!this.state.isDirty()) return true;
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
        if (this.state.isDirty()) {
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
