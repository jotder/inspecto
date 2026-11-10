import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import {
    AbstractControl,
    FormBuilder,
    FormControl,
    FormsModule,
    ReactiveFormsModule,
    Validators,
} from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { DatasetDetailsData, DatasetDetailsDialog, DatasetDetailsResult } from './dataset-details.dialog';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { ComponentsService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import {
    ColumnChange,
    DatasetColumnsService,
    DatasetShapes,
} from '@inspecto/link-analysis/api/dataset-columns.service';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';
import { EntityFamily, RecordTypeGuide, entityTypeOptions } from '@inspecto/link-analysis/api/entity-type-options';
import { ImportDatasetCardComponent } from '@inspecto/link-analysis/add-data/import-dataset-card.component';
import { LinkAnalysisDatasetDiscoverComponent } from '@inspecto/link-analysis/link-analysis/link-analysis-dataset-discover.component';
import { recordPhrase } from '@inspecto/link-analysis/api/column-roles';
import { ADD_DATA_ROUTE } from './first-run';
import {
    ColumnRole,
    DataColumn,
    DataEntry,
    MAPPING_NAME,
    MappingForm,
    ROLE_HINTS,
    ROLE_LABELS,
    RoleProposal,
    TypeChoice,
    dataEntry,
    driftedColumns,
    formOf,
    mappingEntry,
    carriedChoices,
    mappingLine,
    mappingRole,
    MappingRole,
    recordTypeProposals,
    roleOf,
    typeOf,
} from './data-page';

/**
 * The **Data** page (LA-DATA-PAGE-1, plan `docs/superpower/la-data-arrival-plan.md` §8): every Dataset that may be a
 * Mapping's base (LA-PIPELINE-MAPPINGS-1: Add data ones, then stored ones kept up to date elsewhere) with its card (an
 * import's Data status, Arrivals, Add a file, Run now; a short one otherwise), its **Columns** (each column's role - Entity,
 * Time, Measure, Category, Detail - with what an Entity column holds and a Measure's unit, editable; LA-COLUMN-ROLES-1) and its **Mappings** - each a Dataset of its own that reads the same data its own way (From, To, Kind of
 * link, When, Amount), created, edited and deleted here. Every other Dataset is listed by name, with why.
 * LA-DATA-MENU-1: laid out like Settings - a menu of the bases grouped by kind (Streams, References) on the left, the
 * picked one on the right (its body, and the reads its card and Discover make, only then); nothing folds.
 * LA-DATA-DETAILS-1: the open Dataset's header shows its name (stored as `title`), id, description and facts, with
 * *Rename or describe* (`DatasetDetailsDialog`); a filter over the menu finds a Dataset by name, id or description.
 */
@Component({
    selector: 'la-app-data-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        FormsModule,
        ReactiveFormsModule,
        RouterLink,
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        ChipComponent,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        ImportDatasetCardComponent,
        LinkAnalysisDatasetDiscoverComponent,
    ],
    template: `
        <!-- LA-NO-LONG-PAGES-1: from md up the page is the window: the menu and the Dataset scroll on their own -->
        <div
            class="mx-auto flex w-full max-w-7xl flex-col gap-4 p-6 md:h-[calc(100dvh-var(--shell-chrome-height,0rem))] md:overflow-hidden"
        >
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
                <!-- LA-DATA-MENU-1 (operator 2026-11-10): the Settings layout - a menu of Datasets grouped by kind on the
                     left, the chosen one on the right; nothing folds -->
                <div class="flex min-w-0 flex-col gap-4 md:min-h-0 md:flex-1 md:flex-row" data-testid="data-layout">
                    <nav
                        class="flex shrink-0 flex-col gap-3 md:min-h-0 md:w-72 md:overflow-y-auto"
                        aria-label="Datasets"
                    >
                        @if (bases().length > 6) {
                            <div class="relative px-1">
                                <mat-icon
                                    class="icon-size-4 text-hint pointer-events-none absolute left-3 top-1/2 -translate-y-1/2"
                                    svgIcon="heroicons_outline:magnifying-glass"
                                ></mat-icon>
                                <input
                                    type="search"
                                    class="bg-card focus-visible:outline-primary w-full rounded-lg border py-1.5 pl-8 pr-2 text-sm focus-visible:outline-2"
                                    placeholder="Find a Dataset"
                                    aria-label="Find a Dataset by name, id or description"
                                    [value]="filter()"
                                    (input)="filter.set($any($event.target).value)"
                                    data-testid="data-filter"
                                />
                            </div>
                        }
                        @if (filter() && !shownGroups().length) {
                            <p class="text-secondary m-0 px-3 text-sm" data-testid="data-filter-none">
                                No Dataset matches “{{ filter() }}”.
                            </p>
                        }
                        @for (g of shownGroups(); track g.kind) {
                            <div
                                class="flex flex-col gap-0.5"
                                role="group"
                                [attr.aria-labelledby]="'data-group-' + g.kind"
                                [attr.data-testid]="'data-group-' + g.kind"
                            >
                                <div
                                    [id]="'data-group-' + g.kind"
                                    class="text-secondary px-3 pb-1 text-xs font-semibold uppercase tracking-wider"
                                >
                                    {{ g.label }} · {{ g.items.length }}
                                </div>
                                @for (d of g.items; track d.id) {
                                    <button
                                        type="button"
                                        class="hover:bg-hover flex items-center gap-2 rounded-lg px-3 py-1.5 text-left transition-colors"
                                        [class.bg-hover]="selected()?.id === d.id"
                                        [attr.aria-current]="selected()?.id === d.id ? 'true' : null"
                                        [attr.data-testid]="'data-toggle-' + d.id"
                                        (click)="select(d.id)"
                                    >
                                        <mat-icon
                                            class="icon-size-4 text-hint"
                                            [svgIcon]="
                                                d.origin === 'import'
                                                    ? 'heroicons_outline:arrow-up-tray'
                                                    : 'heroicons_outline:circle-stack'
                                            "
                                        ></mat-icon>
                                        <span class="flex min-w-0 flex-auto flex-col">
                                            <span class="truncate text-sm font-medium" [title]="d.name">{{
                                                d.name
                                            }}</span>
                                            <span class="text-secondary truncate text-xs">{{
                                                d.name !== d.id ? d.id + ' · ' + summary(d) : summary(d)
                                            }}</span>
                                        </span>
                                    </button>
                                }
                            </div>
                        }
                    </nav>
                    @if (selected(); as d) {
                        <section
                            class="flex min-w-0 flex-auto flex-col gap-3 md:min-h-0 md:overflow-y-auto md:pr-1"
                            [attr.aria-labelledby]="'data-title-' + d.id"
                            [attr.data-testid]="'data-' + d.id"
                        >
                            <!-- LA-DATA-DETAILS-1 (operator 2026-11-10): what the open Dataset is called and is for, and where to change it -->
                            <header
                                class="bg-card flex flex-col gap-2 rounded-2xl border p-4"
                                data-testid="data-header"
                            >
                                <div class="flex flex-wrap items-start gap-2">
                                    <div class="flex min-w-0 flex-auto flex-col gap-0.5">
                                        <h2
                                            class="m-0 break-words text-lg font-semibold"
                                            [id]="'data-title-' + d.id"
                                            data-testid="data-name"
                                        >
                                            {{ d.name }}
                                        </h2>
                                        <div class="text-secondary flex flex-wrap items-center gap-1 text-xs">
                                            <span>Id</span>
                                            <span class="font-mono" data-testid="data-id">{{ d.id }}</span>
                                            <button
                                                mat-icon-button
                                                type="button"
                                                class="icon-size-4 !h-6 !w-6 !p-0"
                                                [matTooltip]="copied() === d.id ? 'Copied' : 'Copy the id'"
                                                [attr.aria-label]="'Copy the id ' + d.id"
                                                (click)="copyId(d.id)"
                                                data-testid="data-copy-id"
                                            >
                                                <mat-icon
                                                    class="icon-size-4"
                                                    [svgIcon]="
                                                        copied() === d.id
                                                            ? 'heroicons_outline:check'
                                                            : 'heroicons_outline:clipboard-document'
                                                    "
                                                ></mat-icon>
                                            </button>
                                        </div>
                                    </div>
                                    <button
                                        mat-stroked-button
                                        type="button"
                                        (click)="editDetails(d)"
                                        data-testid="data-edit-details"
                                    >
                                        <mat-icon svgIcon="heroicons_outline:pencil-square"></mat-icon>
                                        Rename or describe
                                    </button>
                                </div>
                                @if (d.description) {
                                    <p class="m-0 whitespace-pre-line text-sm" data-testid="data-description">
                                        {{ d.description }}
                                    </p>
                                } @else {
                                    <button
                                        type="button"
                                        class="text-secondary self-start text-left text-sm italic underline-offset-2 hover:underline"
                                        (click)="editDetails(d)"
                                        data-testid="data-description-empty"
                                    >
                                        No description yet: add one so others know what its rows are.
                                    </button>
                                }
                                <div class="flex flex-wrap gap-1" data-testid="data-facts">
                                    <inspecto-chip variant="soft" tone="primary">{{
                                        d.kind === 'reference' ? 'Reference' : 'Stream'
                                    }}</inspecto-chip>
                                    <inspecto-chip variant="outline">{{
                                        d.origin === 'import' ? 'Added here' : 'Kept up to date by a Pipeline'
                                    }}</inspecto-chip>
                                    <inspecto-chip variant="outline"
                                        >{{ d.columns.length }}
                                        {{ d.columns.length === 1 ? 'column' : 'columns' }}</inspecto-chip
                                    >
                                    <inspecto-chip variant="outline"
                                        >{{ mappingsOf(d.id).length }}
                                        {{ mappingsOf(d.id).length === 1 ? 'Mapping' : 'Mappings' }}</inspecto-chip
                                    >
                                    @if (d.eventDate) {
                                        <inspecto-chip variant="outline">Time: {{ d.eventDate }}</inspecto-chip>
                                    }
                                </div>
                            </header>
                            <div class="flex flex-col gap-3">
                                @if (d.origin === 'import') {
                                    <inspecto-import-dataset-card
                                        [dataset]="{
                                            id: d.id,
                                            kind: d.kind,
                                            description: d.description,
                                            importPipeline: d.importPipeline ?? d.id,
                                            importSource: d.importSource ?? undefined,
                                            importFilter: d.importFilter,
                                        }"
                                    ></inspecto-import-dataset-card>
                                } @else {
                                    <div
                                        class="bg-card flex flex-col gap-1 rounded-2xl border p-4"
                                        data-testid="pipeline-card"
                                    >
                                        <p class="m-0 text-sm font-semibold">Kept up to date by a Pipeline or a Job</p>
                                        <p class="text-secondary m-0 text-sm">
                                            Its data is written there, not added here. What its columns hold and its
                                            Mappings are set here.
                                        </p>
                                    </div>
                                }

                                <div class="bg-card flex flex-col gap-2 rounded-2xl border p-4" data-testid="columns">
                                    <h3 class="m-0 text-base font-semibold">Columns</h3>
                                    <p class="text-secondary m-0 text-sm">
                                        What each column is: an Entity it holds, a Time, a Measure, a Category or a
                                        Detail. A Dataset may have several of each.
                                    </p>
                                    @if (recordTypes().length && recordTypeControls().get(d.id); as rt) {
                                        <inspecto-option-picker
                                            label="Each row is"
                                            [options]="recordTypeOptions()"
                                            [formControl]="rt"
                                            help="Puts the Entity Types such a record usually holds first"
                                            data-testid="record-type"
                                        ></inspecto-option-picker>
                                    }
                                    <!-- ENTITY-TYPE-RECORD-ROLES-1: the roles such a record usually gives its columns, not said yet -->
                                    @if (proposals().get(d.id); as p) {
                                        <div
                                            class="flex flex-wrap items-center gap-2 text-sm"
                                            data-testid="record-roles"
                                        >
                                            <span class="text-secondary">
                                                {{ p.phrase }} usually has
                                                @for (r of p.roles; track r.column; let last = $last) {
                                                    <span class="font-medium">{{ r.column }}</span> as a
                                                    {{ roleLabel(r.role) }}{{ last ? '.' : ',' }}
                                                }
                                            </span>
                                            <button
                                                mat-stroked-button
                                                type="button"
                                                [disabled]="applying()"
                                                (click)="applyProposals(d, p.roles)"
                                                data-testid="record-roles-apply"
                                            >
                                                Use these roles
                                            </button>
                                        </div>
                                    }
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
                                                @if (derivedRoles().get(d.id)?.get(col.name); as dr) {
                                                    @if (dr.role === r) {
                                                        <p
                                                            class="text-secondary m-0 text-xs"
                                                            [attr.data-testid]="'derived-' + col.name"
                                                        >
                                                            From
                                                            {{ dr.mappings.length === 1 ? 'Mapping' : 'Mappings' }}
                                                            {{ dr.mappings.join(', ') }} - not set on this Dataset.
                                                        </p>
                                                    }
                                                }
                                                @if (r === 'entity') {
                                                    <inspecto-option-picker
                                                        [label]="col.name + ' holds'"
                                                        [options]="
                                                            holdsOptions().get(d.id + '/' + col.name) ?? typeOptions()
                                                        "
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
                                                <!-- LA-COLUMN-ROLES-1 P2: a Time, Measure or Category rides on every link unless kept off -->
                                                @if (r === 'temporal' || r === 'measure' || r === 'dimension') {
                                                    <inspecto-option-picker
                                                        label="On each link"
                                                        [options]="carriedOptions"
                                                        [formControl]="carried().get(d.id)!.get(col.name)!"
                                                        [attr.data-testid]="'carried-' + col.name"
                                                    ></inspecto-option-picker>
                                                }
                                            </div>
                                        }
                                    </div>
                                </div>

                                <!-- LA-BASIC-DISCOVER-1: what is in it - entities, busiest, kinds, period, Measure totals -->
                                <inspecto-link-analysis-dataset-discover
                                    class="bg-card rounded-2xl border p-4"
                                    [dataset]="d.id"
                                    title="Discover"
                                    [level]="3"
                                    drawWhyNot="Draw from Explore: its Start box has the same Discover."
                                    [attr.data-testid]="'discover-' + d.id"
                                ></inspecto-link-analysis-dataset-discover>

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
                                                        <span
                                                            class="text-warn w-full text-sm"
                                                            data-testid="mapping-drift"
                                                        >
                                                            Reads
                                                            {{ gone.length === 1 ? 'a column' : 'columns' }}
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
                                                editing()?.id
                                                    ? 'Edit the Mapping ' + editing()?.id
                                                    : 'New mapping of ' + d.id
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
                                                        form.controls.from.touched &&
                                                        form.controls.from.hasError('required')
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
                                                    @if (
                                                        form.controls.to.touched &&
                                                        form.controls.to.hasError('required')
                                                    ) {
                                                        <p class="text-warn m-0 text-xs" role="alert">
                                                            Choose the column each link goes to.
                                                        </p>
                                                    } @else if (
                                                        form.controls.to.touched && form.controls.to.hasError('same')
                                                    ) {
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
                                                <!-- LA-COLUMN-ROLES-2: this Mapping's own "On each link" marks -->
                                                @for (col of mappingCarried(); track col) {
                                                    <inspecto-option-picker
                                                        [label]="col + ' on each link'"
                                                        [options]="carriedOptions"
                                                        [ngModel]="isCarried(col) ? 'yes' : 'no'"
                                                        [ngModelOptions]="{ standalone: true }"
                                                        (ngModelChange)="setCarried(col, $event)"
                                                        [attr.data-testid]="'mapping-carried-' + col"
                                                    ></inspecto-option-picker>
                                                }
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
                                                <button mat-button type="button" (click)="editing.set(null)">
                                                    Cancel
                                                </button>
                                            </div>
                                        </form>
                                    }
                                </div>
                            </div>
                        </section>
                    }
                </div>
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
    private readonly route = inject(ActivatedRoute);
    private readonly dialog = inject(MatDialog);

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

    /** What one row of a Dataset can be (ENTITY-TYPE-CATALOG-1 P2): the Space's record types, or not said. */
    protected readonly recordTypeOptions = computed<PickerOption[]>(() => [
        { value: '', label: 'Not said' },
        ...this.recordTypes().map((r) => ({
            value: r.id,
            label: r.label,
            ...(r.definition ? { hint: r.definition } : {}),
        })),
    ]);

    /**
     * What an Entity column of each base may hold (keyed `<dataset>/<column>`): as {@link typeOptions}, the base's record
     * type's likely types first, and a type whose shape the column's values fit marked "Shape matches" - read on the
     * server over an unmasked sample (`shapes`, ENTITY-TYPE-RECORD-ROLES-1), since this page's own rows are masked.
     */
    protected readonly holdsOptions = computed(() => {
        const out = new Map<string, PickerOption[]>();
        for (const d of this.bases()) {
            const recordType = this.recordTypes().find((r) => r.id === d.recordType) ?? null;
            const marks = this.shapes().get(d.id);
            for (const c of d.columns) {
                const fits = marks ? new Map((marks[c.name] ?? []).map((f) => [f.type, { strong: f.strong }])) : null;
                out.set(`${d.id}/${c.name}`, [
                    { value: '', label: 'Nothing: a detail' },
                    ...entityTypeOptions(this.types(), this.families(), { recordType, fits }),
                ]);
            }
        }
        return out;
    });

    /** Each base's shape marks once read (`GET /inv/datasets/{id}/shapes`); a base whose read failed has none. */
    readonly shapes = signal<ReadonlyMap<string, DatasetShapes['columns']>>(new Map());
    /** The bases whose marks were asked for: once per page, not on every re-read after a save. */
    private readonly shapesAsked = new Set<string>();

    private readShapes(list: readonly DataEntry[]): void {
        for (const d of list) {
            if (this.shapesAsked.has(d.id)) continue;
            this.shapesAsked.add(d.id);
            this.columns
                .shapes(d.id)
                .pipe(catchError(() => of(null)))
                .subscribe((r) => {
                    if (r?.columns) this.shapes.update((m) => new Map(m).set(d.id, r.columns));
                });
        }
    }

    /** What each base's record type proposes for the columns it does not say yet (ENTITY-TYPE-RECORD-ROLES-1); none = absent. */
    protected readonly proposals = computed(() => {
        const out = new Map<string, { phrase: string; roles: RoleProposal[] }>();
        for (const d of this.bases()) {
            const rt = this.recordTypes().find((r) => r.id === d.recordType);
            const roles = recordTypeProposals(d, rt, this.types());
            if (!rt || !roles.length) continue;
            const phrase = recordPhrase(rt.label);
            out.set(d.id, { phrase: phrase.charAt(0).toUpperCase() + phrase.slice(1), roles });
        }
        return out;
    });
    readonly applying = signal(false);

    protected roleLabel(role: ColumnRole): string {
        return ROLE_LABELS[role];
    }

    /** Save every proposed role at once, then read the Datasets again. */
    applyProposals(d: DataEntry, roles: readonly RoleProposal[]): void {
        this.error.set('');
        this.applying.set(true);
        forkJoin(roles.map((r) => this.columns.setColumn(d.id, r.column, { role: r.role }))).subscribe({
            next: () => {
                this.applying.set(false);
                this.load();
            },
            error: (e) => {
                this.applying.set(false);
                this.error.set(apiErrorMessage(e, `The roles of ${d.id} could not be changed.`));
                this.load();
            },
        });
    }

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
            (d, c) => this.derivedRoles().get(d.id)?.get(c.name)?.role ?? roleOf(c, d, this.types()),
            (d, c, v) => this.setRole(d, c, v),
        ),
    );
    readonly units = computed(() =>
        this.controls<string>(
            (_, c) => c.unit ?? '',
            (d, c, v) => this.save(d, c, { role: 'measure', unit: (v ?? '').trim() || null }),
        ),
    );
    /** Whether a Time, Measure or Category column rides on each link (and in its index); only "no" is stored. */
    protected readonly carriedOptions: PickerOption[] = [
        { value: 'yes', label: 'Carried', hint: 'Play, filters and profiles can use it; the link index carries it' },
        {
            value: 'no',
            label: 'Not carried',
            hint: 'Kept off the links: a narrower index, but no filter or play on it',
        },
    ];
    readonly carried = computed(() =>
        this.controls<string>(
            (_, c) => (c.carried === false ? 'no' : 'yes'),
            (d, c, v) => this.save(d, c, { carried: v === 'no' ? false : null }),
        ),
    );
    /** What one row of each base is; a change saves at once. Rebuilt on every read, so it shows the stored value. */
    readonly recordTypeControls = computed(() => {
        const out = new Map<string, FormControl<string>>();
        for (const d of this.bases()) {
            const ctrl = new FormControl(d.recordType ?? '', { nonNullable: true });
            ctrl.valueChanges.subscribe((v) => this.setRecordType(d, v));
            out.set(d.id, ctrl);
        }
        return out;
    });
    /** A role picked but not saved yet: Entity, until what it holds is picked. Cleared on every read. */
    private readonly picked = signal<ReadonlyMap<string, ColumnRole>>(new Map());

    protected roleShown(d: DataEntry, c: DataColumn): ColumnRole {
        return (
            this.picked().get(`${d.id}/${c.name}`) ??
            this.derivedRoles().get(d.id)?.get(c.name)?.role ??
            roleOf(c, d, this.types())
        );
    }

    /**
     * Per base, the roles its unset columns read from the Mappings built on it (`mappingRole`; operator 2026-11-05):
     * shown with the Mappings that say so, never written - a person's pick saves as any other.
     */
    readonly derivedRoles = computed(() => {
        const out = new Map<string, Map<string, MappingRole>>();
        for (const d of this.bases()) {
            const mappings = this.mappingsOf(d.id);
            const m = new Map<string, MappingRole>();
            for (const c of d.columns) {
                const dr = mappingRole(c, d, mappings, this.types());
                if (dr) m.set(c.name, dr);
            }
            out.set(d.id, m);
        }
        return out;
    });

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
    /** What a row can be, from the Space's packs; empty when none is in force (then the question is not asked). */
    readonly recordTypes = signal<RecordTypeGuide[]>([]);
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
    /** LA-DATA-GROUPS-1: the bases grouped by kind, Streams first; a group with no Dataset is not shown. */
    readonly groups = computed(() =>
        (
            [
                { kind: 'stream', label: 'Streams' },
                { kind: 'reference', label: 'References' },
            ] as const
        )
            .map((g) => ({ ...g, items: this.bases().filter((d) => d.kind === g.kind) }))
            .filter((g) => g.items.length),
    );
    /** LA-DATA-MENU-1: the Dataset picked in the menu (`?dataset=` keeps it across a reload). */
    private readonly pickedDataset = signal<string | null>(null);
    /** LA-DATA-DETAILS-1: the menu's filter - a Dataset whose name, id or description holds every word typed. */
    readonly filter = signal('');
    readonly shownGroups = computed(() => {
        const words = this.filter().toLowerCase().split(/\s+/).filter(Boolean);
        if (!words.length) return this.groups();
        const hit = (d: DataEntry) => {
            const text = `${d.name} ${d.id} ${d.description}`.toLowerCase();
            return words.every((w) => text.includes(w));
        };
        return this.groups()
            .map((g) => ({ ...g, items: g.items.filter(hit) }))
            .filter((g) => g.items.length);
    });
    /** The Dataset shown on the right: the one picked, else the first listed (menu order), so the pane is never empty. */
    readonly selected = computed(() => {
        const list = this.groups().flatMap((g) => g.items);
        return list.find((d) => d.id === this.pickedDataset()) ?? list[0] ?? null;
    });

    /** The id just copied (its button shows a tick for a moment). */
    readonly copied = signal<string | null>(null);

    copyId(id: string): void {
        navigator.clipboard?.writeText(id).then(
            () => {
                this.copied.set(id);
                setTimeout(() => this.copied.set(null), 1500);
            },
            () => this.copied.set(null),
        );
    }

    /** Rename or describe the Dataset; the dialog saves, and closes with the values it saved, shown here at once. */
    editDetails(d: DataEntry): void {
        this.dialog
            .open<DatasetDetailsDialog, DatasetDetailsData, DatasetDetailsResult>(DatasetDetailsDialog, {
                data: { id: d.id, name: d.name === d.id ? '' : d.name, description: d.description },
                width: '36rem',
                maxWidth: '95vw',
                autoFocus: 'first-tabbable',
            })
            .afterClosed()
            .subscribe((r) => {
                if (!r) return;
                this.entries.update((list) =>
                    (list ?? []).map((e) =>
                        e.id === d.id ? { ...e, name: r.name || e.id, description: r.description } : e,
                    ),
                );
            });
    }

    select(id: string): void {
        this.pickedDataset.set(id);
        this.router.navigate([], { queryParams: { dataset: id }, queryParamsHandling: 'merge', replaceUrl: true });
    }

    /** A Dataset's one line in the menu: where its data comes from, and how much is set on it. */
    summary(d: DataEntry): string {
        const maps = this.mappingsOf(d.id).length;
        return [
            d.origin === 'import' ? 'Added here' : 'Kept up to date by a Pipeline',
            `${d.columns.length} ${d.columns.length === 1 ? 'column' : 'columns'}`,
            `${maps} ${maps === 1 ? 'Mapping' : 'Mappings'}`,
        ].join(' · ');
    }
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
        notCarried: [[] as string[]],
    });

    /** The open Mapping form's columns that may be kept off its links (re-read as From and To change). */
    protected readonly mappingCarried = computed(() => {
        this.formTick();
        const e = this.editing();
        return e ? carriedChoices(e.base, this.form.getRawValue(), this.types()) : [];
    });
    private readonly formTick = signal(0);

    protected isCarried(column: string): boolean {
        return !this.form.controls.notCarried.value.includes(column);
    }

    protected setCarried(column: string, v: string): void {
        const rest = this.form.controls.notCarried.value.filter((c) => c !== column);
        this.form.controls.notCarried.setValue(v === 'no' ? [...rest, column] : rest);
    }

    constructor() {
        this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.formTick.update((n) => n + 1));
        this.form.controls.from.valueChanges.pipe(takeUntilDestroyed()).subscribe((from) => {
            this.form.controls.to.updateValueAndValidity({ emitEvent: false });
            this.holdsOf(from, this.form.controls.fromType);
        });
        this.form.controls.to.valueChanges
            .pipe(takeUntilDestroyed())
            .subscribe((to) => this.holdsOf(to, this.form.controls.toType));
    }

    ngOnInit(): void {
        this.pickedDataset.set(this.route.snapshot.queryParamMap.get('dataset'));
        this.load();
        this.profiles
            .entityTypes()
            .pipe(catchError(() => of({ items: [], families: [], recordTypes: [] })))
            .subscribe((l) => {
                this.families.set(l.families ?? []);
                this.recordTypes.set(l.recordTypes ?? []);
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
                this.readShapes(this.bases());
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
        // a Measure holds no Entity Type at all (the server refuses one that does, LA-COLUMN-ROLES-1 P3)
        const clears = this.typeOf(c.classification) || (role === 'measure' && !!c.classification);
        this.save(d, c, clears ? { role, entityType: null } : { role });
    }

    private setRecordType(d: DataEntry, recordType: string): void {
        this.error.set('');
        this.columns.setRecordType(d.id, recordType || null).subscribe({
            next: () => this.load(),
            error: (e) => {
                this.error.set(apiErrorMessage(e, `What a row of ${d.id} is could not be changed.`));
                this.load();
            },
        });
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
        this.pickedDataset.set(base.id); // the form renders under its Dataset, so show that one
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
