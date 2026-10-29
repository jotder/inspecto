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
} from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { catchError, combineLatest, debounceTime, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';
import { LensService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { timeZoneOptions } from '@inspecto/core/schema/time-zones';
import {
    EntityProfileService,
    EntitySuggestion,
    EntitySuggestions,
} from '@inspecto/link-analysis/api/entity-profile.service';
import type { EntityListSummary } from '@inspecto/link-analysis/api/inv.service';
import { LaFoldComponent } from './la-fold.component';
import {
    NewInvestigationRequest,
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
    { value: 'list', label: 'A list', hint: 'The members of an Entity List' },
    { value: 'paste', label: 'A paste', hint: 'Values you paste, one per line' },
    { value: 'dataset', label: 'The whole Dataset', hint: 'Every link of the Dataset' },
];

/** Expand on start: how many degrees, capped at the profile's maximum. */
const DEGREE_CHOICES = [0, 1, 2, 3];

/**
 * **Start from...** (LA-ONBOARD-1 phase 6, OB-9; UC-09), in two modes.
 *
 * - `draw` (the Query zone's empty canvas): pick the Dataset, then start from **one entity** (the Entity Type FIRST, the
 *   Dataset's primary type preselected - A-7b - then the value, with a type-ahead of matches and their link counts), **a
 *   list** (an Entity List), **a paste** (values, unknown ones reported after) or **the whole Dataset** (allowed, warned
 *   above the node cap). The page draws the chosen entities plus one ring, busy ones held. On the Investigations level
 *   *New Investigation…* below opens the form that starts one.
 * - `investigation` (LA-PANELS-REDESIGN-1, the Investigation tab's **New Investigation** form): the same Start from
 *   choice and inputs, then a **Name** and a **Reason** pre-filled from the input (re-defaulted until edited), the time
 *   column under *More*, and an optional *Expand on start*. One press creates the Investigation, seeds it and expands.
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
        ReactiveFormsModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        LaFoldComponent,
        PenFieldComponent,
        TermHelpComponent,
    ],
    host: { class: 'block w-full max-w-3xl', '[class.p-4]': "mode() === 'draw'" },
    template: `
        @if (mode() === 'draw') {
            <h2 class="m-0 text-lg font-semibold" [id]="titleId()">Start from...</h2>
            <p class="text-secondary mb-3 mt-1 text-sm">
                Draw a few entities and who they are linked to, then Expand one degree at a time - not the whole hairball.
                Pick a <inspecto-term term="dataset"></inspecto-term>, then an
                <inspecto-term term="entity-type"></inspecto-term> and one of its values.
            </p>
        } @else {
            <h3 class="m-0 text-base font-semibold" [id]="titleId()">New Investigation</h3>
            <p class="text-secondary mb-2 mt-1 text-sm">
                Records each step you take from this start, with who, when and why, so it can be replayed and handed over.
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
                        [help]="datasetHelp()"
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
                            <mat-label>{{ typeLabel() }}</mat-label>
                            <input
                                matInput
                                data-testid="start-value"
                                formControlName="value"
                                autocomplete="off"
                                [matAutocomplete]="suggestions"
                            />
                            @if (form.controls.value.hasError('required')) {
                                <mat-error>Type the {{ typeLabel() }} to start from.</mat-error>
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
                                    Show its values (you may reveal them; recorded)
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
                                <p class="text-warn m-0 text-xs" role="alert">Pick the Entity List to start from.</p>
                            }
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="start-no-lists">
                                No Entity List in this Space yet - make one on the Entity Lists page, or paste the
                                values.
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
                            Starts over the graph on screen ({{ graphOnScreen()?.name }}) and its one mapping. The
                            query's filter is not carried over: the Investigation reads the whole Dataset.
                        </p>
                    }
                    @case ('dataset') {
                        @if (wholeWarning(); as w) {
                            <inspecto-alert variant="warning" data-testid="start-whole-warning">{{ w }}</inspecto-alert>
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="start-whole-note">
                                Draws every link in {{ datasetName() }}. Above
                                {{ nodeCap().toLocaleString('en') }} nodes the graph is cut to the busiest.
                            </p>
                        }
                    }
                }
                @if (mode() === 'investigation') {
                    <inspecto-pen-field
                        data-testid="new-name"
                        label="Name"
                        formControlName="name"
                        [maxlength]="200"
                        [error]="form.controls.name.hasError('maxlength') ? 'At most 200 characters.' : ''"
                    />
                    <div>
                        <inspecto-pen-field
                            data-testid="new-reason"
                            label="Reason"
                            [required]="true"
                            [multiline]="true"
                            formControlName="reason"
                            [maxlength]="1000"
                            [error]="reasonError()"
                        />
                        <p class="text-secondary m-0 text-xs">
                            The purpose or legal basis, recorded with the Investigation and shown in its Dossier.
                        </p>
                    </div>
                    <la-fold title="More" data-testid="new-more">
                        <div class="flex flex-col gap-2 p-2">
                            <div>
                                <inspecto-pen-field
                                    label="Time column"
                                    formControlName="timeCol"
                                    [maxlength]="200"
                                    [placeholder]="picked()?.timeCol ?? ''"
                                />
                                <p class="text-secondary m-0 text-xs">
                                    The event time windows and the coverage check read{{
                                        picked()?.timeCol ? ' - blank uses ' + picked()!.timeCol : ''
                                    }}.
                                </p>
                            </div>
                            <inspecto-option-picker
                                label="Time column zone"
                                formControlName="timeColZone"
                                [options]="timeZones"
                                help="The zone a plain TIMESTAMP time column is read in. A column that already carries a zone refuses one."
                            />
                        </div>
                    </la-fold>
                    @if (kind() !== 'dataset' && kind() !== 'graph') {
                        <la-fold title="Expand on start" data-testid="new-expand">
                            <div class="flex flex-col gap-2 p-2">
                                <inspecto-option-picker
                                    data-testid="new-degrees"
                                    label="Expand"
                                    formControlName="degrees"
                                    [options]="degreeOptions()"
                                />
                                <p class="text-secondary m-0 text-xs">
                                    Each degree is a recorded expand step with the profile's read settings; busy entities
                                    are held. Expand… on the canvas goes further later.
                                </p>
                            </div>
                        </la-fold>
                    }
                }
                <p class="text-secondary m-0 text-xs" data-testid="start-what">{{ whatHappens() }}</p>
                <div>
                    <button
                        mat-flat-button
                        color="primary"
                        type="submit"
                        data-testid="start-submit"
                        [disabled]="busy() || (mode() === 'investigation' && !canWrite())"
                    >
                        {{ submitLabel() }}
                    </button>
                </div>
            </form>
        } @else {
            <p class="m-0 text-sm" data-testid="start-no-dataset">
                No Dataset in this Space has a link yet, so there is nothing to start from. Bring in a file of events with
                <em>Add data</em>, or choose a Dataset's from and to columns yourself with File ▸ Explore dataset….
            </p>
        }
        @if (notice(); as n) {
            <inspecto-alert class="mt-3 block" [variant]="n.variant" data-testid="start-notice">{{
                n.text
            }}</inspecto-alert>
        }

        @if (mode() === 'draw') {
            <p class="text-secondary m-0 mt-4 text-sm" data-testid="start-file-hint">
                Open…, Explore dataset… and the examples are in the File menu.
            </p>
            @if (investigations()) {
                <div class="mt-2">
                    <button mat-stroked-button type="button" data-testid="start-new-investigation" (click)="newInvestigation.emit()">
                        <mat-icon svgIcon="heroicons_outline:document-plus"></mat-icon>
                        New Investigation…
                    </button>
                </div>
            }
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
    /** The Investigations level: *New Investigation…* is offered under the draw. */
    readonly investigations = input(false);
    readonly nodeCap = input(1000);
    readonly notice = input<StartNotice | null>(null);
    readonly busy = input(false);
    /** The host has an Add data screen (`/add-data`): the card is offered. */
    readonly addData = input(false);
    /** `draw` = the Query zone's start; `investigation` = the Investigation tab's New Investigation form. */
    readonly mode = input<'draw' | 'investigation'>('draw');
    /** The Space's masking mode (`InvService.masking`): an entity value enters the default Name only when it is `none`. */
    readonly maskingMode = input<string | null>(null);
    /** The profile's last degree: Expand on start offers no more. */
    readonly maxDegree = input(3);
    /** Starting an Investigation needs the Incident management capability. */
    readonly canWrite = input(true);

    readonly start = output<StartRequest>();
    /** The New Investigation form's answer (`investigation` mode). */
    readonly create = output<NewInvestigationRequest>();
    /** *New Investigation…* under the draw box (Investigations level): the page opens the form in the Investigation tab. */
    readonly newInvestigation = output<void>();
    readonly openAddData = output<void>();
    /** "A list" was chosen: the host re-reads the Space's Entity Lists, so a list made elsewhere since is offered. */
    readonly listsWanted = output<void>();

    /** The graph on screen (New form only): offered as a fifth Start from, a seedless Investigation over its mapping. */
    readonly graphOnScreen = input<{ name: string } | null>(null);
    /** Start from's choices: the four starts, plus *The graph on screen* in the New form while a graph is drawn. */
    readonly kindOptions = computed<PickerOption[]>(() => {
        const g = this.mode() === 'investigation' ? this.graphOnScreen() : null;
        return g
            ? [...KINDS, { value: 'graph', label: 'The graph on screen', hint: `${g.name}: its mapping, no seed yet` }]
            : KINDS;
    });
    readonly timeZones: PickerOption[] = timeZoneOptions('UTC (default)');
    readonly titleId = computed(() => (this.mode() === 'draw' ? 'la-start-title' : 'la-new-investigation-title'));
    readonly form = new FormGroup({
        dataset: new FormControl('', { nonNullable: true }),
        kind: new FormControl<StartKind | 'graph'>('entity', { nonNullable: true }),
        entityType: new FormControl('', { nonNullable: true }),
        value: new FormControl('', { nonNullable: true }),
        listId: new FormControl('', { nonNullable: true }),
        paste: new FormControl('', { nonNullable: true }),
        // ── the New Investigation form only ──
        name: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
        reason: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(/\S/), Validators.maxLength(1000)],
        }),
        timeCol: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
        timeColZone: new FormControl('', { nonNullable: true }),
        degrees: new FormControl('0', { nonNullable: true }),
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
        this.catalogue().datasets.map((d) => ({
            value: d.id,
            label: d.name,
            hint: `${d.mapping.sourceCol} to ${d.mapping.targetCol}`,
        })),
    );
    readonly datasetHelp = computed(() => {
        const d = this.picked();
        return d ? `Links ${d.mapping.sourceCol} to ${d.mapping.targetCol} (${d.from}).` : '';
    });
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
        () => this.catalogue().types.find((t) => t.id === this.entityType())?.label ?? this.entityType() ?? 'value',
    );
    readonly listOptions = computed<PickerOption[]>(() =>
        this.lists()
            .filter((l) => !l.retired)
            .map((l) => ({
                value: l.id,
                label: l.title,
                hint: `${l.size.toLocaleString('en')} ${l.entityType} - ${l.purpose}`,
            })),
    );

    // ── the New Investigation form: default Name and Reason, Expand on start ──

    /** The default Name and Reason for the current input (LA-PANELS-REDESIGN-1). */
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
    readonly degreeOptions = computed<PickerOption[]>(() =>
        DEGREE_CHOICES.filter((d) => d <= this.maxDegree()).map((d) => ({
            value: String(d),
            label: d === 0 ? "Don't expand" : `${d} ${d === 1 ? 'degree' : 'degrees'}`,
        })),
    );
    readonly reasonError = computed(() => {
        this.tried();
        const c = this.form.controls.reason;
        if (c.hasError('maxlength')) return 'At most 1000 characters.';
        return this.tried() && c.invalid ? 'A reason is required to start an Investigation.' : '';
    });

    constructor() {
        // The graph left the screen: *The graph on screen* is no longer a choice.
        effect(() => {
            if (!this.graphOnScreen() && this.form.controls.kind.value === 'graph') this.form.controls.kind.setValue('entity');
        });
        // The Name and Reason follow the input until the analyst edits them; an edit is kept from then on.
        effect(() => {
            const d = this.defaults();
            if (this.mode() !== 'investigation') return;
            const { name, reason } = this.form.controls;
            if (name.pristine && name.value !== d.name) name.setValue(d.name);
            if (reason.pristine && reason.value !== d.reason) reason.setValue(d.reason);
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
            const d = this.catalogue().datasets.find((x) => x.id === id);
            const type = d?.primaryEntityType ?? d?.types[0] ?? this.catalogue().types[0]?.id ?? '';
            this.form.controls.entityType.setValue(type);
        });
        // A reveal asked for one Entity Type is not carried to the next.
        this.form.controls.entityType.valueChanges
            .pipe(takeUntilDestroyed())
            .subscribe(() => this.revealSuggestions.set(false));
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
        if (s.masked) return 'This Entity Type is masked: type the whole value - nothing is suggested.';
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
        if (this.mode() === 'investigation') return 'Start Investigation';
        switch (this.kind()) {
            case 'list':
                return 'Start from this list';
            case 'paste':
                return 'Start from these';
            case 'dataset':
                return 'Draw the whole Dataset';
            default:
                return 'Start';
        }
    });

    /** What pressing the button will do on this level, in one line. */
    readonly whatHappens = computed(() => {
        const kind = this.kind();
        if (this.mode() === 'investigation') {
            if (kind === 'graph') return 'Creates an Investigation over the graph on screen, with no seed yet.';
            if (kind === 'dataset')
                return 'Draws every link of the Dataset and creates an Investigation over it, with no seed yet.';
            return 'Creates the Investigation, records the start as its seed step, then expands as set under Expand on start.';
        }
        if (kind === 'dataset') return 'Draws every link of the Dataset at once.';
        const ring =
            kind === 'entity'
                ? 'Draws it and everything linked to it - one ring - with busy ones held; Expand… continues from there.'
                : 'Draws them and everything linked to them - one ring - with busy ones held; Expand… continues from there.';
        return ring;
    });

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
        const { name, reason } = this.form.controls;
        if (name.invalid || reason.invalid) {
            reason.markAsTouched();
            return;
        }
        this.create.emit({
            start: req,
            title: v.name.trim(),
            purpose: v.reason.trim(),
            timeCol: v.timeCol.trim(),
            timeColZone: v.timeColZone,
            degrees: req.kind === 'dataset' || req.kind === 'graph' ? 0 : Number(v.degrees) || 0,
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
