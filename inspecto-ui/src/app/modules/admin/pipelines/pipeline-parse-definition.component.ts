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
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthoredNode } from 'app/inspecto/api';
import { GrammarEditorComponent } from 'app/inspecto/grammar';

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
                [initial]="parsingBlock()"
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

    private lastDirty = false;

    constructor() {
        // Seed from the node input. The host recreates this component per node (and on Discard), so
        // this runs once per instance — but an input swap without recreation re-seeds correctly too
        // (the [initial] binding re-seeds the editor, which is what marks it pristine again).
        effect(() => {
            const n = this.node();
            this.form.patchValue({ name: n.name ?? '', description: n.description ?? '' });
            this.form.markAsPristine();
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

    private emitDirty(): void {
        const dirty = this.form.dirty || (this.editor?.isDirty() ?? false);
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
        const n = this.node();
        const node: AuthoredNode = {
            ...n,
            name: (v.name ?? '').trim() || n.name,
            description: (v.description ?? '').trim() || undefined,
            config: { ...(n.config ?? {}), parsing: block },
        };
        this.form.markAsPristine();
        this.editor.markPristine();
        this.emitDirty();
        this.applied.emit(node);
    }
}
