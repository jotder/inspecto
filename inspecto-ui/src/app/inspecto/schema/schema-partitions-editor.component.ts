import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

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
                                <select
                                    class="w-full bg-transparent text-sm"
                                    formControlName="source"
                                    [attr.aria-label]="'Partition source field'"
                                    (change)="emitChanged()"
                                >
                                    <option value="" disabled>pick a field…</option>
                                    @for (f of sourceChoices(g); track f) {
                                        <option [value]="f">{{ f }}</option>
                                    }
                                </select>
                            </td>
                            <td class="py-1 pr-2">
                                <select
                                    class="w-full bg-transparent text-sm"
                                    formControlName="type"
                                    [attr.aria-label]="'Partition derivation type'"
                                    (change)="emitChanged()"
                                >
                                    @for (t of types; track t) {
                                        <option [value]="t">{{ t }}</option>
                                    }
                                </select>
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
                The date segments disagree on their source field — the engine will still write them, but it
                records no event-time bounds for a scheme with no single event time.
            </p>
        }
        <div class="mt-2 flex flex-wrap items-center gap-2">
            <select
                class="bg-transparent text-sm"
                aria-label="Date field for the year/month/day partitions"
                [value]="dateSource()"
                (change)="dateSource.set($any($event.target).value)"
            >
                <option value="" disabled [selected]="!dateSource()">date field…</option>
                @for (f of fieldNames(); track f) {
                    <option [value]="f">{{ f }}</option>
                }
            </select>
            <button
                mat-stroked-button
                type="button"
                class="!text-xs"
                [disabled]="!dateSource()"
                matTooltip="Adds year/month/day segments cut from the picked date field — the standard scheme"
                (click)="addDateTrio()"
            >
                Partition by date (year/month/day)
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

    readonly types = PARTITION_TYPES;
    readonly form: FormGroup = this.fb.group({ rows: this.fb.array<FormGroup>([]) });
    get partitionRows(): FormArray<FormGroup> {
        return this.form.controls['rows'] as FormArray<FormGroup>;
    }

    /** The trio button's source pick — deliberately NOT a form control, it is a launcher, not state. */
    readonly dateSource = signal('');
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

    /** The standard scheme in one click: year/month/day, all cut from the picked date field. */
    addDateTrio(): void {
        const src = this.dateSource();
        if (!src) return;
        this.pushRow({ column: 'year', source: src, type: 'DATE_YEAR' });
        this.pushRow({ column: 'month', source: src, type: 'DATE_MONTH' });
        this.pushRow({ column: 'day', source: src, type: 'DATE_DAY' });
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
