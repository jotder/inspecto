import { inject } from '@angular/core';
import { CanActivateFn, Route, Router, UrlSegment } from '@angular/router';
import { authGuard } from '@inspecto/core/auth/auth.guard';
import { SessionService } from '@inspecto/core/auth/session.service';
import { productNav } from '@inspecto/core/product/product-host';
import { ALERTS_INVESTIGATION_ROUTE } from 'app/modules/admin/alerts/alerts.component';
import { LaShellComponent } from './la-shell.component';
import { LandingComponent } from './landing.component';
import { PRODUCT } from './product.generated';

/**
 * An App's page opens only when the Product's nav offers it (PRODUCT-ASSEMBLY-1 PA-5b): the server's resolved nav, else
 * the generated one under the feature rule - the same areas the rail draws. A typed or bookmarked URL for an area this
 * build, edition, Space or caller does not have lands on the landing page: Basic has no Alerts (no Investigations,
 * operator 2026-10-21), an Inspecto LA build has no Reconciliations. `/bootstrap` is read by an app initializer before
 * routing, so the Product and the features are known here.
 */
export const areaGuard: CanActivateFn = (route) => {
    const session = inject(SessionService);
    const path = route.routeConfig?.path;
    return (
        productNav(session.product(), PRODUCT, session.features()).some((n) => n.path === path) ||
        inject(Router).createUrlTree(['/'])
    );
};

/** A console page mounted whole as an App area, behind the area guard. */
const area = (path: string, loadChildren: Route['loadChildren']): Route => ({
    path,
    canActivate: [areaGuard],
    loadChildren,
});

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
        loadComponent: () =>
            import('@inspecto/link-analysis/link-analysis/embed-view.page').then((m) => m.EmbedViewPageComponent),
    },
    {
        path: '',
        component: LaShellComponent,
        canActivate: [authGuard],
        children: [
            { path: '', pathMatch: 'full', component: LandingComponent },
            // Add data (OB-14), the first-run page's "Use my own data": the one Add data screen both apps use (LA-ONBOARD-1 phase 4).
            { path: 'add-data', loadComponent: () => import('./add-data.page').then((m) => m.AddDataPageComponent) },
            // LA-DATA-PAGE-1: the Datasets, their Entities and their Mappings.
            {
                path: 'data',
                canActivate: [areaGuard],
                loadComponent: () => import('./data.page').then((m) => m.DataPageComponent),
            },
            // The library's lazy boundaries, exactly as the gamma host loads them.
            area('link-analysis', () => import('@inspecto/link-analysis/link-analysis/link-analysis.routes')),
            area('geo-map', () => import('@inspecto/link-analysis/geo-map/geo-map.routes')),
            // The Entities pages (Entity Type Profile, Entity Profile), the library's own lazy boundary.
            // NOT behind the area guard: the Space guide's "What the columns hold" (/entities?tab=columns) is a step of every
            // edition, Basic included, where the Entities area itself is not offered (entityPages false; D-LAE4 gates only
            // the profile reads on the server).
            { path: 'entities', loadChildren: () => import('@inspecto/link-analysis/entities/entities.routes') },
            // LA-EDITIONS-1 section 4: the "Open in Inspecto LA" deep link another tool builds (/open?entity=&investigation=).
            { path: 'open', loadChildren: () => import('@inspecto/link-analysis/open/open.routes') },
            {
                path: 'entity-lists',
                canActivate: [areaGuard],
                loadComponent: () => import('./entity-lists.page').then((m) => m.EntityListsPageComponent),
            },
            {
                // The console's Alerts page, reused whole; *Open Investigation* stays inside this app. Inspecto LA's Alerts
                // area, and Case Management's in Inspecto Telco (the first App to declare a page owns it).
                path: 'alerts',
                canActivate: [areaGuard],
                loadComponent: () => import('app/modules/admin/alerts/alerts.component').then((m) => m.AlertsComponent),
                providers: [{ provide: ALERTS_INVESTIGATION_ROUTE, useValue: '/link-analysis' }],
            },
            // PA-5b: the Inspecto Telco Apps' areas are the console's pages, mounted whole (apps/*/app.toon declares them).
            // Revenue Assurance:
            area('reconciliation', () => import('app/modules/admin/reconciliation/reconciliation.routes')),
            area('kpi-reports', () => import('app/modules/admin/kpi-reports/kpi-reports.routes')),
            // Case Management:
            area('incidents', () => import('app/modules/admin/objects/incidents.routes')),
            area('cases', () => import('app/modules/admin/objects/cases.routes')),
            // Fraud Management:
            area('risk-scores', () => import('app/modules/admin/risk-scores/risk-scores.routes')),
            area('anomaly-models', () => import('app/modules/admin/anomaly-models/anomaly-models.routes')),
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
