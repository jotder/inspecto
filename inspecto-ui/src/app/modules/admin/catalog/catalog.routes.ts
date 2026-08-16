import { inject } from '@angular/core';
import { Router, Routes, UrlSegment } from '@angular/router';
import { CatalogComponent } from 'app/modules/admin/catalog/catalog.component';
import { onboardRedirect } from './onboard-redirect';
// Side-effect: register Studio's ComponentKinds on the unified component model when Catalog loads.
import 'app/modules/admin/studio/datasets/dataset.kind';

export default [
    {
        path: '',
        component: CatalogComponent,
    },
    // Datasets are a data asset (Catalog's home); component files stay under studio/datasets/.
    { path: 'datasets', loadChildren: () => import('app/modules/admin/studio/datasets/datasets.routes') },
    // Stream/Reference onboarding — RETIRED (definition-surface P6-a). The wizard's route is now a
    // hard redirect into the guided editor, which since P3–P6 runs the same components and does
    // strictly more (plan §11-5: no read-only flag release — by P6 both surfaces had run identical
    // panes for several phases). The matcher stays so old links and bookmarks — including the
    // per-stage ones — resolve rather than 404.
    {
        matcher: (segments: UrlSegment[]) => {
            if (segments.length >= 2 && segments.length <= 3 && segments[0].path === 'onboard') {
                const posParams: Record<string, UrlSegment> = { name: segments[1] };
                if (segments.length === 3) posParams['stage'] = segments[2];
                return { consumed: segments, posParams };
            }
            return null;
        },
        redirectTo: (r) => onboardRedirect(inject(Router), r.params['name'], r.params['stage']),
    },
] as Routes;
