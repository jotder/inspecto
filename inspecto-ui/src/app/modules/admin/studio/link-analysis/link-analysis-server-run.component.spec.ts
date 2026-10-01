import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Subject, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import {
    GraphAlgorithmCatalogue,
    GraphRunResult,
    GraphRunStatus,
    GraphRunView,
    GraphRunsService,
} from 'app/inspecto/api';
import { LinkAnalysisServerRunComponent } from './link-analysis-server-run.component';

function view(status: GraphRunStatus, over: Partial<GraphRunView> = {}): GraphRunView {
    return {
        runId: 'r1',
        status,
        investigationId: 'inv-1',
        algorithm: 'betweennessCentrality',
        engine: 'in-memory',
        budget: { maxNodes: 5000, maxEdges: 50000, timeoutMs: 30000 },
        budgetClamped: false,
        consumed: { nodes: 800, edges: 1200, elapsedMs: 250, work: 0 },
        progress: { work: 0, fraction: 0 },
        cancelRequested: false,
        cached: false,
        createdAt: '2026-10-01T00:00:00Z',
        ...over,
    };
}

const RESULT: GraphRunResult = {
    algorithm: 'betweennessCentrality',
    kind: 'SCORES',
    dropped: 0,
    elapsedMs: 9,
    scores: [{ id: 'Bob', label: 'Bob', score: 1 }],
};

const CEILINGS = { maxNodes: 100000, maxEdges: 1000000, timeoutMs: 600000 };

function make(opts: { allowed?: boolean; investigationId?: string | null; ceilings?: boolean } = {}) {
    const stream = new Subject<GraphRunView>();
    const runs = {
        catalogue: signal<GraphAlgorithmCatalogue | null>(
            opts.ceilings === false ? null : ({ ceilings: CEILINGS } as GraphAlgorithmCatalogue),
        ),
        loadCatalogue: vi.fn(),
        run: vi.fn(() => stream),
        cancel: vi.fn(() => of({ runId: 'r1', status: 'RUNNING' as GraphRunStatus, cancelRequested: true })),
    };
    TestBed.configureTestingModule({
        imports: [LinkAnalysisServerRunComponent],
        providers: [provideNoopAnimations(), { provide: GraphRunsService, useValue: runs }],
    });
    const fixture = TestBed.createComponent(LinkAnalysisServerRunComponent);
    fixture.componentRef.setInput('algorithm', 'betweennessCentrality');
    fixture.componentRef.setInput('params', {});
    fixture.componentRef.setInput('allowed', opts.allowed ?? true);
    fixture.componentRef.setInput(
        'investigationId',
        opts.investigationId === undefined ? 'inv-1' : opts.investigationId,
    );
    fixture.componentRef.setInput('note', 'This graph has 800 nodes - above the 500-node limit.');
    const completed: GraphRunResult[] = [];
    fixture.componentInstance.completed.subscribe((r) => completed.push(r));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
    const click = (id: string) => {
        q(id)!.click();
        fixture.detectChanges();
    };
    const push = (v: GraphRunView) => {
        stream.next(v);
        fixture.detectChanges();
    };
    return { fixture, runs, stream, completed, el, q, click, push };
}

/** D-4 step 7 - the "Run on server" control and the states a run ends in. */
describe('LinkAnalysisServerRunComponent', () => {
    it('without the capability the button is disabled with a stated reason, and nothing starts', () => {
        const { q, runs, fixture } = make({ allowed: false });
        expect((q('run-on-server') as HTMLButtonElement).disabled).toBe(true);
        expect(q('blocked-reason')!.textContent).toMatch(/capability/);
        fixture.componentInstance.start();
        expect(runs.run).not.toHaveBeenCalled();
    });

    it('without an open Investigation it says what to do instead of failing', () => {
        const { q } = make({ investigationId: null });
        expect((q('run-on-server') as HTMLButtonElement).disabled).toBe(true);
        expect(q('blocked-reason')!.textContent).toMatch(/Investigation/);
    });

    it('starts the run with the Investigation and the parameters - no log step, so the server reads the head', () => {
        const { click, runs, fixture } = make();
        fixture.componentRef.setInput('params', { minSize: 3 });
        fixture.detectChanges();
        click('run-on-server');
        expect(runs.run).toHaveBeenCalledWith({
            investigationId: 'inv-1',
            algorithm: 'betweennessCentrality',
            params: { minSize: 3 },
        });
    });

    it('shows QUEUED then RUNNING with progress and elapsed time, then emits the result once on COMPLETED', () => {
        const { click, push, q, completed } = make();
        click('run-on-server');
        push(view('QUEUED'));
        expect(q('run-progress')!.textContent).toMatch(/Queued/);
        push(
            view('RUNNING', {
                progress: { work: 5, fraction: 0.4 },
                consumed: { nodes: 800, edges: 1200, elapsedMs: 1500, work: 5 },
            }),
        );
        expect(q('run-progress')!.textContent).toMatch(/Running.*40%.*1,500 ms elapsed/s);
        expect(completed).toEqual([]);
        // an engine that counts steps but knows no fraction: the steps are shown, not a made-up percentage
        push(
            view('RUNNING', {
                progress: { work: 316, fraction: 0 },
                consumed: { nodes: 800, edges: 1200, elapsedMs: 0, work: 316 },
            }),
        );
        expect(q('run-progress')!.textContent).toMatch(/316 steps done/);
        expect(q('run-progress')!.textContent).not.toMatch(/%/);
        push(view('COMPLETED', { result: RESULT }));
        expect(q('run-done')!.textContent).toMatch(/Ran on the server/);
        expect(completed).toEqual([RESULT]);
    });

    it('prefers the server fraction and elapsed time when they are > 0, and falls back to steps and the browser clock when they are 0', () => {
        let now = 1_000_000;
        const spy = vi.spyOn(Date, 'now').mockImplementation(() => now);
        try {
            const { click, push, q } = make();
            click('run-on-server');
            now += 5000; // the browser has waited 5 s by the time the first view arrives
            push(
                view('RUNNING', {
                    progress: { work: 316, fraction: 0.25 },
                    consumed: { nodes: 800, edges: 1200, elapsedMs: 1500, work: 316 },
                }),
            );
            // the server's 1 500 ms wins over the browser's 5 000 ms (it used to show the larger of the two)
            expect(q('run-progress')!.textContent).toMatch(/25%.*1,500 ms elapsed/s);
            expect(q('run-progress')!.textContent).not.toMatch(/5,000/);
            // an older server reports neither: steps done and the browser-measured wait
            push(
                view('RUNNING', {
                    progress: { work: 316, fraction: 0 },
                    consumed: { nodes: 800, edges: 1200, elapsedMs: 0, work: 316 },
                }),
            );
            expect(q('run-progress')!.textContent).toMatch(/316 steps done.*5,000 ms elapsed/s);
        } finally {
            spy.mockRestore();
        }
    });

    it('Cancel calls the cancel route and the polled CANCELLED view ends the run with no result', () => {
        const { click, push, q, runs, completed, el } = make();
        click('run-on-server');
        push(view('RUNNING'));
        click('cancel-run');
        expect(runs.cancel).toHaveBeenCalledWith('r1');
        push(view('CANCELLED', { cancelRequested: true }));
        expect(el.textContent).toMatch(/cancelled/i);
        expect(completed).toEqual([]);
        expect(q('run-on-server')).not.toBeNull(); // can run again
    });

    // The negative test needs a probe that would otherwise SUCCEED: this response carries a `result` anyway.
    it('BUDGET_EXCEEDED never reaches the canvas, shows the exact numbers and offers ONE next action', () => {
        const { click, push, q, completed, el } = make();
        click('run-on-server');
        push(
            view('BUDGET_EXCEEDED', {
                exceeded: 'NODES',
                reason: 'the Working Set has 800 nodes; the budget allows 500 (budget.maxNodes). Raise it (up to the server ceiling), filter the Working Set, or pick a cheaper algorithm.',
                budget: { maxNodes: 500, maxEdges: 5000, timeoutMs: 10000 },
                consumed: { nodes: 800, edges: 1200, elapsedMs: 3, work: 0 },
                result: RESULT, // a server that wrongly sent one would still not be applied
            }),
        );
        expect(completed).toEqual([]);
        expect(q('budget-reason')!.textContent!.trim()).toBe(
            'the Working Set has 800 nodes; the budget allows 500 (budget.maxNodes).',
        ); // the facts, without the server's own list of three remedies
        const numbers = q('budget-numbers')!.textContent!.replace(/\s+/g, ' ');
        expect(numbers).toContain('NODES');
        expect(numbers).toContain('Budget: 500 nodes, 5000 links, 10000 ms');
        expect(numbers).toContain('Measured: 800 nodes, 1200 links, 3 ms');
        expect(q('raise-budget')!.textContent).toMatch(/800 nodes/);
        expect(q('narrow-advice')).toBeNull(); // exactly one next action
        expect(el.querySelector('[data-testid="run-done"]')).toBeNull();
    });

    it('the raise-budget action re-runs with exactly the budget the server said it needs', () => {
        const { click, push, runs } = make();
        click('run-on-server');
        push(
            view('BUDGET_EXCEEDED', {
                exceeded: 'NODES',
                reason: 'r',
                budget: { maxNodes: 500, maxEdges: 5000, timeoutMs: 10000 },
            }),
        );
        click('raise-budget');
        expect(runs.run).toHaveBeenLastCalledWith({
            investigationId: 'inv-1',
            algorithm: 'betweennessCentrality',
            budget: { maxNodes: 800 },
        });
    });

    it('when the need is above the server ceiling the one action is to narrow the Working Set, not to raise', () => {
        const { click, push, q } = make({ ceilings: false });
        click('run-on-server');
        push(view('BUDGET_EXCEEDED', { exceeded: 'NODES', reason: 'r' }));
        expect(q('raise-budget')).toBeNull();
        expect(q('narrow-advice')!.textContent).toMatch(/Filter the Working Set/);
    });

    it('FAILED says so (naming the exception class) and applies nothing', () => {
        const { click, push, completed, el } = make();
        click('run-on-server');
        push(view('FAILED', { failure: 'IllegalStateException' }));
        expect(el.textContent).toMatch(/failed.*IllegalStateException/s);
        expect(completed).toEqual([]);
    });

    it.each([
        [403, '', /capability/],
        [404, '', /not found/],
        [422, 'CONFIG_VALIDATION_FAILED', /refused/],
        [503, 'STORE_BUSY', /busy/],
    ])('a %i from the start is shown as a message, not a thrown error', (status, errorCode, pattern) => {
        const { click, stream, el, fixture } = make();
        click('run-on-server');
        stream.error(new HttpErrorResponse({ status, error: { error: { errorCode, message: 'bad' } } }));
        fixture.detectChanges();
        expect(el.querySelector('inspecto-alert')!.textContent).toMatch(pattern);
    });

    it('destroying the component stops the run being followed', () => {
        const { click, stream, fixture } = make();
        click('run-on-server');
        expect(stream.observed).toBe(true);
        fixture.destroy();
        expect(stream.observed).toBe(false);
    });
});
