import { Route } from '@angular/router';
import { authGuard } from '@inspecto/core/auth/auth.guard';
import { LaShellComponent } from './la-shell.component';
import { LandingComponent } from './landing.component';

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
    {
        path: '',
        component: LaShellComponent,
        canActivate: [authGuard],
        children: [
            { path: '', pathMatch: 'full', component: LandingComponent },
            // The library's lazy boundaries, exactly as the gamma host loads them.
            {
                path: 'link-analysis',
                loadChildren: () => import('@inspecto/link-analysis/link-analysis/link-analysis.routes'),
            },
            { path: 'geo-map', loadChildren: () => import('@inspecto/link-analysis/geo-map/geo-map.routes') },
            {
                path: 'entity-lists',
                loadComponent: () => import('./entity-lists.page').then((m) => m.EntityListsPageComponent),
            },
            { path: '**', redirectTo: '' },
        ],
    },
];
