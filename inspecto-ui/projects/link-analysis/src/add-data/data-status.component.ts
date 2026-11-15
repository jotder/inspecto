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
import { Arrivals, dataStatusLines } from './add-data.model';
import { OnboardService } from './onboard.service';
import { GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
import { datasetIndexStatus } from '@inspecto/link-analysis/link-analysis/index-status';

/**
 * A Dataset's **Data status** (LA-ARRIVAL-LEDGER-1, plan `la-data-arrival-plan.md` §4): *Received · covers · graph*, then
 * any further line `dataStatusLines` adds. One component wherever a Dataset is named in passing - the Link Analysis Start
 * box and the Rows dock - so the words are the same everywhere. (The Dataset card says the same facts under their own
 * labels, `datasetFacts`, LA-STREAM-FACTS-1.)
 *
 * Pass `[arrivals]` when the host already read `GET /datasets/{id}/arrivals`;
 * otherwise it reads them for `[dataset]` itself. With a `[dataset]` the *graph* clock is the link index's live status
 * (`GET /inv/index`, LA-FULL-CYCLE-TEST-1 phase 4) whenever the server gives one - the answer the Data page and the
 * Investigation's data strip read too. A Dataset no import made has no arrivals and shows nothing; so does a
 * failed read (the status is a help, never a blocker).
 *
 * @example <inspecto-la-data-status [dataset]="datasetId" />
 */
@Component({
    selector: 'inspecto-la-data-status',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block' },
    template: `
        @if (lines().length) {
            <div class="flex flex-col gap-0.5 text-sm" data-testid="data-status">
                @for (l of lines(); track $index) {
                    <p class="m-0">
                        @if ($first) {
                            <span class="font-semibold">Data status:</span>
                        }
                        {{ l }}
                    </p>
                }
            </div>
        }
    `,
})
export class DataStatusComponent {
    private readonly onboard = inject(OnboardService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly graphRuns = inject(GraphRunsService);

    /** The Dataset whose status to read; ignored when {@link arrivals} is given. */
    readonly dataset = input<string | null>(null);
    /** The arrivals the host already read; `undefined` = read them here. */
    readonly arrivals = input<Arrivals | null | undefined>(undefined);

    private readonly own = signal<Arrivals | null>(null);
    protected readonly lines = computed(() => {
        const given = this.arrivals();
        const id = this.dataset();
        return dataStatusLines(
            given === undefined ? this.own() : given,
            id ? datasetIndexStatus(this.graphRuns.indexes(), id) : null,
        );
    });
    private reading?: Subscription;

    constructor() {
        effect(() => {
            const id = this.arrivals() === undefined ? this.dataset() : null;
            untracked(() => this.read(id));
        });
        effect(() => {
            if (this.dataset()) untracked(() => this.graphRuns.loadIndexes()); // read once per page; a no-op once answered
        });
    }

    /** Read the arrivals for `id`; a read still in flight is dropped, so an older answer never overwrites a newer one. */
    private read(id: string | null): void {
        this.reading?.unsubscribe();
        this.own.set(null);
        if (!id) return;
        this.reading = this.onboard
            .arrivals(id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({ next: (r) => this.own.set(r), error: () => this.own.set(null) });
    }
}
