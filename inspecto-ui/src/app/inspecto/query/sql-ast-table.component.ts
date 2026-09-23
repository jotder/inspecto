import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { SqlAstNode, predicateRows } from './sql-ast';

/**
 * The READ-ONLY structured view of a row predicate (AUTHORING-REDESIGN-1 (c), design Step 2): DuckDB's parse
 * tree, fed by the host, rendered as a semantic table — joined-by · column · test · value, one row per
 * condition, a heading row per nested group. Presentational: no HTTP, writes nothing.
 *
 * <p>A semantic `<table>` of plain text, deliberately not an ag-Grid host and not a grid of disabled
 * controls (design §9): a disabled grid is unreachable by keyboard and announces nothing. Group nesting is
 * carried by visually-hidden "Level N" text as well as indentation — indentation alone is a visual-only
 * cue. ⚠ `aria-level` on a `<tr>` is only valid inside a `treegrid`, and a read-only table is not one.
 *
 * <p>A part the view cannot show is kept as a row that says so — never dropped, or the table would claim
 * a narrower predicate than the one stored.
 */
@Component({
    selector: 'inspecto-sql-ast-table',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <table class="w-full border-collapse text-sm">
            <caption class="sr-only">
                The stored condition, one row per test
            </caption>
            <thead>
                <tr class="text-secondary text-left text-xs">
                    <th scope="col" class="w-20 py-1 pr-2 font-medium">Joined by</th>
                    <th scope="col" class="py-1 pr-2 font-medium">Column</th>
                    <th scope="col" class="py-1 pr-2 font-medium">Test</th>
                    <th scope="col" class="py-1 font-medium">Value</th>
                </tr>
            </thead>
            <tbody>
                @for (r of rows(); track $index) {
                    <tr class="border-t" style="border-color: var(--gamma-border)">
                        <td class="py-1 pr-2 align-top" [style.padding-left.rem]="(r.level - 1) * 1">
                            @if (r.level > 1) {
                                <span class="sr-only">Level {{ r.level }}</span>
                            }
                            @if (r.joiner) {
                                <span class="text-secondary">{{ r.joiner === 'AND' ? 'and' : 'or' }}</span>
                            }
                        </td>
                        @switch (r.kind) {
                            @case ('group') {
                                <td colspan="3" class="py-1 italic">
                                    {{ r.groupOp === 'OR' ? 'any' : 'all' }} of the rows below
                                </td>
                            }
                            @case ('unshown') {
                                <td colspan="3" class="text-secondary py-1">
                                    A part this view cannot show — read it in the text above
                                </td>
                            }
                            @default {
                                <td class="py-1 pr-2 font-mono">{{ r.left }}</td>
                                <td class="py-1 pr-2">{{ r.operator }}</td>
                                <td class="py-1 font-mono">{{ r.right }}</td>
                            }
                        }
                    </tr>
                }
            </tbody>
        </table>
        @if (unshown(); as n) {
            <p class="text-secondary m-0 mt-1 text-xs">
                {{ n }} {{ n === 1 ? 'part' : 'parts' }} of this condition {{ n === 1 ? 'is' : 'are' }} not shown here;
                the text is the complete condition.
            </p>
        }
    `,
})
export class SqlAstTableComponent {
    /** The predicate's tree, as `POST /components/sql/ast` (`fragment: 'predicate'`) answered it. */
    readonly ast = input.required<SqlAstNode>();

    readonly rows = computed(() => predicateRows(this.ast()));
    readonly unshown = computed(() => this.rows().filter((r) => r.kind === 'unshown').length);
}
