import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { QueryConditionGroupComponent } from 'app/inspecto/query/query-condition-group.component';
import { ColumnMeta, ConditionGroup } from 'app/inspecto/query/query-types';

/** Stage-1 result: how many of the loaded links the predicate keeps. */
export interface LocalMatch {
    matched: number;
    total: number;
}

/**
 * **Link Analysis — filter predicate** (the two-stage query loop, spec §3.7 / plan S1.4). One structured
 * condition tree with two evaluators: **Apply locally** runs it over the working set in the browser at no
 * round-trip cost; **Push to server** sends the same tree as `filter` on the projection so the Dataset is
 * narrowed *before* the `GROUP BY` and the folded counts stay right. `truncated` on the result is the
 * loop's termination signal — refine and push again until it clears.
 *
 * Presentational: the host owns the tree (deep-cloned — the editor mutates in place), the stage results
 * and the round-trip. ⚠ Until the backend accepts `filter`, the server ignores the extra key and the push
 * returns the unfiltered projection; the panel says so through `pushState`.
 */
@Component({
    selector: 'inspecto-link-analysis-filter',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule, ChipComponent, QueryConditionGroupComponent],
    host: { class: 'block' },
    template: `
        <div class="mb-1 flex items-center gap-2">
            <h3 class="text-xs font-semibold uppercase tracking-wide opacity-60">Filter predicate</h3>
            <inspecto-chip
                class="whitespace-nowrap"
                variant="soft"
                title="The same tree the data-table builder emits; the server compiles it to a DuckDB predicate"
                >1 tree · 2 evaluators</inspecto-chip
            >
            <button
                mat-stroked-button
                class="ml-auto !min-h-0 !px-2 !py-0.5 text-xs"
                (click)="advanced.emit()"
                [disabled]="disabled()"
                matTooltip="Advanced search: SQL over the Dataset with a tabular result"
                aria-label="Open advanced search"
            >
                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:code-bracket"></mat-icon>
                Advanced
            </button>
        </div>

        <inspecto-query-condition-group
            [group]="where()"
            [columns]="columns()"
            [root]="true"
            (changed)="changed.emit()"
        ></inspecto-query-condition-group>

        <dl class="mt-2 grid grid-cols-2 gap-1.5 text-xs" aria-label="Filter stages">
            <div class="rounded-md border px-2 py-1.5">
                <dt class="text-secondary font-semibold">Stage 1 · local (browser)</dt>
                <dd class="m-0 text-sm font-semibold tabular-nums">
                    @if (localMatch(); as m) {
                        {{ m.matched }} / {{ m.total }}
                    } @else {
                        —
                    }
                </dd>
                <dd class="text-secondary m-0">links in the working set match · 0 ms</dd>
            </div>
            <div class="rounded-md border px-2 py-1.5">
                <dt class="text-secondary font-semibold">Stage 2 · pushdown (Dataset)</dt>
                <dd class="m-0 text-sm font-semibold" [class.text-warn]="truncated()">{{ pushState() }}</dd>
                <dd class="text-secondary m-0">applied before the fold, so counts stay right</dd>
            </div>
        </dl>

        <div class="mt-2 flex items-center gap-2">
            <button
                mat-stroked-button
                class="flex-1"
                (click)="applyLocal.emit()"
                [disabled]="disabled()"
                aria-label="Apply the predicate locally"
            >
                Apply locally
            </button>
            <button
                mat-flat-button
                color="primary"
                class="flex-1"
                (click)="pushToServer.emit()"
                [disabled]="disabled() || busy()"
                aria-label="Push the predicate to the server"
            >
                Push to server
            </button>
            <button
                mat-icon-button
                (click)="clear.emit()"
                [disabled]="disabled()"
                matTooltip="Clear the predicate"
                aria-label="Clear the predicate"
            >
                <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
            </button>
        </div>
        <p class="text-secondary mt-1 text-[11px]">
            Fields are validated against the relation's columns; values never reach the statement as text.
        </p>
    `,
})
export class LinkAnalysisFilterComponent {
    /** The condition tree (host-owned; the editor mutates it in place, so hosts deep-clone before binding). */
    readonly where = input.required<ConditionGroup>();
    readonly columns = input<ColumnMeta[]>([]);
    readonly localMatch = input<LocalMatch | null>(null);
    /** Stage-2 status line, e.g. "not pushed" / "1,214 links · complete" / "2,000 links · truncated". */
    readonly pushState = input('not pushed');
    readonly truncated = input(false);
    /** No graph loaded yet. */
    readonly disabled = input(false);
    /** A push is in flight. */
    readonly busy = input(false);

    readonly changed = output<void>();
    readonly applyLocal = output<void>();
    readonly pushToServer = output<void>();
    readonly clear = output<void>();
    readonly advanced = output<void>();
}
