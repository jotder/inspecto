import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ianaTimeZones } from './time-zones';

/**
 * The DuckDB scalar types the engine honours — the mirror of `SchemaFieldTypes.names()` (Java).
 *
 * <p>⚠ **This list and the engine's must stay identical.** It used to be four entries, because
 * `TransformCompiler.direct()`'s `default` branch emitted a column UNCAST — so a fifth option here
 * would have silently stored text. The engine now casts every type below and REFUSES anything else
 * at config load (operator decision 2026-08-22, fail closed), which is what makes offering them
 * honest. Nested (`LIST`/`STRUCT`/`MAP`), `JSON` and `INTERVAL` are excluded on both sides: a parsed
 * text token cannot become one.
 *
 * <p>`DECIMAL` is the one parameterised entry — picking it authors `DECIMAL(p,s)` via the precision
 * and scale inputs the Type cell reveals.
 */
export const SCHEMA_TYPES = [
    'VARCHAR',
    'BOOLEAN',
    'TINYINT',
    'SMALLINT',
    'INTEGER',
    'BIGINT',
    'HUGEINT',
    'UTINYINT',
    'USMALLINT',
    'UINTEGER',
    'UBIGINT',
    'FLOAT',
    'DOUBLE',
    'DECIMAL',
    'DATE',
    'TIME',
    'TIMESTAMP',
    'TIMESTAMPTZ',
    'UUID',
    'BLOB',
] as const;

/** DuckDB's DECIMAL limits — precision 1‥38, scale 0‥precision. Clamped, never merely validated. */
export const DECIMAL_MAX_PRECISION = 38;
/** What picking `DECIMAL` from the menu authors before the operator adjusts it. */
export const DECIMAL_DEFAULT = 'DECIMAL(18,2)';

/** `DECIMAL(18,2)` → `DECIMAL`; every other type is its own base. Lets one icon/hint/filter entry
 *  serve every parameterisation. */
export function baseSchemaType(type: string | null | undefined): string {
    const t = (type ?? '').trim().toUpperCase();
    const paren = t.indexOf('(');
    return paren > 0 ? t.slice(0, paren) : t;
}

/** Precision/scale of a `DECIMAL(p,s)`, or the defaults for anything else. */
export function decimalParts(type: string | null | undefined): { precision: number; scale: number } {
    const m = /^DECIMAL\(\s*(\d+)\s*,\s*(\d+)\s*\)$/.exec((type ?? '').trim().toUpperCase());
    return m ? { precision: Number(m[1]), scale: Number(m[2]) } : { precision: 18, scale: 2 };
}

/** Build a valid `DECIMAL(p,s)`, clamping both into DuckDB's range so an out-of-range value can
 *  never reach the engine's fail-closed gate. */
export function decimalType(precision: number, scale: number): string {
    const p = Math.min(Math.max(Math.trunc(precision) || 1, 1), DECIMAL_MAX_PRECISION);
    const s = Math.min(Math.max(Math.trunc(scale) || 0, 0), p);
    return `DECIMAL(${p},${s})`;
}

/** Data-format icon + plain-words hint per type — the Type cell renders these so a 500-column
 *  table scans visually. Keyed by BASE type, so every `DECIMAL(p,s)` shares one entry. */
export const TYPE_META: Record<string, { icon: string; hint: string }> = {
    VARCHAR: { icon: 'heroicons_outline:bars-3-bottom-left', hint: 'Text' },
    BOOLEAN: { icon: 'heroicons_outline:check-circle', hint: 'True / false' },
    TINYINT: { icon: 'heroicons_outline:hashtag', hint: 'Whole number (8-bit)' },
    SMALLINT: { icon: 'heroicons_outline:hashtag', hint: 'Whole number (16-bit)' },
    INTEGER: { icon: 'heroicons_outline:hashtag', hint: 'Whole number (32-bit)' },
    BIGINT: { icon: 'heroicons_outline:hashtag', hint: 'Whole number (64-bit)' },
    HUGEINT: { icon: 'heroicons_outline:hashtag', hint: 'Whole number (128-bit)' },
    UTINYINT: { icon: 'heroicons_outline:hashtag', hint: 'Whole number, unsigned (8-bit)' },
    USMALLINT: { icon: 'heroicons_outline:hashtag', hint: 'Whole number, unsigned (16-bit)' },
    UINTEGER: { icon: 'heroicons_outline:hashtag', hint: 'Whole number, unsigned (32-bit)' },
    UBIGINT: { icon: 'heroicons_outline:hashtag', hint: 'Whole number, unsigned (64-bit)' },
    FLOAT: { icon: 'heroicons_outline:hashtag', hint: 'Number (single precision)' },
    DOUBLE: { icon: 'heroicons_outline:hashtag', hint: 'Number (floating point)' },
    DECIMAL: { icon: 'heroicons_outline:banknotes', hint: 'Exact decimal — money, rates' },
    DATE: { icon: 'heroicons_outline:calendar', hint: 'Date' },
    TIME: { icon: 'heroicons_outline:clock', hint: 'Time of day' },
    TIMESTAMP: { icon: 'heroicons_outline:clock', hint: 'Date & time' },
    TIMESTAMPTZ: { icon: 'heroicons_outline:globe-alt', hint: 'Date & time with zone' },
    UUID: { icon: 'heroicons_outline:finger-print', hint: 'UUID' },
    BLOB: { icon: 'heroicons_outline:cube', hint: 'Raw bytes' },
};

const IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * A server-inferred type (`SchemaSuggest`) mapped onto the grid's vocabulary.
 *
 * <p>Since 2026-08-22 the grid offers every type the engine casts, so an inferred type is normally
 * kept VERBATIM. 🔴 It used to collapse `BIGINT` → `DOUBLE` — honest while the engine cast only
 * DOUBLE, but lossy above 2⁵³, which is exactly the range long numeric identifiers live in. That
 * narrowing is gone.
 *
 * <p>An unrecognised type still falls back to `VARCHAR` rather than being passed through: the engine
 * now REFUSES an unknown type at load, so passing one through would turn a server quirk into a
 * config that will not load.
 */
export function narrowToSchemaType(serverType: string): string {
    const t = (serverType ?? '').trim().toUpperCase();
    if (!t) return 'VARCHAR';
    const base = baseSchemaType(t);
    if (base === 'DECIMAL') {
        const { precision, scale } = decimalParts(t);
        return t === 'DECIMAL' ? DECIMAL_DEFAULT : decimalType(precision, scale);
    }
    return (SCHEMA_TYPES as readonly string[]).includes(t) ? t : 'VARCHAR';
}

/** One editable row of a schema's `raw.fields[]`. `synonym` (B3, additive) is an optional unique
 *  alias; `description`/`unit`/`classification` (D1(b)) are edited on the Files & metadata tab's
 *  `<inspecto-schema-metadata-grid>` — all four are Catalog-facing metadata, never read by the ETL. */
export interface SchemaFieldRow {
    include: boolean;
    name: string;
    selector: string;
    type: string;
    synonym?: string;
    description?: string;
    unit?: string;
    classification?: string;
    /** `raw.fields[].timezone` — the zone this column's timestamps are written IN. ETL-read. */
    timezone?: string;
    /**
     * `raw.fields[].timezone_column` — a sibling column naming the zone PER ROW. Deliberately not
     * editable here (offering it beside 400 zone names in one cell invites exactly the ambiguity the
     * engine's mutual-exclusion rule exists to prevent), but carried so a hand-authored one survives
     * a save, and shown read-only on its row so the fixed-zone box cannot silently contradict it.
     */
    timezone_column?: string;
}

type SortKey = 'source' | 'name' | 'selector' | 'type';

/** A parsed column name → a valid SQL identifier (`Identifiers.validate`'s own pattern), so an
 *  auto-derived field name is register-able without hand-editing. */
export function sanitizeIdentifier(raw: string, index: number): string {
    let s = raw
        .trim()
        .toUpperCase()
        .replace(/[^A-Z0-9_]+/g, '_');
    s = s.replace(/^_+/, '').replace(/_+$/, '');
    if (/^[0-9]/.test(s)) s = `_${s}`;
    return s || `FIELD_${index}`;
}

/** `raw.fields[].selector` semantics differ by frontend (P2 recon): delimited/fixedwidth address
 *  the parsed column by its 0-based position; json/text_regex address it by the key/group name. */
export function deriveSelector(frontend: string, index: number, columnName: string): string {
    return frontend === 'delimited' || frontend === 'fixedwidth' ? String(index) : columnName;
}

/**
 * The shared schema `raw.fields[]` grid — name / source / type per column, with include flags and a
 * search·filter·sort·paginate window that keeps a 500-column feed usable.
 *
 * <p>Extracted from `onboarding/schema-mapping-pane` (definition-surface unification P4-2a-i) so the
 * Parse drawer can author an output schema too; a feature may not import a feature, the same rule that
 * moved the segments editor here in P3d slice A. Behaviour-neutral by construction — the window logic,
 * the identifier rule and the duplicate check are the originals.
 *
 * <p><b>Pure.</b> It holds no API client and no shared state: the host seeds `[rows]`, calls
 * {@link validate} then {@link value} on submit, and owns every write. Problems surface through
 * {@link problem} for the host to render, the segments editor's contract — a shared grid must not
 * decide how a host reports an error.
 */
@Component({
    selector: 'inspecto-schema-fields-editor',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatMenuModule,
        MatPaginatorModule,
        MatSelectModule,
        MatTooltipModule,
    ],
    templateUrl: './schema-fields-editor.component.html',
})
export class InspectoSchemaFieldsEditorComponent {
    private fb = inject(FormBuilder);

    /**
     * The rows to edit. Rebuilt on **reference change**, not on every change detection — a host that
     * rebuilds this array inline would wipe the edits under the user's cursor, so hosts hold it in a
     * signal. (The same identity discipline P2 landed on for re-seeding a pane.)
     */
    readonly rows = input<SchemaFieldRow[]>([]);

    /**
     * Data-types Auto mode (§4.4): the type cells render read-only inferred icons — the menu is
     * disabled with a tooltip pointing at the Declared toggle. The host owns the mode.
     */
    readonly autoTypes = input(false);

    /**
     * Name-based frontends (json / text_regex) address parsed columns by key/group NAME, so the
     * `#` column shows the row's position and a separate read-only Selector column shows the
     * selector. Positional frontends (delimited / fixedwidth) leave this false: `#` IS the selector.
     */
    readonly nameBasedSelectors = input(false);

    readonly types = SCHEMA_TYPES;
    protected readonly typeMeta = TYPE_META;
    protected readonly decimalMaxPrecision = DECIMAL_MAX_PRECISION;

    readonly form: FormGroup = this.fb.group({ fields: this.fb.array<FormGroup>([]) });
    get fieldRows(): FormArray<FormGroup> {
        return this.form.controls['fields'] as FormArray<FormGroup>;
    }

    /** The included rows' names — hosts bind partition-key style pickers to this. */
    readonly includedNames = signal<string[]>([]);
    /** Why {@link validate} last refused, or `null`. The host renders it. */
    readonly problem = signal<string | null>(null);

    /** Keyed by BASE type, so `DECIMAL(18,2)` and `DECIMAL(38,10)` share one glyph. */
    typeIcon(t: string | null | undefined): string {
        return (TYPE_META[baseSchemaType(t)] ?? TYPE_META['VARCHAR']).icon;
    }

    // ── View window over the FormArray ──────────────────────────────────────────
    // The form stays the single source of truth; these signals only choose WHICH rows render.
    // Deliberately NOT reactive to keystrokes in the name cells — re-sorting/re-filtering while
    // the user types would make rows jump under the caret. The window recomputes on search/
    // filter/sort/page changes and on structural rebuilds (a new [rows] reference).
    readonly search = signal('');
    readonly typeFilter = signal<string>('all');
    readonly sortKey = signal<SortKey>('source');
    readonly sortDir = signal<1 | -1>(1);
    readonly pageIndex = signal(0);
    readonly pageSize = signal(50);
    private readonly structureVersion = signal(0);

    readonly totalCount = computed(() => {
        this.structureVersion();
        return this.fieldRows.length;
    });

    readonly filteredEntries = computed<{ group: FormGroup; index: number }[]>(() => {
        this.structureVersion();
        const q = this.search().trim().toUpperCase();
        const tf = this.typeFilter();
        const key = this.sortKey();
        const dir = this.sortDir();
        let entries = this.fieldRows.controls.map((group, index) => ({
            group,
            index,
            v: group.getRawValue() as SchemaFieldRow,
        }));
        if (q)
            entries = entries.filter(
                ({ v }) =>
                    v.name.toUpperCase().includes(q) ||
                    String(v.selector).toUpperCase().includes(q) ||
                    String(v.synonym ?? '')
                        .toUpperCase()
                        .includes(q),
            );
        // Compare BASE types so the DECIMAL filter matches every DECIMAL(p,s), not one spelling.
        if (tf !== 'all') entries = entries.filter(({ v }) => baseSchemaType(v.type) === tf);
        if (key === 'source') return dir === 1 ? entries : [...entries].reverse();
        return [...entries].sort((a, b) => {
            const av = String(a.v[key] ?? '');
            const bv = String(b.v[key] ?? '');
            const an = Number(av);
            const bn = Number(bv);
            // Numeric when both sides are numbers — delimited selectors are positions, and
            // "10" must not sort before "2".
            const c = av !== '' && bv !== '' && !Number.isNaN(an) && !Number.isNaN(bn) ? an - bn : av.localeCompare(bv);
            return (c !== 0 ? c : a.index - b.index) * dir;
        });
    });

    readonly pagedEntries = computed(() => {
        const start = this.pageIndex() * this.pageSize();
        return this.filteredEntries().slice(start, start + this.pageSize());
    });

    /** Header master-checkbox state over the FILTERED set (not just the visible page). */
    readonly visibleIncludeState = computed<'all' | 'none' | 'some'>(() => {
        this.includedNames(); // any row edit re-evaluates
        const entries = this.filteredEntries();
        if (entries.length === 0) return 'none';
        const on = entries.filter((e) => (e.group.getRawValue() as SchemaFieldRow).include).length;
        return on === 0 ? 'none' : on === entries.length ? 'all' : 'some';
    });

    constructor() {
        effect(() => {
            const seed = this.rows();
            this.fieldRows.clear();
            for (const r of seed) this.addRow(r);
            this.structureVersion.update((v) => v + 1);
            this.syncIncludedNames();
            // A freshly seeded grid is pristine: the host has applied nothing yet.
            this.form.markAsPristine();
            this.problem.set(null);
        });
    }

    setSearch(q: string): void {
        this.search.set(q);
        this.pageIndex.set(0);
    }

    setTypeFilter(t: string): void {
        this.typeFilter.set(t);
        this.pageIndex.set(0);
    }

    sortBy(key: Exclude<SortKey, 'source'>): void {
        if (this.sortKey() === key) {
            this.sortDir.update((d) => (d === 1 ? -1 : 1));
        } else {
            this.sortKey.set(key);
            this.sortDir.set(1);
        }
        this.pageIndex.set(0);
    }

    ariaSort(key: SortKey): 'ascending' | 'descending' | null {
        if (this.sortKey() !== key) return null;
        return this.sortDir() === 1 ? 'ascending' : 'descending';
    }

    onPage(e: PageEvent): void {
        this.pageIndex.set(e.pageIndex);
        this.pageSize.set(e.pageSize);
    }

    /** Include/exclude every row matching the current search + type filter, across all pages. */
    toggleAllVisible(checked: boolean): void {
        for (const e of this.filteredEntries()) e.group.get('include')?.setValue(checked, { emitEvent: false });
        this.form.markAsDirty();
        this.syncIncludedNames();
    }

    private addRow(row: SchemaFieldRow): void {
        const g = this.fb.group({
            include: [row.include],
            name: [row.name, [Validators.required, Validators.pattern(IDENTIFIER_RE)]],
            selector: [{ value: row.selector, disabled: true }],
            type: [row.type],
            synonym: [row.synonym ?? '', [Validators.pattern(IDENTIFIER_RE)]],
            timezone: [row.timezone ?? ''],
        });
        g.valueChanges.subscribe(() => this.syncIncludedNames());
        this.fieldRows.push(g);
    }

    /** Pick a type from the icon menu (§4.3 col ③). DECIMAL needs parameters, so it lands on the
     *  default and the Type cell reveals precision/scale inputs for that row. */
    setType(group: FormGroup, type: string): void {
        const c = group.get('type');
        c?.setValue(type === 'DECIMAL' ? DECIMAL_DEFAULT : type);
        c?.markAsDirty();
        this.form.markAsDirty();
    }

    typeName(group: FormGroup): string {
        return String(group.get('type')?.value ?? 'VARCHAR');
    }

    /** Whether this row's type is a `DECIMAL(p,s)` — the one type with parameters to edit. */
    isDecimal(group: FormGroup): boolean {
        return baseSchemaType(this.typeName(group)) === 'DECIMAL';
    }

    /**
     * Whether this row carries an instant, i.e. whether a source zone means anything for it.
     *
     * <p>⛔ `DATE` is excluded on purpose, matching the engine: a date has no instant, so shifting it
     * would move a calendar day across midnight under any negative offset.
     */
    isTemporal(group: FormGroup): boolean {
        const t = baseSchemaType(this.typeName(group));
        return t === 'TIMESTAMP' || t === 'TIMESTAMPTZ';
    }

    /** A row whose zone comes from a sibling column per row — read-only here, never overwritten. */
    zoneColumnOf(group: FormGroup, index: number): string {
        return String(this.rows()[index]?.timezone_column ?? '');
    }

    /**
     * 🔴 A `TIMESTAMPTZ` with no zone anywhere is REFUSED by the engine at config load — it cannot be
     * resolved against anything but the server's own zone, so the same file would import differently
     * on different machines. Flagged per row here because the pipeline-level default lives on another
     * tab: this hints, the server decides.
     */
    needsZone(group: FormGroup, index: number): boolean {
        return (
            baseSchemaType(this.typeName(group)) === 'TIMESTAMPTZ' &&
            !String(group.get('timezone')?.value ?? '').trim() &&
            !this.zoneColumnOf(group, index)
        );
    }

    /**
     * Whether any row carries an instant — the Source zone column renders only then, so a schema with
     * no timestamps is exactly as wide as before.
     *
     * <p>⚠ Depends on `includedNames()` as well as `structureVersion()`: retyping a column fires
     * `valueChanges` (and therefore `syncIncludedNames`) but never bumps the structure version, so a
     * version-only dependency would leave the column hidden until the next add/remove.
     */
    readonly anyTemporal = computed(() => {
        this.structureVersion();
        this.includedNames();
        return this.fieldRows.controls.some((g) => this.isTemporal(g as FormGroup));
    });

    /** The zone vocabulary offered by the cell's datalist. */
    readonly zoneOptions = ianaTimeZones();

    decimalPrecision(group: FormGroup): number {
        return decimalParts(this.typeName(group)).precision;
    }

    decimalScale(group: FormGroup): number {
        return decimalParts(this.typeName(group)).scale;
    }

    /** Re-author `DECIMAL(p,s)` from the inputs, clamped into DuckDB's range (precision 1‥38, scale
     *  0‥precision) so an out-of-range value never reaches the engine's fail-closed gate. */
    setDecimal(group: FormGroup, precision: number | string, scale: number | string): void {
        const c = group.get('type');
        c?.setValue(decimalType(Number(precision), Number(scale)));
        c?.markAsDirty();
        this.form.markAsDirty();
    }

    private syncIncludedNames(): void {
        this.includedNames.set(
            this.fieldRows.controls
                .map((g) => g.getRawValue() as SchemaFieldRow)
                .filter((r) => r.include)
                .map((r) => r.name.trim()),
        );
    }

    /** Clear the view filters and jump to the page holding the given row — with 500 columns a
     *  problem row hidden by a filter or on another page would block submit with nothing visibly
     *  wrong on screen. */
    private revealRow(index: number): void {
        if (index < 0) return;
        this.search.set('');
        this.typeFilter.set('all');
        this.sortKey.set('source');
        this.sortDir.set(1);
        this.pageIndex.set(Math.floor(index / this.pageSize()));
    }

    /**
     * Whether the grid can produce a schema: every name a valid identifier, at least one row included,
     * and no duplicate names. Reveals the offending row and sets {@link problem}; the host renders it.
     */
    validate(): boolean {
        this.problem.set(null);
        if (this.fieldRows.invalid) {
            this.fieldRows.controls.forEach((g) => g.markAllAsTouched());
            const bad = this.fieldRows.controls.findIndex((g) => g.invalid);
            this.revealRow(bad);
            const badName = String(this.fieldRows.at(bad)?.get('name')?.value ?? '').trim();
            const what = this.fieldRows.at(bad)?.get('synonym')?.invalid ? 'synonyms' : 'names';
            this.problem.set(
                `Field ${bad + 1}${badName ? ` ("${badName}")` : ''}: ${what} must start with a letter or _ and use only letters, digits, _.`,
            );
            return false;
        }
        const included = this.fieldRows.controls
            .map((g, index) => ({ index, v: g.getRawValue() as SchemaFieldRow }))
            .filter((e) => e.v.include);
        if (included.length === 0) {
            this.problem.set('Include at least one field.');
            return false;
        }
        const seen = new Set<string>();
        for (const e of included) {
            const n = e.v.name.trim();
            if (seen.has(n)) {
                this.revealRow(e.index);
                this.problem.set(`Duplicate field name "${n}" — names must be unique.`);
                return false;
            }
            seen.add(n);
        }
        // D3: a synonym must be unique across synonyms ∪ column names — a lookup resolving a
        // synonym must never be ambiguous with another column. `seen` already holds the names.
        for (const e of included) {
            const s = String(e.v.synonym ?? '').trim();
            if (!s) continue;
            if (seen.has(s)) {
                this.revealRow(e.index);
                const c = this.fieldRows.at(e.index)?.get('synonym');
                c?.setErrors({ duplicate: true });
                c?.markAsTouched();
                this.problem.set(`Duplicate synonym "${s}" — synonyms must be unique across synonyms and names.`);
                return false;
            }
            seen.add(s);
        }
        return true;
    }

    /**
     * The included rows, trimmed — call {@link validate} first. An empty synonym or zone is dropped.
     *
     * <p>⚠ Each row is rebuilt over its SEED, not over the form alone. The form holds five controls
     * while a `raw.fields[]` entry legitimately carries more (`description`/`unit`/`classification`
     * from the metadata grid, a hand-authored `timezone_column`), so emitting `getRawValue()` alone
     * would silently drop every key this grid does not model — the data-loss shape this repo keeps
     * meeting. Seed and controls are index-aligned because the seeding effect pushes one row per
     * `rows()` entry in order.
     */
    value(): SchemaFieldRow[] {
        const seed = this.rows();
        return this.fieldRows.controls
            .map((g, i) => ({ seed: seed[i], v: g.getRawValue() as SchemaFieldRow }))
            .filter(({ v }) => v.include)
            .map(({ seed: original, v }) => {
                const synonym = String(v.synonym ?? '').trim();
                const zone = String(v.timezone ?? '').trim();
                const out: SchemaFieldRow = { ...(original ?? {}), ...v, name: v.name.trim() };
                if (synonym) out.synonym = synonym;
                else delete out.synonym;
                // A blank zone means "inherit the pipeline's source_timezone, else wall clock" — the
                // engine's own no-key behaviour — so it is dropped rather than written as ''.
                if (zone) out.timezone = zone;
                else delete out.timezone;
                return out;
            });
    }

    markPristine(): void {
        this.form.markAsPristine();
    }
}
