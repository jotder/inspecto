import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
    Space,
    SpaceComparisonRequest,
    SpaceComparisonResult,
    SpaceComparisonRun,
    SpaceComparisonService,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SPACE_COMPARISON_POLL_MS, SpaceComparisonDialog } from './space-comparison.dialog';

const SPACES: Space[] = [
    { id: 'alpha', displayName: 'Alpha', description: '', createdAt: '' },
    { id: 'beta', displayName: '', description: '', createdAt: '' },
    { id: 'gamma', displayName: 'Gamma', description: '', createdAt: '' },
];

const RESULT: SpaceComparisonResult = {
    spaces: ['alpha', 'gamma'],
    windowDays: 30,
    comparable: ['alpha'],
    notComparable: { gamma: '1 sample(s) in 30d, need >= 2' },
    axes: [],
};

function runOf(status: string, message: string | null = null): SpaceComparisonRun {
    return {
        runId: 'space.comparison-1',
        job: 'space.comparison',
        type: 'space.comparison',
        trigger: 'manual',
        status,
        startedAt: null,
        finishedAt: null,
        durationMs: 0,
        message,
    };
}

function create(api: Partial<SpaceComparisonService>) {
    TestBed.configureTestingModule({
        imports: [SpaceComparisonDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: { spaces: SPACES } },
            { provide: SpaceComparisonService, useValue: api },
            { provide: InspectoConfirmService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(SpaceComparisonDialog);
    fixture.detectChanges();
    return fixture;
}

function checkboxes(el: HTMLElement): HTMLInputElement[] {
    return Array.from(el.querySelectorAll<HTMLInputElement>('mat-checkbox input[type="checkbox"]'));
}

describe('SpaceComparisonDialog', () => {
    afterEach(() => vi.useRealTimers());

    it('refuses fewer than two Spaces with a rendered message, sends nothing, and has no a11y violations', async () => {
        const compare = vi.fn();
        const fixture = create({ compare });
        const el = fixture.nativeElement as HTMLElement;
        expect(checkboxes(el).length).toBe(3);
        expect(el.querySelector('[role="alert"]')).toBeNull();

        // Through the DOM, as the operator does it: tick one Space, press Compare.
        checkboxes(el)[0].click();
        fixture.detectChanges();
        const compareButton = Array.from(el.querySelectorAll<HTMLButtonElement>('mat-dialog-actions button')).find(
            (b) => b.textContent?.trim() === 'Compare',
        )!;
        compareButton.click();
        fixture.detectChanges();

        expect(compare).not.toHaveBeenCalled();
        expect(el.querySelector('fieldset [role="alert"]')?.textContent).toContain('Choose at least two Spaces.');
        await expectNoA11yViolations(el);
    });

    it('sends the chosen Spaces in list order, polls the run and renders the result', async () => {
        vi.useFakeTimers();
        let sent: SpaceComparisonRequest | undefined;
        const runs = [runOf('RUNNING'), runOf('SUCCESS', 'space.comparison: 2 space(s)')];
        const run = vi.fn(() => of(runs.shift()!));
        const result = vi.fn(() => of(RESULT));
        const fixture = create({
            compare: (b: SpaceComparisonRequest) => {
                sent = b;
                return of({ runId: 'space.comparison-1', spaces: b.spaces, status: 'running' });
            },
            run,
            result,
        });
        const c = fixture.componentInstance;
        c.toggle('gamma', true);
        c.toggle('alpha', true);
        c.compare();
        fixture.detectChanges();

        expect(sent).toEqual({ spaces: ['alpha', 'gamma'], window_days: 30, top: 5 });
        expect(c.phase()).toBe('running');
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[role="status"]')?.textContent).toContain('alpha, gamma');

        vi.advanceTimersByTime(SPACE_COMPARISON_POLL_MS);
        expect(c.phase()).toBe('running');
        vi.advanceTimersByTime(SPACE_COMPARISON_POLL_MS);
        fixture.detectChanges();

        expect(run).toHaveBeenCalledTimes(2);
        expect(result).toHaveBeenCalledWith('space.comparison-1');
        expect(c.phase()).toBe('done');
        expect(el.textContent).toContain('1 of 2 Spaces compared over the last 30 days.');
        expect(el.textContent).toContain('1 sample(s) in 30d, need >= 2');
        expect(el.textContent).toContain('Nothing to compare');
        vi.useRealTimers();
        await expectNoA11yViolations(el);
    });

    it('explains a 409 inline and falls back to the Run message when a finished run left no Signal', () => {
        vi.useFakeTimers();
        let attempt = 0;
        const fixture = create({
            compare: () =>
                attempt++ === 0
                    ? throwError(() => new HttpErrorResponse({ status: 409 }))
                    : of({ runId: 'space.comparison-1', spaces: [], status: 'running' }),
            run: () => of(runOf('SUCCESS', 'space.comparison: 2 space(s), 0 comparable over 30d')),
            result: () => of(null),
        });
        const c = fixture.componentInstance;
        const el = fixture.nativeElement as HTMLElement;
        c.toggle('alpha', true);
        c.toggle('beta', true);
        c.compare();
        fixture.detectChanges();
        expect(c.phase()).toBe('form');
        expect(el.textContent).toContain('A comparison is already running in this Space.');

        c.compare();
        vi.advanceTimersByTime(SPACE_COMPARISON_POLL_MS);
        fixture.detectChanges();
        expect(c.error()).toBeNull();
        expect(c.phase()).toBe('done');
        expect(el.textContent).toContain('0 comparable over 30d');
    });

    it('shows a failed run as an error with its message', () => {
        vi.useFakeTimers();
        const fixture = create({
            compare: () => of({ runId: 'space.comparison-1', spaces: [], status: 'running' }),
            run: () => of(runOf('FAILED', 'space.comparison: not authorized to read Space(s) [beta]')),
            result: vi.fn(),
        });
        const c = fixture.componentInstance;
        c.toggle('alpha', true);
        c.toggle('beta', true);
        c.compare();
        vi.advanceTimersByTime(SPACE_COMPARISON_POLL_MS);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('The comparison did not finish');
        expect(el.textContent).toContain('not authorized to read Space(s) [beta]');
    });
});
