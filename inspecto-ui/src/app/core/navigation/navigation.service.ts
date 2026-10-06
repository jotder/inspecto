import { Injectable, inject } from '@angular/core';
import { GammaNavigationItem } from '@gamma/components/navigation';
import { Navigation } from 'app/core/navigation/navigation.types';
import {
    compactNavigation,
    defaultNavigation,
    futuristicNavigation,
    horizontalNavigation,
} from 'app/core/navigation/navigation-data';
import { SessionService } from 'app/inspecto/auth/session.service';
import { favoritesNavGroup, loadMenuFavorites, loadMenuTrees, menuTreeToNav, saveMenuTrees } from 'app/inspecto/menu';
import { NavMenusService } from 'app/inspecto/menu/menu-api';
import { cloneDeep } from 'lodash-es';
import { Observable, ReplaySubject, catchError, map, of, tap } from 'rxjs';

/**
 * Keep only the first item per top-level id. Guards the sidebar against duplicate track keys (Angular
 * NG0955 — "duplicated keys for a given collection"), which render a nav group twice; e.g. a custom Menu
 * group colliding with a platform group, or an accidental duplicate entry in {@link defaultNavigation}.
 */
function dedupeById(items: GammaNavigationItem[]): GammaNavigationItem[] {
    const seen = new Set<string>();
    return items.filter((item) => (seen.has(item.id) ? false : (seen.add(item.id), true)));
}

@Injectable({ providedIn: 'root' })
export class NavigationService {
    private _navigation: ReplaySubject<Navigation> = new ReplaySubject<Navigation>(1);
    private readonly session = inject(SessionService);
    private readonly navMenus = inject(NavMenusService);
    /**
     * Whether this app instance has pulled the active Space's Menu tree from the server. The sidebar
     * merge is synchronous over the `localStorage` mirror, which only the Menu Builder used to fill — so
     * a fresh browser never showed a Space's custom menus until Settings ▸ Menus was opened. The first
     * {@link get} (the shell route resolver) therefore hydrates the mirror from `GET /nav/menus`.
     * Once only: switching Space reloads the app, and a later rebuild must NOT refetch — it would race
     * the Menu Builder's optimistic local edit with the not-yet-PUT server copy and revert it.
     */
    private hydrated = false;

    /**
     * Drop every entry whose `navFeature` (EDITIONS CP-09 / CP-11 / CP-13) `/bootstrap` does not report true,
     * at any depth, in place. Hidden — not disabled — so a Personal install never OFFERS a screen whose
     * backend module is absent. The id is declared ON the entry (navigation-data.ts), and the backend reports
     * only features whose routes actually bound — adding an optional module's screen edits neither this
     * service nor the session (MODULE-REORG-1 P1).
     *
     * ⛔ `audit` and `alerts` carry no `navFeature`: both read core routes every edition serves.
     * ⚠ Recursive on purpose: studio-group is a CHILD of platform-group, so a top-level-only walk silently
     * filters nothing.
     */
    private static dropUnavailable(items: GammaNavigationItem[], features: Record<string, boolean>): void {
        for (const item of items) {
            if (item.children) {
                item.children = item.children.filter((c) => !c.navFeature || features[c.navFeature] === true);
                NavigationService.dropUnavailable(item.children, features);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // @ Accessors
    // -----------------------------------------------------------------------------------------------------

    /**
     * Getter for navigation
     */
    get navigation$(): Observable<Navigation> {
        return this._navigation.asObservable();
    }

    // -----------------------------------------------------------------------------------------------------
    // @ Public methods
    // -----------------------------------------------------------------------------------------------------

    /**
     * Get all navigation data. The shell navigation is built entirely client-side — the platform groups
     * are the static {@link defaultNavigation} (compact/futuristic/horizontal reuse its children), and the
     * operator's per-space Menu Builder tree is prepended as top-level custom groups, read fresh on every
     * call so a menu edit refreshes the sidebar on the next fetch. (Formerly served over the Fuse
     * `api/common/navigation` mock; that layer was removed in the M4 shell re-plumb.)
     */
    get(): Observable<Navigation> {
        const source = this.hydrated ? of(undefined) : this.hydrateMenus();
        return source.pipe(
            map(() => this._build()),
            tap((navigation) => {
                this._navigation.next(navigation);
            }),
        );
    }

    /**
     * Pull the active Space's tree into the mirror. A failure (503 legacy no-write-root, offline, …) keeps
     * whatever the mirror holds and raises nothing: custom menus are an overlay, and their absence is not
     * an error worth a toast (the global interceptor still drives the connectivity banner on status 0).
     */
    private hydrateMenus(): Observable<void> {
        this.hydrated = true;
        return this.navMenus.get().pipe(
            map((tree) => {
                if (tree && Array.isArray(tree.nodes)) {
                    saveMenuTrees({ ...loadMenuTrees(), [NavigationService.activeSpace()]: tree });
                }
            }),
            catchError(() => of(undefined)),
        );
    }

    private static activeSpace(): string {
        return (typeof localStorage !== 'undefined' && localStorage.getItem('inspecto.currentSpace')) || 'default';
    }

    private _build(): Navigation {
        const _default = cloneDeep(defaultNavigation);
        // Runs from a route RESOLVER, after SessionService.init() (an APP_INITIALIZER) has awaited /bootstrap —
        // so this reads a settled map, not a race. A one-shot filter is correct here.
        NavigationService.dropUnavailable(_default, this.session.features());
        const _compact = cloneDeep(compactNavigation);
        const _futuristic = cloneDeep(futuristicNavigation);
        const _horizontal = cloneDeep(horizontalNavigation);

        // Fill the compact / futuristic / horizontal variants' children from the default navigation.
        for (const variant of [_compact, _futuristic, _horizontal]) {
            variant.forEach((item) => {
                const match = _default.find((d) => d.id === item.id);
                if (match) {
                    item.children = cloneDeep(match.children);
                }
            });
        }

        // Merge the user's per-space Menu tree (Menu Builder) as top-level siblings of the platform
        // groups, prepended above the custom-menus divider. Read fresh so a re-fetch after an edit
        // refreshes the sidebar.
        const space = NavigationService.activeSpace();
        const tree = loadMenuTrees()[space];
        const custom = tree ? menuTreeToNav(tree.nodes) : [];
        // The personal Favorites group (client-local overlay) sits above the custom groups.
        const favorites = tree ? favoritesNavGroup(tree.nodes, loadMenuFavorites()[space] ?? []) : null;
        const prepend = favorites ? [favorites, ...custom] : custom;
        const withCustom = (nav: GammaNavigationItem[]): GammaNavigationItem[] =>
            dedupeById(prepend.length ? [...cloneDeep(prepend), ...nav] : nav);

        return {
            compact: withCustom(_compact),
            default: withCustom(_default),
            futuristic: withCustom(_futuristic),
            horizontal: withCustom(_horizontal),
        };
    }
}
