import { ComponentRef, Injectable, Injector, inject } from '@angular/core';
import { ActivatedRouteSnapshot, BaseRouteReuseStrategy, DetachedRouteHandle, Route } from '@angular/router';
import { SessionService } from './auth/session.service';
import { SpacesService } from './api/spaces.service';

/** Route `data` flag: keep this page alive while the user visits other pages (see {@link KeepAliveRouteReuseStrategy}). */
export const KEEP_ALIVE = 'keepAlive';

/** The parts of Angular's internal `DetachedRouteHandleInternal` this strategy destroys. */
interface KeptHandle {
    componentRef?: ComponentRef<unknown>;
}

/**
 * Keeps a page flagged `data: { keepAlive: true }` alive across navigation (operator 2026-10-21: an open Investigation
 * vanished after a visit to Settings). Leaving the page DETACHES it instead of destroying it, and coming back re-attaches
 * the same instance, so whatever it had open — graph, selection, filters, the Investigation — is still there. Every other
 * route behaves exactly as Angular's default.
 *
 * <p>A kept page belongs to one Space and one signed-in user: one kept for another is destroyed, never shown. (Switching
 * Space reloads the app anyway; this is the guard for a sign-out and sign-in without a reload.) With sign-in on, a page
 * left while NO ONE is signed in is not kept at all: a sign-out clears the person before it navigates away, so the page
 * was kept for "nobody" - and the next person, whose name the session reads only after it has navigated back, was
 * "nobody" too and got it (LA-ONBOARD-DEFECTS-1).
 */
@Injectable()
export class KeepAliveRouteReuseStrategy extends BaseRouteReuseStrategy {
    // Looked up on use, never injected: the Router constructs this strategy, and SessionService injects the Router (NG0200).
    private readonly injector = inject(Injector);
    private readonly kept = new Map<Route, { handle: DetachedRouteHandle; owner: string }>();

    override shouldDetach(route: ActivatedRouteSnapshot): boolean {
        return route.routeConfig?.data?.[KEEP_ALIVE] === true && this.someoneOrNoSignIn();
    }

    override store(route: ActivatedRouteSnapshot, handle: DetachedRouteHandle | null): void {
        const config = route.routeConfig;
        if (!config) return;
        // Angular stores `null` right after it re-attached a handle: the page is live again, nothing is kept.
        if (handle) this.kept.set(config, { handle, owner: this.owner() });
        else this.kept.delete(config);
    }

    override shouldAttach(route: ActivatedRouteSnapshot): boolean {
        return this.retrieve(route) !== null;
    }

    override retrieve(route: ActivatedRouteSnapshot): DetachedRouteHandle | null {
        const config = route.routeConfig;
        const kept = config ? this.kept.get(config) : undefined;
        if (!config || !kept) return null;
        if (kept.owner === this.owner()) return kept.handle;
        this.kept.delete(config);
        (kept.handle as KeptHandle).componentRef?.destroy();
        return null;
    }

    /** No sign-in in this edition, or a person is signed in: only then is there someone a page can be kept for. */
    private someoneOrNoSignIn(): boolean {
        const session = this.injector.get(SessionService);
        return session.authMode() !== 'oidc' || !!session.actor();
    }

    private owner(): string {
        const space = this.injector.get(SpacesService).currentSpaceId();
        return `${space ?? ''}|${this.injector.get(SessionService).actor() ?? ''}`;
    }
}
