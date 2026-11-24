import { ChangeDetectionStrategy, Component, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { LookupRow, formatLookups, lookupPrefix, lookupsProblem, parseLookups } from './join-lookups';

/**
 * The `lookups:` of a keyed `transform.join` (LOOKUP-1 P1d) — one mapping per looked-up column, each with its own
 * returned columns, output names, not-found defaults and match details — in place of the validated-JSON row the
 * Additional config section showed. Same pattern as the summarize editor: the host pane owns Apply and dirty and asks
 * for {@link value}; the stored shape is the engine's, unchanged (`join-lookups.ts`).
 *
 * ⚠ No backticks in this template literal.
 */
@Component({
    selector: 'inspecto-join-lookups-editor',
    standalone: true,
    imports: [FormsModule, MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="mb-1 mt-2 text-xs font-semibold uppercase opacity-70">Per-input lookups</div>
        @if (!rows().length) {
            <p class="text-secondary m-0 mb-1 text-sm">
                None: every input in <span class="font-mono">on</span> gets the same columns. Add a mapping to name each
                input's columns yourself.
            </p>
        }
        @for (row of rows(); track $index; let i = $index) {
            <fieldset class="mb-2 rounded-md border p-2" style="border-color: var(--gamma-border)">
                <legend class="px-1 text-sm font-semibold">Mapping {{ i + 1 }}</legend>
                <div class="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-2">
                    <label class="flex items-center gap-2 text-sm">
                        <span class="w-28 shrink-0">Looked up by</span>
                        <input
                            type="text"
                            class="h-8 w-full rounded-md border border-gray-300 bg-transparent px-2 font-mono text-sm dark:border-gray-600"
                            [attr.aria-label]="'Looked up by, mapping ' + (i + 1)"
                            [attr.list]="inputColumns().length ? listId : null"
                            [disabled]="readOnly()"
                            [ngModel]="row.on"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="patch(i, { on: $event })"
                        />
                    </label>
                    <label class="flex items-center gap-2 text-sm">
                        <span class="w-28 shrink-0">Name prefix</span>
                        <input
                            type="text"
                            class="h-8 w-full rounded-md border border-gray-300 bg-transparent px-2 font-mono text-sm dark:border-gray-600"
                            [attr.aria-label]="'Name prefix, mapping ' + (i + 1)"
                            placeholder="{on}_"
                            [disabled]="readOnly()"
                            [ngModel]="row.as"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="patch(i, { as: $event })"
                        />
                    </label>
                </div>

                <div class="text-secondary mt-2 text-xs font-semibold uppercase">Columns it returns</div>
                @for (c of row.columns; track $index; let j = $index) {
                    <div class="mt-1 grid grid-cols-[1fr_1fr_2rem] items-center gap-2">
                        <input
                            type="text"
                            class="h-8 rounded-md border border-gray-300 bg-transparent px-2 font-mono text-sm dark:border-gray-600"
                            [attr.aria-label]="'Reference column ' + (j + 1) + ', mapping ' + (i + 1)"
                            placeholder="Reference column"
                            [disabled]="readOnly()"
                            [ngModel]="c.ref"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="patchColumn(i, j, { ref: $event })"
                        />
                        <input
                            type="text"
                            class="h-8 rounded-md border border-gray-300 bg-transparent px-2 font-mono text-sm dark:border-gray-600"
                            [attr.aria-label]="'Output name ' + (j + 1) + ', mapping ' + (i + 1)"
                            [placeholder]="prefixOf(row) + (c.ref || 'column')"
                            [disabled]="readOnly()"
                            [ngModel]="c.out"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="patchColumn(i, j, { out: $event })"
                        />
                        @if (!readOnly()) {
                            <button
                                type="button"
                                [attr.aria-label]="'Remove column ' + (j + 1) + ', mapping ' + (i + 1)"
                                (click)="removeColumn(i, j)"
                            >
                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:x-mark" />
                            </button>
                        }
                    </div>
                } @empty {
                    <p class="text-secondary m-0 mt-1 text-sm">
                        @if (row.emptyColumns || row.details || row.detailsRaw) {
                            No Reference column: this mapping adds only the match details.
                        } @else {
                            Every Reference column.
                        }
                    </p>
                }
                @if (!readOnly()) {
                    <button type="button" class="mt-1 text-xs underline" (click)="addColumn(i)">Add a column</button>
                }

                <div class="text-secondary mt-2 text-xs font-semibold uppercase">When nothing matches</div>
                @for (d of row.defaults; track $index; let k = $index) {
                    <div class="mt-1 grid grid-cols-[1fr_1fr_2rem] items-center gap-2">
                        <input
                            type="text"
                            class="h-8 rounded-md border border-gray-300 bg-transparent px-2 font-mono text-sm dark:border-gray-600"
                            [attr.aria-label]="'Default for output column ' + (k + 1) + ', mapping ' + (i + 1)"
                            placeholder="Output column"
                            [disabled]="readOnly()"
                            [ngModel]="d.name"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="patchDefault(i, k, { name: $event })"
                        />
                        <input
                            type="text"
                            class="h-8 rounded-md border border-gray-300 bg-transparent px-2 text-sm dark:border-gray-600"
                            [attr.aria-label]="'Default value ' + (k + 1) + ', mapping ' + (i + 1)"
                            placeholder="Value"
                            [disabled]="readOnly()"
                            [ngModel]="d.value"
                            [ngModelOptions]="{ standalone: true }"
                            (ngModelChange)="patchDefault(i, k, { value: $event })"
                        />
                        @if (!readOnly()) {
                            <button
                                type="button"
                                [attr.aria-label]="'Remove default ' + (k + 1) + ', mapping ' + (i + 1)"
                                (click)="removeDefault(i, k)"
                            >
                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:x-mark" />
                            </button>
                        }
                    </div>
                } @empty {
                    <p class="text-secondary m-0 mt-1 text-sm">An unmatched value leaves the column empty.</p>
                }
                @if (!readOnly()) {
                    <button type="button" class="mt-1 text-xs underline" (click)="addDefault(i)">Add a default</button>
                }

                <div class="mt-2 text-sm">
                    @if (row.detailsRaw) {
                        <span class="text-secondary">Match details: named as written in the file.</span>
                    } @else {
                        <label class="flex items-center gap-2">
                            <input
                                type="checkbox"
                                [disabled]="readOnly()"
                                [checked]="row.details"
                                (change)="patch(i, { details: $any($event.target).checked })"
                            />
                            Add match details (the key that matched, and how)
                        </label>
                    }
                </div>
                @if (!readOnly()) {
                    <button type="button" class="mt-2 text-xs underline" (click)="removeRow(i)">
                        Remove mapping {{ i + 1 }}
                    </button>
                }
            </fieldset>
        }
        @if (problem(); as p) {
            <p class="text-warn m-0 mb-1 text-xs" role="alert">{{ p }}</p>
        }
        @if (!readOnly()) {
            <button type="button" class="text-xs underline" (click)="addRow()">Add a mapping</button>
        }
        <datalist [id]="listId">
            @for (col of inputColumns(); track col) {
                <option [value]="col"></option>
            }
        </datalist>
    `,
})
export class JoinLookupsEditorComponent {
    /** The node's stored `lookups`. */
    readonly initial = input<unknown>(undefined);
    /** The rows' columns, when known — offered for "Looked up by". */
    readonly inputColumns = input<readonly string[]>([]);
    readonly readOnly = input(false);
    /** Any edit — the host pane listens to re-evaluate its dirty state. */
    readonly changed = output<void>();

    readonly listId = `join-lookup-cols-${Math.random().toString(36).slice(2, 8)}`;
    readonly rows = signal<LookupRow[]>([]);
    /** Set by an edit or by {@link validate}, so an untouched pane is not flagged. */
    readonly problem = signal<string | null>(null);
    private pristine = '[]';

    constructor() {
        // Seed from the node, and re-seed when the drawer opens on a different node.
        effect(() => {
            const rows = parseLookups(this.initial()) ?? [];
            this.rows.set(rows);
            this.problem.set(null);
            this.pristine = JSON.stringify(formatLookups(rows) ?? []);
        });
    }

    prefixOf(row: LookupRow): string {
        return lookupPrefix(row);
    }

    // ── edits ───────────────────────────────────────────────────────────────────────────────────────

    private update(change: (rows: LookupRow[]) => LookupRow[]): void {
        this.rows.update(change);
        this.problem.set(lookupsProblem(this.rows()));
        this.changed.emit();
    }

    patch(i: number, change: Partial<LookupRow>): void {
        this.update((rows) => rows.map((r, n) => (n === i ? { ...r, ...change } : r)));
    }

    addRow(): void {
        this.update((rows) => [
            ...rows,
            { on: '', as: '', columns: [], emptyColumns: false, defaults: [], details: false },
        ]);
    }

    removeRow(i: number): void {
        this.update((rows) => rows.filter((_, n) => n !== i));
    }

    addColumn(i: number): void {
        this.update((rows) =>
            rows.map((r, n) => (n === i ? { ...r, columns: [...r.columns, { ref: '', out: '' }] } : r)),
        );
    }

    patchColumn(i: number, j: number, change: Partial<{ ref: string; out: string }>): void {
        this.update((rows) =>
            rows.map((r, n) =>
                n === i ? { ...r, columns: r.columns.map((c, m) => (m === j ? { ...c, ...change } : c)) } : r,
            ),
        );
    }

    removeColumn(i: number, j: number): void {
        // Removing the last column means "every Reference column" again, not "none" (a stored `columns: {}`
        // is kept only until the author edits it).
        this.update((rows) =>
            rows.map((r, n) =>
                n === i ? { ...r, columns: r.columns.filter((_, m) => m !== j), emptyColumns: false } : r,
            ),
        );
    }

    addDefault(i: number): void {
        this.update((rows) =>
            rows.map((r, n) => (n === i ? { ...r, defaults: [...r.defaults, { name: '', value: '' }] } : r)),
        );
    }

    patchDefault(i: number, k: number, change: Partial<{ name: string; value: unknown }>): void {
        this.update((rows) =>
            rows.map((r, n) =>
                n === i ? { ...r, defaults: r.defaults.map((d, m) => (m === k ? { ...d, ...change } : d)) } : r,
            ),
        );
    }

    removeDefault(i: number, k: number): void {
        this.update((rows) =>
            rows.map((r, n) => (n === i ? { ...r, defaults: r.defaults.filter((_, m) => m !== k) } : r)),
        );
    }

    // ── the host's contract ─────────────────────────────────────────────────────────────────────────

    /** The `lookups` list to store, or `undefined` when there are no mappings (the key is then dropped). */
    value(): Record<string, unknown>[] | undefined {
        return formatLookups(this.rows());
    }

    /** False when a mapping is refused — the pane must not Apply a config the run would throw on. */
    validate(): boolean {
        const p = lookupsProblem(this.rows());
        this.problem.set(p);
        return p === null;
    }

    isDirty(): boolean {
        return JSON.stringify(this.value() ?? []) !== this.pristine;
    }

    markPristine(): void {
        this.pristine = JSON.stringify(this.value() ?? []);
    }
}
