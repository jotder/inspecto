import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { InvService, InvestigationLog, PendingExpansion, WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { LensService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';

/** A readable message for a four-eyes / reveal refusal; the server's own reason is always kept. */
export function oversightErrorMessage(err: unknown, fallback: string): string {
    const status = err instanceof HttpErrorResponse ? err.status : (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 403:
            return 'Refused by the server: ' + server;
        case 404:
            return (
                'Not available — the Investigation or request does not exist or is not visible to you. Server: ' +
                server
            );
        case 409:
            return 'Already decided or no longer applicable — ' + server;
        case 422:
            return 'The server refused the request: ' + server;
        default:
            return server;
    }
}

/**
 * **Oversight** (D-U6 / D-U7) — the Investigation tab's four-eyes and reveal surfaces. Lists every held sensitive
 * expand (who requested it, the thresholds it crossed, its status); a holder of `canApproveLinkExpansions` may
 * approve or deny a pending one — the SERVER refuses the requester deciding their own request, and that refusal is
 * shown here verbatim. A holder of `canRevealLinkEntities` may reveal one masked entity at a time; each reveal is
 * audited server-side. Emits `(decided)` so the host re-reads the log and Working Set.
 */
@Component({
    selector: 'inspecto-link-analysis-oversight',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent, StatusBadgeComponent],
    template: `
        @if (requests().length || masked().length) {
            <section
                class="flex flex-col gap-2 rounded-md border p-2 text-xs"
                style="border-color: var(--gamma-border)"
            >
                <h3 class="m-0 text-sm font-semibold">Oversight</h3>
                @if (error()) {
                    <inspecto-alert variant="error" title="Oversight">{{ error() }}</inspecto-alert>
                }
                @for (p of requests(); track p.id) {
                    <div
                        class="flex flex-col gap-1 border-t pt-1"
                        style="border-color: var(--gamma-border)"
                        [attr.aria-label]="'Expand request ' + p.id"
                    >
                        <div class="flex items-center gap-2">
                            <strong>Expand request {{ p.id }}</strong>
                            <inspecto-status-badge [value]="statusTone(p)" [label]="p.status"></inspecto-status-badge>
                        </div>
                        <span
                            >Requested by <strong>{{ p.requestedBy ?? 'unknown' }}</strong> at {{ p.requestedAt }}</span
                        >
                        <span class="text-secondary">
                            Four-eyes threshold crossed: {{ p.sensitivity.exceeded.join('; ') }} (budget above
                            {{ p.sensitivity.fourEyesBudgetAbove ?? '—' }}, fan-out above
                            {{ p.sensitivity.fourEyesFanOutAbove ?? '—' }})
                        </span>
                        @if (p.status !== 'pending') {
                            <span class="text-secondary">
                                {{ p.status === 'approved' ? 'Approved' : 'Denied' }} by {{ p.decidedBy ?? 'unknown' }}
                                @if (p.step) {
                                    — ran as step {{ p.step }}
                                }
                                @if (p.reason) {
                                    — reason: {{ p.reason }}
                                }
                            </span>
                        } @else if (canApprove()) {
                            <div class="flex items-center gap-2">
                                <input
                                    class="rounded border px-1"
                                    style="border-color: var(--gamma-border)"
                                    maxlength="200"
                                    placeholder="Reason (optional, for a deny)"
                                    aria-label="Deny reason"
                                    [value]="reason()"
                                    (input)="reason.set($any($event.target).value)"
                                />
                                <button mat-stroked-button [disabled]="busy()" (click)="decide(p, true)">
                                    Approve
                                </button>
                                <button mat-button [disabled]="busy()" (click)="decide(p, false)">Deny</button>
                            </div>
                        } @else {
                            <span class="text-secondary"
                                >Waiting for a different person with the approve-expansions capability.</span
                            >
                        }
                    </div>
                }
                @if (canReveal() && masked().length) {
                    <div class="flex flex-col gap-1 border-t pt-1" style="border-color: var(--gamma-border)">
                        <strong>Masked entities</strong>
                        <span class="text-secondary"
                            >Each reveal is one entity and is recorded in the audit trail.</span
                        >
                        @for (t of masked(); track t) {
                            <div class="flex items-center gap-2">
                                <code>{{ t }}</code>
                                @if (revealed()[t]; as v) {
                                    <span
                                        >→ <code>{{ v }}</code>
                                        <span class="text-secondary">(reveal audited)</span></span
                                    >
                                } @else {
                                    <button
                                        mat-button
                                        [disabled]="busy()"
                                        [attr.aria-label]="'Reveal ' + t"
                                        (click)="reveal(t)"
                                    >
                                        Reveal
                                    </button>
                                }
                            </div>
                        }
                    </div>
                }
            </section>
        }
    `,
})
export class LinkAnalysisOversightComponent {
    readonly investigationId = input.required<string>();
    readonly log = input<InvestigationLog | null>(null);
    readonly workingSet = input<WorkingSet | null>(null);
    readonly decided = output<void>();

    private readonly inv = inject(InvService);
    private readonly lens = inject(LensService);
    readonly canApprove = computed(() => this.lens.canApproveLinkExpansions());
    readonly canReveal = computed(() => this.lens.canRevealLinkEntities());

    readonly requests = computed<PendingExpansion[]>(() => this.log()?.pending ?? []);
    readonly masked = computed(() =>
        (this.workingSet()?.entities ?? []).map((e) => e.id).filter((id) => id.startsWith('masked:')),
    );
    readonly revealed = signal<Record<string, string>>({});
    readonly reason = signal('');
    readonly busy = signal(false);
    readonly error = signal('');

    statusTone(p: PendingExpansion): string {
        return p.status === 'pending' ? 'warning' : p.status === 'approved' ? 'success' : 'error';
    }

    async decide(p: PendingExpansion, approve: boolean): Promise<void> {
        const id = this.investigationId();
        this.busy.set(true);
        this.error.set('');
        try {
            if (approve) await firstValueFrom(this.inv.approveExpansion(id, p.id));
            else await firstValueFrom(this.inv.denyExpansion(id, p.id, this.reason().trim() || undefined));
            this.reason.set('');
            this.decided.emit();
        } catch (err) {
            this.error.set(oversightErrorMessage(err, approve ? 'Approve failed.' : 'Deny failed.'));
        } finally {
            this.busy.set(false);
        }
    }

    async reveal(token: string): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        try {
            const res = await firstValueFrom(this.inv.revealEntities(this.investigationId(), [token]));
            const hit = res.revealed.find((r) => r.token === token);
            if (hit) this.revealed.update((m) => ({ ...m, [token]: hit.id }));
            else this.error.set('The server did not recognise ' + token + ' as a pseudonym of this Investigation.');
        } catch (err) {
            this.error.set(oversightErrorMessage(err, 'Reveal failed.'));
        } finally {
            this.busy.set(false);
        }
    }
}
