import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { RiskScore, RiskScoresService } from '../api/risk-scores.service';
import { displayEntityKey } from '../risk/risk-score-view';
import { apiErrorMessage } from '../api/api-base';
import { InspectoAlertComponent } from './alert.component';
import { StatusBadgeComponent } from './status-badge.component';

/**
 * A Risk Score's factor breakdown (ASSURE-RISK-SCORE-1): the score, and one contribution bar per factor, each
 * bar RELATIVE to the largest contribution. Loads `GET /risk-scores/{model}/{entityKey}`; renders nothing when
 * there is no visible score (a 404 is "no score", not an error — the route hides out-of-scope models that way).
 * With `explainAbsence` (the Risk Scores lookup) it says so instead: a 404 renders an explained empty line and any
 * other failure an error alert.
 */
@Component({
    selector: 'inspecto-risk-score-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoAlertComponent, DecimalPipe, StatusBadgeComponent],
    template: `
        @if (score(); as s) {
            <section class="rounded-2xl border p-4" [attr.aria-label]="'Risk Score of ' + shownKey(s.entityKey)">
                <div class="flex items-center gap-2">
                    <span class="text-sm font-semibold">Risk Score</span>
                    <span class="text-lg font-semibold tabular-nums">{{ s.score | number: '1.0-1' }}</span>
                    <inspecto-status-badge
                        [value]="s.high ? 'error' : 'success'"
                        [label]="s.high ? 'High' : 'Below threshold'"
                    />
                    <span class="text-secondary text-xs">
                        {{ s.model }} · high at {{ s.highThreshold }} · {{ s.scoredAt }}
                    </span>
                </div>
                <ul class="mt-3 flex flex-col gap-2">
                    @for (f of rows(); track f.indicator) {
                        <li>
                            <div class="flex justify-between text-sm">
                                <span>
                                    {{ f.label || f.indicator }}
                                    @if (f.missing) {
                                        <span class="text-secondary text-xs">(no value, counted as 0)</span>
                                    }
                                    @if (f.capped) {
                                        <span class="text-secondary text-xs">(capped at {{ f.cap }})</span>
                                    }
                                </span>
                                <span class="tabular-nums">
                                    {{ f.contribution | number: '1.0-2' }}
                                    <span class="text-secondary text-xs">
                                        = {{ f.weight }} × {{ f.value ?? 0 | number: '1.0-2' }}
                                    </span>
                                </span>
                            </div>
                            <div class="bg-hover mt-1 h-2 rounded-full" aria-hidden="true">
                                <span class="bg-primary block h-2 rounded-full" [style.width.%]="f.pct"></span>
                            </div>
                            <span class="sr-only">{{ f.pct | number: '1.0-0' }} % of the largest contribution</span>
                        </li>
                    }
                </ul>
            </section>
        } @else if (explainAbsence()) {
            @if (loadError(); as e) {
                <inspecto-alert variant="error">{{ e }}</inspecto-alert>
            } @else if (absent()) {
                <p class="text-secondary text-sm" role="status">
                    No stored score for this entity yet — the risk.score Job has not scored it. Use Preview to score it
                    now.
                </p>
            }
        }
    `,
})
export class RiskScorePanelComponent {
    private api = inject(RiskScoresService);

    readonly model = input.required<string>();
    readonly entityKey = input.required<string>();
    /** A ready score to render instead of loading the latest one (the S3 preview). */
    readonly preset = input<RiskScore | null>(null);

    /** Explain a missing score (404) and surface other failures, instead of rendering nothing. */
    readonly explainAbsence = input(false);

    readonly score = signal<RiskScore | null>(null);
    readonly absent = signal(false);
    readonly loadError = signal<string | null>(null);
    readonly shownKey = displayEntityKey;

    readonly rows = computed(() => {
        const s = this.score();
        if (!s) return [];
        const max = Math.max(0, ...s.factors.map((f) => Math.abs(f.contribution)));
        return s.factors.map((f) => ({ ...f, pct: max > 0 ? (Math.abs(f.contribution) / max) * 100 : 0 }));
    });

    constructor() {
        effect((onCleanup) => {
            const model = this.model();
            const key = this.entityKey();
            const preset = this.preset();
            this.score.set(preset);
            this.absent.set(false);
            this.loadError.set(null);
            if (preset) return;
            const sub = this.api.latest(model, key).subscribe({
                next: (s) => this.score.set(s),
                error: (err) => {
                    this.score.set(null);
                    if (err?.status === 404) this.absent.set(true);
                    else this.loadError.set(apiErrorMessage(err, 'Could not load the latest score.'));
                },
            });
            onCleanup(() => sub.unsubscribe());
        });
    }
}
