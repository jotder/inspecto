import { NgTemplateOutlet } from '@angular/common';
import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    TemplateRef,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { FormArray, FormBuilder, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { Subscription } from 'rxjs';
import { InspectoClassificationSelectComponent } from '../components/classification-select.component';
import { InspectoOptionPickerComponent, PickerOption } from '../components/option-picker.component';
import { PenFieldComponent } from '../components/pen-field.component';
import { BASIC_COLUMN_TYPES, BASIC_COLUMN_TYPE_LABELS } from './basic-column-types';
import { SchemaFieldRow } from './schema-fields-editor.component';

/**
 * One row of the grid's `types` mode: a source column (`selector`, never edited), the name the **mapping** gives it, its
 * type, and whether the mapping leaves it out (BASIC-ELT-PROFILE-1 O3: Basic's rename and *Leave out* live in the
 * mapping, never in `raw.fields[]` or `transform.sql`).
 */
export interface SchemaTypeRow {
    selector: string;
    name: string;
    type: string;
    leaveOut: boolean;
}

/** One edit a person made in `types` mode, for the host to write into its mapping. */
export type SchemaTypeRowChange = { selector: string } & ({ name: string } | { type: string } | { leaveOut: boolean });

type TypeRowGroup = FormGroup<{
    selector: FormControl<string>;
    name: FormControl<string>;
    type: FormControl<string>;
    leaveOut: FormControl<boolean>;
}>;

/** The per-column metadata this grid edits — `raw.fields[].{description,unit,classification}`,
 *  Catalog-facing and never read by the ETL (the same tier as `synonym`). */
export interface SchemaFieldMetadata {
    description?: string;
    unit?: string;
    classification?: string;
}

/**
 * The **column-metadata grid** (D1(b), delimited-grammar-properties plan) — description / unit /
 * classification per column, projected into the grammar editor's `[tabTypes]` slot under Output
 * schema (behind the Column metadata… disclosure). The model shipped long ago; this is its first UI.
 *
 * <p><b>A second VIEW over the columns table's rows, not a second owner.</b> It seeds from the same
 * `[rows]` signal as `<inspecto-schema-fields-editor>` and edits ONLY the three metadata keys —
 * identity columns (# / name) render read-only, keyed by the row's `selector` (stable and read-only
 * in the columns table, so a rename over in tab 2 can never orphan a metadata edit). The host merges
 * {@link value} onto the columns table's rows by selector at submit.
 *
 * <p><b>Pure</b>, the fields editor's contract: no API client, hosts read {@link value} and own every
 * write. Description and unit are free text; classification is picked from the Space's vocabulary by
 * `<inspecto-classification-select>` (which reads it), because a save refuses a class the Space does not know.
 *
 * <p><b>`mode="types"`</b> (BASIC-ELT-PROFILE-1 U1a) is the compact column review Basic's Add data shows: one row per
 * source column with the name the mapping gives it, a type from {@link BASIC_COLUMN_TYPES}, *Leave out*, and the host's
 * evidence/Confirm slot ({@link rowDetail}) under each kept row. It seeds from {@link typeRows} and emits every edit as
 * one {@link rowChange}; the host writes it into its own mapping (O3) — this mode never touches `raw.fields[]`,
 * {@link value} or `transform.sql`. A host update with the same columns is applied in place, so an open name box keeps
 * its caret; different columns rebuild the rows.
 */
@Component({
    selector: 'inspecto-schema-metadata-grid',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        NgTemplateOutlet,
        ReactiveFormsModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatInputModule,
        MatPaginatorModule,
        InspectoClassificationSelectComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    template: `
        @if (mode() === 'types') {
            <ul class="m-0 flex list-none flex-col gap-2 p-0" data-testid="column-list">
                @for (g of typeGroups(); track g.controls.selector.value; let i = $index) {
                    <li
                        class="flex flex-col gap-1 rounded-lg border p-2"
                        [class]="rowClasses().get(g.controls.selector.value) ?? ''"
                        [formGroup]="g"
                        [attr.data-column]="g.controls.selector.value"
                    >
                        <div class="grid grid-cols-1 items-center gap-2 sm:grid-cols-[2fr_2fr_auto]">
                            <inspecto-pen-field
                                formControlName="name"
                                [label]="'Column ' + (i + 1)"
                                [ariaLabel]="'Name of column ' + g.controls.selector.value"
                                [readOnly]="g.controls.leaveOut.value"
                                [error]="rowErrors().get(g.controls.selector.value) ?? ''"
                            ></inspecto-pen-field>
                            <inspecto-option-picker
                                label="Type"
                                formControlName="type"
                                [attr.data-testid]="'type-' + g.controls.selector.value"
                                [options]="typeOptions"
                                [readOnly]="g.controls.leaveOut.value"
                            ></inspecto-option-picker>
                            <mat-checkbox formControlName="leaveOut" data-testid="drop-column">Leave out</mat-checkbox>
                        </div>
                        @if (!g.controls.leaveOut.value && rowDetail(); as detail) {
                            <ng-container
                                *ngTemplateOutlet="detail; context: { $implicit: g.controls.selector.value }"
                            ></ng-container>
                        }
                    </li>
                }
            </ul>
        } @else {
            <table class="w-full border-collapse">
                <thead>
                    <tr class="text-secondary text-left text-xs font-semibold uppercase tracking-wider">
                        <th scope="col" class="w-16 pb-1">#</th>
                        <th scope="col" class="w-40 pb-1">Name</th>
                        <th scope="col" class="pb-1">Description</th>
                        <th scope="col" class="w-28 pb-1">Unit</th>
                        <th scope="col" class="w-36 pb-1">Classification</th>
                    </tr>
                </thead>
                <tbody>
                    @for (e of pagedEntries(); track e.selector) {
                        <tr [formGroup]="e.group" class="border-t align-middle">
                            <td class="py-1 font-mono text-xs opacity-70">{{ e.selector }}</td>
                            <td class="truncate py-1 text-sm">{{ e.name }}</td>
                            <td class="py-1 pr-2">
                                <input
                                    class="w-full bg-transparent text-sm"
                                    formControlName="description"
                                    [attr.aria-label]="'Description for ' + (e.name || e.selector)"
                                />
                            </td>
                            <td class="py-1 pr-2">
                                <input
                                    class="w-full bg-transparent text-sm"
                                    formControlName="unit"
                                    [attr.aria-label]="'Unit for ' + (e.name || e.selector)"
                                />
                            </td>
                            <td class="py-1">
                                <inspecto-classification-select
                                    [label]="'Classification for ' + (e.name || e.selector)"
                                    [value]="e.group.get('classification')?.value ?? ''"
                                    (valueChange)="setClassification(e.group, $event)"
                                />
                            </td>
                        </tr>
                    }
                </tbody>
            </table>
            @if (rows().length > pageSize()) {
                <mat-paginator
                    [length]="rows().length"
                    [pageIndex]="pageIndex()"
                    [pageSize]="pageSize()"
                    [pageSizeOptions]="[50, 100, 250]"
                    (page)="onPage($event)"
                    aria-label="Column metadata pages"
                ></mat-paginator>
            }
        }
    `,
})
export class InspectoSchemaMetadataGridComponent {
    private fb = inject(FormBuilder);

    /** `metadata` (default): description / unit / classification over {@link rows}. `types`: the compact column review. */
    readonly mode = input<'metadata' | 'types'>('metadata');
    /** `types` mode: the columns to review, in order. */
    readonly typeRows = input<SchemaTypeRow[]>([]);
    /** `types` mode: a row's reason under its name, by selector (a name the host refuses). */
    readonly rowErrors = input<ReadonlyMap<string, string>>(new Map());
    /** `types` mode: extra classes for a row, by selector (the host's highlight of a guess, always paired with words). */
    readonly rowClasses = input<ReadonlyMap<string, string>>(new Map());
    /** `types` mode: the host's evidence/Confirm slot under each kept row; its context is the row's selector. */
    readonly rowDetail = input<TemplateRef<{ $implicit: string }> | null>(null);
    /** `types` mode: one edit a person made (never one {@link typeRows} applied). */
    readonly rowChange = output<SchemaTypeRowChange>();

    /** The types `types` mode offers: Basic's named subset of `SCHEMA_TYPES`. */
    protected readonly typeOptions: PickerOption[] = BASIC_COLUMN_TYPES.map((t) => ({
        value: t,
        label: BASIC_COLUMN_TYPE_LABELS[t],
    }));
    private readonly typeArray = this.fb.nonNullable.array<TypeRowGroup>([]);
    private readonly typeVersion = signal(0);
    protected readonly typeGroups = computed(() => {
        this.typeVersion();
        return [...this.typeArray.controls];
    });
    private typeSubs = new Subscription();

    /**
     * The columns to annotate — the SAME signal the columns table is seeded from, so both grids
     * rebuild together on a reference change. Metadata already on the rows (a hydrated schema, a
     * CSV import) seeds the inputs.
     */
    readonly rows = input<SchemaFieldRow[]>([]);

    readonly form: FormGroup = this.fb.group({ fields: this.fb.array<FormGroup>([]) });
    private get fieldRows(): FormArray<FormGroup> {
        return this.form.controls['fields'] as FormArray<FormGroup>;
    }

    readonly pageIndex = signal(0);
    readonly pageSize = signal(50);
    private readonly structureVersion = signal(0);

    private entries(): { group: FormGroup; selector: string; name: string }[] {
        return this.fieldRows.controls.map((group) => ({
            group,
            selector: String(group.get('selector')?.value ?? ''),
            name: String(group.get('name')?.value ?? ''),
        }));
    }

    readonly pagedEntries = computed(() => {
        this.structureVersion();
        const start = this.pageIndex() * this.pageSize();
        return this.entries().slice(start, start + this.pageSize());
    });

    constructor() {
        effect(() => {
            const seed = this.rows();
            this.fieldRows.clear();
            for (const r of seed) {
                this.fieldRows.push(
                    this.fb.group({
                        selector: [{ value: r.selector, disabled: true }],
                        name: [{ value: r.name, disabled: true }],
                        description: [r.description ?? ''],
                        unit: [r.unit ?? ''],
                        classification: [r.classification ?? ''],
                    }),
                );
            }
            this.pageIndex.set(0);
            this.structureVersion.update((v) => v + 1);
            // A freshly seeded grid is pristine: the host has applied nothing yet.
            this.form.markAsPristine();
        });
        effect(() => this.seedTypes(this.typeRows()));
        inject(DestroyRef).onDestroy(() => this.typeSubs.unsubscribe());
    }

    /**
     * The same columns in the same order: their values are applied in place without an event (the host's own update is
     * never echoed back as a person's edit, and an open name box keeps its caret). Other columns rebuild the rows.
     */
    private seedTypes(rows: SchemaTypeRow[]): void {
        const same =
            rows.length === this.typeArray.length &&
            rows.every((r, i) => this.typeArray.at(i).controls.selector.value === r.selector);
        if (same) {
            rows.forEach((r, i) =>
                this.typeArray
                    .at(i)
                    .patchValue({ name: r.name, type: r.type, leaveOut: r.leaveOut }, { emitEvent: false }),
            );
        } else {
            this.typeSubs.unsubscribe();
            this.typeSubs = new Subscription();
            this.typeArray.clear({ emitEvent: false });
            for (const r of rows) this.typeArray.push(this.typeGroup(r), { emitEvent: false });
        }
        this.typeVersion.update((v) => v + 1);
    }

    private typeGroup(r: SchemaTypeRow): TypeRowGroup {
        const g: TypeRowGroup = this.fb.nonNullable.group({
            selector: [r.selector],
            name: [r.name],
            type: [r.type],
            leaveOut: [r.leaveOut],
        });
        const selector = r.selector;
        this.typeSubs.add(g.controls.name.valueChanges.subscribe((name) => this.rowChange.emit({ selector, name })));
        this.typeSubs.add(g.controls.type.valueChanges.subscribe((type) => this.rowChange.emit({ selector, type })));
        this.typeSubs.add(
            g.controls.leaveOut.valueChanges.subscribe((leaveOut) => this.rowChange.emit({ selector, leaveOut })),
        );
        return g;
    }

    /** The classification picker's pick, written as a typed edit would be (dirty, so the host's Apply arms). */
    setClassification(group: FormGroup, value: string): void {
        const control = group.get('classification');
        control?.setValue(value);
        control?.markAsDirty();
    }

    onPage(e: PageEvent): void {
        this.pageIndex.set(e.pageIndex);
        this.pageSize.set(e.pageSize);
    }

    /** `selector → metadata`, trimmed; blank values are dropped so a cleared field removes its key. */
    value(): Map<string, SchemaFieldMetadata> {
        const out = new Map<string, SchemaFieldMetadata>();
        for (const g of this.fieldRows.controls) {
            const v = g.getRawValue() as Record<string, string>;
            const meta: SchemaFieldMetadata = {};
            for (const k of ['description', 'unit', 'classification'] as const) {
                const t = (v[k] ?? '').trim();
                if (t) meta[k] = t;
            }
            out.set(String(v['selector'] ?? ''), meta);
        }
        return out;
    }

    /** The metadata for these rows, merged by selector — what the host persists / exports. */
    applyTo(rows: SchemaFieldRow[]): SchemaFieldRow[] {
        const meta = this.value();
        return rows.map((r) => {
            const m = meta.get(String(r.selector));
            if (!m) return r;
            const { description: _d, unit: _u, classification: _c, ...rest } = r;
            return { ...rest, ...m };
        });
    }

    markPristine(): void {
        this.form.markAsPristine();
    }
}
