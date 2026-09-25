import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { formatNumber, NumberFormat } from '../number-format';
import { targetStatus } from '../target-status';

type KpiMode = 'mini' | 'standard' | 'max';
type Tone = 'good' | 'bad' | 'flat';

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
 */
@Component({
    selector: 'inspecto-kpi',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, StatusBadgeComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div
            class="bg-card relative flex h-full flex-col justify-center rounded-2xl p-4 shadow"
            [class.items-center]="mode() !== 'standard'"
        >
            <button
                mat-icon-button
                class="absolute right-1 top-1"
                (click)="cycle()"
                [attr.aria-label]="'KPI size: ' + mode() + ' (click to change)'"
            >
                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrows-pointing-out"></mat-icon>
            </button>
            @if (label(); as l) {
                <div class="text-secondary mb-1 text-xs font-medium">{{ l }}</div>
            }
            <div
                class="whitespace-nowrap font-extrabold tabular-nums leading-none"
                [class.text-2xl]="mode() === 'mini'"
                [class.text-4xl]="mode() === 'standard'"
                [class.text-6xl]="mode() === 'max'"
                data-testid="kpi-value"
            >
                {{ display() }}
            </div>
            @if (delta(); as d) {
                <div class="mt-2" data-testid="kpi-delta">
                    <inspecto-status-badge [value]="badgeFor(d.tone)" [label]="d.text" />
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
    /** A caption for a host with no title of its own (the assistant's KPI artifact). A dashboard tile omits it —
     *  its card already carries the widget title. */
    readonly label = input<string | undefined>(undefined);
    /** The prior-period value (`compare` channel); absent ⇒ no delta line. */
    readonly compare = input<number | undefined>(undefined);
    readonly format = input<NumberFormat | undefined>(undefined);
    readonly target = input<number | undefined>(undefined);
    readonly better = input<'higher' | 'lower'>('higher');
    readonly mode = signal<KpiMode>('standard');

    readonly display = computed(() => formatNumber(this.value(), this.format()));

    /** "▲ Up 12.4 % (+SAR 0.9M) vs prior period" — change and direction in words, toned by whether it is good.
     *  The amount is SIGNED and sits before "vs", so it cannot be read as the prior-period value itself. */
    readonly delta = computed<{ text: string; tone: Tone } | null>(() => {
        const prev = this.compare();
        if (prev == null || !Number.isFinite(prev)) return null;
        const diff = this.value() - prev;
        if (diff === 0) return { text: 'No change vs prior period', tone: 'flat' };
        const up = diff > 0;
        const good = up === (this.better() === 'higher');
        const tone: Tone = good ? 'good' : 'bad';
        // A percent KPI changes in POINTS — a relative % beside it would be a second, different percentage.
        if (this.format()?.style === 'percent') {
            const pts = formatNumber(Math.abs(diff), { decimals: 1 });
            return { text: `${up ? '▲ Up' : '▼ Down'} ${pts} pts vs prior period`, tone };
        }
        const pct = prev !== 0 ? ` ${formatNumber(Math.abs((diff / prev) * 100), { decimals: 1 })} %` : '';
        const amount = formatNumber(Math.abs(diff), { ...this.format(), compact: true });
        return { text: `${up ? '▲ Up' : '▼ Down'}${pct} (${up ? '+' : '−'}${amount}) vs prior period`, tone };
    });

    /** "Target 99 — below target" — the target in the widget's format and whether the value meets it. */
    readonly targetLine = computed<{ text: string; tone: Tone } | null>(() => {
        const s = targetStatus(this.value(), this.target(), this.better(), this.format());
        return s ? { text: s.text, tone: s.met ? 'good' : 'bad' } : null;
    });

    /** The status-badge value for a tone: the shared badge owns status colour (PASS → success, FAIL → error). */
    badgeFor(tone: Tone): string {
        return tone === 'good' ? 'PASS' : tone === 'bad' ? 'FAIL' : '';
    }

    cycle(): void {
        const order: KpiMode[] = ['mini', 'standard', 'max'];
        this.mode.set(order[(order.indexOf(this.mode()) + 1) % order.length]);
    }
}
