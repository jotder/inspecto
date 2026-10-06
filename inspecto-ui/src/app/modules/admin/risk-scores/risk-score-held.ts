import { ChangeDetectionStrategy, Component, Injectable, OnInit, inject, input, signal } from '@angular/core';
import { PendingChange, PendingChangesService } from 'app/inspecto/api/pending-changes.service';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

/**
 * The `risk-score` changes awaiting approval (slice S2b, spec §4 "Held change"), by model id. One read of
 * `GET /pending-changes?status=pending` for the pane; a failure (no maker-checker module, no capability) reads as
 * "nothing held" — the badge is a hint, never a gate.
 */
@Injectable({ providedIn: 'root' })
export class RiskScoreHeldStore {
    private api = inject(PendingChangesService);
    readonly byModel = signal<Record<string, PendingChange>>({});

    refresh(): void {
        this.api.list('pending').subscribe({
            next: (r) =>
                this.byModel.set(
                    Object.fromEntries(r.items.filter((p) => p.kind === 'risk-score').map((p) => [p.name, p])),
                ),
            error: () => this.byModel.set({}),
        });
    }
}

/** "Awaiting approval" on a model with a held change (non-interactive: it sits inside the list row button). */
@Component({
    selector: 'app-risk-score-held-badge',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [StatusBadgeComponent],
    template: `
        @if (store.byModel()[model()]; as pc) {
            <span class="inline-flex" [title]="pc.operation + ' held as ' + pc.id + ' (see Pending Changes)'">
                <inspecto-status-badge value="pending" label="Awaiting approval"></inspecto-status-badge>
            </span>
        }
    `,
})
export class RiskScoreHeldBadgeComponent implements OnInit {
    readonly store = inject(RiskScoreHeldStore);
    readonly model = input.required<string>();
    /** Only the first badge on the page triggers the read. */
    readonly loads = input(false);

    ngOnInit(): void {
        if (this.loads()) this.store.refresh();
    }
}
