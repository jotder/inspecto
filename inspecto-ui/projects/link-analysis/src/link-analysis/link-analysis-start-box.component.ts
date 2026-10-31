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
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { catchError, combineLatest, debounceTime, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';
import { LensService, needsPermission } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { StepperFieldComponent } from '@inspecto/core/components/stepper-field.component';
import { DISABLED_REASON, expandOnStartCap, mostDegrees } from './disabled-reasons';
import { timeZoneOptions } from '@inspecto/core/schema/time-zones';
import {
    EntityProfileService,
    EntitySuggestion,
    EntitySuggestions,
} from '@inspecto/link-analysis/api/entity-profile.service';
import type { EntityListSummary } from '@inspecto/link-analysis/api/inv.service';
import {
    UnsavedStartRequest,
    StartCatalogue,
    StartKind,
    StartRequest,
    newInvestigationDefaults,
    parsePastedValues,
    suggestionLine,
    wholeDatasetWarning,
} from './start-from';

/** A notice the page shows under the box after a start (what was drawn, what was not found). */
export interface StartNotice {
    variant: 'info' | 'warning';
    text: string;
}

const KINDS: PickerOption[] = [
    { value: 'entity', label: 'One entity', hint: 'An Entity Type, then one of its values' },
    { value: 'list', label: 'Entity List', hint: 'The members of an Entity List' },
    { value: 'paste', label: 'Pasted values', hint: 'Values you paste, one per line' },
    { value: 'dataset', label: 'Whole Dataset', hint: 'Every link of the Dataset' },
];

/** An Entity List option's sub-line, as Settings shows it: "Party · 2 members · Watch". */
function listHint(l: EntityListSummary, types: readonly { id: string; label: string }[]): string {
    const type = types.find((t) => t.id === l.entityType)?.label || l.entityType;
    const purpose = l.purpose ? l.purpose[0].toUpperCase() + l.purpose.slice(1) : '';
    const members = `${l.size.toLocaleString('en-GB')} ${l.size === 1 ? 'member' : 'members'}`;
    return [type, members, purpose].filter(Boolean).join(' · ');
}

/** Expand on start: at most this many degrees (0 = don't expand), and never past the profile's maximum. */
const MOST_DEGREES_ON_START = 3;

/**
 * Why Save is not offered to this user (LA-UNSAVED-INVESTIGATION-1): starting and expanding unsaved is open to everyone,
 * saving creates the Investigation, and `POST /inv/investigations` is gated on `canManageIncidents` (Incident management).
 */
export const SAVE_NEEDS = needsPermission('canManageIncidents');

/**
 * **Start from...** (LA-ONBOARD-1 phase 6, OB-9; UC-09), in two modes.
 *
 * - `draw` (the Query zone's empty canvas): pick the Dataset, then start from **one entity** (the Entity Type FIRST, the
 *   Dataset's primary type preselected - A-7b - then the value, with a type-ahead of matches and their link counts), **a
 *   list** (an Entity List), **a paste** (values, unknown ones reported after) or **the whole Dataset** (allowed, warned
 *   above the node cap). The page draws the chosen entities plus one ring, busy ones held. On the Investigations level
 *   *New Investigation…* below opens the form that starts one.
 * - `investigation` (the Investigation tab's start form, LA-UNSAVED-INVESTIGATION-1): the same Start from choice and
 *   inputs, a **Time column** and a **Time zone** picker and the **Degrees** stepper. Start draws the start UNSAVED: no
 *   Name or Purpose is asked; Save asks for them later and records the steps (the default Name and Purpose travel along).
 *
 * Open…, Explore dataset… and the examples live in the File menu.
 */
@Component({
    selector: 'inspecto-link-analysis-start-box',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatAutocompleteModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        ReactiveFormsModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        StepperFieldComponent,
    ],
    host: { class: 'block w-full max-w-3xl', '[class.p-4]': "mode() === 'draw'" },
    template: `
        @if (mode() === 'draw') {
            <h2 class="m-0 text-lg font-semibold" [id]="titleId()">Start a graph</h2>
            <p class="text-secondary mb-3 mt-1 text-sm">
                Draw a few entities and their links, then Expand one degree at a time.
            </p>
        } @else {
            <h3 class="m-0 text-base font-semibold" [id]="titleId()">{{ adding() ? 'Add start entities' : 'New Investigation' }}</h3>
            <p class="text-secondary mb-2 mt-1 text-sm">
                Start, then Expand. Nothing is recorded until you Save; Save asks for a Name and a Purpose.
            </p>
        }
        @if (catalogue().datasets.length) {
            <form
                class="flex flex-col gap-3"
                [class.start-box]="mode() === 'draw'"
                data-testid="start-box"
                [attr.aria-labelledby]="titleId()"
                [formGroup]="form"
                (ngSubmit)="submit()"
            >
                <inspecto-option-picker
                    data-testid="start-kind"
                    label="Start from"
                    [outlined]="true"
                    [options]="kindOptions()"
                    formControlName="kind"
                ></inspecto-option-picker>
                @if (kind() !== 'graph') {
                    <inspecto-option-picker
                        data-testid="start-dataset"
                        label="Dataset"
                        [outlined]="true"
                        [options]="datasetOptions()"
                        formControlName="dataset"
                    ></inspecto-option-picker>
                }

                @switch (kind()) {
                    @case ('entity') {
                        <inspecto-option-picker
                            data-testid="start-type"
                            label="Entity Type"
                            [outlined]="true"
                            [options]="typeOptions()"
                            help="Pick what you are looking for first, then type it."
                            formControlName="entityType"
                        ></inspecto-option-picker>
                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                            <mat-label>{{ typeLabel() || 'Value' }}</mat-label>
                            <input
                                matInput
                                data-testid="start-value"
                                formControlName="value"
                                autocomplete="off"
                                [matAutocomplete]="suggestions"
                            />
                            @if (form.controls.value.hasError('required')) {
                                <mat-error>Enter a value to start from.</mat-error>
                            }
                            @if (suggestNote(); as note) {
                                <mat-hint data-testid="start-suggest-note">{{ note }}</mat-hint>
                            }
                        </mat-form-field>
                        @if (offerReveal()) {
                            <div>
                                <button
                                    mat-button
                                    type="button"
                                    data-testid="start-reveal"
                                    (click)="revealSuggestions.set(true)"
                                >
                                    Show values (this is logged)
                                </button>
                            </div>
                        }
                        <mat-autocomplete #suggestions="matAutocomplete">
                            @for (s of suggested(); track s.value) {
                                <mat-option [value]="s.value">
                                    <span class="block font-mono text-sm">{{ s.value }}</span>
                                    <span class="text-secondary block text-xs">{{ line(s) }}</span>
                                </mat-option>
                            }
                        </mat-autocomplete>
                    }
                    @case ('list') {
                        @if (listOptions().length) {
                            <inspecto-option-picker
                                data-testid="start-list"
                                label="Entity List"
                                [outlined]="true"
                                [options]="listOptions()"
                                formControlName="listId"
                            ></inspecto-option-picker>
                            @if (tried() && !form.controls.listId.value) {
                                <p class="text-warn m-0 text-xs" role="alert">Choose an Entity List.</p>
                            }
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="start-no-lists">
                                No Entity List yet. Create one in Settings ▸ Entity Lists, or paste values.
                            </p>
                        }
                    }
                    @case ('paste') {
                        <inspecto-option-picker
                            data-testid="start-paste-type"
                            label="Entity Type"
                            [outlined]="true"
                            [options]="typeOptions()"
                            formControlName="entityType"
                        ></inspecto-option-picker>
                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                            <mat-label>Values, one per line</mat-label>
                            <textarea
                                matInput
                                data-testid="start-paste"
                                formControlName="paste"
                                rows="4"
                                autocomplete="off"
                            ></textarea>
                            @if (form.controls.paste.hasError('required')) {
                                <mat-error>Paste at least one value.</mat-error>
                            }
                            <mat-hint
                                >Commas, semicolons and tabs separate values too; values not in the Dataset are
                                reported.</mat-hint
                            >
                        </mat-form-field>
                    }
                    @case ('graph') {
                        <p class="text-secondary m-0 text-sm" data-testid="start-graph-note">
                            Uses the graph on screen ({{ graphOnScreen()?.name }}). Its filter is not kept: the whole
                            Dataset is read.
                        </p>
                    }
                    @case ('dataset') {
                        @if (wholeWarning(); as w) {
                            <inspecto-alert variant="warning" data-testid="start-whole-warning">{{ w }}</inspecto-alert>
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="start-whole-note">
                                Draws the whole of {{ datasetName() }} (busiest
                                {{ nodeCap().toLocaleString('en-GB') }} nodes).
                                @if (mode() === 'investigation') {
                                    Then select start entities on the graph and choose Start from selected.
                                }
                            </p>
                        }
                    }
                }
                @if (mode() === 'investigation' && !adding()) {
                    <inspecto-option-picker
                        data-testid="new-time-col"
                        label="Time column"
                        formControlName="timeCol"
                        [options]="timeColumnOptions()"
                        help="Used for time windows and the coverage check."
                    />
                    <inspecto-option-picker
                        data-testid="new-time-zone"
                        label="Time zone"
                        formControlName="timeColZone"
                        [options]="timeZones"
                        help="Time zone for a column that has none."
                    />
                    @if (kind() !== 'dataset' && kind() !== 'graph') {
                        <inspecto-stepper-field
                            data-testid="new-degrees"
                            label="Degrees"
                            formControlName="degrees"
                            decrementLabel="Fewer degrees"
                            incrementLabel="More degrees"
                            [min]="1"
                            [max]="degreeMax()"
                            [minReason]="fewestDegrees"
                            [maxReason]="degreeMaxReason()"
                        />
                    }
                }
                <p class="text-secondary m-0 text-xs" data-testid="start-what">{{ whatHappens() }}</p>
                <div>
                    <button mat-flat-button color="primary" type="submit" data-testid="start-submit" [disabled]="busy()">
                        {{ submitLabel() }}
                    </button>
                </div>
                @if (mode() === 'investigation' && !canWrite()) {
                    <p class="text-secondary m-0 text-xs" data-testid="new-save-needs">
                        You can start and Expand. Saving needs Incident management.
                    </p>
                }
            </form>
        } @else {
            <p class="m-0 text-sm" data-testid="start-no-dataset">
                No Dataset in this Space has a link yet, so there is nothing to start from. Bring in a file of events with
                <em>Add data</em>, or choose a Dataset's From and To columns yourself with File ▸ Explore Dataset.
            </p>
        }
        @if (notice(); as n) {
            <inspecto-alert class="mt-3 block" [variant]="n.variant" data-testid="start-notice">{{
                n.text
            }}</inspecto-alert>
        }

        @if (mode() === 'draw') {
            <p class="text-secondary m-0 mt-4 text-sm" data-testid="start-file-hint">
                Open, Explore Dataset and Examples are in the File menu.
            </p>
            @if (addData()) {
                <ul class="m-0 mt-3 grid list-none gap-3 p-0 grid-cols-[repeat(auto-fit,minmax(11rem,1fr))]">
                    <li>
                        <button
                            type="button"
                            class="starter-card"
                            data-testid="starter-add-data"
                            (click)="openAddData.emit()"
                        >
                            <mat-icon class="icon-size-6" svgIcon="heroicons_outline:arrow-up-tray"></mat-icon>
                            <span class="block text-sm font-semibold">Add data</span>
                            <span class="text-secondary block text-xs">
                                Bring a file of events (transfers, calls, messages) into this Space.
                            </span>
                        </button>
                    </li>
                </ul>
            }
        }
    `,
    styles: `
        .starter-card {
            display: flex;
            flex-direction: column;
            gap: 0.25rem;
            width: 100%;
            height: 100%;
            padding: 0.75rem;
            text-align: left;
            border: 1px solid var(--gamma-border);
            border-radius: 0.5rem;
            background: var(--gamma-bg-card);
            cursor: pointer;
        }
        .starter-card:hover {
            background: var(--gamma-bg-hover);
        }
        .starter-card:focus-visible {
            outline: 2px solid currentColor;
            outline-offset: 2px;
        }
        .start-box {
            padding: 0.75rem;
            border: 1px solid var(--gamma-border);
            border-radius: 0.5rem;
            background: var(--gamma-bg-card);
        }
    `,
})
export class LinkAnalysisStartBoxComponent {
    private readonly profiles = inject(EntityProfileService);
    private readonly lens = inject(LensService);
    private readonly destroyRef = inject(DestroyRef);

    /** What can be started from: the Datasets with a link and the Entity Types their link ends hold. */
    readonly catalogue = input<StartCatalogue>({ datasets: [], types: [] });
    readonly lists = input<EntityListSummary[]>([]);
    /** The Dataset to preselect (`?dataset=<id>`, Add data's *Explore in Link Analysis*); null = the first. */
    readonly dataset = input<string | null>(null);
    readonly nodeCap = input(1000);
    readonly notice = input<StartNotice | null>(null);
    readonly busy = input(false);
    /** The host has an Add data screen (`/add-data`): the card is offered. */
    readonly addData = input(false);
    /** `draw` = the Explorer level's centre start; `investigation` = the Investigation tab's start form. */
    readonly mode = input<'draw' | 'investigation'>('draw');
    /** The Space's masking mode (`InvService.masking`): an entity value enters the default Name only when it is `none`. */
    readonly maskingMode = input<string | null>(null);
    /** The profile's last degree: Degrees offers no more. */
    readonly maxDegree = input(3);
    /** Add mode (an open Investigation's "Add start entities…"): only the start inputs; Start records ONE seed step. */
    readonly adding = input(false);
    /** Saving an Investigation needs Incident management; starting unsaved does not (the form says so under Start). */
    readonly canWrite = input(true);
    /** The picked Dataset's columns (the page reads them); the Time column picker offers them. */
    readonly columns = input<readonly { name: string; type: string }[]>([]);
    /** The Dataset whose columns {@link columns} are: the page reads them when this changes. */
    readonly datasetPicked = output<string>();

    readonly start = output<StartRequest>();
    /** The start form's answer (`investigation` mode): the page draws it and starts working unsaved. */
    readonly create = output<UnsavedStartRequest>();
    readonly openAddData = output<void>();
    /** "A list" was chosen: the host re-reads the Space's Entity Lists, so a list made elsewhere since is offered. */
    readonly listsWanted = output<void>();

    /** The graph on screen (New form only): offered as a fifth Start from, a seedless Investigation over its mapping. */
    readonly graphOnScreen = input<{ name: string } | null>(null);
    /** Start from's choices: the four starts, plus *The graph on screen* in the New form while a graph is drawn. */
    readonly kindOptions = computed<PickerOption[]>(() => {
        if (this.adding()) return KINDS.filter((k) => k.value !== 'dataset');
        const g = this.mode() === 'investigation' ? this.graphOnScreen() : null;
        return g
            ? [...KINDS, { value: 'graph', label: 'Graph on screen', hint: `${g.name}, no start entities yet` }]
            : KINDS;
    });
    // "UTC (default)" is the blank choice: a second plain "UTC" would offer the same zone twice (T-036).
    readonly timeZones: PickerOption[] = timeZoneOptions('UTC (default)').filter((o) => o.value !== 'UTC');
    readonly titleId = computed(() => (this.mode() === 'draw' ? 'la-start-title' : 'la-new-investigation-title'));
    readonly form = new FormGroup({
        dataset: new FormControl('', { nonNullable: true }),
        kind: new FormControl<StartKind | 'graph'>('entity', { nonNullable: true }),
        entityType: new FormControl('', { nonNullable: true }),
        value: new FormControl('', { nonNullable: true }),
        listId: new FormControl('', { nonNullable: true }),
        paste: new FormControl('', { nonNullable: true }),
        // ── the Investigation tab's start form only ──
        timeCol: new FormControl('', { nonNullable: true }),
        timeColZone: new FormControl('', { nonNullable: true }),
        degrees: new FormControl(1, { nonNullable: true }),
    });
    private readonly datasetId = toSignal(this.form.controls.dataset.valueChanges, { initialValue: '' });
    readonly kind = toSignal(this.form.controls.kind.valueChanges, { initialValue: 'entity' as StartKind | 'graph' });
    private readonly entityType = toSignal(this.form.controls.entityType.valueChanges, { initialValue: '' });
    private readonly valueText = toSignal(this.form.controls.value.valueChanges, { initialValue: '' });
    private readonly listId = toSignal(this.form.controls.listId.valueChanges, { initialValue: '' });
    private readonly pasteText = toSignal(this.form.controls.paste.valueChanges, { initialValue: '' });
    /** Submit was pressed: a missing list choice says so. */
    readonly tried = signal(false);

    readonly picked = computed(() => this.catalogue().datasets.find((d) => d.id === this.datasetId()) ?? null);
    readonly datasetName = computed(() => this.picked()?.name ?? 'the Dataset');
    readonly datasetOptions = computed<PickerOption[]>(() =>
        this.catalogue().datasets.map((d) => ({ value: d.id, label: d.name })),
    );
    /** The picked Dataset's types first (its primary leading), then every other type that can start a graph. */
    readonly typeOptions = computed<PickerOption[]>(() => {
        const cat = this.catalogue();
        const here = this.picked()?.types ?? [];
        const ordered = [...here, ...cat.types.map((t) => t.id).filter((id) => !here.includes(id))];
        return ordered.map((id) => ({
            value: id,
            label: cat.types.find((t) => t.id === id)?.label ?? id,
            ...(here.includes(id) ? {} : { hint: `Not a link end of ${this.datasetName()}` }),
        }));
    });
    readonly typeLabel = computed(
        // `||`, not `??`: an unpicked type is '' and must fall through (X7)
        () => this.catalogue().types.find((t) => t.id === this.entityType())?.label || this.entityType() || '',
    );
    readonly listOptions = computed<PickerOption[]>(() =>
        this.lists()
            .filter((l) => !l.retired)
            .map((l) => ({
                value: l.id,
                label: l.title,
                hint: listHint(l, this.catalogue().types),
            })),
    );

    // ── the Investigation tab's start form: the default Name and Purpose Save offers, Time column, Degrees ──

    /** The default Name and Purpose for the current input (LA-PANELS-REDESIGN-1); Save's dialog opens with them. */
    readonly defaults = computed(() =>
        newInvestigationDefaults({
            kind: this.kind(),
            typeLabel: this.typeLabel(),
            value: this.valueText(),
            listTitle: this.lists().find((l) => l.id === this.listId())?.title ?? '',
            pasted: parsePastedValues(this.pasteText()).length,
            datasetName: this.datasetName(),
            graphName: this.graphOnScreen()?.name,
            maskingMode: this.maskingMode(),
        }),
    );
    /**
     * The Time column picker: blank = the Dataset's own time column (or none), then the Dataset's date columns, then the
     * rest - a column of another type may still hold times as text.
     */
    readonly timeColumnOptions = computed<PickerOption[]>(() => {
        const own = this.picked()?.timeCol;
        const cols = [...this.columns()].sort((a, b) => Number(b.type === 'date') - Number(a.type === 'date'));
        return [
            { value: '', label: own ? `${own} (the Dataset's)` : 'None' },
            ...cols.filter((c) => c.name !== own).map((c) => ({ value: c.name, label: c.name, hint: c.type })),
        ];
    });
    /** The Degrees stepper (LA-INV-EXPAND-1): the start draws one ring, so 1 … the profile's maximum, at most three. */
    readonly degreeMax = computed(() => Math.max(1, Math.min(MOST_DEGREES_ON_START, this.maxDegree())));
    readonly fewestDegrees = DISABLED_REASON.fewestDegrees;
    /** The pack's limit only when the pack stops first; otherwise the form's own cap, said as the form's. */
    readonly degreeMaxReason = computed(() =>
        this.maxDegree() > MOST_DEGREES_ON_START ? expandOnStartCap(MOST_DEGREES_ON_START) : mostDegrees(this.degreeMax()),
    );

    constructor() {
        // The graph left the screen: *The graph on screen* is no longer a choice.
        effect(() => {
            if (!this.graphOnScreen() && this.form.controls.kind.value === 'graph') this.form.controls.kind.setValue('entity');
        });
        // The Dataset: the page's preselect (`?dataset=`) when it can start a graph, else keep a valid pick, else the first.
        effect(() => {
            const ids = this.catalogue().datasets.map((d) => d.id);
            const wanted = this.dataset();
            const current = this.form.controls.dataset.value;
            const next = wanted && ids.includes(wanted) ? wanted : ids.includes(current) ? current : (ids[0] ?? '');
            if (next !== current) this.form.controls.dataset.setValue(next);
        });
        this.form.controls.kind.valueChanges.pipe(takeUntilDestroyed()).subscribe((k) => {
            if (k === 'list') this.listsWanted.emit();
        });
        // A-7b: a new Dataset preselects its primary Entity Type (else its first link-end type).
        this.form.controls.dataset.valueChanges.pipe(takeUntilDestroyed()).subscribe((id) => {
            this.form.controls.entityType.setValue(this.defaultType(id));
            this.form.controls.timeCol.setValue('');
            if (id) this.datasetPicked.emit(id);
        });
        // T-028: the Entity Types read can answer AFTER the Dataset was picked, so the default above was taken from a
        // partial catalogue (blank, or a type the Space does not keep). Re-default once the catalogue moves, unless the
        // type in the box is one it still offers.
        effect(() => {
            const offered = this.catalogue().types.map((t) => t.id);
            untracked(() => {
                const ctl = this.form.controls.entityType;
                if (ctl.value && offered.includes(ctl.value)) return;
                const type = this.defaultType(this.form.controls.dataset.value);
                if (type !== ctl.value) ctl.setValue(type);
            });
        });
        // A reveal asked for one Entity Type is not carried to the next.
        this.form.controls.entityType.valueChanges
            .pipe(takeUntilDestroyed())
            .subscribe(() => this.revealSuggestions.set(false));
    }

    /** The Entity Type a Dataset starts with: its primary, else its first link-end type, else the first offered. */
    private defaultType(datasetId: string): string {
        const cat = this.catalogue();
        const d = cat.datasets.find((x) => x.id === datasetId);
        return d?.primaryEntityType ?? d?.types[0] ?? cat.types[0]?.id ?? '';
    }

    // ── one entity: the type-ahead ──

    /** A masked type's values were asked for by someone who may reveal them (LA-ONBOARD-DEFECTS-1); the server audits it. */
    readonly revealSuggestions = signal(false);

    // The kind is a source too: switching back to One entity re-asks (LA-ONBOARD-DEFECTS-1), else the answer from before the
    // switch - or none, when the type or Dataset changed meanwhile - stayed.
    private readonly suggestions = toSignal(
        combineLatest([
            this.form.controls.value.valueChanges.pipe(startWith(''), debounceTime(250), distinctUntilChanged()),
            this.form.controls.entityType.valueChanges.pipe(startWith('')),
            this.form.controls.dataset.valueChanges.pipe(startWith('')),
            this.form.controls.kind.valueChanges.pipe(startWith(this.form.controls.kind.value)),
            toObservable(this.revealSuggestions),
        ]).pipe(
            switchMap(([prefix, type, dataset, kind, reveal]) =>
                type && dataset && kind === 'entity'
                    ? this.profiles
                          .suggestEntities(type, {
                              prefix: prefix.trim(),
                              dataset,
                              limit: 8,
                              ...(reveal ? { reveal: true } : {}),
                          })
                          .pipe(catchError(() => of(null as EntitySuggestions | null)))
                    : of(null as EntitySuggestions | null),
            ),
            takeUntilDestroyed(this.destroyRef),
        ),
        { initialValue: null as EntitySuggestions | null },
    );
    readonly suggested = computed<EntitySuggestion[]>(() => this.suggestions()?.items ?? []);
    /** Why nothing is suggested, when that is worth saying (a masked type, nothing matching). */
    readonly suggestNote = computed(() => {
        const s = this.suggestions();
        if (!s) return '';
        if (s.masked && s.revealed)
            return 'This Entity Type is masked: its values are shown because you may reveal them, and each look-up is recorded.';
        if (s.masked) return 'This Entity Type is masked: type the whole value. Nothing is suggested.';
        if (s.assessed && !s.items.length) return `No ${this.typeLabel()} in ${this.datasetName()} starts like this.`;
        return '';
    });
    /** A masked type suggests nothing; someone who may reveal is offered its values (an explicit, audited ask). */
    readonly offerReveal = computed(() => {
        const s = this.suggestions();
        return !!s?.masked && !s.revealed && this.lens.canRevealLinkEntities();
    });
    readonly line = (s: EntitySuggestion): string => suggestionLine(s);

    // ── the whole Dataset: how many entities it holds, for the node-cap warning ──

    private readonly wholeCount = toSignal(
        combineLatest([
            this.form.controls.kind.valueChanges.pipe(startWith(this.form.controls.kind.value)),
            this.form.controls.dataset.valueChanges.pipe(startWith('')),
        ]).pipe(
            switchMap(([kind]) => {
                const d = this.picked();
                const type = d?.primaryEntityType ?? d?.types[0];
                if (kind !== 'dataset' || !d || !type) return of(null);
                return this.profiles.suggestEntities(type, { dataset: d.id, limit: 1 }).pipe(
                    map((r) => r.distinct ?? null),
                    catchError(() => of(null)),
                );
            }),
            takeUntilDestroyed(this.destroyRef),
        ),
        { initialValue: null as number | null },
    );
    readonly wholeWarning = computed(() => {
        const d = this.picked();
        const type = d?.primaryEntityType ?? d?.types[0];
        const label = this.catalogue().types.find((t) => t.id === type)?.label ?? type ?? 'linked';
        return wholeDatasetWarning(this.wholeCount(), this.nodeCap(), this.datasetName(), label);
    });

    readonly submitLabel = computed(() => {
        if (this.adding()) return 'Add start entities';
        if (this.mode() === 'investigation') return 'Start';
        switch (this.kind()) {
            case 'list':
                return 'Start from this list';
            case 'paste':
                return 'Start from these';
            case 'dataset':
                return 'Draw the whole Dataset';
            default:
                return 'Draw';
        }
    });

    /** What pressing the button will do on this level, in one line. */
    readonly whatHappens = computed(() => {
        const kind = this.kind();
        if (this.adding()) return 'Records them as one Start step of this Investigation.';
        if (this.mode() === 'investigation') {
            if (kind === 'graph') return 'Works on the graph on screen, with no start entities yet. Nothing is recorded until you Save.';
            if (kind === 'dataset') return 'Draws the whole Dataset, with no start entities yet. Nothing is recorded until you Save.';
            return 'Draws the start and its links. Nothing is recorded until you Save.';
        }
        if (kind === 'dataset') return 'Draws the whole Dataset at once.';
        return kind === 'entity'
            ? 'Draws it and its links. Busy entities are held; Expand… goes further.'
            : 'Draws them and their links. Busy entities are held; Expand… goes further.';
    });

    /** LA-INVESTIGATE-UX-1 "Investigate this" on a node: a fresh form starting from that one entity. */
    prefillEntity(value: string): void {
        this.resetNew();
        this.form.controls.value.setValue(value);
    }

    /** Each File ▸ New starts fresh: the start inputs are cleared (the form stays mounted while an Investigation is open). */
    resetNew(): void {
        const c = this.form.controls;
        c.kind.setValue('entity');
        c.value.setValue('');
        c.listId.setValue('');
        c.paste.setValue('');
        c.timeCol.setValue('');
        c.timeColZone.setValue('');
        c.degrees.setValue(1);
        this.form.markAsPristine();
        this.form.markAsUntouched();
        this.tried.set(false);
    }

    submit(): void {
        this.tried.set(true);
        const v = this.form.getRawValue();
        const graph = this.mode() === 'investigation' && v.kind === 'graph';
        if (!v.dataset && !graph) return;
        const req = graph ? ({ kind: 'graph' } as const) : this.request(v);
        if (!req) return;
        if (this.mode() === 'draw') {
            if (req.kind !== 'graph') this.start.emit(req);
            return;
        }
        this.create.emit({
            start: req,
            defaults: this.defaults(),
            timeCol: v.timeCol,
            timeColZone: v.timeColZone,
            degrees: req.kind === 'dataset' || req.kind === 'graph' ? 0 : Math.max(1, Number(v.degrees) || 1),
        });
    }

    /** The start the form names, or null (with the missing input marked). */
    private request(v: ReturnType<LinkAnalysisStartBoxComponent['form']['getRawValue']>): Exclude<StartRequest, { kind: 'found' }> | null {
        switch (v.kind) {
            case 'entity': {
                const value = v.value.trim();
                if (!value) {
                    this.form.controls.value.setErrors({ required: true });
                    this.form.controls.value.markAsTouched();
                    return null;
                }
                return { kind: 'entity', datasetId: v.dataset, entityType: v.entityType, value };
            }
            case 'list':
                return v.listId ? { kind: 'list', datasetId: v.dataset, listId: v.listId } : null;
            case 'paste': {
                const values = parsePastedValues(v.paste);
                if (!values.length) {
                    this.form.controls.paste.setErrors({ required: true });
                    this.form.controls.paste.markAsTouched();
                    return null;
                }
                return { kind: 'paste', datasetId: v.dataset, entityType: v.entityType, values };
            }
            default:
                return { kind: 'dataset', datasetId: v.dataset };
        }
    }
}
