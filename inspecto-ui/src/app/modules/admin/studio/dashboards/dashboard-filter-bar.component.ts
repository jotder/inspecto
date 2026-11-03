import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FormsModule } from '@angular/forms';
import { InspectoOptionPickerComponent, PickerOption, pickerOptions } from 'app/inspecto/components/option-picker.component';
import { Condition, ConditionGroup } from 'app/inspecto/query';
import { DrillEvent } from '../widgets/widget-host.component';

/**
 * Viewer-facing dashboard **filter bar** — quick filters over the dashboard's cross-filter without opening the
 * editor's condition builder. For each *exposed* field it offers the distinct sample values as a picker; every
 * active `field = value` equality condition renders as a removable chip. Both paths emit `(drillToggle)` with the
 * same `{field, value}` shape the tiles' drill-down uses, so the host applies one toggle rule everywhere.
 * Presentational — the host owns the ConditionGroup.
 */
@Component({
    selector: 'app-dashboard-filter-bar',
    standalone: true,
    imports: [FormsModule, InspectoOptionPickerComponent, MatButtonModule, MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (fields().length) {
            <div class="flex flex-wrap items-center gap-2" role="group" aria-label="Dashboard quick filters">
                @for (field of fields(); track field) {
                    <inspecto-option-picker
                        class="w-56"
                        [label]="field"
                        [options]="optionsFor(field)"
                        [ngModel]="null"
                        (ngModelChange)="onPick(field, $event)"
                        [ngModelOptions]="{ standalone: true }"
                    />
                }
                @for (c of activeConditions(); track c.field + '=' + c.value) {
                    <button
                        mat-stroked-button
                        type="button"
                        class="rounded-full"
                        (click)="drillToggle.emit({ field: c.field, value: c.value ?? '' })"
                        [attr.aria-label]="'Remove filter ' + c.field + ' = ' + c.value"
                    >
                        <span class="font-medium">{{ c.field }}</span>
                        <span class="text-secondary px-1">=</span>
                        <span>{{ c.value }}</span>
                        <mat-icon class="icon-size-4 ml-1" svgIcon="heroicons_outline:x-mark"></mat-icon>
                    </button>
                }
            </div>
        }
    `,
})
export class DashboardFilterBarComponent {
    /** Fields the dashboard exposes as quick filters (a subset of the cross-filter's columns). */
    readonly fields = input.required<string[]>();
    /** Distinct candidate values per field (derived from the tiles' sample rows by the host). */
    readonly values = input.required<Record<string, string[]>>();
    /** The dashboard's live cross-filter — equality conditions on exposed fields render as chips. */
    readonly filter = input.required<ConditionGroup>();
    /** Toggle `field = value` in the cross-filter (add when absent, remove when present). */
    /** Renamed from `toggle` 2026-09-17: an output named after a native DOM event is ambiguous on the host element. */
    readonly drillToggle = output<DrillEvent>();

    /** Top-level `field = value` conditions on exposed fields — the removable chips. */
    readonly activeConditions = computed<Condition[]>(() => {
        const exposed = new Set(this.fields());
        return this.filter().items.filter(
            (i): i is Condition => i.kind === 'condition' && i.operator === '=' && exposed.has(i.field),
        );
    });

    /** The picker's choices per exposed field, memoised per `values` (not a fresh array per change detection). */
    private readonly optionsByField = computed<Record<string, PickerOption[]>>(() =>
        Object.fromEntries(Object.entries(this.values()).map(([f, vs]) => [f, pickerOptions(vs)])),
    );
    private readonly noOptions: PickerOption[] = [];
    optionsFor(field: string): PickerOption[] {
        return this.optionsByField()[field] ?? this.noOptions;
    }

    onPick(field: string, value: string | null): void {
        if (value == null || value === '') return;
        // The picker reports a re-pick of the value it shows; adding a filter that is already on must not toggle it off.
        if (this.activeConditions().some((c) => c.field === field && c.value === value)) return;
        this.drillToggle.emit({ field, value });
    }
}
