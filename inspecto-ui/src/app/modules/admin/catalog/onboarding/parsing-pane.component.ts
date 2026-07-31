import { ChangeDetectionStrategy, Component, OnDestroy, ViewChild, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, LensService, ParserDef, ParserPreview, ParserTreeNode, ParsersService, SpacesService, apiErrorMessage } from 'app/inspecto/api';
import { forkJoin, switchMap } from 'rxjs';
import { fieldSpecsToAttributes } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { ParserTreeComponent } from 'app/inspecto/components/parser-tree.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { OnboardingSamplePanelComponent } from './sample-panel.component';
import { OnboardingSegmentsEditorComponent } from './segments-editor.component';
import { SegmentDraft, schemaDraftFor } from './segment-drafts';
import { PARSING_FRONTENDS, ParsingFrontend, parsingAttributesFor } from './parsing-attributes';
import { FrontendSuggestion, jsonSampleToTree, sniffFrontend } from './parsing-sniff';
import { clearMissingRoots, flattenBlock, mergeBlock, nestKeys } from 'app/inspecto/component-model';
import { OnboardingStateService } from './onboarding-state.service';

/** The parsing-block roots this pane owns — switching frontend clears the others' sub-blocks. */
const PARSING_ROOTS = ['frontend', 'delimited', 'fixedwidth', 'json', 'text_regex', 'encoding', 'compression', 'plugin'];

/**
 * Parsing stage — authors the Stage-1 `parsing:` block over the four engine-real frontends and
 * chains the captured sample through `POST /config/preview/parsing`, so the builder sees THEIR
 * data parsed by the same DuckDB idioms the engine runs (D4 sample-as-thread, the raw→parsed
 * hop). A `plugin` frontend (Java ingester + segment schemas) is TOON-managed: shown, never
 * edited here.
 */
@Component({
    selector: 'app-onboarding-parsing-pane',
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
        OnboardingSamplePanelComponent,
        ParserTreeComponent,
        DataTableComponent,
        OnboardingSegmentsEditorComponent,
    ],
    templateUrl: './parsing-pane.component.html',
})
export class OnboardingParsingPaneComponent implements OnDestroy {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private configApi = inject(ConfigService);
    private parsersApi = inject(ParsersService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);

    @ViewChild('sf') schemaForm?: InspectoSchemaFormComponent;
    @ViewChild(OnboardingSegmentsEditorComponent) segmentsEditor?: OnboardingSegmentsEditorComponent;

    private spaces = inject(SpacesService);

    readonly frontends = PARSING_FRONTENDS;

    /** The served parser catalog (`GET /parsers`); null until it arrives — the four engine-real
     *  built-ins render regardless, so an old server degrades to exactly the previous behavior. */
    readonly served = signal<ParserDef[] | null>(null);
    /** Served parsers beyond the built-ins (plugins: XML today, ASN.1/vendors when deployed). */
    readonly pluginTypes = computed<ParserDef[]>(() =>
        (this.served() ?? []).filter((p) => !PARSING_FRONTENDS.some((f) => f.id === p.id)),
    );
    /** The selected PLUGIN parser (null = one of the four built-ins is selected). */
    readonly pluginDef = signal<ParserDef | null>(null);
    /**
     * The selected plugin can actually load to Tables — it names a `StreamingFileIngester`. Only
     * then is the segments editor shown and Save unlocked; a preview-only plugin (XML today) still
     * cannot be saved, because there is nothing honest to write.
     */
    readonly pluginIngestable = computed(() => {
        const p = this.pluginDef();
        return !!p && p.ingestable && !!p.ingesterClass;
    });
    /** The active type id — a built-in frontend or a plugin id (drives the toggle + options form). */
    readonly activeType = computed(() => this.pluginDef()?.id ?? this.frontend());

    /** A plugin preview result (pane-local: the sample thread's parsed hop stays builtin-only). */
    readonly pluginPreview = signal<ParserPreview | null>(null);
    readonly pluginError = signal<string | null>(null);
    readonly pluginRows = computed<Record<string, unknown>[]>(() => {
        const p = this.pluginPreview();
        return p?.kind === 'table' ? p.rows : [];
    });
    /** The decoded record forest — what the segments editor derives its proposals from. */
    readonly pluginTreeNodes = computed<ParserTreeNode[] | null>(() => {
        const p = this.pluginPreview();
        return p?.kind === 'tree' ? p.nodes : null;
    });

    private readonly parsingBlock =
        ((this.state.config() ?? {})['parsing'] as Record<string, unknown> | undefined) ?? {};

    /** A plugin ingester (parsing.plugin / processing.ingester) is authored in the TOON, not here. */
    readonly pluginManaged: boolean =
        !!this.parsingBlock['plugin'] ||
        String(this.parsingBlock['frontend'] ?? '') === 'plugin' ||
        !!((this.state.config() ?? {})['processing'] as Record<string, unknown> | undefined)?.['ingester'];

    readonly frontend = signal<ParsingFrontend>(normalizeFrontend(this.parsingBlock['frontend']));
    /** The options form: engine-real specs for the built-ins, the SERVED grammar schema for plugins. */
    readonly specs = computed(() => {
        const plugin = this.pluginDef();
        return plugin ? fieldSpecsToAttributes(plugin.grammarSchema) : parsingAttributesFor(this.frontend());
    });
    readonly initial = flattenBlock(this.parsingBlock);
    /** Frontend switched since load — dirty even before a field is touched. */
    private readonly frontendTouched = signal(false);

    /** Bespoke fixed-width fields editor: name / start / length rows (`fixedwidth.fields[]`). */
    readonly fwForm: FormGroup = this.fb.group({ fields: this.fb.array<FormGroup>([]) });
    get fwFields(): FormArray<FormGroup> {
        return this.fwForm.controls['fields'] as FormArray<FormGroup>;
    }

    readonly saving = signal(false);
    readonly testing = signal(false);
    readonly parsedRows = computed<Record<string, unknown>[]>(() => this.state.parsePreview()?.rows ?? []);

    /** Sniffed frontend suggestion — shown only while it differs from the current pick. */
    readonly suggestion = computed<FrontendSuggestion | null>(() => {
        const text = this.state.sample()?.text;
        if (!text) return null;
        const s = sniffFrontend(text);
        return s && s.frontend !== this.activeType() ? s : null;
    });

    /** Parsed-result presentation; Tree is offered for the json frontend when the sample parses. */
    readonly resultView = signal<'table' | 'tree'>('table');
    readonly treeNodes = computed(() => {
        const text = this.state.sample()?.text ?? '';
        return this.frontend() === 'json' && text ? jsonSampleToTree(text) : null;
    });

    private readonly dirtyCheck = (): boolean =>
        (this.schemaForm?.isDirty() ?? false) || this.fwForm.dirty || this.frontendTouched()
        || (this.segmentsEditor?.isDirty() ?? false);

    constructor() {
        const fields = (this.parsingBlock['fixedwidth'] as Record<string, unknown> | undefined)?.['fields'];
        if (Array.isArray(fields)) {
            for (const f of fields as Record<string, unknown>[]) {
                this.addField(String(f['name'] ?? ''), Number(f['start'] ?? 0), Number(f['length'] ?? 1));
            }
        }
        this.state.registerDirtyCheck(this.dirtyCheck);
        // The catalog is additive: without it (old server / offline blip) the built-ins still work.
        this.parsersApi.list().subscribe({
            next: (list) => this.served.set(list),
            error: () => this.served.set(null),
        });
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
    }

    setFrontend(f: ParsingFrontend): void {
        if (f === this.frontend() && !this.pluginDef()) return;
        this.pluginDef.set(null);
        this.frontend.set(f);
        this.frontendTouched.set(true);
        if (f === 'fixedwidth' && this.fwFields.length === 0) this.addField();
    }

    /** Toggle click: a built-in frontend id, or a served plugin id (preview-only options form). */
    setType(id: string): void {
        const plugin = this.pluginTypes().find((p) => p.id === id);
        if (!plugin) {
            this.setFrontend(id as ParsingFrontend);
            return;
        }
        if (this.pluginDef()?.id === plugin.id) return;
        // Plugin selection is a preview/authoring aid — Save stays disabled, so it is deliberately
        // NOT part of the unsaved-changes dirty state (nothing here can be lost by navigating).
        this.pluginDef.set(plugin);
        this.pluginPreview.set(null);
        this.pluginError.set(null);
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

    /** The `parsing:` block as currently edited (frontend + its keys; other frontends cleared). */
    private buildParsingBlock(): Record<string, unknown> | null {
        if (!this.schemaForm?.validate()) return null;
        const nested = nestKeys(this.schemaForm.value());
        nested['frontend'] = this.frontend();
        if (this.frontend() === 'fixedwidth') {
            if (this.fwForm.invalid || this.fwFields.length === 0) {
                this.fwForm.markAllAsTouched();
                this.toastr.warning('Fixed width needs at least one field (name, start, length).');
                return null;
            }
            const fw = (nested['fixedwidth'] ??= {}) as Record<string, unknown>;
            fw['fields'] = this.fwFields.controls.map((g) => ({
                name: String(g.value['name'] ?? '').trim(),
                start: Number(g.value['start'] ?? 0),
                length: Number(g.value['length'] ?? 1),
            }));
        }
        return clearMissingRoots(nested, PARSING_ROOTS);
    }

    testParse(): void {
        const sample = this.state.sample();
        if (!sample) return;
        const plugin = this.pluginDef();
        if (plugin) {
            // Stateless grammar preview — the plugin path (table or tree), pane-local: the sample
            // thread's parsed hop is fed only by the built-ins the draft can actually go live with.
            if (!this.schemaForm?.validate()) return;
            const grammar = nestKeys(this.schemaForm.value());
            this.testing.set(true);
            this.pluginError.set(null);
            this.parsersApi.preview(plugin.id, grammar, sample.text).subscribe({
                next: (p) => {
                    this.testing.set(false);
                    this.pluginPreview.set(p);
                },
                error: (e) => {
                    this.testing.set(false);
                    this.pluginPreview.set(null);
                    this.pluginError.set(apiErrorMessage(e, 'The sample does not parse with this grammar.'));
                },
            });
            return;
        }
        const parsing = this.buildParsingBlock();
        if (!parsing) return;
        const draft = mergeBlock(this.state.config() ?? {}, { parsing });
        this.testing.set(true);
        this.state.parseError.set(null);
        this.configApi.previewParsing(draft, sample.text).subscribe({
            next: (p) => {
                this.testing.set(false);
                this.state.parsePreview.set(p);
                // Re-parsing invalidates any schema cast-check computed against the old rows.
                this.state.schemaPreview.set(null);
                this.state.schemaError.set(null);
            },
            error: (e) => {
                this.testing.set(false);
                this.state.parsePreview.set(null);
                this.state.parseError.set(apiErrorMessage(e, 'The sample does not parse with these settings.'));
            },
        });
    }

    // ── plugin (segments) save ───────────────────────────────────────────────────

    private readonly pipelineName = String((this.state.config() ?? {})['name'] ?? '');

    private base(): string {
        return this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
    }
    /** One schema toon per segment, named by convention — the Schema stage's `conventionPath` idiom. */
    private schemaNameFor(segmentKey: string): string {
        return `${this.pipelineName}_${segmentKey}`.replace(/[^A-Za-z0-9_]+/g, '_');
    }
    private schemaPathFor(segmentKey: string): string {
        return `${this.base()}/config/${this.schemaNameFor(segmentKey)}.toon`;
    }

    /** The segments already saved in `parsing.plugin`, re-hydrated for the editor. */
    readonly initialSegments = computed<SegmentDraft[]>(() => {
        const plugin = this.parsingBlock['plugin'] as Record<string, unknown> | undefined;
        const segs = plugin?.['segments'];
        if (!segs || typeof segs !== 'object') return [];
        // The pipeline stores segment key → schema-toon PATH; the columns live in those files, which
        // this pane does not read. Keys alone still let the operator see what exists and re-derive.
        return Object.keys(segs as Record<string, unknown>).map((key) => ({ key, columns: [] }));
    });

    /**
     * Persist an ingestable plugin parser: write each segment's schema toon, then point
     * `parsing.plugin` at them. Two hops, same as the Schema stage (write the schema, then
     * reference it) — the pipeline must never name a schema file that does not exist yet, which is
     * why the block is patched only after every write resolves.
     */
    private savePlugin(plugin: ParserDef): void {
        const editor = this.segmentsEditor;
        if (!editor || !editor.validate()) {
            this.toastr.warning(editor?.problem() ?? 'Add at least one segment.');
            return;
        }
        if (!this.schemaForm?.validate()) return;
        const segments = editor.value();
        // The parser's grammar IS the ingester's config, minus its id namespace — so preview and
        // ingest are driven by the same authored values and cannot drift.
        const grammar = nestKeys(this.schemaForm.value());
        const ingesterConfig = (grammar[plugin.id] ?? {}) as Record<string, unknown>;

        this.saving.set(true);
        const writes = segments.map((s) =>
            this.configApi.write('schema', schemaDraftFor(s, this.schemaNameFor(s.key)), { overwrite: true }),
        );
        forkJoin(writes)
            .pipe(
                switchMap(() => {
                    const segmentPaths: Record<string, string> = {};
                    for (const s of segments) segmentPaths[s.key] = this.schemaPathFor(s.key);
                    return this.state.saveBlock({
                        parsing: {
                            frontend: 'plugin',
                            plugin: {
                                ingester: plugin.ingesterClass,
                                segments: segmentPaths,
                                ingester_config: ingesterConfig,
                            },
                        },
                    });
                }),
            )
            .subscribe({
                next: () => {
                    this.saving.set(false);
                    this.schemaForm?.form.markAsPristine();
                    editor.markPristine();
                    this.toastr.success(`Parsing saved — ${segments.length} segment(s)`);
                },
                error: (e) => {
                    this.saving.set(false);
                    this.toastr.error(apiErrorMessage(e, 'Could not save the segments.'));
                },
            });
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        const plugin = this.pluginDef();
        if (plugin) {
            if (this.pluginIngestable()) this.savePlugin(plugin);
            return;   // a preview-only plugin has nothing honest to save
        }
        const parsing = this.buildParsingBlock();
        if (!parsing) return;
        this.saving.set(true);
        this.state.saveBlock({ parsing }).subscribe({
            next: () => {
                this.saving.set(false);
                this.schemaForm?.form.markAsPristine();
                this.fwForm.markAsPristine();
                this.frontendTouched.set(false);
                this.toastr.success('Parsing saved');
            },
            error: () => this.saving.set(false),
        });
    }
}

function normalizeFrontend(raw: unknown): ParsingFrontend {
    const f = String(raw ?? 'delimited').trim().toLowerCase();
    if (f === 'fixed_width' || f === 'fixedwidth') return 'fixedwidth';
    if (f === 'json') return 'json';
    if (f === 'text_regex') return 'text_regex';
    return 'delimited';
}
