import { ChangeDetectionStrategy, Component, ViewChild, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import {
    AuthoredNode,
    ComponentDef,
    ComponentsService,
    ParserDef,
    ParserPreview,
} from 'app/inspecto/api';
import { flattenBlock, nestKeys, parseUseRef } from 'app/inspecto/component-model';
import { downloadCsv } from 'app/inspecto/data-table/core/csv';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
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
import { NodeConfigResult } from './node-config.dialog';

/** Dialog data: the parse node to configure + its (resolved) type/category labels for the header. */
export interface GrammarEditorDialogData {
    node: AuthoredNode;
    typeLabel: string;
    categoryLabel: string;
}

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
 * library means. See `docs/archived-documents/plans-archive/grammar-templates-not-bindings-plan.md`.
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
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatSelectModule,
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
    readonly data = inject<GrammarEditorDialogData>(MAT_DIALOG_DATA);

    @ViewChild(GrammarEditorComponent) private editor?: GrammarEditorComponent;

    /** Existing reusable Grammars (the inline-or-choose options). */
    readonly grammars = signal<ComponentDef[]>([]);
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
    }

    /** Onward link: author the Schema this parse feeds — seeded with the test-parsed rows, so
     *  "Suggest from sample" is armed. The editor saves itself; nothing here changes the node. */
    openSchemaEditor(): void {
        this.dialog.open(SchemaEditorDialog, {
            data: { sampleRows: this.previewRows() } satisfies SchemaEditorData,
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

    save(): void {
        if (this.pluginBlocked() || !this.editor?.validate()) return;
        // Always inline — a bound node MIGRATES to an independent copy rather than writing back to the
        // shared component (D4). `closeInline` already drops the `use:`, so both cases are one path.
        this.closeInline(this.editor.value());
    }

    /** The default: the block lives on the node itself, and any previous binding is dropped. */
    private closeInline(block: Record<string, unknown>): void {
        const { use: _unbound, ...node } = this.data.node;
        this.ref.close({ node: { ...node, config: { ...(this.data.node.config ?? {}), parsing: block } } });
    }
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
