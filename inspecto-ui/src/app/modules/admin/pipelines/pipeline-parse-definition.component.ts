import {
    ChangeDetectionStrategy,
    Component,
    HostListener,
    ViewChild,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import {
    AuthoredNode,
    ComponentDef,
    ConfigService,
    ParserDef,
    ParserPreview,
    ParserTablePreview,
    ParserTreeNode,
    ParsersService,
    SpacesService,
    apiErrorMessage,
} from 'app/inspecto/api';
import {
    InspectoSchemaFieldsEditorComponent,
    SchemaFieldRow,
    deriveSelector,
    sanitizeIdentifier,
} from 'app/inspecto/schema';
import {
    GrammarEditorComponent,
    ParsingFrontend,
    grammarContentAsParsingBlock,
    grammarSeedsFrontend,
} from 'app/inspecto/grammar';
import {
    InspectoSegmentsEditorComponent,
    SegmentDraft,
    schemaDraftFor,
    schemaNameFromPath,
    segmentDraftFrom,
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
    'parser.plugin': 'plugin',
};

/**
 * The **Parse definition pane** (definition-surface P3a; fixed width P3b, ASN.1 P3c) — the per-format
 * path of the parse node, re-hosted inside `<inspecto-definition-drawer>` instead of
 * `grammar-editor.dialog`. Renders name/description plus the shared `<inspecto-grammar-editor>`
 * locked to the node's own format: a per-format node's format IS its type (B6), so the picker would
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
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        GrammarEditorComponent,
        InspectoSegmentsEditorComponent,
        InspectoSchemaFieldsEditorComponent,
    ],
    template: `
        <form [formGroup]="form" (ngSubmit)="submit()" class="space-y-1">
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Name</mat-label>
                <input matInput formControlName="name" [placeholder]="node().id" />
            </mat-form-field>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Description</mat-label>
                <input matInput formControlName="description" />
            </mat-form-field>

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
                <button
                    class="ml-auto"
                    mat-stroked-button
                    type="button"
                    (click)="requestSaveAsTemplate()"
                    matTooltip="Store this Grammar as a reusable starting point. It is a copy — this node is unaffected by later edits to it."
                >
                    Save as template&hellip;
                </button>
            </div>
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
                    <!--
                        Output schema (P4-2a-ii). §4b's icon table always listed "+ output schema" for
                        the flat formats; schema_file is the PARSER node's key, so this is its home.
                        Same two-hop write as segments: the toon lands before the node names it.
                    -->
                    @if (authorsSchema()) {
                        <div class="mb-1 mt-3 flex items-center gap-2">
                            <span class="text-xs font-semibold uppercase opacity-70">Output schema</span>
                            @if (schemaLoading()) {
                                <span class="text-secondary text-xs">Loading the saved schema…</span>
                            }
                        </div>
                        @if (schemaSeed().length) {
                            <inspecto-schema-fields-editor [rows]="schemaSeed()" />
                        } @else {
                            <p class="text-secondary m-0 text-sm">
                                Test the parse above — the output schema is derived from the columns it
                                produces, never hand-typed.
                            </p>
                        }
                    } @else if (foreignSchema()) {
                        <p class="text-secondary m-0 mt-3 text-sm">
                            This parser's schema file ({{ existingSchemaFile() }}) doesn't match the editor's naming
                            convention — author it in the pipeline TOON directly. Applying here leaves it untouched.
                        </p>
                    }
                </div>
            </inspecto-grammar-editor>
        </form>
    `,
})
export class PipelineParseDefinitionComponent {
    private fb = inject(FormBuilder);
    private configApi = inject(ConfigService);
    private spaces = inject(SpacesService);
    private parsersApi = inject(ParsersService);

    /** The per-format parse node being defined (identity fixed; config/name/description editable). */
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

    /** The edited node, rebuilt by {@link submit} — the host applies it to the in-memory model. */
    readonly applied = output<AuthoredNode>();
    /** Whether the pane holds edits since creation / the last successful submit. */
    readonly dirtyChange = output<boolean>();
    /**
     * The operator asked to store this Grammar as a reusable template. The pane emits the validated
     * block and nothing else: a `grammar` registry component is a THIRD entity, so writing it is the
     * host's job, not the pane's (P2 pure-pane rule — only a stage's own companion artifact is a pane
     * write). The node is deliberately untouched — a template is a copy, never a binding.
     */
    readonly saveAsTemplate = output<Record<string, unknown>>();

    @ViewChild(GrammarEditorComponent) private editor?: GrammarEditorComponent;
    @ViewChild(InspectoSegmentsEditorComponent) private segmentsEditor?: InspectoSegmentsEditorComponent;
    @ViewChild(InspectoSchemaFieldsEditorComponent) private schemaGrid?: InspectoSchemaFieldsEditorComponent;

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

    readonly existingSchemaFile = computed(() => String(this.node().config?.['schema_file'] ?? '').trim());
    private schemaName(): string {
        return `${this.pipelineName() || this.node().id}_schema`.replace(/[^A-Za-z0-9_]+/g, '_');
    }
    private schemaConventionPath(): string {
        return `${this.base()}/config/${this.schemaName()}.toon`;
    }
    /** A `schema_file` this editor did not write — hand-authored in the TOON; never touched here. */
    readonly foreignSchema = computed(
        () => this.existingSchemaFile() !== '' && this.existingSchemaFile() !== this.schemaConventionPath(),
    );
    /**
     * Whether this node authors an output schema. Segment-authoring nodes (ASN.1 / plugin) carry their
     * schemas per segment instead, and a foreign `schema_file` is left alone — so this is the flat
     * formats, which is exactly what §4b's icon table listed "+ output schema" against.
     */
    readonly authorsSchema = computed(() => !this.authorsSegments() && !this.foreignSchema());

    /** Keep both halves of the discriminated preview: the tree feeds segments, the table feeds schema. */
    onPreviewed(p: ParserPreview): void {
        this.previewTree.set(p.kind === 'tree' ? p.nodes : null);
        if (p.kind !== 'table') return;
        this.previewTable.set(p);
        // ⛔ Never re-derive over a schema read back from disk: the operator's saved names, types and
        // include flags are the truth, and a fresh sample would silently replace them on Apply.
        if (this.schemaHydrated()) return;
        this.schemaSeed.set(
            p.columns.map((col, i) => ({
                include: true,
                name: sanitizeIdentifier(col, i),
                selector: deriveSelector(this.frontend(), i, col),
                type: 'VARCHAR',
            })),
        );
    }

    /** Segment drafts re-hydrated from the node's saved `asn1.segments`, keys AND columns. */
    readonly initialSegments = signal<SegmentDraft[]>([]);
    readonly segmentsLoading = signal(false);
    /** A segment-schema write is in flight — Apply is a server round-trip for an ASN.1 node. */
    readonly writing = signal(false);

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

    readonly form = this.fb.group({
        name: this.fb.control(''),
        description: this.fb.control(''),
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
            this.form.patchValue({ name: n.name ?? '', description: n.description ?? '' });
            this.form.markAsPristine();
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
            this.schemaHydrated.set(false);
            this.previewTable.set(null);
            this.emitDirty();
            this.loadSavedSegments();
            this.loadSavedSchema();
        });
    }

    /**
     * Read this node's saved output schema back, so re-opening a defined parser edits the schema that
     * exists rather than proposing a new one. Only for our own convention path — a hand-authored
     * `schema_file` is reported and left alone (the Onboarding stage's rule, ported).
     */
    private loadSavedSchema(): void {
        if (!this.authorsSchema() || !this.existingSchemaFile()) return;
        this.schemaLoading.set(true);
        this.configApi.read('schema', this.schemaName()).subscribe({
            next: (r) => {
                this.schemaLoading.set(false);
                const raw = (r.config?.['raw'] ?? {}) as Record<string, unknown>;
                const fields = Array.isArray(raw['fields']) ? (raw['fields'] as Record<string, unknown>[]) : [];
                if (!fields.length) return;
                this.schemaSeed.set(
                    fields.map((f) => ({
                        include: true,
                        name: String(f['name'] ?? ''),
                        selector: String(f['selector'] ?? ''),
                        type: String(f['type'] ?? 'VARCHAR'),
                    })),
                );
                this.schemaHydrated.set(true);
            },
            // A 404 is ordinary: the node names a schema whose file was never written. Deriving from
            // the next parse is exactly right there, so leave the seed alone and stay un-hydrated.
            error: () => this.schemaLoading.set(false),
        });
    }

    /** `segment key → schema-toon path`, as stored in the node's `parsing.<asn1|plugin>.segments`. */
    private savedSegmentPaths(): Record<string, unknown> {
        const f = this.frontend();
        const key = f === 'plugin' ? 'plugin' : 'asn1';
        const a = this.parsingBlock()[key] as Record<string, unknown> | undefined;
        const segs = a?.['segments'];
        return segs && typeof segs === 'object' && !Array.isArray(segs) ? (segs as Record<string, unknown>) : {};
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

    private base(): string {
        return this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
    }
    /** One schema toon per segment, named by the Onboarding Parsing stage's convention. */
    private schemaNameFor(segmentKey: string): string {
        const pipeline = this.pipelineName() || this.node().id;
        return `${pipeline}_${segmentKey}`.replace(/[^A-Za-z0-9_]+/g, '_');
    }
    private schemaPathFor(segmentKey: string): string {
        return `${this.base()}/config/${this.schemaNameFor(segmentKey)}.toon`;
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
            this.form.dirty ||
            this.templateDirty ||
            (this.editor?.isDirty() ?? false) ||
            (this.segmentsEditor?.isDirty() ?? false) ||
            (this.schemaGrid?.form.dirty ?? false);
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    /**
     * Validate and hand the block to the host to store as a template. Does NOT mark the pane pristine:
     * saving a template neither consumes the operator's unapplied edits nor persists them to the node,
     * so a dirty pane must stay dirty.
     */
    requestSaveAsTemplate(): void {
        if (!this.editor?.validate() || this.asn1Unavailable() || this.pluginUnavailable()) return;
        const block = this.parsingValue();
        // A template is a Grammar copy; segments are deployment-specific schema paths, not grammar.
        for (const key of ['asn1', 'plugin'] as const) {
            if (block[key] && typeof block[key] === 'object') {
                const { segments: _deployment, ...grammarOnly } = block[key] as Record<string, unknown>;
                block[key] = grammarOnly;
            }
        }
        this.saveAsTemplate.emit(block);
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
    private submitWithSchema(): void {
        const grid = this.schemaGrid;
        if (!this.authorsSchema() || !grid || !this.schemaSeed().length) return this.applyWith(this.parsingValue());
        if (!grid.validate()) {
            this.editor?.error.set(grid.problem() ?? 'Fix the output schema before applying.');
            return;
        }
        const fields = grid.value();
        const name = this.schemaName();
        const draft = {
            raw: { name, format: 'CSV', fields: fields.map((f) => ({ name: f.name, selector: f.selector, type: f.type })) },
            mapping: {
                canonicalName: name,
                rawName: name,
                rules: fields.map((f) => ({ targetColumn: f.name, sourceExpression: f.name })),
            },
        };
        this.writing.set(true);
        this.configApi.write('schema', draft, { overwrite: true }).subscribe({
            next: () => {
                this.writing.set(false);
                grid.markPristine();
                this.applyWith(this.parsingValue(), this.schemaConventionPath());
            },
            error: (e) => {
                this.writing.set(false);
                // Nothing is applied: a node naming a schema that failed to write is the state this
                // ordering exists to prevent, and the pane stays dirty so the edits survive.
                this.editor?.error.set(apiErrorMessage(e, 'Could not save the output schema.'));
            },
        });
    }

    /** Rebuild the node around the finished block, emit it, and consume the pane's edits. */
    private applyWith(block: Record<string, unknown>, schemaFile?: string): void {
        const v = this.form.getRawValue();
        // `use` is dropped, never carried: this pane's whole contract is that the node owns its
        // Grammar inline. A node opened here BOUND to a `grammar/<id>` component is materialised into
        // an independent copy by Applying (D4) — one place decides that, and it is this one.
        const { use: _unbound, ...n } = this.node();
        const node: AuthoredNode = {
            ...n,
            name: (v.name ?? '').trim() || n.name,
            description: (v.description ?? '').trim() || undefined,
            config: { ...(n.config ?? {}), parsing: block, ...(schemaFile ? { schema_file: schemaFile } : {}) },
        };
        this.form.markAsPristine();
        this.editor?.markPristine();
        this.templateDirty = false; // Apply consumed the pick, same as any other edit
        this.emitDirty();
        this.applied.emit(node);
    }
}
