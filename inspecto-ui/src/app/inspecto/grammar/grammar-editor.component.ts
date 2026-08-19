import {
    AfterViewInit,
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    EventEmitter,
    Input,
    Output,
    QueryList,
    ViewChildren,
    computed,
    inject,
    signal,
} from '@angular/core';
import {
    AbstractControl,
    FormArray,
    FormBuilder,
    FormGroup,
    ReactiveFormsModule,
    Validators,
} from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { Subscription, merge } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable } from 'rxjs';
import { ParserDef, ParserPreview, ParserTreeNode, ParsersService, apiErrorMessage } from 'app/inspecto/api';
import {
    AttributeSpec,
    fieldSpecsToAttributes,
    flattenBlock,
    nestKeys,
    clearMissingRoots,
} from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { ParserTreeComponent } from 'app/inspecto/components/parser-tree.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { PARSING_FRONTENDS, ParsingFrontend, grammarTabsFor, parsingAttributesFor } from './parsing-attributes';
import { FrontendSuggestion, jsonSampleToTree, sniffFrontend } from './parsing-sniff';

/** The `parsing:` roots this editor owns — switching frontend clears the others' sub-blocks. */
export const PARSING_ROOTS = [
    'frontend',
    'delimited',
    'fixedwidth',
    'json',
    'text_regex',
    'xlsx',
    'encoding',
    'compression',
    'plugin',
];

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
        NgTemplateOutlet,
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatTabsModule,
        MatTooltipModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoSchemaFormComponent,
        ParserTreeComponent,
        DataTableComponent,
    ],
    templateUrl: './grammar-editor.component.html',
})
export class GrammarEditorComponent implements AfterViewInit {
    private readonly parsersApi = inject(ParsersService);
    private readonly fb = inject(FormBuilder);
    private readonly destroyRef = inject(DestroyRef);

    /**
     * Every mounted schema-form — ONE for a flat spec set, one PER TAB for a tabbed set (§4.1 of the
     * delimited-grammar-properties plan). All host-facing reads go through the aggregation helpers
     * below so neither shape leaks to adopters.
     */
    @ViewChildren(InspectoSchemaFormComponent) private schemaForms?: QueryList<InspectoSchemaFormComponent>;

    /** The single form of a flat (untabbed) spec set — legacy accessor for specs/hosts. */
    get schemaForm(): InspectoSchemaFormComponent | undefined {
        return this.schemaForms?.first;
    }

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

    /** A BINARY sample's bytes as base64 (an .xlsx workbook — multiformat X4), host-supplied. When
     *  set it wins over `sample`, whose text is then only the strip's human summary. */
    @Input() set sampleBytes(v: string | null | undefined) {
        this.sampleB64.set(v || null);
    }
    @Input() sampleMode: SampleMode = 'own';

    /**
     * Pre-select a type (a built-in frontend id or a served parser id).
     *
     * ⚠ A SETTER that re-attempts, for the same reason {@link configuredIngester} does: the catalog
     * fetch is kicked off in the constructor, so with an already-resolved source it completes BEFORE
     * Angular sets this input and `applyPendingType` runs against an empty pending slot. Both orders
     * must resolve, or a served-parser type (asn1) silently presents as the default built-in.
     */
    @Input() set type(v: string | null | undefined) {
        if (!v) return;
        this.pendingType.set(v);
        const list = this.served();
        if (list) this.applyPendingType(list);
    }

    /**
     * Hide the format picker: for a per-format parser node (B6, `parser.delimited` first) the format
     * IS the node's type, so offering a switch here would author a block the save path refuses with
     * PARSER_FRONTEND_MISMATCH. Hosts that lock the type also seed it via `[type]`.
     */
    @Input() lockType = false;

    /**
     * Optional preview override. Onboarding parses BUILT-INS through `POST /config/preview/parsing`
     * so the result feeds the sample thread and the Schema stage; without an override the editor uses
     * the stateless `POST /parsers/{id}/preview`, which is all a dialog needs.
     */
    @Input() previewFn?: (
        type: string,
        grammar: Record<string, unknown>,
        text: string,
        b64?: string,
    ) => Observable<ParserPreview>;

    /** Enter on the form, or the editor's own Save affordance in a host that renders one. */
    @Output() readonly submitted = new EventEmitter<void>();
    /** A successful preview — hosts mirror it into their own state (Onboarding's parsed hop). */
    @Output() readonly previewed = new EventEmitter<ParserPreview>();
    /** The sample text changed inside the editor (`sampleMode: 'own'`). */
    @Output() readonly sampleChange = new EventEmitter<string>();
    /** The active type changed (a built-in frontend id or a served parser id). */
    @Output() readonly typeChange = new EventEmitter<string>();
    /**
     * The selected PLUGIN parser, or null when a built-in is active. Emitted on selection AND on
     * load-time rehydration, so a host can react without reading the editor through a `@ViewChild`
     * in its template — which would evaluate before the query resolves.
     */
    @Output() readonly pluginChange = new EventEmitter<ParserDef | null>();

    // ── state ────────────────────────────────────────────────────────────────

    protected readonly block = signal<Record<string, unknown>>({});
    protected readonly seed = signal<Record<string, unknown>>({});
    readonly sampleText = signal('');
    /** Base64 bytes of a binary sample; non-null wins over {@link sampleText} at Test parse. */
    readonly sampleB64 = signal<string | null>(null);
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

    /**
     * The tab split of the active spec set, or null to render flat. A set names tabs by `spec.tab`
     * (grammarTabsFor order); fewer than 2 distinct tabs ⇒ flat — json/fixedwidth/text_regex and every
     * served plugin render byte-identical to before. Untabbed specs in a tabbed set fall into tab 1.
     */
    readonly tabs = computed<{ id: string; label: string; specs: AttributeSpec[] }[] | null>(() => {
        const specs = this.specs();
        const ids = new Set(specs.map((s) => s.tab).filter((t): t is string => !!t));
        if (ids.size < 2) return null;
        const shell = grammarTabsFor(this.frontend());
        const fallback = shell[0].id;
        // The 'files' tab is kept even when no spec names it: it anchors the Collection pointer and
        // the host's [tabFiles] projection (the column-metadata grid) — xlsx has no file-level
        // option of its own but the tab's content is not the specs'.
        return shell
            .filter(
                (t) =>
                    ids.has(t.id) || t.id === 'files' || (t.id === fallback && specs.some((s) => !s.tab)),
            )
            .map((t) => ({
                ...t,
                specs: specs.filter((s) => (s.tab ?? fallback) === t.id),
            }));
    });

    /** The selected tab of a tabbed set — `validate()` steers it to the first failing tab. */
    readonly activeTab = signal(0);

    /**
     * The seed with legacy scalar spellings normalised for `list` controls: an old block authored as
     * `null_strings: "NULL,N/A"` (the pre-tab UI wrote comma-joined strings) seeds the chips split,
     * exactly as the engine's own `strList` reads it — otherwise the stored value renders as an empty
     * list and a save would silently drop it.
     */
    readonly seedValue = computed<Record<string, unknown>>(() => {
        const seed = this.seed();
        const out: Record<string, unknown> = { ...seed };
        for (const s of this.specs()) {
            const v = out[s.key];
            if (s.type === 'list' && typeof v === 'string') {
                const items = v
                    .split(',')
                    .map((x) => x.trim())
                    .filter(Boolean);
                out[s.key] = items.length ? items : null;
            }
        }
        return out;
    });

    /** Bumped on any form value/status change — drives the tab badges under OnPush. */
    private readonly formTick = signal(0);
    private formSubs = new Subscription();

    /**
     * Per-tab badge state: how many values are set away from their declared default (the count chip),
     * and whether the tab holds an invalid touched control (the warn dot) — the operator sees where
     * configuration lives without hunting (§4.1).
     */
    readonly tabBadges = computed<{ set: number; invalid: boolean }[]>(() => {
        this.formTick();
        const tabs = this.tabs();
        if (!tabs) return [];
        const forms = this.forms();
        return tabs.map((t, i) => {
            const form = forms[i];
            if (!form) return { set: 0, invalid: false };
            const value = form.value();
            const blank = (x: unknown): boolean =>
                x === undefined || x === null || x === '' || (Array.isArray(x) && x.length === 0);
            let set = 0;
            for (const s of t.specs) {
                const v = value[s.key];
                const d = s.default;
                if (blank(v) && blank(d)) continue;
                if (!blank(v) && !blank(d) && String(v) === String(d)) continue;
                set++;
            }
            return { set, invalid: form.form.invalid && form.form.touched };
        });
    });

    ngAfterViewInit(): void {
        this.schemaForms?.changes.subscribe(() => this.resubscribeForms());
        this.resubscribeForms();
        this.destroyRef.onDestroy(() => this.formSubs.unsubscribe());
    }

    /** Re-wire the badge tick to the CURRENT set of mounted forms (a spec/tab swap remounts them). */
    private resubscribeForms(): void {
        this.formSubs.unsubscribe();
        this.formSubs = new Subscription();
        for (const f of this.forms()) {
            this.formSubs.add(
                merge(f.form.valueChanges, f.form.statusChanges).subscribe(() =>
                    this.formTick.update((n) => n + 1),
                ),
            );
        }
        this.formTick.update((n) => n + 1);
    }

    // ── schema-form aggregation (one flat form, or one per tab) ────────────────

    private forms(): InspectoSchemaFormComponent[] {
        return this.schemaForms?.toArray() ?? [];
    }

    /** The merged live values of every mounted form (keys share one flat namespace). */
    private formsValue(): Record<string, unknown> {
        return Object.assign({}, ...this.forms().map((f) => f.value()));
    }

    /** The control for a flat key, wherever it is mounted. */
    private controlFor(key: string): AbstractControl | null {
        for (const f of this.forms()) {
            const c = f.form.get(key);
            if (c) return c;
        }
        return null;
    }

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
        if (match) {
            this.pluginDef.set(match);
            this.pluginChange.emit(match);
        }
    }

    private applyPendingType(list: ParserDef[]): void {
        const want = this.pendingType();
        if (!want) return;
        this.pendingType.set(null);
        const plugin = list.find((p) => p.id === want && !PARSING_FRONTENDS.some((f) => f.id === p.id));
        if (plugin) {
            this.pluginDef.set(plugin);
            this.pluginChange.emit(plugin);
        } else if (PARSING_FRONTENDS.some((f) => f.id === want)) {
            this.frontend.set(want as ParsingFrontend);
        }
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
        this.seed.update((s) => ({ ...s, ...this.formsValue() }));
        this.pluginDef.set(null);
        this.frontend.set(f);
        this.activeTab.set(0);
        this.typeTouched.set(true);
        this.preview.set(null);
        this.error.set(null);
        if (f === 'fixedwidth' && this.fwFields.length === 0) this.addField();
        this.pluginChange.emit(null);
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
        this.seed.update((s) => ({ ...s, ...this.formsValue() }));
        this.pluginDef.set(plugin);
        // Selecting a PREVIEW-ONLY plugin is not an unsaved change: there is nothing to save, so
        // nothing can be lost by navigating away — and a host that treats it as dirty raises an
        // unsaved-changes guard the operator can never satisfy. An INGESTABLE plugin can be saved,
        // so switching to one is a real edit.
        if (plugin.ingestable && plugin.ingesterClass) this.typeTouched.set(true);
        this.preview.set(null);
        this.error.set(null);
        this.pluginChange.emit(plugin);
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
                const c = this.controlFor('delimited__delimiter');
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
        this.sampleB64.set(null); // typing replaces any captured binary sample
        this.preview.set(null);
        this.error.set(null);
        this.sampleChange.emit(text);
    }

    /** Load sample content from a local file (text, capped; an .xlsx workbook captures as BYTES). */
    onSampleFile(files: FileList | null): void {
        const file = files?.[0];
        if (!file) return;
        // Binary: text() would round-trip the zip through a charset. No truncation — a sliced zip is
        // unreadable, so an oversized workbook is refused whole rather than silently maimed.
        if (this.frontend() === 'xlsx' || /\.xlsx$/i.test(file.name)) {
            if (file.size > MAX_SAMPLE_BYTES) {
                this.error.set(`Workbook too large for a preview sample (max ${MAX_SAMPLE_BYTES / 1024} KB).`);
                return;
            }
            file.arrayBuffer().then(
                (buf) => {
                    let bin = '';
                    for (const b of new Uint8Array(buf)) bin += String.fromCharCode(b);
                    this.sampleB64.set(btoa(bin));
                    this.sampleText.set(
                        `[${file.name} — binary workbook, ${Math.max(1, Math.round(file.size / 1024))} KB]`,
                    );
                    this.preview.set(null);
                    this.error.set(null);
                },
                () => this.error.set('Could not read the file.'),
            );
            return;
        }
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

    /** Validate every property sheet (all tabs) and (for fixed width) the slice table. */
    validate(): boolean {
        // Validate ALL forms (marking each invalid one touched), then steer to the first failing tab
        // rather than short-circuiting — cross-tab errors must all light up their badges at once.
        let firstFailing = -1;
        const forms = this.forms();
        for (let i = 0; i < forms.length; i++) {
            if (!forms[i].validate() && firstFailing < 0) firstFailing = i;
        }
        if (firstFailing >= 0) {
            if (this.tabs()) this.activeTab.set(firstFailing);
            return false;
        }
        if (
            !this.pluginDef() &&
            this.frontend() === 'fixedwidth' &&
            (this.fwForm.invalid || this.fwFields.length === 0)
        ) {
            this.fwForm.markAllAsTouched();
            this.error.set('Fixed width needs at least one field (name, start, length).');
            return false;
        }
        return true;
    }

    isDirty(): boolean {
        return this.forms().some((f) => f.isDirty()) || this.fwForm.dirty || this.typeTouched();
    }

    markPristine(): void {
        for (const f of this.forms()) f.form.markAsPristine();
        this.fwForm.markAsPristine();
        this.typeTouched.set(false);
    }

    /** The nested grammar map as authored — a plugin's served options, or the built-in sub-blocks. */
    grammar(): Record<string, unknown> {
        return nestKeys(this.formsValue());
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
        const b64 = this.sampleB64() ?? undefined;
        if ((!text && !b64) || !this.validate()) return;
        const type = this.activeType();
        // 🔴 Fixed width's slice table is its OWN FormArray, not part of the property sheet, so
        // `grammar()` carries no `fixedwidth.fields` and the preview ALWAYS failed with "fixed width
        // needs at least one field" — no matter what the operator typed. Only {@link value} injects
        // them. Plugins keep `grammar()`: `value()` would stamp a built-in block shape over theirs.
        const grammar = this.frontend() === 'fixedwidth' && !this.pluginDef() ? this.value() : this.grammar();
        this.testing.set(true);
        this.error.set(null);
        const req$ = this.previewFn
            ? this.previewFn(type, grammar, text, b64)
            : this.parsersApi.preview(type, grammar, text, b64);
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
    const f = String(raw ?? 'delimited')
        .trim()
        .toLowerCase();
    if (f === 'fixed_width' || f === 'fixedwidth') return 'fixedwidth';
    if (f === 'json') return 'json';
    if (f === 'text_regex') return 'text_regex';
    if (f === 'xlsx' || f === 'excel') return 'xlsx';
    return 'delimited';
}
