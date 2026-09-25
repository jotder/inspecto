import { ChangeDetectionStrategy, Component, computed, input, linkedSignal, output } from '@angular/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import {
    DATE_RANGE_PRESETS,
    DateRangePreset,
    DateRangeSelection,
    asRangeSelection,
    resolveRange,
    spanLabel,
} from './dashboard-date-range';

/**
 * UIE-5 (d) — the compact Dashboard date-range control: a preset picker (counted back from `anchor`, the
 * Dashboard's as-of day or today), *All dates* (no range), and *Custom* with From / To day inputs. Shows the span
 * the pick resolves to. Presentational — the host owns the selection; a Custom pick is emitted only once both
 * days are set and From is on or before To. Used by the viewers (transient) and by the editor (the default range).
 */
@Component({
    selector: 'app-dashboard-date-range',
    standalone: true,
    imports: [MatFormFieldModule, MatInputModule, MatSelectModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex flex-wrap items-center gap-2" role="group" [attr.aria-label]="label()">
            <mat-form-field subscriptSizing="dynamic" class="w-44">
                <mat-label>{{ label() }}</mat-label>
                <mat-select [value]="picked()" (selectionChange)="onPick($event.value)">
                    <mat-option value="">All dates</mat-option>
                    @for (p of presets; track p.id) {
                        <mat-option [value]="p.id">{{ p.label }}</mat-option>
                    }
                    <mat-option value="custom">Custom</mat-option>
                </mat-select>
            </mat-form-field>
            @if (picked() === 'custom') {
                <mat-form-field subscriptSizing="dynamic" class="w-40">
                    <mat-label>From</mat-label>
                    <input matInput type="date" [value]="from()" (change)="onDay('from', $event)" />
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic" class="w-40">
                    <mat-label>To</mat-label>
                    <input matInput type="date" [value]="to()" (change)="onDay('to', $event)" />
                </mat-form-field>
                @if (customError(); as message) {
                    <p class="text-warn text-xs" role="alert">{{ message }}</p>
                }
            }
            @if (span(); as s) {
                <span class="text-secondary whitespace-nowrap text-sm" data-testid="date-range-span">{{ s }}</span>
            }
        </div>
    `,
})
export class DashboardDateRangeComponent {
    /** The current pick: a preset id, a custom span, or null for all dates. */
    readonly selection = input<DateRangeSelection | null>(null);
    /** The day presets count back from (`YYYY-MM-DD`). */
    readonly anchor = input.required<string>();
    readonly label = input('Date range');
    readonly selectionChange = output<DateRangeSelection | null>();

    readonly presets = DATE_RANGE_PRESETS;

    /** The select's value — follows the selection, but can sit on *Custom* while its days are incomplete. */
    readonly picked = linkedSignal<string>(() => {
        const s = this.selection();
        return s == null ? '' : typeof s === 'string' ? s : 'custom';
    });
    readonly from = linkedSignal(() => this.customOf()?.from ?? '');
    readonly to = linkedSignal(() => this.customOf()?.to ?? '');

    readonly customError = computed(() =>
        this.from() && this.to() && this.from() > this.to() ? 'From must be on or before To.' : '',
    );

    /** What the pick covers, e.g. `1 Sep – 24 Sep 2026`; empty for all dates or an incomplete custom range. */
    readonly span = computed(() => {
        if (this.picked() === 'custom' && !asRangeSelection({ from: this.from(), to: this.to() })) return '';
        const r = resolveRange(this.selection(), this.anchor());
        return r ? spanLabel(r) : '';
    });

    private customOf() {
        const s = this.selection();
        return s && typeof s === 'object' ? s : null;
    }

    onPick(value: string): void {
        this.picked.set(value);
        if (value === 'custom') this.emitCustom();
        else this.selectionChange.emit(value ? (value as DateRangePreset) : null);
    }

    onDay(end: 'from' | 'to', event: Event): void {
        const value = (event.target as HTMLInputElement).value;
        (end === 'from' ? this.from : this.to).set(value);
        this.emitCustom();
    }

    private emitCustom(): void {
        const custom = asRangeSelection({ from: this.from(), to: this.to() });
        if (custom) this.selectionChange.emit(custom);
    }
}
