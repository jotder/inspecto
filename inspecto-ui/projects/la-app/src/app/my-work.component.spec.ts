import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService, RunsService } from '@inspecto/core/api';
import { ObjectFilter, ObjectsService } from '@inspecto/core/api/objects.service';
import { SessionService } from '@inspecto/core/auth/session.service';
import { BootstrapProduct } from '@inspecto/core/product/product-host';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisService } from '@inspecto/link-analysis/link-analysis/link-analysis.service';
import { MyWorkComponent } from './my-work.component';

function create(
    opts: {
        views?: unknown;
        datasets?: unknown;
        lists?: unknown;
        batches?: (p: string) => unknown;
        product?: BootstrapProduct | null;
        actor?: string | null;
        objects?: (f: ObjectFilter) => unknown;
    } = {},
) {
    const batches = vi.fn((p: string) => (opts.batches ? opts.batches(p) : of([])));
    const objects = vi.fn((f: ObjectFilter) => (opts.objects ? opts.objects(f) : of([])));
    TestBed.configureTestingModule({
        imports: [MyWorkComponent],
        providers: [
            provideRouter([]),
            {
                provide: SessionService,
                useValue: {
                    product: signal(opts.product ?? null),
                    features: signal({}),
                    actor: signal(opts.actor ?? null),
                },
            },
            { provide: ObjectsService, useValue: { list: objects } },
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
                    fromContent: (id: string, c: Record<string, unknown>) => ({
                        id,
                        name: c['name'],
                        lists: c['lists'],
                    }),
                },
            },
        ],
    });
    const f = TestBed.createComponent(MyWorkComponent);
    f.detectChanges();
    return { f, el: f.nativeElement as HTMLElement, batches, objects };
}

describe('MyWorkComponent - the home page My work (LA-ONBOARD-1 phase 7)', () => {
    // PRODUCT-ASSEMBLY-1 PA-5b: Case Management's work joins My work where the Product's nav offers it.
    it('lists the Incidents and Cases assigned to the caller where the nav offers them, each opening its page (no violations)', async () => {
        const product: BootstrapProduct = {
            id: 'la',
            title: 'Inspecto LA',
            nav: [
                { app: 'case-management', id: 'incidents', path: 'incidents', label: 'Incidents' },
                { app: 'case-management', id: 'cases', path: 'cases', label: 'Cases' },
            ],
        };
        const at = Date.parse('2026-11-10T09:00:00Z');
        const { el, objects } = create({
            product,
            actor: 'ana',
            objects: (f) =>
                of(
                    f.type === 'INCIDENT'
                        ? [
                              {
                                  id: 'i-1',
                                  objectType: 'INCIDENT',
                                  title: 'Lost xDRs',
                                  status: 'OPEN',
                                  description: '',
                                  createdAt: at,
                                  updatedAt: at,
                                  closedAt: 0,
                              },
                          ]
                        : [
                              {
                                  id: 'c-1',
                                  objectType: 'CASE',
                                  title: 'SIM-box ring',
                                  status: 'OPEN',
                                  description: '',
                                  createdAt: at,
                                  updatedAt: at + 1,
                                  closedAt: 0,
                              },
                          ],
                ),
        });
        expect(objects.mock.calls.map(([f]) => [f.type, f.assignee])).toEqual([
            ['INCIDENT', 'ana'],
            ['CASE', 'ana'],
        ]);
        const work = el.querySelector('[data-testid="la-work"]')!;
        expect(work.querySelector('h3')?.textContent?.trim()).toBe('Assigned to you');
        expect(
            [...work.querySelectorAll('[data-testid="la-work-rows"] a')].map((a) => [
                a.textContent?.trim(),
                a.getAttribute('href'),
            ]),
        ).toEqual([
            ['SIM-box ring', '/cases/c-1'],
            ['Lost xDRs', '/incidents/i-1'],
        ]);
        await expectNoA11yViolations(el);
    }, 30_000);

    it('shows no Incidents or Cases, and reads none, where the nav does not offer them (Inspecto LA)', () => {
        const { el, objects } = create();
        expect(el.querySelector('[data-testid="la-work"]')).toBeNull();
        expect(objects).not.toHaveBeenCalled();
    });

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
                {
                    type: 'dataset',
                    name: 'transfers',
                    ref: '',
                    content: { origin: 'import', importPipeline: 'transfers' },
                },
                { type: 'dataset', name: 'mule_transfers', ref: '', content: {} },
            ]),
            batches: () => of([{ status: 'SUCCESS', end_time: '2026-10-01T08:00:00Z', total_output_rows: '2104' }]),
        });
        const link = el.querySelector('[data-testid="la-cases"] a') as HTMLAnchorElement;
        expect(link.textContent?.trim()).toBe('Ring');
        expect(link.getAttribute('href')).toBe('/link-analysis?view=ring');
        expect(el.querySelector('[data-testid="la-cases"]')?.textContent).toContain(
            'Saved 21 Oct 2026 · Lists: started from Suspects',
        );
        expect(batches).toHaveBeenCalledTimes(1); // only the imported Dataset's Pipeline is read
        expect(batches).toHaveBeenCalledWith('transfers');
        expect(el.querySelector('[data-testid="la-changes"]')?.textContent).toContain(
            'transfers: imported 1 Oct 2026, +2,104 rows',
        );
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

    it('pages the saved views 5 at a time, most recent first, and the search narrows them', () => {
        const views = Array.from({ length: 12 }, (_, i) => ({
            type: 'link-analysis-view',
            name: `v${i}`,
            ref: '',
            content: { name: i === 3 ? 'SIM swap ring' : `View ${i}` },
            modified: `2026-10-${String(10 + i).padStart(2, '0')}T09:00:00Z`,
        }));
        const { f, el } = create({ views: of(views) });
        const names = () =>
            Array.from(el.querySelectorAll('[data-testid="la-cases"] a')).map((a) => a.textContent?.trim());
        expect(names()).toEqual(['View 11', 'View 10', 'View 9', 'View 8', 'View 7']);
        expect(el.querySelector('[data-testid="la-cases-pager"]')?.textContent).toContain('1–5 of 12');

        (el.querySelector('[aria-label="Next page of saved views"]') as HTMLButtonElement).click();
        f.detectChanges();
        expect(names()[0]).toBe('View 6');

        f.componentInstance.setPageSize(25);
        f.detectChanges();
        expect(names().length).toBe(12);

        f.componentInstance.search('sim  SWAP');
        f.detectChanges();
        expect(names()).toEqual(['SIM swap ring']);
        f.componentInstance.search('nothing here');
        f.detectChanges();
        expect(el.querySelector('[data-testid="la-cases-no-match"]')).not.toBeNull();
    });
});
