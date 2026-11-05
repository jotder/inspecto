import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { NO_ROLE_FILTERS, RoleFilters, roleFilterErrors } from './role-filters';

/** One Category column and the values the panel offers for it (its distinct values, at most the Category bound). */
export interface RoleFilterCategory {
    name: string;
    values: string[];
}

/**
 * **Filter by role** (LA-COLUMN-ROLES-1 P3): a range per Measure, a value list per Category and a window on any Time
 * column of the loaded Dataset. Presentational: `(apply)` emits the filters; the host writes them into its predicate tree
 * (`withRoleFilters`) and pushes that to the server, which filters over the Dataset in SQL, or over the link index's
 * attribute columns when the read is served from it. Measures and Categories need no carrying on the links for this.
 */
@Component({
    selector: 'inspecto-link-analysis-role-filter',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, ChipComponent, InspectoOptionPickerComponent, PenFieldComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2" aria-labelledby="la-role-filter-title" data-testid="role-filter">
            <h3 id="la-role-filter-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Filter by role
            </h3>
            @for (m of measures(); track m) {
                <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2" [attr.data-testid]="'measure-' + m">
                    <inspecto-pen-field
                        type="number"
                        [label]="m + ' from'"
                        [ngModel]="value().measures[m]?.min ?? ''"
                        (ngModelChange)="setMeasure(m, 'min', $event)"
                        [ngModelOptions]="{ standalone: true }"
                    ></inspecto-pen-field>
                    <inspecto-pen-field
                        type="number"
                        [label]="m + ' to'"
                        [ngModel]="value().measures[m]?.max ?? ''"
                        (ngModelChange)="setMeasure(m, 'max', $event)"
                        [ngModelOptions]="{ standalone: true }"
                        [error]="errors()['measure.' + m] ?? ''"
                    ></inspecto-pen-field>
                </div>
            }
            @for (c of categories(); track c.name) {
                <div class="flex flex-col gap-1" [attr.data-testid]="'category-' + c.name">
                    <span class="text-sm">{{ c.name }}</span>
                    @if (c.values.length > FIND_FROM) {
                        <inspecto-pen-field
                            [label]="'Find a ' + c.name"
                            [ngModel]="find()[c.name] ?? ''"
                            (ngModelChange)="setFind(c.name, $event)"
                            [ngModelOptions]="{ standalone: true }"
                            [attr.data-testid]="'find-' + c.name"
                        ></inspecto-pen-field>
                    }
                    @if (c.values.length) {
                        <div class="flex flex-wrap gap-1" role="group" [attr.aria-label]="c.name + ' values'">
                            @for (v of shown(c); track v) {
                                <button
                                    type="button"
                                    class="rounded-full"
                                    [attr.aria-pressed]="picked(c.name, v)"
                                    (click)="toggle(c.name, v)"
                                >
                                    <inspecto-chip [variant]="picked(c.name, v) ? 'soft' : 'outline'" tone="primary">{{
                                        v
                                    }}</inspecto-chip>
                                </button>
                            }
                        </div>
                    } @else {
                        <span class="text-secondary text-xs">No values read for this column.</span>
                    }
                </div>
            }
            @if (times().length) {
                <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2" data-testid="time-window">
                    <inspecto-option-picker
                        label="Time column"
                        [options]="timeOptions()"
                        [ngModel]="value().time.column"
                        (ngModelChange)="setTime('column', $event)"
                        [ngModelOptions]="{ standalone: true }"
                    ></inspecto-option-picker>
                    <span></span>
                    @if (value().time.column) {
                        <inspecto-pen-field
                            type="datetime-local"
                            label="From"
                            [ngModel]="value().time.from"
                            (ngModelChange)="setTime('from', $event)"
                            [ngModelOptions]="{ standalone: true }"
                        ></inspecto-pen-field>
                        <inspecto-pen-field
                            type="datetime-local"
                            label="Before"
                            [ngModel]="value().time.to"
                            (ngModelChange)="setTime('to', $event)"
                            [ngModelOptions]="{ standalone: true }"
                            [error]="errors()['time'] ?? ''"
                        ></inspecto-pen-field>
                    }
                </div>
            }
            <div class="flex items-center gap-2">
                <button
                    mat-flat-button
                    color="primary"
                    type="button"
                    data-testid="role-filter-apply"
                    [disabled]="busy() || hasErrors()"
                    (click)="apply.emit(value())"
                >
                    Filter on the server
                </button>
                <button mat-button type="button" data-testid="role-filter-clear" (click)="clear()">Clear</button>
            </div>
        </section>
    `,
})
export class LinkAnalysisRoleFilterComponent {
    readonly measures = input<readonly string[]>([]);
    readonly categories = input<readonly RoleFilterCategory[]>([]);
    readonly times = input<readonly string[]>([]);
    /** What the host last applied: the panel starts from it. */
    readonly initial = input<RoleFilters>(NO_ROLE_FILTERS);
    readonly busy = input(false);
    readonly apply = output<RoleFilters>();

    readonly value = signal<RoleFilters>(NO_ROLE_FILTERS);
    /** A Category with more values than this gets a find box above its values (plan §3: a picker with search). */
    readonly FIND_FROM = 12;
    /** The find text per Category column. */
    readonly find = signal<Record<string, string>>({});
    readonly errors = computed(() => roleFilterErrors(this.value()));
    readonly hasErrors = computed(() => Object.keys(this.errors()).length > 0);
    readonly timeOptions = computed<PickerOption[]>(() => [
        { value: '', label: 'None' },
        ...this.times().map((t) => ({ value: t, label: t })),
    ]);

    constructor() {
        effect(() => this.value.set(structuredClone(this.initial())));
    }

    /** The values of `c` the find text matches (any case), the picked ones always kept in view. */
    shown(c: RoleFilterCategory): string[] {
        const q = (this.find()[c.name] ?? '').trim().toLowerCase();
        return q ? c.values.filter((v) => v.toLowerCase().includes(q) || this.picked(c.name, v)) : c.values;
    }

    setFind(column: string, v: string | null): void {
        this.find.update((f) => ({ ...f, [column]: v ?? '' }));
    }

    picked(column: string, v: string): boolean {
        return (this.value().categories[column] ?? []).includes(v);
    }

    toggle(column: string, v: string): void {
        this.value.update((f) => {
            const now = f.categories[column] ?? [];
            const next = now.includes(v) ? now.filter((x) => x !== v) : [...now, v];
            return { ...f, categories: { ...f.categories, [column]: next } };
        });
    }

    setMeasure(column: string, bound: 'min' | 'max', v: string | number | null): void {
        this.value.update((f) => {
            const r = f.measures[column] ?? { min: '', max: '' };
            return { ...f, measures: { ...f.measures, [column]: { ...r, [bound]: v == null ? '' : String(v) } } };
        });
    }

    setTime(key: 'column' | 'from' | 'to', v: string | null): void {
        this.value.update((f) => ({ ...f, time: { ...f.time, [key]: v ?? '' } }));
    }

    clear(): void {
        this.value.set(structuredClone(NO_ROLE_FILTERS));
        this.apply.emit(this.value());
    }
}
