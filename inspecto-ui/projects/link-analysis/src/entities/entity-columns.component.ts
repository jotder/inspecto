import { Router } from '@angular/router';
import { TermHelpComponent } from '@inspecto/core/onboarding/term-help.component';
import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { Subscription } from 'rxjs';
import { ComponentsService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { LockedActionComponent } from '@inspecto/core/components/locked-action.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { DatasetColumnsService } from '@inspecto/link-analysis/api/dataset-columns.service';
import type { EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import {
    ColumnRow,
    DatasetEntry,
    EntitiesChange,
    columnNote,
    columnRows,
    datasetColumns,
    entityTypeOptions,
    primaryOf,
} from './entity-columns';

/** Why the column and primary controls are locked for a signed-in person without the Dataset-authoring right. */
export const COLUMNS_LOCKED_REASON =
    'Changing what a column holds needs permission to edit Datasets in this Space. Ask an administrator.';

interface RowView extends ColumnRow {
    key: string;
    note: string;
    ctl: FormControl<string | null>;
}

interface DatasetView {
    entry: DatasetEntry;
    listsColumns: boolean;
    primary: FormControl<string | null>;
    rows: RowView[];
}

/**
 * **Columns** (LA-ONBOARD-1 phase 5, UC-07 step 2): each Dataset column that looks like an identifier with the Entity
 * Type it holds or "Not set", and each Dataset's primary Entity Type. A change is written at once
 * (`PUT /datasets/{id}/columns/{column}`; the primary through the registry entry, `PUT /components/dataset/{id}`) and
 * reported to the host, which reads the Entity Types again and shows the result. Without `canEdit` (a signed-in person
 * lacking the right) the values read as text and the action shows locked with its reason.
 */
@Component({
    selector: 'inspecto-la-entity-columns',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatCheckboxModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoOptionPickerComponent,
        InspectoSkeletonComponent,
        LockedActionComponent,
        TermHelpComponent,
    ],
    template: `
        <div class="flex flex-col gap-4">
            <p class="text-secondary m-0 text-sm">
                Say which <inspecto-term term="entity-type"></inspecto-term> each identifier column of a
                <inspecto-term term="dataset"></inspecto-term> holds. Columns that hold the same Entity Type join up in
                the graph and share one profile.
            </p>
            @if (!canEdit()) {
                <inspecto-locked-action
                    label="Change what columns hold"
                    [reason]="lockedReason"
                    data-testid="columns-locked"
                ></inspecto-locked-action>
            }
            <p class="m-0 text-sm" role="status" data-testid="columns-result">{{ result() }}</p>
            @if (writeError(); as e) {
                <inspecto-alert variant="error" data-testid="columns-error">{{ e }}</inspecto-alert>
            }
            @if (loadError(); as e) {
                <inspecto-alert variant="error" data-testid="datasets-error">{{ e }}</inspecto-alert>
            } @else if (views(); as list) {
                @if (list.length) {
                    <mat-checkbox [formControl]="showAll" data-testid="show-all">Show every column</mat-checkbox>
                    @for (d of list; track d.entry.id) {
                        <section
                            class="bg-card flex flex-col gap-3 rounded-2xl p-5 shadow"
                            data-testid="dataset-columns"
                            [attr.aria-labelledby]="'ds-' + $index"
                        >
                            <div class="flex flex-wrap items-center justify-between gap-3">
                                <h2 class="m-0 font-mono text-base font-semibold" [id]="'ds-' + $index">
                                    {{ d.entry.id }}
                                </h2>
                                <div class="flex items-center gap-2 text-sm" data-testid="primary">
                                    @if (canEdit()) {
                                        <inspecto-option-picker
                                            data-testid="primary-picker"
                                            [attr.data-dataset]="d.entry.id"
                                            [label]="'Primary Entity Type of ' + d.entry.id"
                                            [hideLabel]="true"
                                            [outlined]="true"
                                            [options]="primaryOptions()"
                                            [formControl]="d.primary"
                                            help="The Entity Type this Dataset's graph leads with."
                                        ></inspecto-option-picker>
                                    } @else {
                                        <span class="text-secondary">Primary Entity Type</span>
                                        <span data-testid="primary-text">{{ labelOf(d.primary.value, 'none') }}</span>
                                    }
                                </div>
                            </div>
                            @if (!d.listsColumns) {
                                <p class="text-secondary m-0 text-sm" data-testid="no-columns">
                                    This Dataset lists no columns, so none can be set here.
                                </p>
                            } @else if (!d.rows.length) {
                                <p class="text-secondary m-0 text-sm" data-testid="no-identifiers">
                                    No column looks like an identifier. Choose Show every column to set one.
                                </p>
                            } @else {
                                <table class="w-full text-sm">
                                    <caption class="sr-only">
                                        What each column of
                                        {{
                                            d.entry.id
                                        }}
                                        holds
                                    </caption>
                                    <thead>
                                        <tr class="text-secondary text-left text-xs">
                                            <th scope="col" class="py-1 pr-3 font-medium">Column</th>
                                            <th scope="col" class="py-1 pr-3 font-medium">Holds</th>
                                            <th scope="col" class="py-1 font-medium">Note</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        @for (r of d.rows; track r.key) {
                                            <tr class="border-t" data-testid="column-row">
                                                <td class="py-1 pr-3 font-mono" data-testid="column-name">
                                                    {{ r.column }}
                                                </td>
                                                <td class="py-1 pr-3" data-testid="column-type">
                                                    @if (canEdit()) {
                                                        <inspecto-option-picker
                                                            data-testid="column-picker"
                                                            [attr.data-column]="r.key"
                                                            [label]="'What ' + r.column + ' holds'"
                                                            [hideLabel]="true"
                                                            [outlined]="true"
                                                            [options]="columnOptions()"
                                                            [formControl]="r.ctl"
                                                        ></inspecto-option-picker>
                                                    } @else {
                                                        {{ labelOf(r.ctl.value, 'Not set') }}
                                                    }
                                                </td>
                                                <td class="text-secondary py-1 text-xs" data-testid="column-note">
                                                    {{ r.note }}
                                                </td>
                                            </tr>
                                        }
                                    </tbody>
                                </table>
                            }
                        </section>
                    }
                } @else {
                    <inspecto-empty-state
                        icon="heroicons_outline:table-cells"
                        title="No Dataset yet"
                        message="This Space holds no Dataset, so there is no column to describe. Add a file first; its identifier columns are listed here."
                        actionLabel="Add data"
                        data-testid="columns-no-dataset"
                        (action)="addData()"
                    ></inspecto-empty-state>
                }
            } @else {
                <inspecto-skeleton [lines]="3" height="4rem"></inspecto-skeleton>
            }
        </div>
    `,
})
export class EntityColumnsComponent {
    private readonly columnsApi = inject(DatasetColumnsService);
    private readonly router = inject(Router);
    private readonly components = inject(ComponentsService);

    /** The Entity Types in force (the host's `GET /inv/entity-types` read). */
    readonly types = input.required<EntityTypeSummary[]>();
    /** The Dataset registry entries; `null` while they are being read. */
    readonly datasets = input<DatasetEntry[] | null>(null);
    /** Why the Datasets could not be read, or ''. */
    readonly loadError = input('');
    /** May change what columns hold (`canAuthorWorkbench`; everyone with no sign-in). */
    readonly canEdit = input(false);
    /** The result of the last change, as the host states it after reading the Entity Types again. */
    readonly result = input('');
    /** A change was written; the host reads the Entity Types and the Datasets again. */
    readonly changed = output<EntitiesChange>();

    protected readonly lockedReason = COLUMNS_LOCKED_REASON;
    readonly showAll = new FormControl(false, { nonNullable: true });
    private readonly all = toSignal(this.showAll.valueChanges, { initialValue: false });
    readonly writeError = signal('');
    readonly views = signal<DatasetView[] | null>(null);
    readonly columnOptions = computed<PickerOption[]>(() => entityTypeOptions(this.types()));
    readonly primaryOptions = computed<PickerOption[]>(() => entityTypeOptions(this.types(), 'None'));

    /** Each control's subscription, replaced whenever the rows are rebuilt from fresh inputs. */
    private subs = new Subscription();

    constructor() {
        effect(() => {
            const datasets = this.datasets();
            const types = this.types();
            const all = this.all();
            untracked(() => this.rebuild(datasets, types, all));
        });
        inject(DestroyRef).onDestroy(() => this.subs.unsubscribe());
    }

    labelOf(id: string | null, blank: string): string {
        if (!id) return blank;
        return this.types().find((t) => t.id === id)?.label ?? id;
    }

    private rebuild(datasets: DatasetEntry[] | null, types: EntityTypeSummary[], all: boolean): void {
        this.subs.unsubscribe();
        this.subs = new Subscription();
        if (!datasets) {
            this.views.set(null);
            return;
        }
        this.views.set(
            datasets.map((entry) => {
                const primary = new FormControl<string | null>(primaryOf(entry.content));
                this.subs.add(primary.valueChanges.subscribe((v) => this.setPrimary(entry, v || null, primary)));
                const rows = columnRows([entry], types, all).map((r) => {
                    const ctl = new FormControl<string | null>(r.entityType);
                    const view: RowView = { ...r, key: `${r.dataset}/${r.column}`, note: columnNote(r, types), ctl };
                    this.subs.add(ctl.valueChanges.subscribe((v) => this.setColumn(view, v || null)));
                    return view;
                });
                return { entry, listsColumns: datasetColumns(entry.content).length > 0, primary, rows };
            }),
        );
    }

    private setColumn(row: RowView, entityType: string | null): void {
        if (entityType === row.entityType) return;
        this.writeError.set('');
        this.columnsApi.setEntityType(row.dataset, row.column, entityType).subscribe({
            next: () =>
                this.changed.emit({
                    kind: 'column',
                    dataset: row.dataset,
                    column: row.column,
                    entityType,
                    previous: row.entityType,
                }),
            error: (e) => {
                row.ctl.setValue(row.entityType, { emitEvent: false });
                this.writeError.set(apiErrorMessage(e, `What "${row.column}" holds could not be changed.`));
            },
        });
    }

    private setPrimary(entry: DatasetEntry, entityType: string | null, ctl: FormControl<string | null>): void {
        const before = primaryOf(entry.content);
        if (entityType === before) return;
        this.writeError.set('');
        const content: Record<string, unknown> = { ...entry.content };
        if (entityType) content['primaryEntityType'] = entityType;
        else delete content['primaryEntityType'];
        this.components.update('dataset', entry.id, content, { ifMatch: entry.contentHash || undefined }).subscribe({
            next: () => this.changed.emit({ kind: 'primary', dataset: entry.id, entityType }),
            error: (e) => {
                ctl.setValue(before, { emitEvent: false });
                this.writeError.set(apiErrorMessage(e, `The primary Entity Type of ${entry.id} could not be changed.`));
            },
        });
    }

    /** The empty state's next action: the Add data screen. */
    addData(): void {
        void this.router.navigateByUrl('/add-data');
    }
}
