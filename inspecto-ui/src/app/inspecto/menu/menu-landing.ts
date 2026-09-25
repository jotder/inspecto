import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { firstValueFrom, timeout } from 'rxjs';
import { SessionService } from 'app/inspecto/api';
import { NavMenusService } from './menu-api';
import { landingUrl } from './menu-nav';

/** Where `/` lands when neither the Demo User nor the Space names a landing (landing-page plan D1). */
export const PLATFORM_HOME = '/home';

/** How long `/` waits for the Menu tree before giving up and opening the platform Home. */
const LANDING_FETCH_MS = 5000;

/** Stale landing ids already warned about this page load — the console hears about each one once. */
const warned = new Set<string>();

/**
 * UIE-7: the `/` redirect. Precedence is **per-Demo-User landing > Space landing > platform Home**: the Demo
 * User's `landing` (from the sign-in picker entry) and the Space's `landing` (from `nav-menus.toon`) each name a
 * menu item, and the first that still names a leaf of the Space's Menu tree wins. A landing naming nothing — a
 * deleted item, a group, another Space's item — is skipped with one console warning, and a Menu tree that cannot
 * be read (offline, slow, 5xx) opens Home: `/` never renders a blank screen.
 *
 * It runs BEFORE the shell's `authGuard`, so a visitor who still has to sign in goes straight to Home, whose own
 * guard sends them to sign-in — no Menu read is attempted without a session.
 */
export const landingGuard: CanActivateFn = async (): Promise<UrlTree> => {
    const router = inject(Router);
    const session = inject(SessionService);
    const menus = inject(NavMenusService);
    const home = router.parseUrl(PLATFORM_HOME);
    if (session.loginRequired()) return home;

    let tree;
    try {
        tree = await firstValueFrom(menus.get().pipe(timeout(LANDING_FETCH_MS)));
    } catch (err) {
        // The one way a signed-in user reaches Home despite a landing — say so, or the fallback reads as a wrong landing.
        console.warn('Landing skipped: the Menu tree could not be read; opening Home.', err);
        return home;
    }
    await session.sessionSettled();
    const candidates = [session.demoUserLanding(), tree.landing];
    const url = landingUrl(tree.nodes ?? [], candidates);
    for (const id of candidates) {
        if (!id || warned.has(id) || landingUrl(tree.nodes ?? [], [id])) continue;
        warned.add(id);
        console.warn(`Landing menu item '${id}' is not in this Space's menu; falling back.`);
    }
    return url ? router.parseUrl(url) : home;
};
