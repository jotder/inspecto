import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import {
    DraftConflictReport,
    DraftStateName,
    InvService,
    InvestigationDraft,
} from '@inspecto/link-analysis/api/inv.service';
import { apiErrorMessage } from '@inspecto/core/api';
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
            if (/must rebase/.test(server))
                return (
                    'The main log has moved on — rebase this Draft onto the current head, then promote. Server: ' +
                    server
                );
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
            class="flex flex-col gap-2 rounded-md border p-2 text-xs"
            style="border-color: var(--gamma-border)"
            aria-label="Drafts"
        >
            <div class="flex items-center gap-2">
                <h3 class="m-0 text-sm font-semibold">Drafts</h3>
                <button mat-stroked-button [disabled]="busy()" (click)="fork()">Fork a Draft</button>
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
                    [attr.aria-label]="'Draft ' + d.draftId"
                >
                    <div class="flex flex-wrap items-center gap-2">
                        <strong>{{ d.actor }}</strong>
                        <code>{{ d.draftId }}</code>
                        <inspecto-status-badge [value]="tone(d.state)" [label]="d.state"></inspecto-status-badge>
                        <span class="text-secondary">
                            forked at step {{ d.baseStep }} · {{ d.steps }} own step(s)
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
                                <button mat-button [disabled]="busy()" (click)="scope.emit(null)">
                                    Back to the Investigation
                                </button>
                            } @else {
                                <button mat-stroked-button [disabled]="busy()" (click)="scope.emit(d.draftId)">
                                    Work on this Draft
                                </button>
                            }
                            <button mat-button [disabled]="busy()" (click)="showWorkingSet(d)">Working Set</button>
                            <button mat-button [disabled]="busy()" (click)="showConflicts(d)">Conflicts</button>
                            <button mat-button [disabled]="busy()" (click)="rebase(d)">Rebase</button>
                            <button mat-stroked-button [disabled]="busy()" (click)="promote(d)">Promote</button>
                            <button mat-button [disabled]="busy()" (click)="discard(d)">Discard</button>
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

    readonly drafts = signal<InvestigationDraft[]>([]);
    readonly hidden = signal(false);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly notice = signal('');
    readonly sizes = signal<Record<string, { entities: number; links: number; excluded: number }>>({});
    readonly reports = signal<Record<string, DraftConflictReport>>({});

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
        } catch (err) {
            this.drafts.set([]);
            if ((err as { status?: number } | null)?.status === 404) this.hidden.set(true);
            else this.error.set(draftErrorMessage(err, 'Could not read the Drafts.'));
        }
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
        await this.run(() => firstValueFrom(this.inv.forkDraft(this.investigationId())), 'Could not fork a Draft.');
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
        const done = await this.run(
            () => firstValueFrom(this.inv.discardDraft(this.investigationId(), d.draftId)),
            'Could not discard the Draft.',
        );
        if (done && this.activeDraftId() === d.draftId) this.scope.emit(null);
    }
}
