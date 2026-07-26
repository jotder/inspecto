import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentService, LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { AiAssistComponent } from './ai-assist.component';

/** The real `suggest_expectations` result shape — two derived candidates from one column profile. */
const SUGGEST_RESULT = {
    table: 'cdr',
    column: 'cost_usd',
    profile: { rows: 12480, nulls: 0, numeric: true },
    suggestions: [
        { name: 'cdr_cost_usd_not_null', kind: 'non_null', description: 'never null' },
        { name: 'cdr_cost_usd_range', kind: 'range', description: 'observed bounds', min: '0.0', max: '412.75' },
    ],
};

describe('AiAssistComponent', () => {
    let runTool: ReturnType<typeof vi.fn>;
    let toastr: { error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

    beforeEach(() => {
        // LensService persists the chosen lens — state leaks across specs otherwise.
        localStorage.removeItem('inspecto.currentLens');
        runTool = vi.fn();
        toastr = { error: vi.fn(), info: vi.fn() };
    });

    function create(options: { canAuthor?: boolean; tool?: 'suggest_expectations' | 'component_draft' } = {}) {
        TestBed.configureTestingModule({
            imports: [AiAssistComponent],
            providers: [
                provideNoopAnimations(),
                { provide: AgentService, useValue: { runTool } },
                { provide: LensService, useValue: { canAuthorWorkbench: signal(options.canAuthor ?? true) } },
                { provide: ToastrService, useValue: toastr },
            ],
        });
        const fixture = TestBed.createComponent(AiAssistComponent);
        fixture.componentRef.setInput('tool', options.tool ?? 'suggest_expectations');
        fixture.componentRef.setInput('args', { table: 'cdr', column: 'cost_usd' });
        fixture.detectChanges();
        return fixture;
    }

    function clickRun(fixture: ReturnType<typeof create>) {
        const button = (fixture.nativeElement as HTMLElement).querySelector('button');
        expect(button?.hasAttribute('disabled')).toBe(false);
        (button as HTMLButtonElement).click();
        fixture.detectChanges();
    }

    it('sends the pane context straight through as the tool arguments (A3)', () => {
        runTool.mockReturnValue(of(SUGGEST_RESULT));
        const fixture = create();
        clickRun(fixture);

        // The whole point of A3: the operator never re-states what the screen already knows.
        expect(runTool).toHaveBeenCalledWith('suggest_expectations', { table: 'cdr', column: 'cost_usd' });
    });

    it('renders one selectable candidate per suggestion and applies the chosen one', () => {
        runTool.mockReturnValue(of(SUGGEST_RESULT));
        const fixture = create();
        const applied: unknown[] = [];
        fixture.componentInstance.applyDraft.subscribe((d) => applied.push(d));
        clickRun(fixture);

        expect(fixture.componentInstance.drafts()).toHaveLength(2);
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('cdr_cost_usd_not_null');

        // Pick the second candidate, then apply it.
        fixture.componentInstance.select(1);
        fixture.detectChanges();
        fixture.componentInstance.apply(fixture.componentInstance.activeDraft()!);

        expect(applied).toHaveLength(1);
        expect((applied[0] as { label: string }).label).toBe('cdr_cost_usd_range');
        // Applying closes the surface — a stale draft must not linger over the pane.
        expect(fixture.componentInstance.drafts()).toBeNull();
    });

    it('renders anchored findings and still allows Apply so the operator can finish by hand', () => {
        runTool.mockReturnValue(
            of({
                kind: 'expectation',
                clean: false,
                findings: [
                    { severity: 'WARNING', fieldPath: 'severity', message: 'severity defaults to MAJOR' },
                    { severity: 'ERROR', fieldPath: 'column', message: 'column is required' },
                ],
                draft: { name: 'amt-nonneg' },
            }),
        );
        const fixture = create({ tool: 'component_draft' });
        clickRun(fixture);

        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('column is required');
        expect(text).toContain('Needs attention');
        // Worst-first, so an ERROR is never hidden below a WARNING.
        expect(fixture.componentInstance.findingsOf(fixture.componentInstance.activeDraft()!)[0].severity).toBe('ERROR');
    });

    it('diffs the draft against the pane current config, folding unchanged fields away', () => {
        runTool.mockReturnValue(
            of({ kind: 'expectation', clean: true, findings: [], draft: { name: 'amt', severity: 'MAJOR' } }),
        );
        const fixture = create({ tool: 'component_draft' });
        fixture.componentRef.setInput('current', { name: 'amt', severity: 'MINOR' });
        clickRun(fixture);

        // Only the real change is shown up front...
        expect(fixture.componentInstance.diff().map((r) => r.path)).toEqual(['severity']);
        expect(fixture.componentInstance.unchangedCount()).toBe(1);

        // ...but the operator can audit the whole config.
        fixture.componentInstance.toggleUnchanged();
        fixture.detectChanges();
        expect(fixture.componentInstance.diff().map((r) => r.path).sort()).toEqual(['name', 'severity']);
    });

    it('degrades to a disabled, explained affordance on 503 instead of failing the pane', () => {
        runTool.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
        const fixture = create();
        clickRun(fixture);

        expect(fixture.componentInstance.unavailable()).toBe(true);
        expect(fixture.componentInstance.blocked()).toBe(true);
        expect(toastr.info).toHaveBeenCalled();
        expect(toastr.error).not.toHaveBeenCalled();
        const html = fixture.nativeElement as HTMLElement;
        expect(html.textContent).toContain('not installed on this backend');
        // Latched: retrying into the same wall is prevented.
        expect(html.querySelector('button')?.hasAttribute('disabled')).toBe(true);
    });

    it('surfaces the backend message on a rejected request without latching', () => {
        runTool.mockReturnValue(
            throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: 'column is required' } } })),
        );
        const fixture = create();
        clickRun(fixture);

        expect(toastr.error).toHaveBeenCalledWith('column is required');
        expect(fixture.componentInstance.unavailable()).toBe(false);
        expect(fixture.componentInstance.blocked()).toBe(false);
    });

    it('is gated on canAuthorWorkbench and never calls the tool when the lens cannot author', () => {
        const fixture = create({ canAuthor: false });
        expect(fixture.componentInstance.blocked()).toBe(true);
        expect((fixture.nativeElement as HTMLElement).querySelector('button')?.hasAttribute('disabled')).toBe(true);

        // Defense in depth: the method itself refuses, not just the button.
        fixture.componentInstance.run();
        expect(runTool).not.toHaveBeenCalled();
    });

    it('has no a11y violations while showing a draft', async () => {
        runTool.mockReturnValue(of(SUGGEST_RESULT));
        const fixture = create();
        clickRun(fixture);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('re-enables when the pane clears [disabled] after first render', () => {
        // Regression guard: `blocked` is a computed(). With plain @Input fields instead of signal
        // inputs it never invalidated, so a pane flipping disabled true→false left the button dead
        // forever. Caught in the offline preview, not by the original specs — which only ever set
        // the input BEFORE the first render.
        runTool.mockReturnValue(of(SUGGEST_RESULT));
        const fixture = create();
        fixture.componentRef.setInput('disabled', true);
        fixture.componentRef.setInput('disabledReason', 'Select a column first');
        fixture.detectChanges();
        const button = () => (fixture.nativeElement as HTMLElement).querySelector('button')!;
        expect(button().hasAttribute('disabled')).toBe(true);
        expect(fixture.componentInstance.blockedReason()).toBe('Select a column first');

        fixture.componentRef.setInput('disabled', false);
        fixture.componentRef.setInput('disabledReason', '');
        fixture.detectChanges();
        expect(fixture.componentInstance.blocked()).toBe(false);
        expect(button().hasAttribute('disabled')).toBe(false);

        button().click();
        expect(runTool).toHaveBeenCalled();
    });

    it('recomputes the diff when the pane changes [current] while a draft is shown', () => {
        // Same computed-over-input trap, on the diff baseline.
        runTool.mockReturnValue(
            of({ kind: 'expectation', clean: true, findings: [], draft: { name: 'amt', severity: 'MAJOR' } }),
        );
        const fixture = create({ tool: 'component_draft' });
        clickRun(fixture);
        expect(fixture.componentInstance.diff().map((r) => r.path)).toEqual(['name', 'severity']);

        fixture.componentRef.setInput('current', { name: 'amt', severity: 'MAJOR' });
        fixture.detectChanges();
        expect(fixture.componentInstance.diff()).toEqual([]);
        expect(fixture.componentInstance.unchangedCount()).toBe(2);
    });
});
