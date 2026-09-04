import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AuthoredNode, ComponentsService, RelationsPreview } from 'app/inspecto/api';
import { InspectoSchemaFieldsEditorComponent, SchemaFieldRow } from 'app/inspecto/schema/schema-fields-editor.component';
import { StepPreviewResultComponent } from 'app/inspecto/components/step-preview-result.component';

/** How many rows an inline "Try it on the sample" test posts (mirrors the config pane's cap). */
const MAX_TEST_ROWS = 50;

/** The fixed input relation every `transform.sql` SELECT reads from (the engine's own name). */
const INPUT_RELATION = 'input';

/**
 * Seed SQL for a NEW Step: an explicit column list over the upstream columns when the host knows them
 * (the first parsed sample row's keys), else `SELECT * FROM input`. Exported for the spec.
 */
export function seedSql(upstreamColumns: string[]): string {
    if (!upstreamColumns.length) return `SELECT * FROM ${INPUT_RELATION}`;
    return `SELECT\n    ${upstreamColumns.map(quoteIdentifier).join(',\n    ')}\nFROM ${INPUT_RELATION}`;
}

/** Double-quote a column name unless it is already a plain identifier — parsed headers may carry spaces. */
function quoteIdentifier(name: string): string {
    return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : `"${name.replace(/"/g, '""')}"`;
}

/**
 * The **SQL-first Transform Step pane** for `transform.sql` (operator instruction 2026-09-04: "Go for
 * Advanced (SQL) based transformation" — this SUPERSEDES the 2026-09-03 D5/D6 Simple-grid + lock design).
 * The SQL IS the transformation: one `<textarea>`, a Test this Step run, and the derived output columns
 * shown in the SAME `<inspecto-schema-fields-editor>` the Parse pane uses.
 *
 * <p><b>Persisted shape.</b> `node.config` is `{ sql }` — the single declared attribute. A node saved by
 * the retired grid may still carry a `fields` key beside `sql`; {@link submit} deliberately does NOT
 * write it back, so the legacy key is dropped on the next Apply (the engine never read it).
 *
 * <p><b>Derived columns.</b> There is no live `describe` endpoint yet (BACKLOG AUTHORING-REDESIGN-1 (a)).
 * The columns table is fed from `ComponentsService.previewTransform`'s DESCRIBE-derived `columnTypes`,
 * so before the first test the section says so rather than fabricating types.
 */
@Component({
    selector: 'app-pipeline-transform-sql-definition',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        FormsModule,
        MatButtonModule,
        MatIconModule,
        InspectoSchemaFieldsEditorComponent,
        StepPreviewResultComponent,
    ],
    templateUrl: './pipeline-transform-sql-definition.component.html',
})
export class PipelineTransformSqlDefinitionComponent {
    private components = inject(ComponentsService);

    readonly node = input.required<AuthoredNode>();
    /** The rows the tab's sample thread parsed — seeds a NEW step's column list and feeds "Try it on the sample". */
    readonly sampleRows = input<Record<string, unknown>[] | undefined>(undefined);

    readonly applied = output<AuthoredNode>();
    readonly dirtyChange = output<boolean>();

    readonly sql = signal('');
    /** The SQL as loaded (or seeded) — dirty ⇔ `sql() !== loadedSql`. */
    private loadedSql = '';
    private lastDirty = false;
    private seededFor: string | null = null;

    // ── derived columns, from the same preview call Test this Step makes ──
    readonly preview = signal<RelationsPreview | null>(null);
    readonly previewPending = signal(false);
    readonly previewError = signal<string | null>(null);

    /**
     * Rows for the shared columns table, built from the preview's DESCRIBE `columnTypes` — positional
     * (selector = 1-based position, no name-based Selector column), all included, types read-only via
     * `autoTypes`. The editor has no read-only Name input and another agent owns it, so edits made in
     * the table are simply never read back here (renaming a column is done in the SQL — v1).
     */
    readonly derivedRows = computed<SchemaFieldRow[]>(() => {
        const rel = this.preview()?.relations?.[0];
        return (rel?.columnTypes ?? []).map((c, i) => ({
            include: true,
            name: c.name,
            selector: String(i + 1),
            type: c.type,
        }));
    });

    /** First previewed row's value per column, keyed by the positional selector above. */
    readonly derivedSamples = computed<Record<string, string>>(() => {
        const rel = this.preview()?.relations?.[0];
        const row = rel?.rows?.[0];
        const out: Record<string, string> = {};
        if (!row) return out;
        (rel?.columnTypes ?? []).forEach((c, i) => {
            const v = (row as Record<string, unknown>)[c.name];
            if (v !== undefined && v !== null) out[String(i + 1)] = String(v);
        });
        return out;
    });

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

    /** Load the node's `sql`, or seed a fresh Step from the upstream sample columns. */
    private seedFrom(node: AuthoredNode): void {
        const stored = node.config?.['sql'];
        const text =
            typeof stored === 'string' && stored.trim()
                ? stored
                : seedSql(Object.keys(this.sampleRows()?.[0] ?? {}));
        this.loadedSql = text;
        this.sql.set(text);
        this.preview.set(null);
        this.previewError.set(null);
    }

    editSql(text: string): void {
        this.sql.set(text);
        this.preview.set(null);
        const dirty = text !== this.loadedSql;
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    // ── Test this Step — the SAME route every other transform.* node type tests against ──

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

    submit(): void {
        const n = this.node();
        // `{ sql }` only — a legacy `fields` key (written by the retired Simple grid) is dropped here on purpose.
        const config: Record<string, unknown> = { sql: this.sql() };
        this.loadedSql = this.sql();
        this.lastDirty = false;
        this.dirtyChange.emit(false);
        this.applied.emit({ id: n.id, type: n.type, name: n.name, description: n.description, use: n.use, config });
    }
}
