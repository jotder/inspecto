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
import { OnboardService } from './onboard.service';
import { plural } from './words';

/** `GET /datasets/{id}/coverage` (LA-REFERENCE-DETAILS-MORE-1): counts only, never a key or a value. */
export interface ReferenceCoverage {
    dataset: string;
    entityType: string | null;
    entityTypeLabel?: string;
    identifyingColumn: string | null;
    /** Distinct keys of the identifying column; null when not a Reference or not readable (`reason`). */
    described: number | null;
    /** Each other Dataset holding the Entity Type: how many of the described keys appear in it, and how many do not. */
    datasets: { dataset: string; columns: string[]; appear: number; absent: number }[];
    /** A row or Dataset cap bit: the counts cover part of the data. */
    truncated: boolean;
    reason?: string;
}

/**
 * The coverage in plain words (UC-04 H9): "40 Account entities described." then, per Dataset holding that Entity Type,
 * "20 appear in transfers; 20 do not." - in the singular for one ("137 appear in transfers; 1 does not."). Nothing when
 * there are no counts. Pure.
 */
export function coverageLines(c: ReferenceCoverage | null): string[] {
    if (!c || c.described === null) return [];
    const type = c.entityTypeLabel || c.entityType;
    const described = `${plural(c.described, `${type} entity`, `${type} entities`)} described`;
    const cut = c.truncated ? ' (counted over part of the rows)' : '';
    if (!c.datasets.length) return [`${described}; no other Dataset holds ${type} entities yet${cut}.`];
    return [
        `${described}${cut}.`,
        ...c.datasets.map(
            (d) => `${plural(d.appear, 'appears', 'appear')} in ${d.dataset}; ${plural(d.absent, 'does', 'do')} not.`,
        ),
    ];
}

/**
 * **How much of a Reference the other data uses** (LA-REFERENCE-DETAILS-MORE-1, UC-04 H9), on a Reference's Dataset card
 * (Add data, the console Dataset page and the Link Analysis Data page share it). Reads `GET /datasets/{id}/coverage` again
 * whenever `[refresh]` changes (the card passes its arrivals, re-read after every run). A failed read shows nothing (a
 * help, never a blocker).
 *
 * @example <inspecto-la-reference-coverage [dataset]="id" [refresh]="arrivals" />
 */
@Component({
    selector: 'inspecto-la-reference-coverage',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block' },
    template: `
        @if (lines().length) {
            <div class="flex flex-col gap-0.5 text-sm" data-testid="reference-coverage">
                @for (l of lines(); track $index) {
                    <p class="m-0">{{ l }}</p>
                }
            </div>
        }
    `,
})
export class ReferenceCoverageComponent {
    private readonly onboard = inject(OnboardService);
    private readonly destroyRef = inject(DestroyRef);

    readonly dataset = input.required<string>();
    /** Any value whose change means the data may have changed (re-read). */
    readonly refresh = input<unknown>(null);

    private readonly answer = signal<ReferenceCoverage | null>(null);
    protected readonly lines = computed(() => coverageLines(this.answer()));
    private read?: Subscription;

    constructor() {
        effect(() => {
            const id = this.dataset();
            this.refresh();
            untracked(() => this.load(id));
        });
    }

    private load(id: string): void {
        this.read?.unsubscribe(); // an older answer never overwrites a newer one
        this.read = this.onboard
            .coverage(id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({ next: (c) => this.answer.set(c), error: () => this.answer.set(null) });
    }
}
