import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Router, RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { ComponentsService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { ColumnChange, DatasetColumnsService } from '@inspecto/link-analysis/api/dataset-columns.service';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';
import { EntityFamily, entityTypeOptions } from '@inspecto/link-analysis/api/entity-type-options';
import { ImportDatasetCardComponent } from '@inspecto/link-analysis/add-data/import-dataset-card.component';
import { ADD_DATA_ROUTE } from './first-run';
import {
    ColumnRole,
    DataColumn,
    DataEntry,
    MAPPING_NAME,
    MappingForm,
    ROLE_HINTS,
    ROLE_LABELS,
    TypeChoice,
    dataEntry,
    driftedColumns,
    formOf,
    mappingEntry,
    mappingLine,
    roleOf,
    typeOf,
} from './data-page';

/**
 * The **Data** page (LA-DATA-PAGE-1, plan `docs/superpower/la-data-arrival-plan.md` §8): every Dataset that may be a
 * Mapping's base (LA-PIPELINE-MAPPINGS-1: Add data ones, then stored ones kept up to date elsewhere) with its card (an
 * import's Data status, Arrivals, Add a file, Run now; a short one otherwise), its **Columns** (each column's role - Entity,
 * Time, Measure, Category, Detail - with what an Entity column holds and a Measure's unit, editable; LA-COLUMN-ROLES-1) and its **Mappings** - each a Dataset of its own that reads the same data its own way (From, To, Kind of
 * link, When, Amount), created, edited and deleted here. Every other Dataset is listed by name, with why.
 */
@Component({
    selector: 'la-app-data-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        RouterLink,
        MatButtonModule,
        MatIconModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        ImportDatasetCardComponent,
    ],
    template: `
        <div class="mx-auto flex w-full max-w-5xl flex-col gap-4 p-6">
            <div class="flex flex-wrap items-center gap-3">
                <h1 class="text-title m-0 font-semibold">Data</h1>
                <a mat-flat-button color="primary" class="ml-auto" [routerLink]="addData" data-testid="data-add">
                    <mat-icon svgIcon="heroicons_outline:arrow-up-tray"></mat-icon>
                    Add data
                </a>
            </div>
            <p class="text-secondary m-0 text-sm">
                Each Dataset you added or a Pipeline keeps up to date, what its columns hold, and its Mappings: the ways
                its rows are read as links. A Mapping is a Dataset of its own over the same data.
            </p>
            @if (error()) {
                <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
            }
            @if (entries() === null) {
                <inspecto-skeleton [lines]="4"></inspecto-skeleton>
            } @else if (!bases().length) {
                <inspecto-empty-state
                    icon="heroicons_outline:circle-stack"
                    title="No Dataset added yet"
                    message="Add a file of events between entities and it becomes a Dataset you can explore."
                    actionLabel="Add data"
                    (action)="goAddData()"
                ></inspecto-empty-state>
            } @else {
                @for (d of bases(); track d.id) {
                    <section
                        class="flex flex-col gap-3"
                        [attr.aria-labelledby]="'data-' + d.id"
                        [attr.data-testid]="'data-' + d.id"
                    >
                        <h2 class="m-0 text-lg font-semibold" [id]="'data-' + d.id">{{ d.id }}</h2>
                        @if (d.origin === 'import') {
                            <inspecto-import-dataset-card
                                [dataset]="{
                                    id: d.id,
                                    kind: d.kind,
                                    description: d.description,
                                    importPipeline: d.importPipeline ?? d.id,
                                }"
                            ></inspecto-import-dataset-card>
                        } @else {
                            <div class="bg-card flex flex-col gap-1 rounded-2xl border p-4" data-testid="pipeline-card">
                                <p class="m-0 text-sm font-semibold">Kept up to date by a Pipeline or a Job</p>
                                @if (d.description) {
                                    <p class="text-secondary m-0 text-sm">{{ d.description }}</p>
                                }
                                <p class="text-secondary m-0 text-sm">
                                    Its data is written there, not added here. What its columns hold and its Mappings
                                    are set here.
                                </p>
                            </div>
                        }

                        <div class="bg-card flex flex-col gap-2 rounded-2xl border p-4" data-testid="columns">
                            <h3 class="m-0 text-base font-semibold">Columns</h3>
                            <p class="text-secondary m-0 text-sm">
                                What each column is: an Entity it holds, a Time, a Measure, a Category or a Detail. A
                                Dataset may have several of each.
                            </p>
                            <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2">
                                @for (col of d.columns; track col.name) {
                                    @let r = roleShown(d, col);
                                    <div class="flex flex-col" [attr.data-testid]="'column-' + col.name">
                                        <inspecto-option-picker
                                            [label]="col.name"
                                            [options]="roleOptions"
                                            [formControl]="roles().get(d.id)!.get(col.name)!"
                                            [attr.data-testid]="'role-' + col.name"
                                        ></inspecto-option-picker>
                                        @if (r === 'entity') {
                                            <inspecto-option-picker
                                                [label]="col.name + ' holds'"
                                                [options]="typeOptions()"
                                                [formControl]="holds().get(d.id)!.get(col.name)!"
                                                [attr.data-testid]="'entity-' + col.name"
                                            ></inspecto-option-picker>
                                            @if (!holds().get(d.id)!.get(col.name)!.value) {
                                                <p class="text-warn m-0 text-xs" role="alert">
                                                    Pick the Entity Type it holds to save it as an Entity.
                                                </p>
                                            }
                                        } @else if (r === 'measure') {
                                            <inspecto-pen-field
                                                label="Unit"
                                                [ariaLabel]="col.name + ' unit'"
                                                placeholder="USD, s, bytes"
                                                [maxlength]="32"
                                                help="What it counts in"
                                                [formControl]="units().get(d.id)!.get(col.name)!"
                                                [attr.data-testid]="'unit-' + col.name"
                                            ></inspecto-pen-field>
                                        }
                                    </div>
                                }
                            </div>
                        </div>

                        <div class="bg-card flex flex-col gap-2 rounded-2xl border p-4" data-testid="mappings">
                            <div class="flex flex-wrap items-center gap-2">
                                <h3 class="m-0 text-base font-semibold">Mappings</h3>
                                <button
                                    mat-stroked-button
                                    type="button"
                                    class="ml-auto"
                                    data-testid="mapping-new"
                                    (click)="newMapping(d)"
                                >
                                    <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                                    New mapping
                                </button>
                            </div>
                            <ul class="m-0 flex list-none flex-col gap-2 p-0 text-sm">
                                <li class="flex flex-wrap items-center gap-2">
                                    <span class="font-medium">{{ d.id }}</span>
                                    <span class="text-secondary">(its own) · {{ line(d.mapping) }}</span>
                                </li>
                                @for (m of mappingsOf(d.id); track m.id) {
                                    <li
                                        class="flex flex-wrap items-center gap-2"
                                        [attr.data-testid]="'mapping-' + m.id"
                                    >
                                        <span class="font-medium">{{ m.id }}</span>
                                        <span class="text-secondary">· {{ line(m.mapping) }}</span>
                                        @if (drifted(m, d); as gone) {
                                            @if (gone.length) {
                                                <span class="text-warn w-full text-sm" data-testid="mapping-drift">
                                                    Reads {{ gone.length === 1 ? 'a column' : 'columns' }} {{ d.id }} no
                                                    longer has: {{ gone.join(', ') }}. Edit the Mapping, or it cannot be
                                                    read.
                                                </span>
                                            }
                                        }
                                        <span class="ml-auto flex gap-1">
                                            <a
                                                mat-button
                                                [routerLink]="'/link-analysis'"
                                                [queryParams]="{ dataset: m.id }"
                                                [attr.aria-label]="'Explore the Mapping ' + m.id"
                                                >Explore</a
                                            >
                                            <button
                                                mat-button
                                                type="button"
                                                (click)="editMapping(d, m)"
                                                [attr.aria-label]="'Edit the Mapping ' + m.id"
                                            >
                                                Edit
                                            </button>
                                            <button
                                                mat-button
                                                type="button"
                                                (click)="deleteMapping(m)"
                                                [attr.aria-label]="'Delete the Mapping ' + m.id"
                                            >
                                                Delete
                                            </button>
                                        </span>
                                    </li>
                                }
                            </ul>
                            @if (editing()?.base?.id === d.id) {
                                <form
                                    class="flex flex-col gap-2 border-t pt-3"
                                    [formGroup]="form"
                                    (ngSubmit)="saveMapping()"
                                    [attr.aria-label]="
                                        editing()?.id ? 'Edit the Mapping ' + editing()?.id : 'New mapping of ' + d.id
                                    "
                                    data-testid="mapping-form"
                                >
                                    <!-- property rows (label · value · ✎ / ▾), two per line: the whole form at a glance -->
                                    <div class="grid grid-cols-1 gap-x-8 gap-y-1 sm:grid-cols-2">
                                        <inspecto-pen-field
                                            label="Mapping name"
                                            formControlName="name"
                                            data-testid="mapping-name"
                                            [required]="true"
                                            [error]="nameError()"
                                            help="Lower-case letters, digits and _"
                                        ></inspecto-pen-field>
                                        <inspecto-pen-field
                                            label="Description"
                                            formControlName="description"
                                        ></inspecto-pen-field>
                                        <!-- validated on save: the page draws the required line itself (the picker
                                             shows its own only once its dialog was opened), so no [required] here -->
                                        <div>
                                            <inspecto-option-picker
                                                label="From"
                                                formControlName="from"
                                                data-testid="mapping-from"
                                                [options]="columnOptions(d)"
                                                help="The column each link starts from"
                                            ></inspecto-option-picker>
                                            @if (
                                                form.controls.from.touched && form.controls.from.hasError('required')
                                            ) {
                                                <p class="text-warn m-0 text-xs" role="alert">
                                                    Choose the column each link starts from.
                                                </p>
                                            }
                                        </div>
                                        <inspecto-option-picker
                                            label="From holds"
                                            formControlName="fromType"
                                            [options]="typeOptions()"
                                            help="Links join where columns hold the same Entity Type"
                                        ></inspecto-option-picker>
                                        <div>
                                            <inspecto-option-picker
                                                label="To"
                                                formControlName="to"
                                                data-testid="mapping-to"
                                                [options]="columnOptions(d)"
                                                help="The column each link goes to"
                                            ></inspecto-option-picker>
                                            @if (form.controls.to.touched && form.controls.to.hasError('required')) {
                                                <p class="text-warn m-0 text-xs" role="alert">
                                                    Choose the column each link goes to.
                                                </p>
                                            } @else if (form.controls.to.touched && form.controls.to.hasError('same')) {
                                                <p class="text-warn m-0 text-xs" role="alert">
                                                    From and To must be two different columns.
                                                </p>
                                            }
                                        </div>
                                        <inspecto-option-picker
                                            label="To holds"
                                            formControlName="toType"
                                            [options]="typeOptions()"
                                        ></inspecto-option-picker>
                                        @for (opt of optional; track opt.key) {
                                            <inspecto-option-picker
                                                [label]="opt.label"
                                                [formControlName]="opt.key"
                                                [attr.data-testid]="'mapping-' + opt.key"
                                                [options]="noneOr(d, opt.role)"
                                                [help]="opt.help"
                                                placeholder="None"
                                            ></inspecto-option-picker>
                                        }
                                    </div>
                                    @if (saveError()) {
                                        <inspecto-alert variant="error">{{ saveError() }}</inspecto-alert>
                                    }
                                    <div class="flex gap-2">
                                        <button
                                            mat-flat-button
                                            color="primary"
                                            type="submit"
                                            [disabled]="saving()"
                                            data-testid="mapping-save"
                                        >
                                            Save Mapping
                                        </button>
                                        <button mat-button type="button" (click)="editing.set(null)">Cancel</button>
                                    </div>
                                </form>
                            }
                        </div>
                    </section>
                }
            }
            @if (others().length) {
                <section class="flex flex-col gap-1" aria-labelledby="data-others">
                    <h2 class="m-0 text-base font-semibold" id="data-others">Other Datasets</h2>
                    <p class="text-secondary m-0 text-sm">
                        No Mapping is offered for these: each is computed when read, has no stored data, is an Entity
                        List's store, or lists fewer than two columns to read as From and To.
                        {{ others().join(', ') }}
                    </p>
                </section>
            }
        </div>
    `,
})
export class DataPageComponent implements OnInit {
    private readonly components = inject(ComponentsService);
    private readonly profiles = inject(EntityProfileService);
    private readonly columns = inject(DatasetColumnsService);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly fb = inject(FormBuilder);
    private readonly router = inject(Router);

    protected readonly addData = ADD_DATA_ROUTE;
    protected readonly line = mappingLine;
    protected readonly optional = [
        { key: 'kind', label: 'Kind of link', help: 'A column naming the kind of each link', role: null },
        { key: 'primaryTime', label: 'When', help: 'The Time column that dates each link', role: 'temporal' },
        { key: 'primaryMeasure', label: 'Measure', help: 'The Measure column that weighs each link', role: 'measure' },
    ] as const;

    /** What a column may hold: an Entity Type (grouped by family, with its definition and an example), or nothing (a detail). */
    protected readonly typeOptions = computed<PickerOption[]>(() => [
        { value: '', label: 'Nothing: a detail' },
        ...entityTypeOptions(this.types(), this.families()),
    ]);

    /** A base's columns to pick From / To from, each with what the base says it holds. */
    protected columnOptions(d: DataEntry): PickerOption[] {
        return d.columns.map((c) => ({
            value: c.name,
            label: c.name,
            hint: this.typeOf(c.classification)?.label ?? 'a detail',
        }));
    }

    /** An optional column: none, or one of the base's (only its Time or Measure columns for a primary, LA-COLUMN-ROLES-1 P1). */
    protected noneOr(d: DataEntry, role: ColumnRole | null = null): PickerOption[] {
        const fits = (name: string) => {
            const c = d.columns.find((x) => x.name === name);
            return !role || (!!c && roleOf(c, d, this.types()) === role);
        };
        return [{ value: '', label: 'None' }, ...this.columnOptions(d).filter((o) => fits(o.value))];
    }

    /** A column's role, in the words the page shows (LA-COLUMN-ROLES-1). */
    protected readonly roleOptions: PickerOption[] = (Object.keys(ROLE_LABELS) as ColumnRole[]).map((r) => ({
        value: r,
        label: ROLE_LABELS[r],
        hint: ROLE_HINTS[r],
    }));

    /**
     * One control per column of every base and per question (its role, what it holds, a measure's unit); a change saves
     * at once, as the Entities page does. Rebuilt whenever the Datasets or the Entity Types are read again, so a control
     * always shows the stored value.
     */
    private controls<T>(seed: (d: DataEntry, c: DataColumn) => T, save: (d: DataEntry, c: DataColumn, v: T) => void) {
        const out = new Map<string, Map<string, FormControl<T>>>();
        for (const d of this.bases()) {
            const controls = new Map<string, FormControl<T>>();
            for (const c of d.columns) {
                const ctrl = new FormControl(seed(d, c), { nonNullable: true });
                ctrl.valueChanges.subscribe((v) => save(d, c, v));
                controls.set(c.name, ctrl);
            }
            out.set(d.id, controls);
        }
        return out;
    }

    readonly holds = computed(() =>
        this.controls<string>(
            (_, c) => this.typeOf(c.classification)?.id ?? '',
            (d, c, v) => this.setHolds(d, c, v),
        ),
    );
    readonly roles = computed(() =>
        this.controls<ColumnRole>(
            (d, c) => roleOf(c, d, this.types()),
            (d, c, v) => this.setRole(d, c, v),
        ),
    );
    readonly units = computed(() =>
        this.controls<string>(
            (_, c) => c.unit ?? '',
            (d, c, v) => this.save(d, c, { role: 'measure', unit: (v ?? '').trim() || null }),
        ),
    );
    /** A role picked but not saved yet: Entity, until what it holds is picked. Cleared on every read. */
    private readonly picked = signal<ReadonlyMap<string, ColumnRole>>(new Map());

    protected roleShown(d: DataEntry, c: DataColumn): ColumnRole {
        return this.picked().get(`${d.id}/${c.name}`) ?? roleOf(c, d, this.types());
    }

    /** The name's error line, shown once the field was left or a save was tried. */
    protected nameError(): string {
        const c = this.form.controls.name;
        if (!c.touched || c.disabled) return '';
        if (c.hasError('required')) return 'A name is required.';
        if (c.hasError('pattern')) return 'A lower-case letter, then letters, digits or _.';
        if (c.hasError('taken')) return 'A Dataset of that name exists.';
        return '';
    }

    /** Every Dataset entry; null while they load. */
    readonly entries = signal<DataEntry[] | null>(null);
    readonly types = signal<TypeChoice[]>([]);
    /** The families the type choices group by, in the server's order. */
    readonly families = signal<EntityFamily[]>([]);
    readonly error = signal('');
    readonly imports = computed(() => (this.entries() ?? []).filter((e) => e.origin === 'import'));
    /** Every Dataset a Mapping may be based on: the imports first, then those Pipelines keep up to date. */
    readonly bases = computed(() => [
        ...this.imports(),
        ...(this.entries() ?? []).filter((e) => e.origin === 'other' && e.mappable),
    ]);
    readonly others = computed(() =>
        (this.entries() ?? []).filter((e) => e.origin === 'other' && !e.mappable).map((e) => e.id),
    );
    protected readonly drifted = driftedColumns;
    /** The open Mapping editor: its base, and the Mapping's id when editing one (null = a new one). */
    readonly editing = signal<{ base: DataEntry; id: string | null } | null>(null);
    readonly saving = signal(false);
    readonly saveError = signal('');

    readonly form = this.fb.nonNullable.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(MAPPING_NAME),
                (c: { value: string }) => (this.taken(c.value) ? { taken: true } : null),
            ],
        ],
        description: [''],
        from: ['', Validators.required],
        fromType: [''],
        // To must differ from From: a control-level rule, re-run whenever From changes (constructor)
        to: [
            '',
            [
                Validators.required,
                (c: AbstractControl<string>) =>
                    c.value && c.parent?.get('from')?.value === c.value ? { same: true } : null,
            ],
        ],
        toType: [''],
        kind: [''],
        primaryTime: [''],
        primaryMeasure: [''],
    });

    constructor() {
        this.form.controls.from.valueChanges.pipe(takeUntilDestroyed()).subscribe((from) => {
            this.form.controls.to.updateValueAndValidity({ emitEvent: false });
            this.holdsOf(from, this.form.controls.fromType);
        });
        this.form.controls.to.valueChanges
            .pipe(takeUntilDestroyed())
            .subscribe((to) => this.holdsOf(to, this.form.controls.toType));
    }

    ngOnInit(): void {
        this.load();
        this.profiles
            .entityTypes()
            .pipe(catchError(() => of({ items: [], families: [] })))
            .subscribe((l) => {
                this.families.set(l.families ?? []);
                this.types.set(
                    (l.items ?? []).map((t) => ({
                        id: t.id,
                        label: t.label,
                        classifications: t.classifications ?? [],
                        family: t.family,
                        definition: t.definition,
                        example: t.example,
                    })),
                );
            });
    }

    load(): void {
        this.components
            .list('dataset')
            .pipe(
                map((defs) =>
                    (Array.isArray(defs) ? defs : []).map(dataEntry).sort((a, b) => a.id.localeCompare(b.id)),
                ),
                catchError((e) => {
                    this.error.set(apiErrorMessage(e, 'The Datasets could not be read.'));
                    return of([] as DataEntry[]);
                }),
            )
            .subscribe((list) => {
                this.picked.set(new Map());
                this.entries.set(list);
                this.form.controls.name.updateValueAndValidity({ emitEvent: false }); // "taken" reads this list
            });
    }

    protected typeOf(classification: string | null): TypeChoice | null {
        return typeOf(classification, this.types());
    }

    /** A column just picked as From or To starts out holding what the base says it holds; the user may change it. */
    private holdsOf(column: string, holds: { setValue(v: string): void }): void {
        const base = this.editing()?.base;
        const col = base?.columns.find((c) => c.name === column);
        if (col) holds.setValue(typeOf(col.classification, this.types())?.id ?? '');
    }

    mappingsOf(base: string): DataEntry[] {
        return (this.entries() ?? []).filter((e) => e.origin === 'mapping' && e.basedOn === base);
    }

    private taken(name: string): boolean {
        const e = this.editing();
        return !!name && !e?.id && (this.entries() ?? []).some((x) => x.id === name);
    }

    /** What an Entity column holds: a type makes it an Entity column; none makes it a detail (the server drops the role). */
    private setHolds(d: DataEntry, c: DataColumn, typeId: string): void {
        this.save(d, c, typeId ? { entityType: typeId, role: 'entity' } : { entityType: null });
    }

    /** An Entity role waits for what it holds; any other role saves at once, and stops the column holding a type. */
    private setRole(d: DataEntry, c: DataColumn, role: ColumnRole): void {
        const held = this.holds().get(d.id)?.get(c.name)?.value ?? '';
        this.picked.update((m) => new Map(m).set(`${d.id}/${c.name}`, role));
        if (role === 'entity') {
            if (held) this.save(d, c, { role, entityType: held });
            return;
        }
        this.save(d, c, this.typeOf(c.classification) ? { role, entityType: null } : { role });
    }

    private save(d: DataEntry, c: DataColumn, change: ColumnChange): void {
        this.error.set('');
        this.columns.setColumn(d.id, c.name, change).subscribe({
            next: () => this.load(),
            error: (e) => {
                this.error.set(apiErrorMessage(e, `Column ${c.name} could not be changed.`));
                this.load(); // back to what is stored
            },
        });
    }

    newMapping(base: DataEntry): void {
        this.open(base, null, formOf(base, this.types()));
    }

    editMapping(base: DataEntry, m: DataEntry): void {
        this.open(base, m.id, formOf(m, this.types(), m.id));
    }

    private open(base: DataEntry, id: string | null, f: MappingForm): void {
        this.saveError.set('');
        this.editing.set({ base, id });
        // fromType / toType are reset after from / to (declaration order), so Edit keeps the Mapping's own "holds"
        this.form.reset(f);
        // an existing Mapping keeps its name (its id): not edited, so not re-judged by the naming rule
        if (id) this.form.controls.name.disable({ emitEvent: false });
        else this.form.controls.name.enable({ emitEvent: false });
        this.form.updateValueAndValidity();
    }

    saveMapping(): void {
        const e = this.editing();
        if (!e) return;
        this.form.updateValueAndValidity();
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const f = this.form.getRawValue();
        const content = mappingEntry(e.base, f, this.types());
        this.saving.set(true);
        this.saveError.set('');
        const save = e.id
            ? this.components.update('dataset', e.id, content)
            : this.components.create('dataset', { id: f.name, ...content });
        save.subscribe({
            next: () => {
                this.saving.set(false);
                this.editing.set(null);
                this.load();
            },
            error: (err) => {
                this.saving.set(false);
                this.saveError.set(apiErrorMessage(err, 'The Mapping could not be saved.'));
            },
        });
    }

    async deleteMapping(m: DataEntry): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Only the Mapping goes: the data of ${m.basedOn} stays, and so do its other Mappings.`,
            { title: `Delete the Mapping ${m.id}?`, confirmText: 'Delete Mapping' },
        );
        if (!ok) return;
        this.components.remove('dataset', m.id).subscribe({
            next: () => this.load(),
            error: (e) => this.error.set(apiErrorMessage(e, `The Mapping ${m.id} could not be deleted.`)),
        });
    }

    protected goAddData(): void {
        void this.router.navigateByUrl(this.addData);
    }
}
