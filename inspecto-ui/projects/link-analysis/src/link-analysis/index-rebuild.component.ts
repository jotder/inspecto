import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { LensService } from '@inspecto/core/api';
import { GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
import { datasetIndexStatus } from './index-status';
import { indexBuildErrorMessage } from './link-analysis-index-build.component';

/** The permission a Rebuild needs, named as the Access page names it (G14). */
const NEEDS = 'Needs Build link index.';

/**
 * LA-FULL-CYCLE-TEST-1 phase 4 - **Rebuild** for a Dataset whose link index is out of date, beside the words that say so
 * (the Investigation's data strip, the Data page's Graph fact). Renders nothing while the index is current, not listed, or
 * `GET /inv/index` cannot answer (Basic). The build is the index's own mapping in the mode the server advises
 * (`rebuildRequest`), on `POST /inv/index/builds`; a caller without `canBuildLinkIndex` sees the button disabled with the
 * permission it needs. When the build completes the index list is read again, so every surface that reads it agrees at once.
 *
 * @example <inspecto-la-index-rebuild [dataset]="datasetId" />
 */
@Component({
    selector: 'inspecto-la-index-rebuild',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatTooltipModule],
    host: { class: 'contents' },
    template: `
        @if (outOfDate() || busy()) {
            <span class="inline-flex flex-wrap items-center gap-2" data-testid="index-rebuild">
                <span [matTooltip]="allowed() ? '' : needs">
                    <button
                        mat-stroked-button
                        type="button"
                        data-testid="index-rebuild-button"
                        [disabled]="!allowed() || busy()"
                        [attr.aria-label]="label()"
                        (click)="rebuild()"
                    >
                        {{ busy() ? 'Rebuilding' : 'Rebuild' }}
                    </button>
                </span>
                @if (error()) {
                    <span class="text-warn text-xs" role="alert" data-testid="index-rebuild-error">{{ error() }}</span>
                }
            </span>
        }
        <!-- the end of a rebuild, for a screen reader: the button itself goes away with the out-of-date state -->
        <span class="sr-only" role="status" data-testid="index-rebuild-status">{{ announcement() }}</span>
    `,
})
export class IndexRebuildComponent {
    private readonly runs = inject(GraphRunsService);
    private readonly lens = inject(LensService);
    private sub: Subscription | null = null;

    /** The Dataset whose link index this rebuilds. */
    readonly dataset = input.required<string>();

    protected readonly needs = NEEDS;
    protected readonly busy = signal(false);
    protected readonly error = signal('');
    protected readonly announcement = signal('');
    private readonly status = computed(() => datasetIndexStatus(this.runs.indexes(), this.dataset()));
    protected readonly outOfDate = computed(() => this.status()?.state === 'out_of_date');
    protected readonly allowed = computed(() => this.lens.canBuildLinkIndex());
    /** The name starts with the words on the button (WCAG 2.5.3 label in name), then says which index. */
    protected readonly label = computed(() =>
        this.busy()
            ? `Rebuilding the link index of ${this.dataset()}`
            : this.allowed()
              ? `Rebuild the link index of ${this.dataset()}`
              : `Rebuild. ${NEEDS}`,
    );

    constructor() {
        inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
        this.runs.loadIndexes();
    }

    rebuild(): void {
        const req = this.status()?.rebuild;
        if (!req || !this.allowed() || this.busy()) return;
        this.busy.set(true);
        this.error.set('');
        this.announcement.set('');
        this.sub?.unsubscribe();
        this.sub = this.runs.startBuild(req).subscribe({
            next: (first) => {
                this.sub = this.runs.watchBuild(first.buildId).subscribe({
                    next: (v) => {
                        if (v.status === 'COMPLETED') {
                            this.busy.set(false);
                            this.announcement.set(`The link index of ${this.dataset()} is rebuilt.`);
                            this.runs.reloadIndexes();
                        } else if (v.status === 'FAILED' || v.status === 'CANCELLED') {
                            this.busy.set(false);
                            this.error.set(
                                v.status === 'FAILED'
                                    ? 'The rebuild failed. Try again, or ask an administrator.'
                                    : 'The rebuild was cancelled.',
                            );
                        }
                    },
                    error: () => {
                        this.busy.set(false);
                        this.error.set('The rebuild could not be read back from the server.');
                    },
                });
            },
            error: (err) => {
                this.busy.set(false);
                this.error.set(indexBuildErrorMessage(err));
            },
        });
    }
}
