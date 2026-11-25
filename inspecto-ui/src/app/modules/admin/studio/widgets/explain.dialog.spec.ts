import { describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { InsightsService, ExplainResult } from 'app/inspecto/api/insights.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ExplainDialogComponent, driverSentence } from './explain.dialog';

const RESULT: ExplainResult = {
    measure: 'sum_amount_per_count',
    kind: 'ratio',
    grain: 'month',
    previous: '2026-01',
    current: '2026-02',
    before: 133.33,
    after: 166.67,
    delta: 33.33,
    pct: 25,
    cover: 0.8,
    drivers: [
        {
            dimension: 'plan',
            explains: true,
            covered: 2.5,
            surprise: 0.1,
            cover: ['p2'],
            elements: [
                { value: 'p2', before: 33.33, after: 116.67, contribution: 83.33, share: 2.5, surprise: 0.08 },
                { value: 'p1', before: 100, after: 50, contribution: -50, share: -1.5, surprise: 0.02 },
            ],
            valueCount: 2,
        },
    ],
    mixRate: [{ dimension: 'plan', before: 133.33, after: 166.67, mix: -16.67, rate: 50 }],
    refused: [],
    movedWith: [{ measure: 'sum_cost', before: 800, after: 1000, pairs: 11, r: 1, movedWith: true }],
    movedWithNote: 'Moved together over the last 12 months (correlation of the changes). This does not say one causes the other.',
};

function make(result: ReturnType<InsightsService['explain']>) {
    TestBed.configureTestingModule({
        imports: [ExplainDialogComponent],
        providers: [
            { provide: MAT_DIALOG_DATA, useValue: { title: 'Sales', request: {} } },
            { provide: InsightsService, useValue: { explain: () => result } },
        ],
    });
    const f = TestBed.createComponent(ExplainDialogComponent);
    f.detectChanges();
    return f;
}

describe('ExplainDialogComponent', () => {
    it('reads the change, the drivers, the mix and rate and what moved with it — never a cause', async () => {
        const f = make(of(RESULT));
        const el: HTMLElement = f.nativeElement;
        expect(el.querySelector('[data-testid="explain-headline"]')?.textContent).toContain('from 133.33 in 2026-01');
        expect(el.querySelector('[data-testid="explain-driver"]')?.textContent).toContain('p2 explains 250 % of the change.');
        expect(el.querySelector('[data-testid="explain-mix-sentence"]')?.textContent).toContain('−16.67');
        const moved = el.querySelector('[data-testid="explain-moved"]')?.textContent ?? '';
        expect(moved).toContain('rose and fell with it');
        expect(el.textContent).toContain('does not say one causes the other');
        expect(el.textContent?.toLowerCase()).not.toContain('because');
        await expectNoA11yViolations(el);
    });

    it('shows the server refusal in words', () => {
        const f = make(
            throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: "'median' cannot be explained by slices" } } })),
        );
        expect(f.nativeElement.textContent).toContain('Cannot explain this change');
    });

    it('words a dimension that explains nothing', () => {
        expect(driverSentence({ ...RESULT.drivers[0], explains: false })).toBe('No small set of values explains the change.');
        expect(driverSentence({ ...RESULT.drivers[0], cover: ['E', 'N'], covered: 1.1 })).toBe('E and N explain 110 % of the change.');
    });
});
