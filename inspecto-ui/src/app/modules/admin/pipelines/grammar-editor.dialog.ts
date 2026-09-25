import { ChangeDetectionStrategy, Component, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { Observable, catchError, tap, throwError } from 'rxjs';
import {
    AuthoredNode,
    ComponentDef,
    ComponentsService,
    ConfigService,
    ParserDef,
    ParserPreview,
    ParserTablePreview,
    ParsersService,
    STALE_WRITE_MESSAGE,
    apiErrorMessage,
    isStaleVersionError,
} from 'app/inspecto/api';
import { flattenBlock, nestKeys, parseUseRef } from 'app/inspecto/component-model';
import { downloadCsv } from 'app/inspecto/data-table/core/csv';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import {
    GrammarEditorComponent,
    grammarContentAsParsingBlock,
    grammarCsvFilename,
    grammarToCsv,
    parseGrammarCsv,
    parsingAttributesFor,
    ParsingFrontend,
    PARSING_FRONTENDS,
} from 'app/inspecto/grammar';
import { MappingEditorDialog } from 'app/modules/admin/components/mapping-editor.dialog';
import { SchemaEditorData, SchemaEditorDialog } from 'app/modules/admin/components/schema-editor.dialog';
import {
    InspectoOptionPickerComponent,
    PickerOption,
    pickerOptions,
} from 'app/inspecto/components/option-picker.component';
import { companionSchemaName, portableConfigRef } from 'app/inspecto/segments';
import { ParseSchemaWrite, derivedSchemaRows, inferredSchemaTypes, writeParseSchema } from './parse-output-schema';

/**
 * Dialog close payload: the edited node (absent ⇒ the user cancelled). Re-homed here from the retired
 * `node-config.dialog` (S2) — this dialog is the only surface that still returns one.
 */
export interface NodeConfigResult {
    node: AuthoredNode;
}

/** Dialog data: the parse node to configure + its (resolved) type/category labels for the header. */
export interface GrammarEditorDialogData {
    node: AuthoredNode;
    typeLabel: string;
    categoryLabel: string;
    /** The pipeline's own config directory, threaded to the onward Schema editor so a drafted satellite
     *  lands beside its pipeline (`SCHEMA-SATELLITE-SUBDIR-1`). Blank/absent = the write root. */
    configSubdir?: string;
    /** The pipeline's registered identity, threaded to the onward Schema editor so it can show the
     *  DERIVED output schema beside the authored one (`DERIVED-SCHEMA-PANEL-ORPHAN-1`). ⚠ This travels
     *  purely as a pass-through — nothing in THIS dialog reads it. */
    pipeline?: string;
    /**
     * The editor TAB's sample thread — the same {@link DefinitionStateService} the Parse drawer's sample
     * panel and every downstream Step (the Record Transformer's fields) read. The dialog seeds its sample
     * box from it and writes back into it on a successful Test parse and on Save, so ONE sample follows
     * the builder: before this, a sample pasted and test-parsed here was lost on Save, and the drawer's
     * Sample card and the Transformer's field list were empty until it was pasted again.
     */
    sampleThread?: DefinitionStateService | null;
}

/** The name the thread's Sample card shows for a sample captured in this dialog. */
export const GRAMMAR_DIALOG_SAMPLE_NAME = 'sample from Edit Grammar';

/**
 * Edit the **Grammar** a parse node applies — a thin host over the shared
 * `<inspecto-grammar-editor>`, the same surface the Onboarding Parsing stage renders. The dialog owns
 * only what the editor deliberately does not: the dialog shell, the inline-or-reusable choice, and
 * the persistence.
 *
 * <p>**Always inline; templates are copies** (operator decision 2026-08-15): a Grammar lives in the
 * node's own `parsing:` block, full stop. Since U4 (delimited-grammar-properties plan §4.5) the
 * portable template is the **Grammar CSV** — export/import here — while "Start from" keeps offering
 * the existing stored templates (creating one now happens in the Components registry).
 *
 * <p>⚠ This REVERSES the previous store contract, in which the same action MOVED the block into the
 * component and bound the node, making a later template edit reach back into every pipeline using it.
 * The `use: grammar/<id>` form stays **read-supported** (a hand-authored file may use it) but is never
 * authored: opening a bound node and saving MIGRATES it to an independent inline copy rather than
 * writing back to the shared component. **No pipeline-editor surface updates a `grammar` component in
 * place any more** — only the Components registry page does, which is what editing a template in the
 * library means. As built: `docs/okf/backend/pipeline-graph/editable-round-trip.md` and
 * `docs/okf/frontend/features/grammar-config.md`. Provenance only, not maintained:
 * `docs/archived-documents/plans-archive/grammar-templates-not-bindings-plan.md`.
 *
 * <p>**Plugin Grammars are preview-only here.** A plugin parser also needs per-segment schema files,
 * which only the Onboarding Parsing stage can author; rather than write a config the engine would
 * reject at load, this dialog previews the plugin and refuses the save, saying where to go. (Before
 * the unification it saved a `parser_type` key no engine code has ever read.)
 */
@Component({
    selector: 'app-grammar-editor-dialog',
    standalone: true,
    imports: [
        InspectoOptionPickerComponent,
        FormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
        GrammarEditorComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './grammar-editor.dialog.html',
})
export class GrammarEditorDialog {
    private components = inject(ComponentsService);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    private ref = inject(MatDialogRef<GrammarEditorDialog, NodeConfigResult>);
    private dialog = inject(MatDialog);
    private parsers = inject(ParsersService);
    private configApi = inject(ConfigService);
    readonly data = inject<GrammarEditorDialogData>(MAT_DIALOG_DATA);

    /** The thread's sample when the dialog opened — seeds the editor's own sample box. */
    readonly seedSample = this.data.sampleThread?.sample() ?? null;

    /**
     * With a thread, Test parse goes through here so the result lands in the thread exactly as the
     * drawer's `previewFn` does — including the FAILURE arm: a failing re-parse clears the thread's parsed
     * hop, so no downstream Step keeps columns from a Grammar that no longer parses. Only a TABLE result
     * feeds it (a record tree is not rows a downstream Step can cast). Without a thread the editor keeps
     * its stateless default.
     */
    readonly previewFn = this.data.sampleThread
        ? (type: string, grammar: Record<string, unknown>, text: string, b64?: string): Observable<ParserPreview> => {
              const thread = this.data.sampleThread!;
              thread.parseError.set(null);
              return this.parsers.preview(type, grammar, text, b64, this.data.configSubdir?.trim() || undefined).pipe(
                  tap((p) => {
                      if (p.kind !== 'table') return;
                      this.shareSample();
                      thread.parsePreview.set({
                          frontend: type,
                          columns: p.columns,
                          rows: p.rows,
                          rowCount: p.rowCount,
                          rejectedRows: p.rejectedRows,
                          columnTypes: p.columnTypes,
                      });
                      // Re-parsing invalidates any cast checked against the old rows.
                      thread.schemaPreview.set(null);
                      thread.schemaError.set(null);
                  }),
                  catchError((e) => {
                      this.lastTable.set(null); // a Grammar that no longer parses derives no schema
                      thread.parsePreview.set(null);
                      thread.parseError.set(apiErrorMessage(e, 'The sample does not parse with these settings.'));
                      return throwError(() => e);
                  }),
              );
          }
        : undefined;

    @ViewChild(GrammarEditorComponent) private editor?: GrammarEditorComponent;

    /** Existing reusable Grammars (the inline-or-choose options). */
    readonly grammars = signal<ComponentDef[]>([]);
    readonly grammarOptions = computed<PickerOption[]>(() => [
        { value: '', label: "This Step's own Grammar" },
        ...pickerOptions(this.grammars().map((g) => g.name)),
    ]);
    /** The Grammar component this node is bound to; `null` ⇒ the block lives inline on the node. */
    readonly boundGrammarId = signal<string | null>(null);

    /** The `parsing:`-shaped block seeding the editor — from the node, or from the bound component. */
    readonly initialBlock = signal<Record<string, unknown> | undefined>(undefined);
    /** The plugin FQCN the stored block names, so the editor can re-select the served parser. */
    readonly configuredIngester = signal('');

    /**
     * The selected PLUGIN parser, mirrored from `(pluginChange)`. Read from the OUTPUT rather than
     * through a `@ViewChild` in the template — a view query is unresolved on first render.
     */
    readonly plugin = signal<ParserDef | null>(null);
    readonly pluginBlocked = computed(() => this.plugin() !== null);

    /** The last test-parse's table rows, mirrored from `(previewed)` — they arm the Schema
     *  editor's "Suggest from sample" when the user follows the Draft Schema link. */
    readonly previewRows = signal<Record<string, unknown>[]>([]);

    /**
     * The last TABLE parse — what Save derives the output schema from. Seeded from the thread's parse
     * when the dialog opens over one (its sample is the one in the box), so re-saving without re-parsing
     * still creates the schema.
     */
    readonly lastTable = signal<ParserTablePreview | null>(threadTable(this.data.sampleThread));
    /** Save is writing the output schema; the dialog closes only once it lands. */
    readonly writing = signal(false);
    /** Why the output-schema write failed — the dialog stays open so nothing the builder did is lost. */
    readonly saveError = signal<string | null>(null);

    /** The `use: grammar/<id>` this node names but which the registry does not return — see the ctor. */
    readonly missingBinding = signal<string | null>(null);

    /** Unknown option keys the last CSV import listed — shown, never applied (§4.5). */
    readonly importWarning = signal<string | null>(null);
    /** A CSV import re-seeds the editor pristine, so the edit is tracked here (the template-pick idiom). */
    private importedDirty = false;

    /** Esc / backdrop / Cancel all confirm before discarding a dirty Grammar. */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => (this.editor?.isDirty() ?? false) || this.importedDirty,
        this.confirm,
    );

    constructor() {
        const ref = parseUseRef(this.data.node.use);
        const boundId = ref?.kind === 'grammar' ? ref.id : null;
        this.boundGrammarId.set(boundId);
        if (!boundId) this.seedFrom(nodeParsingBlock(this.data.node));

        this.components.list('grammar').subscribe({
            next: (list) => {
                this.grammars.set(list);
                // ⚠ Read the LIVE binding, not the constructor's `boundId` const. While this request was
                // in flight the operator may have picked "This Step's own Grammar", which seeds from the
                // node and clears the binding — the stale const then re-seeded the editor with the old
                // template's block while the dropdown, hint and Save-as-template button all still said
                // the block was the Step's own.
                const live = this.boundGrammarId();
                if (!live) return;
                const bound = list.find((g) => g.name === live);
                if (bound) {
                    this.seedFrom(grammarBlock(bound.content ?? {}));
                    return;
                }
                // The bound Grammar is GONE (deleted/renamed). Seeding nothing left the editor showing
                // delimited defaults, and `closeInline` strips `use:` — so Save silently replaced the
                // authored binding AND its grammar with a default block. Fall back to the node's own
                // parsing block and say so.
                this.missingBinding.set(live);
                this.seedFrom(nodeParsingBlock(this.data.node));
            },
            // Same hole via the error arm: swallowed, the editor stayed unseeded and Save overwrote.
            error: () => {
                this.grammars.set([]);
                if (this.boundGrammarId()) {
                    this.missingBinding.set(this.boundGrammarId());
                    this.seedFrom(nodeParsingBlock(this.data.node));
                }
            },
        });
    }

    private seedFrom(block: Record<string, unknown>): void {
        this.initialBlock.set(block);
        const plugin = block['plugin'];
        const ingester = plugin && typeof plugin === 'object' ? (plugin as Record<string, unknown>)['ingester'] : null;
        this.configuredIngester.set(typeof ingester === 'string' ? ingester : '');
    }

    onPreviewed(p: ParserPreview): void {
        this.previewRows.set(p.kind === 'table' ? p.rows : []);
        this.lastTable.set(p.kind === 'table' ? p : null);
    }

    /** Onward link: author the Schema this parse feeds — seeded with the test-parsed rows, so
     *  "Suggest from sample" is armed. The editor saves itself; nothing here changes the node. */
    openSchemaEditor(): void {
        this.dialog.open(SchemaEditorDialog, {
            // home: 'config' — a pipeline's satellite schema, which a parse node references by bare
            // `<name>.toon`; the registry spelling `schema/<id>` is authored by no shipped surface.
            // ✅ SCHEMA-SATELLITE-SUBDIR-1 (2026-09-13): the pipeline's own directory is threaded through so
            // the draft lands BESIDE its pipeline. It used to land at the write root — the SATELLITE-WRITE-1
            // shape, where the root file wins the read and the drawer edits a schema the engine never loads.
            data: {
                sampleRows: this.previewRows(),
                home: 'config',
                subdir: this.data.configSubdir,
                // DERIVED-SCHEMA-PANEL-ORPHAN-1: only this opener knows a pipeline, so only this one
                // shows the derived schema. The Components pane's registry schemas belong to no
                // pipeline, so the panel stays hidden there rather than rendering an empty state.
                pipeline: this.data.pipeline,
            } satisfies SchemaEditorData,
            width: '1000px',
            maxHeight: '88vh',
        });
    }

    /** Onward link: author the Mapping the typed fields feed. Saves itself, node untouched. */
    openMappingEditor(): void {
        this.dialog.open(MappingEditorDialog, { data: {}, width: '900px', maxHeight: '88vh' });
    }

    /** Choose where the Grammar lives: `''` = inline on this node, or an existing reusable Grammar. */
    onGrammarChange(id: string): void {
        if (!id) {
            this.boundGrammarId.set(null);
            this.seedFrom(nodeParsingBlock(this.data.node));
            return;
        }
        const def = this.grammars().find((g) => g.name === id);
        if (!def) return;
        this.boundGrammarId.set(id);
        this.seedFrom(grammarBlock(def.content ?? {}));
    }

    /** The active FRONTEND the editor is on (plugins have no CSV round-trip — options are served). */
    private activeFrontend(): ParsingFrontend | null {
        if (this.plugin()) return null;
        const f = String(this.editor?.value()['frontend'] ?? 'delimited');
        return PARSING_FRONTENDS.some((x) => x.id === f) ? (f as ParsingFrontend) : null;
    }

    /** §4.5: export the whole property set — the portable template that replaced Save-as-template. */
    exportCsv(): void {
        const frontend = this.activeFrontend();
        if (!frontend || !this.editor) {
            this.toastr.warning('CSV export covers the built-in formats — plugin options are served.');
            return;
        }
        const csv = grammarToCsv(
            { format: frontend, pipeline: this.data.node.name || this.data.node.id },
            parsingAttributesFor(frontend),
            flattenBlock(this.editor.value()),
            [],
        );
        downloadCsv(grammarCsvFilename(this.data.node.name || this.data.node.id), csv);
    }

    /** §4.5: import a Grammar CSV — refuse a format mismatch, apply known options, list unknown keys. */
    async importCsv(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        const frontend = this.activeFrontend();
        if (!file || !frontend) return;
        try {
            const parsed = parseGrammarCsv(await file.text(), parsingAttributesFor(frontend));
            if (parsed.meta.format !== frontend) {
                this.toastr.error(
                    "That file is a '" + parsed.meta.format + "' Grammar — this Step parses '" + frontend + "'.",
                );
                return;
            }
            const block = nestKeys(parsed.options);
            block['frontend'] = frontend;
            this.seedFrom(block);
            this.importedDirty = true;
            this.importWarning.set(
                parsed.unknownKeys.length
                    ? 'Not applied (the engine reads no such options): ' + parsed.unknownKeys.join(', ')
                    : null,
            );
        } catch (e) {
            this.toastr.error(e instanceof Error ? e.message : 'Could not read the file as a Grammar CSV.');
        }
    }

    /**
     * Put the editor's current sample into the tab's thread. A no-op when it is the sample the thread
     * already holds — re-capturing would reset every downstream result for nothing.
     */
    private shareSample(): void {
        const thread = this.data.sampleThread;
        if (!thread || !this.editor) return;
        const text = this.editor.sampleText();
        const b64 = this.editor.sampleB64();
        if (!text && !b64) return;
        const current = thread.sample();
        if (current && current.text === text && (current.b64 ?? null) === b64) return;
        if (b64) thread.captureBinarySample(GRAMMAR_DIALOG_SAMPLE_NAME, b64, text);
        else thread.captureSample(GRAMMAR_DIALOG_SAMPLE_NAME, text);
    }

    /**
     * Save = the Parse drawer's Apply: with a table Test parse and no schema named yet, the output schema
     * is WRITTEN (the same `writeParseSchema`, the same `<pipeline>_schema` file) and the node leaves
     * naming it in `schema_file`. 🔴 Before 2026-09-25 Save patched the node in memory only, so a new
     * Pipeline configured here showed "Schema: Not configured", Activate was refused on Schema, and the
     * drawer's Apply was disabled — no visible way to create the schema. Write first, then close: the
     * node must never name a file that does not exist.
     */
    save(): void {
        if (this.writing() || this.pluginBlocked() || !this.editor?.validate()) return;
        // The sample follows the builder out of the dialog, parsed or not.
        this.shareSample();
        // Always inline — a bound node MIGRATES to an independent copy rather than writing back to the
        // shared component (D4). `closeInline` already drops the `use:`, so both cases are one path.
        const block = this.editor.value();
        const schema = this.outputSchemaWrite(block);
        if (!schema) return this.closeInline(block);
        this.writing.set(true);
        this.saveError.set(null);
        writeParseSchema(this.configApi, schema).subscribe({
            next: () => {
                this.writing.set(false);
                this.closeInline(block, portableConfigRef(schema.name));
            },
            error: (e) => {
                this.writing.set(false);
                this.saveError.set(
                    isStaleVersionError(e)
                        ? STALE_WRITE_MESSAGE
                        : apiErrorMessage(e, 'Could not save the output schema.'),
                );
            },
        });
    }

    /**
     * The output schema Save writes, or null to save the Grammar alone: a plugin/ASN.1 Grammar carries
     * per-segment schemas (authored elsewhere), a node that already names a schema keeps it — the drawer
     * re-reads and never re-derives over a saved one — and without a table parse there is nothing to
     * derive from (a parser may be defined before its schema). Names exactly as the drawer does: the
     * pipeline's registered id is both the file stem and the declared identity.
     */
    private outputSchemaWrite(block: Record<string, unknown>): ParseSchemaWrite | null {
        const table = this.lastTable();
        const frontend = String(block['frontend'] ?? '');
        if (!table || !frontend || frontend === 'asn1' || frontend === 'plugin') return null;
        if (String(this.data.node.config?.['schema_file'] ?? '').trim()) return null;
        const pipeline = this.data.pipeline?.trim() ?? '';
        const name = companionSchemaName(pipeline || this.data.node.id, 'schema');
        const identity = pipeline || name;
        return {
            name,
            subdir: this.data.configSubdir?.trim() || undefined,
            fields: derivedSchemaRows(frontend, table.columns, inferredSchemaTypes(table)),
            rawName: identity,
            canonicalName: identity,
            mappingRawName: identity,
            // D2: Auto is the default for a new parse step — the written types ARE the inferred snapshot.
            typesMode: 'auto',
            partitions: [],
            extras: {},
        };
    }

    /** The default: the block lives on the node itself, and any previous binding is dropped. */
    private closeInline(block: Record<string, unknown>, schemaFile?: string): void {
        const { use: _unbound, ...node } = this.data.node;
        const config = {
            ...(this.data.node.config ?? {}),
            parsing: block,
            ...(schemaFile ? { schema_file: schemaFile } : {}),
        };
        this.ref.close({ node: { ...node, config } });
    }
}

/** The thread's parse as a table preview — what a dialog opened over an already-parsed sample derives from. */
function threadTable(thread: DefinitionStateService | null | undefined): ParserTablePreview | null {
    const p = thread?.parsePreview();
    return p ? { kind: 'table', ...p } : null;
}

/** The node's own `parsing:` block — the inline home a parse node has owned since slice 2. */
function nodeParsingBlock(node: AuthoredNode): Record<string, unknown> {
    const p = node.config?.['parsing'];
    return p && typeof p === 'object' && !Array.isArray(p) ? { ...(p as Record<string, unknown>) } : {};
}

/**
 * A Grammar component's content AS a `parsing:` block — now the shared
 * {@link grammarContentAsParsingBlock}.
 *
 * ⚠ This local version mapped `parser_type` → `frontend` but left a **legacy flat** component's
 * top-level csv settings where they were, so `{delimiter: '|'}` matched no `delimited__*` spec key
 * and the property sheet fell back to its defaults — selecting an existing flat Grammar silently
 * showed (and would have re-saved) `delimiter: ','`. Proven with a probe before the fix.
 */
const grammarBlock = grammarContentAsParsingBlock;
