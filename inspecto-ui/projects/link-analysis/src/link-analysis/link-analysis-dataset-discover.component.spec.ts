import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { INSPECTO_GRID_DARK, InspectoGridThemeService } from '@inspecto/core/grid';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { DiscoverResult, InvService } from '@inspecto/link-analysis/api/inv.service';
import {
    discoverCount,
    discoverDrawValues,
    discoverMeasureLine,
    discoverPeriod,
    discoverSourceLine,
} from './dataset-discover';
import { LinkAnalysisDatasetDiscoverComponent } from './link-analysis-dataset-discover.component';
import type { PresetDraw } from './link-analysis-presets.component';

const ANSWER: DiscoverResult = {
    dataset: 'pays',
    computedAt: '2026-11-05T10:00:00Z',
    cached: false,
    rows: 1200,
    roles: { from: 'payer', to: 'payee', kind: 'kind', time: 'ts' },
    entityTypes: {
        assessed: true,
        reason: null,
        items: [
            {
                type: 'wallet',
                label: 'Wallet',
                columns: ['payer', 'payee'],
                distinct: 340,
                approximate: true,
                masked: true,
            },
        ],
    },
    period: {
        assessed: true,
        reason: null,
        column: 'ts',
        from: '2026-10-01T00:00:00Z',
        to: '2026-10-09T23:00:00Z',
        days: 9,
    },
    linkKinds: {
        assessed: true,
        reason: null,
        column: 'kind',
        items: [{ kind: 'p2p', links: 1000 }],
        truncated: false,
    },
    busiest: {
        assessed: true,
        reason: null,
        from: [{ entity: 'masked:aaaa', links: 40, counterparts: 12 }],
        to: [
            { entity: 'masked:bbbb', links: 30, counterparts: 9 },
            { entity: 'masked:aaaa', links: 2, counterparts: 1 },
        ],
        source: { kind: 'index', version: 7, stale: false },
        fromType: 'wallet',
        toType: 'wallet',
    },
    measures: {
        assessed: true,
        reason: null,
        items: [{ column: 'amount', unit: 'USD', rows: 1190, sum: 5000.456, median: 3.5 }],
    },
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisDatasetDiscoverComponent],
    template: `<main>
        <h1>Data</h1>
        <h2>pays</h2>
        <inspecto-link-analysis-dataset-discover
            [dataset]="dataset()"
            [drawWhyNot]="why()"
            (draw)="drawn.push($event)"
        ></inspecto-link-analysis-dataset-discover>
    </main>`,
})
class HostComponent {
    readonly dataset = signal('pays');
    readonly why = signal('');
    readonly drawn: PresetDraw[] = [];
}

function setup(answer: () => unknown = () => of(ANSWER), closeWith: unknown = undefined) {
    const inv = { discover: vi.fn((_d: string) => answer()) };
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of(closeWith) })) };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const c = fixture.debugElement.query(By.directive(LinkAnalysisDatasetDiscoverComponent))
        .componentInstance as LinkAnalysisDatasetDiscoverComponent;
    return { fixture, el, c, inv, dialog, host: fixture.componentInstance };
}

describe('dataset discover helpers', () => {
    it('says "about" for an approximate count and nothing for an unmeasured one', () => {
        expect(discoverCount(12366932, true)).toBe('about 12,366,932');
        expect(discoverCount(5)).toBe('5');
        expect(discoverCount(null)).toBe('');
    });

    it('reads the period in one line, and nothing when it was not measured', () => {
        expect(discoverPeriod(ANSWER)).toBe('2026-10-01 to 2026-10-09 (9 days, by ts)');
        expect(
            discoverPeriod({ ...ANSWER, period: { ...ANSWER.period, assessed: false, reason: 'no Time column' } }),
        ).toBe('');
    });

    it('draws the busiest at both ends once each, and nothing when they were not ranked', () => {
        expect(discoverDrawValues(ANSWER)).toEqual(['masked:aaaa', 'masked:bbbb']);
        expect(
            discoverDrawValues({ ...ANSWER, busiest: { ...ANSWER.busiest, assessed: false, reason: 'timeout' } }),
        ).toEqual([]);
    });

    it('names the link index when it answered, and totals a measure with its unit', () => {
        expect(discoverSourceLine(ANSWER)).toBe('Answered from the link index (version 7).');
        expect(discoverSourceLine({ ...ANSWER, busiest: { ...ANSWER.busiest, source: undefined } })).toBe('');
        expect(discoverMeasureLine(ANSWER.measures.items[0])).toBe(
            'Total 5,000.46 USD · median 3.5 USD · 1,190 rows with a number',
        );
    });
});

describe('LinkAnalysisDatasetDiscoverComponent', () => {
    it('reads nothing until asked, then shows each section and draws the busiest', async () => {
        const { fixture, el, c, inv, host } = setup();
        expect(inv.discover).not.toHaveBeenCalled();
        (el.querySelector('[data-testid="la-discover-run"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(inv.discover).toHaveBeenCalledWith('pays');
        expect(el.querySelector('[data-testid="la-discover-summary"]')?.textContent).toContain('1,200 link rows.');
        expect(el.querySelector('[data-testid="la-discover-types"]')?.textContent).toContain('about 340');
        expect(el.querySelector('[data-testid="la-discover-kinds"]')?.textContent).toContain('p2p: 1,000');
        expect(el.querySelector('[data-testid="la-discover-measures"]')?.textContent).toContain('Total 5,000.46 USD');
        expect(el.querySelector('[data-testid="la-discover-source"]')?.textContent).toContain('link index');

        c.drawThese();
        expect(host.drawn).toEqual([
            { datasetId: 'pays', values: ['masked:aaaa', 'masked:bbbb'], what: 'the busiest entities' },
        ]);
        await expectNoA11yViolations(el);
    });

    it("opens a busiest row's summary, of the type its end holds, and draws what the summary draws", () => {
        const drawn = { datasetId: 'pays', values: ['masked:bbbb'], what: 'masked:bbbb (from its summary)' };
        const { c, dialog, host } = setup(() => of(ANSWER), drawn);
        c.load();
        c.pick({ entity: 'masked:bbbb' }, 'to');
        expect(dialog.open).toHaveBeenCalledTimes(1);
        const calls = dialog.open.mock.calls as unknown as [unknown, { data: unknown }][];
        expect(calls[0][1].data).toEqual({ type: 'wallet', key: 'masked:bbbb', dataset: 'pays', drawWhyNot: '' });
        expect(host.drawn).toEqual([drawn]);
        c.pick({ entity: '' }, 'from');
        expect(dialog.open).toHaveBeenCalledTimes(1);
    });

    it('says why a section was not measured instead of a zero', () => {
        const { fixture, el, c } = setup(() =>
            of({
                ...ANSWER,
                busiest: { assessed: false, reason: 'it took longer than this Space allows', from: [], to: [] },
                measures: { assessed: false, reason: 'no column is a Measure', items: [] },
            }),
        );
        c.load();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-discover-busy-why"]')?.textContent).toContain(
            'took longer than this Space allows',
        );
        expect(el.textContent).toContain('Not totalled: no column is a Measure');
        expect(el.querySelector('[data-testid="la-discover-draw"]')).toBeNull();
    });

    it('does not draw while the host says it cannot, and shows a failed read', () => {
        const { fixture, el, c, host } = setup(() =>
            throwError(() => new HttpErrorResponse({ status: 404, error: { error: { message: 'no such Dataset' } } })),
        );
        c.load();
        fixture.detectChanges();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain('no such Dataset');
        expect(c.busy()).toBe(false);
        host.why.set('Pick a Dataset you can view');
        c.result.set(ANSWER);
        fixture.detectChanges();
        c.drawThese();
        expect(host.drawn).toEqual([]);
        expect(el.textContent).toContain('Pick a Dataset you can view');
    });
    it("drops the answer when its Dataset changes, so Draw these never sends another Dataset's entities", () => {
        const { fixture, el, c, host } = setup();
        c.load();
        fixture.detectChanges();
        expect(c.result()).not.toBeNull();
        host.dataset.set('other');
        fixture.detectChanges();
        expect(c.result()).toBeNull();
        expect(el.querySelector('[data-testid="la-discover-run"]')).not.toBeNull();
        c.drawThese();
        expect(host.drawn).toEqual([]);
    });
});
