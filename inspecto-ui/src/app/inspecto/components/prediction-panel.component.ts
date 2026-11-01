import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { ComponentsService } from '../api/components.service';
import { Prediction, PredictionKind, PredictionsService } from '../api/predictions.service';
import { bandBadge, stampLabel } from '../anomaly/anomaly-score-view';
import { AnomalyBand } from '../api/anomaly-scores.service';
import { displayEntityKey } from '../risk/risk-score-view';
import { ForecastChartComponent } from './forecast-chart.component';
import { StatusBadgeComponent } from './status-badge.component';

const KINDS: PredictionKind[] = ['segmentation-model', 'churn-model', 'forecast-model'];

/**
 * The **entity prediction panel** (PREDICTIVE-SURFACE-1, the shape of `<inspecto-anomaly-panel>`): for one entity key,
 * one block per Predictive model that HAS a prediction for it — its Customer Segment with the R/F/M scores, its Churn
 * Score with the band, or (when the key is a forecast series) the forecast chart. The key is masked by the server
 * unless the caller may reveal it (`keyMasked`).
 *
 * Every failure renders nothing: a 404 is "no prediction", and a 503 / unknown kind means the predictive module is
 * not installed — the panel hides rather than alarming.
 */
@Component({
    selector: 'inspecto-prediction-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [DecimalPipe, StatusBadgeComponent, ForecastChartComponent],
    template: `
        @for (p of predictions(); track p.kind + '/' + p.model) {
            <section class="mb-3 rounded-2xl border p-4" [attr.aria-label]="title(p) + ', ' + p.model">
                <div class="flex flex-wrap items-center gap-2">
                    <span class="text-sm font-semibold">{{ title(p) }}</span>
                    @switch (p.kind) {
                        @case ('segmentation-model') {
                            <span class="text-lg font-semibold">{{ p.segment }}</span>
                            <span class="text-secondary text-xs tabular-nums">
                                R {{ p.recencyScore ?? '—' }} · F {{ p.frequencyScore ?? '—' }} · M
                                {{ p.monetaryScore ?? '—' }}
                            </span>
                        }
                        @case ('churn-model') {
                            @if (p.score == null) {
                                <span class="text-secondary text-sm">Not enough history</span>
                            } @else {
                                <span class="text-lg font-semibold tabular-nums">{{ p.score | number: '1.0-1' }}</span>
                                <inspecto-status-badge [value]="badge(p).value" [label]="badge(p).label" />
                            }
                        }
                    }
                </div>
                <p class="text-secondary mt-1 text-xs">
                    {{ p.model }}. As of <span [attr.title]="p.asOf">{{ stamp(p.asOf, true) }}</span
                    >. Computed <span [attr.title]="p.scoredAt">{{ stamp(p.scoredAt) }}</span>
                </p>
                <p class="mt-1 text-xs">
                    <span class="text-secondary">{{ p.entityType || 'Entity' }}</span>
                    <span class="ml-1 font-mono">{{ shownKey(p) }}</span>
                </p>
                @if (p.kind === 'forecast-model') {
                    <inspecto-forecast-chart class="mt-3 block" [model]="p.model" [points]="p.points ?? []" />
                }
            </section>
        }
    `,
})
export class PredictionPanelComponent {
    private api = inject(PredictionsService);
    private components = inject(ComponentsService);

    readonly entityKey = input.required<string>();

    readonly predictions = signal<Prediction[]>([]);
    readonly stamp = stampLabel;

    constructor() {
        effect((onCleanup) => {
            const key = this.entityKey();
            this.predictions.set([]);
            const reads: Observable<(Prediction | null)[]>[] = KINDS.map((kind) =>
                this.components.list(kind).pipe(
                    map((defs) => defs.map((d) => d.name)),
                    catchError(() => of([] as string[])),
                    switchMap((ids) =>
                        ids.length
                            ? forkJoin(ids.map((id) => this.api.latest(kind, id, key).pipe(catchError(() => of(null)))))
                            : of([]),
                    ),
                ),
            );
            const sub = forkJoin(reads)
                .pipe(catchError(() => of([] as (Prediction | null)[][])))
                .subscribe((rows) => this.predictions.set(rows.flat().filter((r): r is Prediction => !!r)));
            onCleanup(() => sub.unsubscribe());
        });
    }

    title(p: Prediction): string {
        return p.kind === 'segmentation-model' ? 'Customer Segment' : p.kind === 'churn-model' ? 'Churn Score' : 'Forecast';
    }

    badge(p: Prediction) {
        return bandBadge((p.band ?? 'normal') as AnomalyBand);
    }

    /** A masked token shows as-is; a raw key is dotted down to its last 4. */
    shownKey(p: Prediction): string {
        return p.keyMasked ? p.entityKey : displayEntityKey(p.entityKey);
    }
}
