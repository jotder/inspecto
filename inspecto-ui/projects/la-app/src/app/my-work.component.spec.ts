import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService, RunsService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisService } from '@inspecto/link-analysis/link-analysis/link-analysis.service';
import { MyWorkComponent } from './my-work.component';

function create(opts: { views?: unknown; datasets?: unknown; lists?: unknown; batches?: (p: string) => unknown } = {}) {
    const batches = vi.fn((p: string) => (opts.batches ? opts.batches(p) : of([])));
    TestBed.configureTestingModule({
        imports: [MyWorkComponent],
        providers: [
            provideRouter([]),
            {
                provide: ComponentsService,
                useValue: {
                    list: (type: string) =>
                        type === 'link-analysis-view' ? (opts.views ?? of([])) : (opts.datasets ?? of([])),
                },
            },
            { provide: InvService, useValue: { listEntityLists: () => opts.lists ?? of({ lists: [] }) } },
            { provide: RunsService, useValue: { batches } },
            {
                provide: LinkAnalysisService,
                useValue: {
                    fromContent: (id: string, c: Record<string, unknown>) => ({ id, name: c['name'], lists: c['lists'] }),
                },
            },
        ],
    });
    const f = TestBed.createComponent(MyWorkComponent);
    f.detectChanges();
    return { f, el: f.nativeElement as HTMLElement, batches };
}

describe('MyWorkComponent - the home page My work (LA-ONBOARD-1 phase 7)', () => {
    it('lists the cases with their lists, each opening its graph, and what changed in imported data (no violations)', async () => {
        const { el, batches } = create({
            views: of([
                {
                    type: 'link-analysis-view',
                    name: 'ring',
                    ref: '',
                    content: { name: 'Ring', lists: { start: 'suspects' } },
                    modified: '2026-10-21T09:00:00Z',
                },
            ]),
            lists: of({ lists: [{ id: 'suspects', title: 'Suspects' }] }),
            datasets: of([
                { type: 'dataset', name: 'transfers', ref: '', content: { origin: 'import', importPipeline: 'transfers' } },
                { type: 'dataset', name: 'mule_transfers', ref: '', content: {} },
            ]),
            batches: () => of([{ status: 'SUCCESS', end_time: '2026-10-01T08:00:00Z', total_output_rows: '2104' }]),
        });
        const link = el.querySelector('[data-testid="la-cases"] a') as HTMLAnchorElement;
        expect(link.textContent?.trim()).toBe('Ring');
        expect(link.getAttribute('href')).toBe('/link-analysis?view=ring');
        expect(el.querySelector('[data-testid="la-cases"]')?.textContent).toContain('Saved 21 Oct 2026 · Lists: started from Suspects');
        expect(batches).toHaveBeenCalledTimes(1); // only the imported Dataset's Pipeline is read
        expect(batches).toHaveBeenCalledWith('transfers');
        expect(el.querySelector('[data-testid="la-changes"]')?.textContent).toContain('transfers: imported 1 Oct 2026, +2,104 rows');
        await expectNoA11yViolations(el);
    });

    it('nothing yet: each half says what to do next (no violations)', async () => {
        const { el } = create({
            views: of([]),
            datasets: of([{ type: 'dataset', name: 't', ref: '', content: { origin: 'import', importPipeline: 't' } }]),
            batches: () => throwError(() => new Error('no ledger')),
        });
        expect(el.querySelector('[data-testid="la-cases-empty"]')?.textContent).toContain('Save view as');
        expect(el.querySelector('[data-testid="la-cases-failed"]')).toBeNull();
        expect(el.querySelector('[data-testid="la-changes-empty"]')?.textContent).toContain('Nothing imported with');
        expect(el.querySelector('[role="alert"]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('cases that could not be read say so with Try again, not "No case yet" and not an error (LA-ONBOARD-1 phase 8)', async () => {
        const { el } = create({ views: throwError(() => new Error('down')) });
        const failed = el.querySelector('[data-testid="la-cases-failed"]');
        expect(failed?.textContent).toContain('could not be read');
        expect(failed?.querySelector('button')?.textContent).toContain('Try again');
        expect(el.querySelector('[data-testid="la-cases-empty"]')).toBeNull();
        expect(el.querySelector('[role="alert"]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('explains what an Entity List is (a saved view is no "case": that is the Incident-management object), one click away', () => {
        const { el } = create();
        const terms = Array.from(el.querySelectorAll('[data-testid="term-help"]')).map((b) =>
            b.getAttribute('aria-label'),
        );
        expect(terms).toEqual(['What is an Entity List?']);
    });
});
