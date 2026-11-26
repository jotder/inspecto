import { ChangeDetectionStrategy, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { InsightFeed, InsightsService } from 'app/inspecto/api/insights.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { SessionService } from 'app/inspecto/auth/session.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InsightProfilesComponent } from './insight-profiles.component';

const PROFILE: ComponentDef = {
    type: 'insight-profile',
    name: 'sales',
    ref: 'insight-profile/sales',
    content: {
        id: 'sales',
        dataset: 'orders',
        measure: { agg: 'sum', field: 'amount' },
        field: 'order_date',
        grain: 'week',
        dimensions: ['region'],
        types: ['anomaly', 'drift'],
    },
};

const FEED: InsightFeed = {
    profile: 'sales',
    runs: 1,
    latestRun: '2026-11-25T06:00:00Z',
    total: 1,
    insights: [
        {
            insight_id: 'i1',
            insight_type: 'anomaly',
            dimension: 'region',
            slice_value: 'North',
            period: '2026-11-17',
            score: 82,
            band: 'high',
            status: 'new',
            explanation: { sentence: 'Sales in North were 40% above usual.' },
        },
    ],
};

function create(opts: { author: boolean; feature?: boolean; feed?: 'ok' | '404' }) {
    const feedback = vi.fn(() => of({}));
    const list = vi.fn(() => (opts.feed === '404' ? throwError(() => ({ status: 404 })) : of(FEED)));
    const remove = vi.fn(() => of({}));
    TestBed.configureTestingModule({
        imports: [InsightProfilesComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ComponentsService, useValue: { list: () => of([PROFILE]), remove, get: () => of(PROFILE) } },
            { provide: InsightsService, useValue: { list, feedback } },
            { provide: SessionService, useValue: { features: signal({ insights: opts.feature ?? true }) } },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(opts.author) } },
            { provide: MatDialog, useValue: { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) } },
            { provide: ToastrService, useValue: { success: vi.fn(), info: vi.fn(), error: vi.fn() } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => true) } },
        ],
    });
    TestBed.overrideComponent(InsightProfilesComponent, { set: { changeDetection: ChangeDetectionStrategy.Eager } });
    const fixture = TestBed.createComponent(InsightProfilesComponent);
    fixture.detectChanges();
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, feedback, list, remove };
}

const buttons = (el: HTMLElement, text: string) =>
    Array.from(el.querySelectorAll('button')).filter((b) => b.textContent?.trim() === text);

describe('InsightProfilesComponent', () => {
    it('lists Profiles, shows the first and its Insight feed, and records feedback', async () => {
        const { fixture, el, feedback, list } = create({ author: true });
        expect(el.querySelector('h2')?.textContent).toContain('sales');
        expect(el.textContent).toContain('insights_sales');
        expect(list).toHaveBeenCalledWith('sales');
        const card = el.querySelector('[data-testid="insight"]')!;
        expect(card.textContent).toContain('region = North');
        expect(card.textContent).toContain('Sales in North were 40% above usual.');
        expect(card.querySelector('inspecto-status-badge')?.textContent).toContain('High');
        buttons(el, 'Useful')[0].click();
        fixture.detectChanges();
        expect(feedback).toHaveBeenCalledWith('sales', 'i1', 'useful');
        expect(el.textContent).toContain('Marked useful');
        await expectNoA11yViolations(el);
    });

    it('disables feedback with the reason, and hides authoring, without canAuthorWorkbench', async () => {
        const { el } = create({ author: false });
        expect(buttons(el, 'New Insight Profile').length).toBe(0);
        expect(buttons(el, 'Edit').length).toBe(0);
        const useful = buttons(el, 'Useful')[0];
        expect(useful.disabled).toBe(true);
        expect(useful.getAttribute('title')).toContain('Needs');
        await expectNoA11yViolations(el);
    });

    it('explains that an insight.scan Job produces Insights when no run exists yet', async () => {
        const { el } = create({ author: true, feed: '404' });
        expect(el.textContent).toContain('No Insights yet');
        expect(el.textContent).toContain('insight.scan');
        expect(el.querySelector('a[href="/jobs"]')).toBeTruthy();
        await expectNoA11yViolations(el);
    });

    it('says the add-on is missing when the insights feature is off', () => {
        const { el } = create({ author: true, feature: false });
        expect(el.textContent).toContain('Business Insights is not installed');
        expect(el.querySelector('nav')).toBeNull();
    });

    it('deletes after confirmation', async () => {
        const { el, remove } = create({ author: true });
        buttons(el, 'Delete')[0].click();
        await new Promise((r) => setTimeout(r, 0));
        expect(remove).toHaveBeenCalledWith('insight-profile', 'sales');
    });
});
