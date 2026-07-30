import { ChangeDetectionStrategy, Component, computed, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    AuthoredNode,
    ComponentDef,
    ComponentsService,
    ParserDef,
    ParserPreview,
    ParsersService,
} from 'app/inspecto/api';
import { fieldSpecsToAttributes, flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { NodeConfigResult } from './node-config.dialog';
import { ParserTreeComponent } from 'app/inspecto/components/parser-tree.component';

/** Sample cap — mirrors the onboarding sample panel (a scratch preview, not a data upload). */
const MAX_SAMPLE_BYTES = 256 * 1024;

/** Dialog data: the parser node to configure + its (resolved) type/category labels for the header. */
export interface ParserConfigData {
    node: AuthoredNode;
    typeLabel: string;
    categoryLabel: string;
}

/** A short illustrative sample seeded into the content viewer so Test has something to chew on. */
function sampleFor(type: string | undefined): string {
    switch (type) {
        case 'delimited':
            return 'id,msisdn,start_time,duration_s\n1001,8801700000001,2026-06-24 09:00:00,42\n1002,8801700000002,2026-06-24 09:01:30,17';
        case 'json':
            return '{ "id": 1001, "msisdn": "8801700000001", "duration_s": 42 }\n{ "id": 1002, "msisdn": "8801700000002", "duration_s": 17 }';
        case 'xml':
            return '<records>\n  <record id="1001"><msisdn>8801700000001</msisdn><duration_s>42</duration_s></record>\n  <record id="1002"><msisdn>8801700000002</msisdn><duration_s>17</duration_s></record>\n</records>';
        case 'text_regex':
            return 'INFO 2026-06-24 pipeline started\nWARN 2026-06-24 slow batch';
        case 'fixedwidth':
            return '1001 8801700000001 0042\n1002 8801700000002 0017';
        default:
            return '';
    }
}

/**
 * Parser configuration — the rich, multi-pane editor for a PARSE node (replaces the generic node-config
 * popup for parsers). Runs on the SERVED parser framework: the file-format dropdown is
 * {@code GET /parsers} (built-ins + any parser deployed as a plugin — no UI change when one lands),
 * the property sheet renders each parser's served grammar schema through the shared
 * `<inspecto-schema-form>` (`fieldSpecsToAttributes`), and Test parse runs the REAL
 * {@code POST /parsers/{id}/preview}: a searchable table for tabular formats, a collapsible record
 * tree ({@link ParserTreeComponent}) for hierarchical ones. The config persists as a reusable
 * `grammar` component (content `{ parser_type, <nested grammar> }`); on save the node is bound to it
 * via `use`.
 *
 * **Ask the minimum** (binding form rule): a fresh parser's config comes first; the grammar **name is
 * asked only at save time** (a save step, pre-filled `<type>_grammar`, unique). Editing an existing
 * grammar (chosen from the Grammar dropdown, or pre-bound via the node's `use`) saves straight
 * through with its id locked.
 */
@Component({
    selector: 'app-parser-config-dialog',
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
        DataTableComponent,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        ParserTreeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './parser-config.dialog.html',
})
export class ParserConfigDialog {
    private fb = inject(FormBuilder);
    private components = inject(ComponentsService);
    private parsersApi = inject(ParsersService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ParserConfigDialog, NodeConfigResult>);
    readonly data = inject<ParserConfigData>(MAT_DIALOG_DATA);

    /** The served parser catalog — built-ins + deployed plugins (`GET /parsers`). */
    readonly parserDefs = signal<ParserDef[]>([]);

    /** The selected file format (a served parser id; drives the property sheet + table-vs-tree output). */
    readonly parserType = signal('delimited');
    readonly parserDef = computed<ParserDef | null>(
        () => this.parserDefs().find((p) => p.id === this.parserType()) ?? null,
    );
    readonly parserTypeLabel = computed(() => this.parserDef()?.label ?? this.parserType());
    readonly isHierarchical = computed(() => this.parserDef()?.hierarchical ?? false);

    /** The served grammar schema, schema-form-ready (unknown field shapes skipped, never guessed). */
    readonly schemaFormSpecs = computed(() => fieldSpecsToAttributes(this.parserDef()?.grammarSchema));
    /** Saved values to seed the schema-form with; `undefined` ⇒ the served defaults (fresh/new type). */
    readonly schemaFormInitial = signal<Record<string, unknown> | undefined>(undefined);
    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    /** The built content snapshot from the config step — captured before the save step unmounts the
     *  schema-form (its `<form>` is destroyed with the `@if` block, so it can't be read again there). */
    private pendingContent: Record<string, unknown> | null = null;

    /** Existing reusable grammars (the choose-or-create options). */
    readonly grammars = signal<ComponentDef[]>([]);
    /** The grammar being edited (dropdown-picked, or pre-bound via the node's `use`); `null` ⇒ authoring new. */
    readonly boundGrammarId = signal<string | null>(null);

    /** Raw sample content the user chooses/pastes; fed to the Test preview. */
    readonly sampleText = signal('');
    readonly preview = signal<ParserPreview | null>(null);
    readonly gridRows = signal<Record<string, unknown>[]>([]);

    readonly testing = signal(false);
    readonly testError = signal<string | null>(null);
    readonly saving = signal(false);

    // ── View state: full-screen dialog · per-pane maximize ──
    /** Expand the whole dialog to fill the viewport. */
    readonly fullscreen = signal(false);
    /** Which single pane fills the body (`null` = the normal layout). */
    readonly maximized = signal<'props' | 'sample' | 'output' | null>(null);

    /** A pane gets the tall layout when it is maximized or the whole dialog is full-screen. */
    readonly bigProps = computed(() => this.fullscreen() || this.maximized() === 'props');
    readonly bigSample = computed(() => this.fullscreen() || this.maximized() === 'sample');
    readonly bigOutput = computed(() => this.fullscreen() || this.maximized() === 'output');

    /** Create flow: `config` (pick/author + properties + test) → `save` (name, asked only now). Editing an
     *  existing grammar stays on `config` and saves straight through. */
    readonly step = signal<'config' | 'save'>('config');
    /** Save-step field (create only): the grammar name IS the unique id pipeline nodes reference via `use`. */
    readonly saveForm = this.fb.group({
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

    constructor() {
        this.sampleText.set(sampleFor('delimited'));
        this.parsersApi.list().subscribe({
            next: (defs) => this.parserDefs.set(defs),
            error: () => this.parserDefs.set([]),
        });
        this.components.list('grammar').subscribe({
            next: (list) => {
                this.grammars.set(list);
                const ref = this.data.node.use ?? '';
                const boundId = ref.startsWith('grammar/') ? ref.slice('grammar/'.length) : null;
                const bound = boundId ? list.find((g) => g.name === boundId) : null;
                if (bound) this.loadGrammar(bound);
            },
            error: () => this.grammars.set([]),
        });
    }

    /** Switch file format → reset the property sheet to that type's served defaults + reseed the sample. */
    onTypeChange(type: string): void {
        this.parserType.set(type);
        this.schemaFormInitial.set(undefined);
        this.sampleText.set(sampleFor(type));
        this.preview.set(null);
        this.testError.set(null);
        this.maximized.set(null);
    }

    /** Choose an existing grammar to edit, or `''` to author a new one (of the current type). */
    onGrammarChange(id: string): void {
        if (!id) {
            this.boundGrammarId.set(null);
            this.saveForm.reset({ name: '' });
            this.onTypeChange(this.parserType());
            return;
        }
        const def = this.grammars().find((g) => g.name === id);
        if (def) this.loadGrammar(def);
    }

    /** Load a saved grammar's content into the form (type + props), binding the id and locking the save step. */
    private loadGrammar(def: ComponentDef): void {
        const content = def.content ?? {};
        const type = typeof content['parser_type'] === 'string' ? (content['parser_type'] as string) : 'delimited';
        this.parserType.set(type);
        const { parser_type: _pt, ...grammar } = content;
        this.schemaFormInitial.set(flattenBlock(grammar as Record<string, unknown>));
        this.sampleText.set(sampleFor(type));
        this.boundGrammarId.set(def.name);
        this.saveForm.patchValue({ name: def.name });
        this.preview.set(null);
        this.step.set('config');
    }

    /** Load sample content from a local file (text, capped) — the "choose a file → view it" entry. */
    onSampleFile(files: FileList | null): void {
        const file = files?.[0];
        if (!file) return;
        const truncated = file.size > MAX_SAMPLE_BYTES;
        file.slice(0, MAX_SAMPLE_BYTES)
            .text()
            .then(
                (text) => {
                    this.sampleText.set(text);
                    this.preview.set(null);
                    this.testError.set(null);
                    if (truncated) this.toastr.info(`Sample truncated to the first ${MAX_SAMPLE_BYTES / 1024} KB.`);
                },
                () => this.toastr.error('Could not read the file as text.'),
            );
    }

    // ── Layout: full-screen + per-pane maximize ──

    /** A pane shows in the normal layout, or is the single pane currently maximized. */
    paneVisible(pane: 'props' | 'sample' | 'output'): boolean {
        return this.maximized() === null || this.maximized() === pane;
    }

    /** Maximize a pane to fill the body, or restore the normal layout. */
    toggleMaximize(pane: 'props' | 'sample' | 'output'): void {
        this.maximized.update((cur) => (cur === pane ? null : pane));
    }

    /** Expand the dialog to the full viewport (adds a panel class that overrides the open-time maxWidth). */
    toggleFullscreen(): void {
        const on = !this.fullscreen();
        this.fullscreen.set(on);
        if (on) this.ref.addPanelClass('dialog-fullscreen');
        else this.ref.removePanelClass('dialog-fullscreen');
    }

    /** The grammar component content: `parser_type` + the nested grammar the form authored. */
    private buildContent(): Record<string, unknown> {
        return { parser_type: this.parserType(), ...nestKeys(this.schemaForm.value()) };
    }

    /** The nested grammar map alone (what the preview endpoint takes). */
    private buildGrammar(): Record<string, unknown> {
        return nestKeys(this.schemaForm.value());
    }

    /** The suggested grammar name for a freshly authored parser: `<type>_grammar`, sanitized. */
    suggestedName(): string {
        return `${this.parserType()}_grammar`.replace(/[^A-Za-z0-9._-]+/g, '_');
    }

    /** Parse the sample with the in-progress grammar (no save) → table or tree preview. */
    test(): void {
        this.testing.set(true);
        this.testError.set(null);
        this.preview.set(null);
        this.parsersApi.preview(this.parserType(), this.buildGrammar(), this.sampleText()).subscribe({
            next: (p) => {
                this.testing.set(false);
                this.preview.set(p);
                if (p.kind === 'table') {
                    this.gridRows.set(p.rows);
                }
            },
            error: (e) => {
                this.testing.set(false);
                this.testError.set(apiErrorMessage(e, 'Parse test failed'));
            },
        });
    }

    /** Create flow only: leave the save step back to the config step (name is kept). */
    backToConfig(): void {
        this.step.set('config');
    }

    /** Save the parser as a reusable grammar (create or update) and bind the node to it via `use`. */
    save(): void {
        if (this.step() === 'config') {
            if (!this.schemaForm.validate()) return;

            const content = this.buildContent();
            const bound = this.boundGrammarId();
            if (bound) {
                this.persist(bound, content);
                return;
            }
            // Ask the minimum: a fresh parser's name is asked only now, at save time.
            this.pendingContent = content;
            if (this.saveForm.controls.name.pristine) {
                this.saveForm.patchValue({ name: this.suggestedName() });
            }
            this.step.set('save');
            return;
        }

        if (this.saveForm.invalid) {
            this.saveForm.markAllAsTouched();
            return;
        }
        this.persist(String(this.saveForm.getRawValue().name ?? '').trim(), this.pendingContent!);
    }

    private persist(name: string, content: Record<string, unknown>): void {
        this.saving.set(true);
        const req$ = this.boundGrammarId()
            ? this.components.update('grammar', name, content)
            : this.components.create('grammar', { id: name, ...content });
        req$.subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success(`Parser "${name}" saved`);
                this.ref.close({ node: { ...this.data.node, use: `grammar/${name}` } });
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
