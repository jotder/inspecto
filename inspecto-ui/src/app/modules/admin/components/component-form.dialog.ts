import { Component, computed, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import {
    AbstractControl,
    FormBuilder,
    FormGroup,
    FormsModule,
    ReactiveFormsModule,
    ValidationErrors,
    ValidatorFn,
    Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatChipInputEvent, MatChipsModule } from '@angular/material/chips';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { COMMA, ENTER } from '@angular/cdk/keycodes';
import { Observable } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    ComponentDef,
    ComponentsService,
    ComponentType,
    GrammarPreview,
    RelationsPreview,
    SinkPreview,
} from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { grammarContentAsParsingBlock, isNestedGrammarContent, nonDelimitedGrammar } from 'app/inspecto/grammar';

/** Dialog data: `def` set ⇒ edit mode (id locked, Test available); absent ⇒ create. */
interface ComponentFormData {
    kind: ComponentType;
    def?: ComponentDef;
}

/** Close payload: the saved component, or a 503 signal so the caller can hide mutate actions. */
export interface ComponentFormResult {
    saved?: ComponentDef;
    writesDisabled?: boolean;
}

/** Config textarea: blank is fine (untouched default), anything else must parse as JSON. */
const jsonValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
    const v = control.value;
    if (!v || !String(v).trim()) return null;
    try {
        JSON.parse(v);
        return null;
    } catch {
        return { invalidJson: true };
    }
};

const TRANSFORM_SUBTYPES = [
    'transform.map',
    'transform.select',
    'transform.derive',
    'transform.filter',
    'transform.validate',
    'transform.dedup.marker',
    'transform.route',
    'transform.split',
    'transform.merge',
];
const SINK_KINDS = ['sink.persistent', 'sink.materialized', 'sink.view'];
const SINK_FORMATS = ['parquet', 'csv', 'json', 'avro'];

/**
 * Create/edit a registry component (grammar / transform / sink) — generalises the connection form
 * to the non-secret kinds (T19). Structured fields per kind; a transform's operator config and free-form
 * keys are authored as JSON. An inline **Test** panel runs the saved component over a sample through the
 * production dry-run endpoints (T18) on a throwaway DuckDB — no write. Submits to POST/PUT /components/{type}.
 */
@Component({
    selector: 'app-component-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        FormsModule,
        MatDialogModule,
        MatButtonModule,
        MatChipsModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatSlideToggleModule,
        StatusBadgeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.Eager,
    templateUrl: './component-form.dialog.html',
})
export class ComponentFormDialog {
    private fb = inject(FormBuilder);
    private api = inject(ComponentsService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<ComponentFormDialog, ComponentFormResult>);
    readonly data = inject<ComponentFormData>(MAT_DIALOG_DATA);

    readonly kind = this.data.kind;
    readonly isEdit = !!this.data.def;
    readonly transformSubtypes = TRANSFORM_SUBTYPES;
    readonly sinkKinds = SINK_KINDS;
    readonly sinkFormats = SINK_FORMATS;

    saving = false;
    readonly testing = signal(false);
    readonly grammarResult = signal<GrammarPreview | null>(null);
    readonly relationsResult = signal<RelationsPreview | null>(null);
    readonly sinkResult = signal<SinkPreview | null>(null);
    readonly testError = signal<string | null>(null);

    /** Raw sample input for the Test panel: free text for grammar, a JSON rows array otherwise. */
    sampleText = this.kind === 'grammar' ? 'a,b,c\n1,2,3' : '';
    sampleRows = '[{ "id": "1", "amt": "150" }, { "id": "x", "amt": "abc" }]';

    /** Original `partitions:` entries by chip label — a `{column, source}` map survives an untouched save. */
    private readonly sinkPartitionEntries = new Map<string, unknown>();

    /**
     * The stored grammar content read as a `parsing:` block. Kept whole so a save re-emits the keys
     * this form cannot author (`frontend`, `null_strings`, …) instead of replacing the component with
     * its six DSV fields — `PUT /components` is a REPLACE, and the server's "merge" carries over only
     * `owner`/`shares` (`ComponentAccess.onUpdate`), so anything dropped here is gone from the file.
     */
    private grammarBlock: Record<string, unknown> = {};

    /** Whether to write the settings back under `delimited:` — a stored shape must not change on save. */
    private grammarNested = false;

    /** Set when the stored grammar is one this DSV form cannot express; names it, and blocks Save. */
    readonly grammarUnauthorable = signal<string | null>(null);

    readonly title = computed(() => `${this.isEdit ? 'Edit' : 'New'} ${this.kind}`);
    readonly partitionSeparatorKeys = [ENTER, COMMA];

    form: FormGroup = this.fb.group({
        id: [
            { value: '', disabled: this.isEdit },
            [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)],
        ],
        // grammar
        delimiter: [','],
        hasHeader: [false],
        skipHeaderLines: [0],
        quote: [''],
        escape: [''],
        encoding: [''],
        // transform
        subtype: [TRANSFORM_SUBTYPES[0]],
        config: ['{\n  "where": "CAST(amt AS INT) >= 100"\n}', [jsonValidator]],
        // sink
        sinkKind: [SINK_KINDS[0]],
        store: [''],
        format: ['parquet'],
        partitions: [[] as string[]],
    });

    constructor() {
        const c = this.data.def?.content ?? {};
        if (this.data.def) this.form.patchValue({ id: this.data.def.name });

        if (this.kind === 'grammar') {
            // Seed through the normaliser, never off raw content: a component written by a Parse drawer
            // keeps its csv settings under `delimited:`, and reading top-level keys would show DEFAULTS
            // for a stored `|` — the exact trap grammar-block.ts's "Load-bearing" note describes.
            const content = c as Record<string, unknown>;
            this.grammarBlock = grammarContentAsParsingBlock(content);
            this.grammarNested = isNestedGrammarContent(content);
            // This form has six delimited inputs and nothing else, so a non-DSV Grammar must be REFUSED
            // rather than opened showing defaults — the Parse drawer makes the same call for plugins.
            this.grammarUnauthorable.set(nonDelimitedGrammar(content));
            const d = obj(this.grammarBlock['delimited']);
            this.form.patchValue({
                delimiter: str(d['delimiter'], ','),
                hasHeader: d['has_header'] === true || d['has_header'] === 'true',
                skipHeaderLines: num(d['skip_header_lines'], 0),
                quote: str(d['quote'], ''),
                escape: str(d['escape'], ''),
                encoding: str(this.grammarBlock['encoding'], ''),
            });
        } else if (this.kind === 'transform') {
            const { type, ...rest } = c as { type?: string };
            this.form.patchValue({
                subtype: typeof type === 'string' ? type : TRANSFORM_SUBTYPES[0],
                config: Object.keys(rest).length ? JSON.stringify(rest, null, 2) : this.form.get('config')!.value,
            });
        } else if (this.kind === 'sink') {
            // Keep the original partitions entries (some carry {column, source} maps) keyed by their
            // chip label, so an unedited entry round-trips verbatim instead of collapsing to a string.
            const src = c['partitions'];
            if (Array.isArray(src)) {
                for (const p of src) {
                    const label = partitionLabel(p);
                    if (label) this.sinkPartitionEntries.set(label, p);
                }
            }
            this.form.patchValue({
                sinkKind: str(c['type'], SINK_KINDS[0]),
                store: str(c['store'], ''),
                format: str(c['format'], 'parquet'),
                partitions: partitionsOf(c),
            });
        }
    }

    // AI drafting (AGT-6a A5.2) lived here and was offered ONLY for the `schema` kind — a validator over
    // the control plane's ConfigSpecs, and of this dialog's kinds only `schema` had one. `schema` is no
    // longer a registry component (retired 2026-07-31, unification W1: a schema lives solely in the config
    // TOON the engine executes), so the affordance has no applicable kind and was removed WITH it rather
    // than left rendering "no structural spec for kind" on every use. To bring it back, give another kind a
    // structural ConfigSpec first — the bounded repair loop on the backend is untouched and still generic.

    addPartition(event: MatChipInputEvent): void {
        const value = event.value.trim();
        if (value) {
            const ctrl = this.form.controls['partitions'];
            ctrl.setValue([...(ctrl.value as string[]), value]);
        }
        event.chipInput?.clear();
    }

    removePartition(value: string): void {
        const ctrl = this.form.controls['partitions'];
        ctrl.setValue((ctrl.value as string[]).filter((p) => p !== value));
    }

    /** Assemble the kind-specific content map (without the routing `id`, which create() adds). */
    private buildContent(): Record<string, unknown> | null {
        const v = this.form.getRawValue();
        switch (this.kind) {
            case 'grammar': {
                // Start from the STORED block, not an empty literal: the six inputs below are the only
                // keys this form owns, and a PUT replaces the file wholesale.
                const block = { ...this.grammarBlock };
                const delimited = obj(block['delimited']);
                delimited['delimiter'] = v.delimiter || ',';
                delimited['has_header'] = !!v.hasHeader;
                const skip = Number(v.skipHeaderLines);
                setOrDelete(delimited, 'skip_header_lines', skip > 0 ? skip : undefined);
                setOrDelete(delimited, 'quote', v.quote || undefined);
                setOrDelete(delimited, 'escape', v.escape || undefined);
                setOrDelete(block, 'encoding', v.encoding || undefined);

                // Write back in the shape it was stored in — an edit here is not a migration.
                if (this.grammarNested) return { ...block, delimited };
                delete block['delimited'];
                return { ...block, ...delimited };
            }
            case 'transform': {
                // form.invalid already blocks submit() when config isn't valid JSON (jsonValidator).
                const config: Record<string, unknown> = v.config?.trim() ? JSON.parse(v.config) : {};
                return { type: v.subtype, ...config };
            }
            case 'sink': {
                const out: Record<string, unknown> = { type: v.sinkKind };
                if (v.store?.trim()) out['store'] = v.store.trim();
                if (v.format) out['format'] = v.format;
                const parts = (v.partitions as string[])
                    .map((p) => p.trim())
                    .filter(Boolean)
                    .map((p) => this.sinkPartitionEntries.get(p) ?? p);
                if (parts.length) out['partitions'] = parts;
                return out;
            }
        }
    }

    submit(): void {
        if (this.grammarUnauthorable()) return;
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const content = this.buildContent();
        if (!content) return;
        const id = this.form.getRawValue().id as string;
        this.saving = true;
        const req$ = this.isEdit
            ? this.api.update(this.kind, id, content)
            : this.api.create(this.kind, { id, ...content });
        req$.subscribe({
            next: (saved) => {
                this.saving = false;
                this.toastr.success(`${this.kind} "${id}" ${this.isEdit ? 'updated' : 'created'}`);
                this.ref.close({ saved });
            },
            error: (e) => {
                this.saving = false;
                const msg =
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : e?.status === 409
                          ? `A ${this.kind} "${id}" already exists.`
                          : apiErrorMessage(e, `Could not save "${id}".`);
                this.toastr.error(msg);
                if (e?.status === 503) this.ref.close({ writesDisabled: true });
            },
        });
    }

    /** Run the saved component over the sample through the dry-run endpoints (edit mode only). */
    runTest(): void {
        const id = this.data.def?.name;
        if (!id) return;
        this.testing.set(true);
        this.testError.set(null);
        this.grammarResult.set(null);
        this.relationsResult.set(null);
        this.sinkResult.set(null);

        if (this.kind === 'grammar') {
            this.api.testGrammar(id, this.sampleText).subscribe({
                next: (r) => {
                    this.testing.set(false);
                    this.grammarResult.set(r);
                },
                error: (e) => this.failTest(e),
            });
            return;
        }
        let rows: Record<string, unknown>[];
        try {
            rows = JSON.parse(this.sampleRows);
            if (!Array.isArray(rows)) throw new Error('expected an array of rows');
        } catch (e) {
            this.testing.set(false);
            this.testError.set('Sample rows must be a JSON array of objects.');
            return;
        }
        const obs: Observable<RelationsPreview | SinkPreview> =
            this.kind === 'transform' ? this.api.testTransform(id, rows) : this.api.testSink(id, rows);
        obs.subscribe({
            next: (r) => {
                this.testing.set(false);
                if (this.kind === 'sink') this.sinkResult.set(r as SinkPreview);
                else this.relationsResult.set(r as RelationsPreview);
            },
            error: (e) => this.failTest(e),
        });
    }

    private failTest(e: unknown): void {
        this.testing.set(false);
        this.testError.set(apiErrorMessage(e, 'Test failed.'));
    }

    /** Column names across a preview's sample rows (for a small results table header). */
    columnsOf(rows: Record<string, unknown>[]): string[] {
        const cols = new Set<string>();
        for (const r of rows) for (const k of Object.keys(r)) cols.add(k);
        return [...cols];
    }
}

// ── content readers (defensive against the loosely-typed .toon content map) ────────────────────────────

function str(v: unknown, dflt: string): string {
    return v == null ? dflt : String(v);
}
function num(v: unknown, dflt: number): number {
    const n = Number(v);
    return Number.isFinite(n) ? n : dflt;
}
/** An optional key: write it, or REMOVE it when the operator clears the field. */
function setOrDelete(target: Record<string, unknown>, key: string, value: unknown): void {
    if (value === undefined) delete target[key];
    else target[key] = value;
}
/** A `.toon` sub-block read as a map — `{}` for anything that is not one. */
function obj(v: unknown): Record<string, unknown> {
    return v !== null && typeof v === 'object' && !Array.isArray(v) ? { ...(v as Record<string, unknown>) } : {};
}
/** The chip label for a `partitions:` entry — a bare column name, or the `column` of a `{column, source}` map. */
function partitionLabel(p: unknown): string {
    return (p && typeof p === 'object' ? String((p as { column?: unknown }).column ?? '') : String(p ?? '')).trim();
}
function partitionsOf(c: Record<string, unknown>): string[] {
    const src = c['partitions'];
    if (!Array.isArray(src)) return [];
    return src.map(partitionLabel).filter(Boolean);
}
