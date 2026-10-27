import {
    ChangeDetectionStrategy,
    Component,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    untracked,
} from '@angular/core';
import { LensService, SessionService } from '@inspecto/core/api';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import {
    DraftConflictReport,
    DraftStateName,
    InvService,
    InvestigationDraft,
    InvestigationRole,
} from '@inspecto/link-analysis/api/inv.service';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { limitRefusalMessage } from './limit-refusal';

/** A Draft refusal in an analyst's words; the server's own reason (which names the step or the limit) is kept. */
export function draftErrorMessage(err: unknown, fallback: string): string {
    const limit = limitRefusalMessage(err);
    if (limit) return limit;
    const status = (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 403:
            return 'Your role does not allow that on this Draft. Server: ' + server;
        case 404:
            return 'Not available — the Draft or Investigation is gone or not visible to you. Server: ' + server;
        case 409:
            if (/open Drafts/.test(server))
                return (
                    'This Space has reached its Drafts limit (50 open Drafts). Discard or promote one, or wait for an idle one to expire, then fork again. Server: ' +
                    server
                );
            if (/already have a live draft/.test(server))
                return (
                    'You already have a live Draft on this Investigation — continue it, promote it or discard it before forking another. Server: ' +
                    server
                );
            if (/must rebase/.test(server) && /undone steps/.test(server))
                return 'The Draft holds undone steps — rebase it to compact them away, then promote. Server: ' + server;
            if (/must rebase/.test(server) && /index version/.test(server))
                return (
                    'The index version this Draft pinned has expired — rebase it to re-pin, then promote. Server: ' +
                    server
                );
            if (/must rebase/.test(server) && /based on step/.test(server))
                return (
                    'The main log has moved on — rebase this Draft onto the current head, then promote. Server: ' +
                    server
                );
            if (/must rebase/.test(server))
                return 'This Draft must be rebased before it can be promoted. Server: ' + server;
            return 'Refused as the Draft stands now — ' + server;
        case 422:
            return 'The server refused the request: ' + server;
        default:
            return server;
    }
}

/**
 * **Drafts** (D7-3 .. D7-6) — a member's own working copy of the Investigation: fork it, explore, then rebase it onto the
 * main log's head (resolving any conflicts) and promote it, or discard it. Everything is decided by the SERVER; this panel
 * shows the Draft's state (`open` / `hibernated` / …), how far the main log has moved past it, its Working Set sizes and
 * the conflict report, and shows a refusal in place. A promote that crosses the four-eyes thresholds is HELD, not applied.
 * Emits `(promoted)` so the host re-reads the main log and Working Set. "Work on this Draft" emits `(scope)` with the Draft's
 * id so the host routes the Investigation's steps and Undo to the Draft (`…/drafts/{draftId}/ops` · `/undo`); null = back to
 * the main log (LA-UI-DRAFT-OPS-1).
 */
@Component({
    selector: 'inspecto-link-analysis-drafts',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent, StatusBadgeComponent],
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2 text-sm"
            style="border-color: var(--gamma-border)"
            aria-label="Drafts"
        >
            <div class="flex items-center gap-2">
                <h3 class="m-0 text-base font-semibold">Drafts</h3>
                <input
                    class="min-w-0 flex-1 rounded border px-2 py-1"
                    style="border-color: var(--gamma-border)"
                    type="text"
                    maxlength="80"
                    placeholder="Name this Draft (optional)"
                    aria-label="Draft label (optional)"
                    [value]="label()"
                    (input)="label.set($any($event.target).value)"
                />
                <button mat-stroked-button [disabled]="busy() || !canWrite()" (click)="fork()">Fork a Draft</button>
            </div>
            @if (error()) {
                <inspecto-alert variant="error" title="Drafts">{{ error() }}</inspecto-alert>
            }
            @if (notice()) {
                <inspecto-alert variant="info" title="Drafts">{{ notice() }}</inspecto-alert>
            }
            @if (hidden()) {
                <p class="text-secondary m-0">Drafts are not available to you on this Investigation.</p>
            } @else if (drafts().length === 0) {
                <p class="text-secondary m-0">
                    No Drafts. A Draft is your own working copy: explore on it, then promote it to the Investigation.
                </p>
            }
            @for (d of drafts(); track d.draftId) {
                <div
                    class="flex flex-col gap-1 border-t pt-1"
                    style="border-color: var(--gamma-border)"
                    role="group"
                    [attr.aria-label]="'Draft ' + d.draftId"
                >
                    <div class="flex flex-wrap items-center gap-2">
                        <strong>{{ d.actor }}</strong>
                        @if (d.label) {
                            <strong class="draft-label">{{ d.label }}</strong>
                        }
                        <code>{{ d.draftId }}</code>
                        <inspecto-status-badge [value]="tone(d.state)" [label]="d.state"></inspecto-status-badge>
                        <span class="text-secondary">
                            forked at step {{ d.baseStep }} · {{ ownSteps(d) }} own step(s)
                        </span>
                        @if (d.behind > 0) {
                            <inspecto-status-badge
                                value="warning"
                                [label]="'main moved ' + d.behind + ' step(s)'"
                            ></inspecto-status-badge>
                        }
                        @if (d.expiryWarning) {
                            <inspecto-status-badge value="warning" label="expires soon"></inspecto-status-badge>
                        }
                        @if (d.pinWarning) {
                            <inspecto-status-badge value="warning" label="index pin expiring"></inspecto-status-badge>
                        }
                    </div>
                    @if (d.state === 'open' || d.state === 'hibernated') {
                        <div class="flex flex-wrap items-center gap-1">
                            @if (activeDraftId() === d.draftId) {
                                <inspecto-status-badge value="info" label="working scope"></inspecto-status-badge>
                                <button
                                    mat-button
                                    [disabled]="busy()"
                                    [attr.aria-label]="'Back to the Investigation from Draft ' + d.draftId"
                                    (click)="scope.emit(null)"
                                >
                                    Back to the Investigation
                                </button>
                            } @else {
                                <button
                                    mat-stroked-button
                                    [disabled]="busy()"
                                    [attr.aria-label]="'Work on this Draft ' + d.draftId"
                                    (click)="scope.emit(d.draftId)"
                                >
                                    Work on this Draft
                                </button>
                            }
                            <button
                                mat-button
                                [disabled]="busy()"
                                [attr.aria-label]="'Working Set of Draft ' + d.draftId"
                                (click)="showWorkingSet(d)"
                            >
                                Working Set
                            </button>
                            <button
                                mat-button
                                [disabled]="busy()"
                                [attr.aria-label]="'Conflicts of Draft ' + d.draftId"
                                (click)="showConflicts(d)"
                            >
                                Conflicts
                            </button>
                            <button
                                mat-button
                                [disabled]="busy() || !canRebase(d)"
                                [attr.aria-label]="'Rebase of Draft ' + d.draftId"
                                (click)="rebase(d)"
                            >
                                Rebase
                            </button>
                            <button
                                mat-stroked-button
                                [disabled]="busy() || !canFinish(d)"
                                [attr.aria-label]="'Promote Draft ' + d.draftId"
                                (click)="promote(d)"
                            >
                                Promote
                            </button>
                            <button
                                mat-button
                                [disabled]="busy() || !canFinish(d)"
                                [attr.aria-label]="'Discard Draft ' + d.draftId"
                                (click)="discard(d)"
                            >
                                Discard
                            </button>
                        </div>
                    }
                    @if (sizes()[d.draftId]; as s) {
                        <span>
                            Working Set of this Draft: {{ s.entities }} entities · {{ s.links }} links ·
                            {{ s.excluded }} excluded
                        </span>
                    }
                    @if (reports()[d.draftId]; as r) {
                        <div class="flex flex-col gap-1">
                            <span>
                                Based on step {{ r.baseStep }}; main is at step {{ r.mainHead }} ({{ r.behind }} behind)
                                · {{ r.carried }} step(s) would carry over.
                            </span>
                            @if (r.conflicts.length === 0) {
                                <span class="text-secondary">No conflicts — a rebase would carry every step.</span>
                            }
                            @for (c of r.conflicts; track c.step) {
                                <span>
                                    Step {{ c.step }} ({{ c.op }}) — <strong>{{ c.kind }}</strong
                                    >: {{ c.detail }}
                                    @if (c.requiresConfirm) {
                                        <em class="text-secondary"> — Rebase drops it, and asks you to confirm.</em>
                                    }
                                </span>
                            }
                        </div>
                    }
                </div>
            }
        </section>
    `,
})
export class LinkAnalysisDraftsComponent {
    readonly investigationId = input.required<string>();
    readonly promoted = output<void>();
    /** The Draft that is the host's working scope (null = the main log). */
    readonly activeDraftId = input<string | null>(null);
    /** Asks the host to make this Draft (or, with null, the main log) the working scope. */
    readonly scope = output<string | null>();
    /** Changes whenever the host applies a step or Undo — the list (own-step counts) is re-read then. */
    readonly refreshOn = input<unknown>(null);

    private readonly inv = inject(InvService);
    private readonly lens = inject(LensService);
    private readonly confirm = inject(InspectoConfirmService);
    readonly canWrite = computed(() => this.lens.canManageIncidents());
    private readonly session = inject(SessionService);
    /** The caller's role on this Investigation (null until read, or when it cannot be). */
    private readonly role = signal<InvestigationRole | null>(null);

    /** The server's rule (`DraftRoutes.openDraft`): only a Draft's actor writes or rebases it; its actor or a lead promotes or discards it. */
    private mine(d: InvestigationDraft): boolean {
        const me = this.session.actor();
        return !me || d.actor === me;
    }
    canRebase(d: InvestigationDraft): boolean {
        return this.canWrite() && this.mine(d);
    }
    canFinish(d: InvestigationDraft): boolean {
        return this.canWrite() && (this.mine(d) || this.role() === 'lead');
    }

    readonly drafts = signal<InvestigationDraft[]>([]);
    readonly hidden = signal(false);
    readonly busy = signal(false);
    /** The optional label typed for the NEXT fork. */
    readonly label = signal('');
    readonly error = signal('');
    readonly notice = signal('');
    readonly sizes = signal<Record<string, { entities: number; links: number; excluded: number }>>({});
    readonly reports = signal<Record<string, DraftConflictReport>>({});
    /** EFFECTIVE own steps per Draft (undo records and the ops they undid folded away, as the evaluator does). */
    readonly effective = signal<Record<string, number>>({});

    constructor() {
        effect(() => {
            const id = this.investigationId();
            this.refreshOn();
            untracked(() => this.reload(id));
        });
    }

    tone(state: DraftStateName): string {
        return state === 'open'
            ? 'success'
            : state === 'hibernated'
              ? 'info'
              : state === 'promoted'
                ? 'success'
                : 'neutral';
    }

    private async reload(id: string = this.investigationId()): Promise<void> {
        try {
            const l = await firstValueFrom(this.inv.investigationDrafts(id));
            if (id !== this.investigationId()) return;
            this.drafts.set(l.items);
            this.hidden.set(false);
            await this.readRole(id);
            await this.countEffective(id, l.items);
        } catch (err) {
            this.drafts.set([]);
            if ((err as { status?: number } | null)?.status === 404) this.hidden.set(true);
            else this.error.set(draftErrorMessage(err, 'Could not read the Drafts.'));
        }
    }

    private async readRole(id: string): Promise<void> {
        try {
            const m = await firstValueFrom(this.inv.investigationMembers(id));
            if (id === this.investigationId()) this.role.set(m.you);
        } catch {
            this.role.set(null); // not a member, or the list is not available: only the Draft's own actor may act
        }
    }

    /** The own-step count shown: the effective count when the Draft's log was read, else the server's raw record count. */
    ownSteps(d: InvestigationDraft): number {
        return this.effective()[d.draftId] ?? d.steps;
    }

    /**
     * `steps` on the list counts every own log RECORD, an Undo included; fold the Draft's own entries the way the evaluator
     * does (an op counts unless it was undone; undo records never count). A read that fails or is truncated keeps the raw count.
     */
    private async countEffective(id: string, items: InvestigationDraft[]): Promise<void> {
        const counts: Record<string, number> = {};
        await Promise.all(
            items
                .filter((d) => d.steps > 0 && (d.state === 'open' || d.state === 'hibernated'))
                .map(async (d) => {
                    try {
                        const log = await firstValueFrom(this.inv.draftLog(id, d.draftId));
                        if (log.truncated) return;
                        counts[d.draftId] = log.entries.filter(
                            (e) => e.step > d.baseStep && e.kind === 'op' && e.undoneBy == null,
                        ).length;
                    } catch {
                        /* keep the raw count */
                    }
                }),
        );
        if (id === this.investigationId()) this.effective.set(counts);
    }

    /** Run one Draft call, show a refusal in place, refresh the list; resolves to the answer or null on refusal. */
    private async run<T>(call: () => Promise<T>, fallback: string): Promise<T | null> {
        this.busy.set(true);
        this.error.set('');
        this.notice.set('');
        try {
            const out = await call();
            await this.reload();
            return out;
        } catch (err) {
            this.error.set(draftErrorMessage(err, fallback));
            return null;
        } finally {
            this.busy.set(false);
        }
    }

    async fork(): Promise<void> {
        const label = this.label().trim();
        const made = await this.run(
            () => firstValueFrom(this.inv.forkDraft(this.investigationId(), undefined, label || undefined)),
            'Could not fork a Draft.',
        );
        if (made) this.label.set('');
    }

    async showWorkingSet(d: InvestigationDraft): Promise<void> {
        const id = this.investigationId();
        this.error.set('');
        try {
            const [e, l, x] = await Promise.all(
                (['entities', 'links', 'excluded'] as const).map((of) =>
                    firstValueFrom(this.inv.draftWorkingSet(id, d.draftId, of)),
                ),
            );
            this.sizes.update((s) => ({ ...s, [d.draftId]: { entities: e.total, links: l.total, excluded: x.total } }));
        } catch (err) {
            this.error.set(draftErrorMessage(err, 'Could not read the Draft’s Working Set.'));
        }
    }

    async showConflicts(d: InvestigationDraft): Promise<void> {
        const r = await this.run(
            () => firstValueFrom(this.inv.draftConflicts(this.investigationId(), d.draftId)),
            'Could not compute the conflicts.',
        );
        if (r) this.reports.update((m) => ({ ...m, [d.draftId]: r }));
    }

    /** Rebase: the superseded / blocked steps are the ones the server asks to be confirmed; the panel confirms exactly those it just showed. */
    async rebase(d: InvestigationDraft): Promise<void> {
        const id = this.investigationId();
        const report = await this.run(
            () => firstValueFrom(this.inv.draftConflicts(id, d.draftId)),
            'Could not compute the conflicts.',
        );
        if (!report) return;
        this.reports.update((m) => ({ ...m, [d.draftId]: report }));
        const confirm = report.conflicts.filter((c) => c.requiresConfirm).map((c) => c.step);
        const done = await this.run(
            () => firstValueFrom(this.inv.rebaseDraft(id, d.draftId, confirm, report.mainHead)),
            'Could not rebase the Draft.',
        );
        if (done) {
            this.reports.update((m) => {
                const { [d.draftId]: _gone, ...rest } = m;
                return rest;
            });
            this.notice.set(
                confirm.length
                    ? `Rebased onto step ${report.mainHead}; ${confirm.length} conflicting step(s) were dropped.`
                    : `Rebased onto step ${report.mainHead}.`,
            );
        }
    }

    async promote(d: InvestigationDraft): Promise<void> {
        const id = this.investigationId();
        const head = d.baseStep + d.behind;
        const r = await this.run(
            () => firstValueFrom(this.inv.promoteDraft(id, d.draftId, head)),
            'Could not promote the Draft.',
        );
        if (!r) return;
        if (r.status === 'pending') {
            this.notice.set(
                'This promote contains a sensitive expand, so it is held for four-eyes approval — nothing was applied yet. A different person approves or denies it under Oversight.',
            );
        } else {
            this.notice.set('Promoted — the Draft’s steps are now in the Investigation.');
            this.promoted.emit();
        }
    }

    async discard(d: InvestigationDraft): Promise<void> {
        const name = d.label ? `“${d.label}” (${d.draftId})` : d.draftId;
        const ok = await this.confirm.confirmDestructive(
            `Discard the Draft ${name}? Its steps are dropped and are not promoted. This cannot be undone.`,
            { title: 'Discard Draft', confirmText: 'Discard' },
        );
        if (!ok) return;
        const done = await this.run(
            () => firstValueFrom(this.inv.discardDraft(this.investigationId(), d.draftId)),
            'Could not discard the Draft.',
        );
        if (done && this.activeDraftId() === d.draftId) this.scope.emit(null);
    }
}
