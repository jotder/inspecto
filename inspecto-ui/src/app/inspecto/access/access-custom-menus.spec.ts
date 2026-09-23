import { afterEach, describe, expect, it } from 'vitest';
import { GammaNavigationItem } from '@gamma/components/navigation';
import { favoritesNavGroup, menuTreeToNav } from '../menu/menu-nav';
import { MENU_STORAGE_KEY } from '../menu/menu-persist';
import { MenuNode } from '../menu/menu-types';
import {
    activeSpaceMenuNodes,
    CUSTOM_MENUS_NODE_ID,
    deriveDefaultAccessCatalog,
    deriveSpaceAccessCatalog,
    filterNavByAccess,
    indexCatalog,
} from './access-catalog';

/** Menu-Builder custom menus are part of the Access Catalog, so a lens profile can hide them. */
describe('access catalog — custom menus', () => {
    const tree: MenuNode[] = [
        {
            id: 'ra',
            title: 'Revenue Assurance',
            children: [{ id: 'ra-ov', title: 'Overview', binding: { kind: 'dashboard', componentId: 'd1' } }],
        },
        {
            id: 'fm',
            title: 'Fraud Management',
            children: [{ id: 'fm-ov', title: 'Overview', binding: { kind: 'dashboard', componentId: 'd2' } }],
        },
    ] as unknown as MenuNode[];

    const titles = (items: GammaNavigationItem[]) => items.map((i) => i.title);
    afterEach(() => localStorage.clear());

    it('adds a Custom menus node carrying the tree under the sidebar ids', () => {
        const cat = deriveSpaceAccessCatalog(tree);
        const custom = cat.find((n) => n.id === CUSTOM_MENUS_NODE_ID)!;
        expect(custom.children!.map((c) => c.id)).toEqual(['menu-ra', 'menu-fm']);
        expect(custom.children![0].children![0].id).toBe('menu-ra-ov');
    });

    it('adds nothing when the space has no custom tree', () => {
        expect(deriveSpaceAccessCatalog([])).toEqual(deriveDefaultAccessCatalog());
    });

    it('denying a custom group hides only that group', () => {
        const idx = indexCatalog(deriveSpaceAccessCatalog(tree));
        const out = filterNavByAccess(menuTreeToNav(tree), { 'menu-fm': 'deny' }, idx);
        expect(titles(out)).toEqual(['Revenue Assurance']);
    });

    it('denying the Custom menus root hides every custom group', () => {
        const idx = indexCatalog(deriveSpaceAccessCatalog(tree));
        const out = filterNavByAccess(menuTreeToNav(tree), { [CUSTOM_MENUS_NODE_ID]: 'deny' }, idx);
        expect(out).toEqual([]);
    });

    it('a Favorites shortcut follows the grant of the entry it points at', () => {
        const idx = indexCatalog(deriveSpaceAccessCatalog(tree));
        const fav = favoritesNavGroup(tree, ['fm-ov', 'ra-ov'])!;
        const [kept] = filterNavByAccess([fav], { 'menu-fm-ov': 'deny' }, idx);
        expect(kept.children!.map((c) => c.id)).toEqual(['fav-ra-ov']);
    });

    it('reads the active space tree from the mirror', () => {
        localStorage.setItem('inspecto.currentSpace', 'telco');
        localStorage.setItem(
            MENU_STORAGE_KEY,
            JSON.stringify({ telco: { version: 1, nodes: tree }, other: { version: 1, nodes: [] } }),
        );
        expect(activeSpaceMenuNodes().map((n) => n.id)).toEqual(['ra', 'fm']);
    });
});
