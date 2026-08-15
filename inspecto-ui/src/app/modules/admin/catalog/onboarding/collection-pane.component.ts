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
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { LensService } from 'app/inspecto/api';
import { CollectorConfigComponent } from 'app/inspecto/collector/collector-config.component';
import { KEY_SEP, clearMissingRoots, flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { stageAttributesFor } from './stage-attributes';

/**
 * Collection stage — authors the Stage-1 `collector:` block. A thin host over the shared
 * {@link CollectorConfigComponent}: that component owns the whole where-do-files-come-from surface
 * (mode toggle, schema form, Test connection, create-in-place, the derived connector).
 *
 * Pure (definition-surface unification D2): it reads its value from `collector` and emits the block
 * to persist on `applied` — it does NOT save. The host owns persistence, which is what lets the same
 * component serve the wizard today and the pipeline editor's drawer from P3a, whose save models
 * differ (immediate `POST /config/patch` vs. deferred whole-graph `PUT`).
 */
@Component({
    selector: 'app-onboarding-collection-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, CollectorConfigComponent],
    template: `
        <div class="flex max-w-3xl flex-col gap-4">
            <p class="text-secondary m-0">
                Where this {{ kind() }}'s files come from — the pipeline's local inbox folder, or a saved Connection. A
                Connection carries its own connector type, so it is never asked twice.
            </p>

            <inspecto-collector-config
                [specs]="stageSpecs"
                [initial]="initial()"
                [storedConnector]="storedConnector()"
                (submitted)="submit()"
            />

            <div class="flex items-center gap-3">
                <button
                    mat-flat-button
                    color="primary"
                    [disabled]="saving() || !lens.canAuthorWorkbench()"
                    (click)="submit()"
                >
                    Save collection
                </button>
                @if (!lens.canAuthorWorkbench()) {
                    <span class="text-secondary text-sm">Your lens is read-only.</span>
                }
            </div>
        </div>
    `,
})
export class OnboardingCollectionPaneComponent {
    protected readonly lens = inject(LensService);

    /** Prose only — a Stream's "files" and a Reference's "dumps" read differently. */
    readonly kind = input<'stream' | 'reference'>('stream');
    /** The `collector:` block as currently persisted, or null when not yet authored. */
    readonly collector = input<Record<string, unknown> | null>(null);
    /** Host-owned: a save is in flight. */
    readonly saving = input(false);

    /** The collector block to persist, connector already resolved. The host writes it. */
    readonly applied = output<Record<string, unknown>>();
    /** Whether the pane holds edits against the `collector` input it was last seeded with. */
    readonly dirtyChange = output<boolean>();

    /** Not private: the pane is a thin host, so its specs drive the shared child directly. */
    @ViewChild(CollectorConfigComponent) collectorRef?: CollectorConfigComponent;

    /** The shared collector table via the stage lookup (identical to the Pipelines acquisition node's). */
    readonly stageSpecs = stageAttributesFor('collection')!;

    readonly initial = computed(() => flattenBlock(this.collector() ?? {}));
    readonly storedConnector = computed(() => String((this.collector() ?? {})['connector'] ?? ''));

    private lastDirty = false;

    constructor() {
        /**
         * Re-seeding is how this pane returns to pristine — there is no host→pane method call.
         * A successful save updates the host's config, which hands back a NEW `collector` object and
         * re-runs this; a FAILED save leaves the object identical, so the pane correctly stays dirty
         * and the unsaved-changes guard still fires. (The child's `initial` setter re-seeds off the
         * computed bindings above; this only has to reset the dirty baseline.)
         */
        effect(() => {
            this.collector();
            this.collectorRef?.markPristine();
            this.emitDirty();
        });
    }

    /**
     * Dirty is derived on interaction, not streamed: the collector surface exposes `isDirty()` as a
     * method with no output, so re-derive after any input/click inside the pane and report only the
     * transitions — the same contract as the Pipelines collection drawer (P1).
     */
    @HostListener('input')
    @HostListener('click')
    onInteraction(): void {
        this.emitDirty();
    }

    private emitDirty(): void {
        const dirty = this.collectorRef?.isDirty() ?? false;
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    submit(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        if (!this.collectorRef || !this.collectorRef.validate()) return;
        const connector = this.collectorRef.resolveConnector();
        if (!connector) return;
        // Cleared fields delete their key (incl. `connection` when collecting locally); keys this
        // form never owned survive the host's deep merge.
        const roots = new Set(this.stageSpecs.map((a) => a.key.split(KEY_SEP)[0]));
        const collector = clearMissingRoots(nestKeys(this.collectorRef.value()), roots);
        collector['connector'] = connector;
        this.applied.emit(collector);
    }
}
