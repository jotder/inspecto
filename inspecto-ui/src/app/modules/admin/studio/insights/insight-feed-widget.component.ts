import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { InsightFeed, InsightsService } from 'app/inspecto/api/insights.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { bandBadge, sliceLabel } from 'app/inspecto/insights/insight-profile-form';

/** How many Insights a tile lists; the rest are one link away on the Insight Profiles screen. */
export const INSIGHT_TILE_LIMIT = 10;

/**
 * The **Insight Feed** Dashboard tile (BI-INSIGHTS-1, Visualization Type `insight-feed`): one Insight Profile's latest
 * Insights — band badge with its word, sentence, slice and period — read from `GET /insights?profile=` as the viewer
 * may see them. A 404 (no run yet, or the Insights Dataset is not visible to this viewer) is the empty state naming the
 * `insight.scan` Job. Read-only: feedback and Cases stay on the Insight Profiles screen. Loaded lazily (`widget.kind`).
 */
@Component({
    selector: 'app-insight-feed-widget',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [RouterLink, InspectoEmptyStateComponent, InspectoSkeletonComponent, StatusBadgeComponent],
    template: `
        @if (!viewId()) {
            <p class="text-secondary text-sm">Pick an Insight Profile for this tile.</p>
        } @else if (loading()) {
            <inspecto-skeleton [lines]="3"></inspecto-skeleton>
        } @else if (error(); as e) {
            <p class="text-warn text-sm" role="alert">{{ e }}</p>
        } @else if (feed(); as f) {
            @if (f.insights.length === 0) {
                <p class="text-secondary text-sm">The latest run ({{ f.latestRun }}) found no Insight.</p>
            } @else {
                <ul class="flex flex-col gap-2" [attr.aria-label]="'Insights of ' + viewId()">
                    @for (i of f.insights; track i.insight_id) {
                        <li class="border-b pb-2 last:border-b-0" data-testid="tile-insight">
                            <div class="flex flex-wrap items-center gap-2">
                                <inspecto-status-badge [value]="band(i.band).tone" [label]="band(i.band).label" />
                                <span class="text-sm font-semibold">{{ slice(i) }}</span>
                                <span class="text-secondary text-xs">{{ i.period }}</span>
                            </div>
                            <p class="mt-1 text-sm">{{ i.explanation?.sentence || 'No sentence was stored.' }}</p>
                        </li>
                    }
                </ul>
            }
            <p class="text-secondary mt-2 text-xs">
                Latest run {{ f.latestRun }} · {{ f.insights.length }} of {{ f.total }} ·
                <a class="text-primary underline" routerLink="/studio/insights" [queryParams]="{ profile: viewId() }"
                    >Open the feed</a
                >
            </p>
        } @else {
            <inspecto-empty-state
                icon="heroicons_outline:light-bulb"
                title="No Insights yet"
                message="An insight.scan Job writes the Insights of {{
                    viewId()
                }}. Once it has run, they show here (if you may view its Insights Dataset)."
            ></inspecto-empty-state>
        }
    `,
})
export class InsightFeedWidgetComponent {
    private api = inject(InsightsService);

    /** The Insight Profile id (the Widget's binding). */
    readonly viewId = input<string | undefined>(undefined);
    /** Passed by the share viewer and the Working Set host; unused here. */
    readonly allowExport = input<boolean>(false);
    readonly binding = input<unknown>(undefined);

    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    readonly feed = signal<InsightFeed | null>(null);
    readonly band = bandBadge;
    readonly slice = sliceLabel;

    constructor() {
        effect(() => {
            const id = this.viewId();
            if (id) this.load(id);
        });
    }

    private load(profile: string): void {
        this.loading.set(true);
        this.error.set(null);
        this.feed.set(null);
        this.api.list(profile, { limit: INSIGHT_TILE_LIMIT }).subscribe({
            next: (f) => {
                this.feed.set(f);
                this.loading.set(false);
            },
            error: (e) => {
                if (e?.status !== 404) this.error.set(apiErrorMessage(e, 'Could not load the Insights.'));
                this.loading.set(false);
            },
        });
    }
}
