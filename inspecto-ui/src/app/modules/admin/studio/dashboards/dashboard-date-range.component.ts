import { ChangeDetectionStrategy, Component, computed, input, linkedSignal, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerInputEvent, MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { DateTime } from 'luxon';
import {
    DATE_RANGE_PRESETS,
    DateRangePreset,
    DateRangeSelection,
    addDays,
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
    imports: [MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
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
                    <mat-option value="day">Single day</mat-option>
                    <mat-option value="custom">Custom</mat-option>
                </mat-select>
            </mat-form-field>
            @if (picked() === 'day') {
                <button
                    mat-icon-button
                    type="button"
                    aria-label="Previous day"
                    data-testid="day-prev"
                    (click)="stepDay(-1)"
                >
                    <mat-icon svgIcon="heroicons_outline:chevron-left" />
                </button>
                <mat-form-field subscriptSizing="dynamic" class="w-40">
                    <mat-label>Day</mat-label>
                    <input
                        matInput
                        [matDatepicker]="dayPicker"
                        [max]="dateOf(anchor())"
                        [value]="dateOf(from())"
                        (dateChange)="onDayPick($event)"
                    />
                    <mat-datepicker-toggle matIconSuffix [for]="dayPicker" />
                    <mat-datepicker #dayPicker />
                </mat-form-field>
                <button
                    mat-icon-button
                    type="button"
                    aria-label="Next day"
                    data-testid="day-next"
                    [disabled]="from() >= anchor()"
                    (click)="stepDay(1)"
                >
                    <mat-icon svgIcon="heroicons_outline:chevron-right" />
                </button>
            }
            @if (picked() === 'custom') {
                <mat-form-field subscriptSizing="dynamic" class="w-40">
                    <mat-label>From</mat-label>
                    <input
                        matInput
                        [matDatepicker]="fromPicker"
                        [value]="dateOf(from())"
                        (dateChange)="onDay('from', $event)"
                    />
                    <mat-datepicker-toggle matIconSuffix [for]="fromPicker" />
                    <mat-datepicker #fromPicker />
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic" class="w-40">
                    <mat-label>To</mat-label>
                    <input
                        matInput
                        [matDatepicker]="toPicker"
                        [value]="dateOf(to())"
                        (dateChange)="onDay('to', $event)"
                    />
                    <mat-datepicker-toggle matIconSuffix [for]="toPicker" />
                    <mat-datepicker #toPicker />
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
        return s == null ? '' : typeof s === 'string' ? s : s.from === s.to ? 'day' : 'custom';
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
        if (value === 'day') this.emitDay(this.from() && this.from() === this.to() ? this.from() : this.anchor());
        else if (value === 'custom') this.emitCustom();
        else this.selectionChange.emit(value ? (value as DateRangePreset) : null);
    }

    /** The calendar's date for a `YYYY-MM-DD` day (null when blank); the adapter in use is Luxon. */
    dateOf(day: string): DateTime | null {
        return day ? DateTime.fromISO(day) : null;
    }

    private dayOf(event: MatDatepickerInputEvent<DateTime>): string {
        return event.value?.toISODate() ?? '';
    }

    onDay(end: 'from' | 'to', event: MatDatepickerInputEvent<DateTime>): void {
        const value = this.dayOf(event);
        (end === 'from' ? this.from : this.to).set(value);
        this.emitCustom();
    }

    /** The Single day view: the picked day alone, and the previous / next day buttons. */
    stepDay(by: number): void {
        this.emitDay(addDays(this.from(), by));
    }

    onDayPick(event: MatDatepickerInputEvent<DateTime>): void {
        const day = this.dayOf(event);
        if (day) this.emitDay(day);
    }

    private emitDay(day: string): void {
        this.from.set(day);
        this.to.set(day);
        const one = asRangeSelection({ from: day, to: day });
        if (one) this.selectionChange.emit(one);
    }

    private emitCustom(): void {
        const custom = asRangeSelection({ from: this.from(), to: this.to() });
        if (custom) this.selectionChange.emit(custom);
    }
}
