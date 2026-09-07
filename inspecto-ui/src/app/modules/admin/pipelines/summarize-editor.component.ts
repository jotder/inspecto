import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MEASURE_AGGS, groupByError } from './measure-grammar';
import { MeasureRow, formatMeasures, measureRowError, needsField, parseMeasures } from './summarize-editor';

/**
 * The `transform.summarize` grouping surface (S4c of `superpower/step-workbench-s4-design.md`): a group-by
 * chip row and a measures table, in place of the two bare string lists the generic schema form rendered.
 *
 * <p><b>It writes exactly what it always wrote.</b> `group_by` and `measures` stay flat string lists on the
 * node; this is a different way to TYPE them, not a different storage shape. The host pane still owns
 * Apply, dirty and the rest of the config — this component is asked for its {@link value} the same way the
 * enrichment editor is asked for its `build()`.
 *
 * <p>⛔ The aggregate list is {@link MEASURE_AGGS}, read from the committed grammar contract and pinned to
 * the engine's `MeasureCompiler` by `MeasureGrammarContractTest`. Never inline it here: a hard-coded option
 * list is exactly the drift the contract file exists to prevent.
 *
 * <p>🔴 A stored measure this UI cannot parse is shown as a **text row** carrying its original spelling, so
 * opening the pane and pressing Apply round-trips it unchanged. See `summarize-editor.ts`.
 */
@Component({
    selector: 'inspecto-summarize-editor',
    standalone: true,
    imports: [FormsModule, MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="mb-1 text-xs font-semibold uppercase opacity-70">Group by</div>
        <div class="mb-2 flex flex-wrap items-center gap-1">
            @for (col of groupBy(); track $index) {
                <span
                    class="flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs"
                    style="border-color: var(--gamma-border)"
                >
                    <span class="font-mono">{{ col }}</span>
                    <button
                        type="button"
                        [attr.aria-label]="'Remove ' + col + ' from group by'"
                        (click)="removeGroup($index)"
                    >
                        <mat-icon class="icon-size-3" svgIcon="heroicons_outline:x-mark" />
                    </button>
                </span>
                @if (groupError(col); as err) {
                    <span class="text-warn text-xs" role="alert">{{ err }}</span>
                }
            }
            @if (availableColumns().length) {
                <select
                    class="h-7 rounded-md border border-gray-300 bg-transparent px-1 text-xs dark:border-gray-600"
                    aria-label="Add a grouping column"
                    [ngModel]="''"
                    (ngModelChange)="addGroup($event)"
                >
                    <option value="">Add a column…</option>
                    @for (col of availableColumns(); track col) {
                        <option [value]="col">{{ col }}</option>
                    }
                </select>
            } @else {
                <!-- Authoring without a parsed sample: the upstream column list is empty (or exhausted),
                     so the column is typed. Same validation either way. -->
                <input
                    type="text"
                    class="h-7 w-40 rounded-md border border-gray-300 bg-transparent px-2 text-xs dark:border-gray-600"
                    aria-label="Add a grouping column by name"
                    placeholder="Add a column…"
                    [ngModel]="typedGroup()"
                    (ngModelChange)="typedGroup.set($event)"
                    (keyup.enter)="commitTypedGroup()"
                    (blur)="commitTypedGroup()"
                />
            }
        </div>

        <div class="mb-1 text-xs font-semibold uppercase opacity-70">Measures</div>
        <table class="w-full border-collapse">
            <thead>
                <tr class="text-secondary text-left text-xs font-semibold uppercase">
                    <th class="w-36 px-1 py-1">Aggregate</th>
                    <th class="px-1 py-1">Column</th>
                    <th class="w-8 px-1 py-1"><span class="sr-only">Remove</span></th>
                </tr>
            </thead>
            <tbody>
                @for (row of rows(); track $index) {
                    <tr class="align-top">
                        <td class="px-1 py-1">
                            @if (row.raw !== undefined) {
                                <span class="text-secondary text-xs italic">as written</span>
                            } @else {
                                <select
                                    class="h-8 w-full rounded-md border border-gray-300 bg-transparent px-1 text-sm dark:border-gray-600"
                                    [attr.aria-label]="'Aggregate, measure ' + ($index + 1)"
                                    [ngModel]="row.agg"
                                    (ngModelChange)="setAgg($index, $event)"
                                >
                                    <option value="">Pick…</option>
                                    @for (agg of aggregates; track agg) {
                                        <option [value]="agg">{{ agg }}</option>
                                    }
                                </select>
                            }
                        </td>
                        <td class="px-1 py-1">
                            @if (row.raw !== undefined) {
                                <input
                                    type="text"
                                    class="h-8 w-full rounded-md border border-gray-300 bg-transparent px-2 font-mono text-sm dark:border-gray-600"
                                    [attr.aria-label]="'Measure ' + ($index + 1)"
                                    [ngModel]="row.raw"
                                    (ngModelChange)="setRaw($index, $event)"
                                />
                            } @else if (needsColumn(row.agg)) {
                                <input
                                    type="text"
                                    class="h-8 w-full rounded-md border border-gray-300 bg-transparent px-2 font-mono text-sm dark:border-gray-600"
                                    [attr.aria-label]="'Column, measure ' + ($index + 1)"
                                    [attr.list]="columns().length ? listId : null"
                                    [ngModel]="row.field"
                                    (ngModelChange)="setField($index, $event)"
                                />
                            } @else {
                                <span class="text-secondary text-xs">every row — count needs no column</span>
                            }
                            @if (rowError(row); as err) {
                                <p class="text-warn m-0 mt-1 text-xs" role="alert">{{ err }}</p>
                            }
                        </td>
                        <td class="px-1 py-1">
                            <button
                                type="button"
                                [attr.aria-label]="'Remove measure ' + ($index + 1)"
                                (click)="removeRow($index)"
                            >
                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:x-mark" />
                            </button>
                        </td>
                    </tr>
                } @empty {
                    <tr>
                        <td colspan="3" class="text-secondary px-1 py-2 text-sm">
                            No measures yet — add one to say what the rollup computes.
                        </td>
                    </tr>
                }
            </tbody>
        </table>
        <datalist [id]="listId">
            @for (col of columns(); track col) {
                <option [value]="col"></option>
            }
        </datalist>
        <button type="button" class="mt-1 text-xs underline" (click)="addRow()">Add a measure</button>
    `,
})
export class SummarizeEditorComponent {
    /** Upstream column names, when the tab has them — the pickers offer these, never a hard-coded list. */
    readonly columns = input<readonly string[]>([]);
    /** The node's stored `group_by`. */
    readonly initialGroupBy = input<readonly string[]>([]);
    /** The node's stored `measures`, in shorthand. */
    readonly initialMeasures = input<readonly string[]>([]);
    /** Any edit — the host pane listens to re-evaluate its dirty state. */
    readonly changed = output<void>();

    /** ⛔ Contract-pinned; see the class note. */
    readonly aggregates = MEASURE_AGGS;
    readonly listId = `summarize-cols-${Math.random().toString(36).slice(2, 8)}`;

    readonly groupBy = signal<string[]>([]);
    readonly rows = signal<MeasureRow[]>([]);
    readonly typedGroup = signal('');
    private pristine = { groupBy: '[]', measures: '[]' };

    constructor() {
        // Seed from the node, and RE-seed when the drawer opens on a different node. Writing the pristine
        // snapshot here is what makes `isDirty` mean "changed since it was loaded" rather than "non-empty".
        effect(() => {
            const g = [...this.initialGroupBy()];
            const m = parseMeasures(this.initialMeasures());
            this.groupBy.set(g);
            this.rows.set(m);
            this.pristine = { groupBy: JSON.stringify(g), measures: JSON.stringify(formatMeasures(m)) };
        });
    }

    readonly availableColumns = computed(() => {
        const taken = new Set(this.groupBy());
        return this.columns().filter((c) => !taken.has(c));
    });

    groupError(column: string): string | null {
        return groupByError(column);
    }

    rowError(row: MeasureRow): string | null {
        return measureRowError(row);
    }

    needsColumn(agg: string): boolean {
        return needsField(agg);
    }

    // ── edits ───────────────────────────────────────────────────────────────────────────────────────

    addGroup(column: string): void {
        if (!column || this.groupBy().includes(column)) return;
        this.groupBy.update((g) => [...g, column]);
        this.changed.emit();
    }

    commitTypedGroup(): void {
        const c = this.typedGroup().trim();
        this.typedGroup.set('');
        if (c) this.addGroup(c);
    }

    removeGroup(index: number): void {
        this.groupBy.update((g) => g.filter((_, i) => i !== index));
        this.changed.emit();
    }

    addRow(): void {
        this.rows.update((r) => [...r, { agg: '', field: '' }]);
        this.changed.emit();
    }

    removeRow(index: number): void {
        this.rows.update((r) => r.filter((_, i) => i !== index));
        this.changed.emit();
    }

    private patch(index: number, change: (row: MeasureRow) => MeasureRow): void {
        this.rows.update((rows) => rows.map((r, i) => (i === index ? change(r) : r)));
        this.changed.emit();
    }

    setAgg(index: number, agg: string): void {
        // Switching to `count` keeps the column: the engine accepts `count(x)`, and silently discarding
        // what the author typed on a mis-click is worse than carrying a field nothing reads.
        this.patch(index, (r) => ({ ...r, agg }));
    }

    setField(index: number, field: string): void {
        this.patch(index, (r) => ({ ...r, field }));
    }

    setRaw(index: number, raw: string): void {
        this.patch(index, () => ({ agg: '', field: '', raw }));
    }

    // ── the host's contract ─────────────────────────────────────────────────────────────────────────

    /** The two keys, in the flat list shape the node stores. */
    value(): { group_by: string[]; measures: string[] } {
        return { group_by: [...this.groupBy()], measures: formatMeasures(this.rows()) };
    }

    /** False when any row or chip is refused — the pane must not Apply a config the Job would throw on. */
    validate(): boolean {
        return !this.groupBy().some((c) => groupByError(c)) && !this.rows().some((r) => measureRowError(r));
    }

    isDirty(): boolean {
        const v = this.value();
        return (
            JSON.stringify(v.group_by) !== this.pristine.groupBy ||
            JSON.stringify(v.measures) !== this.pristine.measures
        );
    }

    markPristine(): void {
        const v = this.value();
        this.pristine = { groupBy: JSON.stringify(v.group_by), measures: JSON.stringify(v.measures) };
    }
}
