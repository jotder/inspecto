import { ChangeDetectionStrategy, Component, OnDestroy, ViewChild, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ToastrService } from 'ngx-toastr';
import { LensService } from 'app/inspecto/api';
import { CollectorConfigComponent } from 'app/inspecto/collector/collector-config.component';
import { KEY_SEP, clearMissingRoots, flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { OnboardingStateService } from './onboarding-state.service';
import { stageAttributesFor } from './stage-attributes';

/**
 * Collection stage — authors the Stage-1 `collector:` block. A thin host over the shared
 * {@link CollectorConfigComponent}: that component owns the whole where-do-files-come-from surface
 * (mode toggle, schema form, Test connection, create-in-place, the derived connector), the pane owns
 * persistence — `saveBlock` → `POST /config/patch`, the server-side merged block write.
 */
@Component({
    selector: 'app-onboarding-collection-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, CollectorConfigComponent],
    template: `
        <div class="flex max-w-3xl flex-col gap-4">
            <p class="text-secondary m-0">
                Where this {{ state.kind() }}'s files come from — the pipeline's local inbox folder, or a saved
                Connection. A Connection carries its own connector type, so it is never asked twice.
            </p>

            <inspecto-collector-config
                [specs]="stageSpecs"
                [initial]="initial"
                [storedConnector]="storedConnector"
                (submitted)="save()"
            />

            <div class="flex items-center gap-3">
                <button
                    mat-flat-button
                    color="primary"
                    [disabled]="saving() || !lens.canAuthorWorkbench()"
                    (click)="save()"
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
export class OnboardingCollectionPaneComponent implements OnDestroy {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private toastr = inject(ToastrService);

    @ViewChild(CollectorConfigComponent) collector!: CollectorConfigComponent;

    /** The shared collector table via the stage lookup (identical to the Pipelines acquisition node's). */
    readonly stageSpecs = stageAttributesFor('collection')!;

    private readonly collectorBlock = this.state.block('collector') ?? {};
    readonly storedConnector = String(this.collectorBlock['connector'] ?? '');
    readonly initial = flattenBlock(this.collectorBlock);

    readonly saving = signal(false);

    private readonly dirtyCheck = (): boolean => this.collector?.isDirty() ?? false;

    constructor() {
        this.state.registerDirtyCheck(this.dirtyCheck);
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        if (!this.collector.validate()) return;
        const connector = this.collector.resolveConnector();
        if (!connector) return;
        // Cleared fields delete their key (incl. `connection` when collecting locally); keys this
        // form never owned survive the deep merge.
        const roots = new Set(this.stageSpecs.map((a) => a.key.split(KEY_SEP)[0]));
        const collector = clearMissingRoots(nestKeys(this.collector.value()), roots);
        collector['connector'] = connector;
        this.saving.set(true);
        this.state.saveBlock({ collector }).subscribe({
            next: () => {
                this.saving.set(false);
                this.collector.markPristine();
                this.toastr.success('Collection saved');
            },
            error: () => this.saving.set(false),
        });
    }
}
