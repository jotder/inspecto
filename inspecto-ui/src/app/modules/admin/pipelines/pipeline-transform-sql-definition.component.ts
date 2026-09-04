import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AuthoredNode, ComponentsService, RelationsPreview } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { StepPreviewResultComponent } from 'app/inspecto/components/step-preview-result.component';
import { SqlCodemirrorComponent } from 'app/inspecto/data-table/sql/sql-codemirror.component';
import {
    CompiledField,
    SqlField,
    applyFunction,
    compileFields,
    generateSql,
    newFieldId,
    readFields,
    seedFields,
} from './pipeline-transform-sql';
import { SqlFunction, sqlFunctionsByCategory, usesSource } from './sql-functions';

/** How many rows an inline "Try it on the sample" test posts (mirrors the config pane's cap). */
const MAX_TEST_ROWS = 50;

/** Page sizes the operator pinned on 2026-09-03 for tables that can run past 600 columns. */
export const PAGE_SIZES = [10, 20, 100] as const;

/** The view-only lenses over the grid. They never change what is written — only what is on screen. */
export type FieldFilter = 'all' | 'changed' | 'calculated' | 'problems';

const FILTER_LABELS: Record<FieldFilter, string> = {
    all: 'All',
    changed: 'Changed',
    calculated: 'Calculated',
    problems: 'Needs attention',
};

/** One grid row as the template consumes it: the field, its compilation, and its display-only extras. */
export interface FieldRow extends CompiledField {
    /** 1-based position in the FULL field list — stable across search, filter and paging. */
    readonly seq: number;
    readonly definition: SqlFunction | undefined;
    /** True when this row is not a straight passthrough of an identically-named column. */
    readonly changed: boolean;
    readonly sample: string;
    readonly outType: string;
}

/**
 * The **Fields-grid Transform pane** for `transform.sql`.
 *
 * <p><b>Why a grid.</b> The Step shipped SQL-only on 2026-09-04 and the operator rejected that the same
 * day: a raw SQL box is not an authoring surface for a non-technical user, and the legacy `transform.map`
 * rule grid — four constants, two of which pack arguments into a delimited string — is not a mapping
 * model either. This pane restores the fields grid with a real {@link SqlFunction} catalog: one row per
 * output column, a function per row, and a form control per declared parameter, with the row's source
 * column bound to the function's `{source}` automatically.
 *
 * <p><b>Persisted shape.</b> `{ sql, fields }`. The engine declares and reads only `sql`; `fields` rides
 * the `steps:` chain opaquely and exists so reopening rebuilds the grid exactly. Nothing ever parses SQL
 * back into rows, so a node whose `sql` was hand-written (or authored by the SQL-only pane) opens in
 * {@link sqlOnly} mode and says so rather than inventing a grid that would silently rewrite it.
 *
 * <p><b>Wide tables.</b> Search, view-only filters with counts, a `#` that is the position in the full
 * list, and paging at 10 / 20 / 100 — the operator's 2026-09-03 requirement for 600+ column sources.
 */
@Component({
    selector: 'app-pipeline-transform-sql-definition',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatIconModule,
        StepPreviewResultComponent,
        InspectoAlertComponent,
        SqlCodemirrorComponent,
    ],
    templateUrl: './pipeline-transform-sql-definition.component.html',
})
export class PipelineTransformSqlDefinitionComponent {
    private components = inject(ComponentsService);

    readonly node = input.required<AuthoredNode>();
    /** The rows the tab's sample thread parsed — seeds a NEW step and feeds "Try it on the sample". */
    readonly sampleRows = input<Record<string, unknown>[] | undefined>(undefined);
    /** Upstream column names from the upstream schema or step, allowing authoring without parsed sample rows. */
    readonly upstreamColumnsInput = input<string[] | undefined>(undefined, { alias: 'upstreamColumns' });
    /**
     * The DECLARED type per upstream column, keyed by name. ⚠ Load-bearing for the zero-row `DESCRIBE`:
     * assuming VARCHAR everywhere made DuckDB REFUSE valid SQL (`AMOUNT * 2` over a declared DOUBLE →
     * "No function matches … *(VARCHAR, INTEGER)") and that refusal blocked Apply.
     */
    readonly upstreamColumnTypes = input<Record<string, string>>({});

    readonly applied = output<AuthoredNode>();
    readonly dirtyChange = output<boolean>();

    // ── the authored model ──────────────────────────────────────────────────────────────────────────
    readonly fields = signal<SqlField[]>([]);
    readonly leftOut = signal<string[]>([]);
    /** Set when the node carries `sql` we did not generate: the grid cannot be rebuilt from it. */
    readonly sqlOnly = signal(false);
    /** The hand-written SQL shown in `sqlOnly` mode. */
    readonly storedSql = signal('');

    readonly generatedSql = computed(() => (this.sqlOnly() ? this.storedSql() : generateSql(this.fields())));

    /** What was loaded, so dirty is a real comparison rather than "something rendered". */
    private loaded = '';
    private lastDirty = false;
    private seededFor: string | null = null;
    private describeTimer: ReturnType<typeof setTimeout> | null = null;
    private readonly destroyRef = inject(DestroyRef);

    // ── live schema derivation and binder error detection ──────────────────────────────────────────
    readonly isDeriving = signal(false);
    readonly binderError = signal<string | null>(null);
    readonly derivedColumnTypes = signal<Record<string, string>>({});

    // ── view state: search, filter, paging. None of this is persisted or written. ───────────────────
    readonly query = signal('');
    /**
     * D10 (2026-09-04): a Step that changes something opens on CHANGED, not ALL — the grid's model is
     * *everything passes through; show me what I changed*, so on a wide feed that is a few rows to read
     * instead of hundreds, with {@link passthroughNote} stating the rest rather than listing them.
     * {@link seedFrom} picks the opening value; a Step that changes nothing opens on ALL, because an
     * empty table is a worse first screen than the fields themselves.
     *
     * <p>⚠ Worth keeping in view: this inverts the argument for having a grid at all. The grid is
     * O(n) in fields where SQL is O(1) in fields and O(k) in changes, so for wide data `SELECT *`
     * with three overrides is genuinely the simpler surface. Anything that forces the grid to
     * enumerate every field is pushing it back toward the shape that does not scale.
     */
    readonly filter = signal<FieldFilter>('changed');
    readonly pageSize = signal<number>(PAGE_SIZES[0]);
    readonly page = signal(0);
    readonly pageSizes = PAGE_SIZES;
    readonly functionGroups = sqlFunctionsByCategory();

    /** The upstream columns a row may read, from upstreamColumns input or the parsed sample. */
    readonly upstreamColumns = computed(() => {
        const fromInput = this.upstreamColumnsInput();
        if (fromInput && fromInput.length > 0) return fromInput;
        return Object.keys(this.sampleRows()?.[0] ?? {});
    });

    /** Every row, compiled, with its stable `#`. This is the list search and filters are lenses over. */
    readonly allRows = computed<FieldRow[]>(() => {
        const compiled = compileFields(this.fields());
        const previewTypes = this.previewTypesByName();
        const derived = this.derivedColumnTypes();
        const sample = this.sampleRows()?.[0] ?? {};
        const previewRow = (this.preview()?.relations?.[0]?.rows?.[0] ?? {}) as Record<string, unknown>;
        return compiled.map((c, i) => {
            const name = c.field.name;
            const previewed = previewRow[name];
            const raw = c.field.from ? sample[c.field.from] : undefined;
            const shown = previewed !== undefined && previewed !== null ? previewed : raw;
            return {
                ...c,
                seq: i + 1,
                changed: c.field.fn !== 'keep' || c.field.name !== c.field.from,
                sample: shown === undefined || shown === null ? '' : String(shown),
                outType: derived[name] || previewTypes[name] || '',
            };
        });
    });

    readonly counts = computed(() => {
        const rows = this.allRows();
        return {
            all: rows.length,
            changed: rows.filter((r) => r.changed).length,
            calculated: rows.filter((r) => r.definition && !usesSource(r.definition)).length,
            problems: rows.filter((r) => !!r.problem).length,
        };
    });

    readonly filterChips = computed(() =>
        (Object.keys(FILTER_LABELS) as FieldFilter[]).map((key) => ({
            key,
            label: FILTER_LABELS[key],
            count: this.counts()[key],
            active: this.filter() === key,
        })),
    );

    /**
     * The "N others pass through unchanged" line, or `null` when there is nothing to say — shown only
     * on the unsearched `changed` view, where the rows NOT on screen are exactly the untouched ones.
     */
    readonly passthroughNote = computed<string | null>(() => {
        if (this.filter() !== 'changed' || this.query().trim()) return null;
        const untouched = this.counts().all - this.counts().changed;
        if (untouched <= 0) return null;
        const plural = untouched === 1 ? 'field passes' : 'fields pass';
        return `${untouched} other ${plural} through unchanged — nothing to do for them.`;
    });

    /** Search over the output name and the source column; then the active filter. Order is preserved. */
    readonly visibleRows = computed<FieldRow[]>(() => {
        const q = this.query().trim().toLowerCase();
        const f = this.filter();
        return this.allRows().filter((r) => {
            if (q && !r.field.name.toLowerCase().includes(q) && !r.field.from.toLowerCase().includes(q)) return false;
            if (f === 'changed') return r.changed;
            if (f === 'calculated') return !!r.definition && !usesSource(r.definition);
            if (f === 'problems') return !!r.problem;
            return true;
        });
    });

    readonly pageCount = computed(() => Math.max(1, Math.ceil(this.visibleRows().length / this.pageSize())));
    /** Clamped, so deleting rows or narrowing a filter can never strand the view on an empty page. */
    readonly currentPage = computed(() => Math.min(this.page(), this.pageCount() - 1));
    readonly pagedRows = computed(() => {
        const start = this.currentPage() * this.pageSize();
        return this.visibleRows().slice(start, start + this.pageSize());
    });
    readonly pageLabel = computed(() => {
        const total = this.visibleRows().length;
        if (!total) return '0 of 0';
        const start = this.currentPage() * this.pageSize();
        return `${start + 1}–${start + this.pagedRows().length} of ${total}`;
    });
    readonly showing = computed(() => {
        const shown = this.visibleRows().length;
        const total = this.allRows().length;
        return shown === total ? `${total} fields` : `Showing ${shown} of ${total} fields`;
    });

    readonly summary = computed(() => {
        const c = this.counts();
        const parts = [`${c.all} fields out`, `${c.changed} changed`, `${c.calculated} calculated`];
        if (this.leftOut().length) parts.push(`${this.leftOut().length} left out`);
        if (c.problems) parts.push(`${c.problems} need attention`);
        return parts.join(' · ');
    });

    /**
     * Apply is refused while any row cannot compile or the SQL fails to bind — an incomplete row must
     * never save silently.
     *
     * <p>⚠ `sqlOnly` is NOT a refusal. It was, while the hand-written SQL was read-only; once CodeMirror
     * made it editable the drawer's Apply armed on the first keystroke and `submit()` then returned
     * early, so editing hand-written SQL saved NOTHING and said nothing. The binder check above already
     * guards what is typed there.
     */
    readonly canApply = computed(() => this.counts().problems === 0 && !this.binderError());

    // ── preview ─────────────────────────────────────────────────────────────────────────────────────
    readonly preview = signal<RelationsPreview | null>(null);
    readonly previewPending = signal(false);
    readonly previewError = signal<string | null>(null);
    readonly showSql = signal(false);

    private previewTypesByName = computed<Record<string, string>>(() => {
        const out: Record<string, string> = {};
        for (const c of this.preview()?.relations?.[0]?.columnTypes ?? []) out[c.name] = c.type;
        return out;
    });

    constructor() {
        effect(() => {
            const n = this.node();
            if (this.seededFor === n.id) return;
            this.seededFor = n.id;
            this.seedFrom(n);
            // Seeding is not an edit: the Step opens clean, never pre-armed for an Apply nobody asked for.
            this.lastDirty = false;
            this.dirtyChange.emit(false);
        });

        effect(() => {
            const cols = this.upstreamColumns();
            // If the node was loaded with no configured fields and cols were empty at the time,
            // seed once cols become available.
            if (cols.length > 0 && this.fields().length === 0 && !this.sqlOnly() && !this.storedSql()) {
                this.fields.set(seedFields(cols));
                this.loaded = this.snapshot();
                this.lastDirty = false;
                this.dirtyChange.emit(false);
            }
        });

        // Live DuckDB zero-row schema derivation and binder error detection
        effect(() => {
            const sql = this.generatedSql();
            const cols = this.upstreamColumns();
            if (this.describeTimer) clearTimeout(this.describeTimer);
            if (!sql.trim() || cols.length === 0) {
                this.derivedColumnTypes.set({});
                this.binderError.set(null);
                this.isDeriving.set(false);
                return;
            }
            this.isDeriving.set(true);
            this.describeTimer = setTimeout(() => {
                const sample = this.sampleRows()?.[0] ?? {};
                const declared = this.upstreamColumnTypes();
                const inputCols = cols.map((c) => ({
                    // Declared first (the ETL's real type), then the sample's shape, then VARCHAR.
                    name: c,
                    type: declared[c] || (typeof sample[c] === 'number' ? 'DOUBLE' : 'VARCHAR'),
                }));
                this.components.describeTransform(inputCols, sql).subscribe({
                    next: (res) => {
                        this.isDeriving.set(false);
                        const map: Record<string, string> = {};
                        for (const c of res.columns) map[c.name] = c.type;
                        this.derivedColumnTypes.set(map);
                        this.binderError.set(null);
                    },
                    error: (e) => {
                        this.isDeriving.set(false);
                        this.derivedColumnTypes.set({});
                        // Only DuckDB's own refusal (422 from /components/transform/describe) is a
                        // statement the operator can act on — and only that one blocks Apply. Any other
                        // failure (offline, 404 on an older control plane, 503) says nothing about the
                        // SQL, so it must never lock the pane out of saving.
                        if ((e as { status?: number })?.status !== 422) {
                            this.binderError.set(null);
                            return;
                        }
                        const msg =
                            e?.error?.error?.message ??
                            e?.error?.message ??
                            e?.message ??
                            'SQL expression failed to bind.';
                        this.binderError.set(msg);
                    },
                });
            }, 300);
        });

        // A drawer closed mid-debounce must not fire a describe at a dead component.
        this.destroyRef.onDestroy(() => {
            if (this.describeTimer) clearTimeout(this.describeTimer);
        });
    }

    private seedFrom(node: AuthoredNode): void {
        const storedFields = readFields(node.config?.['fields']);
        const storedSql = typeof node.config?.['sql'] === 'string' ? (node.config['sql'] as string) : '';
        this.preview.set(null);
        this.previewError.set(null);
        this.query.set('');
        this.page.set(0);
        this.leftOut.set([]);

        if (storedFields) {
            this.sqlOnly.set(false);
            this.storedSql.set('');
            this.fields.set(storedFields);
        } else if (storedSql.trim()) {
            // Hand-written (or SQL-only-pane) SQL: forward-only means we cannot rebuild rows from it.
            this.sqlOnly.set(true);
            this.storedSql.set(storedSql);
            this.fields.set([]);
        } else {
            this.sqlOnly.set(false);
            this.storedSql.set('');
            this.fields.set(seedFields(this.upstreamColumns()));
        }
        // D10: land on Changed when there IS something changed — on a wide feed that is a few rows to
        // read instead of hundreds. ⛔ Never on a Step that changes nothing: "show me what I changed"
        // over an all-passthrough Step is an empty table, which is a worse first screen than the
        // fields themselves. The note under the toolbar carries the count either way.
        this.filter.set(this.allRows().some((r) => r.changed) ? 'changed' : 'all');
        this.loaded = this.snapshot();
    }

    /** The comparison dirty is computed from — the generated SQL plus the authored rows. */
    private snapshot(): string {
        return JSON.stringify({ sql: this.generatedSql(), fields: this.fields() });
    }

    private touched(): void {
        this.preview.set(null);
        const dirty = this.snapshot() !== this.loaded;
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    /**
     * A keystroke in the hand-written SQL editor. ⚠ It must go through {@link touched} like every row
     * edit: binding `valueChange` straight to the signal changed the value but emitted no
     * `dirtyChange`, so the drawer's Apply never armed and the edit could not be saved at all.
     */
    onSqlEdited(sql: string): void {
        this.storedSql.set(sql);
        this.touched();
    }

    // ── row edits ───────────────────────────────────────────────────────────────────────────────────

    private patch(id: string, change: (f: SqlField) => SqlField): void {
        this.fields.update((rows) => rows.map((f) => (f.id === id ? change(f) : f)));
        this.touched();
    }

    rename(id: string, name: string): void {
        this.patch(id, (f) => ({ ...f, name }));
    }

    setSource(id: string, from: string): void {
        this.patch(id, (f) => ({ ...f, from }));
    }

    setFunction(id: string, fnId: string): void {
        this.patch(id, (f) => applyFunction(f, fnId));
    }

    setArg(id: string, param: string, value: string): void {
        this.patch(id, (f) => ({ ...f, args: { ...f.args, [param]: value } }));
    }

    /** Leave a field out. Its source column is remembered so it can be put back with one click. */
    removeField(id: string): void {
        const row = this.fields().find((f) => f.id === id);
        this.fields.update((rows) => rows.filter((f) => f.id !== id));
        if (row?.from) this.leftOut.update((names) => (names.includes(row.from) ? names : [...names, row.from]));
        this.touched();
    }

    restore(column: string): void {
        this.leftOut.update((names) => names.filter((n) => n !== column));
        this.fields.update((rows) => [...rows, { id: newFieldId(), name: column, from: column, fn: 'keep', args: {} }]);
        this.touched();
    }

    addCalculated(): void {
        this.fields.update((rows) => [
            ...rows,
            { id: newFieldId(), name: '', from: '', fn: 'custom', args: { expression: '' } },
        ]);
        this.page.set(this.pageCount() - 1);
        this.touched();
    }

    moveUp(id: string): void {
        const rows = [...this.fields()];
        const idx = rows.findIndex((f) => f.id === id);
        if (idx <= 0) return;
        const temp = rows[idx];
        rows[idx] = rows[idx - 1];
        rows[idx - 1] = temp;
        this.fields.set(rows);
        this.touched();
    }

    moveDown(id: string): void {
        const rows = [...this.fields()];
        const idx = rows.findIndex((f) => f.id === id);
        if (idx < 0 || idx >= rows.length - 1) return;
        const temp = rows[idx];
        rows[idx] = rows[idx + 1];
        rows[idx + 1] = temp;
        this.fields.set(rows);
        this.touched();
    }

    /** Replace hand-written SQL with a grid seeded from the upstream columns. Explicit, never automatic. */
    startGridFromColumns(): void {
        this.sqlOnly.set(false);
        this.storedSql.set('');
        this.fields.set(seedFields(this.upstreamColumns()));
        this.touched();
    }

    // ── view controls ───────────────────────────────────────────────────────────────────────────────

    setQuery(q: string): void {
        this.query.set(q);
        this.page.set(0);
    }

    setFilter(f: FieldFilter): void {
        this.filter.set(f);
        this.page.set(0);
    }

    setPageSize(size: number): void {
        this.pageSize.set(size);
        this.page.set(0);
    }

    prevPage(): void {
        this.page.set(Math.max(0, this.currentPage() - 1));
    }

    nextPage(): void {
        this.page.set(Math.min(this.pageCount() - 1, this.currentPage() + 1));
    }

    // ── test ────────────────────────────────────────────────────────────────────────────────────────

    readonly testRows = computed(() => (this.sampleRows() ?? []).slice(0, MAX_TEST_ROWS));
    readonly canTest = computed(() => this.testRows().length > 0);

    runTest(): void {
        if (!this.canTest()) return;
        this.previewPending.set(true);
        this.previewError.set(null);
        this.components
            .previewTransform({ type: this.node().type, sql: this.generatedSql() }, this.testRows())
            .subscribe({
                next: (result) => {
                    this.previewPending.set(false);
                    this.preview.set(result);
                },
                error: (e) => {
                    this.previewPending.set(false);
                    this.preview.set(null);
                    this.previewError.set(e?.error?.error?.message ?? e?.message ?? 'The test failed.');
                },
            });
    }

    submit(): void {
        if (!this.canApply()) return;
        const n = this.node();
        // `fields` is the authoring artifact; `sql` is what the engine reads. Both are written together.
        const config: Record<string, unknown> = { sql: this.generatedSql(), fields: this.fields() };
        this.loaded = this.snapshot();
        this.lastDirty = false;
        this.dirtyChange.emit(false);
        this.applied.emit({ id: n.id, type: n.type, name: n.name, description: n.description, use: n.use, config });
    }
}
