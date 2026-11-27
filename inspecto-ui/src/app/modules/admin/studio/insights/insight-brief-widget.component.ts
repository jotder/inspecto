import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { BriefProfile, InsightsService, NarratedProfile } from 'app/inspecto/api/insights.service';
import { SessionService } from 'app/inspecto/auth/session.service';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { bandBadge } from 'app/inspecto/insights/insight-profile-form';

/**
 * The **Dashboard Brief** tile (BI-INSIGHTS-1 P4 BR-3, Visualization Type `insight-brief`): one Insight Profile's top
 * Insights as plain sentences, read from `GET /insights/brief` as the viewer may see them. The **Narrate** button (only
 * when `/bootstrap` reports `agent`) asks `POST /insights/brief/narrate`; its paragraph sits ABOVE the cited lines, each
 * sentence marked when it is the Insight's own template, and is held in the tile, never saved. Every sentence is plain
 * text (interpolation only: no markdown or HTML from a model). Loaded lazily (`widget.kind`).
 */
@Component({
    selector: 'app-insight-brief-widget',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block h-full max-h-96 min-h-0 overflow-y-auto' },
    imports: [
        RouterLink,
        ChipComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    template: `
        @if (!viewId()) {
            <p class="text-secondary text-sm">Pick an Insight Profile for this tile.</p>
        } @else if (loading()) {
            <inspecto-skeleton [lines]="3"></inspecto-skeleton>
        } @else if (error(); as e) {
            <p class="text-warn text-sm" role="alert">{{ e }}</p>
        } @else if (brief(); as b) {
            @if (b.note) {
                <p class="text-secondary mb-2 text-xs">{{ b.note }}</p>
            }
            @if (narration(); as n) {
                <section class="mb-3" data-testid="brief-narration" aria-label="Narrated Brief">
                    <p class="text-secondary mb-1 text-xs">
                        {{ n.modelBacked ? 'Model-written, checked against the Insights' : 'The Brief’s own sentences' }}
                        @if (n.reason === 'no model') {
                            · No model is available.
                        } @else if (n.reason) {
                            · {{ n.reason }}
                        }
                    </p>
                    <ul class="flex flex-col gap-1">
                        @for (s of n.sentences; track s.n) {
                            <li class="text-sm" data-testid="narrated-sentence">
                                {{ s.text }}
                                @if (s.source === 'template') {
                                    <inspecto-chip variant="outline">Template</inspecto-chip>
                                }
                            </li>
                        }
                    </ul>
                </section>
            }
            @if (b.lines.length === 0) {
                <p class="text-sm">{{ b.summary || 'Nothing unusual in the latest run.' }}</p>
            } @else {
                <ul class="flex flex-col gap-2" [attr.aria-label]="'Brief of ' + b.profile">
                    @for (l of b.lines; track l.insightId) {
                        <li class="border-b pb-2 last:border-b-0" data-testid="brief-line">
                            <div class="flex flex-wrap items-center gap-2">
                                <inspecto-status-badge [value]="band(l.band).tone" [label]="band(l.band).label" />
                                <span class="text-sm">{{ l.sentence }}</span>
                            </div>
                            <a
                                class="text-primary text-xs underline"
                                routerLink="/studio/insights"
                                [queryParams]="{ profile: b.profile, insight: l.insightId }"
                                [attr.aria-label]="'Open the evidence for ' + l.sentence"
                                >Evidence</a
                            >
                        </li>
                    }
                </ul>
                @if (canNarrate()) {
                    <button
                        type="button"
                        class="text-primary mt-2 text-sm underline"
                        data-testid="narrate"
                        [disabled]="narrating()"
                        (click)="narrate()"
                    >
                        {{ narrating() ? 'Narrating…' : 'Narrate' }}
                    </button>
                }
                @if (narrateError(); as ne) {
                    <p class="text-warn mt-1 text-xs" role="alert">{{ ne }}</p>
                }
            }
        } @else {
            <inspecto-empty-state
                icon="heroicons_outline:light-bulb"
                title="No Insights yet"
                message="An insight.scan Job writes the Insights of {{
                    viewId()
                }}. Once it has run, its Brief shows here (if you may view its Insights Dataset)."
            ></inspecto-empty-state>
        }
    `,
})
export class InsightBriefWidgetComponent {
    private api = inject(InsightsService);
    private session = inject(SessionService);

    /** The Insight Profile id (the Widget's binding). */
    readonly viewId = input<string | undefined>(undefined);
    /** Passed by the share viewer and the Working Set host; unused here. */
    readonly allowExport = input<boolean>(false);
    readonly binding = input<unknown>(undefined);

    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    readonly brief = signal<BriefProfile | null>(null);
    readonly narration = signal<NarratedProfile | null>(null);
    readonly narrating = signal(false);
    readonly narrateError = signal<string | null>(null);
    /** AI Assist answered 503: the button goes, the templated Brief stays. */
    private readonly agentGone = signal(false);
    readonly band = bandBadge;

    readonly canNarrate = computed(
        () => this.session.features()['agent'] === true && !this.agentGone() && (this.brief()?.lines.length ?? 0) > 0,
    );

    constructor() {
        effect(() => {
            const id = this.viewId();
            if (id) this.load(id);
        });
    }

    private load(profile: string): void {
        this.loading.set(true);
        this.error.set(null);
        this.brief.set(null);
        this.narration.set(null);
        this.narrateError.set(null);
        this.api.brief([profile]).subscribe({
            next: (r) => {
                this.brief.set(r.profiles.find((p) => p.profile === profile) ?? null);
                this.loading.set(false);
            },
            error: (e) => {
                if (e?.status !== 404) this.error.set(apiErrorMessage(e, 'Could not load the Brief.'));
                this.loading.set(false);
            },
        });
    }

    narrate(): void {
        const id = this.viewId();
        if (!id || this.narrating()) return;
        this.narrating.set(true);
        this.narrateError.set(null);
        this.api.narrate([id]).subscribe({
            next: (r) => {
                this.narration.set(r.narration.profiles.find((p) => p.profile === id) ?? null);
                this.narrating.set(false);
            },
            error: (e) => {
                this.narrating.set(false);
                if (e?.status === 503) {
                    this.agentGone.set(true);
                    this.narrateError.set('AI Assist is not available, so the Brief stays as the Insights state it.');
                } else {
                    this.narrateError.set(apiErrorMessage(e, 'Could not narrate the Brief.'));
                }
            },
        });
    }
}
