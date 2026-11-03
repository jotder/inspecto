import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { ComponentsService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { DatasetColumnsService } from '@inspecto/link-analysis/api/dataset-columns.service';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';
import { ImportDatasetCardComponent } from '@inspecto/link-analysis/add-data/import-dataset-card.component';
import { ADD_DATA_ROUTE } from './first-run';
import {
    DataEntry,
    MAPPING_NAME,
    MappingForm,
    TypeChoice,
    dataEntry,
    driftedColumns,
    formOf,
    mappingEntry,
    mappingLine,
    typeOf,
} from './data-page';

/**
 * The **Data** page (LA-DATA-PAGE-1, plan `docs/superpower/la-data-arrival-plan.md` §8): every Dataset that may be a
 * Mapping's base (LA-PIPELINE-MAPPINGS-1: Add data ones, then stored ones kept up to date elsewhere) with its card (an
 * import's Data status, Arrivals, Add a file, Run now; a short one otherwise), its **Entities** (what each column holds,
 * editable) and its **Mappings** - each a Dataset of its own that reads the same data its own way (From, To, Kind of
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
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        InspectoAlertComponent,
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

                        <div class="bg-card flex flex-col gap-2 rounded-2xl border p-4" data-testid="entities">
                            <h3 class="m-0 text-base font-semibold">Entities</h3>
                            <p class="text-secondary m-0 text-sm">
                                What each column holds. A column with no Entity Type is a detail.
                            </p>
                            <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
                                @for (col of d.columns; track col.name) {
                                    <mat-form-field subscriptSizing="dynamic">
                                        <mat-label>{{ col.name }}</mat-label>
                                        <mat-select
                                            [value]="typeOf(col.classification)?.id ?? ''"
                                            [attr.data-testid]="'entity-' + col.name"
                                            (selectionChange)="setEntityType(d, col.name, $event.value)"
                                        >
                                            <mat-option value="">Nothing: a detail</mat-option>
                                            @for (t of types(); track t.id) {
                                                <mat-option [value]="t.id">{{ t.label }}</mat-option>
                                            }
                                        </mat-select>
                                    </mat-form-field>
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
                                                    Reads {{ gone.length === 1 ? 'a column' : 'columns' }}
                                                    {{ d.id }} no longer has: {{ gone.join(', ') }}. Edit the
                                                    Mapping, or it cannot be read.
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
                                    <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
                                        <mat-form-field>
                                            <mat-label>Mapping name</mat-label>
                                            <input matInput formControlName="name" data-testid="mapping-name" />
                                            @if (form.controls.name.hasError('required')) {
                                                <mat-error>A name is required.</mat-error>
                                            } @else if (form.controls.name.hasError('pattern')) {
                                                <mat-error>A lower-case letter, then letters, digits or _.</mat-error>
                                            } @else if (form.controls.name.hasError('taken')) {
                                                <mat-error>A Dataset of that name exists.</mat-error>
                                            }
                                        </mat-form-field>
                                        <mat-form-field>
                                            <mat-label>Description</mat-label>
                                            <input matInput formControlName="description" />
                                        </mat-form-field>
                                        <mat-form-field>
                                            <mat-label>From</mat-label>
                                            <mat-select formControlName="from" data-testid="mapping-from">
                                                @for (c of d.columns; track c.name) {
                                                    <mat-option [value]="c.name">{{ c.name }}</mat-option>
                                                }
                                            </mat-select>
                                            @if (form.controls.from.hasError('required')) {
                                                <mat-error>Choose the column each link starts from.</mat-error>
                                            }
                                        </mat-form-field>
                                        <mat-form-field>
                                            <mat-label>From holds</mat-label>
                                            <mat-select formControlName="fromType">
                                                <mat-option value="">Nothing: a detail</mat-option>
                                                @for (t of types(); track t.id) {
                                                    <mat-option [value]="t.id">{{ t.label }}</mat-option>
                                                }
                                            </mat-select>
                                        </mat-form-field>
                                        <mat-form-field>
                                            <mat-label>To</mat-label>
                                            <mat-select formControlName="to" data-testid="mapping-to">
                                                @for (c of d.columns; track c.name) {
                                                    <mat-option [value]="c.name">{{ c.name }}</mat-option>
                                                }
                                            </mat-select>
                                            @if (form.controls.to.hasError('required')) {
                                                <mat-error>Choose the column each link goes to.</mat-error>
                                            } @else if (form.controls.to.hasError('same')) {
                                                <mat-error>From and To must be two different columns.</mat-error>
                                            }
                                        </mat-form-field>
                                        <mat-form-field>
                                            <mat-label>To holds</mat-label>
                                            <mat-select formControlName="toType">
                                                <mat-option value="">Nothing: a detail</mat-option>
                                                @for (t of types(); track t.id) {
                                                    <mat-option [value]="t.id">{{ t.label }}</mat-option>
                                                }
                                            </mat-select>
                                        </mat-form-field>
                                        @for (opt of optional; track opt.key) {
                                            <mat-form-field>
                                                <mat-label>{{ opt.label }}</mat-label>
                                                <mat-select [formControlName]="opt.key">
                                                    <mat-option value="">None</mat-option>
                                                    @for (c of d.columns; track c.name) {
                                                        <mat-option [value]="c.name">{{ c.name }}</mat-option>
                                                    }
                                                </mat-select>
                                            </mat-form-field>
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
        { key: 'kind', label: 'Kind of link' },
        { key: 'time', label: 'When' },
        { key: 'amount', label: 'Amount' },
    ] as const;

    /** Every Dataset entry; null while they load. */
    readonly entries = signal<DataEntry[] | null>(null);
    readonly types = signal<TypeChoice[]>([]);
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
        time: [''],
        amount: [''],
    });

    constructor() {
        this.form.controls.from.valueChanges
            .pipe(takeUntilDestroyed())
            .subscribe(() => this.form.controls.to.updateValueAndValidity({ emitEvent: false }));
    }

    ngOnInit(): void {
        this.load();
        this.profiles
            .entityTypes()
            .pipe(catchError(() => of({ items: [] })))
            .subscribe((l) =>
                this.types.set(
                    (l.items ?? []).map((t) => ({
                        id: t.id,
                        label: t.label,
                        classifications: t.classifications ?? [],
                    })),
                ),
            );
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
                this.entries.set(list);
                this.form.controls.name.updateValueAndValidity({ emitEvent: false }); // "taken" reads this list
            });
    }

    protected typeOf(classification: string | null): TypeChoice | null {
        return typeOf(classification, this.types());
    }

    mappingsOf(base: string): DataEntry[] {
        return (this.entries() ?? []).filter((e) => e.origin === 'mapping' && e.basedOn === base);
    }

    private taken(name: string): boolean {
        const e = this.editing();
        return !!name && !e?.id && (this.entries() ?? []).some((x) => x.id === name);
    }

    protected setEntityType(d: DataEntry, column: string, typeId: string): void {
        this.error.set('');
        this.columns.setEntityType(d.id, column, typeId || null).subscribe({
            next: () => this.load(),
            error: (e) => this.error.set(apiErrorMessage(e, `What ${column} holds could not be changed.`)),
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
