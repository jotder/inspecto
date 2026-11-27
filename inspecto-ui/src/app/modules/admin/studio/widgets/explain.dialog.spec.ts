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

    it('asks depth 2 on demand and draws the pairs, the stale flag and refused pairs', async () => {
        const ADD: ExplainResult = { ...RESULT, kind: 'additive', mixRate: [] };
        const calls: unknown[] = [];
        const withPairs: ExplainResult = {
            ...ADD,
            pairs: {
                profile: 'margin_rev',
                computedAt: '2026-11-26T10:00:00Z',
                previous: '2026-01',
                current: '2026-02',
                before: 800,
                after: 1000,
                stale: true,
                drivers: [{ ...RESULT.drivers[0], dimension: 'product × partner', cover: ['A × X'], covered: 0.9 }],
                refused: [{ dimension: 'plan × city', reason: 'more than 200 value pairs (maxPairValues)' }],
            },
        };
        TestBed.configureTestingModule({
            imports: [ExplainDialogComponent],
            providers: [
                { provide: MAT_DIALOG_DATA, useValue: { title: 'Sales', request: { dataset: 'd' } } },
                {
                    provide: InsightsService,
                    useValue: { explain: (b: { depth?: number }) => (calls.push(b), of(b.depth === 2 ? withPairs : ADD)) },
                },
            ],
        });
        const f = TestBed.createComponent(ExplainDialogComponent);
        f.detectChanges();
        const el: HTMLElement = f.nativeElement;
        expect(el.querySelector('[data-testid="explain-pair-driver"]')).toBeNull();
        const btn = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('pairs of dimensions'))!;
        btn.click();
        f.detectChanges();
        expect(calls[1]).toEqual({ dataset: 'd', depth: 2 });
        expect(el.querySelector('[data-testid="explain-pair-driver"]')?.textContent).toContain('product × partner');
        expect(el.querySelector('[data-testid="explain-pair-driver"]')?.textContent).toContain('A × X explains 90 %');
        expect(el.querySelector('[data-testid="explain-pairs-stale"]')?.textContent).toContain('data changed since this scan');
        expect(el.querySelector('[data-testid="explain-pair-refused"]')?.textContent).toContain('plan × city: not explained');
        expect(el.querySelector('[data-testid="explain-pairs-source"]')?.textContent).toContain('margin_rev');
        await expectNoA11yViolations(el);
    });

    it('shows pairsNote when no Profile stored pairs, and offers no pairs for a ratio', () => {
        const ADD: ExplainResult = { ...RESULT, kind: 'additive', mixRate: [] };
        const none = { ...ADD, pairs: null, pairsNote: 'No precomputed pairs of dimensions exist for this Measure.' };
        TestBed.configureTestingModule({
            imports: [ExplainDialogComponent],
            providers: [
                { provide: MAT_DIALOG_DATA, useValue: { title: 'Sales', request: {} } },
                { provide: InsightsService, useValue: { explain: (b: { depth?: number }) => of(b.depth === 2 ? none : ADD) } },
            ],
        });
        const f = TestBed.createComponent(ExplainDialogComponent);
        f.detectChanges();
        f.componentInstance.loadPairs();
        f.detectChanges();
        expect(f.nativeElement.querySelector('[data-testid="explain-pairs-note"]')?.textContent).toContain('No precomputed pairs');
        TestBed.resetTestingModule();
        const r = make(of(RESULT));
        expect(r.nativeElement.querySelector('[data-testid="explain-pairs"]')).toBeNull();
    });

    it('words a dimension that explains nothing', () => {
        expect(driverSentence({ ...RESULT.drivers[0], explains: false })).toBe('No small set of values explains the change.');
        expect(driverSentence({ ...RESULT.drivers[0], cover: ['E', 'N'], covered: 1.1 })).toBe('E and N explain 110 % of the change.');
    });
});
