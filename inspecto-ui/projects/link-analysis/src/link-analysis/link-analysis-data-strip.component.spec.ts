import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { DbBrowserService } from '@inspecto/core/api/db-browser.service';
import { RunsService } from '@inspecto/core/api/runs.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
import { DatasetFilesPage, InvService, InvestigationCoverage } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisDataStripComponent } from './link-analysis-data-strip.component';

const FILES = {
    dataset: 'calls',
    assessed: true,
    total: 2,
    offset: 0,
    limit: 25,
    readAt: 'x',
    items: [
        {
            name: 'a.csv',
            day: '2026-09-01',
            rows: 10,
            errorRows: null,
            ingestedAt: '2026-09-02T10:00:00Z',
            outcome: 'SUCCESS',
            collector: 'c',
            pipeline: 'p',
            consignmentId: 'b1',
            outputFiles: ['x'],
            indexState: 'INDEXED',
            indexVersion: 3,
        },
        {
            name: 'empty.csv',
            day: null,
            rows: 0,
            errorRows: 0,
            ingestedAt: '2026-09-04T10:00:00Z',
            outcome: 'QUARANTINED',
            collector: 'c',
            pipeline: 'p',
            consignmentId: null,
            outputFiles: [],
            indexState: null,
            indexVersion: null,
        },
    ],
    days: [{ day: '2026-09-03', files: 1, rows: 0 }],
    summary: {
        files: 2,
        rows: 10,
        latestIngestedAt: '2026-09-04T10:00:00Z',
        indexed: 1,
        waiting: 1,
        unknown: 0,
        problem: 1,
    },
    index: { present: true, version: 3, builtAt: 'x', inputsRecorded: 1 },
} as unknown as DatasetFilesPage;

const COVERAGE = {
    id: 'inv1',
    dataset: 'calls',
    zone: 'UTC',
    expectedDays: 3,
    coveredDays: 1,
    missingDays: ['2026-09-02', '2026-09-03'],
    complete: false,
    perDay: [
        { date: '2026-09-01', rows: 10 },
        { date: '2026-09-02', rows: 0 },
        { date: '2026-09-03', rows: 0 },
    ],
    readAt: 'x',
    collectors: {
        assessed: true,
        collectors: [{ collector: 'c1', coveredDays: 1, missingDays: ['2026-09-02'], complete: false }],
    },
} as unknown as InvestigationCoverage;

@Component({
    standalone: true,
    imports: [LinkAnalysisDataStripComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<la-data-strip investigationId="inv1" dataset="calls"
        ><p la-data-alerts data-test="banner">corrected day</p></la-data-strip
    >`,
})
class Host {}

function setup(over: { files?: unknown; coverage?: unknown; asOf?: string | null } = {}): ComponentFixture<Host> {
    const indexes = signal({
        enabled: true,
        indexes: [{ dataset: 'calls', version: 3, stale: false, reason: null, mapping: {}, plan: { added: 1 } }],
    });
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            {
                provide: GammaConfigService,
                useValue: { config$: of({ scheme: 'light' }), config: { scheme: 'light' } },
            },
            {
                provide: InvService,
                useValue: {
                    datasetFiles: vi.fn(() => over.files ?? of(FILES)),
                    investigationCoverage: vi.fn(() => over.coverage ?? of(COVERAGE)),
                },
            },
            {
                provide: DbBrowserService,
                useValue: {
                    datasetFreshness: () =>
                        of({
                            dataset: 'calls',
                            eventDateColumn: 'ts',
                            dataAsOf: over.asOf === undefined ? '2026-09-05T10:00:00Z' : over.asOf,
                        }),
                },
            },
            {
                provide: RunsService,
                useValue: { pending: () => of({ pipeline: 'p', inbox: '', pending: 2, running: false }) },
            },
            { provide: GraphRunsService, useValue: { indexes, loadIndexes: vi.fn() } },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    return f;
}

const q = (f: ComponentFixture<Host>, sel: string): HTMLElement | null => f.nativeElement.querySelector(sel);

async function settle(f: ComponentFixture<Host>): Promise<void> {
    await f.whenStable();
    f.detectChanges();
}

describe('LinkAnalysisDataStripComponent', () => {
    it('renders the one-line strip with a badge once its reads answer', async () => {
        const f = setup();
        await settle(f);
        const text = q(f, '[data-test="strip-text"]')!.textContent!;
        expect(text).toContain('Data as of 5 Sep 2026');
        expect(text).toContain('1 of 3 days');
        expect(text).toContain('2 gaps');
        expect(text).toContain('index v3, 1 file waiting');
        expect(q(f, 'inspecto-status-badge')!.textContent).toContain('WARNING');
    });

    it('projects a banner into the documented mount point and starts closed', async () => {
        const f = setup();
        await settle(f);
        expect(q(f, '[data-test="strip-alerts"] [data-test="banner"]')!.textContent).toContain('corrected day');
        expect(q(f, '[data-test="drawer"]')).toBeNull();
    });

    it('opens a drawer: fresh, complete (gap vs zero-row day), files, index', async () => {
        const f = setup();
        await settle(f);
        q(f, '[data-test="strip-toggle"]')!.click();
        await settle(f);
        expect(q(f, '[data-test="strip-toggle"]')!.getAttribute('aria-expanded')).toBe('true');
        expect(q(f, '[data-test="fresh-asof"]')!.textContent).toContain('5 Sep 2026');
        expect(q(f, '[data-test="fresh-ingested"]')!.textContent).toContain('2026-09-04T10:00:00Z');
        expect(q(f, '[data-test="fresh-inbox"]')!.textContent).toContain('2 Files are waiting in the inbox');
        const cells = Array.from(f.nativeElement.querySelectorAll('[data-test="day-strip"] li')).map((l) =>
            (l as HTMLElement).textContent!.replace(/\s+/g, ' ').trim(),
        );
        expect(cells[0]).toContain('10 rows');
        expect(cells[1]).toContain('no File');
        expect(cells[2]).toContain('0 rows');
        expect(q(f, '[data-test="complete-basis"]')!.textContent).toContain(
            'not against a list of expected Collectors',
        );
        expect(q(f, 'table[aria-label="Per-Collector coverage"]')).not.toBeNull();
        expect(q(f, '[data-test="index-line"]')!.textContent).toContain('Version 3');
        expect(q(f, '[data-test="index-line"]')!.textContent).toContain('1 File waiting');
        expect(q(f, 'inspecto-data-table')).not.toBeNull();
        await expectNoA11yViolations(f.nativeElement);
    });

    it('an unassessed Files read is an explained empty-state, not an empty grid', async () => {
        const f = setup({ files: of({ ...FILES, assessed: false, total: 0, items: [], summary: undefined }) });
        await settle(f);
        q(f, '[data-test="strip-toggle"]')!.click();
        await settle(f);
        expect(q(f, 'inspecto-empty-state')!.textContent).toContain('Files not assessed');
        expect(q(f, 'inspecto-data-table')).toBeNull();
    });

    it('a failed coverage read degrades to "not assessed", never a clean bill', async () => {
        const f = setup({ coverage: throwError(() => new Error('boom')) });
        await settle(f);
        expect(q(f, '[data-test="strip-text"]')!.textContent).toContain('days not assessed');
        q(f, '[data-test="strip-toggle"]')!.click();
        await settle(f);
        expect(q(f, '[data-test="complete-unassessed"]')).not.toBeNull();
    });

    it('an unknown data-as-of date says so', async () => {
        const f = setup({ asOf: null });
        await settle(f);
        expect(q(f, '[data-test="strip-text"]')!.textContent).toContain('Data as-of date not known');
    });
});
