import { ChangeDetectionStrategy, Component, ViewChild, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { Observable } from 'rxjs';
import {
    apiErrorMessage,
    AuthoredNode,
    ComponentDef,
    ComponentsService,
    ParserDef,
    ParserPreview,
} from 'app/inspecto/api';
import { parseUseRef } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { GrammarEditorComponent, grammarContentAsParsingBlock } from 'app/inspecto/grammar';
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
 * node's own `parsing:` block, full stop. *Save as template…* writes a `grammar` component as a
 * reusable starting point and leaves the node untouched — no `use:` binding, block still inline.
 *
 * <p>⚠ This REVERSES the previous store contract, in which the same action MOVED the block into the
 * component and bound the node, making a later template edit reach back into every pipeline using it.
 * The `use: grammar/<id>` form stays **read-supported** (a hand-authored file may use it) but is never
 * authored; `{@link updateBoundComponent}` is the last writer of one and retires in S3. See
 * `docs/superpower/grammar-templates-not-bindings-plan.md`.
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
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
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
    private fb = inject(FormBuilder);
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

    readonly saving = signal(false);

    /** `config` (author + test) → `name` (asked only when extracting to a reusable Grammar). */
    readonly step = signal<'config' | 'name'>('config');
    /** The block captured before the name step unmounts the editor (its form dies with the `@if`). */
    private pendingBlock: Record<string, unknown> | null = null;

    readonly nameForm = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                (c: AbstractControl): ValidationErrors | null =>
                    this.grammars().some((g) => g.name === String(c.value ?? '').trim()) ? { duplicate: true } : null,
            ],
        ],
    });

    /** Esc / backdrop / Cancel all confirm before discarding a dirty Grammar. */
    readonly requestClose = guardDirtyClose(this.ref, () => this.editor?.isDirty() ?? false, this.confirm);

    constructor() {
        const ref = parseUseRef(this.data.node.use);
        const boundId = ref?.kind === 'grammar' ? ref.id : null;
        this.boundGrammarId.set(boundId);
        if (!boundId) this.seedFrom(nodeParsingBlock(this.data.node));

        this.components.list('grammar').subscribe({
            next: (list) => {
                this.grammars.set(list);
                const bound = boundId ? list.find((g) => g.name === boundId) : null;
                if (bound) this.seedFrom(grammarBlock(bound.content ?? {}));
            },
            error: () => this.grammars.set([]),
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
            this.nameForm.reset({ name: '' });
            this.seedFrom(nodeParsingBlock(this.data.node));
            return;
        }
        const def = this.grammars().find((g) => g.name === id);
        if (!def) return;
        this.boundGrammarId.set(id);
        this.seedFrom(grammarBlock(def.content ?? {}));
    }

    /** The suggested id for a freshly extracted Grammar: `<frontend>_grammar`, sanitized. */
    suggestedName(): string {
        const frontend = String(this.editor?.value()['frontend'] ?? 'delimited');
        return `${frontend}_grammar`.replace(/[^A-Za-z0-9._-]+/g, '_');
    }

    /** Store the inline Grammar as a reusable template — the only path that asks for a name. */
    extract(): void {
        if (this.pluginBlocked() || !this.editor?.validate()) return;
        this.pendingBlock = this.editor.value();
        if (this.nameForm.controls.name.pristine) this.nameForm.patchValue({ name: this.suggestedName() });
        this.step.set('name');
    }

    /** Leave the name step back to the editor (the typed name is kept). */
    backToConfig(): void {
        this.step.set('config');
    }

    save(): void {
        if (this.step() === 'name') {
            if (this.nameForm.invalid) {
                this.nameForm.markAllAsTouched();
                return;
            }
            this.saveAsTemplate(String(this.nameForm.getRawValue().name ?? '').trim(), this.pendingBlock!);
            return;
        }

        if (this.pluginBlocked() || !this.editor?.validate()) return;
        const block = this.editor.value();
        const bound = this.boundGrammarId();
        if (bound) this.updateBoundComponent(bound, block);
        else this.closeInline(block);
    }

    /** The default: the block lives on the node itself, and any previous binding is dropped. */
    private closeInline(block: Record<string, unknown>): void {
        const { use: _unbound, ...node } = this.data.node;
        this.ref.close({ node: { ...node, config: { ...(this.data.node.config ?? {}), parsing: block } } });
    }

    /**
     * Store the authored Grammar as a reusable **template** — a copy, not a link (2026-08-15).
     *
     * ⚠ Until this date the same action MOVED the block into the component and bound the node via
     * `use: grammar/<id>`, so a later template edit reached back into every pipeline using it. It now
     * writes the component and leaves the node exactly as it was, inline block and all. See
     * `docs/superpower/grammar-templates-not-bindings-plan.md`.
     */
    private saveAsTemplate(name: string, block: Record<string, unknown>): void {
        this.write(this.components.create('grammar', { id: name, ...block }), name, () =>
            this.closeInline(block),
        );
    }

    /**
     * Update the component a bound node references — the last surface that writes a Grammar in place.
     * Retired in S3, when bound nodes migrate to an inline copy on edit; until then it keeps its
     * pre-existing binding behaviour so a legacy bound node is not silently rehomed mid-plan.
     */
    private updateBoundComponent(name: string, block: Record<string, unknown>): void {
        this.write(this.components.update('grammar', name, block), name, () => {
            const config = { ...(this.data.node.config ?? {}) };
            delete config['parsing'];
            this.ref.close({ node: { ...this.data.node, use: `grammar/${name}`, config } });
        });
    }

    private write(req$: Observable<unknown>, name: string, onDone: () => void): void {
        this.saving.set(true);
        req$.subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success(`Grammar "${name}" saved`);
                onDone();
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : apiErrorMessage(e, `Could not save "${name}"`),
                );
            },
        });
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
