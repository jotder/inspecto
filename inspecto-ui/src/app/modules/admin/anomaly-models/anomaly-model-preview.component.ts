import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { BaselineStrip, baselineStrip } from 'app/inspecto/anomaly/anomaly-model-form';
import {
    AnomalyFeatureResult,
    AnomalyModelsService,
    AnomalyScorePreview,
} from 'app/inspecto/api/anomaly-models.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { formatNumber } from 'app/inspecto/viz/number-format';

/** A band's status tone + label: the badge carries the text, so the colour is never the only signal. */
export function bandBadge(band: string | null): { value: string; label: string } {
    switch (band) {
        case 'high':
            return { value: 'error', label: 'High' };
        case 'elevated':
            return { value: 'warning', label: 'Elevated' };
        case 'normal':
            return { value: 'success', label: 'Normal' };
        default:
            return { value: 'unknown', label: 'Not scored' };
    }
}

const fmt = (v: number | null | undefined, digits = 2): string =>
    v == null || !Number.isFinite(v) ? '—' : formatNumber(v, { decimals: digits });

/**
 * The preview box of the Anomaly Model pane (design §13.1): pick an entity → its score now under the SAVED model
 * (`POST /anomaly-scores/preview`, writes nothing), the per-feature table (self z, peer z, cohort shift, reason) and
 * per feature a strip of its baseline — the usual range (median ± 3 MAD), the median and the observed point.
 */
@Component({
    selector: 'app-anomaly-model-preview',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, StatusBadgeComponent],
    template: `
        <form class="mt-2 flex flex-wrap items-end gap-2" (submit)="$event.preventDefault(); run()">
            <label class="flex flex-col text-sm">
                Entity key
                <input class="rounded border px-2 py-1" autocomplete="off" [formControl]="entityDraft" />
            </label>
            <label class="flex flex-col text-sm">
                As of (optional)
                <input class="rounded border px-2 py-1" type="date" [formControl]="asOfDraft" />
            </label>
            <button mat-stroked-button type="submit" [disabled]="running()">Preview</button>
        </form>
        <p class="text-secondary mt-1 text-xs">
            Scores the entity's last full day before "as of" (default today, UTC) over the data; writes nothing.
        </p>
        @if (missingKey()) {
            <p class="text-warn mt-1 text-xs" role="alert">Enter an entity key to preview.</p>
        }
        @if (error(); as e) {
            <p class="text-warn mt-2 text-sm" role="alert">{{ e }}</p>
        }
        @if (result(); as p) {
            <div class="mt-3" data-testid="anomaly-preview">
                @if (!p.found) {
                    <p class="text-secondary text-sm">No Feature names this entity in the window; nothing to score.</p>
                } @else {
                    <div class="flex flex-wrap items-center gap-3">
                        <span class="text-2xl font-semibold tabular-nums">{{ fmt(p.score, 1) }}</span>
                        <inspecto-status-badge
                            [value]="badge(p.band).value"
                            [label]="badge(p.band).label"
                        ></inspecto-status-badge>
                        <span class="text-secondary text-sm">
                            {{ p.entityKey }} · day {{ p.periodStart }} · Elevated ≥ {{ p.elevatedThreshold }} · High ≥
                            {{ p.highThreshold }}
                        </span>
                    </div>
                    <table class="mt-3 w-full text-left text-sm">
                        <caption class="sr-only">
                            Per-feature explanation of the previewed Anomaly Score
                        </caption>
                        <thead>
                            <tr class="border-b">
                                <th scope="col">Feature</th>
                                <th scope="col">Observed</th>
                                <th scope="col">Self z</th>
                                <th scope="col">Peer z</th>
                                <th scope="col">Cohort shift</th>
                                <th scope="col">Baseline</th>
                                <th scope="col">Reason</th>
                            </tr>
                        </thead>
                        <tbody>
                            @for (f of p.features; track f.feature) {
                                <tr class="border-b align-top">
                                    <td>{{ f.label || f.feature }}</td>
                                    <td class="tabular-nums">{{ fmt(f.observed) }}</td>
                                    <td class="tabular-nums">{{ fmt(f.zSelf) }}</td>
                                    <td class="tabular-nums">{{ f.peerBaseline ? fmt(f.zPeer) : '—' }}</td>
                                    <td class="tabular-nums">
                                        {{ f.cohortShift != null && f.peerBaseline ? '×' + fmt(f.cohortShift) : '—' }}
                                    </td>
                                    <td class="w-44">
                                        @if (strip(f); as s) {
                                            <svg
                                                viewBox="0 0 160 24"
                                                class="h-6 w-40"
                                                role="img"
                                                [attr.aria-label]="stripLabel(f)"
                                            >
                                                <line
                                                    x1="0"
                                                    y1="12"
                                                    x2="160"
                                                    y2="12"
                                                    class="text-hint stroke-current"
                                                    stroke-width="1"
                                                />
                                                <rect
                                                    [attr.x]="s.lo * 160"
                                                    y="6"
                                                    [attr.width]="(s.hi - s.lo) * 160"
                                                    height="12"
                                                    rx="2"
                                                    class="text-hint fill-current"
                                                    fill-opacity="0.35"
                                                />
                                                <line
                                                    [attr.x1]="s.median * 160"
                                                    y1="4"
                                                    [attr.x2]="s.median * 160"
                                                    y2="20"
                                                    class="text-secondary stroke-current"
                                                    stroke-width="2"
                                                />
                                                @if (s.observed != null) {
                                                    <circle
                                                        [attr.cx]="s.observed * 160"
                                                        cy="12"
                                                        r="4"
                                                        class="text-primary fill-current"
                                                    />
                                                }
                                            </svg>
                                        } @else {
                                            <span class="text-secondary text-xs">No baseline</span>
                                        }
                                    </td>
                                    <td class="text-xs">{{ f.reason }}</td>
                                </tr>
                            }
                        </tbody>
                    </table>
                    <p class="text-secondary mt-1 text-xs">
                        Baseline: the shaded band is the usual range (median ± 3 MAD of the window), the bar its median
                        and the dot the observed day.
                    </p>
                }
            </div>
        }
    `,
})
export class AnomalyModelPreviewComponent {
    private api = inject(AnomalyModelsService);

    /** The SAVED model to preview. */
    readonly model = input.required<string>();

    readonly entityDraft = new FormControl('', { nonNullable: true });
    readonly asOfDraft = new FormControl('', { nonNullable: true });
    readonly running = signal(false);
    readonly missingKey = signal(false);
    readonly error = signal<string | null>(null);
    readonly result = signal<AnomalyScorePreview | null>(null);
    readonly fmt = fmt;
    readonly badge = bandBadge;

    constructor() {
        effect(() => {
            this.model();
            this.result.set(null);
            this.error.set(null);
        });
    }

    run(): void {
        const k = this.entityDraft.value.trim();
        this.missingKey.set(!k);
        if (!k) return;
        this.running.set(true);
        this.error.set(null);
        this.result.set(null);
        this.api.preview(this.model(), k, this.asOfDraft.value || undefined).subscribe({
            next: (p) => {
                this.running.set(false);
                this.result.set(p);
            },
            error: (e) => {
                this.running.set(false);
                this.error.set(apiErrorMessage(e, 'Could not preview this Anomaly Score.'));
            },
        });
    }

    strip(f: AnomalyFeatureResult): BaselineStrip | null {
        const b = f.baseline;
        if (b && b.median != null) return baselineStrip(b.median, b.mad, f.observed);
        const p = f.peerBaseline;
        return p && !p.insufficient ? baselineStrip(p.median, p.mad, f.observed) : null;
    }

    stripLabel(f: AnomalyFeatureResult): string {
        const self = f.baseline && f.baseline.median != null;
        const b = self ? f.baseline : f.peerBaseline!;
        return (
            `${f.label || f.feature}: observed ${fmt(f.observed)} against a ${self ? 'own' : 'peer'} baseline ` +
            `median ${fmt(b.median)}, usual range ${fmt((b.median ?? 0) - 3 * (b.mad ?? 0))} to ` +
            `${fmt((b.median ?? 0) + 3 * (b.mad ?? 0))}`
        );
    }
}
