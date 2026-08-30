import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoOptionPickerComponent, PickerOption } from '../components/option-picker.component';

/** One `partitions[]` entry of the schema toon — the engine's `PartitionDef` verbatim. */
export interface SchemaPartitionRow {
    /** Output Hive-directory segment name, e.g. `year`. */
    column: string;
    /** The schema field the expression is derived from, e.g. `TXN_DATE`. */
    source: string;
    /** How the SQL expression is produced — `PartitionDef.Type`'s vocabulary. */
    type: string;
}

/** The engine's `PartitionDef.Type` enum, spelled as the toon spells it. */
export const PARTITION_TYPES = ['VARCHAR', 'INTEGER', 'DOUBLE', 'DATE_YEAR', 'DATE_MONTH', 'DATE_DAY'] as const;

const DATE_TYPES = new Set(['DATE_YEAR', 'DATE_MONTH', 'DATE_DAY']);

/**
 * The **partitioning editor** (operator ask 2026-08-22) — the schema toon's `partitions[]` list,
 * first UI over a long-shipped model (`PartitionDef.fromSchema`): each row derives one output
 * Hive-directory segment from a schema field. Until now `partitions[]` was hand-authored only —
 * and the Parse pane's schema write silently DROPPED it, since the draft carried `raw`+`mapping`
 * with `overwrite: true`.
 *
 * <p><b>Pure</b>, the fields-editor contract: `[initial]` + `[fieldNames]` in, {@link value} out,
 * hosts own every write. The date trio (year/month/day off one source column) is the overwhelmingly
 * common scheme, so it is one click; every row stays individually editable after.
 *
 * <p>⚠ The engine only recognises a single event time when ALL `DATE_*` rows share one source
 * (`PartitionDef.eventTimeDef` — mixed sources degrade catalog bounds to none, deliberately).
 * That is a WARNING here, never a refusal: the write is legal, the degradation is the engine's
 * documented choice.
 */
@Component({
    selector: 'inspecto-schema-partitions-editor',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoOptionPickerComponent,
    ],
    template: `
        @if (partitionRows.length) {
            <table class="w-full border-collapse">
                <thead>
                    <tr class="text-secondary text-left text-xs font-semibold uppercase tracking-wider">
                        <th scope="col" class="pb-1">Directory segment</th>
                        <th scope="col" class="pb-1">From field</th>
                        <th scope="col" class="w-32 pb-1">Derivation</th>
                        <th scope="col" class="w-10 pb-1"><span class="sr-only">Remove</span></th>
                    </tr>
                </thead>
                <tbody>
                    @for (g of partitionRows.controls; track g) {
                        <tr [formGroup]="g" class="border-t align-middle">
                            <td class="py-1 pr-2">
                                <input
                                    class="w-full bg-transparent font-mono text-sm"
                                    formControlName="column"
                                    placeholder="year"
                                    [attr.aria-label]="'Partition segment name'"
                                    (change)="emitChanged()"
                                />
                            </td>
                            <td class="py-1 pr-2">
                                <mat-form-field class="w-full" subscriptSizing="dynamic">
                                    <mat-select
                                        formControlName="source"
                                        aria-label="Partition source field"
                                        placeholder="pick a field…"
                                        (selectionChange)="emitChanged()"
                                    >
                                        @for (f of sourceChoices(g); track f) {
                                            <mat-option [value]="f">{{ f }}</mat-option>
                                        }
                                    </mat-select>
                                </mat-form-field>
                            </td>
                            <td class="py-1 pr-2">
                                <mat-form-field class="w-full" subscriptSizing="dynamic">
                                    <mat-select
                                        formControlName="type"
                                        aria-label="Partition derivation type"
                                        (selectionChange)="emitChanged()"
                                    >
                                        @for (t of types; track t) {
                                            <mat-option [value]="t">{{ t }}</mat-option>
                                        }
                                    </mat-select>
                                </mat-form-field>
                            </td>
                            <td class="py-1 text-right">
                                <button
                                    mat-icon-button
                                    type="button"
                                    aria-label="Remove this partition segment"
                                    (click)="removeRow(g)"
                                >
                                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:trash"></mat-icon>
                                </button>
                            </td>
                        </tr>
                    }
                </tbody>
            </table>
        } @else {
            <p class="text-secondary m-0 text-sm">
                No partitioning — every run writes one flat store. Pick a date field to cut the output into
                year/month/day directories, or add a single segment.
            </p>
        }
        @if (mixedDateSources()) {
            <p class="text-warn m-0 mt-1 text-xs" role="alert">
                The date segments disagree on their source field — the engine will still write them, but it records no
                event-time bounds for a scheme with no single event time.
            </p>
        }
        <div class="mt-2 flex flex-wrap items-center gap-2">
            <!-- Smart date partitioning (operator ask 2026-08-22): the field picker SUGGESTS —
                 date-typed schema fields first (pre-picked when there is exactly one obvious
                 choice, and hinted "date-typed" in the popup), everything else after, because a
                 date can legitimately live in a VARCHAR column the strptime chain parses. The
                 grain expands to rows, which stay editable. Both are the design-system
                 <inspecto-option-picker> (operator ask 2026-08-23 — a native select here was the
                 one raw dropdown on the tab); the launcher state stays SIGNALS (the spec's and
                 addDateGrain's API), mirrored into the two FormControls the CVA binds. -->
            <inspecto-option-picker
                class="block min-w-40"
                label="Date field"
                placeholder="pick…"
                [formControl]="dateSourceCtrl"
                [options]="dateFieldOptions()"
            />
            <inspecto-option-picker class="block" label="Grain" [formControl]="grainCtrl" [options]="grainOptions" />
            <button
                mat-stroked-button
                type="button"
                class="!text-xs"
                [disabled]="!dateSource()"
                matTooltip="Adds the date segments for the picked grain, all cut from one field — each row stays editable after"
                (click)="addDateGrain()"
            >
                Partition by date
            </button>
            <button mat-stroked-button type="button" class="!text-xs" (click)="addRow()">Add segment</button>
        </div>
    `,
})
export class InspectoSchemaPartitionsEditorComponent {
    private fb = inject(FormBuilder);

    /** The stored `partitions[]`, verbatim — reseeds the grid on reference change. */
    readonly initial = input<SchemaPartitionRow[]>([]);
    /** The schema's field names — what a partition may derive from. */
    readonly fieldNames = input<string[]>([]);
    /**
     * The date-typed subset (DATE/TIMESTAMP/TIMESTAMPTZ) — the host derives it from the schema's
     * declared types. Suggestions only: a date can live in a VARCHAR column too (the strptime chain
     * parses it), so {@link fieldNames} stays the full offer.
     */
    readonly dateFieldNames = input<string[]>([]);
    /** The rest of the offer, after the date-typed suggestions. */
    readonly nonDateFieldNames = computed(() => {
        const dates = new Set(this.dateFieldNames());
        return this.fieldNames().filter((f) => !dates.has(f));
    });

    readonly types = PARTITION_TYPES;
    readonly form: FormGroup = this.fb.group({ rows: this.fb.array<FormGroup>([]) });
    get partitionRows(): FormArray<FormGroup> {
        return this.form.controls['rows'] as FormArray<FormGroup>;
    }

    /** The launcher's source pick — a signal, because it is a launcher, not saved state. */
    readonly dateSource = signal('');
    /** The launcher's grain: how deep the date directories nest. Day is the historical default. */
    readonly grain = signal<'year' | 'month' | 'day'>('day');
    /** The CVA bindings for the two pickers — mirrors of the signals above, never read directly. */
    readonly dateSourceCtrl = new FormControl('', { nonNullable: true });
    readonly grainCtrl = new FormControl<'year' | 'month' | 'day'>('day', { nonNullable: true });

    /** Date-typed suggestions first (hinted in the popup), the rest of the schema after. */
    readonly dateFieldOptions = computed<PickerOption[]>(() => [
        ...this.dateFieldNames().map((f) => ({ value: f, label: f, hint: 'date-typed' })),
        ...this.nonDateFieldNames().map((f) => ({ value: f, label: f })),
    ]);
    readonly grainOptions: PickerOption[] = [
        { value: 'year', label: 'Year' },
        { value: 'month', label: 'Year + Month' },
        { value: 'day', label: 'Year + Month + Day' },
    ];
    /** Bumped on any structural change so computed warnings re-derive (FormArray is not a signal). */
    private readonly version = signal(0);

    constructor() {
        effect(() => {
            const seed = this.initial();
            this.partitionRows.clear();
            for (const r of seed) this.pushRow(r);
            this.version.update((v) => v + 1);
            // A freshly seeded grid is pristine: the host has applied nothing yet.
            this.form.markAsPristine();
        });
        // Smart pre-pick: exactly ONE date-typed field is an unambiguous suggestion — offer it
        // pre-selected. Two or more is the operator's call (guessing which date is THE event time is
        // how wrong bounds happen), and the pick never overrides one they already made.
        effect(() => {
            const dates = this.dateFieldNames();
            if (dates.length === 1 && !this.dateSource()) this.dateSource.set(dates[0]);
        });
        // Signal ↔ control mirror: the signals stay the API (the spec and addDateGrain read them),
        // the controls are only the pickers' CVA binding. Guarded so neither side loops.
        this.dateSourceCtrl.valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => this.dateSource.set(v));
        this.grainCtrl.valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => this.grain.set(v));
        effect(() => {
            const v = this.dateSource();
            if (this.dateSourceCtrl.value !== v) this.dateSourceCtrl.setValue(v, { emitEvent: false });
        });
        effect(() => {
            const v = this.grain();
            if (this.grainCtrl.value !== v) this.grainCtrl.setValue(v, { emitEvent: false });
        });
    }

    /** A stored row may reference a field the schema no longer carries — keep it listed so the
     *  stored value stays visible and editable rather than silently blanking. */
    sourceChoices(g: FormGroup): string[] {
        const current = String(g.get('source')?.value ?? '');
        const names = this.fieldNames();
        return current && !names.includes(current) ? [current, ...names] : names;
    }

    readonly mixedDateSources = computed(() => {
        this.version();
        const dateSources = new Set(
            this.partitionRows.controls
                .map((g) => g.getRawValue() as SchemaPartitionRow)
                .filter((r) => DATE_TYPES.has(r.type))
                .map((r) => r.source)
                .filter((s) => !!s),
        );
        return dateSources.size > 1;
    });

    private pushRow(r: SchemaPartitionRow): void {
        this.partitionRows.push(
            this.fb.group({
                column: [r.column, [Validators.required, Validators.pattern(/^[A-Za-z][A-Za-z0-9_-]*$/)]],
                source: [r.source, Validators.required],
                type: [r.type || 'VARCHAR', Validators.required],
            }),
        );
    }

    addRow(): void {
        this.pushRow({ column: '', source: '', type: 'VARCHAR' });
        this.form.markAsDirty();
        this.emitChanged();
    }

    /**
     * The date scheme in one click, at the picked grain — Year, Year+Month, or Year+Month+Day — all
     * cut from ONE field (the single-event-time rule `eventTimeDef` rewards). A shallower grain is a
     * real choice, not a shortcut: monthly directories for a low-volume feed are 12 healthy files a
     * year instead of 365 tiny ones.
     */
    addDateGrain(): void {
        const src = this.dateSource();
        if (!src) return;
        this.pushRow({ column: 'year', source: src, type: 'DATE_YEAR' });
        if (this.grain() !== 'year') this.pushRow({ column: 'month', source: src, type: 'DATE_MONTH' });
        if (this.grain() === 'day') this.pushRow({ column: 'day', source: src, type: 'DATE_DAY' });
        this.form.markAsDirty();
        this.emitChanged();
    }

    removeRow(g: FormGroup): void {
        const i = this.partitionRows.controls.indexOf(g);
        if (i >= 0) this.partitionRows.removeAt(i);
        this.form.markAsDirty();
        this.emitChanged();
    }

    emitChanged(): void {
        this.version.update((v) => v + 1);
    }

    /** Every row must name a segment (a valid identifier) and a source field. */
    validate(): boolean {
        this.form.markAllAsTouched();
        return this.partitionRows.controls.every((g) => g.valid);
    }

    /** The rows as the toon spells them, trimmed. */
    value(): SchemaPartitionRow[] {
        return this.partitionRows.controls.map((g) => {
            const v = g.getRawValue() as SchemaPartitionRow;
            return { column: v.column.trim(), source: v.source.trim(), type: v.type };
        });
    }

    markPristine(): void {
        this.form.markAsPristine();
    }
}
