import {
    ChangeDetectionStrategy,
    Component,
    Injector,
    afterNextRender,
    computed,
    effect,
    inject,
    input,
    signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { switchMap } from 'rxjs';
import { ActionRequestsService } from 'app/inspecto/api/action-requests.service';
import { apiErrorMessage, needsPermission } from 'app/inspecto/api/api-base';
import { Insight, InsightBand, InsightFeed, InsightFeedback, InsightsService } from 'app/inspecto/api/insights.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { ObjectsService } from 'app/inspecto/api/objects.service';
import { SessionService } from 'app/inspecto/auth/session.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { bandBadge, sliceLabel } from 'app/inspecto/insights/insight-profile-form';
import {
    insightActionPayload,
    insightCaseDescription,
    insightCaseMember,
    insightCaseTitle,
    insightEvidence,
} from 'app/inspecto/insights/insight-evidence';
import { InsightActionRequestDialog, InsightActionRequestResult } from './insight-action-request.dialog';

/** What was opened from one Insight in this view: the Case, and the Action Request when one was raised. */
interface Opened {
    caseId: string;
    actionRequestId?: string;
}

const FEEDBACK: { status: InsightFeedback; label: string }[] = [
    { status: 'useful', label: 'Useful' },
    { status: 'not_useful', label: 'Not useful' },
    { status: 'expected', label: 'Expected' },
];

const RUNS: { run: 'latest' | 'all'; label: string }[] = [
    { run: 'latest', label: 'This run' },
    { run: 'all', label: 'Every run' },
];

const BANDS: { band: InsightBand | null; label: string }[] = [
    { band: null, label: 'Any band' },
    { band: 'high', label: 'High' },
    { band: 'elevated', label: 'Elevated' },
    { band: 'normal', label: 'Normal' },
];

const STATUS_WORDS: Record<string, string> = {
    new: 'New',
    useful: 'Marked useful',
    not_useful: 'Marked not useful',
    expected: 'Marked expected',
};

/**
 * The Insight feed of one Insight Profile (BI-INSIGHTS-1 P3): `GET /insights?profile=`, the latest run, best score
 * first. Feedback needs canAuthorWorkbench on the server, so the buttons are disabled with the reason without it.
 * A 404 means no run has written the Insights Dataset yet (or the reader may not view it) — an empty state, not an error.
 */
@Component({
    selector: 'app-insight-feed',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        RouterLink,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    template: `
        <div class="mb-3 flex flex-wrap items-center gap-x-4 gap-y-2" data-testid="feed-filters">
            <div class="flex items-center gap-1" role="group" aria-label="Which runs">
                @for (r of runChoices; track r.run) {
                    <button
                        mat-stroked-button
                        type="button"
                        [class.bg-hover]="run() === r.run"
                        [attr.aria-pressed]="run() === r.run"
                        (click)="run.set(r.run)"
                    >
                        {{ r.label }}
                    </button>
                }
            </div>
            <div class="flex items-center gap-1" role="group" aria-label="Band">
                @for (b of bandChoices; track b.label) {
                    <button
                        mat-stroked-button
                        type="button"
                        [class.bg-hover]="bandFilter() === b.band"
                        [attr.aria-pressed]="bandFilter() === b.band"
                        (click)="bandFilter.set(b.band)"
                    >
                        {{ b.label }}
                    </button>
                }
            </div>
        </div>
        @if (loading()) {
            <inspecto-skeleton [lines]="3"></inspecto-skeleton>
        } @else if (error(); as e) {
            <p class="text-warn text-sm" role="alert">{{ e }}</p>
        } @else if (feed(); as f) {
            @if (f.insights.length === 0) {
                <p class="text-secondary text-sm">
                    @if (run() === 'all') {
                        No kept run found an Insight{{ bandFilter() ? ' in the ' + bandFilter() + ' band' : '' }}.
                    } @else {
                        The latest run{{ f.latestRun ? ' (' + f.latestRun + ')' : '' }} found no Insight{{
                            bandFilter() ? ' in the ' + bandFilter() + ' band' : ''
                        }}.
                    }
                </p>
            } @else {
                @if (highlight(); as h) {
                    @if (!hasInsight(f, h)) {
                        <p class="text-secondary mb-2 text-xs" data-testid="highlight-missing">
                            The linked Insight {{ h }} is not in the latest run; it came from an earlier one.
                        </p>
                    }
                }
                <p class="text-secondary mb-2 text-xs">
                    {{ run() === 'all' ? 'Every kept run (' + f.runs + ')' : 'Latest run ' + f.latestRun }} ·
                    {{ f.insights.length }} of {{ f.total }} Insights{{
                        bandFilter() ? ', ' + bandFilter() + ' band' : ''
                    }}, best score first.
                </p>
                <ul class="flex flex-col gap-3" aria-label="Insights">
                    @for (i of f.insights; track i.insight_id) {
                        <li
                            class="rounded-lg border p-3"
                            data-testid="insight"
                            [attr.id]="'insight-' + i.insight_id"
                            [class.border-primary]="i.insight_id === highlight()"
                            [class.border-2]="i.insight_id === highlight()"
                            [attr.aria-current]="i.insight_id === highlight() ? 'true' : null"
                        >
                            @if (i.insight_id === highlight()) {
                                <p class="text-primary mb-1 text-xs font-semibold">The Insight the link points to</p>
                            }
                            <div class="flex flex-wrap items-center gap-2">
                                <inspecto-status-badge [value]="band(i).tone" [label]="band(i).label" />
                                <span class="text-sm font-semibold">{{ slice(i) }}</span>
                                <span class="text-secondary text-xs">{{ i.insight_type }} · {{ i.period }}</span>
                                @if (run() === 'all' && i.run_at) {
                                    <span class="text-secondary text-xs" data-testid="insight-run"
                                        >found {{ i.run_at }}</span
                                    >
                                }
                                <span class="text-secondary ml-auto text-xs tabular-nums"
                                    >Score {{ round(i.score) }}</span
                                >
                            </div>
                            <p class="mt-1 text-sm">{{ i.explanation?.sentence || 'No sentence was stored.' }}</p>
                            <div class="mt-2 flex flex-wrap items-center gap-2">
                                <span class="text-secondary text-xs">{{ statusWords(i) }}</span>
                                @for (b of feedbackChoices; track b.status) {
                                    <button
                                        mat-stroked-button
                                        type="button"
                                        [disabled]="!canFeedback() || busy() === i.insight_id"
                                        [attr.title]="canFeedback() ? null : noFeedbackReason"
                                        [attr.aria-pressed]="i.status === b.status"
                                        (click)="give(i, b.status)"
                                    >
                                        {{ b.label }}
                                    </button>
                                }
                                @if (casesInstalled()) {
                                    <button
                                        mat-stroked-button
                                        type="button"
                                        data-testid="open-case"
                                        [disabled]="!!caseBlocked() || busy() === i.insight_id"
                                        [attr.title]="caseBlocked()"
                                        (click)="openCase(i)"
                                    >
                                        Open a Case
                                    </button>
                                }
                                @if (actionsInstalled()) {
                                    <button
                                        mat-stroked-button
                                        type="button"
                                        data-testid="request-action"
                                        [disabled]="!!actionBlocked() || busy() === i.insight_id"
                                        [attr.title]="actionBlocked()"
                                        (click)="requestAction(i)"
                                    >
                                        Request action…
                                    </button>
                                }
                            </div>
                            @if (opened()[i.insight_id]; as o) {
                                <p class="mt-2 text-sm" role="status" data-testid="opened">
                                    Opened Case
                                    <a class="text-primary underline" [routerLink]="['/cases', o.caseId]">{{
                                        o.caseId
                                    }}</a>
                                    @if (o.actionRequestId) {
                                        and Action Request
                                        <a
                                            class="text-primary underline"
                                            routerLink="/action-requests"
                                            [queryParams]="{ id: o.actionRequestId }"
                                            >{{ o.actionRequestId }}</a
                                        >, waiting for approval.
                                    }
                                </p>
                            }
                        </li>
                    }
                </ul>
                @if (!canFeedback()) {
                    <p class="text-secondary mt-2 text-xs">{{ noFeedbackReason }}</p>
                }
                @if (casesInstalled() && caseBlocked(); as why) {
                    <p class="text-secondary mt-1 text-xs">Open a Case: {{ why }}</p>
                }
                @if (actionsInstalled() && actionBlocked(); as why) {
                    <p class="text-secondary mt-1 text-xs">Request action: {{ why }}</p>
                }
            }
        } @else {
            <inspecto-empty-state
                icon="heroicons_outline:light-bulb"
                title="No Insights yet"
                message="An insight.scan Job writes this Profile's Insights. Add one on the Jobs screen with the parameter profile = {{
                    profile()
                }}, then run it."
            ></inspecto-empty-state>
            <a mat-stroked-button class="mt-2" routerLink="/jobs">Open Jobs</a>
        }
    `,
})
export class InsightFeedComponent {
    private api = inject(InsightsService);
    private toastr = inject(ToastrService);
    private lens = inject(LensService);
    private session = inject(SessionService);
    private objects = inject(ObjectsService);
    private actionRequests = inject(ActionRequestsService);
    private dialog = inject(MatDialog);

    readonly profile = input.required<string>();
    /** The Insight a link points to (`?insight=` on the page): marked and scrolled to; said so when not in this run. */
    readonly highlight = input<string | null>(null);
    /** Bumped by the page to load again (a scan it started has ended). */
    readonly reload = input(0);
    readonly runChoices = RUNS;
    readonly bandChoices = BANDS;
    /** "This run" (`run=latest`) or "Every run" (`run=all`), and a band filter — both applied by the server. */
    readonly run = signal<'latest' | 'all'>('latest');
    readonly bandFilter = signal<InsightBand | null>(null);
    private injector = inject(Injector);
    readonly feedbackChoices = FEEDBACK;
    readonly noFeedbackReason = needsPermission('canAuthorWorkbench');
    readonly canFeedback = this.lens.canAuthorWorkbench;
    readonly loading = signal(true);
    readonly error = signal<string | null>(null);
    readonly feed = signal<InsightFeed | null>(null);
    readonly busy = signal<string | null>(null);
    readonly band = (i: Insight) => bandBadge(i.band);
    readonly slice = sliceLabel;
    readonly round = (n: number) => Math.round(n);
    readonly hasInsight = (f: InsightFeed, id: string) => f.insights.some((i) => i.insight_id === id);

    /** Each action shows only when its module is installed (`/bootstrap` features); a request also opens a Case. */
    readonly casesInstalled = computed(() => this.session.features()['cases'] === true);
    readonly actionsInstalled = computed(
        () => this.casesInstalled() && this.session.features()['actionRequests'] === true,
    );
    /** Why an installed action is disabled for this reader, or null when they may use it. */
    readonly caseBlocked = computed(() =>
        this.lens.canManageIncidents() ? null : needsPermission('canManageIncidents'),
    );
    readonly actionBlocked = computed(
        () => this.caseBlocked() ?? (this.lens.canWorkIncidents() ? null : needsPermission('canWorkIncidents')),
    );
    readonly opened = signal<Record<string, Opened>>({});

    constructor() {
        effect(() => {
            this.reload();
            this.load(this.profile(), this.run(), this.bandFilter());
        });
    }

    load(profile: string, run: 'latest' | 'all' = 'latest', band: InsightBand | null = null): void {
        this.loading.set(true);
        this.error.set(null);
        this.feed.set(null);
        this.api.list(profile, { run, band }).subscribe({
            next: (f) => {
                this.feed.set(f);
                this.loading.set(false);
                const h = this.highlight();
                if (h)
                    afterNextRender(
                        () => document.getElementById('insight-' + h)?.scrollIntoView?.({ block: 'center' }),
                        { injector: this.injector },
                    );
            },
            error: (e) => {
                // 404: no run yet, or the Insights Dataset is not visible — the empty state explains both.
                if (e?.status !== 404) this.error.set(apiErrorMessage(e, 'Could not load the Insights.'));
                this.loading.set(false);
            },
        });
    }

    /** `POST /cases/from-entities` with the Insight as its one member, so the Case carries the evidence. */
    private caseFor(i: Insight) {
        const e = insightEvidence(i, this.profile(), window.location.origin);
        return this.objects.openCaseFromEntities(insightCaseTitle(e), insightCaseDescription(e), [
            insightCaseMember(e),
        ]);
    }

    openCase(i: Insight): void {
        this.busy.set(i.insight_id);
        this.caseFor(i).subscribe({
            next: (made) => {
                this.busy.set(null);
                this.remember(i, { caseId: made.case.id });
            },
            error: (e) => {
                this.busy.set(null);
                this.toastr.error(apiErrorMessage(e, 'The Case was not opened.'));
            },
        });
    }

    /** Ask for the Connection, open the Case, then raise the Action Request from it (`POST /action-requests`). */
    requestAction(i: Insight): void {
        const e = insightEvidence(i, this.profile(), window.location.origin);
        this.dialog
            .open<InsightActionRequestDialog, unknown, InsightActionRequestResult>(InsightActionRequestDialog, {
                data: { title: insightCaseTitle(e) },
            })
            .afterClosed()
            .subscribe((choice) => {
                if (!choice) return;
                this.busy.set(i.insight_id);
                let caseId = '';
                this.caseFor(i)
                    .pipe(
                        switchMap((made) => {
                            caseId = made.case.id;
                            return this.actionRequests.propose({
                                connection: choice.connection,
                                payloadTemplate: insightActionPayload(e),
                                caseId,
                                reason: choice.reason || undefined,
                            });
                        }),
                    )
                    .subscribe({
                        next: (ar) => {
                            this.busy.set(null);
                            this.remember(i, { caseId, actionRequestId: ar.id });
                        },
                        error: (err) => {
                            this.busy.set(null);
                            // The Case may exist already: show it, so a retry does not open a second one blindly.
                            if (caseId) this.remember(i, { caseId });
                            this.toastr.error(
                                apiErrorMessage(
                                    err,
                                    caseId
                                        ? 'The Case was opened, but the Action Request was not raised.'
                                        : 'The Case was not opened.',
                                ),
                            );
                        },
                    });
            });
    }

    private remember(i: Insight, o: Opened): void {
        this.opened.update((m) => ({ ...m, [i.insight_id]: o }));
    }

    statusWords(i: Insight): string {
        return STATUS_WORDS[i.status ?? 'new'] ?? String(i.status);
    }

    give(i: Insight, status: InsightFeedback): void {
        this.busy.set(i.insight_id);
        this.api.feedback(this.profile(), i.insight_id, status).subscribe({
            next: () => {
                this.busy.set(null);
                const f = this.feed();
                if (f)
                    this.feed.set({
                        ...f,
                        insights: f.insights.map((x) => (x.insight_id === i.insight_id ? { ...x, status } : x)),
                    });
                this.toastr.success('Thanks — the next scan applies it.');
            },
            error: (e) => {
                this.busy.set(null);
                this.toastr.error(apiErrorMessage(e, 'The feedback was not saved.'));
            },
        });
    }
}
