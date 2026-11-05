import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { DatasetRoleFilters, NO_ROLE_FILTERS, RoleFilters, roleFilterErrors } from './role-filters';

/** One Category column and the values the panel offers for it (its distinct values, at most the Category bound). */
export interface RoleFilterCategory {
    name: string;
    values: string[];
}

/** One Dataset of a read over several Datasets: its own Measures, Categories and Time columns (LA-COLUMN-ROLES-2 D-3 b). */
export interface RoleFilterSection {
    /** The Dataset id - the key its conditions are applied under. */
    key: string;
    /** The Dataset's name: the section's heading. */
    title: string;
    measures: readonly string[];
    categories: readonly RoleFilterCategory[];
    times: readonly string[];
}

/**
 * **Filter by role** (LA-COLUMN-ROLES-1 P3): a range per Measure, a value list per Category and a window on any Time
 * column of the loaded Dataset. Presentational: `(apply)` emits the filters; the host writes them into its predicate tree
 * (`withRoleFilters`) and pushes that to the server, which filters over the Dataset in SQL, or over the link index's
 * attribute columns when the read is served from it. Measures and Categories need no carrying on the links for this.
 *
 * Given `sections` (a read over several Datasets, LA-COLUMN-ROLES-2 D-3 b) it draws one titled section per Dataset and
 * `(applyByDataset)` emits the filters per Dataset id instead; the host writes each as that Dataset's edge mappings' own
 * `filter` (`withDatasetRoleFilters`).
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
            @for (s of drawn(); track s.key; let i = $index) {
                <div
                    class="flex flex-col gap-2"
                    [attr.role]="multi() ? 'group' : null"
                    [attr.aria-labelledby]="multi() ? 'la-role-filter-ds-' + i : null"
                    [attr.data-testid]="multi() ? 'role-filter-section-' + s.key : null"
                >
                    @if (multi()) {
                        <h4 [id]="'la-role-filter-ds-' + i" class="m-0 text-sm font-semibold">{{ s.title }}</h4>
                    }
                    @for (m of s.measures; track m) {
                        <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2" [attr.data-testid]="'measure-' + m">
                            <inspecto-pen-field
                                type="number"
                                [label]="m + ' from'"
                                [ariaLabel]="named(s, m + ' from')"
                                [ngModel]="value(s.key).measures[m]?.min ?? ''"
                                (ngModelChange)="setMeasure(m, 'min', $event, s.key)"
                                [ngModelOptions]="{ standalone: true }"
                            ></inspecto-pen-field>
                            <inspecto-pen-field
                                type="number"
                                [label]="m + ' to'"
                                [ariaLabel]="named(s, m + ' to')"
                                [ngModel]="value(s.key).measures[m]?.max ?? ''"
                                (ngModelChange)="setMeasure(m, 'max', $event, s.key)"
                                [ngModelOptions]="{ standalone: true }"
                                [error]="errorsOf(s.key)['measure.' + m] ?? ''"
                            ></inspecto-pen-field>
                        </div>
                    }
                    @for (c of s.categories; track c.name) {
                        <div class="flex flex-col gap-1" [attr.data-testid]="'category-' + c.name">
                            <span class="text-sm">{{ c.name }}</span>
                            @if (c.values.length > FIND_FROM) {
                                <inspecto-pen-field
                                    [label]="'Find a ' + c.name"
                                    [ariaLabel]="named(s, 'Find a ' + c.name)"
                                    [ngModel]="find()[findKey(c.name, s.key)] ?? ''"
                                    (ngModelChange)="setFind(c.name, $event, s.key)"
                                    [ngModelOptions]="{ standalone: true }"
                                    [attr.data-testid]="'find-' + c.name"
                                ></inspecto-pen-field>
                            }
                            @if (c.values.length) {
                                <div
                                    class="flex flex-wrap gap-1"
                                    role="group"
                                    [attr.aria-label]="named(s, c.name + ' values')"
                                >
                                    @for (v of shown(c, s.key); track v) {
                                        <button
                                            type="button"
                                            class="rounded-full"
                                            [attr.aria-pressed]="picked(c.name, v, s.key)"
                                            (click)="toggle(c.name, v, s.key)"
                                        >
                                            <inspecto-chip
                                                [variant]="picked(c.name, v, s.key) ? 'soft' : 'outline'"
                                                tone="primary"
                                                >{{ v }}</inspecto-chip
                                            >
                                        </button>
                                    }
                                </div>
                            } @else {
                                <span class="text-secondary text-xs">No values read for this column.</span>
                            }
                        </div>
                    }
                    @if (s.times.length) {
                        <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2" data-testid="time-window">
                            <inspecto-option-picker
                                [label]="named(s, 'Time column')"
                                [options]="timeOptions(s)"
                                [ngModel]="value(s.key).time.column"
                                (ngModelChange)="setTime('column', $event, s.key)"
                                [ngModelOptions]="{ standalone: true }"
                            ></inspecto-option-picker>
                            <span></span>
                            @if (value(s.key).time.column) {
                                <inspecto-pen-field
                                    type="datetime-local"
                                    label="From"
                                    [ariaLabel]="named(s, 'From')"
                                    [ngModel]="value(s.key).time.from"
                                    (ngModelChange)="setTime('from', $event, s.key)"
                                    [ngModelOptions]="{ standalone: true }"
                                ></inspecto-pen-field>
                                <inspecto-pen-field
                                    type="datetime-local"
                                    label="Before"
                                    [ariaLabel]="named(s, 'Before')"
                                    [ngModel]="value(s.key).time.to"
                                    (ngModelChange)="setTime('to', $event, s.key)"
                                    [ngModelOptions]="{ standalone: true }"
                                    [error]="errorsOf(s.key)['time'] ?? ''"
                                ></inspecto-pen-field>
                            }
                        </div>
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
                    (click)="applyNow()"
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
    /** A read over several Datasets: one section per Dataset (when given, `measures` / `categories` / `times` are unused). */
    readonly sections = input<readonly RoleFilterSection[]>([]);
    /** What the host last applied per Dataset id, over several Datasets. */
    readonly initialByDataset = input<DatasetRoleFilters>({});
    readonly busy = input(false);
    readonly apply = output<RoleFilters>();
    /** Over several Datasets: the filters of every drawn section, by Dataset id. */
    readonly applyByDataset = output<DatasetRoleFilters>();

    /** One section per Dataset (several Datasets), else the one untitled section of the loaded Dataset (key ''). */
    readonly multi = computed(() => this.sections().length > 0);
    readonly drawn = computed<readonly RoleFilterSection[]>(() =>
        this.multi()
            ? this.sections()
            : [{ key: '', title: '', measures: this.measures(), categories: this.categories(), times: this.times() }],
    );
    /** The filters as typed, per section key. */
    private readonly values = signal<DatasetRoleFilters>({});
    /** A Category with more values than this gets a find box above its values (plan §3: a picker with search). */
    readonly FIND_FROM = 12;
    /** The find text per section and Category column ({@link findKey}). */
    readonly find = signal<Record<string, string>>({});
    readonly errors = computed<Record<string, Record<string, string>>>(() =>
        Object.fromEntries(Object.entries(this.values()).map(([k, f]) => [k, roleFilterErrors(f)])),
    );
    readonly hasErrors = computed(() => Object.values(this.errors()).some((e) => Object.keys(e).length > 0));

    constructor() {
        // read `multi` (a boolean), never `sections`: Category values arriving must not reset what was typed
        effect(() => this.values.set(structuredClone(this.multi() ? this.initialByDataset() : { '': this.initial() })));
    }

    /** The filters of section `key` ('' = the one loaded Dataset). */
    value(key = ''): RoleFilters {
        return this.values()[key] ?? NO_ROLE_FILTERS;
    }

    errorsOf(key: string): Record<string, string> {
        return this.errors()[key] ?? {};
    }

    /** A control's accessible name: prefixed by its Dataset over several, so the same column in two sections differs. */
    named(s: RoleFilterSection, text: string): string {
        return this.multi() ? `${s.title}: ${text}` : text;
    }

    timeOptions(s: RoleFilterSection): PickerOption[] {
        return [{ value: '', label: 'None' }, ...s.times.map((t) => ({ value: t, label: t }))];
    }

    findKey(column: string, key = ''): string {
        return key ? `${key}\u0000${column}` : column;
    }

    /** The values of `c` the find text matches (any case), the picked ones always kept in view. */
    shown(c: RoleFilterCategory, key = ''): string[] {
        const q = (this.find()[this.findKey(c.name, key)] ?? '').trim().toLowerCase();
        return q ? c.values.filter((v) => v.toLowerCase().includes(q) || this.picked(c.name, v, key)) : c.values;
    }

    setFind(column: string, v: string | null, key = ''): void {
        this.find.update((f) => ({ ...f, [this.findKey(column, key)]: v ?? '' }));
    }

    picked(column: string, v: string, key = ''): boolean {
        return (this.value(key).categories[column] ?? []).includes(v);
    }

    toggle(column: string, v: string, key = ''): void {
        this.update(key, (f) => {
            const now = f.categories[column] ?? [];
            const next = now.includes(v) ? now.filter((x) => x !== v) : [...now, v];
            return { ...f, categories: { ...f.categories, [column]: next } };
        });
    }

    setMeasure(column: string, bound: 'min' | 'max', v: string | number | null, key = ''): void {
        this.update(key, (f) => {
            const r = f.measures[column] ?? { min: '', max: '' };
            return { ...f, measures: { ...f.measures, [column]: { ...r, [bound]: v == null ? '' : String(v) } } };
        });
    }

    setTime(field: 'column' | 'from' | 'to', v: string | null, key = ''): void {
        this.update(key, (f) => ({ ...f, time: { ...f.time, [field]: v ?? '' } }));
    }

    applyNow(): void {
        if (this.multi())
            this.applyByDataset.emit(Object.fromEntries(this.sections().map((s) => [s.key, this.value(s.key)])));
        else this.apply.emit(this.value());
    }

    clear(): void {
        this.values.set(this.multi() ? {} : { '': structuredClone(NO_ROLE_FILTERS) });
        this.applyNow();
    }

    private update(key: string, fn: (f: RoleFilters) => RoleFilters): void {
        this.values.update((all) => ({ ...all, [key]: fn(all[key] ?? structuredClone(NO_ROLE_FILTERS)) }));
    }
}
