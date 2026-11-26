import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, needsPermission } from 'app/inspecto/api/api-base';
import { Insight, InsightFeed, InsightFeedback, InsightsService } from 'app/inspecto/api/insights.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { bandBadge, sliceLabel } from 'app/inspecto/insights/insight-profile-form';

const FEEDBACK: { status: InsightFeedback; label: string }[] = [
    { status: 'useful', label: 'Useful' },
    { status: 'not_useful', label: 'Not useful' },
    { status: 'expected', label: 'Expected' },
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
        @if (loading()) {
            <inspecto-skeleton [lines]="3"></inspecto-skeleton>
        } @else if (error(); as e) {
            <p class="text-warn text-sm" role="alert">{{ e }}</p>
        } @else if (feed(); as f) {
            @if (f.insights.length === 0) {
                <p class="text-secondary text-sm">The latest run ({{ f.latestRun }}) found no Insight.</p>
            } @else {
                <p class="text-secondary mb-2 text-xs">
                    Latest run {{ f.latestRun }} · {{ f.insights.length }} of {{ f.total }} Insights, best score first.
                </p>
                <ul class="flex flex-col gap-3" aria-label="Insights">
                    @for (i of f.insights; track i.insight_id) {
                        <li class="rounded-lg border p-3" data-testid="insight">
                            <div class="flex flex-wrap items-center gap-2">
                                <inspecto-status-badge [value]="band(i).tone" [label]="band(i).label" />
                                <span class="text-sm font-semibold">{{ slice(i) }}</span>
                                <span class="text-secondary text-xs">{{ i.insight_type }} · {{ i.period }}</span>
                                <span class="text-secondary ml-auto text-xs tabular-nums">Score {{ i.score }}</span>
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
                            </div>
                        </li>
                    }
                </ul>
                @if (!canFeedback()) {
                    <p class="text-secondary mt-2 text-xs">{{ noFeedbackReason }}</p>
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

    readonly profile = input.required<string>();
    readonly feedbackChoices = FEEDBACK;
    readonly noFeedbackReason = needsPermission('canAuthorWorkbench');
    readonly canFeedback = this.lens.canAuthorWorkbench;
    readonly loading = signal(true);
    readonly error = signal<string | null>(null);
    readonly feed = signal<InsightFeed | null>(null);
    readonly busy = signal<string | null>(null);
    readonly band = (i: Insight) => bandBadge(i.band);
    readonly slice = sliceLabel;

    constructor() {
        effect(() => this.load(this.profile()));
    }

    load(profile: string): void {
        this.loading.set(true);
        this.error.set(null);
        this.feed.set(null);
        this.api.list(profile).subscribe({
            next: (f) => {
                this.feed.set(f);
                this.loading.set(false);
            },
            error: (e) => {
                // 404: no run yet, or the Insights Dataset is not visible — the empty state explains both.
                if (e?.status !== 404) this.error.set(apiErrorMessage(e, 'Could not load the Insights.'));
                this.loading.set(false);
            },
        });
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
