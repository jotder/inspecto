import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    computed,
    effect,
    inject,
    input,
    signal,
    untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { Arrivals } from '@inspecto/link-analysis/add-data/add-data.model';
import { Dataset } from './dataset-types';
import { DatasetFacts, rowsWords, setAsideLines, spanWords, timeColumn } from './dataset-page';
import { DatasetsService } from './datasets.service';

/**
 * The console Dataset page's **summary** (ONBOARDING-FIRST-RUN-1 B4, C5): rows, time span, what was set aside and why
 * (DT-15), and the primary entity (A-7). Shown inside the shared import Dataset card; the card's own Data status, last
 * import and arrivals stay where they are. Rows and time span are counted over `POST /bi/query` and re-counted when
 * `version` changes (the host bumps it after every import).
 *
 * @example <app-dataset-summary cardSummary [dataset]="d" [arrivals]="a" [version]="n" />
 */
@Component({
    selector: 'app-dataset-summary',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block' },
    template: `
        <dl class="m-0 grid grid-cols-1 gap-x-6 gap-y-1 text-sm sm:grid-cols-2" data-testid="dataset-summary">
            <div class="flex gap-2">
                <dt class="text-secondary shrink-0">Rows</dt>
                <dd class="m-0 tabular-nums" data-testid="summary-rows">{{ rows() }}</dd>
            </div>
            <div class="flex gap-2">
                <dt class="text-secondary shrink-0">Time span</dt>
                <dd class="m-0 tabular-nums" data-testid="summary-span">{{ span() }}</dd>
            </div>
            <div class="flex gap-2">
                <dt class="text-secondary shrink-0">Primary entity</dt>
                <dd class="m-0" data-testid="summary-primary">{{ dataset().primaryEntityType || 'Not set' }}</dd>
            </div>
            <div class="flex gap-2 sm:col-span-2">
                <dt class="text-secondary shrink-0">Set aside</dt>
                <dd class="m-0" data-testid="summary-set-aside">
                    @for (l of setAside(); track l) {
                        <span class="block">{{ l }}</span>
                    } @empty {
                        Nothing
                    }
                </dd>
            </div>
        </dl>
    `,
})
export class DatasetSummaryComponent {
    private readonly api = inject(DatasetsService);
    private readonly destroyRef = inject(DestroyRef);

    readonly dataset = input.required<Dataset>();
    /** The card's last arrivals read (`(arrivalsLoaded)`); null while unread. */
    readonly arrivals = input<Arrivals | null>(null);
    /** Bumped by the host after an import, to count again. */
    readonly version = input(0);

    private readonly facts = signal<DatasetFacts | null>(null);
    private read?: Subscription;

    protected readonly rows = computed(() => rowsWords(this.facts()));
    protected readonly span = computed(() => spanWords(this.facts(), timeColumn(this.dataset()) !== null));
    protected readonly setAside = computed(() => setAsideLines(this.arrivals()));

    constructor() {
        effect(() => {
            const d = this.dataset();
            this.version();
            untracked(() => this.load(d));
        });
    }

    private load(d: Dataset): void {
        this.read?.unsubscribe();
        this.read = this.api
            .facts(d)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (f) => this.facts.set(f),
                error: () => this.facts.set(null), // "Not counted": the rest of the card stays usable
            });
    }
}
