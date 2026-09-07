import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { SessionService } from 'app/inspecto/api/session.service';
import { NavigationService } from './navigation.service';

/**
 * EDG-01 cell 3b (EDITIONS CP-09): the geo map and link analysis nav entries follow the backend module.
 * Both directions are asserted — a filter that always hid them, or never did, would pass a one-sided test.
 */
describe('NavigationService — optional-module nav entries', () => {
    function mount(geoLinkEnabled: boolean) {
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({
            providers: [
                NavigationService,
                // Only the one signal the filter reads; the real service does an HTTP bootstrap.
                { provide: SessionService, useValue: { geoLinkEnabled: signal(geoLinkEnabled) } },
            ],
        });
        return TestBed.inject(NavigationService);
    }

    type Item = { id: string; children?: Item[] };

    /** studio-group is nested under platform-group, so find it at any depth — a top-level find returns nothing. */
    function findById(items: Item[], id: string): Item | undefined {
        for (const it of items) {
            if (it.id === id) return it;
            const hit = it.children ? findById(it.children, id) : undefined;
            if (hit) return hit;
        }
        return undefined;
    }

    function studioChildIds(nav: { default: Item[] }): string[] {
        const studio = findById(nav.default, 'studio-group');
        expect(
            studio,
            'the fixture must actually contain studio-group, or every assertion below is vacuous',
        ).toBeDefined();
        return (studio?.children ?? []).map((c) => c.id);
    }

    it('shows Geo Map Analysis and Link Analysis when the bundle registered their routes', async () => {
        const nav = await firstValueFrom(mount(true).get());
        const ids = studioChildIds(nav as never);
        expect(ids).toContain('studio-geo-map');
        expect(ids).toContain('studio-link-analysis');
    });

    it('hides them — and ONLY them — when the module is absent (Personal)', async () => {
        const nav = await firstValueFrom(mount(false).get());
        const ids = studioChildIds(nav as never);
        expect(ids).not.toContain('studio-geo-map');
        expect(ids).not.toContain('studio-link-analysis');
        // The siblings are untouched: a filter that emptied the group would also "pass" the two lines above.
        expect(ids).toContain('studio-queries');
        expect(ids).toContain('studio-dashboards');
        expect(ids).toContain('menus');
    });

    it('applies the same filter to the compact/futuristic/horizontal variants, which copy from default', async () => {
        const nav = (await firstValueFrom(mount(false).get())) as never as {
            compact: { id: string; children?: { id: string }[] }[];
            horizontal: { id: string; children?: { id: string }[] }[];
        };
        for (const layout of [nav.compact, nav.horizontal]) {
            const studio = findById(layout as Item[], 'studio-group');
            const ids = (studio?.children ?? []).map((c) => c.id);
            expect(ids).not.toContain('studio-geo-map');
            expect(ids).not.toContain('studio-link-analysis');
        }
    });
});
