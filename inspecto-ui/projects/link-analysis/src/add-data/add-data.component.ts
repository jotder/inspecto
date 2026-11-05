import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    OnInit,
    computed,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatRadioModule } from '@angular/material/radio';
import { Router } from '@angular/router';
import type { ColDef } from 'ag-grid-community';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { LockedActionComponent } from '@inspecto/core/components/locked-action.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { statusEdgeClasses, statusRowClasses } from '@inspecto/core/components/status-badge.component';
import { DataTableComponent } from '@inspecto/core/data-table/data-table.component';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';
import {
    EntityFamily,
    EntityTypeGuide,
    OTHER_FAMILY,
    RecordTypeGuide,
    entityTypeOptions,
} from '@inspecto/link-analysis/api/entity-type-options';
import { LaLimitsService } from '@inspecto/link-analysis/link-analysis/la-limits.service';
import { OnboardingEventsService } from '@inspecto/core/onboarding/onboarding-events.service';
import { AddDataAccess } from './add-data-access';
import {
    ACCEPTED_EXTENSIONS,
    COLUMN_NAME,
    Choice,
    ColumnState,
    ColumnType,
    DATASET_NAME,
    FormatOverrides,
    ImportDataset,
    ImportKind,
    ImportSummary,
    OnboardDraft,
    PendingChoice,
    READ_FORMATS,
    RolesState,
    TYPE_LABELS,
    buildCommit,
    choiceKey,
    count,
    followOnClause,
    delimiterLabel,
    excelLine,
    fileProblem,
    formatSize,
    initialColumns,
    initialRoles,
    LINK_PRIMARIES,
    MEASURE_TYPES,
    LinkPrimary,
    PRIMARY_ROLE,
    columnRole,
    limitsLine,
    lowChoices,
    quoteLabel,
    suggestDatasetName,
    roleUnder,
} from './add-data.model';
import { ColumnRole, ROLE_HINTS, ROLE_LABELS } from '../api/column-roles';
import { ImportDatasetCardComponent } from './import-dataset-card.component';
import { ImportRunFailed, OnboardService } from './onboard.service';

type ColumnGroup = FormGroup<{
    name: FormControl<string>;
    rename: FormControl<string>;
    type: FormControl<string>;
    drop: FormControl<boolean>;
    entityType: FormControl<string>;
    role: FormControl<ColumnRole>;
}>;

const NONE = '';

/**
 * **Add data** - the ONE screen both apps use to **Onboard a file** (LA-ONBOARD-1 phase 4, OF-1; console
 * `ONBOARDING-FIRST-RUN-1` B1/C3). File first, on one screen:
 *
 * 1. **Drop or choose a file** - the formats and this Space's limits are shown before anything is sent; a database or a
 *    remote system is shown locked on Basic (DT-4).
 * 2. **Check the columns** - every detected choice is pre-applied with its evidence; a low-confidence one is highlighted
 *    and needs a click (OF-2). Rename or leave out a column; a different separator, header line or quote re-reads the file.
 * 3. **What does each column hold?** - an Entity Type per column, which way a link goes (from, to, and optionally its kind,
 *    time and amount), the main kind of thing; for details about entities, the column that identifies each one. The
 *    console passes `whatColumnsHold="optional"`: the step starts closed and a low guess left unopened is not applied.
 * 4. **Create and run** - one confirm; a large file answers 202 and the run is followed.
 * 5. **The result in plain words**, the Dataset card, and what to do next.
 *
 * When the person may not add data here (no `canUploadData`, or the Space's `data.self_import` is off) the whole screen is
 * the locked reason instead - never an upload that fails (A-15). Product copy never says "wizard".
 *
 * @example <inspecto-add-data exploreRoute="/link-analysis" (imported)="onImported($event)" />
 */
@Component({
    selector: 'inspecto-add-data',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        NgTemplateOutlet,
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatIconModule,
        MatRadioModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoOptionPickerComponent,
        LockedActionComponent,
        PenFieldComponent,
        DataTableComponent,
        ImportDatasetCardComponent,
    ],
    host: { class: 'block' },
    templateUrl: './add-data.component.html',
})
export class AddDataComponent implements OnInit {
    protected readonly access = inject(AddDataAccess);
    private readonly onboard = inject(OnboardService);
    private readonly entityProfiles = inject(EntityProfileService);
    private readonly limitsService = inject(LaLimitsService);
    private readonly router = inject(Router);
    private readonly destroyRef = inject(DestroyRef);
    private readonly events = inject(OnboardingEventsService);
    private readonly fb = inject(FormBuilder);

    /** `ask` (Inspecto LA): step 3 is part of the screen. `optional` (the console): it starts closed, one click opens it. */
    readonly whatColumnsHold = input<'ask' | 'optional'>('ask');
    /** Where *Explore in Link Analysis* goes, with `?dataset=<id>` (the console's is `/studio/link-analysis`). */
    readonly exploreRoute = input('/link-analysis');
    /** Handed to the Dataset card: the console's Pipelines route for *Open in editor*; null where there is no editor. */
    readonly editorRoute = input<string | null>(null);
    /** What the file holds when the screen opens. */
    readonly initialKind = input<ImportKind>('stream');
    /** A Dataset was created and ran: its run summary. */
    readonly imported = output<ImportSummary>();

    // ── state ────────────────────────────────────────────────────────────────────────────────────────────────────────
    protected readonly kind = new FormControl<ImportKind>('stream', { nonNullable: true });
    protected readonly file = signal<File | null>(null);
    protected readonly draft = signal<OnboardDraft | null>(null);
    protected readonly reading = signal(false);
    protected readonly running = signal(false);
    protected readonly following = signal(false);
    protected readonly error = signal('');
    /** A 503: this Space cannot take data at all (no write root); explained, not an error to retry. */
    protected readonly unavailable = signal('');
    protected readonly summary = signal<ImportSummary | null>(null);
    protected readonly dragging = signal(false);
    /** Whether this installation reads Excel: known after the first draft (or an Excel refusal). */
    protected readonly excel = signal<boolean | null>(null);
    /** The low-confidence choices the person confirmed with a click (OF-2). */
    protected readonly confirmed = signal<ReadonlySet<string>>(new Set());
    /** Step 3 opened (always, when `whatColumnsHold` is `ask`). */
    protected readonly whatOpen = signal(false);
    /** Bumped on every form change, so the computed views below re-read the form. */
    private readonly formTick = signal(0);
    private formSub: Subscription | null = null;

    protected readonly form = this.fb.nonNullable.group({
        name: ['', [Validators.required, Validators.pattern(DATASET_NAME)]],
        description: [''],
        delimiter: [''],
        header: ['true'],
        quote: [''],
        columns: this.fb.array<ColumnGroup>([]),
        primary: [NONE],
        from: [NONE],
        to: [NONE],
        kind: [NONE],
        primaryTime: [NONE],
        primaryMeasure: [NONE],
        identifying: [NONE],
        recordType: [NONE],
    });

    // ── words and options ────────────────────────────────────────────────────────────────────────────────────────────
    protected readonly accept = ACCEPTED_EXTENSIONS;
    protected readonly formats = READ_FORMATS;
    protected readonly typeOptions: PickerOption[] = (Object.keys(TYPE_LABELS) as ColumnType[]).map((t) => ({
        value: t,
        label: TYPE_LABELS[t],
    }));
    protected readonly delimiterOptions: PickerOption[] = [',', ';', '\t', '|'].map((d) => ({
        value: d,
        label: delimiterLabel(d),
    }));
    protected readonly headerOptions: PickerOption[] = [
        { value: 'true', label: 'The first line holds the column names' },
        { value: 'false', label: 'There is no header line' },
    ];
    protected readonly quoteOptions: PickerOption[] = [
        { value: '"', label: quoteLabel('"') },
        { value: "'", label: quoteLabel("'") },
        { value: '', label: quoteLabel('') },
    ];
    /** The Entity Types of this Space (`/inv/entity-types`); empty when not readable, then the draft's proposals stand in. */
    private readonly typeList = signal<{ types: EntityTypeGuide[]; families: EntityFamily[] }>({
        types: [],
        families: [],
    });
    private readonly entityTypes = computed<PickerOption[]>(() =>
        entityTypeOptions(this.typeList().types, this.typeList().families),
    );
    /** What a row of a Dataset can be, from the Space's packs (ENTITY-TYPE-CATALOG-1 P2); empty = not asked. */
    protected readonly recordTypes = signal<RecordTypeGuide[]>([]);
    protected readonly recordTypeOptions = computed<PickerOption[]>(() => [
        { value: NONE, label: 'Not said' },
        ...this.recordTypes().map((r) => ({
            value: r.id,
            label: r.label,
            ...(r.definition ? { hint: r.definition } : {}),
        })),
    ]);
    protected readonly excelText = computed(() => excelLine(this.excel()));
    /** This Space's limits before any upload (`import.*` in the Link Analysis limits), else null. */
    protected readonly limitsText = computed(() => {
        const items = new Map(this.limitsService.items().map((i) => [i.key, i.inForce]));
        const maxBytes = items.get('import.max_bytes');
        const maxRows = items.get('import.max_rows');
        const maxImports = items.get('import.max_datasets');
        if (maxBytes == null || maxRows == null || maxImports == null) return null;
        return limitsLine({ maxBytes, maxRows, maxImports });
    });
    private readonly maxBytes = computed(
        () =>
            this.draft()?.limits.maxBytes ??
            this.limitsService.items().find((i) => i.key === 'import.max_bytes')?.inForce ??
            null,
    );

    protected readonly columnGroups = computed(() => {
        this.formTick();
        return this.form.controls.columns.controls;
    });
    private readonly columnStates = computed<ColumnState[]>(() => {
        this.formTick();
        return this.form.controls.columns.getRawValue();
    });
    private readonly dropped = computed(
        () =>
            new Set(
                this.columnStates()
                    .filter((c) => c.drop)
                    .map((c) => c.name),
            ),
    );
    protected readonly keptColumns = computed(() => this.columnStates().filter((c) => !c.drop));
    protected readonly entityOptions = computed<PickerOption[]>(() => {
        const known = this.entityTypes();
        const ids = new Set(known.map((o) => o.value));
        const proposed = (this.draft()?.entityTypes.columns ?? [])
            .map((c) => c.entityType.value)
            .filter((v): v is string => !!v && !ids.has(v));
        return [
            { value: NONE, label: 'Nothing: a detail', hint: 'Shown on links or entities, not drawn as a node' },
            ...known,
            // a proposal the Space does not know: under Other when the known types are grouped by family
            ...[...new Set(proposed)].map((v) => ({
                value: v,
                label: v,
                ...(known.some((o) => o.group) ? { group: OTHER_FAMILY } : {}),
            })),
        ];
    });
    /**
     * Each column's choices (ENTITY-TYPE-CATALOG-1 P2): {@link entityOptions}, with the chosen record type's likely types first
     * and a type whose value shape every sampled value of the column fits marked "Shape matches". The sample is the
     * draft column's own sampled values: the person's file, before anything is masked.
     */
    protected readonly entityOptionsByColumn = computed<ReadonlyMap<string, PickerOption[]>>(() => {
        const d = this.draft();
        const base = this.entityOptions();
        const { types, families } = this.typeList();
        if (!d || !types.length) return new Map();
        this.formTick();
        const rt = this.recordTypes().find((r) => r.id === this.form.controls.recordType.value) ?? null;
        const known = new Set(types.map((t) => t.id));
        return new Map(
            d.columns.map((c) => {
                const sample = (c.sample ?? []).map((v) => (v == null ? null : String(v)));
                const narrowed = entityTypeOptions(types, families, { recordType: rt, sample });
                // the "nothing" choice first and a proposal the Space does not know last, as entityOptions has them
                return [c.name, [base[0], ...narrowed, ...base.filter((o) => o.value !== NONE && !known.has(o.value))]];
            }),
        );
    });
    protected readonly columnOptions = computed<PickerOption[]>(() =>
        this.keptColumns().map((c) => ({ value: c.name, label: c.rename.trim() || c.name })),
    );
    protected readonly optionalColumnOptions = computed<PickerOption[]>(() => [
        { value: NONE, label: 'None' },
        ...this.columnOptions(),
    ]);
    /**
     * The columns a link choice may name (LA-COLUMN-ROLES-1 P1): any for the kind; the Time columns for its time, the
     * Measure columns for its measure.
     */
    protected readonly linkColumnOptions = computed<Record<LinkPrimary, PickerOption[]>>(() => {
        const of = (role: ColumnRole | null) => [
            { value: NONE, label: 'None' },
            ...this.keptColumns()
                .filter((c) => !role || columnRole(c) === role)
                .map((c) => ({ value: c.name, label: c.rename.trim() || c.name })),
        ];
        return {
            kind: of(null),
            primaryTime: of(PRIMARY_ROLE.primaryTime),
            primaryMeasure: of(PRIMARY_ROLE.primaryMeasure),
        };
    });
    /** A column's role when it holds no Entity Type: Time, Measure (a number column only), Category or Detail. */
    protected roleOptions(type: string): PickerOption[] {
        return (['temporal', 'measure', 'dimension', 'detail'] as const)
            .filter((r) => r !== 'measure' || MEASURE_TYPES.has(type))
            .map((r) => ({ value: r, label: ROLE_LABELS[r], hint: ROLE_HINTS[r] }));
    }
    protected readonly primaryOptions = computed<PickerOption[]>(() => {
        const held = [
            ...new Set(
                this.keptColumns()
                    .map((c) => c.entityType)
                    .filter(Boolean),
            ),
        ];
        const labels = new Map(this.entityOptions().map((o) => [o.value, o.label]));
        return [{ value: NONE, label: 'None' }, ...held.map((v) => ({ value: v, label: labels.get(v) ?? v }))];
    });
    protected readonly withEntityTypes = computed(() => this.whatColumnsHold() === 'ask' || this.whatOpen());

    /** Low-confidence choices still waiting for a click (a changed value counts as the person's own choice). */
    protected readonly pending = computed<PendingChoice[]>(() => {
        const d = this.draft();
        if (!d) return [];
        this.formTick();
        const done = this.confirmed();
        return lowChoices(d, this.withEntityTypes(), this.dropped()).filter(
            (p) =>
                !done.has(p.key) &&
                !this.changedFromProposal(d, p.key) &&
                // a role another record type now proposes surely needs no click
                !(p.key.startsWith('role.') && this.roleChoice(p.key.slice(5))?.confidence === 'high'),
        );
    });
    protected readonly pendingKeys = computed(() => new Set(this.pending().map((p) => p.key)));

    /** The preview of the first rows as they will be kept (renamed, left-out columns gone). */
    protected readonly previewColumns = computed<ColDef[]>(() =>
        this.keptColumns().map((c) => ({ field: c.name, headerName: c.rename.trim() || c.name })),
    );
    protected readonly previewRows = computed(() => this.draft()?.rows ?? []);

    protected readonly linkMissing = computed(() => {
        this.formTick();
        const v = this.form.getRawValue();
        return this.draft()?.kind === 'stream' && (!v.from || !v.to || v.from === v.to);
    });
    protected readonly nameError = computed(() => {
        this.formTick();
        const c = this.form.controls.name;
        if (!c.touched || c.valid) return '';
        return c.hasError('required')
            ? 'Give the Dataset a name.'
            : 'Lower-case letters, digits and _, starting with a letter (at most 63).';
    });
    protected readonly renameErrors = computed(() => {
        this.formTick();
        const seen = new Map<string, number>();
        for (const c of this.keptColumns()) {
            const n = (c.rename.trim() || c.name).toLowerCase();
            seen.set(n, (seen.get(n) ?? 0) + 1);
        }
        const out = new Map<string, string>();
        for (const c of this.keptColumns()) {
            const n = c.rename.trim() || c.name;
            if (!COLUMN_NAME.test(n)) out.set(c.name, 'Letters, digits and _ only.');
            else if ((seen.get(n.toLowerCase()) ?? 0) > 1) out.set(c.name, 'Two columns would have this name.');
        }
        return out;
    });
    protected readonly everyColumnDropped = computed(() => !!this.draft() && this.keptColumns().length === 0);
    protected readonly canCreate = computed(
        () =>
            !!this.draft() &&
            !this.running() &&
            !this.reading() &&
            this.pending().length === 0 &&
            this.renameErrors().size === 0 &&
            !this.everyColumnDropped(),
    );
    /** The one sentence above Create: what will happen. */
    protected readonly createLine = computed(() => {
        const d = this.draft();
        if (!d) return '';
        this.formTick();
        const name = this.form.controls.name.value || '…';
        const what = d.kind === 'reference' ? 'details about entities' : 'events between entities';
        const followOn = followOnClause(d.followOns, d.kind === 'stream' && !this.linkMissing());
        return `Creates the Dataset ${name} (${what}) from ${d.file.name}, ${count(d.file.rows)} rows; runs it once${followOn}.`;
    });
    protected readonly resultDataset = computed<ImportDataset | null>(() => {
        const s = this.summary();
        if (!s) return null;
        return {
            id: s.dataset,
            kind: s.kind,
            importPipeline: s.pipeline,
            description: this.form.controls.description.value || undefined,
            primaryEntityType: this.form.controls.primary.value || null,
        };
    });

    ngOnInit(): void {
        this.kind.setValue(this.initialKind());
        this.whatOpen.set(this.whatColumnsHold() === 'ask');
        this.access.load();
        // OB-13: leaving with a file chosen but no Dataset made counts the stage where the person stopped.
        this.destroyRef.onDestroy(() => {
            if (this.summary()) return;
            if (this.running() || this.following()) this.events.stepAbandoned('add_data:create');
            else if (this.draft()) this.events.stepAbandoned('add_data:columns');
            else if (this.file()) this.events.stepAbandoned('add_data:file');
        });
        this.entityProfiles
            .entityTypes()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (l) => {
                    this.typeList.set({ types: l.items ?? [], families: l.families ?? [] });
                    this.recordTypes.set(l.recordTypes ?? []);
                },
                error: () => {
                    this.typeList.set({ types: [], families: [] });
                    this.recordTypes.set([]);
                },
            });
        this.formSub = this.form.valueChanges.subscribe(() => this.formTick.update((n) => n + 1));
        this.formSub.add(this.form.controls.recordType.valueChanges.subscribe((rt) => this.reproposeRoles(rt)));
        // A different separator, header line or quote re-reads the file (the draft's columns were read with the old one).
        for (const c of [this.form.controls.delimiter, this.form.controls.header, this.form.controls.quote])
            this.formSub.add(c.valueChanges.subscribe(() => this.reformat()));
        this.destroyRef.onDestroy(() => this.formSub?.unsubscribe());
    }

    // ── 1. the file ──────────────────────────────────────────────────────────────────────────────────────────────────

    protected onChoose(event: Event): void {
        const el = event.target as HTMLInputElement;
        const f = el.files?.[0] ?? null;
        el.value = '';
        if (f) this.read(f);
    }

    protected onDragOver(event: DragEvent): void {
        event.preventDefault();
        this.dragging.set(true);
    }

    protected onDrop(event: DragEvent): void {
        event.preventDefault();
        this.dragging.set(false);
        const f = event.dataTransfer?.files?.[0];
        if (f) this.read(f);
    }

    /** Send the file and read it back as a draft (also the re-read after a format change). */
    read(f: File, format: FormatOverrides = {}): void {
        const problem = fileProblem(f, this.maxBytes());
        this.error.set(problem);
        this.unavailable.set('');
        if (problem) return;
        this.file.set(f);
        this.reading.set(true);
        this.onboard
            .draft(f, this.kind.value, format)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (d) => {
                    this.reading.set(false);
                    this.adopt(d);
                },
                error: (err: unknown) => {
                    this.reading.set(false);
                    this.refused(err, 'The file could not be read.');
                    if (/save .* as CSV/i.test(this.error())) this.excel.set(false);
                    // A failed re-read keeps the draft it had: its format choices go back to what that draft was read with.
                    const kept = this.draft();
                    if (kept)
                        this.form.patchValue(
                            {
                                delimiter: kept.format.delimiter?.value ?? '',
                                header: String(kept.format.header?.value ?? true),
                                quote: kept.format.quote?.value ?? '',
                            },
                            { emitEvent: false },
                        );
                },
            });
    }

    private adopt(d: OnboardDraft): void {
        this.draft.set(d);
        this.excel.set(d.limits.excel);
        this.confirmed.set(new Set());
        const fmt = d.format;
        this.form.controls.columns.clear({ emitEvent: false });
        for (const c of initialColumns(d)) this.form.controls.columns.push(this.columnGroup(c), { emitEvent: false });
        const roles = initialRoles(d);
        this.rolesUnder = roles.recordType ?? NONE;
        this.form.patchValue(
            {
                name: this.form.controls.name.value || suggestDatasetName(d.file.name),
                delimiter: fmt.delimiter?.value ?? '',
                header: String(fmt.header?.value ?? true),
                quote: fmt.quote?.value ?? '',
                ...roles,
            },
            { emitEvent: false },
        );
        this.formTick.update((n) => n + 1);
    }

    private columnGroup(c: ColumnState): ColumnGroup {
        return this.fb.nonNullable.group({
            name: [c.name],
            rename: [c.rename],
            type: [c.type],
            drop: [c.drop],
            entityType: [c.entityType],
            role: [c.role],
        });
    }

    /** Choose another file: back to step 1 with nothing kept. */
    protected startOver(kind: ImportKind = this.kind.value): void {
        this.draft.set(null);
        this.file.set(null);
        this.summary.set(null);
        this.error.set('');
        this.unavailable.set('');
        this.confirmed.set(new Set());
        this.form.controls.columns.clear({ emitEvent: false });
        this.form.reset(undefined, { emitEvent: false });
        this.formTick.update((n) => n + 1);
        this.kind.setValue(kind);
        this.whatOpen.set(this.whatColumnsHold() === 'ask');
    }

    // ── 2. the columns ───────────────────────────────────────────────────────────────────────────────────────────────

    /** A separator, header or quote change re-reads the file with that choice stated (the commit cannot change it). */
    protected reformat(): void {
        const f = this.file();
        const d = this.draft();
        if (!f || !d || this.reading()) return;
        const v = this.form.getRawValue();
        this.read(f, { delimiter: v.delimiter, header: v.header === 'true', quote: v.quote });
    }

    protected confirm(key: string): void {
        this.confirmed.update((s) => new Set(s).add(key));
    }

    protected isPending(key: string): boolean {
        return this.pendingKeys().has(key);
    }

    /** The row classes of a choice that needs a click: the warning wash and edge (paired with words, never colour alone). */
    protected lowClasses(key: string): string {
        return this.isPending(key) ? `${statusRowClasses('warning')} ${statusEdgeClasses('warning')}` : '';
    }

    protected entityChoice(column: string): Choice<string | null> | undefined {
        return this.draft()?.entityTypes.columns.find((c) => c.column === column)?.entityType;
    }

    /** The role proposed for a column under the record type now chosen (ENTITY-TYPE-RECORD-ROLES-1, `roleUnder`). */
    protected roleChoice(column: string): Choice<ColumnRole> | undefined {
        const d = this.draft();
        return d ? roleUnder(d, column, this.form.controls.recordType.value, this.recordTypes()) : undefined;
    }

    /** The record type the role controls were last proposed under. */
    private rolesUnder = NONE;

    /**
     * Another record type picked: each column whose role still is the proposal under the old one takes the proposal under
     * the new one; a role the person picked stays.
     */
    private reproposeRoles(recordType: string): void {
        const d = this.draft();
        const was = this.rolesUnder;
        this.rolesUnder = recordType;
        if (!d || was === recordType) return;
        for (const g of this.form.controls.columns.controls) {
            const n = g.controls.name.value;
            const before = roleUnder(d, n, was, this.recordTypes())?.value;
            const after = roleUnder(d, n, recordType, this.recordTypes())?.value;
            if (before && after && after !== before && g.controls.role.value === before)
                g.controls.role.setValue(after);
        }
    }

    protected typeChoice(column: string): Choice<string> | undefined {
        return this.draft()?.columns.find((c) => c.name === column)?.type;
    }

    protected sample(column: string): string {
        const s = this.draft()?.columns.find((c) => c.name === column)?.sample ?? [];
        return s.map((v) => String(v ?? '')).join(', ');
    }

    protected readonly key = choiceKey;
    protected readonly limitsLine = limitsLine;
    /** The optional link roles, in screen order. */
    protected readonly linkRoles = [
        { id: 'kind', label: 'Kind of link' },
        { id: 'primaryTime', label: 'When (a Time column)' },
        { id: 'primaryMeasure', label: 'Weighed by (a Measure column)' },
    ] as const;
    protected readonly formatSize = formatSize;
    protected readonly count = count;

    // ── 4. create and run ────────────────────────────────────────────────────────────────────────────────────────────

    create(): void {
        const d = this.draft();
        this.form.controls.name.markAsTouched();
        this.formTick.update((n) => n + 1);
        if (!d || !this.canCreate() || this.form.controls.name.invalid) return;
        const v = this.form.getRawValue();
        const roles: RolesState = {
            primary: v.primary,
            from: v.from,
            to: v.to,
            kind: v.kind,
            primaryTime: v.primaryTime,
            primaryMeasure: v.primaryMeasure,
            identifying: v.identifying,
            ...(this.recordTypes().length ? { recordType: v.recordType } : {}),
        };
        const body = buildCommit(d, v.name, v.description, v.columns, roles, {
            withEntityTypes: this.withEntityTypes(),
        });
        this.running.set(true);
        this.following.set(false);
        this.error.set('');
        this.onboard
            .commit(d.draftId, body, () => this.following.set(true))
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => {
                    this.running.set(false);
                    this.summary.set(s);
                    this.imported.emit(s);
                },
                error: (err: unknown) => {
                    this.running.set(false);
                    this.refused(err, 'The Dataset was not created.');
                },
            });
    }

    // ── 5. next ──────────────────────────────────────────────────────────────────────────────────────────────────────

    protected explore(): void {
        const s = this.summary();
        if (s) void this.router.navigate([this.exploreRoute()], { queryParams: { dataset: s.dataset } });
    }

    /** *Add details about these entities*: the same screen, for a file that describes them (UC-04). */
    protected addDetails(): void {
        this.startOver('reference');
    }

    protected addAnother(): void {
        this.startOver('stream');
    }

    /** A refusal in plain words; 403 locks the screen with the server's reason, 503 is explained, not an error. */
    private refused(err: unknown, fallback: string): void {
        if (err instanceof ImportRunFailed) {
            this.error.set(`The import did not finish, and nothing was kept: ${err.message}`);
            return;
        }
        const status = err instanceof HttpErrorResponse ? err.status : 0;
        const server = apiErrorMessage(err, fallback);
        if (status === 403 && !/path|bare file name/i.test(server)) {
            this.access.refusedBy(server);
            return;
        }
        if (status === 503) {
            this.unavailable.set(server);
            return;
        }
        if (status === 409) this.error.set(`${server}.`);
        else if (status === 413) this.error.set(`The file is too large: ${server}.`);
        else if (status === 422) this.error.set(`${server}.`);
        else this.error.set(server);
    }

    /** Whether the person already changed a low choice away from the proposal (their own choice needs no confirm). */
    private changedFromProposal(d: OnboardDraft, key: string): boolean {
        const v = this.form.getRawValue();
        const col = (name: string) => v.columns.find((c) => c.name === name);
        if (key.startsWith('type.')) {
            const n = key.slice(5);
            return col(n)?.type !== d.columns.find((c) => c.name === n)?.type.value;
        }
        if (key.startsWith('entity.')) {
            const n = key.slice(7);
            return (col(n)?.entityType || null) !== (this.entityChoice(n)?.value ?? null);
        }
        if (key.startsWith('role.')) {
            const n = key.slice(5);
            return col(n)?.role !== this.roleChoice(n)?.value;
        }
        const l = d.entityTypes.link;
        if (key === choiceKey.link('from')) return v.from !== (l?.from.value ?? '') || v.to !== (l?.to.value ?? '');
        for (const role of LINK_PRIMARIES) if (key === choiceKey.link(role)) return v[role] !== (l?.[role].value ?? '');
        if (key === choiceKey.primary) return v.primary !== (d.entityTypes.primary.value ?? '');
        if (key === choiceKey.identifying) return v.identifying !== (d.entityTypes.identifying?.value ?? '');
        return false;
    }
}
