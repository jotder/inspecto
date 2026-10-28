import { inject } from '@angular/core';
import { CanActivateFn, Route, Router, UrlSegment } from '@angular/router';
import { authGuard } from '@inspecto/core/auth/auth.guard';
import { SessionService } from '@inspecto/core/auth/session.service';
import { ALERTS_INVESTIGATION_ROUTE } from 'app/modules/admin/alerts/alerts.component';
import { LaShellComponent } from './la-shell.component';
import { LandingComponent } from './landing.component';

/**
 * Basic has no Investigations, so no Alerts (operator 2026-10-21): a typed or bookmarked /alerts lands on the landing page.
 * `/bootstrap` is read by an app initializer before routing, so the features are known here.
 */
export const investigationsGuard: CanActivateFn = () =>
    inject(SessionService).features()['investigations'] === true || inject(Router).createUrlTree(['/']);

export const laAppRoutes: Route[] = [
    // OIDC guest routes: the sign-in and callback pages are the core's, shared with the gamma host.
    {
        path: 'sign-in',
        loadComponent: () => import('@inspecto/core/auth/sign-in.component').then((m) => m.SignInComponent),
    },
    {
        path: 'auth/callback',
        loadComponent: () => import('@inspecto/core/auth/callback.component').then((m) => m.CallbackComponent),
    },
    // LA-EMBED-VIEW-1: the anonymous read-only Investigation viewer. Outside the guarded shell on purpose - the token in the
    // path is the credential, there is no sign-in redirect, and the page never touches the session.
    {
        path: 'embed/inv/:token',
        loadComponent: () => import('@inspecto/link-analysis/link-analysis/embed-view.page').then((m) => m.EmbedViewPageComponent),
    },
    {
        path: '',
        component: LaShellComponent,
        canActivate: [authGuard],
        children: [
            { path: '', pathMatch: 'full', component: LandingComponent },
            // Add data (OB-14), the first-run page's "Use my own data": the one Add data screen both apps use (LA-ONBOARD-1 phase 4).
            { path: 'add-data', loadComponent: () => import('./add-data.page').then((m) => m.AddDataPageComponent) },
            // The library's lazy boundaries, exactly as the gamma host loads them.
            {
                path: 'link-analysis',
                loadChildren: () => import('@inspecto/link-analysis/link-analysis/link-analysis.routes'),
            },
            { path: 'geo-map', loadChildren: () => import('@inspecto/link-analysis/geo-map/geo-map.routes') },
            // The Entities pages (Entity Type Profile, Entity Profile), the library's own lazy boundary.
            { path: 'entities', loadChildren: () => import('@inspecto/link-analysis/entities/entities.routes') },
            // LA-EDITIONS-1 section 4: the "Open in Inspecto LA" deep link another tool builds (/open?entity=&investigation=).
            { path: 'open', loadChildren: () => import('@inspecto/link-analysis/open/open.routes') },
            {
                path: 'entity-lists',
                loadComponent: () => import('./entity-lists.page').then((m) => m.EntityListsPageComponent),
            },
            {
                // The console's Alerts page, reused whole; *Open Investigation* stays inside this app.
                path: 'alerts',
                canActivate: [investigationsGuard],
                loadComponent: () => import('app/modules/admin/alerts/alerts.component').then((m) => m.AlertsComponent),
                providers: [{ provide: ALERTS_INVESTIGATION_ROUTE, useValue: '/link-analysis' }],
            },
            {
                // One config matches BOTH /settings and /settings/<section>, as the console's Settings does: the same
                // route config means the page is reused across a section switch (deep-linkable, Back works).
                matcher: (segments: UrlSegment[]) =>
                    segments[0]?.path !== 'settings' || segments.length > 2
                        ? null
                        : segments.length === 1
                          ? { consumed: segments }
                          : { consumed: segments, posParams: { section: segments[1] } },
                loadComponent: () => import('./settings.page').then((m) => m.SettingsPageComponent),
            },
            { path: '**', redirectTo: '' },
        ],
    },
];
