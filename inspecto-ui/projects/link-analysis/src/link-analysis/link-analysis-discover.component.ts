import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { G6GraphData } from '@inspecto/core/graph';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import type { GraphSelection } from '@inspecto/link-analysis/graph/graph-analysis';
import type { EntityListPurpose } from '@inspecto/link-analysis/api/inv.service';
import { FoundResult, FoundRow, busiest, groups, route } from './discover';
import type { PackTerms } from './grow-exclude';

type Question = 'busiest' | 'groups' | 'route';

/** What *Start from these* / *Add to a list* carry: the row's entities and, for the start, what they are in words. */
export interface FoundAction {
    row: FoundRow;
    /** What the start is drawn from, in words (the row's own). */
    what: string;
    /** *Add to a list* from the busiest hint asks for an exclusion list. */
    purpose?: EntityListPurpose;
}

/**
 * LA-ONBOARD-1 phase 7, UC-12 **Find something interesting**: three plain questions over the graph on screen - *Who is
 * busiest* (degree centrality), *Which groups stick together* (Louvain communities), *How does A reach B* (shortest
 * path) - each result row read in one sentence in the pack's words, with *Show on graph*, *Start from these* (the
 * Start box's path, phase 6) and *Add to a list* (UC-10). Presentational: the host carries out the three actions.
 */
@Component({
    selector: 'inspecto-link-analysis-discover',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatIconModule, MatTooltipModule, InspectoOptionPickerComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2 text-sm" aria-labelledby="la-discover-title" data-testid="la-discover">
            <h3 id="la-discover-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Find something interesting
            </h3>
            @if (!graph()?.nodes?.length) {
                <p class="text-secondary m-0 text-xs">Draw a graph first: these questions are asked of what is on screen.</p>
            } @else {
                <div class="flex flex-wrap gap-1" role="group" aria-label="Questions">
                    <button mat-stroked-button type="button" data-testid="la-discover-busiest" (click)="ask('busiest')">
                        Who is busiest
                    </button>
                    <button mat-stroked-button type="button" data-testid="la-discover-groups" (click)="ask('groups')">
                        Which groups stick together
                    </button>
                    <button
                        mat-stroked-button
                        type="button"
                        data-testid="la-discover-route"
                        [attr.aria-expanded]="question() === 'route'"
                        (click)="question.set('route'); result.set(null)"
                    >
                        How does A reach B
                    </button>
                </div>
                @if (question() === 'route') {
                    <div class="flex flex-col gap-1" data-testid="la-discover-route-form">
                        <inspecto-option-picker label="From" [options]="pickOptions()" [formControl]="from" />
                        <inspecto-option-picker label="To" [options]="pickOptions()" [formControl]="to" />
                        <button
                            mat-flat-button
                            color="primary"
                            type="button"
                            class="self-start"
                            data-testid="la-discover-route-go"
                            [disabled]="!fromValue() || !toValue()"
                            (click)="ask('route')"
                        >
                            Find the way
                        </button>
                    </div>
                }
                @if (result(); as r) {
                    <div class="flex flex-col gap-2" role="status" aria-live="polite" data-testid="la-discover-result">
                        <p class="m-0 text-xs font-semibold">{{ r.heading }}</p>
                        @if (r.empty) {
                            <p class="text-secondary m-0 text-xs" data-testid="la-discover-empty">{{ r.empty }}</p>
                        }
                        <ul class="m-0 flex list-none flex-col gap-2 p-0">
                            @for (row of r.rows; track row.key) {
                                <li class="rounded-md border px-2 py-1" style="border-color: var(--gamma-border)">
                                    <p class="m-0 text-xs" data-testid="la-discover-reading">{{ row.reading }}</p>
                                    <div class="mt-1 flex flex-wrap gap-1">
                                        <button
                                            mat-button
                                            type="button"
                                            [attr.aria-label]="'Show on graph: ' + row.title"
                                            (click)="show.emit({ nodeIds: row.nodeIds, edgeIds: row.edgeIds })"
                                        >
                                            Show on graph
                                        </button>
                                        <span class="inline-flex" [matTooltip]="startWhyNot()">
                                            <button
                                                mat-button
                                                type="button"
                                                [disabled]="!!startWhyNot() || !row.values.length"
                                                [attr.aria-label]="'Start from these: ' + row.title"
                                                (click)="startFrom.emit({ row, what: row.what })"
                                            >
                                                Start from these
                                            </button>
                                        </span>
                                        <span class="inline-flex" [matTooltip]="listWhyNot()">
                                            <button
                                                mat-button
                                                type="button"
                                                [disabled]="!!listWhyNot() || !row.values.length"
                                                [attr.aria-label]="'Add to a list: ' + row.title"
                                                (click)="addToList.emit({ row, what: row.what })"
                                            >
                                                Add to a list
                                            </button>
                                        </span>
                                    </div>
                                    @if (row.hint) {
                                        <div class="mt-1 flex flex-wrap items-center gap-1" data-testid="la-discover-hint">
                                            <p class="text-secondary m-0 text-xs">{{ row.hint }}</p>
                                            @if (!listWhyNot()) {
                                                <button
                                                    mat-stroked-button
                                                    type="button"
                                                    (click)="addToList.emit({ row, what: row.what, purpose: 'exclusion' })"
                                                >
                                                    Add to an exclusion list
                                                </button>
                                            }
                                        </div>
                                    }
                                </li>
                            }
                        </ul>
                    </div>
                }
                @if (startWhyNot() || listWhyNot()) {
                    <p class="text-secondary m-0 text-xs" data-testid="la-discover-why-not">
                        {{ startWhyNot() || listWhyNot() }}
                    </p>
                }
            }
        </section>
    `,
})
export class LinkAnalysisDiscoverComponent {
    /** The graph on screen (the canvas data). */
    readonly graph = input<G6GraphData | null>(null);
    /** The pack's words for nodes and links. */
    readonly terms = input.required<PackTerms>();
    /** The links' time attribute, when one is chosen: a group's reading says the days it spans. */
    readonly timeAttr = input('');
    /** The graph's nodes, for *How does A reach B*. */
    readonly nodeOptions = input<{ id: string; label: string }[]>([]);
    /** Why *Start from these* cannot run here; '' = it can. */
    readonly startWhyNot = input('');
    /** Why *Add to a list* cannot run here; '' = it can. */
    readonly listWhyNot = input('');

    readonly show = output<GraphSelection>();
    readonly startFrom = output<FoundAction>();
    readonly addToList = output<FoundAction>();

    readonly question = signal<Question | null>(null);
    readonly result = signal<FoundResult | null>(null);
    readonly from = new FormControl<string | null>(null);
    readonly to = new FormControl<string | null>(null);
    readonly fromValue = toSignal(this.from.valueChanges, { initialValue: null });
    readonly toValue = toSignal(this.to.valueChanges, { initialValue: null });
    readonly pickOptions = computed<PickerOption[]>(() => this.nodeOptions().map((n) => ({ value: n.id, label: n.label })));

    ask(q: Question): void {
        const g = this.graph();
        if (!g) return;
        this.question.set(q);
        const t = this.terms();
        this.result.set(
            q === 'busiest'
                ? busiest(g, t)
                : q === 'groups'
                  ? groups(g, t, this.timeAttr())
                  : route(g, this.from.value ?? '', this.to.value ?? '', t),
        );
    }

    /** A new graph: the last answer no longer describes it. */
    reset(): void {
        this.question.set(null);
        this.result.set(null);
        this.from.setValue(null);
        this.to.setValue(null);
    }
}
