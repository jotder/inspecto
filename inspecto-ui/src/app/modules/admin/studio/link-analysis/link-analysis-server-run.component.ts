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
    graphRunErrorMessage,
    isTerminalGraphRun,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
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
    imports: [DecimalPipe, MatButtonModule, MatIconModule, InspectoAlertComponent],
    template: `
        @if (note()) {
            <p class="text-secondary mt-1 text-sm">{{ note() }}</p>
        }
        <div class="mt-1 flex flex-wrap items-center gap-2">
            @if (!active()) {
                <button mat-stroked-button data-testid="run-on-server" [disabled]="!!blockedReason()" (click)="start()">
                    <mat-icon svgIcon="heroicons_outline:server-stack"></mat-icon>
                    Run on server
                </button>
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
    /** A reason the run cannot start yet that the toolbox knows (e.g. no source and sink picked); '' = none. */
    readonly hold = input('');
    /** May the Subject start a run (`canRunLinkGraphAnalysis`)? */
    readonly allowed = input(true);
    /** Why the browser did not run it: the cap and the size. */
    readonly note = input('');

    /** Emitted once for a COMPLETED run, never for any other state. */
    readonly completed = output<GraphRunResult>();

    readonly view = signal<GraphRunView | null>(null);
    readonly error = signal('');
    readonly cancelling = signal(false);
    /** Milliseconds since Run was pressed, as the browser saw them - the server reports elapsed time only once a run ends. */
    private readonly waitedMs = signal(0);
    private startedAt = 0;
    readonly elapsedMs = computed(() => Math.max(this.view()?.consumed.elapsedMs ?? 0, this.waitedMs()));
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
            return "Running on the server works on an Investigation's Working Set - open or start an Investigation first.";
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

    start(budget?: Partial<GraphBudgetView>): void {
        const investigationId = this.investigationId();
        if (this.blockedReason() || !investigationId) return;
        this.reset();
        this.starting.set(true);
        this.startedAt = Date.now();
        const req: GraphRunRequest = {
            investigationId,
            algorithm: this.algorithm(),
            ...(Object.keys(this.params()).length ? { params: this.params() } : {}),
            ...(budget ? { budget } : {}),
        };
        this.sub = this.runs.run(req).subscribe({
            next: (v) => {
                this.starting.set(false);
                this.waitedMs.set(Date.now() - this.startedAt);
                this.view.set(v);
                // The ONE place a server answer reaches the canvas: a COMPLETED run, and only that.
                if (v.status === 'COMPLETED' && v.result) this.completed.emit(v.result);
            },
            error: (err) => {
                this.starting.set(false);
                this.error.set(graphRunErrorMessage(err, 'The server run could not be started.'));
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
