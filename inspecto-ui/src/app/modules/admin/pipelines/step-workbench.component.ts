import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { FIELD_LIST_CAP, WorkbenchField, filterFields, workbenchFields } from './step-workbench-fields';
import { InputRelation, inputSummary } from './step-workbench-inputs';

/**
 * Step workbench — the field list (S4a of `superpower/step-workbench-s4-design.md`).
 *
 * <p>The one authoring control the pipelines editor did not have: the upstream columns a Step reads,
 * with their declared types, split into **referenced by this Step** and **not referenced**, filterable,
 * and clickable to insert. Before this, the SQL pane listed upstream columns unfiltered and ungrouped,
 * so on a wide feed the author could not see which of two hundred columns their SQL actually touched.
 *
 * <p><b>No new endpoint.</b> Columns and types are the values the host pane already holds
 * (`upstreamColumns` / `upstreamColumnTypes`); reference detection is lexical and local
 * ({@link workbenchFields}). The design's §3 is explicit that S4 adds no server surface.
 *
 * <p>⚠ This component OWNS NO STATE that is saved. It emits {@link fieldPicked} and the host decides what
 * that means — insert an identifier in the SQL view, add a `keep` row in the grid. Keeping the write out
 * of here is what lets both views host the same list without the workbench learning either config shape.
 */
@Component({
    selector: 'inspecto-step-workbench',
    standalone: true,
    imports: [FormsModule, MatIconModule, MatTooltipModule, ChipComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex h-full flex-col gap-2">
            <!-- Input strip (S4b). Read-only by design: see step-workbench-inputs.ts for why a picker
                 here would write a key nothing reads. -->
            <p class="text-secondary m-0 text-xs" [class.italic]="!inputs().length">{{ readsLine() }}</p>

            <div class="flex items-center gap-2">
                <mat-icon class="icon-size-4 text-secondary" svgIcon="heroicons_outline:magnifying-glass" />
                <input
                    type="text"
                    class="text-secondary min-w-0 flex-1 bg-transparent text-sm focus:outline-none"
                    [attr.aria-label]="'Filter ' + total() + ' upstream fields'"
                    placeholder="Filter fields"
                    [ngModel]="filter()"
                    (ngModelChange)="filter.set($event)"
                />
                @if (filter()) {
                    <button
                        type="button"
                        class="text-secondary"
                        aria-label="Clear the field filter"
                        (click)="filter.set('')"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:x-mark" />
                    </button>
                }
            </div>

            @if (!total()) {
                <p class="text-secondary text-xs">No upstream columns yet — parse a sample or connect an input.</p>
            } @else {
                <div class="flex-1 overflow-y-auto">
                    @for (group of groups(); track group.label) {
                        @if (group.fields.length) {
                            <div class="mt-2 first:mt-0">
                                <div class="text-secondary mb-1 text-xs font-semibold">
                                    {{ group.label }} ({{ group.fields.length }})
                                </div>
                                @for (f of group.fields; track f.name) {
                                    <button
                                        type="button"
                                        class="hover:bg-hover flex w-full items-center gap-2 rounded px-1 py-0.5 text-left"
                                        [attr.aria-label]="'Use field ' + f.name"
                                        [matTooltip]="f.type || 'type not declared upstream'"
                                        (click)="fieldPicked.emit(f.name)"
                                    >
                                        <span class="flex-1 truncate font-mono text-sm">{{ f.name }}</span>
                                        @if (f.type) {
                                            <inspecto-chip variant="outline" tone="neutral">{{ f.type }}</inspecto-chip>
                                        }
                                    </button>
                                }
                            </div>
                        }
                    }
                    @if (hiddenByCap()) {
                        <p class="text-secondary mt-2 text-xs">{{ hiddenByCap() }} more — type to find them.</p>
                    }
                </div>
            }
        </div>
    `,
})
export class StepWorkbenchComponent {
    /** Upstream column names, in the order the relation declares them. */
    readonly columns = input<readonly string[]>([]);
    /** Declared type per column; a column absent here renders without a badge. */
    readonly types = input<Record<string, string>>({});
    /** The Step's current SQL (or generated SQL) — the text reference detection scans. */
    readonly sql = input<string>('');
    /**
     * The inbound edges feeding this Step, from the authored model. Empty ⇒ the strip says the Step is
     * not connected yet, which is a state the author needs to see rather than an empty line.
     */
    readonly inputs = input<readonly InputRelation[]>([]);

    /** The author picked a field. The HOST decides what that does; see the class note. */
    readonly fieldPicked = output<string>();

    readonly filter = signal('');

    /** "Reads Parse CDRs", or the fan-in list. Names the relation only when it is not a plain DATA edge. */
    readonly readsLine = computed(() => inputSummary(this.inputs()));

    private readonly all = computed<WorkbenchField[]>(() => workbenchFields(this.columns(), this.types(), this.sql()));
    private readonly matching = computed(() => filterFields(this.all(), this.filter()));

    readonly total = computed(() => this.all().length);

    /** How many matches the cap is hiding — surfaced so a wide feed never looks truncated by accident. */
    readonly hiddenByCap = computed(() => Math.max(0, this.matching().length - FIELD_LIST_CAP));

    readonly groups = computed(() => {
        const shown = this.matching().slice(0, FIELD_LIST_CAP);
        return [
            { label: 'Used by this step', fields: shown.filter((f) => f.referenced) },
            { label: 'Not used', fields: shown.filter((f) => !f.referenced) },
        ];
    });
}
