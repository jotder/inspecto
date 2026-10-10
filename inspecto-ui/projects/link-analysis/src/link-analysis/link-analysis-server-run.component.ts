import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
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
    untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Subscription } from 'rxjs';
import {
    GraphBudgetView,
    GraphRunRequest,
    GraphRunResult,
    GraphRunView,
    GraphRunsService,
    LinkIndexSummary,
    graphRunErrorMessage,
    indexRefusalMessage,
    isTerminalGraphRun,
} from '@inspecto/link-analysis/api/graph-runs.service';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { BudgetNextAction, budgetNextAction } from './graph-run-apply';

/**
 * **Run on server** (LA separation D-4 step 7): the control the toolbox shows INSTEAD of a local run when the
 * Working Set is above the browser's cap for an algorithm. It starts a Graph Run, shows it while it is QUEUED /
 * RUNNING (status, progress, elapsed, Cancel), and ends in exactly one of five states:
 *
 * - COMPLETED - emits {@link completed} with the result, once; the toolbox applies it through the same code a local
 *   run uses;
 * - BUDGET_EXCEEDED - a banner with the EXACT numbers the server returned (budget, measured size, reason) and ONE next
 *   action. **A result is never emitted for it**, whatever the response carries;
 * - CANCELLED / FAILED - a plain message;
 * - a refused or failed call (403 / 404 / 422 / 503) - the mapped message.
 *
 * Polling is bound to this component: destroying it (or starting another run) unsubscribes and stops the timer.
 */
@Component({
    selector: 'inspecto-link-analysis-server-run',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [DecimalPipe, MatButtonModule, MatIconModule, InspectoAlertComponent, ChipComponent],
    template: `
        @if (note()) {
            <p class="text-secondary mt-1 text-sm">{{ note() }}</p>
        }
        <div class="mt-1 flex flex-wrap items-center gap-2">
            @if (!active()) {
                @if (!indexOnly()) {
                    <button
                        mat-stroked-button
                        data-testid="run-on-server"
                        [disabled]="!!blockedReason()"
                        (click)="start()"
                    >
                        <mat-icon svgIcon="heroicons_outline:server-stack"></mat-icon>
                        Run on server
                    </button>
                }
                @if (index(); as ix) {
                    @if (indexChoices().length > 1) {
                        <label class="text-secondary text-sm">
                            Index
                            <select
                                class="ml-1 rounded border px-1"
                                data-testid="index-pick"
                                (change)="pickIndex($any($event.target).value)"
                            >
                                @for (c of indexChoices(); track c.dataset) {
                                    <option [value]="c.dataset" [selected]="c.dataset === ix.dataset">
                                        {{ c.dataset }}
                                    </option>
                                }
                            </select>
                        </label>
                    }
                    <button
                        mat-stroked-button
                        data-testid="run-on-index"
                        [disabled]="!!indexBlockedReason()"
                        (click)="start(undefined, 'index')"
                    >
                        <mat-icon svgIcon="heroicons_outline:server-stack"></mat-icon>
                        Run on index
                    </button>
                    @if (ix.stale) {
                        <inspecto-chip
                            variant="soft"
                            tone="warning"
                            data-testid="index-stale-chip"
                            [title]="ix.reason ?? ''"
                        >
                            Index stale
                        </inspecto-chip>
                    }
                }
            } @else {
                <button
                    mat-stroked-button
                    data-testid="cancel-run"
                    [disabled]="cancelling() || !view()"
                    (click)="cancel()"
                >
                    <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                    {{ cancelling() ? 'Cancelling...' : 'Cancel' }}
                </button>
            }
        </div>
        @if (blockedReason(); as why) {
            <p class="text-secondary mt-1 text-sm" role="status" data-testid="blocked-reason">{{ why }}</p>
        }
        @if (index() && indexBlockedReason(); as why) {
            <p class="text-secondary mt-1 text-sm" role="status" data-testid="index-blocked-reason">{{ why }}</p>
        }
        @if (error()) {
            <inspecto-alert variant="error" title="Server run">{{ error() }}</inspecto-alert>
        }
        @if (view(); as v) {
            @if (active()) {
                <p class="mt-1 text-sm" role="status" data-testid="run-progress">
                    {{ v.status === 'QUEUED' ? 'Queued on the server' : 'Running on the server' }}
                    @if (v.status === 'RUNNING' && v.progress.fraction > 0) {
                        - {{ v.progress.fraction * 100 | number: '1.0-0' }}%
                    } @else if (v.status === 'RUNNING' && v.progress.work > 0) {
                        - {{ v.progress.work | number: '1.0-0' }} steps done
                    }
                    - {{ elapsedMs() | number: '1.0-0' }} ms elapsed ({{ v.consumed.nodes }} nodes,
                    {{ v.consumed.edges }} links)
                </p>
            } @else {
                @switch (v.status) {
                    @case ('COMPLETED') {
                        <p class="mt-1 text-sm" role="status" data-testid="run-done">
                            Ran on the server in {{ v.consumed.elapsedMs | number: '1.0-0' }} ms over
                            {{ v.consumed.nodes }} nodes and {{ v.consumed.edges }} links{{
                                v.cached ? ' (cached answer)' : ''
                            }}.
                        </p>
                        @if (v.source; as src) {
                            <p class="mt-1 flex flex-wrap items-center gap-2 text-sm" data-testid="index-source">
                                Answered from the edge index, version {{ src.version }}.
                                @if (src.stale) {
                                    <inspecto-chip variant="soft" tone="warning" data-testid="source-stale-chip">
                                        Index stale
                                    </inspecto-chip>
                                    <span class="text-secondary">
                                        {{ src.staleReason ?? 'Rows were added to the Dataset after the build.' }}
                                    </span>
                                }
                            </p>
                            @if (indexSummary(); as sum) {
                                <p class="mt-1 text-sm" data-testid="index-summary">{{ sum.headline }}</p>
                                @if (sum.lines.length) {
                                    <ol class="mt-1 list-inside list-decimal text-sm" data-testid="index-lines">
                                        @for (l of sum.lines; track $index) {
                                            <li>{{ l }}</li>
                                        }
                                    </ol>
                                }
                            }
                        }
                    }
                    @case ('BUDGET_EXCEEDED') {
                        <inspecto-alert variant="warning" title="Over budget - no result">
                            <p data-testid="budget-reason">{{ reasonFacts() }}</p>
                            <ul class="mt-1 list-disc pl-5" data-testid="budget-numbers">
                                <li>
                                    Limit hit: <strong>{{ v.exceeded }}</strong>
                                </li>
                                <li>
                                    Budget: {{ v.budget.maxNodes }} nodes, {{ v.budget.maxEdges }} links,
                                    {{ v.budget.timeoutMs }} ms
                                </li>
                                <li>
                                    Measured: {{ v.consumed.nodes }} nodes, {{ v.consumed.edges }} links,
                                    {{ v.consumed.elapsedMs }} ms
                                </li>
                            </ul>
                            @if (nextAction(); as next) {
                                @if (next.kind === 'raise') {
                                    <button
                                        mat-stroked-button
                                        class="mt-2"
                                        data-testid="raise-budget"
                                        (click)="start(next.budget)"
                                    >
                                        {{ next.label }}
                                    </button>
                                } @else {
                                    <p class="mt-1" data-testid="narrow-advice">{{ next.text }}</p>
                                }
                            }
                        </inspecto-alert>
                    }
                    @case ('CANCELLED') {
                        <inspecto-alert variant="info" title="Cancelled">
                            The run was cancelled. Nothing was changed on the canvas.
                        </inspecto-alert>
                    }
                    @case ('FAILED') {
                        <inspecto-alert variant="error" title="Run failed">
                            The server run failed{{ v.failure ? ' (' + v.failure + ')' : '' }}. Nothing was changed on
                            the canvas.
                        </inspecto-alert>
                    }
                }
            }
        }
    `,
})
export class LinkAnalysisServerRunComponent {
    private readonly runs = inject(GraphRunsService);
    private sub: Subscription | null = null;

    /** The server algorithm id (`GET /inv/graph/algorithms`). */
    readonly algorithm = input.required<string>();
    readonly params = input<Record<string, unknown>>({});
    /**
     * The open Investigation whose Working Set the canvas draws; null = none, so there is nothing to run over. No log
     * step is sent: the canvas always shows the committed head, which is the server's default.
     */
    readonly investigationId = input<string | null>(null);
    /** DR-D3: an Investigation IS open but the canvas draws the query graph, so no id is passed - say that, not "open one". */
    readonly investigationOpen = input(false);
    private readonly notDrawn =
        "the canvas is showing the query graph - untick 'Show the query graph (to pick seeds)' in the Investigation tab so it draws the Working Set.";
    /** A reason the run cannot start yet that the toolbox knows (e.g. no source and sink picked); '' = none. */
    readonly hold = input('');
    /** May the Subject start a run (`canRunLinkGraphAnalysis`)? */
    readonly allowed = input(true);
    /** Why the browser did not run it: the cap and the size. */
    readonly note = input('');

    /** Hide the Working Set button: the host offers only the index run here (the algorithm has no over-cap server run). */
    readonly indexOnly = input(false);
    /** `degreeCentrality` from the index scores these node ids (1..20) and nothing else. */
    readonly seeds = input<string[]>([]);

    /** Emitted once for a COMPLETED Working Set run, never for any other state; an index run is summarised in this control instead. */
    readonly completed = output<GraphRunResult>();

    /** The Dataset picked when the Space has several indexes; '' = the first. */
    private readonly pickedDataset = signal('');
    /** What the last started run read from, so a raised-budget retry and the error text keep the same input. */
    private lastInput: 'workingSet' | 'index' = 'workingSet';

    readonly supportsIndex = computed(
        () =>
            !!this.runs
                .catalogue()
                ?.algorithms?.find((a) => a.id === this.algorithm())
                ?.engines?.includes('index'),
    );
    /** The indexes this algorithm may run on: only when the catalogue says `index` AND the Space serves indexes AND one exists. */
    readonly indexChoices = computed<LinkIndexSummary[]>(() => {
        const l = this.runs.indexes();
        return this.supportsIndex() && l?.enabled ? l.indexes : [];
    });
    readonly index = computed<LinkIndexSummary | null>(() => {
        const all = this.indexChoices();
        return all.find((i) => i.dataset === this.pickedDataset()) ?? all[0] ?? null;
    });
    /** Why Run on index cannot be pressed, stated - or ''. */
    readonly indexBlockedReason = computed(() => {
        if (!this.allowed())
            return 'Running on the index needs the Run link graph analysis capability, which your role does not hold.';
        if (!this.investigationId())
            return this.investigationOpen()
                ? `Running on the index starts from the Working Set, but ${this.notDrawn}`
                : 'Running on the index starts from an Investigation - open or start an Investigation first.';
        const hops = this.params()['hops'];
        if (this.algorithm() === 'neighborhood' && typeof hops === 'number' && hops > 2)
            return 'The index answers at most 2 hops. Lower the hops, or run it on the Working Set.';
        if (this.algorithm() === 'degreeCentrality') {
            if (!this.seeds().length)
                return 'Pick 1 to 20 nodes to score: from the index, degree centrality scores only the nodes you pick.';
            if (this.seeds().length > 20)
                return `${this.seeds().length} nodes are picked; the index scores at most 20 at a time.`;
        }
        return this.hold();
    });
    /** What an index run answered, in words (the canvas has no apply path for these results). */
    readonly indexSummary = computed<{ headline: string; lines: string[] } | null>(() => {
        const v = this.view();
        const r = v?.result;
        if (!r || !v.source) return null;
        if (r.scores)
            return {
                headline: `${r.scores.length} node${r.scores.length === 1 ? '' : 's'} scored by in plus out links.`,
                lines: (r.scores as { label: string; score: number }[])
                    .slice(0, 10)
                    .map((x) => `${x.label}: ${x.score}`),
            };
        const nodes = r.nodes ?? [];
        const links = r.edges ?? [];
        return {
            headline: `${nodes.length} node${nodes.length === 1 ? '' : 's'} and ${links.length} link${links.length === 1 ? '' : 's'} reached${r.truncated ? ' (the server cut the list)' : ''}.`,
            lines: nodes.slice(0, 10).map((n) => n.label),
        };
    });

    readonly view = signal<GraphRunView | null>(null);
    readonly error = signal('');
    readonly cancelling = signal(false);
    /** Milliseconds since Run was pressed, as the browser saw them - what a server that reports no elapsed time yet falls back to. */
    private readonly waitedMs = signal(0);
    private startedAt = 0;
    /** The server's own elapsed time when it reports one (> 0, live on a newer server), else the browser-measured wait. */
    readonly elapsedMs = computed(() => {
        const server = this.view()?.consumed.elapsedMs ?? 0;
        return server > 0 ? server : this.waitedMs();
    });
    private readonly starting = signal(false);

    readonly active = computed(() => {
        const v = this.view();
        return this.starting() || (!!v && !isTerminalGraphRun(v.status));
    });
    /** Why Run cannot be pressed, stated - or ''. */
    readonly blockedReason = computed(() => {
        if (!this.allowed())
            return 'Running on the server needs the Run link graph analysis capability, which your role does not hold.';
        if (!this.investigationId())
            return this.investigationOpen()
                ? `Running on the server works on the Working Set, but ${this.notDrawn}`
                : "Running on the server works on an Investigation's Working Set - open or start an Investigation first.";
        return this.hold();
    });
    /**
     * The server's sentence about which limit was hit, WITHOUT the advice it appends ("Raise it..., filter..., or pick..."):
     * the banner states the facts and offers exactly one next action of its own.
     */
    readonly reasonFacts = computed(() => (this.view()?.reason ?? '').replace(/\s+(Raise|Filter|Pick)[\s\S]*$/, ''));
    readonly nextAction = computed<BudgetNextAction | null>(() => {
        const v = this.view();
        return v && v.status === 'BUDGET_EXCEEDED'
            ? budgetNextAction(v, this.runs.catalogue()?.ceilings ?? null)
            : null;
    });

    constructor() {
        inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
        this.runs.loadCatalogue();
        this.runs.loadIndexes();
        // A different algorithm is a different question: drop the previous run (and stop polling it).
        let first = true;
        effect(() => {
            this.algorithm();
            untracked(() => {
                if (first) first = false;
                else this.reset();
            });
        });
    }

    private reset(): void {
        this.sub?.unsubscribe();
        this.view.set(null);
        this.error.set('');
        this.cancelling.set(false);
        this.starting.set(false);
        this.waitedMs.set(0);
    }

    pickIndex(dataset: string): void {
        this.pickedDataset.set(dataset);
    }

    start(budget?: Partial<GraphBudgetView>, via?: 'workingSet' | 'index'): void {
        const investigationId = this.investigationId();
        const input = via ?? this.lastInput;
        const ix = input === 'index' ? this.index() : null;
        if (input === 'index' ? !ix || !!this.indexBlockedReason() : !!this.blockedReason()) return;
        if (!investigationId) return;
        this.lastInput = input;
        this.reset();
        this.starting.set(true);
        this.startedAt = Date.now();
        const req: GraphRunRequest = {
            investigationId,
            algorithm: this.algorithm(),
            ...(ix
                ? {
                      input: 'index' as const,
                      dataset: ix.dataset,
                      sourceCol: ix.mapping.sourceCol,
                      targetCol: ix.mapping.targetCol,
                      ...(ix.mapping.kindCol ? { linkKindCol: ix.mapping.kindCol } : {}),
                      ...(this.algorithm() === 'degreeCentrality' ? { seeds: this.seeds() } : {}),
                  }
                : {}),
            ...(Object.keys(this.params()).length ? { params: this.params() } : {}),
            ...(budget ? { budget } : {}),
        };
        this.sub = this.runs.run(req).subscribe({
            next: (v) => {
                this.starting.set(false);
                this.waitedMs.set(Date.now() - this.startedAt);
                this.view.set(v);
                // The ONE place a server answer reaches the canvas: a COMPLETED run, and only that.
                if (v.status === 'COMPLETED' && v.result && !v.source) this.completed.emit(v.result);
            },
            error: (err) => {
                this.starting.set(false);
                // An index run is NEVER retried on the Working Set: say exactly why it was refused.
                this.error.set(
                    this.lastInput === 'index' && err instanceof HttpErrorResponse && err.status === 422
                        ? indexRefusalMessage(
                              graphRunErrorMessage(err, '').replace(/^The server refused the run: /, ''),
                          )
                        : graphRunErrorMessage(err, 'The server run could not be started.'),
                );
            },
        });
    }

    cancel(): void {
        const v = this.view();
        if (!v || this.cancelling()) return;
        this.cancelling.set(true);
        this.runs.cancel(v.runId).subscribe({
            // Keep polling: the terminal CANCELLED view is what ends the run on screen.
            error: (err) => {
                this.cancelling.set(false);
                this.error.set(graphRunErrorMessage(err, 'The run could not be cancelled.'));
            },
        });
    }
}
