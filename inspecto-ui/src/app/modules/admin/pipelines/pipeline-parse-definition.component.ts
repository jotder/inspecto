import {
    ChangeDetectionStrategy,
    Component,
    computed,
    effect,
    HostListener,
    inject,
    input,
    output,
    signal,
    untracked,
    ViewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable, forkJoin, of, throwError } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import {
    AuthoredNode,
    ComponentDef,
    ConfigService,
    ParserDef,
    ParserPreview,
    ParserTablePreview,
    ParserTreeNode,
    SchemaDrift,
    ParsersService,
    apiErrorMessage,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { downloadCsv } from 'app/inspecto/data-table/core/csv';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { InspectoSamplePanelComponent } from 'app/inspecto/definition/sample-panel.component';
import {
    InspectoSchemaFieldsEditorComponent,
    InspectoSchemaMetadataGridComponent,
    InspectoSchemaPartitionsEditorComponent,
    SchemaFieldRow,
    SchemaPartitionRow,
    deriveSelector,
    narrowToSchemaType,
    sanitizeIdentifier,
} from 'app/inspecto/schema';
import {
    GrammarEditorComponent,
    ParsingFrontend,
    grammarContentAsParsingBlock,
    grammarCsvFilename,
    grammarSeedsFrontend,
    grammarToCsv,
    parseGrammarCsv,
    parsingAttributesFor,
} from 'app/inspecto/grammar';
import {
    InspectoSegmentsEditorComponent,
    SegmentDraft,
    schemaDraftFor,
    companionSchemaName,
    portableConfigRef,
    schemaNameFromPath,
    segmentDraftFrom,
    segmentPathsOf,
} from 'app/inspecto/segments';

/**
 * The per-format parse node types the drawer serves → the frontend each one means. This is the ONE
 * list that decides both which parse nodes reach the drawer (the host's `isDrawerParse`) and which
 * format the editor is then locked to, so the two can never disagree.
 *
 * ⛔ A parse type absent here keeps the dialog. `parser` (the generic reader) has no single format to
 * lock, and binary fixed-width — which lifts to `parser.fixedwidth` all the same — carries its layout
 * in `processing.ingester_config`, which this pane cannot author (P3b operator decision).
 *
 * `asn1` (P3c) is not a built-in `ParsingFrontend` — the editor hosts it as the served parser it is,
 * schema-driven off `GET /parsers` — but the node type locks it exactly like the built-ins.
 *
 * `json` / `text_regex` (P3d slice C) close the gap between the plan's six-format icon table and the
 * four node types B6 named: they are ordinary built-ins the shared editor already rendered, so they
 * needed only their entry here plus the node type behind it.
 *
 * `plugin` (P3d slice D) is the generic custom-plugin path: unlike `asn1`, its node type does not name
 * ONE served parser — the pane offers a picker over whichever ingestable plugins the catalog serves,
 * excluding ones already homed by their own entry above (today, just `asn1`).
 */
export const PARSE_NODE_FRONTENDS: Record<string, ParsingFrontend | 'asn1' | 'plugin'> = {
    'parser.delimited': 'delimited',
    'parser.fixedwidth': 'fixedwidth',
    'parser.asn1': 'asn1',
    'parser.json': 'json',
    'parser.text_regex': 'text_regex',
    'parser.xlsx': 'xlsx', // multiformat X4 — read_xlsx via the DuckDB excel extension
    'parser.plugin': 'plugin',
};

/**
 * Does this node type occupy the pipeline's single **parse slot**? The generic `parser` node and every
 * per-format subtype both do — the flat config has one `parsing:` block, so a second one of EITHER
 * spelling is refused at lowering with `MULTI_PARSER`. Callers that reason about "the parse node"
 * (the Load pane's schema context, the palette's add rule) must ask this, not just the subtype map.
 */
export const isParseNodeType = (type: string): boolean => type === 'parser' || type in PARSE_NODE_FRONTENDS;

/**
 * The **Parse definition pane** (definition-surface P3a; fixed width P3b, ASN.1 P3c) — the per-format
 * path of the parse node, re-hosted inside `<inspecto-definition-drawer>` instead of
 * `grammar-editor.dialog`. Renders the shared `<inspecto-grammar-editor>` locked to the node's own
 * format (Name/Description moved to the canvas inspector's rename affordance): a per-format node's format IS its type (B6), so the picker would
 * only author a block the save path refuses with PARSER_FRONTEND_MISMATCH.
 *
 * <p>**One pane serves every per-format subtype**, keyed on {@link frontend}. B6 banned a generic
 * parser NODE TYPE with format tabs — not component reuse; the type still locks the format, so no
 * tabs appear and each format stays its own palette entry. A second copy of this file would only
 * drift.
 *
 * <p>**Pure** (D2), with ONE write of its own: {@link submit} rebuilds the node with the Grammar in
 * its inline `parsing:` home and emits it — the host patches its in-memory model and the toolbar Save
 * persists. The exception is an ASN.1 node's **segment schemas**, which this pane writes itself before
 * emitting a block that references them: P2's rule is that a stage's OWN companion artifact stays a
 * pane write (the reusable `grammar` component is a THIRD entity, which is why THAT one is emitted to
 * the host instead). ⚠ Applying therefore hits the server even though the node change is still only
 * in-memory — the same ordering, and the same orphan-on-discard tradeoff, as Onboarding's `savePlugin`.
 * Grammar-BOUND
 * nodes (`use: grammar/<id>`) do not reach this pane: updating a reusable component is its own write
 * route, which the dialog still owns — the host routes those to `grammar-editor.dialog`.
 *
 * <p>Discard is host-owned: the host recreates this component from the model (the drawer epoch), the
 * same contract as the Collection pane.
 */
@Component({
    selector: 'app-pipeline-parse-definition',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        ChipComponent,
        GrammarEditorComponent,
        InspectoAlertComponent,
        InspectoSamplePanelComponent,
        InspectoSegmentsEditorComponent,
        InspectoSchemaFieldsEditorComponent,
        InspectoSchemaMetadataGridComponent,
        InspectoSchemaPartitionsEditorComponent,
    ],
    template: `
        <!--
            The sample thread (§4.3), mounted where the sample is CONSUMED — choose the file, see it,
            then pick a format and options below. One thread per editor tab; the host owns it, so
            switching tabs never shows another pipeline's sample.
        -->
        @if (sample(); as thread) {
            <div class="mb-3">
                <!--
                    S4: the parse verb lives HERE, beside the chips it changes ("not parsed yet" →
                    "parsed · N cols"), not below the option tabs where its only feedback landed
                    silently on another tab. The shared editor hides its own button when the host owns
                    the sample, so there is exactly one.
                    (no backticks in this comment: it lives inside a template literal)
                -->
                <inspecto-sample-panel
                    [state]="thread"
                    parseLabel="Parse sample"
                    [parseDisabled]="!thread.sample()"
                    (parse)="parseSample()"
                />
            </div>
        }
        <!-- Name/Description live on the canvas inspector's rename affordance now, not here: this
             pane defines the Grammar, and the node's display identity is a graph concern. -->
        <div class="space-y-1">
            <div class="mb-1 mt-2 flex items-center gap-2">
                <span class="text-xs font-semibold uppercase opacity-70">Grammar</span>
                @if (seedableTemplates().length) {
                    <mat-form-field class="w-56" subscriptSizing="dynamic">
                        <mat-label>Start from a template</mat-label>
                        <mat-select [value]="pickedTemplate()" (selectionChange)="applyTemplate($event.value)">
                            @for (t of seedableTemplates(); track t.name) {
                                <mat-option [value]="t.name">{{ t.name }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                }
                <!-- CSV export/import replaced "Save as template…" (U4): the file IS the portable
                     template — options AND the columns table, Excel-editable, diff-friendly. -->
                <span class="ml-auto"></span>
                @if (csvCapable()) {
                    <button
                        mat-icon-button
                        type="button"
                        aria-label="Import Grammar from CSV"
                        matTooltip="Import a Grammar CSV — known options and columns repopulate this surface"
                        (click)="csvInput.click()"
                    >
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:arrow-up-tray"></mat-icon>
                    </button>
                    <input
                        #csvInput
                        type="file"
                        accept=".csv,text/csv"
                        class="hidden"
                        aria-label="Import Grammar from CSV"
                        (change)="importCsv($event)"
                    />
                    <button
                        mat-icon-button
                        type="button"
                        aria-label="Export Grammar as CSV"
                        matTooltip="Export the whole property set as <pipeline>_parser.csv"
                        (click)="exportCsv()"
                    >
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:arrow-down-tray"></mat-icon>
                    </button>
                }
            </div>
            @if (importWarning(); as warn) {
                <inspecto-alert class="mb-2 block" variant="warning" title="Imported with unknown options">
                    {{ warn }}
                </inspecto-alert>
            }
            <!--
                The generic plugin's own picker (P3d slice D). Unlike every other subtype, this node's
                TYPE does not name one served parser — it spans whichever ingestable, non-dedicated
                plugin the catalog serves, so the pane offers its own selector rather than unlocking the
                shared editor's built-in toggle (which would also expose the four built-in formats and
                every preview-only plugin).
            -->
            @if (frontend() === 'plugin') {
                <mat-form-field class="mb-2 w-full" subscriptSizing="dynamic">
                    <mat-label>Parser plugin</mat-label>
                    <mat-select [value]="plugin()?.id ?? null" (selectionChange)="pickPlugin($event.value)">
                        @for (p of pluginChoices(); track p.id) {
                            <mat-option [value]="p.id">{{ p.label }}</mat-option>
                        }
                    </mat-select>
                    @if (!pluginChoices().length) {
                        <mat-hint>No ingestable plugin is deployed on this server.</mat-hint>
                    }
                </mat-form-field>
            }
            <inspecto-grammar-editor
                [initial]="seedBlock()"
                [type]="frontend() === 'plugin' ? pickedPluginId() : frontend()"
                [configuredIngester]="configuredIngesterFqcn()"
                [lockType]="true"
                [sampleMode]="sample() ? 'host' : 'own'"
                [sample]="sample()?.sample()?.text"
                [sampleBytes]="sample()?.sample()?.b64"
                [previewFn]="sample() ? previewFn : undefined"
                (pluginChange)="plugin.set($event)"
                (previewed)="onPreviewed($event)"
                (submitted)="submit()"
            >
                <!--
                    Segments need one schema .toon written per segment BEFORE the block that
                    references them, so the editor stays write-free and this is projected in — the
                    same contract the Onboarding Parsing stage uses. Shown only once the served parser
                    has resolved: without it there is no record tree to derive from.
                -->
                <div grammarExtras>
                    @if (authorsSegments()) {
                        <div class="mb-1 mt-3 flex items-center gap-2">
                            <span class="text-xs font-semibold uppercase opacity-70">Segments</span>
                            @if (segmentsLoading()) {
                                <span class="text-secondary text-xs">Loading the saved segment columns…</span>
                            }
                        </div>
                        <inspecto-segments-editor [tree]="previewTree()" [initial]="initialSegments()" />
                    }
                </div>
                <!--
                    Output schema (P4-2a-ii), re-homed onto the "Types & columns" tab (§4.1 U3) —
                    projected into [tabTypes] so the delimited 4-tab surface shows the columns table
                    inside tab 2; untabbed formats render this below the flat options, as before.
                    Same two-hop write as segments: the toon lands before the node names it.
                -->
                <div tabTypes>
                    @if (authorsSchema()) {
                        <!-- §4.4 Data types: Auto (inferred snapshot, read-only icons) / Declared. -->
                        <div class="mb-1 mt-3 flex flex-wrap items-center gap-3">
                            <span class="text-xs font-semibold uppercase opacity-70">Data types</span>
                            <mat-button-toggle-group
                                [value]="typesMode()"
                                (change)="setTypesMode($event.value)"
                                aria-label="Data types mode"
                            >
                                <mat-button-toggle
                                    value="auto"
                                    matTooltip="Types come from the Test parse's inference; saving records the inferred snapshot"
                                    >Auto</mat-button-toggle
                                >
                                <mat-button-toggle value="declared" matTooltip="Pick each column's type yourself"
                                    >Declared</mat-button-toggle
                                >
                            </mat-button-toggle-group>
                            @if (typesMode() === 'declared' && inferredTypes()) {
                                <button
                                    type="button"
                                    (click)="applySuggestedTypes()"
                                    matTooltip="One-click starting point: set every column to its inferred type — you can still change any of them"
                                >
                                    <inspecto-chip variant="soft" tone="primary">Apply suggested types</inspecto-chip>
                                </button>
                            }
                        </div>
                        <div class="mb-1 mt-1 flex items-center gap-2">
                            <span class="text-xs font-semibold uppercase opacity-70">Output schema</span>
                            @if (schemaLoading()) {
                                <span class="text-secondary text-xs">Loading the saved schema…</span>
                            }
                        </div>
                        @if (schemaDrift(); as d) {
                            @if (d.drifted) {
                                <div
                                    class="mb-2 flex flex-wrap items-center gap-2 text-sm"
                                    role="status"
                                    aria-live="polite"
                                >
                                    <span class="text-secondary">
                                        This sample no longer matches the saved schema:
                                        {{ d.added.length }} new, {{ d.missing.length }} missing,
                                        {{ d.typeChanged.length }} changed type.
                                    </span>
                                    @if (d.added.length) {
                                        <button mat-stroked-button type="button" (click)="addDriftedFields()">
                                            Add {{ d.added.length }} new
                                        </button>
                                    }
                                    <!--
                                        S4/D7 — the third verb of the generate loop. Add-new can only
                                        append, and type changes are deliberately never applied, so a
                                        schema whose sample has genuinely moved on had NO way back to a
                                        derived one short of a CSV import. Destructive by nature, so the
                                        confirm names exactly what it replaces.
                                    -->
                                    <button mat-stroked-button type="button" (click)="rederiveSchema()">
                                        Re-derive from this sample
                                    </button>
                                </div>
                                @if (d.typeChanged.length) {
                                    <p class="text-secondary m-0 mb-2 text-xs">
                                        Type changes are reported, not applied — a saved type may be a deliberate
                                        override. Change them yourself if the sample is right:
                                        @for (t of d.typeChanged; track t.name) {
                                            <span class="font-mono">{{ t.name }}</span> ({{ t.declared }} →
                                            {{ t.suggested }})
                                            @if (!$last) {
                                                <span>, </span>
                                            }
                                        }
                                    </p>
                                }
                            }
                        }
                        @if (schemaSeed().length) {
                            @if (schemaStale()) {
                                <p class="text-warn m-0 mb-2 text-sm" role="status" aria-live="polite">
                                    These columns came from an earlier test — the settings above no longer parse the
                                    sample. Fix them and test again, or Apply keeps the columns shown.
                                </p>
                            }
                            <inspecto-schema-fields-editor
                                [rows]="schemaSeed()"
                                [autoTypes]="typesMode() === 'auto'"
                                [nameBasedSelectors]="
                                    frontend() === 'json' || frontend() === 'text_regex' || frontend() === 'xlsx'
                                "
                            />
                            <!--
                                Operator ask 2026-08-22: the lineage column is real in the WRITTEN rows but
                                is not one of the parsed columns above (it never went through this schema —
                                it is stamped at write time), so a reader of the schema would have no way to
                                know it exists. Read-only: this is not an editable schemaSeed row, so it can
                                never be mistaken for an authored column or land in the saved schema.
                            -->
                            @if (filenameColumnTarget()?.value; as filenameCol) {
                                <p class="text-secondary m-0 mt-2 text-xs">
                                    <span class="font-mono">{{ filenameCol }}</span> is also written to
                                    {{ filenameColumnTarget()!.target }}'s output — a source-filename lineage
                                    column set on the Files & metadata tab, not one of the columns above.
                                </p>
                            }
                            <!--
                                BUILDER-1b. One pipeline has ONE output schema (pipeline_schema), and a save
                                that drops or narrows columns is refused by the BACKWARD gate. Changing a
                                pipeline's parse format legitimately does exactly that, so without this the
                                operator hit a wall with no way forward. compatibility:none is the write
                                route's own documented escape hatch — offered explicitly, never applied
                                automatically, because the gate exists to make the loss a decision.
                            -->
                            @if (schemaReplaceNeeded()) {
                                <inspecto-alert class="mt-3 block" variant="warning">
                                    <p class="m-0 text-sm">
                                        The saved output schema has columns this parse no longer produces, so it was not
                                        overwritten. Replacing it is the right move when you have changed what this
                                        pipeline reads — anything downstream that expects the old columns will need
                                        updating.
                                    </p>
                                    <button
                                        mat-stroked-button
                                        type="button"
                                        class="!text-xs mt-2"
                                        [disabled]="writing()"
                                        (click)="replaceOutputSchema()"
                                    >
                                        Replace the output schema
                                    </button>
                                </inspecto-alert>
                            }
                        } @else {
                            <p class="text-secondary m-0 text-sm">
                                Parse the sample above — the output schema is derived from the columns it produces,
                                never hand-typed.
                            </p>
                        }
                    } @else if (foreignSchema()) {
                        <p class="text-secondary m-0 mt-3 text-sm">
                            This parser's schema file ({{ existingSchemaFile() }}) doesn't match the editor's naming
                            convention — author it in the pipeline TOON directly. Applying here leaves it untouched.
                        </p>
                    }
                </div>
                <!--
                    D1(b): the column-metadata grid — description/unit/classification per column,
                    Catalog-facing (raw.fields[]), first UI for a long-shipped model. Projected
                    into [tabFiles] so the delimited 4-tab surface shows it on tab 4; untabbed
                    formats render it below the flat options. A second VIEW over the columns
                    table's rows (same seed signal, merged by selector at submit), never a second
                    owner.
                -->
                <div tabFiles>
                    <!--
                        Source-filename lineage (operator ask 2026-08-22): output.filename_column
                        lives on the SINK node, not this one — [filenameColumnTarget] is null whenever
                        the host cannot name a single sink unambiguously, so the field simply does not
                        render rather than guess. A blank commit clears the column.
                    -->
                    @if (filenameColumnTarget(); as t) {
                        <div class="mb-3">
                            <span class="text-xs font-semibold uppercase opacity-70"
                                >Source filename column (optional)</span
                            >
                            <mat-form-field class="mt-1 w-full" subscriptSizing="dynamic">
                                <input
                                    matInput
                                    [value]="t.value"
                                    placeholder="src_file"
                                    aria-label="Source filename column"
                                    (change)="onFilenameColumnBlur($any($event.target).value)"
                                />
                            </mat-form-field>
                            @if (filenameColumnError(); as err) {
                                <p class="text-warn m-0 mt-1 text-xs" role="alert">{{ err }}</p>
                            }
                            <p class="text-secondary m-0 mt-1 text-xs">
                                Adds a column of this name to {{ t.target }}'s output, carrying each row's source
                                file. Blank = no column (lineage stays in the ledger only).
                            </p>
                        </div>
                    }
                    <!--
                        Partitioning (operator ask 2026-08-22): the schema toon's partitions[] — how the
                        written output is cut into Hive directories, derived from the schema's own
                        fields. Rendered only where this pane owns the schema toon, because the rows
                        travel through the SAME write as the fields (and reading them back is what
                        stops that write from dropping a hand-authored block, as it silently did).
                    -->
                    @if (authorsSchema() && schemaSeed().length) {
                        <div class="mb-1 mt-3 flex items-center gap-2">
                            <span class="text-xs font-semibold uppercase opacity-70">Partitioning</span>
                        </div>
                        <inspecto-schema-partitions-editor
                            [initial]="partitionSeed()"
                            [fieldNames]="schemaFieldNames()"
                        />
                        <div class="mb-1 mt-3 flex items-center gap-2">
                            <span class="text-xs font-semibold uppercase opacity-70">Column metadata</span>
                        </div>
                        <inspecto-schema-metadata-grid [rows]="schemaSeed()" />
                    }
                </div>
            </inspecto-grammar-editor>
        </div>
    `,
})
export class PipelineParseDefinitionComponent {
    private configApi = inject(ConfigService);
    private parsersApi = inject(ParsersService);
    private confirm = inject(InspectoConfirmService);

    /** The per-format parse node being defined (identity fixed; config editable — name/description carried verbatim). */
    readonly node = input.required<AuthoredNode>();

    /**
     * The format this node's type means — the editor is locked to it and only templates naming it are
     * offered. Derived from the node type rather than passed in, so the host cannot desync the two.
     */
    readonly frontend = computed<ParsingFrontend | 'asn1' | 'plugin'>(
        () => PARSE_NODE_FRONTENDS[this.node().type] ?? 'delimited',
    );
    /**
     * Stored Grammar components offered as starting points. Passed IN rather than fetched: the pane
     * stays pure (P2 — `[node]` in, outputs out, no injected state) and the host already lists them.
     */
    readonly templates = input<ComponentDef[]>([]);

    /**
     * The pipeline this node belongs to — only used to name the segment schema toons, by the SAME
     * `<pipeline>_<segmentKey>` convention the Onboarding Parsing stage uses, so a stream onboarded
     * there and then edited here rewrites its own schemas instead of growing a second set.
     */
    readonly pipelineName = input('');

    /**
     * This tab's sample thread, or null when the host keeps none — in which case the grammar editor
     * falls back to its own sample box and nothing downstream sees the sample, exactly as before.
     * Passed in (never injected) because the thread belongs to the TAB, not to this pane.
     */
    readonly sample = input<DefinitionStateService | null>(null);

    /** The edited node, rebuilt by {@link submit} — the host applies it to the in-memory model. */
    readonly applied = output<AuthoredNode>();
    /** Whether the pane holds edits since creation / the last successful submit. */
    readonly dirtyChange = output<boolean>();

    /**
     * The pipeline's single qualifying output SINK, offered here so `output.filename_column` (source-
     * file lineage) can be set from the parse side (operator ask, 2026-08-22) — the field genuinely
     * lives on a DIFFERENT node than this pane edits, so the host resolves it the same way
     * `enrichmentHost` resolves the Stage-1 output: only when exactly one sink declares `database`.
     * `null` ⇒ ambiguous or no sink yet ⇒ the field does not render (a host passes nothing when the
     * fact is ambiguous, per the enrichment-wiring rule — never guess which sink the operator meant).
     */
    readonly filenameColumnTarget = input<{ value: string; target: string } | null>(null);
    /** Commits straight to the SINK node, bypassing this pane's own Apply/Discard — the same
     *  immediate-write precedent the canvas rename affordance already set for cross-node identity. */
    readonly filenameColumnChange = output<string | null>();
    /** Set only while the last edit failed the identifier pattern — cleared on a valid or blank commit. */
    readonly filenameColumnError = signal<string | null>(null);

    /** Commit (on blur): blank clears the column, an invalid identifier is refused with an inline error
     *  (never `<mat-error>` — this input carries no `NgControl`, so a mat-form-field never enters an
     *  error state for it; the schema-form `list`-type fix set the precedent). */
    onFilenameColumnBlur(raw: string): void {
        const value = raw.trim();
        if (!value) {
            this.filenameColumnError.set(null);
            this.filenameColumnChange.emit(null);
            return;
        }
        if (!/^[A-Za-z][A-Za-z0-9_-]*$/.test(value)) {
            this.filenameColumnError.set('Must start with a letter — letters, digits, _ and - only.');
            return;
        }
        this.filenameColumnError.set(null);
        this.filenameColumnChange.emit(value);
    }
    // U4: the `saveAsTemplate` output is gone — the Grammar CSV is the portable template now
    // (grammar templates are created in the Components registry; "Start from a template" stays).

    @ViewChild(GrammarEditorComponent) private editor?: GrammarEditorComponent;
    @ViewChild(InspectoSegmentsEditorComponent) private segmentsEditor?: InspectoSegmentsEditorComponent;
    @ViewChild(InspectoSchemaFieldsEditorComponent) private schemaGrid?: InspectoSchemaFieldsEditorComponent;
    @ViewChild(InspectoSchemaMetadataGridComponent) private metaGrid?: InspectoSchemaMetadataGridComponent;
    @ViewChild(InspectoSchemaPartitionsEditorComponent) private partitionsEditor?: InspectoSchemaPartitionsEditorComponent;

    /** The stored `partitions[]` of this node's schema toon — seeded by {@link loadSavedSchema}. */
    readonly partitionSeed = signal<SchemaPartitionRow[]>([]);
    /**
     * Every top-level key of the stored schema toon this pane does NOT model, carried verbatim
     * through the rewrite. The write is a whole-file overwrite, so any key not re-emitted is
     * destroyed — the partitions[] data-loss lesson, generalised to unknown/future keys.
     */
    private schemaExtras: Record<string, unknown> = {};
    /** What a partition may derive from: the schema's INCLUDED field names. */
    readonly schemaFieldNames = computed(() =>
        this.schemaSeed()
            .filter((r) => r.include)
            .map((r) => r.name),
    );

    // ── segments (asn1 / plugin) ─────────────────────────────────────────────────

    /**
     * The served parser the editor resolved, mirrored from `(pluginChange)`.
     *
     * ⚠ Mirrored, NOT read through the `@ViewChild` in the template: content projected INTO the editor
     * evaluates in THIS component's context and the query is unresolved on first render, so a template
     * read would decide "no segments editor" before the catalog ever arrived.
     */
    readonly plugin = signal<ParserDef | null>(null);

    // ── the plugin picker (P3d slice D) ──────────────────────────────────────────

    /**
     * The full served catalog, fetched independently of the shared editor's own copy — a stateless
     * read, the same tier as the segment-schema reads this pane already makes via `ConfigService`, not
     * the per-pane STATE service P2 forbids. Fetched separately (rather than reached through a
     * `@ViewChild` into the editor's own signal) so the picker's first paint does not depend on the
     * editor's view-init timing.
     */
    private readonly servedPlugins = signal<ParserDef[] | null>(null);

    /**
     * Ingestable plugins this generic node may pick, excluding every id already homed by its own
     * dedicated entry in {@link PARSE_NODE_FRONTENDS} (today, just `asn1`) — offering one of those here
     * would create a second authoring path to the same subtype.
     */
    readonly pluginChoices = computed<ParserDef[]>(() => {
        if (this.frontend() !== 'plugin') return [];
        const dedicated = new Set<string>(Object.values(PARSE_NODE_FRONTENDS));
        return (this.servedPlugins() ?? []).filter((p) => p.ingestable && p.ingesterClass && !dedicated.has(p.id));
    });

    /** The operator's manual pick, feeding the shared editor's `[type]`. Rehydration on load goes
     *  through `[configuredIngester]` instead, so the two paths never race each other. */
    readonly pickedPluginId = signal<string | null>(null);

    /** The FQCN already authored on this node's `parsing.plugin.ingester`, if any — rehydrates the
     *  editor's selection on load without marking it dirty (mirrors the editor's own contract). */
    readonly configuredIngesterFqcn = computed(() => {
        if (this.frontend() !== 'plugin') return '';
        const p = this.parsingBlock()['plugin'] as Record<string, unknown> | undefined;
        return typeof p?.['ingester'] === 'string' ? (p['ingester'] as string) : '';
    });

    pickPlugin(id: string): void {
        this.pickedPluginId.set(id);
    }

    /** The decoded record forest from the last Test parse — what "Derive from preview" proposes from. */
    readonly previewTree = signal<ParserTreeNode[] | null>(null);

    // ── Output schema (P4-2a-ii) ─────────────────────────────────────────────────
    /** The last FLAT parse preview — the columns an output schema is derived from. */
    readonly previewTable = signal<ParserTablePreview | null>(null);
    /** Rows for the shared grid; a stable reference, since it rebuilds on identity change. */
    readonly schemaSeed = signal<SchemaFieldRow[]>([]);
    readonly schemaLoading = signal(false);
    /** A saved schema was read back, so a fresh parse must NOT re-derive over the operator's edits. */
    private readonly schemaHydrated = signal(false);

    // ── Data types: Auto / Declared (§4.4, U3; D2: Auto is the default for new parse steps) ──
    /** Persisted as `raw.types` on the schema companion; existing schemas without the marker load Declared. */
    readonly typesMode = signal<'auto' | 'declared'>('auto');
    /** The last parse's INFERRED types by column position, narrowed to the grid's vocabulary; null until
     *  a parse returns them (an old server never does — everything then behaves exactly as before). */
    readonly inferredTypes = signal<string[] | null>(null);
    private typesModeTouched = false;

    setTypesMode(mode: 'auto' | 'declared'): void {
        if (mode === this.typesMode()) return;
        this.typesMode.set(mode);
        this.typesModeTouched = true;
        // Entering Auto with a fresh inference re-snapshots the icons; entering Declared keeps
        // whatever is shown (the inferred set stays offered via the chip, never forced).
        if (mode === 'auto') this.applyInferredToGrid();
        this.emitDirty();
    }

    /** Declared mode's one-click, non-destructive starting point (§4.4). */
    applySuggestedTypes(): void {
        this.applyInferredToGrid();
        this.emitDirty();
    }

    /**
     * Stamp the inferred types onto the grid rows, matched by POSITION (delimited selectors are
     * 0-based positions — the same order the sniff's columns come back in). Types only: names,
     * include flags and synonyms are the operator's.
     */
    private applyInferredToGrid(): void {
        const inferred = this.inferredTypes();
        if (!inferred) return;
        const grid = this.schemaGrid;
        if (grid) {
            for (const g of grid.fieldRows.controls) {
                const pos = Number(g.getRawValue()['selector']);
                if (Number.isInteger(pos) && inferred[pos] !== undefined) g.get('type')?.setValue(inferred[pos]);
            }
            grid.form.markAsDirty();
        } else {
            this.schemaSeed.update((rows) =>
                rows.map((r) => {
                    const pos = Number(r.selector);
                    return Number.isInteger(pos) && inferred[pos] !== undefined ? { ...r, type: inferred[pos] } : r;
                }),
            );
        }
    }
    /** How the saved schema differs from what THIS sample suggests (B3) — null until a parse runs. */
    readonly schemaDrift = signal<SchemaDrift | null>(null);

    readonly existingSchemaFile = computed(() => String(this.node().config?.['schema_file'] ?? '').trim());
    private schemaName(): string {
        return companionSchemaName(this.pipelineName() || this.node().id, 'schema');
    }
    /**
     * A `schema_file` this editor did not write — hand-authored in the TOON; never touched here.
     *
     * <p>Compared by NAME, not by path: this pane writes the PORTABLE bare `<name>.toon` since W3,
     * but every config written before that carries `spaces/<space>/config/<name>.toon` and is just
     * as much ours. A path comparison would have declared every pre-W3 pipeline's own schema foreign
     * and quietly stopped maintaining it.
     */
    readonly foreignSchema = computed(
        () => this.existingSchemaFile() !== '' && schemaNameFromPath(this.existingSchemaFile()) !== this.schemaName(),
    );
    /**
     * Whether this node authors an output schema. Segment-authoring nodes (ASN.1 / plugin) carry their
     * schemas per segment instead, and a foreign `schema_file` is left alone — so this is the flat
     * formats, which is exactly what §4b's icon table listed "+ output schema" against.
     */
    readonly authorsSchema = computed(() => !this.authorsSegments() && !this.foreignSchema());

    /**
     * Test parse, with the result mirrored into the tab's sample thread so the strip's chips and every
     * downstream step see it. ⛔ It does NOT change WHERE the parse runs: the stateless
     * `POST /parsers/{id}/preview` the editor already used stays the request — Onboarding routed
     * built-ins through `POST /config/preview/parsing` because it held a server-side pipeline DRAFT to
     * post, and this editor holds a graph, not a config.
     *
     * <p>⚠ It exists at all because the grammar editor's `previewed` output fires on SUCCESS only: a
     * failing re-parse would otherwise leave the previous "parsed · N cols" chip standing over a
     * grammar that no longer parses. The failure path is the reason this is a `previewFn` and not two
     * lines in {@link onPreviewed}.
     *
     * <p>⚠ Only a TABLE result feeds the thread. A tree (ASN.1 / plugin) leaves it untouched rather
     * than clearing it — the thread's parsed hop means "rows a downstream step can cast", and a record
     * tree is not that.
     */
    readonly previewFn = (
        type: string,
        grammar: Record<string, unknown>,
        text: string,
        b64?: string,
    ): Observable<ParserPreview> => {
        const thread = this.sample();
        thread?.parseError.set(null);
        return this.parsersApi.preview(type, grammar, text, b64).pipe(
            tap((p) => {
                if (p.kind !== 'table') return;
                this.schemaStale.set(false); // these columns and these settings agree again
                if (!thread) return;
                thread.parsePreview.set({
                    frontend: type,
                    columns: p.columns,
                    rows: p.rows,
                    rowCount: p.rowCount,
                    rejectedRows: p.rejectedRows,
                });
                // Re-parsing invalidates any cast checked against the old rows.
                thread.schemaPreview.set(null);
                thread.schemaError.set(null);
            }),
            catchError((e) => {
                // The grid still shows the columns of the LAST parse that worked; the settings under it
                // have since stopped parsing. Apply stays available (blocking it is the dead end
                // BUILDER-1a fixed) — but the schema must say it no longer describes these settings.
                if (this.schemaSeed().length) this.schemaStale.set(true);
                thread?.parsePreview.set(null);
                thread?.parseError.set(apiErrorMessage(e, 'The sample does not parse with these settings.'));
                return throwError(() => e);
            }),
        );
    };

    /**
     * The seeded columns outlived the parse that produced them — the last Test parse FAILED. Never
     * blocks anything; it exists so a builder cannot apply columns from a superseded grammar believing
     * the product still stands behind them.
     */
    readonly schemaStale = signal(false);

    /** Keep both halves of the discriminated preview: the tree feeds segments, the table feeds schema. */
    onPreviewed(p: ParserPreview): void {
        this.previewTree.set(p.kind === 'tree' ? p.nodes : null);
        if (p.kind !== 'table') return;
        this.previewTable.set(p);
        // U3: keep the parse's inferred types (narrowed to the grid vocabulary), by position.
        // Backward-compatible: an old server serves no columnTypes and everything reads as before.
        this.inferredTypes.set(p.columnTypes ? p.columnTypes.map((ct) => narrowToSchemaType(ct.type)) : null);
        // ⛔ Never re-derive over a schema read back from disk: the operator's saved names, types and
        // include flags are the truth, and a fresh sample would silently replace them on Apply.
        // Instead, ASK what changed — that is exactly the drift question (B3).
        if (this.schemaHydrated()) {
            // …except the TYPES in Auto mode, which are the sniffer's by definition (§4.4).
            if (this.typesMode() === 'auto') this.applyInferredToGrid();
            return this.checkDrift(p.rows);
        }
        // The derived schema is unapplied work — see {@link parsedSinceApply}. Set HERE and not in
        // `previewFn`, which only runs for a node that has a sample thread, and not on the drift
        // path above, which deliberately re-derives nothing.
        this.parsedSinceApply = true;
        this.emitDirty();
        // S4: make the derivation VISIBLE. It lands on the Types & columns tab, which on a tabbed
        // format is not the tab the operator is looking at — so the first one steers them there.
        if (!this.revealedSchema) {
            this.revealedSchema = true;
            this.editor?.showTab('types');
        }
        const inferred = this.typesMode() === 'auto' ? this.inferredTypes() : null;
        this.schemaSeed.set(
            p.columns.map((col, i) => ({
                include: true,
                name: sanitizeIdentifier(col, i),
                selector: deriveSelector(this.frontend(), i, col),
                type: inferred?.[i] ?? 'VARCHAR',
            })),
        );
    }

    /**
     * S4 — the parse verb, driven from the sample strip. The host already holds the editor's
     * `@ViewChild`, so this is a pass-through rather than a second parse path.
     */
    parseSample(): void {
        this.editor?.test();
    }

    /**
     * Whether the Types tab has already been revealed for this pane instance (S4). The FIRST parse to
     * derive a schema steers the operator to it — the derivation used to land there in silence. Every
     * later parse leaves the tab alone: yanking the view out from under someone who is editing another
     * tab is worse than the silence it would be fixing.
     */
    private revealedSchema = false;

    /** Segment drafts re-hydrated from the node's saved `asn1.segments`, keys AND columns. */
    readonly initialSegments = signal<SegmentDraft[]>([]);
    readonly segmentsLoading = signal(false);
    /** A segment-schema write is in flight — Apply is a server round-trip for an ASN.1 node. */
    readonly writing = signal(false);
    /**
     * The saved `<pipeline>_schema` refused this parse's columns under the BACKWARD gate — the state
     * a builder reaches by changing a pipeline's parse FORMAT (BUILDER-1b). Armed only by that exact
     * refusal, and cleared on every fresh attempt, so a stale banner can never offer a destructive
     * write against a different draft.
     */
    readonly schemaReplaceNeeded = signal(false);

    /**
     * Whether this node authors segments: an ASN.1 or generic-plugin node whose served parser has
     * resolved and can actually load to Tables. A preview-only parser has no ingester to feed, so
     * segments would be an elaborate way to author nothing.
     */
    readonly authorsSegments = computed(() => {
        const f = this.frontend();
        const p = this.plugin();
        return (f === 'asn1' || f === 'plugin') && !!p?.ingestable && !!p.ingesterClass;
    });

    /** The node's inline `parsing:` block, seeding (and re-seeding) the editor. */
    readonly parsingBlock = computed<Record<string, unknown>>(() => {
        const p = this.node().config?.['parsing'];
        return p && typeof p === 'object' && !Array.isArray(p) ? { ...(p as Record<string, unknown>) } : {};
    });

    /** The template the operator started from, if any — its block replaces the node's until Apply. */
    readonly pickedTemplate = signal<string | null>(null);
    private readonly templateBlock = signal<Record<string, unknown> | null>(null);

    /** What the editor is seeded from: a picked template's copy, else the node's own block. */
    readonly seedBlock = computed<Record<string, unknown>>(() => this.templateBlock() ?? this.parsingBlock());

    /**
     * Only Grammars that can seed THIS node's format. A component naming another frontend would author
     * a block the save path refuses with `PARSER_FRONTEND_MISMATCH`, so it is not offered at all.
     */
    readonly seedableTemplates = computed<ComponentDef[]>(() =>
        this.templates().filter((t) => grammarSeedsFrontend(t.content ?? {}, this.frontend())),
    );

    private lastDirty = false;
    /**
     * A picked template is an EDIT, but re-seeding `[initial]` marks the editor pristine — so
     * `editor.isDirty()` reads false right after the pick and the drawer's Apply would stay disabled
     * on a real change. Tracked here instead of inferred from the editor.
     */
    private templateDirty = false;
    /**
     * 🔴 A successful Test parse is an EDIT of the pane's unapplied state: it derives the OUTPUT
     * SCHEMA, and Apply is what writes that schema and names it on the node. None of the other
     * dirty inputs see it — the schema grid is seeded PROGRAMMATICALLY (so its form stays pristine)
     * and the captured sample is not a form at all. Without this, a builder who pasted a sample,
     * ran the parse and got a full derived schema found **Apply greyed out**, and had to hand-edit
     * and blur a grid cell to persist work the product had already done for them (BUILDER-1a,
     * found by driving the real UI 2026-08-17).
     */
    private parsedSinceApply = false;

    constructor() {
        // A stateless catalog fetch, same as the shared editor's own — used only to build the picker's
        // choice list, never to decide what gets applied (that stays `this.plugin()`, mirrored from the
        // editor's own resolution).
        this.parsersApi.list().subscribe({
            next: (list) => this.servedPlugins.set(list),
            error: () => this.servedPlugins.set([]),
        });

        // Seed from the node input. The host recreates this component per node (and on Discard), so
        // this runs once per instance — but an input swap without recreation re-seeds correctly too
        // (the [initial] binding re-seeds the editor, which is what marks it pristine again).
        effect(() => {
            const n = this.node();
            // ⚠ untracked: an effect tracks every signal read synchronously in its body, INCLUDING through
            // the methods it calls — and `loadSavedSchema()` reaches `plugin()` via
            // authorsSchema→authorsSegments. So the seed re-ran when the served parser catalog landed (or
            // when the child editor emitted its plugin pick), reverting typed Name/Description, discarding
            // the chosen Grammar template, clearing derived schema rows and segment drafts, and then
            // calling markAsPristine + emitDirty — Apply greyed out over work just done. The node input is
            // the ONLY thing that should re-seed this pane.
            untracked(() => this.seedFromNode(n));
        });
    }

    /** The one-shot seed body — see the effect above for why it must not be tracked. */
    private seedFromNode(n: AuthoredNode): void {
        {
            // A different node (or a Discard-driven re-seed) makes any template pick stale — the
            // seed must fall back to the node's own block, or the previous node's template lingers.
            this.pickedTemplate.set(null);
            this.templateBlock.set(null);
            this.templateDirty = false;
            // A manual plugin pick is the same kind of stale state — the FQCN carried on THIS node
            // rehydrates instead, through [configuredIngester].
            this.pickedPluginId.set(null);
            this.initialSegments.set([]);
            this.schemaSeed.set([]);
            this.partitionSeed.set([]);
            this.schemaHydrated.set(false);
            this.schemaDrift.set(null);
            this.previewTable.set(null);
            this.emitDirty();
            this.loadSavedSegments();
            this.loadSavedSchema();
        }
    }

    /** The rows the grid currently holds — ALL of them, excluded ones included, so a merge never drops. */
    private currentSchemaRows(): SchemaFieldRow[] {
        const grid = this.schemaGrid;
        if (!grid) return this.schemaSeed();
        return grid.fieldRows.controls.map((g) => g.getRawValue() as SchemaFieldRow);
    }

    /** Rows with the metadata grid's description/unit/classification merged on by selector (D1(b)) —
     *  what persists and exports. The columns table's form does not carry those keys, so skipping the
     *  merge would silently drop a hydrated schema's metadata on Apply. */
    private withMetadata(rows: SchemaFieldRow[]): SchemaFieldRow[] {
        return this.metaGrid ? this.metaGrid.applyTo(rows) : rows;
    }

    /**
     * Ask the server how the saved schema differs from what this sample now votes for (B3). Only for a
     * HYDRATED schema: a freshly-derived one was built from this very sample, so it cannot have drifted.
     */
    private checkDrift(rows: Record<string, unknown>[]): void {
        const fields = this.currentSchemaRows().map((r) => ({ name: r.name, selector: r.selector, type: r.type }));
        if (!fields.length) return;
        this.configApi.suggestSchema(rows, { raw: { fields } }).subscribe({
            next: (s) => this.schemaDrift.set(s.drift ?? null),
            // Drift is advisory: failing to compute it must not disturb an otherwise working pane.
            error: () => this.schemaDrift.set(null),
        });
    }

    /**
     * Append the columns the sample has and the schema does not. ⚠ This is the ONLY half of §5.2's
     * "re-sync" that can be done without clobbering: a type change is NOT auto-applied because nothing
     * distinguishes a deliberate operator override from a stale derivation, and a missing field is not
     * auto-removed because the sample may simply be a narrow one. Both stay reported for a human call.
     */
    addDriftedFields(): void {
        const added = this.schemaDrift()?.added ?? [];
        if (!added.length) return;
        // The reseed rebuilds BOTH grids, so the metadata edits must ride the rows or they vanish.
        const current = this.withMetadata(this.currentSchemaRows());
        const columns = this.previewTable()?.columns ?? [];
        this.schemaSeed.set([
            ...current,
            ...added.map((f) => {
                // The selector must address the column the way this frontend does — by POSITION for
                // delimited/fixedwidth — so it is derived from the sample's own column order.
                const i = columns.indexOf(f.name);
                return {
                    include: true,
                    name: sanitizeIdentifier(f.name, i < 0 ? current.length : i),
                    selector: deriveSelector(this.frontend(), i < 0 ? current.length : i, f.name),
                    type: f.type,
                };
            }),
        ]);
        this.schemaDrift.set(null);
        // ⚠ Adding derived columns IS unapplied work, and nothing else here says so: the drift path
        // returns before `parsedSinceApply` is set, and the shared grid rebuilds itself PRISTINE on the
        // new `schemaSeed` reference — so every term of the dirty expression was false and Apply stayed
        // greyed out over columns the product had just derived. The operator had to hand-edit and blur
        // a cell to keep them.
        this.parsedSinceApply = true;
        this.emitDirty();
    }

    /**
     * S4/D7 — throw the hydrated schema away and derive a fresh one from the sample now on screen.
     *
     * <p>Implementation is deliberately "clear the hydrated flag and re-run the parse", so the derive
     * branch of {@link onPreviewed} is the ONE place a schema is ever derived. A second derivation
     * here would be free to drift from it.
     *
     * <p>Destructive, and the confirm says so in full: names, types, synonyms and the column metadata
     * are all the operator's work, and none of it survives. The CSV import stays the file-based
     * wholesale path — this one is the sample-based one.
     */
    async rederiveSchema(): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            'The saved output schema is replaced by one derived from the sample now captured. Column names, ' +
                'declared types, synonyms and the description / unit / classification metadata are all lost.',
            { title: 'Re-derive the output schema?', confirmText: 'Re-derive' },
        );
        if (!ok) return;
        this.schemaHydrated.set(false);
        this.schemaDrift.set(null);
        this.revealedSchema = false; // the fresh derivation is worth revealing again
        this.parseSample();
    }

    /**
     * Read this node's saved output schema back, so re-opening a defined parser edits the schema that
     * exists rather than proposing a new one. Only for our own convention path — a hand-authored
     * `schema_file` is reported and left alone (the Onboarding stage's rule, ported).
     */
    private loadSavedSchema(): void {
        this.schemaExtras = {}; // a re-seed must not carry a previous node's stored keys
        if (!this.authorsSchema() || !this.existingSchemaFile()) return;
        this.schemaLoading.set(true);
        this.configApi.read('schema', this.schemaName()).subscribe({
            next: (r) => {
                this.schemaLoading.set(false);
                const raw = (r.config?.['raw'] ?? {}) as Record<string, unknown>;
                // Retain the unmodeled top-level keys BEFORE any early return, so a later Apply
                // (whose write replaces the whole file) carries them verbatim. `partitionKey` is
                // modeled too: it is migrated into partitions[] below, so re-emitting it verbatim
                // would resurrect the legacy spelling beside the current one.
                const extras = { ...(r.config ?? {}) } as Record<string, unknown>;
                delete extras['raw'];
                delete extras['mapping'];
                delete extras['partitions'];
                delete extras['partitionKey'];
                this.schemaExtras = extras;
                const fields = Array.isArray(raw['fields']) ? (raw['fields'] as Record<string, unknown>[]) : [];
                if (!fields.length) return;
                this.schemaSeed.set(
                    fields.map((f) => ({
                        include: true,
                        name: String(f['name'] ?? ''),
                        selector: String(f['selector'] ?? ''),
                        type: String(f['type'] ?? 'VARCHAR'),
                        ...(f['synonym'] ? { synonym: String(f['synonym']) } : {}),
                        ...(f['description'] ? { description: String(f['description']) } : {}),
                        ...(f['unit'] ? { unit: String(f['unit']) } : {}),
                        ...(f['classification'] ? { classification: String(f['classification']) } : {}),
                    })),
                );
                // §4.4: the mode is the schema's own `raw.types` marker; a schema without one predates
                // the marker and loads as Declared — its stored types are the operator's.
                this.typesMode.set(raw['types'] === 'auto' ? 'auto' : 'declared');
                // The stored `partitions[]` (top-level, sibling of raw) — until 2026-08-22 the draft
                // this pane writes silently DROPPED it, hand-authored-only as it was. Read → edit →
                // rewrite is the round-trip that closes that hole.
                const partitions = Array.isArray(r.config?.['partitions'])
                    ? (r.config!['partitions'] as Record<string, unknown>[])
                    : [];
                const legacyKey = String(r.config?.['partitionKey'] ?? '').trim();
                this.partitionSeed.set(
                    partitions.length
                        ? partitions.map((p) => ({
                              column: String(p['column'] ?? ''),
                              source: String(p['source'] ?? ''),
                              type: String(p['type'] ?? 'VARCHAR').toUpperCase().replace('-', '_'),
                          }))
                        : legacyKey
                          ? // The legacy `partitionKey:` spelling — surfaced as the trio the engine
                            // synthesises from it (PartitionDef.fromSchema case 2), so a rewrite
                            // carries the same semantics forward in the current spelling.
                            [
                                { column: 'year', source: legacyKey, type: 'DATE_YEAR' },
                                { column: 'month', source: legacyKey, type: 'DATE_MONTH' },
                                { column: 'day', source: legacyKey, type: 'DATE_DAY' },
                            ]
                          : [],
                );
                this.schemaHydrated.set(true);
            },
            // A 404 is ordinary: the node names a schema whose file was never written. Deriving from
            // the next parse is exactly right there, so leave the seed alone and stay un-hydrated.
            error: () => this.schemaLoading.set(false),
        });
    }

    /**
     * `segment key → schema-toon path`, as stored in the node's `parsing.<asn1|plugin>.segments`.
     * Read through the shared {@link segmentPathsOf} so this walk and the delete cascade's cannot
     * drift — a frontend one of them knows and the other doesn't is how schema files get orphaned.
     */
    private savedSegmentPaths(): Record<string, string> {
        return segmentPathsOf({ parsing: this.parsingBlock() });
    }

    /**
     * Read each saved segment's schema toon back, so re-editing an existing pipeline does not force a
     * destructive re-derive. Per-segment and non-fatal: a failed read leaves that segment keys-only.
     * A 404 is expected and silent — a config may legitimately reference a schema never written.
     */
    private loadSavedSegments(): void {
        const paths = this.savedSegmentPaths();
        const keys = Object.keys(paths);
        if (keys.length === 0) return;
        const reads = keys.map((key) => {
            const name = schemaNameFromPath(paths[key]);
            if (!name) return of<SegmentDraft>({ key, columns: [] });
            return this.configApi.read('schema', name).pipe(
                map((r) => segmentDraftFrom(key, r.config)),
                catchError(() => of<SegmentDraft>({ key, columns: [] })),
            );
        });
        this.segmentsLoading.set(true);
        forkJoin(reads).subscribe((drafts) => {
            this.segmentsLoading.set(false);
            // The editor's `initial` setter REBUILDS the FormArray, so a late read landing over edits
            // the operator already made would silently discard them.
            if (this.segmentsEditor?.isDirty()) return;
            this.initialSegments.set(drafts);
        });
    }

    /** One schema toon per segment, named by the Onboarding Parsing stage's convention. */
    private schemaNameFor(segmentKey: string): string {
        return companionSchemaName(this.pipelineName() || this.node().id, segmentKey);
    }
    private schemaPathFor(segmentKey: string): string {
        return portableConfigRef(this.schemaNameFor(segmentKey));
    }

    /**
     * Dirty is derived on interaction, not streamed: the Grammar editor exposes `isDirty()` as a
     * method (no output), so the pane re-derives after any user input/click inside it and reports
     * transitions to the host — which is all the drawer's badge and close-guard need.
     */
    @HostListener('input')
    @HostListener('click')
    onInteraction(): void {
        this.emitDirty();
    }

    /**
     * Start from a stored Grammar: its content is COPIED into the editor. No binding is created and
     * the template is never written back — this node owns the copy from here on.
     *
     * ⚠ The content is normalised first: a legacy flat component (`{delimiter: '|'}`) matches no
     * `delimited__*` spec key, so seeding it raw shows the form's DEFAULTS and silently loses the
     * stored settings.
     */
    applyTemplate(name: string): void {
        const t = this.seedableTemplates().find((x) => x.name === name);
        if (!t) return;
        this.pickedTemplate.set(name);
        this.templateBlock.set(grammarContentAsParsingBlock(t.content ?? {}));
        this.templateDirty = true;
        this.emitDirty();
    }

    private emitDirty(): void {
        const dirty =
            this.templateDirty ||
            this.typesModeTouched ||
            this.parsedSinceApply ||
            (this.editor?.isDirty() ?? false) ||
            (this.segmentsEditor?.isDirty() ?? false) ||
            (this.schemaGrid?.form.dirty ?? false) ||
            (this.metaGrid?.form.dirty ?? false) ||
            (this.partitionsEditor?.form.dirty ?? false);
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    /**
     * Validate and hand the block to the host to store as a template. Does NOT mark the pane pristine:
     * saving a template neither consumes the operator's unapplied edits nor persists them to the node,
     * so a dirty pane must stay dirty.
     */
    // ── Grammar CSV round-trip (§4.5, U4 — the portable template) ────────────────

    /** Only the BUILT-IN frontends round-trip as CSV; a plugin's options are served, not authored. */
    readonly csvCapable = computed(() => {
        const f = this.frontend();
        return f !== 'asn1' && f !== 'plugin';
    });
    /** Unknown option keys the last import listed — shown, never applied (§2's silent-drop trap). */
    readonly importWarning = signal<string | null>(null);

    /** Export the whole property set — options + columns — as `<pipeline>_parser.csv`. */
    exportCsv(): void {
        if (!this.csvCapable() || !this.editor) return;
        const frontend = this.frontend() as ParsingFrontend;
        const csv = grammarToCsv(
            {
                format: frontend,
                pipeline: this.pipelineName() || this.node().id,
                ...(this.authorsSchema() && this.schemaSeed().length ? { types: this.typesMode() } : {}),
            },
            parsingAttributesFor(frontend),
            flattenBlock(this.editor.value()),
            this.withMetadata(this.currentSchemaRows()),
        );
        downloadCsv(grammarCsvFilename(this.pipelineName() || this.node().id), csv);
    }

    /**
     * Import a Grammar CSV: refuse outright on a format mismatch, repopulate the options (the
     * template-pick mechanism, so the editor re-seeds), list unknown keys without applying them, and
     * replace the columns table WHOLESALE — behind a confirm when the current state is dirty.
     */
    async importCsv(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        await this.importCsvText(await file.text());
    }

    /** The import core, file-reading shell removed — what the specs drive. */
    async importCsvText(text: string): Promise<void> {
        if (!this.csvCapable()) return;
        const frontend = this.frontend() as ParsingFrontend;
        let parsed: ReturnType<typeof parseGrammarCsv>;
        try {
            parsed = parseGrammarCsv(text, parsingAttributesFor(frontend));
        } catch (e) {
            this.editor?.error.set(e instanceof Error ? e.message : 'Could not read the file as a Grammar CSV.');
            return;
        }
        if (parsed.meta.format !== frontend) {
            this.editor?.error.set(`That file is a '${parsed.meta.format}' Grammar — this node parses '${frontend}'.`);
            return;
        }
        if (parsed.columns && (this.lastDirty || this.schemaSeed().length)) {
            const ok = await this.confirm.confirm(
                `The file carries ${parsed.columns.length} column(s), which replace the current table wholesale.`,
                'Replace the columns table?',
            );
            if (!ok) return;
        }
        const block = nestKeys(parsed.options);
        block['frontend'] = frontend;
        this.templateBlock.set(block);
        this.templateDirty = true;
        if (parsed.columns) {
            this.schemaSeed.set(parsed.columns);
            this.schemaHydrated.set(false);
        }
        if (parsed.meta.types) this.typesMode.set(parsed.meta.types);
        this.importWarning.set(
            parsed.unknownKeys.length
                ? `Not applied (the engine reads no such options): ${parsed.unknownKeys.join(', ')}`
                : null,
        );
        this.emitDirty();
    }

    /**
     * The asn1 form is SERVED — its fields exist only if `GET /parsers` returned the plugin. When it
     * did not (jar not deployed, catalog fetch failed), the schema form holds no `asn1.*` keys at
     * all, so building the block from it would write an EMPTY grammar over a deployed one and answer
     * "applied". Refuse instead, and say why: an unauthorable pane must not look like a successful save.
     */
    private asn1Unavailable(): boolean {
        if (this.frontend() !== 'asn1' || this.editor?.pluginDef()) return false;
        this.editor?.error.set(
            'The ASN.1 parser is not available from this server, so its grammar cannot be edited here.',
        );
        return true;
    }

    /**
     * The generic-plugin twin of {@link asn1Unavailable}: this node has no single served identity, so
     * "unavailable" covers both nothing picked yet and a served-but-not-ingestable pick (the catalog can
     * change between opens). Either way, Applying would write a hollow `plugin:` block over a deployed
     * one and must refuse rather than look like a successful save.
     */
    private pluginUnavailable(): boolean {
        if (this.frontend() !== 'plugin') return false;
        const p = this.plugin();
        if (p?.ingestable && p.ingesterClass) return false;
        this.editor?.error.set(
            this.pluginChoices().length
                ? 'Pick a parser plugin before applying.'
                : 'No ingestable parser plugin is deployed on this server, so this node cannot be edited here.',
        );
        return true;
    }

    /**
     * The `parsing:` block to persist. A built-in comes from the editor's own {@link
     * GrammarEditorComponent#value} (authored keys + cleared sibling roots + the frontend word). asn1
     * and plugin are SERVED parsers, not built-ins — `value()` would stamp the editor's internal
     * frontend (delimited) — so their blocks are assembled here from the schema-form's keys plus the
     * frontend word the node type means.
     *
     * <p>`segments` come from {@link segmentPaths} when this node authors them; otherwise the node's
     * own are carried VERBATIM — a submit that dropped them would silently turn an ingest-capable
     * config preview-only.
     */
    private parsingValue(segments?: Record<string, string>): Record<string, unknown> {
        const f = this.frontend();
        if (f === 'plugin') {
            const p = this.plugin();
            const g = this.editor!.grammar();
            const prior = this.parsingBlock()['plugin'] as Record<string, unknown> | undefined;
            const block: Record<string, unknown> = { ingester: p?.ingesterClass ?? prior?.['ingester'] };
            if (g['ingester_config'] !== undefined) block['ingester_config'] = g['ingester_config'];
            else if (prior?.['ingester_config'] !== undefined) block['ingester_config'] = prior['ingester_config'];
            if (segments) block['segments'] = segments;
            else if (prior?.['segments'] !== undefined) block['segments'] = prior['segments'];
            return { frontend: 'plugin', plugin: block };
        }
        if (f !== 'asn1') return { ...this.editor!.value(), frontend: f };
        const a = { ...((this.editor!.grammar()['asn1'] as Record<string, unknown> | undefined) ?? {}) };
        const prior = this.parsingBlock()['asn1'] as Record<string, unknown> | undefined;
        if (segments) a['segments'] = segments;
        else if (prior?.['segments'] !== undefined) a['segments'] = prior['segments'];
        return { frontend: 'asn1', asn1: a };
    }

    /** `segment key → schema-toon path` for the drafts currently in the editor. */
    private segmentPaths(drafts: SegmentDraft[]): Record<string, string> {
        const paths: Record<string, string> = {};
        for (const d of drafts) paths[d.key] = this.schemaPathFor(d.key);
        return paths;
    }

    /**
     * Validate, rebuild the node with the Grammar in its inline `parsing:` home, emit. The frontend
     * key is stamped explicitly — it is what makes the file lift back to this node type — and the
     * editor is marked pristine because Apply consumed the edits.
     */
    submit(): void {
        if (!this.editor?.validate() || this.asn1Unavailable() || this.pluginUnavailable()) return;
        if (!this.authorsSegments()) return this.submitWithSchema();

        // Segments: write one schema toon per segment FIRST, then emit a block referencing them. Two
        // hops in this order because the config must never name a schema file that does not exist yet
        // — the Schema stage's rule, and Onboarding's `savePlugin` does exactly the same.
        const segments = this.segmentsEditor;
        if (!segments || !segments.validate()) {
            this.editor.error.set(segments?.problem() ?? 'Add at least one segment.');
            return;
        }
        const drafts = segments.value();
        this.writing.set(true);
        forkJoin(
            drafts.map((d) =>
                this.configApi.write('schema', schemaDraftFor(d, this.schemaNameFor(d.key)), { overwrite: true }),
            ),
        ).subscribe({
            next: () => {
                this.writing.set(false);
                segments.markPristine();
                this.applyWith(this.parsingValue(this.segmentPaths(drafts)));
            },
            error: (e) => {
                this.writing.set(false);
                // Nothing is applied: a node pointing at schemas that failed to write is the state
                // this ordering exists to prevent, and the pane stays dirty so the edits survive.
                this.editor?.error.set(apiErrorMessage(e, 'Could not save the segment schemas.'));
            },
        });
    }

    /**
     * The flat-format path: write the output schema toon FIRST, then emit a node naming it — the same
     * two-hop ordering segments use, for the same reason (the config must never name a file that does
     * not exist yet). A node with no schema grid in play applies straight through, so a parser can
     * still be defined before its schema is.
     */
    private submitWithSchema(replace = false): void {
        const grid = this.schemaGrid;
        // §4.4's "block Auto-save without a parse" guard is deliberately NOT here: with no derived
        // columns nothing is written at all (a parser may be defined before its schema — the
        // BUILDER-1a rule), and once columns exist they always came from a parse. The one residue —
        // an OLD server that serves no columnTypes — saves `types: auto` over VARCHAR, which honestly
        // records everything the sniff was able to say.
        if (!this.authorsSchema() || !grid || !this.schemaSeed().length) return this.applyWith(this.parsingValue());
        if (!grid.validate()) {
            this.editor?.error.set(grid.problem() ?? 'Fix the output schema before applying.');
            return;
        }
        if (this.partitionsEditor && !this.partitionsEditor.validate()) {
            this.editor?.error.set('Every partition segment needs a name (a valid identifier) and a source field.');
            return;
        }
        // The rows travel through this SAME write — before 2026-08-22 the draft carried raw+mapping
        // only, so overwrite:true silently DROPPED a hand-authored partitions[] on every Apply.
        const partitions = this.partitionsEditor?.value() ?? this.partitionSeed();
        const fields = this.withMetadata(grid.value());
        const name = this.schemaName();
        const draft = {
            // Unmodeled stored keys ride along first, so every modeled key below wins.
            ...this.schemaExtras,
            ...(partitions.length ? { partitions } : {}),
            raw: {
                name,
                format: 'CSV',
                // §4.4: the Auto/Declared marker rides the schema companion (additive, ETL-ignored);
                // in Auto the written types ARE the inferred snapshot — declared = inferred by
                // construction, so downstream stays deterministic.
                types: this.typesMode(),
                fields: fields.map((f) => ({
                    name: f.name,
                    selector: f.selector,
                    type: f.type,
                    ...(f.synonym ? { synonym: f.synonym } : {}),
                    ...(f.description ? { description: f.description } : {}),
                    ...(f.unit ? { unit: f.unit } : {}),
                    ...(f.classification ? { classification: f.classification } : {}),
                })),
            },
            mapping: {
                canonicalName: name,
                rawName: name,
                rules: fields.map((f) => ({ targetColumn: f.name, sourceExpression: f.name })),
            },
        };
        this.writing.set(true);
        this.schemaReplaceNeeded.set(false);
        this.configApi
            .write('schema', draft, { overwrite: true, ...(replace ? { compatibility: 'none' as const } : {}) })
            .subscribe({
                next: () => {
                    this.writing.set(false);
                    grid.markPristine();
                    this.metaGrid?.markPristine();
                    this.partitionsEditor?.markPristine();
                    this.applyWith(this.parsingValue(), portableConfigRef(name));
                },
                error: (e) => {
                    this.writing.set(false);
                    // Nothing is applied: a node naming a schema that failed to write is the state this
                    // ordering exists to prevent, and the pane stays dirty so the edits survive.
                    this.editor?.error.set(apiErrorMessage(e, 'Could not save the output schema.'));
                    // …but a BACKWARD refusal is recoverable, so offer the override instead of a dead end.
                    if (isBackwardRefusal(e)) this.schemaReplaceNeeded.set(true);
                },
            });
    }

    /**
     * Operator-confirmed override of the schema BACKWARD gate (BUILDER-1b) — the same write, with the
     * route's `compatibility: 'none'` escape hatch. Only reachable from the banner that a real refusal
     * armed, so this cannot silently replace a schema nobody was warned about.
     */
    replaceOutputSchema(): void {
        this.submitWithSchema(true);
    }

    /** Rebuild the node around the finished block, emit it, and consume the pane's edits. */
    private applyWith(block: Record<string, unknown>, schemaFile?: string): void {
        // `use` is dropped, never carried: this pane's whole contract is that the node owns its
        // Grammar inline. A node opened here BOUND to a `grammar/<id>` component is materialised into
        // an independent copy by Applying (D4) — one place decides that, and it is this one.
        // Name/Description are carried VERBATIM — renaming lives on the canvas inspector.
        const { use: _unbound, ...n } = this.node();
        const node: AuthoredNode = {
            ...n,
            config: { ...(n.config ?? {}), parsing: block, ...(schemaFile ? { schema_file: schemaFile } : {}) },
        };
        this.editor?.markPristine();
        this.templateDirty = false; // Apply consumed the pick, same as any other edit
        this.parsedSinceApply = false; // …and the derived schema it wrote
        this.typesModeTouched = false; // …and the mode switch, now persisted as raw.types
        this.emitDirty();
        this.applied.emit(node);
    }
}

/**
 * The schema BACKWARD save-gate's 422, told apart from every other write failure. Matched on the
 * server's own phrase because the route returns no machine code for it; a wrong match here would
 * offer a destructive "replace" for an unrelated error, so it is deliberately narrow.
 */
function isBackwardRefusal(e: unknown): boolean {
    const err = e as { status?: number; error?: { error?: { message?: string } } };
    if (err?.status !== 422) return false;
    return /BACKWARD-compatible/i.test(err.error?.error?.message ?? '');
}
