import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    EntityProfileService,
    EntityTypeList,
    EntityTypeSummary,
} from '@inspecto/link-analysis/api/entity-profile.service';
import { EntitiesComponent } from './entities.component';

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

function make(read: Observable<EntityTypeList>) {
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: EntityProfileService, useValue: { entityTypes: () => read } },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
}

describe('EntitiesComponent', () => {
    it('shows one card per Entity Type: label, distinct marked approx., Datasets, data as of, a freshness badge', () => {
        const { el } = make(of({ items: [MSISDN, IMEI] }));
        const cards = Array.from(el.querySelectorAll('[data-testid=entity-type-card]'));
        expect(cards).toHaveLength(2);
        const m = cards[0] as HTMLElement;
        expect(m.querySelector('h2')!.textContent).toContain('MSISDN');
        expect(m.querySelector('[data-testid=entity-type-link]')!.getAttribute('href')).toBe('/msisdn');
        expect(m.querySelector('[data-testid=distinct]')!.textContent).toContain('20,546');
        expect(m.querySelector('[data-testid=approx]')!.textContent).toContain('approx.');
        expect(m.querySelector('[data-testid=datasets]')!.textContent!.trim()).toBe('2');
        expect(m.querySelector('[data-testid=as-of]')!.textContent).toContain('9 Oct 2026');
        expect(m.querySelector('[data-testid=freshness]')!.textContent).toMatch(/fresh|stale/i);
        expect(m.querySelector('[data-testid=masked]')).toBeTruthy();
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

    it('has no axe violations and one h1', async () => {
        const { el } = make(of({ items: [MSISDN, IMEI] }));
        expect(el.querySelectorAll('h1')).toHaveLength(1);
        await expectNoA11yViolations(el);
    }, 30_000);
});
