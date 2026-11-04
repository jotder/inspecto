import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { INSPECTO_GRID_DARK, InspectoGridThemeService } from '@inspecto/core/grid';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { EntitySummaryResult, InvService } from '@inspecto/link-analysis/api/inv.service';
import {
    activityBars,
    activityLine,
    summaryCounterpartColumns,
    summaryHeadline,
    summaryMeasureLine,
} from './entity-summary';
import { LinkAnalysisEntitySummaryComponent } from './link-analysis-entity-summary.component';
import type { PresetDraw } from './link-analysis-presets.component';
import { NodeRiskService } from './node-risk.service';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';

const SUMMARY: EntitySummaryResult = {
    entity: { type: 'wallet', key: 'masked:aaaa', masked: true },
    dataset: 'pays',
    assessed: true,
    reason: null,
    links: 4,
    linksOut: 3,
    linksIn: 1,
    counterparts: 3,
    firstSeen: '2026-10-01T10:00:00Z',
    lastSeen: '2026-10-05T10:00:00Z',
    measure: { column: 'amount', unit: 'USD', out: 35, in: 1500 },
    counterpartsByLinks: [
        { entity: 'masked:bbbb', links: 2, out: 2, in: 0, amount: 30 },
        { entity: 'masked:dddd', links: 1, out: 0, in: 1, amount: 1500 },
    ],
    counterpartsByAmount: [{ entity: 'masked:dddd', links: 1, out: 0, in: 1, amount: 1500 }],
    kinds: [
        { kind: 'p2p', links: 3, out: 3, in: 0 },
        { kind: 'cash_out', links: 1, out: 0, in: 1 },
    ],
    kindsTruncated: false,
    activity: [
        { day: '2026-10-01', links: 2 },
        { day: '2026-10-05', links: 1 },
    ],
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisEntitySummaryComponent],
    template: `<main>
        <h1>Entity</h1>
        <h2>Summary</h2>
        <inspecto-link-analysis-entity-summary
            type="wallet"
            [key]="key()"
            dataset="pays"
            [showLists]="lists()"
            [drawWhyNot]="why()"
            (draw)="drawn.push($event)"
        ></inspecto-link-analysis-entity-summary>
    </main>`,
})
class HostComponent {
    readonly key = signal('masked:aaaa');
    readonly lists = signal(true);
    readonly why = signal('');
    readonly drawn: PresetDraw[] = [];
}

function setup(answer: () => unknown = () => of(SUMMARY)) {
    const inv = { entitySummary: vi.fn((_t: string, _k: string, _d: string) => answer()) };
    const profiles = {
        resolveEntity: vi.fn((type: string, _k: string) => of({ type, key: 'masked:rrrr', masked: true })),
    };
    const risk = {
        entityLists: vi.fn(() =>
            of({
                lists: [
                    { id: 'w', title: 'Watch', entityType: 'wallet', retired: false },
                    { id: 'b', title: 'Block', entityType: 'wallet', retired: false },
                    { id: 'x', title: 'Other type', entityType: 'agent', retired: false },
                ],
            }),
        ),
        match: vi.fn((id: string) => of({ matches: [{ matched: id === 'w' }] })),
    };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: NodeRiskService, useValue: risk },
            { provide: EntityProfileService, useValue: profiles },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const c = fixture.debugElement.query(By.directive(LinkAnalysisEntitySummaryComponent))
        .componentInstance as LinkAnalysisEntitySummaryComponent;
    return { fixture, el, c, inv, risk, profiles, host: fixture.componentInstance };
}

describe('entity summary helpers', () => {
    it('reads the headline and the measure in one line each; nothing for an unmeasured or unseen entity', () => {
        expect(summaryHeadline(SUMMARY)).toBe('4 links (3 out, 1 in) with 3 counterparts, 2026-10-01 to 2026-10-05.');
        expect(summaryHeadline({ ...SUMMARY, links: 0 })).toBe('Not in this Dataset: no link names it.');
        expect(summaryHeadline({ ...SUMMARY, assessed: false, links: undefined })).toBe('');
        expect(summaryMeasureLine(SUMMARY)).toBe('amount: 35 USD sent, 1,500 USD received');
        expect(summaryMeasureLine({ ...SUMMARY, measure: null })).toBe('');
        // a side with no number is left out, never shown as 0
        expect(summaryMeasureLine({ ...SUMMARY, measure: { column: 'amount', unit: null, out: null, in: 7 } })).toBe(
            'amount: 7 received',
        );
        expect(summaryMeasureLine({ ...SUMMARY, measure: { column: 'amount', unit: null, out: null, in: null } })).toBe(
            'amount: no number in its links',
        );
    });

    it('scales the activity to the busiest day and says it in words', () => {
        expect(activityBars(SUMMARY).map((b) => b.pct)).toEqual([100, 50]);
        expect(activityLine(SUMMARY)).toBe('Active on 2 days; the busiest was 2026-10-01 with 2 links.');
        expect(activityLine({ ...SUMMARY, activity: null })).toBe('');
    });

    it('shows an amount column only when the Dataset has a Measure', () => {
        expect(summaryCounterpartColumns(true).map((c) => c.field)).toContain('amount');
        expect(summaryCounterpartColumns(false).map((c) => c.field)).not.toContain('amount');
    });
});

describe('LinkAnalysisEntitySummaryComponent', () => {
    it('reads the summary, the lists holding it, and draws it', async () => {
        const { fixture, el, c, inv, risk, host } = setup();
        fixture.detectChanges();
        expect(inv.entitySummary).toHaveBeenCalledWith('wallet', 'masked:aaaa', 'pays');
        expect(el.querySelector('[data-testid="la-summary-headline"]')?.textContent).toContain('4 links (3 out, 1 in)');
        expect(el.querySelector('[data-testid="la-summary-measure"]')?.textContent).toContain('1,500 USD received');
        expect(el.querySelector('[data-testid="la-summary-kinds"]')?.textContent).toContain('cash_out (0 out, 1 in)');
        expect(el.querySelector('[data-testid="la-summary-activity"]')?.textContent).toContain('Active on 2 days');
        expect(el.querySelector('[data-testid="la-summary-by-amount"]')).not.toBeNull();
        expect(risk.match).toHaveBeenCalledTimes(2); // the lists of its type only
        expect(el.querySelector('[data-testid="la-summary-lists"]')?.textContent).toContain('Watch');
        expect(el.querySelector('[data-testid="la-summary-lists"]')?.textContent).not.toContain('Block');

        c.drawIt();
        expect(host.drawn).toEqual([
            { datasetId: 'pays', values: ['masked:aaaa'], what: 'masked:aaaa (from its summary)' },
        ]);
        await expectNoA11yViolations(el);
    });

    it('says why it was not summarised, and never draws then', () => {
        const { fixture, el, c, host } = setup(() =>
            of({
                entity: SUMMARY.entity,
                dataset: 'pays',
                assessed: false,
                reason: 'it took longer than this Space allows',
            }),
        );
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-summary-why"]')?.textContent).toContain('took longer');
        expect(el.querySelector('[data-testid="la-summary-headline"]')).toBeNull();
        c.drawIt();
        expect(host.drawn).toEqual([]);
    });

    it('shows a refused read, and reads again for another entity', () => {
        const { fixture, el, inv, host } = setup(() =>
            throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: 'takes the alias' } } })),
        );
        fixture.detectChanges();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain('takes the alias');
        host.key.set('masked:cccc');
        fixture.detectChanges();
        expect(inv.entitySummary).toHaveBeenLastCalledWith('wallet', 'masked:cccc', 'pays');
    });

    it('resolves a raw key in a POST body first, so a raw value never goes in the URL; an alias goes as it is', () => {
        const { fixture, inv, profiles, host } = setup();
        fixture.detectChanges();
        expect(profiles.resolveEntity).not.toHaveBeenCalled();
        host.key.set('27820000001');
        fixture.detectChanges();
        expect(profiles.resolveEntity).toHaveBeenCalledWith('wallet', '27820000001');
        expect(inv.entitySummary).toHaveBeenLastCalledWith('wallet', 'masked:rrrr', 'pays');
        for (const call of inv.entitySummary.mock.calls) expect(call[1]).not.toBe('27820000001');
    });
});
