import {
    ChangeDetectionStrategy,
    Component,
    HostListener,
    ViewChild,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InboxStatus, LensService, RunsService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { clearMissingRoots, flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { stageAttributesFor } from './stage-attributes';

/** Which host write this pane last asked for — the three buttons keep their own spinners while a
 *  single host-owned `saving` flag is in flight. */
type PublishAction = 'output' | 'activate' | 'deactivate';

/**
 * Dataset & Go-live stage — authors the Stage-1 `output:` block, then flips `active: true` once
 * every required stage is configured. Activation is nothing more than that flag: the running
 * service re-reads `active` every poll cycle (no dedicated route), so "going live" here is the
 * same `saveBlock` every other stage uses. Once live, a lightweight activity glance (inbox
 * pending/running, via the existing Runs API) proves the pipeline is actually doing something,
 * with a link to the full Runs page rather than duplicating it.
 *
 * Going live on a Stream also registers the queryable Dataset over its store (onboarding↔pipeline
 * split S1, `docs/superpower/onboarding-pipeline-split.md`) — that now happens HOST-side, as part
 * of the activation write it follows.
 *
 * <p>Pure (definition-surface unification D2): the draft arrives on `config`, the stage-model facts
 * this pane only renders (`lifecycle`, `blockedOn`) arrive as inputs because the stage model is
 * host-side (D5), and the two writes leave as outputs — `applied` for the `output:` block,
 * `activeChange` for the go-live flag. The pane keeps its READS (the run-activity glance) and its
 * confirmations: asking before a consequential write is UI, not persistence.
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
export class OnboardingPublishPaneComponent {
    protected readonly lens = inject(LensService);
    private runsApi = inject(RunsService);
    private confirm = inject(InspectoConfirmService);

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;

    /** The server-held pipeline draft — this stage reads its `output:` block and `active` flag. */
    readonly config = input<Record<string, unknown> | null>(null);
    /** Prose only — a Reference is bound by name, a Stream becomes a queryable Dataset. */
    readonly kind = input<'stream' | 'reference'>('stream');
    /** The draft's name, as the route and the Runs API know it. */
    readonly name = input('');
    /** The engine's normalized pipeline id — what the registered Dataset points at. */
    readonly pipelineId = input('');
    /** Host-computed readiness (D5: the stage model is the host's, this pane only renders it). */
    readonly lifecycle = input<'Draft' | 'Ready' | 'Live'>('Draft');
    /** Required stages still empty — named so a blocked go-live is never a silent dead end. */
    readonly blockedOn = input<string[]>([]);
    /** Host-owned: one of this pane's writes is in flight. */
    readonly saving = input(false);

    /** The `output:` block to persist. The host writes it. */
    readonly applied = output<Record<string, unknown>>();
    /** Go live / take offline — the host writes the flag (and registers the Dataset on go-live). */
    readonly activeChange = output<boolean>();
    /** Whether the pane holds edits against the `config` input it was last seeded with. */
    readonly dirtyChange = output<boolean>();

    /** The shared output table (identical to the Pipelines sink node's) — one table per concern. */
    readonly attributes = stageAttributesFor('publish')!;
    /** A brand-new draft SUGGESTS Parquet (the better landing format) via `initial`; the shared
     *  table's own default stays the engine truth (CSV), so the Pipelines sink dialog never
     *  authors a format the operator did not pick. A resumed block renders verbatim. */
    readonly initial = computed(() => {
        const output = (this.config() ?? {})['output'];
        return output && typeof output === 'object' && !Array.isArray(output)
            ? flattenBlock(output as Record<string, unknown>)
            : { format: 'PARQUET' };
    });

    readonly active = computed(() => (this.config() ?? {})['active'] === true);

    /** Which write is in flight, so each button keeps its own spinner off one host-owned flag. */
    private readonly pending = signal<PublishAction | null>(null);
    readonly savingOutput = computed(() => this.saving() && this.pending() === 'output');
    readonly activating = computed(() => this.saving() && this.pending() === 'activate');
    readonly deactivating = computed(() => this.saving() && this.pending() === 'deactivate');

    readonly refreshing = signal(false);
    readonly activity = signal<InboxStatus | null>(null);
    readonly activityError = signal<string | null>(null);

    private lastDirty = false;
    private wasActive = false;

    constructor() {
        /**
         * Re-seeding is how this pane returns to pristine — a successful save advances the host's
         * config to a NEW object; a failed one leaves it identical, so the pane stays dirty and the
         * unsaved-changes guard still fires. The same signal doubles as "the write finished", which
         * is when the activity glance is worth (re-)reading.
         */
        effect(() => {
            this.config();
            this.schemaForm?.form.markAsPristine();
            this.pending.set(null);
            this.emitDirty();
            const live = this.active();
            if (live && !this.wasActive) this.refreshActivity();
            if (!live && this.wasActive) this.activity.set(null);
            this.wasActive = live;
        });
    }

    /**
     * Dirty is derived on interaction, not streamed — the same contract as every other migrated
     * pane (P2-2 … P2-5).
     */
    @HostListener('input')
    @HostListener('click')
    onInteraction(): void {
        this.emitDirty();
    }

    private emitDirty(): void {
        const dirty = this.schemaForm?.isDirty() ?? false;
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        if (!this.schemaForm.validate()) return;
        const output = clearMissingRoots(nestKeys(this.schemaForm.value()), ['format', 'compression']);
        this.pending.set('output');
        this.applied.emit(output);
    }

    async activate(): Promise<void> {
        if (!this.lens.canAuthorWorkbench() || this.active() || this.lifecycle() !== 'Ready') return;
        const ok = await this.confirm.confirm(
            `"${this.name()}" will start collecting from its inbox on the next poll cycle. Continue?`,
            'Go live',
        );
        if (!ok) return;
        this.pending.set('activate');
        this.activeChange.emit(true);
    }

    /** The inverse of {@link activate} — the same `active` flag, written false. Without it a live
     *  pipeline could never be deleted (`DELETE /config/pipeline` 409s while active) and every stage
     *  edit stayed hot. The registered Dataset is deliberately left in place: it describes data that
     *  was already landed, which taking the collector offline does not unwrite. */
    async deactivate(): Promise<void> {
        if (!this.lens.canAuthorWorkbench() || !this.active()) return;
        const ok = await this.confirm.confirm(
            `"${this.name()}" will stop collecting after the current poll cycle. Its landed data and Dataset are kept. Continue?`,
            'Take offline',
        );
        if (!ok) return;
        this.pending.set('deactivate');
        this.activeChange.emit(false);
    }

    refreshActivity(): void {
        this.refreshing.set(true);
        this.activityError.set(null);
        this.runsApi.pending(this.name()).subscribe({
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
