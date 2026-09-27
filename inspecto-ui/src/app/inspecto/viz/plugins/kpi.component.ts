import { ChangeDetectionStrategy, Component, Injector, computed, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { formatNumber, NumberFormat } from '../number-format';
import { targetStatus } from '../target-status';
import { kpiDelta, toneBadge } from '../kpi-delta';
import { KpisService, KpiValue } from 'app/inspecto/api/kpis.service';

export type KpiMode = 'mini' | 'standard' | 'max';

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
 * direction, format and RAG band from `GET /kpis/{id}/value` and never from the per-Widget inputs: a null value reads
 * "No data" and a failed read "KPI unavailable". The hand-set inputs apply only when no `kpiId` is set. A `band` KPI
 * has no better direction, so its delta and target lines take their tone from the server's band.
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
                    <inspecto-status-badge [value]="d.badge" [label]="d.text" />
                </div>
            }
            @if (bandLine(); as b) {
                <div class="mt-1" data-testid="kpi-band">
                    <inspecto-status-badge [value]="b.value" [label]="b.label" />
                </div>
            }
            @if (targetLine(); as t) {
                <div class="mt-1" data-testid="kpi-target">
                    <inspecto-status-badge [value]="t.badge" [label]="t.text" />
                </div>
            }
        </div>
    `,
})
export class KpiComponent {
    readonly value = input<number>(0);
    /** A KPI definition id: when set, the server's evaluation replaces the hand-set inputs below. */
    readonly kpiId = input<string | undefined>(undefined);
    /** The day a bound definition is evaluated at (`YYYY-MM-DD`, a Dashboard's `asOf`); blank ⇒ the server's today. */
    readonly asOf = input<string | undefined>(undefined);
    /** The definition's evaluation, once loaded. */
    readonly definition = signal<KpiValue | null>(null);
    /** Resolved only when a `kpiId` is bound, so an unbound tile needs no HttpClient (every host spec). */
    private readonly injector = inject(Injector);
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

    /** Whether the definition read failed — a visible "KPI unavailable", never the hand-set number. */
    readonly failed = signal(false);
    readonly bound = computed(() => !!this.kpiId());

    constructor() {
        // One read in flight: a kpiId/asOf change cancels the previous request (the effect's cleanup), so a slow
        // stale answer can never overwrite the newer one.
        effect((onCleanup) => {
            const id = this.kpiId();
            const asOf = this.asOf() || undefined;
            this.definition.set(null);
            this.failed.set(false);
            if (!id) return;
            const read = this.injector
                .get(KpisService)
                .value(id, asOf)
                .subscribe({ next: (v) => this.definition.set(v), error: () => this.failed.set(true) });
            onCleanup(() => read.unsubscribe());
        });
    }

    /**
     * The numbers the tile draws. Bound to a definition, ONLY the definition's — a null value is "no data", and the
     * Widget's hand-set inputs are never shown in its place. Unbound, the hand-set inputs.
     */
    private readonly eValue = computed<number | null>(() =>
        this.bound() ? (this.definition()?.value ?? null) : this.value(),
    );
    private readonly eCompare = computed(() =>
        this.bound() ? (this.definition()?.comparisonValue ?? undefined) : this.compare(),
    );
    private readonly eFormat = computed(() =>
        this.bound() ? ((this.definition()?.format as NumberFormat | undefined) ?? undefined) : this.format(),
    );
    private readonly eTarget = computed(() =>
        this.bound() ? (this.definition()?.target ?? undefined) : this.target(),
    );
    /** Up / down only — a band KPI has no better direction, and is handled apart (see {@link delta}). */
    private readonly eBetter = computed<'higher' | 'lower'>(() =>
        this.bound() ? (this.definition()?.direction === 'down' ? 'lower' : 'higher') : this.better(),
    );
    private readonly isBand = computed(() => this.bound() && this.definition()?.direction === 'band');

    /** "RAG: Amber" — the definition's band, in words; the shared badge tones GREEN / AMBER / RED. */
    readonly bandLine = computed(() => {
        const b = this.definition()?.band;
        return b ? { value: b, label: `RAG: ${b.charAt(0)}${b.slice(1).toLowerCase()}` } : null;
    });

    /** The big number: the value, or — bound — "Loading…", "No data" (a null value) or "KPI unavailable". */
    readonly display = computed(() => {
        if (this.bound()) {
            if (this.failed()) return 'KPI unavailable';
            if (!this.definition()) return 'Loading…';
        }
        const v = this.eValue();
        return v === null ? 'No data' : formatNumber(v, this.eFormat());
    });

    /**
     * "▲ Up 12.4 % (+SAR 0.9M) vs prior period" — change and direction in words, toned by whether it is good
     * (the shared wording, {@link kpiDelta}). A band KPI has no good direction, so its line drops the up / down
     * wording ("Change +10 (25.0 %) vs prior period") and takes its tone from the server's band.
     */
    readonly delta = computed<{ text: string; badge: string } | null>(() => {
        const v = this.eValue();
        if (v === null) return null;
        if (this.isBand()) {
            const d = this.definition()!;
            if (d.delta == null) return null;
            const pct = d.deltaPct == null ? '' : ` (${formatNumber(d.deltaPct, { decimals: 1 })} %)`;
            const sign = d.delta > 0 ? '+' : d.delta < 0 ? '−' : '';
            const amount = formatNumber(Math.abs(d.delta), { ...this.eFormat(), compact: true });
            return { text: `Change ${sign}${amount}${pct} vs prior period`, badge: d.band ?? '' };
        }
        const k = kpiDelta(v, this.eCompare(), this.eBetter(), this.eFormat());
        return k ? { text: k.text, badge: toneBadge(k.tone) } : null;
    });

    /** "Target 99 — below target" — the target in the widget's format and whether the value meets it. A band KPI
     *  states where the value sits against its band instead, toned by the server's band. */
    readonly targetLine = computed<{ text: string; badge: string } | null>(() => {
        const v = this.eValue();
        if (v === null) return null;
        if (this.isBand()) {
            const d = this.definition()!;
            const where =
                d.band === 'GREEN'
                    ? 'within band'
                    : d.band === 'AMBER'
                      ? 'near band'
                      : d.band === 'RED'
                        ? 'outside band'
                        : null;
            if (!where) return null;
            const t = d.target == null ? 'Band' : `Target ${formatNumber(d.target, this.eFormat())}`;
            return { text: `${t} — ${where}`, badge: d.band! };
        }
        const s = targetStatus(v, this.eTarget(), this.eBetter(), this.eFormat());
        return s ? { text: s.text, badge: toneBadge(s.met ? 'good' : 'bad') } : null;
    });

    cycle(): void {
        this.mode.update(nextKpiMode);
    }
}
