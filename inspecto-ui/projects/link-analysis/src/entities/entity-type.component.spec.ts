import { ChangeDetectionStrategy, Component } from '@angular/core';
import { By } from '@angular/platform-browser';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { LinkAnalysisSettingsService } from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { EntityProfileService, EntityTypeProfile } from '@inspecto/link-analysis/api/entity-profile.service';
import { EntityTypeComponent } from './entity-type.component';

@Component({
    standalone: true,
    imports: [EntityTypeComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-la-entity-type></inspecto-la-entity-type>`,
})
class Host {}

const OK = { assessed: true, reason: null };

const PROFILE: EntityTypeProfile = {
    entityType: { id: 'msisdn', label: 'MSISDN', normaliser: 'e164', masked: true, classifications: ['MSISDN'] },
    computedAt: '2026-10-15T09:00:00Z',
    cached: true,
    truncated: false,
    bindings: [
        {
            dataset: 'ims_links_dataset',
            column: 'a_msisdn',
            role: 'source',
            timeColumn: 'first_seen',
            rows: 19638,
            nonNull: 19600,
            fillRate: 0.998,
            distinct: 20546,
            approximate: true,
            failNormaliser: 3,
            ...OK,
        },
    ],
    volume: {
        distinct: 20546,
        approximate: true,
        rows: 19638,
        sentinels: [
            { dataset: 'ims_links_dataset', column: 'a_msisdn', kind: 'builtin', value: '-99', rows: 12, share: 0.001 },
            { dataset: 'ims_links_dataset', column: 'a_msisdn', kind: 'dominant', value: null, rows: 4000, share: 0.2 },
        ],
        ...OK,
    },
    shapes: {
        length: [
            { length: 12, rows: 19000 },
            { length: 11, rows: 600 },
        ],
        prefix: [{ prefix: '249', rows: 19500 }],
        prefixLength: 3,
        charClass: { digits: 19638, letters: 0, mixed: 0 },
        sampled: false,
        ...OK,
    },
    time: {
        earliest: '2026-10-07T00:10:00Z',
        latest: '2026-10-09T10:36:47Z',
        perDay: [
            { day: '2026-10-07', rows: 100, distinct: 40 },
            { day: '2026-10-09', rows: 300, distinct: 90 },
        ],
        missingDays: ['2026-10-08'],
        dataAsOf: [{ dataset: 'ims_links_dataset', dataAsOf: '2026-10-09T10:36:47Z' }],
        ...OK,
    },
    lineage: [
        {
            dataset: 'ims_links_dataset',
            pipelines: ['ims_links'],
            collectors: ['ims_sftp'],
            files: 4,
            rows: 19638,
            latestIngestedAt: '2026-10-09T11:00:00Z',
            waitingForIndex: 0,
            problemFiles: 1,
            ...OK,
        },
    ],
    links: {
        from: 'index',
        kinds: [],
        degree: null,
        hubThreshold: null,
        hubs: null,
        singleLink: null,
        assessed: false,
        reason: 'no fresh index',
    },
    relationships: [
        {
            otherType: 'imei',
            dataset: 'ims_links_dataset',
            oneToOneShare: 0.999,
            maxPerEntity: 15,
            threshold: 6,
            entitiesAtOrAboveThreshold: 1,
            ...OK,
        },
    ],
    measures: [
        {
            dataset: 'ims_links_dataset',
            column: 'a_msisdn',
            measure: 'amount',
            unit: 'USD',
            rows: 19000,
            sum: 1250.5,
            min: 0.5,
            max: 400,
            distribution: {
                entities: 909,
                p50: 12.5,
                p90: 80,
                max: 400,
                sampled: true,
                every: 220,
                sampleEntities: 1000,
                approximate: true,
                truncated: false,
                ...OK,
            },
            ...OK,
        },
    ],
    masking: { mode: 'typed' },
};

function make(
    profile: EntityTypeProfile,
    opts: { canEdit?: boolean; confirm?: boolean; del?: ReturnType<typeof vi.fn> } = {},
) {
    const read = vi.fn(() => of(profile));
    const del = opts.del ?? vi.fn(() => of({}));
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            {
                provide: GammaConfigService,
                useValue: { config$: of({ scheme: 'light' }), config: { scheme: 'light' } },
            },
            { provide: EntityProfileService, useValue: { entityTypeProfile: read } },
            { provide: LinkAnalysisSettingsService, useValue: { deleteEntityType: del } },
            { provide: LensService, useValue: { canAuthorWorkbench: () => opts.canEdit ?? true } },
            {
                provide: InspectoConfirmService,
                useValue: { confirmDestructive: vi.fn(async () => opts.confirm ?? true) },
            },
            {
                provide: ActivatedRoute,
                useValue: {
                    paramMap: of(convertToParamMap({ type: 'msisdn' })),
                    snapshot: {
                        pathFromRoot: [{ url: [] }, { url: [{ path: 'entities' }] }, { url: [{ path: 'msisdn' }] }],
                    },
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, read, del };
}

const page = (fixture: ReturnType<typeof make>['fixture']) =>
    fixture.debugElement.query(By.directive(EntityTypeComponent)).componentInstance as EntityTypeComponent;

const section = (el: HTMLElement, name: string) => el.querySelector(`[data-section="${name}"]`) as HTMLElement;

describe('EntityTypeComponent (Entity Type Profile)', () => {
    it('reads the profile of the routed type and draws every section, the first one open', () => {
        const { el, read } = make(PROFILE);
        expect(read).toHaveBeenCalledWith('msisdn');
        expect(el.querySelector('h1')!.textContent).toContain('MSISDN');
        const names = Array.from(el.querySelectorAll('mat-expansion-panel')).map((p) => p.getAttribute('data-section'));
        expect(names).toEqual([
            'identity',
            'bindings',
            'lineage',
            'volume',
            'shapes',
            'time',
            'links',
            'relationships',
            'measures',
            'not-here',
        ]);
        expect(section(el, 'identity').classList).toContain('mat-expanded');
        expect(section(el, 'volume').classList).not.toContain('mat-expanded');
        expect(section(el, 'identity').textContent).toContain('e164');
        expect(el.querySelector('inspecto-la-entity-lookup form[aria-label="Open an Entity"]')).toBeTruthy();
    });

    it('the Masked tile reads the EFFECTIVE state: under masking mode none a masked type says No and the hint explains why', () => {
        const { el } = make({ ...PROFILE, masking: { mode: 'none', masked: false } });
        const tile = el.querySelector('[data-testid=masked-tile]') as HTMLElement;
        expect(tile.textContent).toContain('No');
        expect(tile.textContent).not.toContain('Yes');
        expect(tile.textContent).toContain('Off in this Space (mode none)');
        expect(tile.textContent).toContain('type set to masked');
    });

    it('the Masked tile says Yes under mode typed for a masked type', () => {
        const { el } = make(PROFILE);
        const tile = el.querySelector('[data-testid=masked-tile]') as HTMLElement;
        expect(tile.textContent).toContain('Yes');
        expect(tile.textContent).toContain('Mode typed');
    });

    it('marks approximate counts, and never shows a dominant value', () => {
        const { el } = make(PROFILE);
        const v = section(el, 'volume');
        expect(v.querySelector('[data-testid=approx]')!.textContent).toContain('approx.');
        expect(v.textContent).toContain('20,546');
        expect(v.textContent).toContain('-99');
        expect(v.textContent).toContain('one dominant value (not shown)');
    });

    it('renders an unassessed section as "not assessed" with its reason, never as zero, and lists it under What is not here', () => {
        const { el } = make(PROFILE);
        const links = section(el, 'links');
        expect(links.querySelector('[data-testid=not-assessed]')!.textContent).toContain(
            'Not assessed: no fresh index',
        );
        expect(links.querySelector('inspecto-stat-tile')).toBeNull();
        expect(section(el, 'not-here').textContent).toContain('Links: not assessed: no fresh index.');
    });

    it('reads a section the server did not return as not assessed', () => {
        const { el } = make({ ...PROFILE, shapes: undefined });
        expect(section(el, 'shapes').querySelector('[data-testid=not-assessed]')!.textContent).toContain(
            'Not assessed: not returned by the server',
        );
    });

    it('draws shapes as bar lists and time as a per-day calendar that names a missing day', () => {
        const { el } = make(PROFILE);
        const shapes = section(el, 'shapes');
        expect(shapes.querySelectorAll('[data-testid=bar]').length).toBe(2 + 1 + 3);
        expect(shapes.textContent).toContain('19,000 rows, 100 % of the largest item');
        const cal = section(el, 'time').querySelector('[data-testid=calendar]')!;
        expect(cal.querySelector('[data-day="2026-10-08"]')!.textContent).toContain('missing');
        expect(cal.querySelector('[data-day="2026-10-09"]')!.textContent).toContain('300 rows, 90 distinct');
    });

    it('names the clipped section(s) in the limit banner, and never a section the server did not mark', () => {
        const { el } = make({ ...PROFILE, truncated: true, time: { ...PROFILE.time!, truncated: true } });
        const text = el.querySelector('[data-testid=truncated]')!.textContent!;
        expect(text).toContain('Time (newest 2 days read) is partial');
        expect(text).not.toContain('Volume');
        expect(text).not.toContain('Shapes');
    });

    it('names Links when only the link-kind list was cut, with no Dataset sentence', () => {
        const { el } = make({ ...PROFILE, truncated: true, links: { ...PROFILE.links!, truncated: true } });
        const text = el.querySelector('[data-testid=truncated]')!.textContent!;
        expect(text).toContain('Links is partial');
        expect(text).not.toContain('Dataset');
    });

    it('says the profile may not have read every Dataset only when no section is marked', () => {
        const { el } = make({ ...PROFILE, truncated: true });
        expect(el.querySelector('[data-testid=truncated]')!.textContent).toContain('may not have read every Dataset');
    });

    it('labels the calendar by its one visible heading', () => {
        const { el } = make(PROFILE);
        const time = section(el, 'time');
        const cal = time.querySelector('[data-testid=calendar]')!;
        expect(cal.querySelector('caption')).toBeNull();
        const h = time.querySelector('#' + cal.getAttribute('aria-labelledby'))!;
        expect(h.textContent).toContain('Rows and distinct Entities per day');
    });

    it('shows each bound column x Measure with its unit and the per-Entity totals, saying when they were sampled', () => {
        const { el, fixture } = make(PROFILE);
        const cols = page(fixture).measureCols;
        const row = PROFILE.measures![0];
        const cell = (header: string) => {
            const c = cols.find((x) => x.headerName === header)!;
            return (c.valueGetter as (p: { data: unknown }) => string)({ data: row });
        };
        expect(cell('Sum')).toBe('1,250.5 USD');
        expect(cell('Per Entity, middle')).toBe('12.5 USD');
        expect(cell('Per Entity, largest')).toBe('400 USD');
        expect(cell('Entities')).toBe('909 (1 in 220 sampled)');
        expect(section(el, 'measures').querySelector('mat-panel-description')!.textContent).toContain('1');
    });

    it('a per-Entity read that failed reads not assessed, keeps the sums, and is named under What is not here', () => {
        const failed = {
            ...PROFILE.measures![0],
            distribution: {
                ...PROFILE.measures![0].distribution!,
                assessed: false,
                reason: 'the read ran past entity_profile.timeout_s',
            },
        };
        const { el, fixture } = make({ ...PROFILE, measures: [failed] });
        const cols = page(fixture).measureCols;
        const cell = (header: string) =>
            (cols.find((x) => x.headerName === header)!.valueGetter as (p: { data: unknown }) => string)({
                data: failed,
            });
        expect(cell('Sum')).toBe('1,250.5 USD');
        expect(cell('Per Entity, middle')).toBe('not assessed');
        expect(cell('Note')).toContain('ran past entity_profile.timeout_s');
        expect(section(el, 'not-here').textContent).toContain(
            'Per-Entity totals of amount over ims_links_dataset.a_msisdn: not assessed: the read ran past entity_profile.timeout_s.',
        );
    });

    it('names Measures in the limit banner when the per-Entity read reached its cap', () => {
        const cut = {
            ...PROFILE.measures![0],
            distribution: { ...PROFILE.measures![0].distribution!, truncated: true },
        };
        const { el } = make({ ...PROFILE, truncated: true, measures: [cut] });
        expect(el.querySelector('[data-testid=truncated]')!.textContent).toContain('Measures is partial');
    });

    it('says when the normaliser declares no safe prefix', () => {
        const { el } = make({ ...PROFILE, shapes: { ...PROFILE.shapes!, prefixLength: 0, prefix: [] } });
        expect(section(el, 'shapes').querySelector('[data-testid=no-prefix]')).toBeTruthy();
    });

    it('deletes the type after the confirm and goes back to Entities (LA-ENTITY-TYPE-RETIRE-1)', async () => {
        const { el, fixture, del } = make(PROFILE);
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        const button = el.querySelector('[data-testid=entity-type-delete]') as HTMLButtonElement;
        expect(button.getAttribute('aria-label')).toBe('Delete the Entity Type MSISDN');
        button.click();
        await fixture.whenStable();
        expect(del).toHaveBeenCalledWith('msisdn');
        expect(navigate).toHaveBeenCalledWith(['/', 'entities']);
    });

    it("shows the server's reason when the type is still used, and stays on the page", async () => {
        const reason = "Entity Type 'msisdn' is still used by Dataset columns ims_links_dataset.a_msisdn";
        const { el, fixture } = make(PROFILE, {
            del: vi.fn(() =>
                throwError(
                    () =>
                        new HttpErrorResponse({ status: 409, error: { error: { code: 'CONFLICT', message: reason } } }),
                ),
            ),
        });
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
        await page(fixture).remove();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid=delete-error]')!.textContent).toContain('ims_links_dataset.a_msisdn');
        expect(navigate).not.toHaveBeenCalled();
    });

    it('a declined confirm deletes nothing; without the right there is no Delete', async () => {
        const { fixture, del } = make(PROFILE, { confirm: false });
        await page(fixture).remove();
        expect(del).not.toHaveBeenCalled();
        TestBed.resetTestingModule();
        const reader = make(PROFILE, { canEdit: false });
        expect(reader.el.querySelector('[data-testid=entity-type-delete]')).toBeNull();
    });

    it('has no axe violations and one h1', async () => {
        const { el, fixture } = make(PROFILE);
        await fixture.whenStable();
        expect(el.querySelectorAll('h1')).toHaveLength(1);
        await expectNoA11yViolations(el);
    }, 30_000);
});
