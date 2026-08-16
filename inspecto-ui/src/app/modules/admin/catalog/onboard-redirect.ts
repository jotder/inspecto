import { Router, UrlTree } from '@angular/router';

/**
 * Where onboarding lives now (definition-surface P6-a): the guided Pipelines editor.
 *
 * <p>ONE statement of the target, used by the retired `/catalog/onboard/:name/:stage` route's redirect
 * AND by the Catalog's own two navigations, so a bookmark and a button can never disagree. Its own
 * file rather than `catalog.routes.ts` because that module imports `CatalogComponent`, and having the
 * component import back would be a cycle.
 */
export function onboardRedirect(router: Router, name: string, stage?: string): UrlTree {
    const focus = STAGE_TO_CHIP[stage ?? ''];
    return router.createUrlTree(['/pipelines'], {
        queryParams: { guided: 1, open: name, ...(focus ? { stage: focus } : {}) },
    });
}

/**
 * The wizard's stage ids → checklist chip ids. `schema` and `keys` are the SAME artifact — a
 * Reference's "Keys & Load" stage authors the schema too (`OnboardingStateService` said so first) — so
 * both land on the Schema chip. An unknown or absent segment carries no focus rather than inventing one.
 */
const STAGE_TO_CHIP: Record<string, string | undefined> = {
    collection: 'collect',
    parsing: 'parse',
    schema: 'schema',
    keys: 'schema',
    enrichment: 'enrich',
    publish: 'publish',
};
