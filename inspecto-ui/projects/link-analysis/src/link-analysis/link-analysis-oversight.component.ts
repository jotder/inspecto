import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { InvService, InvestigationLog, PendingExpansion, WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { LensService, apiErrorMessage } from '@inspecto/core/api';
// eslint-disable-next-line no-restricted-imports -- only `subjectName`, the requester's display name in the approve confirm; LA_FEATURES carries module flags, not identity
import { SessionService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { ledgerTime } from '@inspecto/link-analysis/add-data/add-data.model';
import { thresholdWords } from './investigate-number';
import { RevealedIds, refusal403 } from './investigation-state';
import { EntityListReasonData, EntityListReasonDialog } from './link-analysis-entity-lists.dialogs';
import { confirmApproveExpansion, requesterName } from './expansion-decision';

/** A readable message for a four-eyes / reveal refusal; the server's own reason is always kept. */
export function oversightErrorMessage(err: unknown, fallback: string): string {
    const status = err instanceof HttpErrorResponse ? err.status : (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 403:
            return refusal403(server);
        case 404:
            return 'Not available: the Investigation or request does not exist or is not visible to you.';
        case 409:
            return 'Already decided or no longer applicable.';
        case 422:
            return 'Refused: ' + server;
        default:
            return server;
    }
}

/**
 * **Oversight** (D-U6 / D-U7) — the Investigation tab's four-eyes and reveal surfaces. Lists every held sensitive
 * expand (who requested it, the thresholds it crossed, its status); a holder of `canApproveLinkExpansions` may
 * approve or deny a pending one — the SERVER refuses the requester deciding their own request, and that refusal is
 * shown here verbatim. A holder of `canRevealLinkEntities` reveals the masked entities in ONE action with a reason
 * (LA-FULL-CYCLE-TEST-1 D3, the same Reveal as the canvas's): the server records one audit row per entity, and the host
 * keeps the real ids for this screen only (`revealed`). Emits `(decided)` so the host re-reads the log and Working Set.
 */
@Component({
    selector: 'inspecto-link-analysis-oversight',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent, StatusBadgeComponent],
    template: `
        @if (requests().length || masked().length) {
            <section
                class="flex flex-col gap-2 rounded-md border p-2 text-sm"
                style="border-color: var(--gamma-border)"
            >
                <h3 class="m-0 text-base font-semibold">Oversight</h3>
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
                            >Requested by <strong>{{ p.requestedBy ?? 'unknown' }}</strong> at
                            {{ when(p.requestedAt) }}</span
                        >
                        <span class="text-secondary"> Held for a second person’s approval: {{ heldFor(p) }}. </span>
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
                                >Waiting for a different person who can approve expansions.</span
                            >
                        }
                    </div>
                }
                @if (canReveal() && masked().length) {
                    <div class="flex flex-col gap-1 border-t pt-1" style="border-color: var(--gamma-border)">
                        <div class="flex items-center gap-2">
                            <strong>Masked entities</strong>
                            @if (unrevealed().length && reveal()) {
                                <button
                                    mat-stroked-button
                                    type="button"
                                    class="ml-auto"
                                    data-testid="oversight-reveal"
                                    [disabled]="busy()"
                                    (click)="revealAll()"
                                >
                                    {{
                                        unrevealed().length === 1
                                            ? 'Reveal it…'
                                            : 'Reveal all ' + unrevealed().length + '…'
                                    }}
                                </button>
                            }
                        </div>
                        <span class="text-secondary"
                            >One reveal shows them all, to you on this screen only; each entity is recorded in the audit
                            trail with your reason.</span
                        >
                        @for (t of masked(); track t) {
                            <div class="flex items-center gap-2">
                                <code>{{ t }}</code>
                                @if (revealed()[t]; as v) {
                                    <span
                                        >→ <code>{{ v }}</code>
                                        <span class="text-secondary">(reveal audited)</span></span
                                    >
                                }
                            </div>
                        }
                    </div>
                }
            </section>
        } @else {
            <!-- T-152: the fold never opens blank -->
            <p class="text-secondary m-0 text-sm" data-testid="oversight-empty">
                No approvals needed for this Investigation.
            </p>
        }
    `,
})
export class LinkAnalysisOversightComponent {
    readonly investigationId = input.required<string>();
    readonly log = input<InvestigationLog | null>(null);
    readonly workingSet = input<WorkingSet | null>(null);
    /** The aliases this screen already revealed (the host's, shared with the canvas and the node dialog). */
    readonly revealed = input<RevealedIds>({});
    /** Reveal these aliases with a reason (the host's store); null = no reveal from here. */
    readonly reveal = input<((tokens: string[], reason: string) => Promise<number>) | null>(null);
    readonly decided = output<void>();

    private readonly inv = inject(InvService);
    private readonly lens = inject(LensService);
    private readonly dialog = inject(MatDialog);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly session = inject(SessionService);
    readonly canApprove = computed(() => this.lens.canApproveLinkExpansions());
    readonly canReveal = computed(() => this.lens.canRevealLinkEntities());

    readonly requests = computed<PendingExpansion[]>(() => this.log()?.pending ?? []);
    readonly masked = computed(() =>
        (this.workingSet()?.entities ?? []).map((e) => e.id).filter((id) => id.startsWith('masked:')),
    );
    readonly unrevealed = computed(() => this.masked().filter((t) => !Object.hasOwn(this.revealed(), t)));
    readonly reason = signal('');
    readonly busy = signal(false);
    readonly error = signal('');

    /** A request's time in the one app clock (`ledgerTime`, `DATES-ONE-ZONE-1`), as the step log and File ▸ Open… show it. */
    when(iso: string | undefined): string {
        return ledgerTime(iso) || 'an unknown time';
    }

    /** Which four-eyes limit the request crossed, in words, each with its limit (finding 29: never `budget 500 > 100`). */
    heldFor(p: PendingExpansion): string {
        return thresholdWords(p.sensitivity.exceeded);
    }

    statusTone(p: PendingExpansion): string {
        return p.status === 'pending' ? 'warning' : p.status === 'approved' ? 'success' : 'error';
    }

    async decide(p: PendingExpansion, approve: boolean): Promise<void> {
        // finding 23: approving runs the expand at once, so it is confirmed first (as on the Investigation tab's line)
        if (
            approve &&
            !(await confirmApproveExpansion(
                this.confirm,
                p,
                requesterName(p, (id) => this.session.subjectName(id)),
            ))
        )
            return;
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

    /** D3: one Reveal for every masked entity listed, after asking the reason (the server audits each entity). */
    async revealAll(): Promise<void> {
        const reveal = this.reveal();
        const tokens = this.unrevealed();
        if (!reveal || !tokens.length) return;
        const what = tokens.length === 1 ? 'the masked entity' : `the ${tokens.length} masked entities`;
        const reason = await firstValueFrom(
            this.dialog
                .open<EntityListReasonDialog, EntityListReasonData, string | undefined>(EntityListReasonDialog, {
                    width: '32rem',
                    data: {
                        title: 'Reveal the masked entities',
                        message: `Shows the real identifiers of ${what}, to you and on this screen only. Each one is recorded in the audit trail with your reason.`,
                        confirmLabel: 'Reveal',
                        maxLength: 200,
                    },
                })
                .afterClosed(),
        );
        if (!reason) return;
        this.busy.set(true);
        this.error.set('');
        try {
            await reveal(tokens, reason);
        } catch (err) {
            this.error.set(oversightErrorMessage(err, 'Reveal failed.'));
        } finally {
            this.busy.set(false);
        }
    }
}
