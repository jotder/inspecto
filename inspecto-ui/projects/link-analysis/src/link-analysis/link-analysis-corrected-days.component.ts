import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { DataChange, InvService } from '@inspecto/link-analysis/api/inv.service';

/** What each anomaly kind means to an analyst, in the vocabulary of the Collector's delivery check. */
export const KIND_TEXT: Record<DataChange['kind'], string> = {
    REDELIVERED: 'a File was delivered again',
    RENAMED_CORRECTION: 'a corrected File arrived under a new name',
    PART_COUNT_CHANGED: 'a further File arrived for a day that had looked complete',
    DUPLICATE_SKIPPED: 'a File changed after it was processed and was skipped',
};

/**
 * **Corrected-day warning** (LA-DATA-TRUST-1 S5): "day D was corrected after you last looked", from
 * `GET /inv/investigations/{id}/data-changes`, with a refresh prompt. Renders nothing while there is nothing to say
 * (no change, not assessed, or a failed read — a missing warning must never read as an error on the analyst's screen).
 *
 * **Mount** (the Data strip, or the Investigation header):
 * `<inspecto-link-analysis-corrected-days [investigationId]="id" [reloadToken]="head()" (refresh)="reload()" />`.
 * The host re-reads by changing `reloadToken` (any value, e.g. the head step) and handles `(refresh)` by re-running
 * its read; once the analyst acts the server's last-look moves forward and the warning clears on the next read.
 */
@Component({
    selector: 'inspecto-link-analysis-corrected-days',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent],
    host: { class: 'block' },
    template: `
        @if (changes().length) {
            <inspecto-alert variant="warning" title="Data corrected" data-test="corrected-days">
                <ul class="m-0 list-none p-0">
                    @for (c of changes(); track c.day + c.at + c.file) {
                        <li data-test="corrected-day">
                            Day {{ c.day }} was corrected after you last looked — {{ kindText(c) }}.
                        </li>
                    }
                </ul>
                <button
                    mat-stroked-button
                    type="button"
                    class="mt-2"
                    data-test="corrected-refresh"
                    (click)="refresh.emit()"
                >
                    Refresh this Investigation's data
                </button>
            </inspecto-alert>
        }
    `,
})
export class LinkAnalysisCorrectedDaysComponent {
    private readonly inv = inject(InvService);
    private readonly destroyRef = inject(DestroyRef);

    readonly investigationId = input.required<string>();
    /** Change it to re-read (the host's head step, or a counter bumped after a refresh). */
    readonly reloadToken = input<unknown>(null);
    /** The analyst asked to refresh: the host re-runs its read. */
    readonly refresh = output<void>();
    /** Every correction the read listed (empty when none, not assessed, or the read failed) - the Data strip counts them. */
    readonly changesRead = output<DataChange[]>();

    readonly changes = signal<DataChange[]>([]);

    constructor() {
        effect(() => {
            const id = this.investigationId();
            this.reloadToken();
            this.inv
                .investigationDataChanges(id)
                .pipe(takeUntilDestroyed(this.destroyRef))
                .subscribe({
                    next: (r) => {
                        const all = r.assessed ? r.changes : [];
                        this.changes.set(this.oneRowPerDay(all));
                        this.changesRead.emit(all);
                    },
                    error: () => {
                        this.changes.set([]);
                        this.changesRead.emit([]);
                    },
                });
        });
    }

    kindText(c: DataChange): string {
        return KIND_TEXT[c.kind] ?? 'its data moved';
    }

    /** A day corrected twice is one warning: keep the newest anomaly of each day. */
    private oneRowPerDay(all: DataChange[]): DataChange[] {
        const byDay = new Map<string, DataChange>();
        for (const c of all) byDay.set(c.day, c);
        return [...byDay.values()].sort((a, b) => a.day.localeCompare(b.day));
    }
}
