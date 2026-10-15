import { Routes } from '@angular/router';

/**
 * The Entities pages (la-entity-profile-plan section 3), one lazy boundary for both hosts: la-app mounts it at
 * `entities`, the full SPA at `studio/entities`. The pages read their base from the route, so neither path is assumed.
 */
export default [
    { path: '', loadComponent: () => import('./entities.component').then((m) => m.EntitiesComponent) },
    { path: ':type', loadComponent: () => import('./entity-type.component').then((m) => m.EntityTypeComponent) },
    { path: ':type/:key', loadComponent: () => import('./entity.component').then((m) => m.EntityComponent) },
] as Routes;
