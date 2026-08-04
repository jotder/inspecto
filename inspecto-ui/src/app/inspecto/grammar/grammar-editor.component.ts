import {
    ChangeDetectionStrategy,
    Component,
    EventEmitter,
    Input,
    Output,
    ViewChild,
    computed,
    inject,
    signal,
} from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable } from 'rxjs';
import { ParserDef, ParserPreview, ParserTreeNode, ParsersService, apiErrorMessage } from 'app/inspecto/api';
import { AttributeSpec, fieldSpecsToAttributes, flattenBlock, nestKeys, clearMissingRoots } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { ParserTreeComponent } from 'app/inspecto/components/parser-tree.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { PARSING_FRONTENDS, ParsingFrontend, parsingAttributesFor } from './parsing-attributes';
import { FrontendSuggestion, jsonSampleToTree, sniffFrontend } from './parsing-sniff';

/** The `parsing:` roots this editor owns — switching frontend clears the others' sub-blocks. */
export const PARSING_ROOTS = ['frontend', 'delimited', 'fixedwidth', 'json', 'text_regex', 'encoding', 'compression', 'plugin'];

/** Sample cap — a scratch preview, not a data upload. */
const MAX_SAMPLE_BYTES = 256 * 1024;

/** How a host wants the sample supplied: the editor's own file/paste box, or the host's own strip. */
export type SampleMode = 'own' | 'host';

/**
 * THE Grammar editor — one surface for authoring how raw bytes become rows, shared by the Onboarding
 * Parsing stage and the Pipelines parse node. Before this existed the two were separate
 * implementations over separate stores; see `docs/okf/frontend/features/grammar-config.md`.
 *
 * <p>**Canonical vocabulary:** this authors a **Grammar** (`docs/GLOSSARY.md` §Grammar). Never call it
 * "parser config" or "parse options" in UI copy — those are banned synonyms. *Parser* is the engine
 * that applies a Grammar; the two are different concepts.
 *
 * <p>**No write path — by design.** The editor validates and hands back a value; the HOST persists it.
 * That is what lets one component serve a stage that patches `parsing:` into a pipeline config and a
 * dialog that writes a reusable `grammar` component, without either persistence model leaking into
 * the other. Same rule as `<inspecto-collector-config>` and `<inspecto-enrichment-editor>`.
 *
 * <p>**Degrades honestly.** The four engine-real built-in frontends render even when `GET /parsers`
 * fails, so an old server or an offline blip still leaves the editor usable (the Onboarding behaviour;
 * the dialog previously showed an empty dropdown, which was strictly worse).
 *
 * <p>**Not absorbed:** the segments editor for ingestable plugins. It is projected via
 * `[grammarExtras]` because it is inseparable from a HOST's persistence — segments are written as one
 * schema `.toon` per segment before the block that references them, and this component has no write
 * path. Hosts that cannot author segments simply project nothing.
 */
@Component({
    selector: 'inspecto-grammar-editor',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoSchemaFormComponent,
        ParserTreeComponent,
        DataTableComponent,
    ],
    templateUrl: './grammar-editor.component.html',
})
export class GrammarEditorComponent {
    private readonly parsersApi = inject(ParsersService);
    private readonly fb = inject(FormBuilder);

    @ViewChild(InspectoSchemaFormComponent) schemaForm?: InspectoSchemaFormComponent;

    /** The Grammar as stored (a nested `parsing:`-shaped block); reseeds the form. */
    @Input() set initial(v: Record<string, unknown> | undefined) {
        this.block.set(v ?? {});
        this.seed.set(flattenBlock(v ?? {}));
        this.frontend.set(normalizeFrontend((v ?? {})['frontend']));
        this.seedFixedWidthFields((v ?? {})['fixedwidth']);
    }

    /** Raw sample text to parse. With `sampleMode: 'host'` the host owns the input UI. */
    @Input() set sample(v: string | null | undefined) {
        this.sampleText.set(v ?? '');
    }
    @Input() sampleMode: SampleMode = 'own';

    /** Pre-select a type (a built-in frontend id or a served parser id). */
    @Input() set type(v: string | null | undefined) {
        if (v) this.pendingType.set(v);
    }

    /**
     * Optional preview override. Onboarding parses BUILT-INS through `POST /config/preview/parsing`
     * so the result feeds the sample thread and the Schema stage; without an override the editor uses
     * the stateless `POST /parsers/{id}/preview`, which is all a dialog needs.
     */
    @Input() previewFn?: (type: string, grammar: Record<string, unknown>, text: string) => Observable<ParserPreview>;

    /** Enter on the form, or the editor's own Save affordance in a host that renders one. */
    @Output() readonly submitted = new EventEmitter<void>();
    /** A successful preview — hosts mirror it into their own state (Onboarding's parsed hop). */
    @Output() readonly previewed = new EventEmitter<ParserPreview>();
    /** The sample text changed inside the editor (`sampleMode: 'own'`). */
    @Output() readonly sampleChange = new EventEmitter<string>();
    /** The active type changed (a built-in frontend id or a served parser id). */
    @Output() readonly typeChange = new EventEmitter<string>();

    // ── state ────────────────────────────────────────────────────────────────

    protected readonly block = signal<Record<string, unknown>>({});
    protected readonly seed = signal<Record<string, unknown>>({});
    readonly sampleText = signal('');
    readonly frontends = PARSING_FRONTENDS;
    readonly frontend = signal<ParsingFrontend>('delimited');

    /** The served catalog; null until it arrives OR when the fetch failed — built-ins render anyway. */
    readonly served = signal<ParserDef[] | null>(null);
    /** A type id requested via `[type]` before the catalog landed. */
    private readonly pendingType = signal<string | null>(null);
    readonly pluginDef = signal<ParserDef | null>(null);

    readonly pluginTypes = computed<ParserDef[]>(() =>
        (this.served() ?? []).filter((p) => !PARSING_FRONTENDS.some((f) => f.id === p.id)),
    );
    /** The plugin can actually load to Tables — only then is segment authoring meaningful. */
    readonly pluginIngestable = computed(() => {
        const p = this.pluginDef();
        return !!p && p.ingestable && !!p.ingesterClass;
    });
    readonly activeType = computed(() => this.pluginDef()?.id ?? this.frontend());

    readonly specs = computed<AttributeSpec[]>(() => {
        const plugin = this.pluginDef();
        return plugin ? fieldSpecsToAttributes(plugin.grammarSchema) : parsingAttributesFor(this.frontend());
    });

    readonly preview = signal<ParserPreview | null>(null);
    readonly error = signal<string | null>(null);
    readonly testing = signal(false);
    readonly rows = computed<Record<string, unknown>[]>(() => {
        const p = this.preview();
        return p?.kind === 'table' ? p.rows : [];
    });
    readonly treeNodes = computed<ParserTreeNode[] | null>(() => {
        const p = this.preview();
        return p?.kind === 'tree' ? p.nodes : null;
    });

    /** Client-side JSON tree of the raw sample — offered for the json frontend, no server hop. */
    readonly sampleTree = computed(() => {
        const text = this.sampleText();
        return this.frontend() === 'json' && text ? jsonSampleToTree(text) : null;
    });
    readonly resultView = signal<'table' | 'tree'>('table');

    /** Sniffed suggestion — shown only while it differs from the current pick. Never auto-applied. */
    readonly suggestion = computed<FrontendSuggestion | null>(() => {
        const text = this.sampleText();
        if (!text) return null;
        const s = sniffFrontend(text);
        return s && s.frontend !== this.activeType() ? s : null;
    });

    private readonly typeTouched = signal(false);

    /** Fixed-width field slices (`fixedwidth.fields[]`) — name / start / length. */
    readonly fwForm: FormGroup = this.fb.group({ fields: this.fb.array<FormGroup>([]) });
    get fwFields(): FormArray<FormGroup> {
        return this.fwForm.controls['fields'] as FormArray<FormGroup>;
    }

    /**
     * The FQCN this Grammar parses through, when it names a plugin. A SETTER, not a plain field:
     * inputs are not bound yet when the constructor runs, so the catalog can land either side of it
     * and both orders must re-attempt the rehydrate — otherwise a saved plugin silently presents as
     * the built-in it normalizes to.
     */
    @Input() set configuredIngester(v: string) {
        this.ingesterFqcn.set((v ?? '').trim());
        const list = this.served();
        if (list) this.rehydratePlugin(list);
    }
    private readonly ingesterFqcn = signal('');

    /** The config names an ingester no served parser provides — the plugin jar is not deployed here. */
    readonly unservedPlugin = computed(() => {
        const list = this.served();
        const fqcn = this.ingesterFqcn();
        if (!fqcn || !list) return null;
        return list.some((p) => p.ingesterClass === fqcn) ? null : fqcn;
    });

    constructor() {
        // Additive: without the catalog (old server / offline blip) the built-ins still work.
        this.parsersApi.list().subscribe({
            next: (list) => {
                this.served.set(list);
                this.applyPendingType(list);
                this.rehydratePlugin(list);
            },
            error: () => this.served.set(null),
        });
    }

    /**
     * Re-select the served parser a saved plugin Grammar names. Identified by **`ingesterClass`**,
     * because a saved config stores the FQCN, never the parser id — so the id is recoverable only from
     * the served catalog. Load-time restoration, so it must NOT mark the editor dirty.
     */
    private rehydratePlugin(list: ParserDef[]): void {
        const fqcn = this.ingesterFqcn();
        if (!fqcn || this.pluginDef()) return;
        const match = list.find((p) => p.ingesterClass === fqcn);
        // Not `setType`: that marks the selection as a user action. This is load-time restoration, so
        // it must NOT make the editor dirty — that would prompt "discard changes?" for an untouched
        // config.
        if (match) this.pluginDef.set(match);
    }

    private applyPendingType(list: ParserDef[]): void {
        const want = this.pendingType();
        if (!want) return;
        this.pendingType.set(null);
        const plugin = list.find((p) => p.id === want && !PARSING_FRONTENDS.some((f) => f.id === p.id));
        if (plugin) this.pluginDef.set(plugin);
        else if (PARSING_FRONTENDS.some((f) => f.id === want)) this.frontend.set(want as ParsingFrontend);
    }

    private seedFixedWidthFields(fw: unknown): void {
        this.fwFields.clear();
        const fields = (fw as Record<string, unknown> | undefined)?.['fields'];
        if (!Array.isArray(fields)) return;
        for (const f of fields as Record<string, unknown>[]) {
            this.addField(String(f['name'] ?? ''), Number(f['start'] ?? 0), Number(f['length'] ?? 1));
        }
    }

    // ── type selection ───────────────────────────────────────────────────────

    setFrontend(f: ParsingFrontend): void {
        if (f === this.frontend() && !this.pluginDef()) return;
        // Reassigning `specs` REBUILDS every control from its declared default, and a stable `initial`
        // reference is not re-applied — so carry the current values across the switch or the operator's
        // typing vanishes. (The exact trap found extracting <inspecto-collector-config>.)
        this.seed.update((s) => ({ ...s, ...(this.schemaForm?.value() ?? {}) }));
        this.pluginDef.set(null);
        this.frontend.set(f);
        this.typeTouched.set(true);
        this.preview.set(null);
        this.error.set(null);
        if (f === 'fixedwidth' && this.fwFields.length === 0) this.addField();
        this.typeChange.emit(f);
    }

    /** Toggle click: a built-in frontend id, or a served plugin id. */
    setType(id: string): void {
        const plugin = this.pluginTypes().find((p) => p.id === id);
        if (!plugin) {
            this.setFrontend(id as ParsingFrontend);
            return;
        }
        if (this.pluginDef()?.id === plugin.id) return;
        this.seed.update((s) => ({ ...s, ...(this.schemaForm?.value() ?? {}) }));
        this.pluginDef.set(plugin);
        this.typeTouched.set(true);
        this.preview.set(null);
        this.error.set(null);
        this.typeChange.emit(plugin.id);
    }

    /** The toggle's short name for a served label ("XML — XML file format" → "XML"). */
    typeName(label: string): string {
        return label.split(' — ')[0];
    }
    typeHint(label: string): string {
        return label.split(' — ')[1] ?? '';
    }

    frontendLabel(f: ParsingFrontend): string {
        return PARSING_FRONTENDS.find((x) => x.id === f)?.label ?? f;
    }

    /** One-click apply of the sniffed suggestion (frontend + delimiter prefill) — never automatic. */
    applySuggestion(): void {
        const s = this.suggestion();
        if (!s) return;
        this.setFrontend(s.frontend);
        const delim = s.delimiter;
        if (delim && delim !== ',') {
            // The schema-form rebuilds its controls for the new frontend on the next render.
            setTimeout(() => {
                const c = this.schemaForm?.form.get('delimited__delimiter');
                c?.setValue(delim);
                c?.markAsDirty();
            });
        }
    }

    // ── fixed-width slices ───────────────────────────────────────────────────

    addField(name = '', start = 0, length = 1): void {
        this.fwFields.push(
            this.fb.group({
                name: [name, Validators.required],
                start: [start, [Validators.required, Validators.min(0)]],
                length: [length, [Validators.required, Validators.min(1)]],
            }),
        );
    }

    removeField(i: number): void {
        this.fwFields.removeAt(i);
        this.fwForm.markAsDirty();
    }

    // ── sample ───────────────────────────────────────────────────────────────

    onSampleText(text: string): void {
        this.sampleText.set(text);
        this.preview.set(null);
        this.error.set(null);
        this.sampleChange.emit(text);
    }

    /** Load sample content from a local file (text, capped). */
    onSampleFile(files: FileList | null): void {
        const file = files?.[0];
        if (!file) return;
        const truncated = file.size > MAX_SAMPLE_BYTES;
        file.slice(0, MAX_SAMPLE_BYTES)
            .text()
            .then(
                (text) => {
                    this.onSampleText(text);
                    if (truncated) this.error.set(`Sample truncated to the first ${MAX_SAMPLE_BYTES / 1024} KB.`);
                },
                () => this.error.set('Could not read the file as text.'),
            );
    }

    // ── host API ─────────────────────────────────────────────────────────────

    /** Validate the property sheet and (for fixed width) the slice table. */
    validate(): boolean {
        if (!this.schemaForm?.validate()) return false;
        if (!this.pluginDef() && this.frontend() === 'fixedwidth' && (this.fwForm.invalid || this.fwFields.length === 0)) {
            this.fwForm.markAllAsTouched();
            this.error.set('Fixed width needs at least one field (name, start, length).');
            return false;
        }
        return true;
    }

    isDirty(): boolean {
        return (this.schemaForm?.isDirty() ?? false) || this.fwForm.dirty || this.typeTouched();
    }

    markPristine(): void {
        this.schemaForm?.form.markAsPristine();
        this.fwForm.markAsPristine();
        this.typeTouched.set(false);
    }

    /** The nested grammar map as authored — a plugin's served options, or the built-in sub-blocks. */
    grammar(): Record<string, unknown> {
        return nestKeys(this.schemaForm?.value() ?? {});
    }

    /**
     * The `parsing:`-shaped block for a BUILT-IN frontend: the authored keys + `frontend`, with the
     * other frontends' sub-blocks cleared. Plugin hosts use {@link grammar} instead — their block
     * shape (`plugin.ingester`/`segments`/`ingester_config`) depends on a write the host owns.
     */
    value(): Record<string, unknown> {
        const nested = this.grammar();
        nested['frontend'] = this.frontend();
        if (this.frontend() === 'fixedwidth') {
            const fw = (nested['fixedwidth'] ??= {}) as Record<string, unknown>;
            fw['fields'] = this.fwFields.controls.map((g) => ({
                name: String(g.value['name'] ?? '').trim(),
                start: Number(g.value['start'] ?? 0),
                length: Number(g.value['length'] ?? 1),
            }));
        }
        return clearMissingRoots(nested, PARSING_ROOTS);
    }

    // ── preview ──────────────────────────────────────────────────────────────

    /** Parse the sample with the in-progress Grammar (no save) → table or tree. */
    test(): void {
        const text = this.sampleText();
        if (!text || !this.validate()) return;
        const type = this.activeType();
        const grammar = this.grammar();
        this.testing.set(true);
        this.error.set(null);
        const req$ = this.previewFn
            ? this.previewFn(type, grammar, text)
            : this.parsersApi.preview(type, grammar, text);
        req$.subscribe({
            next: (p) => {
                this.testing.set(false);
                this.preview.set(p);
                this.previewed.emit(p);
            },
            error: (e) => {
                this.testing.set(false);
                this.preview.set(null);
                this.error.set(apiErrorMessage(e, 'The sample does not parse with these settings.'));
            },
        });
    }
}

/** `fixed_width` is the engine's legacy spelling of the same frontend; anything unknown is delimited. */
function normalizeFrontend(raw: unknown): ParsingFrontend {
    const f = String(raw ?? 'delimited').trim().toLowerCase();
    if (f === 'fixed_width' || f === 'fixedwidth') return 'fixedwidth';
    if (f === 'json') return 'json';
    if (f === 'text_regex') return 'text_regex';
    return 'delimited';
}
