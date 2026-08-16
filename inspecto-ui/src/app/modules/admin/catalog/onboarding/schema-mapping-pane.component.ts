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
    viewChild,
} from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, LensService, SpacesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import {
    InspectoSchemaFieldsEditorComponent,
    SchemaFieldRow,
    deriveSelector,
    narrowToSchemaType,
    sanitizeIdentifier,
} from 'app/inspecto/schema';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';

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
        MatFormFieldModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoSchemaFieldsEditorComponent,
        DataTableComponent,
    ],
    templateUrl: './schema-mapping-pane.component.html',
})
export class OnboardingSchemaMappingPaneComponent implements OnInit {
    protected readonly lens = inject(LensService);
    protected readonly definition = inject(DefinitionStateService);
    private configApi = inject(ConfigService);
    private spaces = inject(SpacesService);
    private toastr = inject(ToastrService);

    /** The shared grid (P4-2a-i). This pane orchestrates and writes; the grid owns the rows.
     *  A SIGNAL query, not `@ViewChild`: `includedNames` below is computed off it, and a plain
     *  view-child would never re-run that computed when the grid appears. */
    readonly grid = viewChild(InspectoSchemaFieldsEditorComponent);

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

    /** The grid's seed. Held in a signal so the grid rebuilds on a REFERENCE change only — reseeding
     *  it inline would wipe the user's edits mid-type. */
    readonly fieldSeed = signal<SchemaFieldRow[]>([]);
    readonly partitionKeyControl = new FormControl('');

    /** Included field names, mirrored off the grid for the partition-key picker. */
    readonly includedNames = computed(() => this.grid()?.includedNames() ?? []);
    /** Sample-derived types were prefilled (any non-VARCHAR) — surfaces the "suggested" note. */
    readonly typesSuggested = signal(false);

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
            this.grid()?.markPristine();
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
        const dirty = (this.grid()?.form.dirty ?? false) || this.partitionKeyControl.dirty;
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

    /**
     * Seed the grid from the parsed sample, taking the per-column TYPES from the server's inference
     * (`POST /config/suggest/schema`) — D4: one implementation, the same TRY_CAST voting that runs when
     * anything else asks. A SUGGESTION the builder can override; "Validate types" (real TRY_CAST) stays
     * the verdict.
     *
     * <p>⚠ Only the type is taken. The SELECTOR stays client-derived: it is frontend-dependent (position
     * for delimited/fixedwidth, key for json/text_regex) and the server, which sees only rows, has no
     * way to know which. Server types are narrowed to the four this grid offers.
     */
    private deriveFromSample(): void {
        const preview = this.definition.parsePreview();
        if (!preview) return;
        const seed = (types: Map<string, string>) => {
            this.fieldSeed.set(
                preview.columns.map((col, i) => ({
                    include: true,
                    name: sanitizeIdentifier(col, i),
                    selector: deriveSelector(preview.frontend, i, col),
                    type: types.get(col) ?? 'VARCHAR',
                })),
            );
            this.typesSuggested.set([...types.values()].some((t) => t !== 'VARCHAR'));
        };
        this.loading.set(true);
        this.configApi.suggestSchema(preview.rows).subscribe({
            next: (s) => {
                this.loading.set(false);
                seed(new Map(s.fields.map((f) => [f.name, narrowToSchemaType(f.type)])));
            },
            // Inference is advisory: without it every column is text, which the builder can still fix.
            error: () => {
                this.loading.set(false);
                seed(new Map());
            },
        });
    }

    private hydrateFromSchema(config: Record<string, unknown>): void {
        const raw = (config['raw'] ?? {}) as Record<string, unknown>;
        const fields = Array.isArray(raw['fields']) ? (raw['fields'] as Record<string, unknown>[]) : [];
        this.fieldSeed.set(
            fields.map((f) => ({
                include: true,
                name: String(f['name'] ?? ''),
                selector: String(f['selector'] ?? ''),
                type: String(f['type'] ?? 'VARCHAR'),
            })),
        );
        this.partitionKeyControl.setValue(String(config['partitionKey'] ?? ''), { emitEvent: false });
        // A freshly loaded (unedited) resume state is pristine, not dirty. The grid pristines itself
        // on a new seed; only the pane-owned control needs saying.
        this.partitionKeyControl.markAsPristine();
    }

    /**
     * Validated, included rows — a schema draft's `raw.fields[]` + one straight-through
     * `mapping.rules[]` entry each (`SchemaExtractor`'s own shape); `null` on a blocking problem.
     * The grid validates and reveals its own offending row; this pane only reports.
     */
    private buildFields(): { fields: Record<string, string>[]; rules: Record<string, string>[] } | null {
        const grid = this.grid();
        if (!grid || !grid.validate()) {
            const problem = grid?.problem();
            if (problem) this.toastr.warning(problem);
            return null;
        }
        const included = grid.value();
        return {
            fields: included.map((v) => ({ name: v.name, selector: v.selector, type: v.type })),
            rules: included.map((v) => ({ targetColumn: v.name, sourceExpression: v.name })),
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
