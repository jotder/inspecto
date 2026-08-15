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
import { AuthoredNode, ComponentDef } from 'app/inspecto/api';
import { GrammarEditorComponent, grammarContentAsParsingBlock, isDelimitedGrammar } from 'app/inspecto/grammar';

/**
 * The **Parse definition pane** (definition-surface P3a) — the delimited path of the parse node,
 * re-hosted inside `<inspecto-definition-drawer>` instead of `grammar-editor.dialog`. Renders
 * name/description plus the shared `<inspecto-grammar-editor>` locked to the delimited format:
 * a `parser.delimited` node's format IS its type (B6), so the picker would only author a block the
 * save path refuses with PARSER_FRONTEND_MISMATCH.
 *
 * <p>**Pure** (D2): {@link submit} rebuilds the node with the Grammar in its inline `parsing:` home
 * and emits it — the host patches its in-memory model and the toolbar Save persists. Grammar-BOUND
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
                @if (delimitedTemplates().length) {
                    <mat-form-field class="w-56" subscriptSizing="dynamic">
                        <mat-label>Start from a template</mat-label>
                        <mat-select [value]="pickedTemplate()" (selectionChange)="applyTemplate($event.value)">
                            @for (t of delimitedTemplates(); track t.name) {
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
                type="delimited"
                [lockType]="true"
                (submitted)="submit()"
            />
        </form>
    `,
})
export class PipelineParseDefinitionComponent {
    private fb = inject(FormBuilder);

    /** The `parser.delimited` node being defined (identity fixed; config/name/description editable). */
    readonly node = input.required<AuthoredNode>();
    /**
     * Stored Grammar components offered as starting points. Passed IN rather than fetched: the pane
     * stays pure (P2 — `[node]` in, outputs out, no injected state) and the host already lists them.
     */
    readonly templates = input<ComponentDef[]>([]);

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
     * Only Grammars that can seed a DELIMITED node. A component naming another frontend would author
     * a block the save path refuses with `PARSER_FRONTEND_MISMATCH`, so it is not offered at all.
     */
    readonly delimitedTemplates = computed<ComponentDef[]>(() =>
        this.templates().filter((t) => isDelimitedGrammar(t.content ?? {})),
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
            this.emitDirty();
        });
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
        const t = this.delimitedTemplates().find((x) => x.name === name);
        if (!t) return;
        this.pickedTemplate.set(name);
        this.templateBlock.set(grammarContentAsParsingBlock(t.content ?? {}));
        this.templateDirty = true;
        this.emitDirty();
    }

    private emitDirty(): void {
        const dirty = this.form.dirty || this.templateDirty || (this.editor?.isDirty() ?? false);
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
        if (!this.editor?.validate()) return;
        this.saveAsTemplate.emit({ ...this.editor.value(), frontend: 'delimited' });
    }

    /**
     * Validate, rebuild the node with the Grammar in its inline `parsing:` home, emit. The frontend
     * key is stamped explicitly — it is what makes the file lift back to this node type — and the
     * editor is marked pristine because Apply consumed the edits.
     */
    submit(): void {
        if (!this.editor?.validate()) return;
        const block = { ...this.editor.value(), frontend: 'delimited' };
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
        this.editor.markPristine();
        this.templateDirty = false; // Apply consumed the pick, same as any other edit
        this.emitDirty();
        this.applied.emit(node);
    }
}
