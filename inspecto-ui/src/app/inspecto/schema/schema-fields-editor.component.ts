import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

/** The four types `TransformCompiler.direct()` actually TRY_CASTs — everything else is stored as
 *  text (honesty guard: no type offered here implies rigor the engine does not apply). */
export const SCHEMA_TYPES = ['VARCHAR', 'DOUBLE', 'DATE', 'TIMESTAMP'] as const;

/** Data-format icon + plain-words hint per type — the Type cell renders these so a 500-column
 *  table scans visually. Only the four honest types exist, so the map is closed. */
export const TYPE_META: Record<string, { icon: string; hint: string }> = {
    VARCHAR: { icon: 'heroicons_outline:bars-3-bottom-left', hint: 'Text' },
    DOUBLE: { icon: 'heroicons_outline:hashtag', hint: 'Number (floating point)' },
    DATE: { icon: 'heroicons_outline:calendar', hint: 'Date' },
    TIMESTAMP: { icon: 'heroicons_outline:clock', hint: 'Date & time' },
};

const IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** One editable row of a schema's `raw.fields[]`. */
export interface SchemaFieldRow {
    include: boolean;
    name: string;
    selector: string;
    type: string;
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
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
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

    readonly types = SCHEMA_TYPES;
    protected readonly typeMeta = TYPE_META;

    readonly form: FormGroup = this.fb.group({ fields: this.fb.array<FormGroup>([]) });
    get fieldRows(): FormArray<FormGroup> {
        return this.form.controls['fields'] as FormArray<FormGroup>;
    }

    /** The included rows' names — hosts bind partition-key style pickers to this. */
    readonly includedNames = signal<string[]>([]);
    /** Why {@link validate} last refused, or `null`. The host renders it. */
    readonly problem = signal<string | null>(null);

    typeIcon(t: string | null | undefined): string {
        return (TYPE_META[t ?? ''] ?? TYPE_META['VARCHAR']).icon;
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
                ({ v }) => v.name.toUpperCase().includes(q) || String(v.selector).toUpperCase().includes(q),
            );
        if (tf !== 'all') entries = entries.filter(({ v }) => v.type === tf);
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
        });
        g.valueChanges.subscribe(() => this.syncIncludedNames());
        this.fieldRows.push(g);
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
            this.problem.set(
                `Field ${bad + 1}${badName ? ` ("${badName}")` : ''}: names must start with a letter or _ and use only letters, digits, _.`,
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
        return true;
    }

    /** The included rows, trimmed — call {@link validate} first. */
    value(): SchemaFieldRow[] {
        return this.fieldRows.controls
            .map((g) => g.getRawValue() as SchemaFieldRow)
            .filter((r) => r.include)
            .map((r) => ({ ...r, name: r.name.trim() }));
    }

    markPristine(): void {
        this.form.markAsPristine();
    }
}
