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
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { catchError, combineLatest, debounceTime, distinctUntilChanged, map, of, startWith, switchMap } from 'rxjs';
import { LensService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import {
    EntityProfileService,
    EntitySuggestion,
    EntitySuggestions,
} from '@inspecto/link-analysis/api/entity-profile.service';
import type { EntityListSummary } from '@inspecto/link-analysis/api/inv.service';
import { oneLine } from './la-starter';
import { LinkAnalysisView } from './link-analysis.service';
import {
    StartCatalogue,
    StartKind,
    StartRequest,
    parsePastedValues,
    suggestionLine,
    wholeDatasetWarning,
} from './start-from';

/** A notice the page shows under the box after a start (what was drawn, what was not found). */
export interface StartNotice {
    variant: 'info' | 'warning';
    text: string;
}

const KINDS: { value: StartKind; label: string }[] = [
    { value: 'entity', label: 'One entity' },
    { value: 'list', label: 'A list' },
    { value: 'paste', label: 'A paste' },
    { value: 'dataset', label: 'The whole Dataset' },
];

/**
 * **Start from...** (LA-ONBOARD-1 phase 6, OB-9; UC-09): the first screen of Link Analysis. One box replaces the old starter
 * cards: pick the Dataset, then start from **one entity** (the Entity Type FIRST, the Dataset's primary type preselected -
 * A-7b - then the value, with a type-ahead of matches and their link counts), **a list** (an Entity List), **a paste**
 * (values, unknown ones reported after) or **the whole Dataset** (allowed, warned above the node cap). The page carries it
 * out: on the Explorer level a start draws the chosen entities plus one ring, busy ones held; on the Investigations level
 * one entity seeds an Investigation. The saved-view starts, *Explore a Dataset* (choose the columns yourself) and *Add data*
 * stay below.
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
        MatRadioModule,
        ReactiveFormsModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        TermHelpComponent,
    ],
    host: { class: 'block w-full max-w-3xl p-4' },
    template: `
        <h2 class="m-0 text-lg font-semibold" id="la-start-title">Start from...</h2>
        <p class="text-secondary mb-3 mt-1 text-sm">
            Draw a few entities and who they are linked to, then Expand one degree at a time - not the whole hairball. Pick
            a <inspecto-term term="dataset"></inspecto-term>, then an
            <inspecto-term term="entity-type"></inspecto-term> and one of its values.
        </p>
        @if (catalogue().datasets.length) {
            <form
                class="start-box flex flex-col gap-3"
                data-testid="start-box"
                aria-labelledby="la-start-title"
                [formGroup]="form"
                (ngSubmit)="submit()"
            >
                <inspecto-option-picker
                    data-testid="start-dataset"
                    label="Dataset"
                    [outlined]="true"
                    [options]="datasetOptions()"
                    [help]="datasetHelp()"
                    formControlName="dataset"
                ></inspecto-option-picker>
                <mat-radio-group
                    class="flex flex-wrap gap-x-4"
                    aria-label="Start from"
                    data-testid="start-kind"
                    formControlName="kind"
                >
                    @for (k of kinds; track k.value) {
                        <mat-radio-button [value]="k.value" [attr.data-kind]="k.value">{{ k.label }}</mat-radio-button>
                    }
                </mat-radio-group>

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
                <p class="text-secondary m-0 text-xs" data-testid="start-what">{{ whatHappens() }}</p>
                <div>
                    <button
                        mat-flat-button
                        color="primary"
                        type="submit"
                        data-testid="start-submit"
                        [disabled]="busy()"
                    >
                        {{ submitLabel() }}
                    </button>
                </div>
            </form>
        } @else {
            <p class="m-0 text-sm" data-testid="start-no-dataset">
                No Dataset in this Space has a link yet, so there is nothing to start from. Choose
                <em>Add data</em> below to bring in a file of events, or <em>Explore a Dataset</em> to choose its from
                and to columns yourself.
            </p>
        }
        @if (notice(); as n) {
            <inspecto-alert class="mt-3 block" [variant]="n.variant" data-testid="start-notice">{{
                n.text
            }}</inspecto-alert>
        }

        <h3 class="mb-2 mt-5 text-sm font-semibold">Or</h3>
        <ul class="m-0 grid list-none gap-3 p-0 grid-cols-[repeat(auto-fit,minmax(11rem,1fr))]">
            @if (followView(); as v) {
                <li>
                    <button
                        type="button"
                        class="starter-card"
                        data-testid="starter-follow"
                        (click)="followMoney.emit()"
                    >
                        <mat-icon class="icon-size-6" svgIcon="heroicons_outline:banknotes"></mat-icon>
                        <span class="block text-sm font-semibold">Follow the money (example)</span>
                        <span class="text-secondary block text-xs">
                            Opens the saved view {{ v.name }} and draws it now.
                            {{ followAbout() }}
                        </span>
                    </button>
                </li>
            }
            @if (savedViewCount() > 0) {
                <li>
                    <button
                        type="button"
                        class="starter-card"
                        data-testid="starter-views"
                        (click)="openSavedViews.emit()"
                    >
                        <mat-icon class="icon-size-6" svgIcon="heroicons_outline:folder-open"></mat-icon>
                        <span class="block text-sm font-semibold">Open a saved view</span>
                        <span class="text-secondary block text-xs">
                            {{ savedViewCount() }} saved {{ savedViewCount() === 1 ? 'view' : 'views' }} in this Space.
                            A saved view re-reads live data; it is not evidence.
                        </span>
                    </button>
                </li>
            }
            <li>
                <button
                    type="button"
                    class="starter-card"
                    data-testid="starter-explore"
                    (click)="exploreDataset.emit()"
                >
                    <mat-icon class="icon-size-6" svgIcon="heroicons_outline:circle-stack"></mat-icon>
                    <span class="block text-sm font-semibold">Explore a Dataset</span>
                    <span class="text-secondary block text-xs">
                        Choose a Dataset and its from and to columns yourself - the ones that look like links are listed
                        first.
                    </span>
                </button>
            </li>
            @if (addData()) {
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
            }
        </ul>
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
    /** The Investigations level: one entity seeds an Investigation instead of drawing a ring. */
    readonly investigations = input(false);
    readonly nodeCap = input(1000);
    readonly notice = input<StartNotice | null>(null);
    readonly busy = input(false);
    /** The ready-made "follow the money" saved view, or null (the card is then not shown). */
    readonly followView = input<LinkAnalysisView | null>(null);
    readonly savedViewCount = input(0);
    /** The host has an Add data screen (`/add-data`): the card is offered. */
    readonly addData = input(false);

    readonly start = output<StartRequest>();
    readonly followMoney = output<void>();
    readonly exploreDataset = output<void>();
    readonly openSavedViews = output<void>();
    readonly openAddData = output<void>();
    /** "A list" was chosen: the host re-reads the Space's Entity Lists, so a list made elsewhere since is offered. */
    readonly listsWanted = output<void>();

    readonly kinds = KINDS;
    readonly form = new FormGroup({
        dataset: new FormControl('', { nonNullable: true }),
        kind: new FormControl<StartKind>('entity', { nonNullable: true }),
        entityType: new FormControl('', { nonNullable: true }),
        value: new FormControl('', { nonNullable: true }),
        listId: new FormControl('', { nonNullable: true }),
        paste: new FormControl('', { nonNullable: true }),
    });
    private readonly datasetId = toSignal(this.form.controls.dataset.valueChanges, { initialValue: '' });
    readonly kind = toSignal(this.form.controls.kind.valueChanges, { initialValue: 'entity' as StartKind });
    private readonly entityType = toSignal(this.form.controls.entityType.valueChanges, { initialValue: '' });
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

    constructor() {
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
        switch (this.kind()) {
            case 'list':
                return 'Start from this list';
            case 'paste':
                return 'Start from these';
            case 'dataset':
                return 'Draw the whole Dataset';
            default:
                return this.investigations() ? 'Investigate' : 'Start';
        }
    });

    /** What pressing the button will do on this level, in one line. */
    readonly whatHappens = computed(() => {
        const kind = this.kind();
        if (kind === 'dataset') return 'Draws every link of the Dataset at once.';
        if (kind === 'entity' && this.investigations())
            return 'Seeds an Investigation from it: you give a purpose, and it expands one degree of who it is linked to.';
        const ring =
            kind === 'entity'
                ? 'Draws it and everything linked to it - one ring - with busy ones held; Expand… continues from there.'
                : 'Draws them and everything linked to them - one ring - with busy ones held; Expand… continues from there.';
        return this.investigations() ? `${ring} They are queued as the first seeds of an Investigation.` : ring;
    });

    readonly followAbout = computed(() => oneLine(this.followView()?.description, 110));

    submit(): void {
        this.tried.set(true);
        const v = this.form.getRawValue();
        if (!v.dataset) return;
        switch (v.kind) {
            case 'entity': {
                const value = v.value.trim();
                if (!value) {
                    this.form.controls.value.setErrors({ required: true });
                    this.form.controls.value.markAsTouched();
                    return;
                }
                this.start.emit({ kind: 'entity', datasetId: v.dataset, entityType: v.entityType, value });
                return;
            }
            case 'list':
                if (v.listId) this.start.emit({ kind: 'list', datasetId: v.dataset, listId: v.listId });
                return;
            case 'paste': {
                const values = parsePastedValues(v.paste);
                if (!values.length) {
                    this.form.controls.paste.setErrors({ required: true });
                    this.form.controls.paste.markAsTouched();
                    return;
                }
                this.start.emit({ kind: 'paste', datasetId: v.dataset, entityType: v.entityType, values });
                return;
            }
            default:
                this.start.emit({ kind: 'dataset', datasetId: v.dataset });
        }
    }
}
