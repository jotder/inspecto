import { Routes } from '@angular/router';

/**
 * Studio shell routes. Nested `loadChildren` so adding widgets/dashboards (P1/P2) never re-touches
 * `app.routes.ts`. Datasets moved to `/catalog/datasets` (Phase B.2, it's a Catalog data asset);
 * this redirect keeps the old `/studio/datasets` bookmark/link working.
 */
export default [
    { path: '', pathMatch: 'full', redirectTo: '/catalog/datasets' },
    { path: 'datasets', redirectTo: '/catalog/datasets' },
    { path: 'queries', loadChildren: () => import('./queries/queries.routes') },
    { path: 'widgets', loadChildren: () => import('./widgets/widgets.routes') },
    { path: 'dashboards', loadChildren: () => import('./dashboards/dashboards.routes') },
    { path: 'templates', loadChildren: () => import('./templates/templates.routes') },
    { path: 'link-analysis', data: { hideShellFooter: true }, loadChildren: () => import('@inspecto/link-analysis/link-analysis/link-analysis.routes') },
    { path: 'entities', loadChildren: () => import('@inspecto/link-analysis/entities/entities.routes') },
    // LA-EDITIONS-1 section 4: the "Open in Inspecto LA" deep link, the la-app's /open page at /studio/open.
    { path: 'open', loadChildren: () => import('@inspecto/link-analysis/open/open.routes') },
    { path: 'geo-map', loadChildren: () => import('@inspecto/link-analysis/geo-map/geo-map.routes') },
] as Routes;
