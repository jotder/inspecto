import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * A stat tile — label · value · hint (UI consolidation plan UI-03). The one shape for the KPI rows on
 * Home, Overview, Collectors, Processing Status, Learning and Maintenance, which each hand-rolled a
 * `bg-card rounded-2xl p-5` box with its own label size and number weight.
 *
 * The value is rendered with tabular numerals so a row of tiles lines up; an ABSENT value (`null` /
 * `undefined` / `''`) renders as an em dash with a tooltip saying why, instead of a `0` that asserts a
 * count nobody measured (the Home "Recent Runs —" case, made explicit).
 *
 * Extra content (a status badge, a sparkline) projects after the value.
 */
@Component({
    selector: 'inspecto-stat-tile',
    standalone: true,
    imports: [MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block min-w-0' },
    template: `
        <div class="bg-card flex h-full flex-col gap-1 rounded-xl px-4 py-3 shadow-sm">
            <span class="text-secondary truncate text-xs font-medium">{{ label }}</span>
            <div class="flex min-w-0 items-baseline gap-2">
                @if (present) {
                    <span class="truncate text-xl font-semibold leading-7 tabular-nums">{{ value }}</span>
                } @else {
                    <span
                        class="text-secondary text-xl font-semibold leading-7"
                        [matTooltip]="absentReason"
                        [attr.aria-label]="absentReason || 'Not available'"
                        >—</span
                    >
                }
                <ng-content></ng-content>
            </div>
            @if (hint) {
                <span class="text-secondary truncate text-xs">{{ hint }}</span>
            }
        </div>
    `,
})
export class InspectoStatTileComponent {
    @Input({ required: true }) label = '';
    /** The figure. `null`/`undefined`/`''` renders as an em dash — never coerce an unknown to 0. */
    @Input() value: string | number | null | undefined;
    /** Small line under the value ("0 writes", "last 24h"). */
    @Input() hint = '';
    /** Why the value is absent; shown as the dash's tooltip. */
    @Input() absentReason = 'Not available';

    get present(): boolean {
        return this.value !== null && this.value !== undefined && this.value !== '';
    }
}
