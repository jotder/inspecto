import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    EntityProfile,
    EntityProfileService,
    EntityTypeSummary,
} from '@inspecto/link-analysis/api/entity-profile.service';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { NodeInsightComponent, NodeInsightContext } from '@inspecto/link-analysis/link-analysis/node-insight.component';
import { EntityComponent } from './entity.component';

@Component({
    standalone: true,
    imports: [EntityComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-la-entity></inspecto-la-entity>`,
})
class Host {}

/** The node dialog's sections have their own spec; here only the context the page hands them matters. */
@Component({
    selector: 'inspecto-la-node-insight',
    standalone: true,
    template: `<p data-testid="insight">{{ ctx().key }}</p>`,
})
class InsightStub {
    readonly ctx = input.required<NodeInsightContext>();
}

const MASKED: EntityProfile = {
    entity: { type: 'msisdn', key: 'masked:f01a9b8ca40c4eb1', masked: true },
    firstSeen: '2026-10-07T08:00:00Z',
    lastSeen: '2026-10-09T09:00:00Z',
    activeDays: 2,
    coveredDays: 3,
    perDay: [
        { day: '2026-10-07', rows: 3 },
        { day: '2026-10-09', rows: 5 },
    ],
    where: [{ dataset: 'ims_links_dataset', column: 'a_msisdn', rows: 8 }],
    links: {
        degree: 8,
        kinds: [{ kind: 'co_target', count: 7 }],
        percentile: 97,
        from: 'index',
        assessed: true,
        reason: null,
    },
    related: [{ type: 'imei', count: 1, keys: ['masked:0000aaaa1111bbbb'] }],
    dataAsOf: [{ dataset: 'ims_links_dataset', dataAsOf: '2026-10-09T10:36:47Z' }],
    assessed: true,
    reason: null,
};

const TYPE = (over: Partial<EntityTypeSummary> = {}): EntityTypeSummary => ({
    id: 'msisdn',
    label: 'MSISDN',
    normaliser: 'e164',
    masked: true,
    classifications: [],
    bindings: [],
    headline: {
        distinct: null,
        approximate: false,
        rows: null,
        dataAsOf: null,
        computedAt: null,
        assessed: false,
        reason: null,
    },
    ...over,
});

function make(profile: EntityProfile, type: EntityTypeSummary = TYPE(), key = profile.entity.key) {
    const identityGroup = vi.fn(() =>
        of({
            group: { id: 'g1', members: ['msisdn:+249911', 'msisdn:+249922'], assertions: [] },
            atSeq: 1,
            headHash: 'h',
        }),
    );
    const entityProfile = vi.fn(() => of(profile));
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            {
                provide: GammaConfigService,
                useValue: { config$: of({ scheme: 'light' }), config: { scheme: 'light' } },
            },
            { provide: EntityProfileService, useValue: { entityProfile, entityTypes: () => of({ items: [type] }) } },
            { provide: InvService, useValue: { identityGroup } },
            { provide: LensService, useValue: { canManageIncidents: () => true } },
            {
                provide: ActivatedRoute,
                useValue: {
                    paramMap: of(convertToParamMap({ type: profile.entity.type, key })),
                    snapshot: {
                        pathFromRoot: [
                            { url: [] },
                            { url: [{ path: 'studio' }] },
                            { url: [{ path: 'entities' }] },
                            { url: [{ path: profile.entity.type }, { path: key }] },
                        ],
                    },
                },
            },
        ],
    });
    TestBed.overrideComponent(EntityComponent, {
        remove: { imports: [NodeInsightComponent] },
        add: { imports: [InsightStub] },
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el, entityProfile, identityGroup };
}

const section = (el: HTMLElement, name: string) => el.querySelector(`[data-section="${name}"]`) as HTMLElement;

describe('EntityComponent (Entity Profile)', () => {
    it('shows a masked Entity by its alias only, with Seen, Where, Links (degree + percentile) and Related', () => {
        const { el, entityProfile, identityGroup } = make(MASKED);
        expect(entityProfile).toHaveBeenCalledWith('msisdn', 'masked:f01a9b8ca40c4eb1');
        expect(el.querySelector('h1')!.textContent).toContain('masked:f01a9b8ca40c4eb1');
        expect(el.querySelector('[data-testid=masked]')).toBeTruthy();
        expect(section(el, 'seen').classList).toContain('mat-expanded');
        expect(section(el, 'seen').textContent).toContain('2 of 3');
        expect(section(el, 'links').textContent).toContain('p97');
        const rel = section(el, 'related').querySelector('a')!;
        expect(rel.getAttribute('href')).toBe('/studio/entities/imei/masked:0000aaaa1111bbbb');
        // A masked type has no raw key to group by: said, not read.
        expect(identityGroup).not.toHaveBeenCalled();
        expect(section(el, 'lists').querySelector('[data-testid=identity]')!.textContent).toContain('not assessed');
        expect(section(el, 'lists').querySelector('[data-testid=insight]')!.textContent).toContain(
            'masked:f01a9b8ca40c4eb1',
        );
        expect(section(el, 'freshness').textContent).toContain('9 Oct 2026');
    });

    it('reads the identity group by the normalised typed key for an unmasked type', () => {
        const raw = { ...MASKED, entity: { type: 'msisdn', key: '+249 911', masked: false } };
        const { el, identityGroup } = make(raw, TYPE({ masked: false }));
        expect(identityGroup).toHaveBeenCalledWith('msisdn:+249911');
        expect(section(el, 'lists').querySelector('[data-testid=identity]')!.textContent).toContain('2 identifiers');
    });

    it('renders unassessed links as "not assessed" with the reason, never a degree of 0', () => {
        const { el } = make({
            ...MASKED,
            links: { degree: null, kinds: [], percentile: null, from: null, assessed: false, reason: 'no fresh index' },
        });
        expect(section(el, 'links').querySelector('[data-testid=links-not-assessed]')!.textContent).toContain(
            'Not assessed: no fresh index',
        );
        expect(section(el, 'links').querySelector('inspecto-stat-tile')).toBeNull();
    });

    it('Investigate seeds an Investigation through the starter deep link, beside the Entities pages', () => {
        const { el } = make(MASKED);
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        (el.querySelector('[data-testid=investigate]') as HTMLButtonElement).click();
        expect(nav).toHaveBeenCalledWith(['/', 'studio', 'link-analysis'], {
            queryParams: { seed: 'masked:f01a9b8ca40c4eb1', entityType: 'msisdn', dataset: 'ims_links_dataset' },
        });
    });

    it('has no axe violations and one h1', async () => {
        const { el, fixture } = make(MASKED);
        await fixture.whenStable();
        expect(el.querySelectorAll('h1')).toHaveLength(1);
        await expectNoA11yViolations(el);
    }, 30_000);
});
