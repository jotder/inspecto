import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { ActionRequest, ActionRequestsService, apiErrorMessage, LensService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { fmtDateTime } from 'app/inspecto/grid';

/**
 * The **Action Requests** raised from one Incident or Case (`ASSURE-ACTION-REQUESTS-1`): each with its status,
 * attempts and the target's last answer, and — for an approver — Retry on a failed one (same idempotency key).
 * Approving and declining happen in the Action Requests inbox, where the payload is read in full.
 */
@Component({
    selector: 'app-action-requests-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, RouterLink, InspectoAlertComponent, StatusBadgeComponent],
    template: `
        <section aria-labelledby="action-requests-heading" class="flex flex-col gap-3">
            <div class="flex items-center justify-between">
                <h2 id="action-requests-heading" class="m-0 text-sm font-semibold">Action Requests</h2>
                <a mat-button routerLink="/action-requests">Open inbox</a>
            </div>
            @if (error()) {
                <inspecto-alert variant="error" title="Could not load the Action Requests">{{
                    error()
                }}</inspecto-alert>
            } @else if (items().length === 0) {
                <p class="text-secondary m-0 text-sm">No outbound calls have been requested from here.</p>
            } @else {
                <ul class="m-0 flex list-none flex-col gap-2 p-0">
                    @for (r of items(); track r.id) {
                        <li class="rounded-lg border p-3 text-sm">
                            <div class="flex flex-wrap items-center gap-2">
                                <span class="font-mono">{{ r.method }} → {{ r.connection }}</span>
                                <inspecto-status-badge [value]="r.status" />
                                <span class="text-secondary tabular-nums">{{ r.attempts }} attempt(s)</span>
                                <span class="flex-1"></span>
                                @if (r.status === 'failed' && lens.canApproveChanges()) {
                                    <button
                                        mat-stroked-button
                                        type="button"
                                        [disabled]="busy() === r.id"
                                        (click)="retry(r)"
                                        [attr.aria-label]="'Retry Action Request ' + r.id"
                                    >
                                        Retry
                                    </button>
                                }
                            </div>
                            <div class="text-secondary mt-1">
                                {{ r.id }} · by {{ r.author }} · {{ fmt(r.createdAt) }}
                                @if (r.approver) {
                                    · approved by {{ r.approver }}
                                }
                            </div>
                            @if (r.lastResponse; as last) {
                                <div class="mt-1">
                                    Last response:
                                    <span class="font-medium">{{ last.status ?? 'none' }}</span>
                                    @if (last.bodyRedacted) {
                                        <span class="text-secondary">(body shown to approvers only)</span>
                                    }
                                    @if (last.error) {
                                        — {{ last.error }}
                                    }
                                    @if (last.bodyExcerpt) {
                                        <pre
                                            class="bg-default mt-1 max-h-24 overflow-auto rounded p-2 text-xs whitespace-pre-wrap"
                                            >{{ last.bodyExcerpt }}</pre
                                        >
                                    }
                                </div>
                            }
                        </li>
                    }
                </ul>
            }
        </section>
    `,
})
export class ActionRequestsPanelComponent {
    private readonly api = inject(ActionRequestsService);
    private readonly toastr = inject(ToastrService);
    protected readonly lens = inject(LensService);

    /** The Incident or Case whose requests to list. */
    readonly objectId = input.required<string>();
    /** `INCIDENT` or `CASE` — which link field to filter on. */
    readonly objectType = input.required<string>();

    readonly items = signal<ActionRequest[]>([]);
    readonly error = signal<string | null>(null);
    readonly busy = signal<string | null>(null);

    readonly fmt = fmtDateTime;

    constructor() {
        effect(() => this.load(this.objectId(), this.objectType()));
    }

    load(id: string, type: string): void {
        const filter = type === 'CASE' ? { caseId: id } : { incidentId: id };
        this.api.list(filter).subscribe({
            next: (r) => {
                this.error.set(null);
                this.items.set(r.items);
            },
            error: (err) => this.error.set(apiErrorMessage(err, 'The Action Requests could not be read')),
        });
    }

    retry(r: ActionRequest): void {
        this.busy.set(r.id);
        this.api.retry(r.id).subscribe({
            next: (d) => {
                this.busy.set(null);
                this.toastr.success(d.status === 'succeeded' ? 'Delivered' : `Retried — now ${d.status}`);
                this.load(this.objectId(), this.objectType());
            },
            error: (err) => {
                this.busy.set(null);
                this.toastr.error(apiErrorMessage(err, 'The Action Request could not be retried'));
                this.load(this.objectId(), this.objectType());
            },
        });
    }
}
