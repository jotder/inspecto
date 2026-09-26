import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    Injector,
    computed,
    effect,
    inject,
    input,
    signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { formatNumber, NumberFormat } from '../number-format';
import { targetStatus } from '../target-status';
import { DeltaTone, kpiDelta, toneBadge } from '../kpi-delta';
import { KpisService, KpiValue } from 'app/inspecto/api/kpis.service';

export type KpiMode = 'mini' | 'standard' | 'max';
type Tone = DeltaTone;

const KPI_MODES: KpiMode[] = ['mini', 'standard', 'max'];

/** The next in-place size: mini → standard → max → mini. */
export function nextKpiMode(mode: KpiMode): KpiMode {
    return KPI_MODES[(KPI_MODES.indexOf(mode) + 1) % KPI_MODES.length];
}

/**
 * KPI tile — the `kpi` plugin's component escape hatch, mounted by `viz-render` via `NgComponentOutlet`. The widget
 * card above it already carries the title, so the tile carries only the number and what it means (UIE-1):
 * - the value in the widget's {@link NumberFormat} (`SAR 7.7M`, `96.9 %`);
 * - the delta against the prior period, from the optional `compare` channel;
 * - the target, stated as on / off target.
 *
 * Both are said in WORDS and arrows as well as tone, so colour is never the only signal (WCAG 1.4.1). Which
 * direction is good comes from `better`: an exposure is better lower, a recovery higher. Three in-place sizes
 * (mini → standard → max) toggle with one button. Tone rides the shared status badge, the sanctioned colour owner.
 *
 * Bound to a KPI definition (`kpiId`, ASSURE-KPI-DEFINITIONS-1) the tile reads its value, comparison, target, good
 * direction, format and RAG band from `GET /kpis/{id}/value` instead of the per-Widget inputs, which stay the fallback
 * until (or unless) the definition answers.
 *
 * Inside a Dashboard tile the tile card owns the frame AND the size button (it sits in the tile's hover/focus
 * action set), so the host passes `size` and the KPI drops its own card and button. Standalone (the assistant's KPI
 * artifact) it keeps both.
 */
@Component({
    selector: 'inspecto-kpi',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, StatusBadgeComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div
            class="relative flex h-full flex-col justify-center"
            [class.bg-card]="!framed()"
            [class.rounded-2xl]="!framed()"
            [class.p-4]="!framed()"
            [class.shadow]="!framed()"
            [class.items-center]="effectiveMode() !== 'standard'"
        >
            @if (!framed()) {
                <button
                    mat-icon-button
                    class="absolute right-1 top-1"
                    (click)="cycle()"
                    [attr.aria-label]="'KPI size: ' + effectiveMode() + ' (click to change)'"
                >
                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrows-pointing-out"></mat-icon>
                </button>
            }
            @if (label(); as l) {
                <div class="text-secondary mb-1 text-xs font-medium">{{ l }}</div>
            }
            <div
                class="whitespace-nowrap font-extrabold tabular-nums leading-none"
                [class.text-2xl]="effectiveMode() === 'mini'"
                [class.text-4xl]="effectiveMode() === 'standard'"
                [class.text-6xl]="effectiveMode() === 'max'"
                data-testid="kpi-value"
            >
                {{ display() }}
            </div>
            @if (delta(); as d) {
                <div class="mt-2" data-testid="kpi-delta">
                    <inspecto-status-badge [value]="badgeFor(d.tone)" [label]="d.text" />
                </div>
            }
            @if (bandLine(); as b) {
                <div class="mt-1" data-testid="kpi-band">
                    <inspecto-status-badge [value]="b.value" [label]="b.label" />
                </div>
            }
            @if (targetLine(); as t) {
                <div class="mt-1" data-testid="kpi-target">
                    <inspecto-status-badge [value]="badgeFor(t.tone)" [label]="t.text" />
                </div>
            }
        </div>
    `,
})
export class KpiComponent {
    readonly value = input<number>(0);
    /** A KPI definition id: when set, the server's evaluation replaces the hand-set inputs below. */
    readonly kpiId = input<string | undefined>(undefined);
    /** The definition's evaluation, once loaded. */
    readonly definition = signal<KpiValue | null>(null);
    /** Resolved only when a `kpiId` is bound, so an unbound tile needs no HttpClient (every host spec). */
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);
    /** A caption for a host with no title of its own (the assistant's KPI artifact). A dashboard tile omits it —
     *  its card already carries the widget title. */
    readonly label = input<string | undefined>(undefined);
    /** The prior-period value (`compare` channel); absent ⇒ no delta line. */
    readonly compare = input<number | undefined>(undefined);
    readonly format = input<NumberFormat | undefined>(undefined);
    readonly target = input<number | undefined>(undefined);
    readonly better = input<'higher' | 'lower'>('higher');
    readonly mode = signal<KpiMode>('standard');
    /** Set by a host that owns the frame and the size control (a Dashboard tile); absent ⇒ standalone. */
    readonly size = input<KpiMode | undefined>(undefined);
    readonly framed = computed(() => this.size() !== undefined);
    readonly effectiveMode = computed(() => this.size() ?? this.mode());

    constructor() {
        effect(() => {
            const id = this.kpiId();
            this.definition.set(null);
            if (!id) return;
            this.injector
                .get(KpisService)
                .value(id)
                .pipe(takeUntilDestroyed(this.destroyRef))
                .subscribe({ next: (v) => this.definition.set(v), error: () => this.definition.set(null) });
        });
    }

    /** The inputs the tile draws from: the definition's where it answered, else the hand-set ones. */
    private readonly eValue = computed(() => this.definition()?.value ?? this.value());
    private readonly eCompare = computed(() => {
        const d = this.definition();
        return d ? (d.comparisonValue ?? undefined) : this.compare();
    });
    private readonly eFormat = computed(() => (this.definition()?.format as NumberFormat | undefined) ?? this.format());
    private readonly eTarget = computed(() => {
        const d = this.definition();
        return d ? (d.target ?? undefined) : this.target();
    });
    private readonly eBetter = computed<'higher' | 'lower'>(() => {
        const d = this.definition();
        return d ? (d.direction === 'down' ? 'lower' : 'higher') : this.better();
    });

    /** "RAG: Amber" — the definition's band, in words; the shared badge tones GREEN / AMBER / RED. */
    readonly bandLine = computed(() => {
        const b = this.definition()?.band;
        return b ? { value: b, label: `RAG: ${b.charAt(0)}${b.slice(1).toLowerCase()}` } : null;
    });

    readonly display = computed(() => formatNumber(this.eValue(), this.eFormat()));

    /** "▲ Up 12.4 % (+SAR 0.9M) vs prior period" — change and direction in words, toned by whether it is good
     *  (the shared wording, {@link kpiDelta}). */
    readonly delta = computed(() => kpiDelta(this.eValue(), this.eCompare(), this.eBetter(), this.eFormat()));

    /** "Target 99 — below target" — the target in the widget's format and whether the value meets it. */
    readonly targetLine = computed<{ text: string; tone: Tone } | null>(() => {
        const s = targetStatus(this.eValue(), this.eTarget(), this.eBetter(), this.eFormat());
        return s ? { text: s.text, tone: s.met ? 'good' : 'bad' } : null;
    });

    /** The status-badge value for a tone: the shared badge owns status colour (PASS → success, FAIL → error). */
    badgeFor(tone: Tone): string {
        return toneBadge(tone);
    }

    cycle(): void {
        this.mode.update(nextKpiMode);
    }
}
