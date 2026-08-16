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
    ParserTreeNode,
    SpacesService,
    apiErrorMessage,
} from 'app/inspecto/api';
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
 * `json` / `text_regex` (P3d) close the gap between the plan's six-format icon table and the four node
 * types B6 named: they are ordinary built-ins the shared editor already rendered, so they needed only
 * their entry here plus the node type behind it.
 */
export const PARSE_NODE_FRONTENDS: Record<string, ParsingFrontend | 'asn1'> = {
    'parser.delimited': 'delimited',
    'parser.fixedwidth': 'fixedwidth',
    'parser.asn1': 'asn1',
    'parser.json': 'json',
    'parser.text_regex': 'text_regex',
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
            <inspecto-grammar-editor
                [initial]="seedBlock()"
                [type]="frontend()"
                [lockType]="true"
                (pluginChange)="plugin.set($event)"
                (previewed)="previewTree.set($event.kind === 'tree' ? $event.nodes : null)"
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
            </inspecto-grammar-editor>
        </form>
    `,
})
export class PipelineParseDefinitionComponent {
    private fb = inject(FormBuilder);
    private configApi = inject(ConfigService);
    private spaces = inject(SpacesService);

    /** The per-format parse node being defined (identity fixed; config/name/description editable). */
    readonly node = input.required<AuthoredNode>();

    /**
     * The format this node's type means — the editor is locked to it and only templates naming it are
     * offered. Derived from the node type rather than passed in, so the host cannot desync the two.
     */
    readonly frontend = computed<ParsingFrontend | 'asn1'>(() => PARSE_NODE_FRONTENDS[this.node().type] ?? 'delimited');
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

    // ── segments (asn1) ──────────────────────────────────────────────────────────

    /**
     * The served parser the editor resolved, mirrored from `(pluginChange)`.
     *
     * ⚠ Mirrored, NOT read through the `@ViewChild` in the template: content projected INTO the editor
     * evaluates in THIS component's context and the query is unresolved on first render, so a template
     * read would decide "no segments editor" before the catalog ever arrived.
     */
    readonly plugin = signal<ParserDef | null>(null);

    /** The decoded record forest from the last Test parse — what "Derive from preview" proposes from. */
    readonly previewTree = signal<ParserTreeNode[] | null>(null);

    /** Segment drafts re-hydrated from the node's saved `asn1.segments`, keys AND columns. */
    readonly initialSegments = signal<SegmentDraft[]>([]);
    readonly segmentsLoading = signal(false);
    /** A segment-schema write is in flight — Apply is a server round-trip for an ASN.1 node. */
    readonly writing = signal(false);

    /**
     * Whether this node authors segments: an ASN.1 node whose served parser has resolved and can
     * actually load to Tables. A preview-only parser has no ingester to feed, so segments would be an
     * elaborate way to author nothing.
     */
    readonly authorsSegments = computed(() => {
        const p = this.plugin();
        return this.frontend() === 'asn1' && !!p?.ingestable && !!p.ingesterClass;
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
            this.initialSegments.set([]);
            this.emitDirty();
            this.loadSavedSegments();
        });
    }

    /** `segment key → schema-toon path`, as stored in the node's `parsing.asn1.segments`. */
    private savedSegmentPaths(): Record<string, unknown> {
        const a = this.parsingBlock()['asn1'] as Record<string, unknown> | undefined;
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
            (this.segmentsEditor?.isDirty() ?? false);
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
        if (!this.editor?.validate() || this.asn1Unavailable()) return;
        const block = this.parsingValue();
        // A template is a Grammar copy; segments are deployment-specific schema paths, not grammar.
        if (block['asn1'] && typeof block['asn1'] === 'object') {
            const { segments: _deployment, ...grammarOnly } = block['asn1'] as Record<string, unknown>;
            block['asn1'] = grammarOnly;
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
     * The `parsing:` block to persist. A built-in comes from the editor's own {@link
     * GrammarEditorComponent#value} (authored keys + cleared sibling roots + the frontend word). asn1
     * is a SERVED parser, not a built-in — `value()` would stamp the editor's internal frontend
     * (delimited) — so its block is assembled here from the schema-form's `asn1.*` keys plus the
     * frontend word the node type means.
     *
     * <p>`segments` come from {@link segmentPaths} when this node authors them; otherwise the node's
     * own are carried VERBATIM — a submit that dropped them would silently turn an ingest-capable
     * config preview-only.
     */
    private parsingValue(segments?: Record<string, string>): Record<string, unknown> {
        const f = this.frontend();
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
        if (!this.editor?.validate() || this.asn1Unavailable()) return;
        if (!this.authorsSegments()) return this.applyWith(this.parsingValue());

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

    /** Rebuild the node around the finished block, emit it, and consume the pane's edits. */
    private applyWith(block: Record<string, unknown>): void {
        const v = this.form.getRawValue();
        // `use` is dropped, never carried: this pane's whole contract is that the node owns its
        // Grammar inline. A node opened here BOUND to a `grammar/<id>` component is materialised into
        // an independent copy by Applying (D4) — one place decides that, and it is this one.
        const { use: _unbound, ...n } = this.node();
        const node: AuthoredNode = {
            ...n,
            name: (v.name ?? '').trim() || n.name,
            description: (v.description ?? '').trim() || undefined,
            config: { ...(n.config ?? {}), parsing: block },
        };
        this.form.markAsPristine();
        this.editor?.markPristine();
        this.templateDirty = false; // Apply consumed the pick, same as any other edit
        this.emitDirty();
        this.applied.emit(node);
    }
}
