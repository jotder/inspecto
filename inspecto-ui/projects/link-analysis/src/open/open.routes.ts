import { Routes } from '@angular/router';

/**
 * The "Open in Inspecto LA" deep link's landing page (LA-EDITIONS-1 section 4), one lazy boundary for both hosts: la-app mounts
 * it at `open`, the console at `studio/open`. The page reads its base from the route, so neither path is assumed.
 */
export default [
    { path: '', loadComponent: () => import('./open-in-la.component').then((m) => m.OpenInLaComponent) },
] as Routes;
