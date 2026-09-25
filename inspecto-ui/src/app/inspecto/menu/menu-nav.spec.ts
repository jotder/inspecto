import MENU_ROUTE_CONTRACT from 'app/inspecto/contracts/menu-route.contract.json';
import { describe, expect, it } from 'vitest';
import { favoritesNavGroup, landingUrl, MENU_FAVORITES_NAV_ID, menuLeafUrl, menuTreeToNav } from './menu-nav';
import { MenuNode, routeError } from './menu-types';

describe('menuTreeToNav', () => {
    it('maps groups to collapsable, leaves to basic /w/ links, and namespaces ids', () => {
        const nodes: MenuNode[] = [
            {
                id: 'rev',
                title: 'Revenue',
                icon: 'heroicons_outline:banknotes',
                children: [
                    {
                        id: 'top',
                        title: 'TopX',
                        children: [
                            { id: 'usage', title: 'Top usages', binding: { kind: 'dashboard', componentId: 'd1' } },
                        ],
                    },
                ],
            },
        ];
        const nav = menuTreeToNav(nodes);
        expect(nav[0]).toMatchObject({
            id: 'menu-rev',
            title: 'Revenue',
            type: 'collapsable',
            icon: 'heroicons_outline:banknotes',
        });
        const top = nav[0].children![0];
        expect(top).toMatchObject({ id: 'menu-top', type: 'collapsable' });
        const leaf = top.children![0];
        expect(leaf).toMatchObject({ id: 'menu-usage', title: 'Top usages', type: 'basic', link: '/w/usage' });
        expect(leaf.children).toBeUndefined();
    });

    it('renders an empty group as a collapsable with no children', () => {
        const nav = menuTreeToNav([{ id: 'fms', title: 'FMS' }]);
        expect(nav[0]).toMatchObject({ id: 'menu-fms', type: 'collapsable' });
        expect(nav[0].children).toEqual([]);
    });
});

describe('favoritesNavGroup', () => {
    const tree: MenuNode[] = [
        {
            id: 'rev',
            title: 'Revenue',
            children: [
                { id: 'd1', title: 'Dash one', binding: { kind: 'dashboard', componentId: 'c1' } },
                { id: 'd2', title: 'Dash two', binding: { kind: 'widget', componentId: 'c2' } },
            ],
        },
        { id: 'grp', title: 'A group' },
    ];

    it('resolves favorite leaf ids to basic /w/ links under a Favorites group, distinct fav- ids, in favorite order', () => {
        const group = favoritesNavGroup(tree, ['d2', 'd1']);
        expect(group).toMatchObject({ id: MENU_FAVORITES_NAV_ID, title: 'Favorites', type: 'collapsable' });
        expect(group!.children!.map((c) => c.id)).toEqual(['fav-d2', 'fav-d1']);
        expect(group!.children![0]).toMatchObject({ title: 'Dash two', type: 'basic', link: '/w/d2' });
    });

    it('drops ids that no longer resolve to a leaf (deleted item, or a group)', () => {
        const group = favoritesNavGroup(tree, ['d1', 'gone', 'grp']);
        expect(group!.children!.map((c) => c.id)).toEqual(['fav-d1']);
    });

    it('returns null when nothing resolves', () => {
        expect(favoritesNavGroup(tree, [])).toBeNull();
        expect(favoritesNavGroup(tree, ['grp', 'nope'])).toBeNull();
    });
});

describe('UIE-7 route leaves and the landing', () => {
    const tree: MenuNode[] = [
        {
            id: 'fm',
            title: 'Fraud',
            children: [
                { id: 'cases', title: 'Cases', binding: { kind: 'route', route: '/cases?status=open#top' } },
                { id: 'ops', title: 'Fraud operations', binding: { kind: 'dashboard', componentId: 'fm_ops' } },
            ],
        },
    ];

    it('links a route leaf straight to its route, with query and fragment in their own fields', () => {
        const leaf = menuTreeToNav(tree)[0].children![0];
        expect(leaf).toMatchObject({
            id: 'menu-cases',
            type: 'basic',
            link: '/cases',
            queryParams: { status: 'open' },
            fragment: 'top',
        });
        expect(menuTreeToNav(tree)[0].children![1].link).toBe('/w/ops');
    });

    it('resolves the URL a leaf opens, and nothing for a group', () => {
        expect(menuLeafUrl(tree[0].children![0])).toBe('/cases?status=open#top');
        expect(menuLeafUrl(tree[0].children![1])).toBe('/w/ops');
        expect(menuLeafUrl(tree[0])).toBeUndefined();
    });

    it('lands per-user ahead of the Space, and skips candidates naming no leaf', () => {
        expect(landingUrl(tree, ['cases', 'ops'])).toBe('/cases?status=open#top');
        expect(landingUrl(tree, [undefined, 'ops'])).toBe('/w/ops');
        expect(landingUrl(tree, ['gone', 'ops'])).toBe('/w/ops');
        expect(landingUrl(tree, ['fm', 'ops'])).toBe('/w/ops'); // a group is not a landing
        expect(landingUrl(tree, ['gone', null])).toBeNull();
        expect(landingUrl([], ['cases'])).toBeNull();
    });
});

describe('routeError (client mirror of NavMenus.checkRoute)', () => {
    it('accepts absolute in-app paths, with query and fragment', () => {
        for (const ok of ['/cases', '/alerts?status=open', '/link-analysis/ring#n1', '/'])
            expect(routeError(ok), ok).toBeNull();
    });

    it('refuses anything that could leave the app or escape a segment', () => {
        for (const bad of [
            '',
            'cases',
            'https://evil.example',
            'javascript:alert(1)',
            '//evil.example',
            '/\\evil.example',
            '/cases/../settings',
            '/./cases',
            '/ca ses',
            '/x:y',
            '/' + 'a'.repeat(512),
        ])
            expect(routeError(bad), bad).not.toBeNull();
    });

    it('accepts exactly the set the server does (menu-route.contract.json, shared with ControlApiNavMenusTest)', () => {
        for (const ok of MENU_ROUTE_CONTRACT.accept) expect(routeError(ok), ok).toBeNull();
        for (const bad of MENU_ROUTE_CONTRACT.refuse) expect(routeError(bad), bad).not.toBeNull();
    });
});
