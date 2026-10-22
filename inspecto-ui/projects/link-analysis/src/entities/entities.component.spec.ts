import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService, LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    EntityProfileService,
    EntityTypeList,
    EntityTypeSummary,
} from '@inspecto/link-analysis/api/entity-profile.service';
import { LinkAnalysisSettingsService } from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import { EntitiesComponent, NEW_TYPE_LOCKED_REASON } from './entities.component';
import { EntityColumnsComponent } from './entity-columns.component';

@Component({
    standalone: true,
    imports: [EntitiesComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-la-entities></inspecto-la-entities>`,
})
class Host {}

const MSISDN: EntityTypeSummary = {
    id: 'msisdn',
    label: 'MSISDN',
    normaliser: 'e164',
    masked: true,
    classifications: ['MSISDN'],
    bindings: [
        { dataset: 'ims_links_dataset', column: 'a_msisdn', role: 'source' },
        { dataset: 'ims_links_dataset', column: 'b_msisdn', role: 'target' },
        { dataset: 'crm', column: 'msisdn', role: 'attribute' },
    ],
    headline: {
        distinct: 20546,
        approximate: true,
        rows: 19638,
        dataAsOf: '2026-10-09T10:36:47Z',
        computedAt: '2026-10-15T09:00:00Z',
        assessed: true,
        reason: null,
    },
};
const IMEI: EntityTypeSummary = {
    id: 'imei',
    label: 'IMEI',
    normaliser: 'digits',
    masked: false,
    classifications: ['IMEI'],
    bindings: [{ dataset: 'ims_links_dataset', column: 'imei', role: 'attribute' }],
    headline: {
        distinct: null,
        approximate: false,
        rows: null,
        dataAsOf: null,
        computedAt: null,
        assessed: false,
        reason: 'not computed yet',
    },
};

const CELL: EntityTypeSummary = { ...IMEI, id: 'cell', label: 'Location', classifications: ['CELL'], bindings: [] };
const DATASETS: ComponentDef[] = [
    {
        type: 'dataset',
        name: 'ims_links_dataset',
        contentHash: 'h1',
        content: { columns: [{ name: 'imei', classification: 'IMEI' }], primaryEntityType: 'imei' },
    } as unknown as ComponentDef,
];

/** `mode` = the Space's masking mode in force (`maskingModeInForce`); undefined = not read (yet). */
function make(
    read: Observable<EntityTypeList>,
    mode: string | undefined = 'typed',
    opts: { canAuthor?: boolean; tab?: string; datasets?: Observable<ComponentDef[]> } = {},
) {
    const limits = signal<{ maskingModeInForce?: string }>({ maskingModeInForce: mode });
    const entityTypes = vi.fn(() => read);
    const list = vi.fn(() => opts.datasets ?? of(DATASETS));
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: EntityProfileService, useValue: { entityTypes } },
            { provide: LinkAnalysisSettingsService, useValue: { limits } },
            { provide: ComponentsService, useValue: { list } },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(opts.canAuthor ?? true) } },
            ...(opts.tab
                ? [
                      {
                          provide: ActivatedRoute,
                          useValue: {
                              snapshot: { pathFromRoot: [], queryParamMap: convertToParamMap({ tab: opts.tab }) },
                          },
                      },
                  ]
                : []),
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, limits, entityTypes, list };
}

describe('EntitiesComponent', () => {
    it('shows one card per Entity Type: label, distinct marked approx., Datasets, data as of, a freshness badge', () => {
        const { el } = make(of({ items: [MSISDN, IMEI] }));
        const cards = Array.from(el.querySelectorAll('[data-testid=entity-type-card]'));
        expect(cards).toHaveLength(2);
        const m = cards.find((c) => c.textContent!.includes('MSISDN')) as HTMLElement;
        expect(m.querySelector('h2')!.textContent).toContain('MSISDN');
        expect(m.querySelector('[data-testid=entity-type-link]')!.getAttribute('href')).toBe('/msisdn');
        expect(m.querySelector('[data-testid=distinct]')!.textContent).toContain('20,546');
        expect(m.querySelector('[data-testid=approx]')!.textContent).toContain('approx.');
        expect(m.querySelector('[data-testid=datasets]')!.textContent!.trim()).toBe('2');
        expect(m.querySelector('[data-testid=as-of]')!.textContent).toContain('9 Oct 2026');
        expect(m.querySelector('[data-testid=freshness]')!.textContent).toMatch(/fresh|stale/i);
        expect(m.querySelector('[data-testid=masked]')).toBeTruthy();
    });

    it('says "Masked: shown as aliases." only where it is true: never under masking mode none, nothing until the mode is known', () => {
        const { el, fixture, limits } = make(of({ items: [MSISDN, IMEI] }), 'none');
        for (const [mode, shown] of [
            ['none', false],
            [undefined, false],
            ['all', true],
        ] as const) {
            limits.set({ maskingModeInForce: mode });
            fixture.detectChanges();
            const masked = Array.from(el.querySelectorAll('[data-testid=entity-type-card]')).map(
                (c) => !!c.querySelector('[data-testid=masked]'),
            );
            // MSISDN is set to masked, IMEI is not; mode all masks both
            expect(masked, String(mode)).toEqual(shown ? [true, true] : [false, false]);
        }
    });

    it('renders an unassessed headline as "not assessed" with its reason, never as zero', () => {
        const { el } = make(of({ items: [IMEI] }));
        const card = el.querySelector('[data-testid=entity-type-card]') as HTMLElement;
        expect(card.querySelector('[data-testid=not-assessed]')!.textContent).toContain(
            'Not assessed: not computed yet',
        );
        expect(card.querySelector('[data-testid=distinct]')).toBeNull();
        expect(card.querySelector('[data-testid=approx]')).toBeNull();
        expect(card.querySelector('[data-testid=freshness]')!.textContent).toContain('Not assessed');
        expect(card.textContent).not.toMatch(/\b0\b/);
    });

    it('says when no Entity Type is in force, and states a failed read', () => {
        const { el } = make(
            throwError(() => new HttpErrorResponse({ status: 403, error: { error: { message: 'forbidden' } } })),
        );
        expect(el.querySelector('[data-testid=entities-error]')).toBeTruthy();
    });

    it('shows the types in your data, primary ones first, and the rest collapsed under Not in your data', () => {
        const { el } = make(of({ items: [MSISDN, CELL, IMEI] }));
        const present = el.querySelector('[data-testid=present-types]') as HTMLElement;
        const labels = Array.from(present.querySelectorAll('[data-testid=entity-type-link]')).map((a) =>
            a.textContent!.trim(),
        );
        // IMEI is the primary Entity Type of ims_links_dataset, so it leads; Location binds to no column
        expect(labels).toEqual(['IMEI', 'MSISDN']);
        expect(present.querySelector('[data-testid=primary-chip]')!.textContent).toContain(
            'Primary in ims_links_dataset',
        );
        const absent = el.querySelector('[data-testid=absent-types]') as HTMLElement;
        expect(absent.textContent).toContain('Not in your data (1)');
        expect(absent.querySelector('mat-expansion-panel-header')!.getAttribute('aria-expanded')).toBe('false');
    });

    it('reads the Dataset registry once, not once per Entity Type', () => {
        const { list, entityTypes } = make(of({ items: [MSISDN, CELL, IMEI] }));
        expect(list).toHaveBeenCalledTimes(1);
        expect(list).toHaveBeenCalledWith('dataset');
        expect(entityTypes).toHaveBeenCalledTimes(1);
    });

    it('a failed Dataset read leaves the cards and is stated under Columns', () => {
        const { el } = make(of({ items: [MSISDN] }), 'typed', {
            tab: 'columns',
            datasets: throwError(() => new HttpErrorResponse({ status: 500 })),
        });
        expect(el.querySelector('[data-testid=datasets-error]')).toBeTruthy();
    });

    it('?tab=columns opens Columns; a change there reads the Entity Types again and states the result', () => {
        const { fixture, el, entityTypes, list } = make(of({ items: [MSISDN, IMEI] }), 'typed', { tab: 'columns' });
        const columns = fixture.debugElement.query((d) => d.componentInstance instanceof EntityColumnsComponent);
        expect(columns).toBeTruthy();
        (columns.componentInstance as EntityColumnsComponent).changed.emit({
            kind: 'column',
            dataset: 'ims_links_dataset',
            column: 'imei',
            entityType: 'imei',
            previous: null,
        });
        fixture.detectChanges();
        expect(entityTypes).toHaveBeenCalledTimes(2);
        expect(list).toHaveBeenCalledTimes(2);
        expect(el.querySelector('[data-testid=columns-result]')!.textContent).toContain(
            '"imei" in ims_links_dataset now holds IMEI. IMEI is in 1 column of 1 Dataset.',
        );
    });

    it('states a change only from the Entity Types read after it, and a newer read replaces one in flight', () => {
        const first = new Subject<EntityTypeList>();
        const reads = [of({ items: [MSISDN, IMEI] }), first, of({ items: [IMEI] })];
        const { fixture, el, entityTypes } = make(of({ items: [] }), 'typed', { tab: 'columns' });
        entityTypes.mockImplementation(() => reads.shift()!);
        fixture.detectChanges();
        const page = fixture.debugElement.query((d) => d.componentInstance instanceof EntitiesComponent)
            .componentInstance as EntitiesComponent;
        page.load(); // the initial state the change is made against
        fixture.detectChanges();
        page.onChanged({
            kind: 'column',
            dataset: 'ims_links_dataset',
            column: 'imei',
            entityType: 'imei',
            previous: null,
        });
        fixture.detectChanges();
        // the re-read has not answered: nothing is stated from the old Entity Types
        expect(el.querySelector('[data-testid=columns-result]')!.textContent!.trim()).toBe('');
        page.onChanged({
            kind: 'column',
            dataset: 'ims_links_dataset',
            column: 'imei',
            entityType: null,
            previous: 'imei',
        });
        fixture.detectChanges();
        first.next({ items: [MSISDN, IMEI] }); // the older read answers late: dropped
        fixture.detectChanges();
        expect(page.items()!.map((t) => t.id)).toEqual(['imei']);
        expect(el.querySelector('[data-testid=columns-result]')!.textContent).toContain(
            'no longer holds an Entity Type',
        );
    });

    it('selecting a tab keeps it in the address', () => {
        const { fixture } = make(of({ items: [MSISDN] }));
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        const page = fixture.debugElement.query((d) => d.componentInstance instanceof EntitiesComponent);
        (page.componentInstance as EntitiesComponent).selectTab(1);
        expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { tab: 'columns' } }));
    });

    it('New Entity Type opens the form', () => {
        const { fixture, el } = make(of({ items: [MSISDN] }));
        expect(el.querySelector('inspecto-la-new-entity-type')).toBeNull();
        (el.querySelector('[data-testid=new-entity-type-open]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(el.querySelector('inspecto-la-new-entity-type')).toBeTruthy();
    });

    it('without the right New Entity Type is locked with its reason', () => {
        const { el } = make(of({ items: [MSISDN] }), 'typed', { canAuthor: false });
        expect(el.querySelector('[data-testid=new-entity-type-open]')).toBeNull();
        expect(el.querySelector('[data-testid=new-entity-type-locked]')!.textContent).toContain(NEW_TYPE_LOCKED_REASON);
    });

    it('has no axe violations and one h1', async () => {
        const { el } = make(of({ items: [MSISDN, IMEI] }));
        expect(el.querySelectorAll('h1')).toHaveLength(1);
        await expectNoA11yViolations(el);
    }, 30_000);

    it('no Entity Type in the data: the empty state says why and opens Columns, its one next action (LA-ONBOARD-1 phase 8)', async () => {
        const { fixture, el } = make(of({ items: [CELL] }));
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        const empty = el.querySelector('[data-testid=types-empty]') as HTMLElement;
        expect(empty.textContent).toContain('No column of your Datasets is tied to an Entity Type yet');
        (empty.querySelector('button') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { tab: 'columns' } }));
        expect(el.querySelector('[data-testid=types-about] [data-testid=term-help]')?.getAttribute('aria-label')).toBe(
            'What is an Entity Type?',
        );
        await expectNoA11yViolations(el);
    });
});
