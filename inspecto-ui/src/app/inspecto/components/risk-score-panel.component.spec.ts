import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { RiskScore, RiskScoresService } from '../api/risk-scores.service';
import { expectNoA11yViolations } from '../testing/a11y';
import { RiskScorePanelComponent } from './risk-score-panel.component';

const SCORE: RiskScore = {
    model: 'subs',
    entityType: 'subscriber',
    entityKey: 'm1',
    score: 55,
    high: true,
    highThreshold: 50,
    modelVersion: 'abc',
    runId: 'r2',
    scoredAt: '2026-09-02 00:00:00',
    factors: [
        { indicator: 'failed', label: 'Failed top-ups', value: 2, missing: false, weight: 30, cap: 45,
          capped: true, contribution: 45, evidence: [] },
        { indicator: 'spend', value: 10, missing: false, weight: 1, capped: false, contribution: 10, evidence: [] },
        { indicator: 'swaps', value: null, missing: true, weight: 20, capped: false, contribution: 0, evidence: [] },
    ],
};

function mount(api: Partial<RiskScoresService>) {
    TestBed.configureTestingModule({
        imports: [RiskScorePanelComponent],
        providers: [{ provide: RiskScoresService, useValue: api }],
    });
    const fixture = TestBed.createComponent(RiskScorePanelComponent);
    fixture.componentRef.setInput('model', 'subs');
    fixture.componentRef.setInput('entityKey', 'm1');
    fixture.detectChanges();
    return fixture;
}

describe('RiskScorePanelComponent', () => {
    it('renders the score and one bar per factor relative to the largest contribution', async () => {
        const calls: string[] = [];
        const fixture = mount({ latest: (m: string, k: string) => { calls.push(`${m}/${k}`); return of(SCORE); } });
        await fixture.whenStable();
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(calls).toEqual(['subs/m1']);
        expect(el.textContent).toContain('Risk Score');
        expect(el.textContent).toContain('55');
        expect(el.textContent).toContain('High');
        const bars = Array.from(el.querySelectorAll<HTMLElement>('li span.bg-primary'));
        expect(bars.map((b) => b.style.width)).toEqual(['100%', `${(10 / 45) * 100}%`, '0%']);
        expect(el.textContent).toContain('capped at 45');
        expect(el.textContent).toContain('no value, counted as 0');
        await expectNoA11yViolations(el);
    });

    it('renders nothing when there is no visible score', async () => {
        const fixture = mount({ latest: () => throwError(() => ({ status: 404 })) });
        await fixture.whenStable();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('section')).toBeNull();
    });
});
