import { ChangeDetectionStrategy, Component, OnDestroy, ViewChild, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { ComponentsService, InboxStatus, LensService, RunsService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { clearMissingRoots, flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { OnboardingStateService } from './onboarding-state.service';
import { stageAttributesFor } from './stage-attributes';

/**
 * Dataset & Go-live stage — authors the Stage-1 `output:` block, then flips `active: true` once
 * every required stage is configured. Activation is nothing more than that flag: the running
 * service re-reads `active` every poll cycle (no dedicated route), so "going live" here is the
 * same `saveBlock` every other stage uses. Once live, a lightweight activity glance (inbox
 * pending/running, via the existing Runs API) proves the pipeline is actually doing something,
 * with a link to the full Runs page rather than duplicating it.
 *
 * Going live on a Stream also registers the queryable Dataset over its store (onboarding↔pipeline
 * split S1, `docs/superpower/onboarding-pipeline-split.md`) — see {@link ensureDataset}.
 */
@Component({
    selector: 'app-onboarding-publish-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        RouterLink,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
    ],
    templateUrl: './publish-pane.component.html',
})
export class OnboardingPublishPaneComponent implements OnDestroy {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private runsApi = inject(RunsService);
    private components = inject(ComponentsService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;

    /** The shared output table (identical to the Pipelines sink node's) — one table per concern. */
    readonly attributes = stageAttributesFor('publish')!;
    /** A brand-new draft SUGGESTS Parquet (the better landing format) via `initial`; the shared
     *  table's own default stays the engine truth (CSV), so the Pipelines sink dialog never
     *  authors a format the operator did not pick. A resumed block renders verbatim. */
    readonly initial = (() => {
        const output = this.state.block('output');
        return output ? flattenBlock(output) : { format: 'PARQUET' };
    })();

    readonly saving = signal(false);
    readonly activating = signal(false);
    readonly refreshing = signal(false);
    readonly activity = signal<InboxStatus | null>(null);
    readonly activityError = signal<string | null>(null);

    /** The OTHER required stages still empty (mirrors `lifecycle()`'s own `id !== 'publish'`
     *  exclusion) — named so a blocked go-live is never a silent dead end. `lifecycle() ===
     *  'Ready'` is the actual go-live gate; when these are all done but it's still not Ready,
     *  the only thing left is this stage's own unsaved output block. */
    readonly blockedOn = computed(() =>
        this.state
            .stages()
            .filter((s) => !s.optional && s.id !== 'publish' && this.state.stageStatus()[s.id] === 'empty')
            .map((s) => s.label),
    );

    private readonly dirtyCheck = (): boolean => this.schemaForm?.isDirty() ?? false;

    constructor() {
        this.state.registerDirtyCheck(this.dirtyCheck);
        if (this.state.active()) this.refreshActivity();
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        if (!this.schemaForm.validate()) return;
        const output = clearMissingRoots(nestKeys(this.schemaForm.value()), ['format', 'compression']);
        this.saving.set(true);
        this.state.saveBlock({ output }).subscribe({
            next: () => {
                this.saving.set(false);
                this.schemaForm.form.markAsPristine();
                this.toastr.success('Output saved');
            },
            error: () => this.saving.set(false),
        });
    }

    async activate(): Promise<void> {
        if (!this.lens.canAuthorWorkbench() || this.state.active() || this.state.lifecycle() !== 'Ready') return;
        const name = this.state.name();
        const ok = await this.confirm.confirm(
            `"${name}" will start collecting from its inbox on the next poll cycle. Continue?`,
            'Go live',
        );
        if (!ok) return;
        this.activating.set(true);
        this.state.saveBlock({ active: true }).subscribe({
            next: () => {
                this.activating.set(false);
                this.toastr.success(`"${name}" is live`);
                this.ensureDataset();
                this.refreshActivity();
            },
            error: () => this.activating.set(false),
        });
    }

    /** Going live also registers the Dataset over the stream's store (physicalRef = the normalized
     *  pipeline name), so the Stream→Dataset hop is no longer manual. Idempotent — a dataset
     *  already pointing at the store wins, whatever its id. Any failure downgrades to a warning:
     *  activation has already succeeded, and the Dataset can always be authored by hand. Streams
     *  only — a Reference's store is consumed by name in enrichments (and its upsert/SCD2 layouts
     *  carry system columns), not queried as a raw Dataset. */
    private ensureDataset(): void {
        if (this.state.kind() !== 'stream') return;
        const store = this.state.normalizedName();
        // The display label lives in the config (identity/display split); the route-set signal is
        // the fallback — the same precedence normalizedName() itself uses.
        const display = String((this.state.config() ?? {})['name'] ?? this.state.name());
        const manualHint = `create it under Catalog ▸ Datasets with physical reference "${store}".`;
        this.components.list('dataset').subscribe({
            next: (defs) => {
                if (defs.some((d) => d.content['physicalRef'] === store)) return; // already registered
                this.components
                    .create('dataset', {
                        id: store,
                        name: display,
                        kind: 'physical',
                        physicalRef: store,
                        description: `Landed data of stream "${display}" — registered at go-live.`,
                    })
                    .subscribe({
                        next: () =>
                            this.toastr.success(`Dataset "${store}" registered — queryable under Catalog ▸ Datasets`),
                        error: () =>
                            this.toastr.warning(
                                `The stream is live, but its Dataset could not be registered — ${manualHint}`,
                            ),
                    });
            },
            error: () =>
                this.toastr.warning(`The stream is live, but the Dataset registry could not be read — ${manualHint}`),
        });
    }

    refreshActivity(): void {
        this.refreshing.set(true);
        this.activityError.set(null);
        this.runsApi.pending(this.state.name()).subscribe({
            next: (s) => {
                this.refreshing.set(false);
                this.activity.set(s);
            },
            error: (e) => {
                this.refreshing.set(false);
                this.activityError.set(apiErrorMessage(e, 'Could not read run activity.'));
            },
        });
    }
}
