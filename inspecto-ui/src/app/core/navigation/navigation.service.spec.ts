import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { SessionService } from 'app/inspecto/api/session.service';
import { NavigationService } from './navigation.service';

/**
 * EDG-01 cells 3b + 6: nav entries whose backend lives in an optional module follow that module. Every
 * case is asserted in BOTH directions — a filter that always hid an entry, or never did, would pass a
 * one-sided test.
 */
describe('NavigationService — optional-module nav entries', () => {
    function mount(geoLinkEnabled: boolean, eventsEnabled = true, opsEnabled = true) {
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({
            providers: [
                NavigationService,
                // Only the one signal the filter reads; the real service does an HTTP bootstrap.
                {
                    provide: SessionService,
                    useValue: {
                        geoLinkEnabled: signal(geoLinkEnabled),
                        eventsEnabled: signal(eventsEnabled),
                        opsEnabled: signal(opsEnabled),
                    },
                },
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

    /**
     * EDG-01 cell 6 (EDITIONS CP-13): the Events entry follows `inspecto-events`.
     *
     * ⛔ The third assertion is the one that matters most — `audit` must SURVIVE. The Audit log reads the
     * core `/audit/*` routes, which every edition serves, and EDITIONS §Audit promises Personal "local
     * append-only logs"; hiding it here would take away a documented capability. It is also the adjacent
     * nav id, so a filter written slightly too wide would catch it and nothing else would notice.
     */
    it('shows Events when the feed module registered its routes', async () => {
        const nav = await firstValueFrom(mount(true, true).get());
        expect(findById(nav.default as never, 'events')).toBeDefined();
    });

    it('hides Events when the feed module is absent (Personal) — but never the Audit log', async () => {
        const nav = await firstValueFrom(mount(true, false).get());
        expect(findById(nav.default as never, 'events')).toBeUndefined();
        expect(
            findById(nav.default as never, 'audit'),
            'the Audit log reads core /audit/* and must stay — EDITIONS promises Personal an audit trail',
        ).toBeDefined();
        // Its other siblings are untouched too: an over-wide filter would empty the group.
        expect(findById(nav.default as never, 'processing-status')).toBeDefined();
    });

    /**
     * EDG-01 cell 7 (EDITIONS CP-11): the operational-object screens follow `inspecto-ops`.
     *
     * ⛔ The `alerts` assertion is the one that matters. That pane reads config-authored alert RULES,
     * which every edition serves — it is the ADJACENT nav id and shares the word "alert" with an
     * `OperationalObject` of type ALERT, so an over-wide filter takes it and nothing else notices.
     */
    it('shows Incidents, Case Manager and Tags when the ops module registered its routes', async () => {
        const nav = await firstValueFrom(mount(true, true, true).get());
        for (const id of ['incidents', 'cases', 'tags']) {
            expect(findById(nav.default as never, id), id + ' should be present').toBeDefined();
        }
    });

    it('hides them when the ops module is absent (Personal) — but never Alerts', async () => {
        const nav = await firstValueFrom(mount(true, true, false).get());
        for (const id of ['incidents', 'cases', 'tags']) {
            expect(findById(nav.default as never, id), id + ' should be hidden').toBeUndefined();
        }
        expect(
            findById(nav.default as never, 'alerts'),
            'Alerts reads config alert RULES, which every edition serves — it must survive',
        ).toBeDefined();
        // And the unrelated siblings are untouched: an over-wide filter would empty the group.
        expect(findById(nav.default as never, 'processing-status')).toBeDefined();
        expect(findById(nav.default as never, 'audit')).toBeDefined();
    });
});
