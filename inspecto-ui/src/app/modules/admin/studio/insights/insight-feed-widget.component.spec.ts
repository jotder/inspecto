import { ChangeDetectionStrategy } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InsightFeed, InsightsService } from 'app/inspecto/api/insights.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { getViz } from 'app/inspecto/viz';
import { getVizComponentLoader } from 'app/inspecto/viz/viz-components';
import '../widgets/widget.kind';
import { INSIGHT_TILE_LIMIT, InsightFeedWidgetComponent } from './insight-feed-widget.component';

const FEED: InsightFeed = {
    profile: 'sales',
    runs: 2,
    latestRun: 'r2',
    total: 3,
    insights: [
        {
            insight_id: 'i1',
            insight_type: 'anomaly',
            dimension: 'region',
            slice_value: 'North',
            period: '2026-11-17',
            score: 82,
            band: 'high',
            explanation: { sentence: 'Sales in North were 40% above usual.' },
        },
        {
            insight_id: 'i2',
            insight_type: 'drift',
            dimension: '*',
            slice_value: null,
            period: '2026-11-17',
            score: 50,
            band: 'elevated',
            explanation: { sentence: 'Sales are drifting up.' },
        },
    ],
};

function create(list: ReturnType<typeof vi.fn>) {
    TestBed.configureTestingModule({
        imports: [InsightFeedWidgetComponent],
        providers: [provideRouter([]), { provide: InsightsService, useValue: { list } }],
    });
    TestBed.overrideComponent(InsightFeedWidgetComponent, {
        set: { changeDetection: ChangeDetectionStrategy.Eager },
    });
    const fixture = TestBed.createComponent(InsightFeedWidgetComponent);
    fixture.componentRef.setInput('viewId', 'sales');
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
}

describe('InsightFeedWidgetComponent (Visualization Type insight-feed)', () => {
    it('is a registered view-bound Visualization Type over an Insight Profile, with a lazy component', () => {
        const p = getViz('insight-feed');
        expect(p?.meta.viewKind).toBe('insight-profile');
        expect(p?.render).toEqual({ kind: 'component', componentKey: 'insight-feed' });
        expect(getVizComponentLoader('insight-feed')).toBeTruthy();
    });

    it('lists the latest Insights: band word, sentence, slice, period, capped and linked to the feed', async () => {
        const list = vi.fn(() => of(FEED));
        const { el } = create(list);
        expect(list).toHaveBeenCalledWith('sales', { limit: INSIGHT_TILE_LIMIT });
        const rows = Array.from(el.querySelectorAll('[data-testid="tile-insight"]'));
        expect(rows.length).toBe(2);
        expect(rows[0].textContent).toContain('High');
        expect(rows[0].textContent).toContain('region = North');
        expect(rows[0].textContent).toContain('2026-11-17');
        expect(rows[0].textContent).toContain('Sales in North were 40% above usual.');
        expect(rows[1].textContent).toContain('Elevated');
        expect(rows[1].textContent).toContain('Whole Measure');
        expect(el.textContent).toContain('2 of 3');
        const link = el.querySelector<HTMLAnchorElement>('a')!;
        expect(link.getAttribute('href')).toBe('/studio/insights?profile=sales');
        await expectNoA11yViolations(el);
    });

    it('a 404 is the empty state naming the insight.scan Job, not an error', async () => {
        const { el } = create(vi.fn(() => throwError(() => ({ status: 404 }))));
        expect(el.querySelector('[role="alert"]')).toBeNull();
        expect(el.textContent).toContain('No Insights yet');
        expect(el.textContent).toContain('insight.scan');
        await expectNoA11yViolations(el);
    });

    it('another failure says so', () => {
        const { el } = create(vi.fn(() => throwError(() => ({ status: 500, error: { message: 'boom' } }))));
        expect(el.querySelector('[role="alert"]')).not.toBeNull();
    });
});
