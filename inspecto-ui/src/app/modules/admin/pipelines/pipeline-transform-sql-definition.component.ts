import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { AuthoredNode, ComponentsService, RelationsPreview } from 'app/inspecto/api';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { StepPreviewResultComponent } from 'app/inspecto/components/step-preview-result.component';
import { SCHEMA_TYPES } from 'app/inspecto/schema/schema-fields-editor.component';
import { SqlField, SqlFieldVerb, generateSql, newFieldId, seedFields } from './pipeline-transform-sql';

/** How many rows an inline "Try it on the sample" test posts (mirrors the config pane's cap). */
const MAX_TEST_ROWS = 50;

/** The five plain-language verbs (D5) offered by the "What to do" select. */
const VERB_OPTIONS: { value: SqlFieldVerb; label: string }[] = [
    { value: 'keep', label: 'Keep as it is' },
    { value: 'trim', label: 'Remove extra spaces' },
    { value: 'upper', label: 'Make UPPERCASE' },
    { value: 'cast', label: 'Change type to…' },
    { value: 'formula', label: 'Calculate…' },
];

const PAGE_SIZES = [10, 20, 100] as const;

type FilterKey = 'all' | 'changed' | 'calc' | 'text' | 'number' | 'date';

const NUMBER_TYPES = new Set([
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
]);
const DATE_TYPES = new Set(['DATE', 'TIME', 'TIMESTAMP', 'TIMESTAMPTZ']);

/** A grid row with everything the template needs already derived — the `#` is the FULL-list position. */
interface DisplayRow {
    field: SqlField;
    seq: number;
    changed: boolean;
    derivedType: string | null;
    familyKey: FilterKey;
}

/**
 * The **SQL transformer Simple/Advanced pane** (`sql-transform-v1-plan.md` B3/B4; mockup:
 * `Transform.dc.html`) — a bespoke component for `transform.sql`, deliberately NOT the generic
 * schema-form renderer: the Fields grid needs per-row verb controls, search/filter/pagination over
 * 600+ columns (D7), and the lock/unlock dance between Simple and Advanced (D6) that no declarative
 * `AttributeSpec[]` can express.
 *
 * <p><b>Persisted shape.</b> `node.config` carries `{ sql, fields? }`. `sql` is ALWAYS present — either
 * the generator's output or hand-written text. The presence of `fields` means "unlocked / Simple-
 * authored" (D6's second option); its absence means "locked / hand-written". This mirrors
 * `node-config-build.ts`'s free-form-rows idiom (unmodelled keys round-trip verbatim) without pulling
 * `transform.sql` through the generic split/build path, which has no notion of a `fields[]` authoring
 * artifact — `fields[]` is stored beside `sql`, never mirrored into the engine's own contract (B3).
 *
 * <p><b>Derived types.</b> There is no live `describe` endpoint yet (B2 is deferred — see the plan). The
 * "Comes out as" chip and the Advanced-mode schema table both come from the SAME mechanism B4 wires up:
 * `ComponentsService.previewTransform` over the tab's own sample rows, whose response already carries a
 * DESCRIBE-derived `columnTypes`. When there is no sample yet (or the preview hasn't run), the type
 * column shows a clear "pending" state rather than a fabricated type.
 */
@Component({
    selector: 'app-pipeline-transform-sql-definition',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        ChipComponent,
        InspectoAlertComponent,
        StepPreviewResultComponent,
    ],
    templateUrl: './pipeline-transform-sql-definition.component.html',
})
export class PipelineTransformSqlDefinitionComponent {
    private components = inject(ComponentsService);

    readonly node = input.required<AuthoredNode>();
    /** The rows the tab's sample thread parsed — seeds a NEW step's columns and feeds "Try it on the sample". */
    readonly sampleRows = input<Record<string, unknown>[] | undefined>(undefined);

    readonly applied = output<AuthoredNode>();
    readonly dirtyChange = output<boolean>();

    readonly schemaTypes = SCHEMA_TYPES;
    readonly verbOptions = VERB_OPTIONS;
    readonly pageSizes = PAGE_SIZES;

    readonly mode = signal<'simple' | 'advanced'>('simple');
    readonly fields = signal<SqlField[]>([]);
    readonly leftOut = signal<{ id: string; name: string; from: string }[]>([]);
    /** Non-null ⇔ locked (D6): the author typed into Advanced and this text now overrides the generator. */
    readonly handWritten = signal<string | null>(null);
    readonly query = signal('');
    readonly filter = signal<FilterKey>('all');
    readonly pageSize = signal<number>(10);
    readonly page = signal(0);

    readonly locked = computed(() => this.handWritten() !== null);
    readonly generatedSql = computed(() => generateSql(this.fields()));
    readonly sql = computed(() => this.handWritten() ?? this.generatedSql());

    // ── derived schema, from the same preview call B4 wires for "Try it on the sample" ──
    readonly preview = signal<RelationsPreview | null>(null);
    readonly previewPending = signal(false);
    readonly previewError = signal<string | null>(null);
    readonly derivedTypes = computed<Map<string, string>>(() => {
        const rel = this.preview()?.relations?.[0];
        const map = new Map<string, string>();
        for (const c of rel?.columnTypes ?? []) map.set(c.name, c.type);
        return map;
    });

    private lastDirty = false;
    private seededFor: string | null = null;

    constructor() {
        effect(() => {
            const n = this.node();
            if (this.seededFor === n.id) return;
            this.seededFor = n.id;
            this.seedFrom(n);
            this.lastDirty = false;
            this.dirtyChange.emit(false);
        });
    }

    /** Load the node's config, or seed a fresh step with one Keep row per upstream sample column. */
    private seedFrom(node: AuthoredNode): void {
        const cfg = node.config ?? {};
        const storedFields = cfg['fields'];
        if (Array.isArray(storedFields) && storedFields.length > 0) {
            this.fields.set(storedFields as SqlField[]);
            this.handWritten.set(null);
        } else if (typeof cfg['sql'] === 'string' && cfg['sql'].trim()) {
            // A stored `sql` with no `fields[]` is hand-written (D6): show it locked, in Advanced.
            this.fields.set([]);
            this.handWritten.set(cfg['sql'] as string);
            this.mode.set('advanced');
        } else {
            const upstream = Object.keys(this.sampleRows()?.[0] ?? {});
            this.fields.set(seedFields(upstream));
            this.handWritten.set(null);
        }
        this.leftOut.set([]);
        this.query.set('');
        this.filter.set('all');
        this.page.set(0);
        this.preview.set(null);
        this.previewError.set(null);
    }

    setMode(m: 'simple' | 'advanced'): void {
        this.mode.set(m);
    }

    // ── Simple grid rows ──

    /** Every field, in FULL-list order, with its `#` and derived facts — before search/filter/paging. */
    readonly allRows = computed<DisplayRow[]>(() =>
        this.fields().map((field, i) => {
            const derivedType = this.derivedTypes().get(field.name) ?? null;
            const type = field.verb === 'cast' ? field.castType || null : derivedType;
            return {
                field,
                seq: i + 1,
                changed: field.verb !== 'keep' || field.name !== field.from,
                derivedType: type,
                familyKey: familyOf(field, type),
            };
        }),
    );

    readonly filters = computed(() => {
        const rows = this.allRows();
        const defs: { key: FilterKey; label: string; test: (r: DisplayRow) => boolean }[] = [
            { key: 'all', label: 'All', test: () => true },
            { key: 'changed', label: 'Changed', test: (r) => r.changed },
            { key: 'calc', label: 'Calculated', test: (r) => r.field.verb === 'formula' },
            { key: 'text', label: 'Text', test: (r) => r.familyKey === 'text' },
            { key: 'number', label: 'Numbers', test: (r) => r.familyKey === 'number' },
            { key: 'date', label: 'Dates', test: (r) => r.familyKey === 'date' },
        ];
        return defs.map((d) => ({ ...d, count: rows.filter(d.test).length }));
    });

    readonly visibleRows = computed(() => {
        const q = this.query().trim().toLowerCase();
        const activeFilter = this.filters().find((f) => f.key === this.filter()) ?? this.filters()[0];
        return this.allRows().filter((r) => {
            const matchesQuery = !q || r.field.name.toLowerCase().includes(q) || r.field.from.toLowerCase().includes(q);
            return matchesQuery && activeFilter.test(r);
        });
    });

    readonly pageCount = computed(() => Math.max(1, Math.ceil(this.visibleRows().length / this.pageSize())));
    readonly currentPage = computed(() => Math.min(this.page(), this.pageCount() - 1));
    readonly pagedRows = computed(() => {
        const start = this.currentPage() * this.pageSize();
        return this.visibleRows().slice(start, start + this.pageSize());
    });
    readonly pageLabel = computed(() => {
        const total = this.visibleRows().length;
        if (total === 0) return '0 of 0';
        const start = this.currentPage() * this.pageSize();
        return `${start + 1}–${start + this.pagedRows().length} of ${total}`;
    });
    readonly showing = computed(() => {
        const total = this.allRows().length;
        const visible = this.visibleRows().length;
        return visible === total ? `${total} fields` : `Showing ${visible} of ${total} fields`;
    });

    readonly summary = computed(() => {
        const rows = this.allRows();
        const renamed = rows.filter((r) => r.field.verb !== 'formula' && r.field.name !== r.field.from).length;
        const tidied = rows.filter((r) => r.field.verb === 'trim' || r.field.verb === 'upper').length;
        const calc = rows.filter((r) => r.field.verb === 'formula').length;
        return `${rows.length} fields out · ${renamed} renamed · ${tidied} tidied · ${calc} calculated · ${this.leftOut().length} left out`;
    });

    setQuery(v: string): void {
        this.query.set(v);
        this.page.set(0);
    }
    setFilter(key: FilterKey): void {
        this.filter.set(key);
        this.page.set(0);
    }
    setPageSize(n: number): void {
        this.pageSize.set(n);
        this.page.set(0);
    }
    prevPage(): void {
        this.page.set(Math.max(0, this.currentPage() - 1));
    }
    nextPage(): void {
        this.page.set(Math.min(this.pageCount() - 1, this.currentPage() + 1));
    }

    private mutate(fn: (fields: SqlField[]) => SqlField[]): void {
        if (this.locked()) return;
        this.fields.set(fn(this.fields()));
        this.emitDirty();
        this.preview.set(null);
    }

    rename(id: string, name: string): void {
        this.mutate((fields) => fields.map((f) => (f.id === id ? { ...f, name } : f)));
    }
    setFrom(id: string, from: string): void {
        this.mutate((fields) => fields.map((f) => (f.id === id ? { ...f, from } : f)));
    }
    setVerb(id: string, verb: SqlFieldVerb): void {
        this.mutate((fields) =>
            fields.map((f) => {
                if (f.id !== id) return f;
                // A calculated field has no source; switching AWAY from formula needs one back.
                const from = verb !== 'formula' && !f.from ? (this.leftOut()[0]?.from ?? f.name) : f.from;
                return { ...f, verb, from };
            }),
        );
    }
    setCastType(id: string, castType: string): void {
        this.mutate((fields) => fields.map((f) => (f.id === id ? { ...f, castType } : f)));
    }
    setFormula(id: string, formula: string): void {
        this.mutate((fields) => fields.map((f) => (f.id === id ? { ...f, formula } : f)));
    }

    removeField(id: string): void {
        if (this.locked()) return;
        const field = this.fields().find((f) => f.id === id);
        this.fields.set(this.fields().filter((f) => f.id !== id));
        if (field && field.verb !== 'formula' && field.from) {
            this.leftOut.set([...this.leftOut(), { id: field.id, name: field.from, from: field.from }]);
        }
        this.emitDirty();
        this.preview.set(null);
    }

    restoreLeftOut(id: string): void {
        if (this.locked()) return;
        const entry = this.leftOut().find((l) => l.id === id);
        if (!entry) return;
        this.leftOut.set(this.leftOut().filter((l) => l.id !== id));
        this.fields.set([...this.fields(), { id: newFieldId(), name: entry.name, from: entry.from, verb: 'keep' }]);
        this.emitDirty();
        this.preview.set(null);
    }

    addCalculated(): void {
        if (this.locked()) return;
        this.fields.set([...this.fields(), { id: newFieldId(), name: 'new_field', from: '', verb: 'formula' }]);
        this.emitDirty();
        this.preview.set(null);
    }

    // ── Advanced mode / lock ──

    editSql(text: string): void {
        this.handWritten.set(text);
        this.emitDirty();
        this.preview.set(null);
    }

    /** "Start over from the table" (D6): discard the hand-written SQL and regenerate from `fields[]`. */
    startOverFromTable(): void {
        this.handWritten.set(null);
        this.emitDirty();
        this.preview.set(null);
    }

    private emitDirty(): void {
        const dirty = true;
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    // ── Test this Step / derive the output schema (B4) — both go through the same preview call ──

    readonly testRows = computed(() => (this.sampleRows() ?? []).slice(0, MAX_TEST_ROWS));
    readonly canTest = computed(() => this.testRows().length > 0);

    runTest(): void {
        if (!this.canTest()) return;
        this.previewPending.set(true);
        this.previewError.set(null);
        this.components.previewTransform({ type: this.node().type, sql: this.sql() }, this.testRows()).subscribe({
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

    /** The verb applied to the FIRST sample row's value for this field — "Sample" column, D5. */
    sampleFor(field: SqlField): string {
        const row = this.sampleRows()?.[0];
        if (!row) return '';
        if (field.verb === 'formula') return '…';
        const raw = row[field.from];
        const value = raw == null ? '' : String(raw);
        switch (field.verb) {
            case 'trim':
                return value.trim();
            case 'upper':
                return value.toUpperCase();
            default:
                return value;
        }
    }

    submit(): void {
        const n = this.node();
        const config: Record<string, unknown> = { sql: this.sql() };
        if (!this.locked()) config['fields'] = this.fields();
        this.lastDirty = false;
        this.dirtyChange.emit(false);
        this.applied.emit({ id: n.id, type: n.type, name: n.name, description: n.description, use: n.use, config });
    }
}

/** Which count-chip family a row belongs to, from its best-known type. */
function familyOf(_field: SqlField, type: string | null): FilterKey {
    if (!type) return 'text';
    const t = type.toUpperCase();
    if (NUMBER_TYPES.has(t)) return 'number';
    if (DATE_TYPES.has(t)) return 'date';
    return 'text';
}
