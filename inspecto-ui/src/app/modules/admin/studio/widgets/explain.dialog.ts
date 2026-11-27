import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { InsightsService, ExplainDriver, ExplainResult } from 'app/inspecto/api/insights.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { getViz, VizPlugin, VizProps } from 'app/inspecto/viz';
import { formatNumber } from 'app/inspecto/viz/number-format';
import { VizRenderComponent } from 'app/inspecto/viz/viz-render.component';
import type { ExplainRequest } from 'app/inspecto/viz/explain-request';

export interface ExplainDialogData {
    title: string;
    request: ExplainRequest;
}

/** The sentence a driver dimension reads as: "E and N explain 110 % of the change." */
export function driverSentence(d: ExplainDriver): string {
    const names = d.cover.map((v) => (v == null ? '(blank)' : v));
    const list = names.length <= 1 ? names.join('') : names.slice(0, -1).join(', ') + ' and ' + names[names.length - 1];
    const pct = d.covered == null ? '' : formatNumber(d.covered * 100, { decimals: 0 }) + ' %';
    return d.explains
        ? `${list} ${names.length === 1 ? 'explains' : 'explain'} ${pct} of the change.`
        : 'No small set of values explains the change.';
}

/**
 * BI-INSIGHTS-1 P2 — "Explain this change" for one Widget: the change between its last two periods, the dimensions whose
 * values carry it (I-4), for a ratio the mix / rate bridge on the waterfall Visualization Type (I-5), and the Measures
 * that moved with it (I-6, worded as moving together, never as a cause). Reads only; one call to `POST /insights/explain`.
 */
@Component({
    selector: 'app-explain-dialog',
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatDialogModule, MatButtonModule, InspectoAlertComponent, InspectoSkeletonComponent, VizRenderComponent],
    template: `
        <h2 mat-dialog-title>Explain this change — {{ data.title }}</h2>
        <mat-dialog-content class="flex flex-col gap-5" data-testid="explain-dialog">
            @if (error(); as e) {
                <inspecto-alert variant="warning" title="Cannot explain this change">{{ e }}</inspecto-alert>
            } @else if (result(); as r) {
                <p class="text-base" data-testid="explain-headline">
                    <strong>{{ r.measure }}</strong> went from {{ num(r.before) }} in {{ r.previous }} to
                    {{ num(r.after) }} in {{ r.current }}: {{ signed(r.delta) }}{{ r.pct == null ? '' : ' (' + signed(r.pct) + ' %)' }}.
                </p>

                <section aria-labelledby="explain-drivers">
                    <h3 id="explain-drivers" class="text-sm font-semibold mb-2">What explains it</h3>
                    @for (d of r.drivers; track d.dimension) {
                        <div class="mb-4" data-testid="explain-driver">
                            <p class="text-sm">
                                <span class="font-semibold">{{ d.dimension }}</span>: {{ sentence(d) }}
                            </p>
                            <table class="w-full text-sm tabular-nums mt-1">
                                <caption class="sr-only">Values of {{ d.dimension }} by their share of the change</caption>
                                <thead>
                                    <tr class="text-secondary text-xs">
                                        <th scope="col" class="text-left font-medium">Value</th>
                                        <th scope="col" class="text-right font-medium">{{ r.previous }}</th>
                                        <th scope="col" class="text-right font-medium">{{ r.current }}</th>
                                        <th scope="col" class="text-right font-medium">Change</th>
                                        <th scope="col" class="text-right font-medium">Share of the change</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    @for (e of d.elements.slice(0, 6); track $index) {
                                        <tr [class.font-semibold]="d.cover.includes(e.value)">
                                            <td>{{ e.value ?? '(blank)' }}</td>
                                            <td class="text-right">{{ num(e.before) }}</td>
                                            <td class="text-right">{{ num(e.after) }}</td>
                                            <td class="text-right">{{ signed(e.contribution) }}</td>
                                            <td class="text-right">{{ e.share == null ? '—' : num(e.share * 100) + ' %' }}</td>
                                        </tr>
                                    }
                                </tbody>
                            </table>
                            @if (d.valueCount > 6) {
                                <p class="text-secondary text-xs">The 6 largest of {{ d.valueCount }} values.</p>
                            }
                        </div>
                    }
                    @for (x of r.refused; track x.dimension) {
                        <p class="text-secondary text-xs">{{ x.dimension }}: not explained — {{ x.reason }}.</p>
                    }
                </section>

                @if (bridge(); as b) {
                    <section aria-labelledby="explain-mix">
                        <h3 id="explain-mix" class="text-sm font-semibold mb-1">Mix and rate, by {{ b.dimension }}</h3>
                        <p class="text-sm mb-2" data-testid="explain-mix-sentence">
                            The mix of {{ b.dimension }} moved the ratio by {{ signed(b.mix) }}; the rates within each value
                            moved it by {{ signed(b.rate) }}.
                        </p>
                        @if (waterfall(); as w) {
                            <div class="h-56">
                                <inspecto-viz-render
                                    [plugin]="w"
                                    [props]="b.props"
                                    title="mix and rate"
                                    [renderOptions]="{ waterfall: { start: 'Before', totalLabel: 'After' } }"
                                />
                            </div>
                        }
                    </section>
                }

                @if (r.movedWith.length) {
                    <section aria-labelledby="explain-moved">
                        <h3 id="explain-moved" class="text-sm font-semibold mb-1">Moved with it</h3>
                        <ul class="text-sm" data-testid="explain-moved">
                            @for (m of r.movedWith; track m.measure) {
                                <li>
                                    <span class="font-semibold">{{ m.measure }}</span>:
                                    @if (m.r == null) {
                                        <span class="text-secondary">{{ m.reason }}</span>
                                    } @else {
                                        {{ m.movedWith ? (m.r > 0 ? 'rose and fell with it' : 'moved the opposite way') : 'did not move with it' }}
                                        (correlation {{ num(m.r) }} over {{ m.pairs }} changes)
                                    }
                                </li>
                            }
                        </ul>
                        <p class="text-secondary text-xs mt-1">{{ r.movedWithNote }}</p>
                    </section>
                }

                @if (r.kind !== 'ratio') {
                    <section aria-labelledby="explain-pairs" data-testid="explain-pairs">
                        <h3 id="explain-pairs" class="text-sm font-semibold mb-1">Pairs of dimensions</h3>
                        @if (pairsError(); as pe) {
                            <inspecto-alert variant="warning" title="Pairs not shown">{{ pe }}</inspecto-alert>
                        } @else if (pairsResult(); as pr) {
                            @if (pr.pairs; as p) {
                                <p class="text-secondary text-xs mb-2" data-testid="explain-pairs-source">
                                    Stored by the scan of Insight Profile {{ p.profile }} at {{ p.computedAt }}, for
                                    {{ p.previous }} to {{ p.current }}.
                                </p>
                                @if (p.stale) {
                                    <inspecto-alert variant="warning" title="The data changed since this scan" data-testid="explain-pairs-stale">
                                        The stored totals no longer match today’s figures: the data was restated after the
                                        scan ran. Run the Profile’s scan again for current pairs.
                                    </inspecto-alert>
                                }
                                @for (d of p.drivers; track d.dimension) {
                                    <p class="text-sm mb-1" data-testid="explain-pair-driver">
                                        <span class="font-semibold">{{ d.dimension }}</span>: {{ sentence(d) }}
                                    </p>
                                } @empty {
                                    <p class="text-sm">No pair of dimensions was scored.</p>
                                }
                                @for (x of p.refused; track x.dimension) {
                                    <p class="text-secondary text-xs" data-testid="explain-pair-refused">
                                        {{ x.dimension }}: not explained — {{ x.reason }}.
                                    </p>
                                }
                            } @else {
                                <p class="text-secondary text-sm" data-testid="explain-pairs-note">{{ pr.pairsNote }}</p>
                            }
                        } @else {
                            <button mat-stroked-button type="button" [disabled]="pairsLoading()" (click)="loadPairs()">
                                Look at pairs of dimensions
                            </button>
                        }
                    </section>
                }
            } @else {
                <inspecto-skeleton />
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class ExplainDialogComponent {
    readonly data = inject<ExplainDialogData>(MAT_DIALOG_DATA);
    private insights = inject(InsightsService);

    readonly result = signal<ExplainResult | null>(null);
    readonly error = signal<string | null>(null);
    readonly waterfall = signal<VizPlugin | null>(getViz('waterfall') ?? null);
    readonly pairsResult = signal<ExplainResult | null>(null);
    readonly pairsError = signal<string | null>(null);
    readonly pairsLoading = signal(false);

    /** Depth 2 on demand: a second call reads what an Insight Profile's scan stored (plan §11.12). */
    loadPairs(): void {
        this.pairsLoading.set(true);
        this.insights.explain({ ...this.data.request, depth: 2 }).subscribe({
            next: (r) => {
                this.pairsResult.set(r);
                this.pairsLoading.set(false);
            },
            error: (e: unknown) => {
                this.pairsError.set(apiErrorMessage(e, 'The pairs of dimensions could not be read.'));
                this.pairsLoading.set(false);
            },
        });
    }

    /** I-5: the first dimension's mix / rate split as a waterfall: Before → Mix → Rate → After. */
    readonly bridge = computed(() => {
        const r = this.result();
        const m = r?.mixRate?.[0];
        if (!r || !m) return null;
        const props: VizProps = { labels: ['Before', 'Mix', 'Rate'], series: [{ label: r.measure, data: [m.before, m.mix, m.rate] }] };
        return { dimension: m.dimension, mix: m.mix, rate: m.rate, props };
    });

    constructor() {
        this.insights.explain(this.data.request).subscribe({
            next: (r) => this.result.set(r),
            error: (e: unknown) => this.error.set(apiErrorMessage(e, 'The change could not be explained.')),
        });
    }

    sentence(d: ExplainDriver): string {
        return driverSentence(d);
    }

    num(v: number | null | undefined): string {
        return v == null ? '—' : formatNumber(v, { decimals: 2 });
    }

    signed(v: number): string {
        return (v > 0 ? '+' : v < 0 ? '−' : '') + this.num(Math.abs(v));
    }
}
