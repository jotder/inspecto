import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Observable, catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { AnomalyScore, AnomalyScoresService } from '../api/anomaly-scores.service';
import { ComponentsService } from '../api/components.service';
import { bandBadge, rankedFeatures, sparklinePoints } from '../anomaly/anomaly-score-view';
import { displayEntityKey } from '../risk/risk-score-view';
import { StatusBadgeComponent } from './status-badge.component';

/** Features shown before "Show all" (design §5: the top 3, the rest on demand). */
const TOP_FEATURES = 3;
const SPARK_W = 120;
const SPARK_H = 24;

/**
 * The **entity anomaly panel** (ANOMALY-DETECTION-1 S5, design §13.2): an entity's latest Anomaly Score with its band,
 * the features ranked by contribution (baseline vs observed + the generated `reason`), a sparkline of the last runs
 * and the key — masked by the server unless the caller may reveal it (`keyMasked`).
 *
 * With `model` it reads that one model. Without it (a host that knows only the entity) it lists the Anomaly Models
 * and shows one block per model that HAS a score for the entity. Every failure renders nothing: a 404 is "no score",
 * and a 503 / unknown kind means the anomaly module is not installed — the panel hides rather than alarming.
 */
@Component({
    selector: 'inspecto-anomaly-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [DecimalPipe, MatButtonModule, StatusBadgeComponent],
    template: `
        @for (s of scores(); track s.model) {
            <section class="mb-3 rounded-2xl border p-4" [attr.aria-label]="'Anomaly Score, ' + s.model">
                <div class="flex flex-wrap items-center gap-2">
                    <span class="text-sm font-semibold">Anomaly Score</span>
                    <span class="text-lg font-semibold tabular-nums">{{ s.score | number: '1.0-1' }}</span>
                    <inspecto-status-badge [value]="badge(s).value" [label]="badge(s).label" />
                    @if (spark(s); as pts) {
                        <svg
                            class="text-primary"
                            [attr.width]="sparkW"
                            [attr.height]="sparkH"
                            [attr.viewBox]="'0 0 ' + sparkW + ' ' + sparkH"
                            role="img"
                            [attr.aria-label]="sparkLabel(s)"
                        >
                            <polyline fill="none" stroke="currentColor" stroke-width="1.5" [attr.points]="pts" />
                        </svg>
                    }
                </div>
                <p class="text-secondary mt-1 text-xs">
                    {{ s.model }} · elevated at {{ s.elevatedThreshold }}, high at {{ s.highThreshold }} · period
                    {{ s.periodStart }} · scored {{ s.scoredAt }}
                </p>
                <p class="mt-1 text-xs">
                    <span class="text-secondary">{{ s.entityType || 'Entity' }}</span>
                    <span class="ml-1 font-mono">{{ shownKey(s) }}</span>
                    @if (!s.keyMasked) {
                        <button
                            mat-button
                            type="button"
                            class="ml-1"
                            [attr.aria-pressed]="revealed()[s.model] ? 'true' : 'false'"
                            (click)="toggleReveal(s.model)"
                        >
                            {{ revealed()[s.model] ? 'Hide key' : 'Reveal key' }}
                        </button>
                    }
                </p>
                <ul class="mt-3 flex flex-col gap-2">
                    @for (f of features(s); track f.feature) {
                        <li>
                            <div class="flex justify-between gap-2 text-sm">
                                <span>
                                    {{ f.label || f.feature }}
                                    @if (f.insufficient) {
                                        <span class="text-secondary text-xs">(not enough history)</span>
                                    }
                                </span>
                                <span class="tabular-nums">{{ f.share | number: '1.0-0' }} %</span>
                            </div>
                            <div class="text-secondary text-xs tabular-nums">
                                observed {{ f.observed ?? '—' }} · baseline {{ f.baseline?.median ?? '—' }}
                                @if (f.peerBaseline?.median != null) {
                                    · peers {{ f.peerBaseline!.median }}
                                }
                            </div>
                            @if (f.reason) {
                                <p class="text-xs">{{ f.reason }}</p>
                            }
                        </li>
                    }
                </ul>
                @if (s.features.length > top) {
                    <button
                        mat-button
                        type="button"
                        class="mt-1"
                        [attr.aria-expanded]="expanded()[s.model] ? 'true' : 'false'"
                        (click)="toggleAll(s.model)"
                    >
                        {{ expanded()[s.model] ? 'Show top ' + top : 'Show all ' + s.features.length + ' features' }}
                    </button>
                }
            </section>
        }
    `,
})
export class AnomalyPanelComponent {
    private api = inject(AnomalyScoresService);
    private components = inject(ComponentsService);

    readonly entityKey = input.required<string>();
    /** One model; omitted, every Anomaly Model with a score for the entity gets a block. */
    readonly model = input<string | null>(null);

    readonly scores = signal<AnomalyScore[]>([]);
    readonly expanded = signal<Record<string, boolean>>({});
    readonly revealed = signal<Record<string, boolean>>({});
    readonly top = TOP_FEATURES;
    readonly sparkW = SPARK_W;
    readonly sparkH = SPARK_H;
    readonly badge = (s: AnomalyScore) => bandBadge(s.band);

    constructor() {
        effect((onCleanup) => {
            const key = this.entityKey();
            const model = this.model();
            this.scores.set([]);
            const models$: Observable<string[]> = model
                ? of([model])
                : this.components.list('anomaly-model').pipe(map((defs) => defs.map((d) => d.name)));
            const sub = models$
                .pipe(
                    switchMap((ids) =>
                        ids.length
                            ? forkJoin(ids.map((id) => this.api.latest(id, key).pipe(catchError(() => of(null)))))
                            : of([]),
                    ),
                    catchError(() => of([])),
                )
                .subscribe((rows) => this.scores.set(rows.filter((r): r is AnomalyScore => !!r)));
            onCleanup(() => sub.unsubscribe());
        });
    }

    features(s: AnomalyScore) {
        const ranked = rankedFeatures(s.features ?? []);
        return this.expanded()[s.model] ? ranked : ranked.slice(0, TOP_FEATURES);
    }

    spark(s: AnomalyScore): string {
        return sparklinePoints(s.history ?? [], SPARK_W, SPARK_H);
    }

    sparkLabel(s: AnomalyScore): string {
        const h = s.history ?? [];
        const oldest = h[h.length - 1]?.score ?? 0;
        return `Score over the last ${h.length} runs, from ${Math.round(oldest)} to ${Math.round(s.score)}`;
    }

    /** A masked token shows as-is; a raw key is dotted down to its last 4 until revealed. */
    shownKey(s: AnomalyScore): string {
        if (s.keyMasked || this.revealed()[s.model]) return s.entityKey;
        return displayEntityKey(s.entityKey);
    }

    toggleAll(model: string): void {
        this.expanded.update((e) => ({ ...e, [model]: !e[model] }));
    }

    toggleReveal(model: string): void {
        this.revealed.update((r) => ({ ...r, [model]: !r[model] }));
    }
}
