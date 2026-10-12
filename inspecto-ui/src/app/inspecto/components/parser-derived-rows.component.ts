import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { ParserRowsPreview } from 'app/inspecto/api';
import { InspectoAlertComponent } from './alert.component';
import { ChipComponent } from './chip.component';

/**
 * Read-only sample of the rows an ASN.1 Parse step's `derive` / `children` blocks would load
 * (PARSING-PREVIEW-DERIVED-1), shown under the record tree. One table per segment: derived columns carry a
 * primary-tinted header and a "derived" chip, child segments a "child rows" chip. Presentational only — the
 * server builds the rows with the ingester's own code, this just draws them.
 *
 * With nothing declared it says so in one sentence rather than rendering an empty table.
 */
@Component({
    selector: 'app-parser-derived-rows',
    standalone: true,
    imports: [InspectoAlertComponent, ChipComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section class="mt-3" aria-labelledby="derived-rows-heading" data-test="derived-rows">
            <h3 id="derived-rows-heading" class="mb-1 text-xs font-semibold uppercase opacity-70">
                Derived columns and child rows
            </h3>
            @if (error) {
                <inspecto-alert variant="warning" data-test="derived-error">{{ error }}</inspecto-alert>
            } @else if (!derived || !derived.segments.length) {
                <p class="text-secondary text-sm" data-test="derived-empty">
                    This Parse step declares no derived columns or child rows, so there is nothing to show here beyond
                    the record tree.
                </p>
            } @else {
                @for (set of derived.segments; track set.segment) {
                    <div class="mb-3" [attr.data-test]="'derived-segment-' + set.segment">
                        <div class="mb-1 flex flex-wrap items-center gap-2 text-sm">
                            <span class="font-mono font-medium">{{ set.segment }}</span>
                            @if (set.child) {
                                <inspecto-chip variant="soft" tone="primary">child rows</inspecto-chip>
                            }
                            <span class="text-secondary text-xs"
                                >{{ set.rows.length }} of {{ set.rowCount }} row{{ set.rowCount === 1 ? '' : 's' }}</span
                            >
                        </div>
                        @if (!set.rows.length) {
                            <p class="text-secondary text-xs">No rows from the previewed records.</p>
                        } @else {
                            <div class="overflow-x-auto">
                                <table class="w-full text-left text-xs" [attr.aria-label]="set.segment + ' sample rows'">
                                    <thead>
                                        <tr>
                                            @for (col of set.columns; track col) {
                                                <th
                                                    scope="col"
                                                    class="whitespace-nowrap px-2 py-1 font-mono font-semibold"
                                                    [class.text-primary]="isDerived(set, col)"
                                                    [attr.data-derived]="isDerived(set, col) ? 'true' : null"
                                                    [style.background]="
                                                        isDerived(set, col) ? 'var(--gamma-bg-default)' : null
                                                    "
                                                >
                                                    {{ col }}
                                                    @if (isDerived(set, col)) {
                                                        <span class="sr-only">(derived)</span>
                                                    }
                                                </th>
                                            }
                                        </tr>
                                    </thead>
                                    <tbody>
                                        @for (row of set.rows; track $index) {
                                            <tr>
                                                @for (col of set.columns; track col) {
                                                    <td
                                                        class="whitespace-nowrap px-2 py-0.5 font-mono"
                                                        [attr.data-derived]="isDerived(set, col) ? 'true' : null"
                                                        [style.background]="
                                                            isDerived(set, col) ? 'var(--gamma-bg-default)' : null
                                                        "
                                                    >
                                                        @if (row[col] === null || row[col] === undefined) {
                                                            <span class="opacity-50">NULL</span>
                                                        } @else {
                                                            {{ row[col] }}
                                                        }
                                                    </td>
                                                }
                                            </tr>
                                        }
                                    </tbody>
                                </table>
                            </div>
                        }
                    </div>
                }
            }
        </section>
    `,
})
export class ParserDerivedRowsComponent {
    /** The served rows; absent when the Parse step declares neither `derive` nor `children`. */
    @Input() derived: ParserRowsPreview | null | undefined = null;
    /** Why the rows could not be built (the tree above is still valid). */
    @Input() error: string | null | undefined = null;

    isDerived(set: { derivedColumns: string[] }, col: string): boolean {
        return set.derivedColumns.includes(col);
    }
}
