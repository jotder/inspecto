import {
    ChangeDetectionStrategy,
    Component,
    HostListener,
    OnInit,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { FormArray, FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, LensService, SpacesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { suggestTypes } from 'app/inspecto/grammar';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';

/** The four types `TransformCompiler.direct()` actually TRY_CASTs — everything else is stored as
 *  text (honesty guard: no type offered here implies rigor the engine does not apply). */
const SCHEMA_TYPES = ['VARCHAR', 'DOUBLE', 'DATE', 'TIMESTAMP'] as const;

/** Data-format icon + plain-words hint per type — the Type cell renders these so a 500-column
 *  table scans visually. Only the four honest types exist, so the map is closed. */
const TYPE_META: Record<string, { icon: string; hint: string }> = {
    VARCHAR: { icon: 'heroicons_outline:bars-3-bottom-left', hint: 'Text' },
    DOUBLE: { icon: 'heroicons_outline:hashtag', hint: 'Number (floating point)' },
    DATE: { icon: 'heroicons_outline:calendar', hint: 'Date' },
    TIMESTAMP: { icon: 'heroicons_outline:clock', hint: 'Date & time' },
};

type SortKey = 'source' | 'name' | 'selector' | 'type';

const IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** A parsed column name → a valid SQL identifier (`Identifiers.validate`'s own pattern), so an
 *  auto-derived field name is register-able without hand-editing. */
function sanitizeIdentifier(raw: string, index: number): string {
    let s = raw
        .trim()
        .toUpperCase()
        .replace(/[^A-Z0-9_]+/g, '_');
    s = s.replace(/^_+/, '').replace(/_+$/, '');
    if (/^[0-9]/.test(s)) s = `_${s}`;
    return s || `FIELD_${index}`;
}

/** `raw.fields[].selector` semantics differ by frontend (P2 recon): delimited/fixedwidth address
 *  the parsed column by its 0-based position; json/text_regex address it by the key/group name. */
function deriveSelector(frontend: string, index: number, columnName: string): string {
    return frontend === 'delimited' || frontend === 'fixedwidth' ? String(index) : columnName;
}

interface FieldRow {
    include: boolean;
    name: string;
    selector: string;
    type: string;
}

/**
 * Schema & Mapping stage — authors the legacy `schema` config (`raw.fields[]` + `mapping.rules[]`)
 * a pipeline's `processing.schema_file` points at. Gated on a parsed sample (fields are derived
 * from `ParsingPreview.columns`, not hand-typed); "Validate types" continues the sample thread by
 * TRY_CASTing the SAME parsed rows against the chosen types (`POST /config/preview/schema`) —
 * exactly what production's `DIRECT` mapping would cast, per the four honest types offered.
 *
 * <p>Pure (definition-surface unification D2): it reads the draft from `config` and emits the
 * `processing:` patch to persist on `applied` — the HOST owns the pipeline write and the stage
 * navigation. As in the Parsing stage, the companion artifact of THIS stage (the `<name>_schema`
 * toon) is still written here, and the block pointing at it is emitted only once that write lands:
 * the pipeline must never name a schema file that does not exist yet.
 */
@Component({
    selector: 'app-onboarding-schema-mapping-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatPaginatorModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        DataTableComponent,
    ],
    templateUrl: './schema-mapping-pane.component.html',
})
export class OnboardingSchemaMappingPaneComponent implements OnInit {
    protected readonly lens = inject(LensService);
    protected readonly definition = inject(DefinitionStateService);
    private configApi = inject(ConfigService);
    private spaces = inject(SpacesService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);

    protected readonly types = SCHEMA_TYPES;

    /** The server-held pipeline draft — this stage reads its `processing:` block off it. */
    readonly config = input<Record<string, unknown> | null>(null);
    /** Prose only — a Reference's partition key note differs from a Stream's. */
    readonly kind = input<'stream' | 'reference'>('stream');
    /** Host-owned: a save of the emitted block is in flight. */
    readonly saving = input(false);

    /** The `processing:` patch to persist, once the schema toon it names exists. The host writes it. */
    readonly applied = output<Record<string, unknown>>();
    /** Whether the pane holds edits against the `config` input it was last seeded with. */
    readonly dirtyChange = output<boolean>();
    /** "Go to Parsing" — routing is the host's, not a pane's. */
    readonly jumpToParsing = output<void>();

    private readonly pipelineName = computed(() => String((this.config() ?? {})['name'] ?? ''));
    protected readonly existingSchemaFile = computed(() => {
        const proc = (this.config() ?? {})['processing'];
        const v = proc && typeof proc === 'object' ? (proc as Record<string, unknown>)['schema_file'] : null;
        return String(v ?? '').trim();
    });

    private base(): string {
        return this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
    }
    private schemaName(): string {
        return `${this.pipelineName()}_schema`;
    }
    private conventionPath(): string {
        return `${this.base()}/config/${this.schemaName()}.toon`;
    }

    /** A schema_file set to a path the guided editor did not write — authored in the TOON directly. */
    readonly foreignManaged = computed(
        () => this.existingSchemaFile() !== '' && this.existingSchemaFile() !== this.conventionPath(),
    );
    readonly hasSource = computed(() => !!this.definition.parsePreview() || !!this.existingSchemaFile());
    readonly loading = signal(false);
    /** The pane's OWN in-flight work — the companion schema write that precedes the emit. */
    readonly writing = signal(false);
    /** Anything in flight, either side of the seam — what Save disables on. */
    readonly busy = computed(() => this.writing() || this.saving());
    readonly testing = signal(false);

    readonly fieldsForm: FormGroup = this.fb.group({ fields: this.fb.array<FormGroup>([]) });
    get fieldRows(): FormArray<FormGroup> {
        return this.fieldsForm.controls['fields'] as FormArray<FormGroup>;
    }
    readonly partitionKeyControl = new FormControl('');

    readonly includedNames = signal<string[]>([]);
    /** Sample-derived types were prefilled (any non-VARCHAR) — surfaces the "suggested" note. */
    readonly typesSuggested = signal(false);

    protected readonly typeMeta = TYPE_META;
    typeIcon(t: string | null | undefined): string {
        return (TYPE_META[t ?? ''] ?? TYPE_META['VARCHAR']).icon;
    }

    // ── View window over the FormArray ──────────────────────────────────────────
    // The form stays the single source of truth; these signals only choose WHICH rows render.
    // Deliberately NOT reactive to keystrokes in the name cells — re-sorting/re-filtering while
    // the user types would make rows jump under the caret. The window recomputes on search/
    // filter/sort/page changes and on structural rebuilds (derive/hydrate).
    readonly search = signal('');
    readonly typeFilter = signal<string>('all');
    readonly sortKey = signal<SortKey>('source');
    readonly sortDir = signal<1 | -1>(1);
    readonly pageIndex = signal(0);
    readonly pageSize = signal(50);
    private readonly structureVersion = signal(0);

    readonly totalCount = computed(() => {
        this.structureVersion();
        return this.fieldRows.length;
    });

    readonly filteredEntries = computed<{ group: FormGroup; index: number }[]>(() => {
        this.structureVersion();
        const q = this.search().trim().toUpperCase();
        const tf = this.typeFilter();
        const key = this.sortKey();
        const dir = this.sortDir();
        let entries = this.fieldRows.controls.map((group, index) => ({
            group,
            index,
            v: group.getRawValue() as FieldRow,
        }));
        if (q)
            entries = entries.filter(
                ({ v }) => v.name.toUpperCase().includes(q) || String(v.selector).toUpperCase().includes(q),
            );
        if (tf !== 'all') entries = entries.filter(({ v }) => v.type === tf);
        if (key === 'source') return dir === 1 ? entries : [...entries].reverse();
        return [...entries].sort((a, b) => {
            const av = String(a.v[key] ?? '');
            const bv = String(b.v[key] ?? '');
            const an = Number(av);
            const bn = Number(bv);
            // Numeric when both sides are numbers — delimited selectors are positions, and
            // "10" must not sort before "2".
            const c = av !== '' && bv !== '' && !Number.isNaN(an) && !Number.isNaN(bn) ? an - bn : av.localeCompare(bv);
            return (c !== 0 ? c : a.index - b.index) * dir;
        });
    });

    readonly pagedEntries = computed(() => {
        const start = this.pageIndex() * this.pageSize();
        return this.filteredEntries().slice(start, start + this.pageSize());
    });

    /** Header master-checkbox state over the FILTERED set (not just the visible page). */
    readonly visibleIncludeState = computed<'all' | 'none' | 'some'>(() => {
        this.includedNames(); // any row edit re-evaluates
        const entries = this.filteredEntries();
        if (entries.length === 0) return 'none';
        const on = entries.filter((e) => (e.group.getRawValue() as FieldRow).include).length;
        return on === 0 ? 'none' : on === entries.length ? 'all' : 'some';
    });

    setSearch(q: string): void {
        this.search.set(q);
        this.pageIndex.set(0);
    }

    setTypeFilter(t: string): void {
        this.typeFilter.set(t);
        this.pageIndex.set(0);
    }

    sortBy(key: Exclude<SortKey, 'source'>): void {
        if (this.sortKey() === key) {
            this.sortDir.update((d) => (d === 1 ? -1 : 1));
        } else {
            this.sortKey.set(key);
            this.sortDir.set(1);
        }
        this.pageIndex.set(0);
    }

    ariaSort(key: SortKey): 'ascending' | 'descending' | null {
        if (this.sortKey() !== key) return null;
        return this.sortDir() === 1 ? 'ascending' : 'descending';
    }

    onPage(e: PageEvent): void {
        this.pageIndex.set(e.pageIndex);
        this.pageSize.set(e.pageSize);
    }

    /** Include/exclude every row matching the current search + type filter, across all pages. */
    toggleAllVisible(checked: boolean): void {
        for (const e of this.filteredEntries()) e.group.get('include')?.setValue(checked, { emitEvent: false });
        this.fieldsForm.markAsDirty();
        this.syncIncludedNames();
    }
    readonly rejectedRows = computed<Record<string, unknown>[]>(
        () => this.definition.schemaPreview()?.rejectedRows ?? [],
    );

    private lastDirty = false;

    constructor() {
        /**
         * Re-seeding is how this pane returns to pristine — there is no host→pane method call.
         * A successful save advances the host's config to a NEW object and re-runs this; a FAILED
         * save leaves it identical, so the pane correctly stays dirty and the guard still fires.
         */
        effect(() => {
            this.config();
            this.fieldsForm.markAsPristine();
            this.partitionKeyControl.markAsPristine();
            this.emitDirty();
        });
    }

    /**
     * Dirty is derived on interaction, not streamed — the same contract as the Collection (P2-2) and
     * Parsing (P2-3) panes: re-derive after any input/click inside the pane, report only transitions.
     */
    @HostListener('input')
    @HostListener('click')
    onInteraction(): void {
        this.emitDirty();
    }

    private emitDirty(): void {
        const dirty = this.fieldsForm.dirty || this.partitionKeyControl.dirty;
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    ngOnInit(): void {
        if (this.foreignManaged()) return;
        if (this.existingSchemaFile()) {
            this.loading.set(true);
            this.configApi.read('schema', this.schemaName()).subscribe({
                next: (r) => {
                    this.loading.set(false);
                    this.hydrateFromSchema(r.config);
                },
                error: (e) => {
                    this.loading.set(false);
                    if (e?.status !== 404) this.toastr.warning(apiErrorMessage(e, 'Could not load the saved schema.'));
                    this.deriveFromSample();
                },
            });
        } else {
            this.deriveFromSample();
        }
    }

    private deriveFromSample(): void {
        const preview = this.definition.parsePreview();
        if (!preview) return;
        // Autodetected per-column types over the parsed sample — a SUGGESTION the builder can
        // override; "Validate types" (real TRY_CAST) stays the verdict.
        const suggested = suggestTypes(preview.columns, preview.rows);
        this.fieldRows.clear();
        preview.columns.forEach((col, i) => {
            this.addRow({
                include: true,
                name: sanitizeIdentifier(col, i),
                selector: deriveSelector(preview.frontend, i, col),
                type: suggested[col] ?? 'VARCHAR',
            });
        });
        this.typesSuggested.set(Object.values(suggested).some((t) => t !== 'VARCHAR'));
        this.structureVersion.update((v) => v + 1);
        this.syncIncludedNames();
    }

    private hydrateFromSchema(config: Record<string, unknown>): void {
        const raw = (config['raw'] ?? {}) as Record<string, unknown>;
        const fields = Array.isArray(raw['fields']) ? (raw['fields'] as Record<string, unknown>[]) : [];
        this.fieldRows.clear();
        for (const f of fields) {
            this.addRow({
                include: true,
                name: String(f['name'] ?? ''),
                selector: String(f['selector'] ?? ''),
                type: String(f['type'] ?? 'VARCHAR'),
            });
        }
        this.partitionKeyControl.setValue(String(config['partitionKey'] ?? ''), { emitEvent: false });
        this.structureVersion.update((v) => v + 1);
        this.syncIncludedNames();
        // A freshly loaded (unedited) resume state is pristine, not dirty.
        this.fieldsForm.markAsPristine();
        this.partitionKeyControl.markAsPristine();
    }

    private addRow(row: FieldRow): void {
        const g = this.fb.group({
            include: [row.include],
            name: [row.name, [Validators.required, Validators.pattern(IDENTIFIER_RE)]],
            selector: [{ value: row.selector, disabled: true }],
            type: [row.type],
        });
        g.valueChanges.subscribe(() => this.syncIncludedNames());
        this.fieldRows.push(g);
    }

    private syncIncludedNames(): void {
        this.includedNames.set(
            this.fieldRows.controls
                .map((g) => g.getRawValue() as FieldRow)
                .filter((r) => r.include)
                .map((r) => r.name.trim()),
        );
    }

    /** Clear the view filters and jump to the page holding the given row — with 500 columns a
     *  problem row hidden by a filter or on another page would block Save with nothing visibly
     *  wrong on screen. */
    private revealRow(index: number): void {
        if (index < 0) return;
        this.search.set('');
        this.typeFilter.set('all');
        this.sortKey.set('source');
        this.sortDir.set(1);
        this.pageIndex.set(Math.floor(index / this.pageSize()));
    }

    /** Validated, included rows — a schema draft's `raw.fields[]` + one straight-through
     *  `mapping.rules[]` entry each (`SchemaExtractor`'s own shape); `null` on a blocking problem. */
    private buildFields(): { fields: Record<string, string>[]; rules: Record<string, string>[] } | null {
        if (this.fieldRows.invalid) {
            this.fieldRows.controls.forEach((g) => g.markAllAsTouched());
            const bad = this.fieldRows.controls.findIndex((g) => g.invalid);
            this.revealRow(bad);
            const badName = String(this.fieldRows.at(bad)?.get('name')?.value ?? '').trim();
            this.toastr.warning(
                `Field ${bad + 1}${badName ? ` ("${badName}")` : ''}: names must start with a letter or _ and use only letters, digits, _.`,
            );
            return null;
        }
        const included = this.fieldRows.controls
            .map((g, index) => ({ index, v: g.getRawValue() as FieldRow }))
            .filter((e) => e.v.include);
        if (included.length === 0) {
            this.toastr.warning('Include at least one field.');
            return null;
        }
        const seen = new Set<string>();
        for (const e of included) {
            const n = e.v.name.trim();
            if (seen.has(n)) {
                this.revealRow(e.index);
                this.toastr.warning(`Duplicate field name "${n}" — names must be unique.`);
                return null;
            }
            seen.add(n);
        }
        return {
            fields: included.map(({ v }) => ({ name: v.name.trim(), selector: v.selector, type: v.type })),
            rules: included.map(({ v }) => ({ targetColumn: v.name.trim(), sourceExpression: v.name.trim() })),
        };
    }

    testTypes(): void {
        const preview = this.definition.parsePreview();
        const built = this.buildFields();
        if (!preview || !built) return;
        this.testing.set(true);
        this.definition.schemaError.set(null);
        this.configApi.previewSchema({ raw: { fields: built.fields } }, preview.rows).subscribe({
            next: (p) => {
                this.testing.set(false);
                this.definition.schemaPreview.set(p);
            },
            error: (e) => {
                this.testing.set(false);
                this.definition.schemaPreview.set(null);
                this.definition.schemaError.set(apiErrorMessage(e, 'The sample does not cast with these types.'));
            },
        });
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        const built = this.buildFields();
        if (!built) return;
        const name = this.pipelineName();
        const schemaDraft = {
            partitionKey: this.partitionKeyControl.value?.trim() || undefined,
            raw: { name: this.schemaName(), format: 'CSV', fields: built.fields },
            mapping: { canonicalName: name, rawName: name, rules: built.rules },
        };
        this.writing.set(true);
        this.configApi.write('schema', schemaDraft, { overwrite: true }).subscribe({
            next: () => {
                this.writing.set(false);
                this.applied.emit({ schema_file: this.conventionPath() });
            },
            error: (e) => {
                this.writing.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the schema.'));
            },
        });
    }
}
