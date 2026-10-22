import { computed, inject, Injectable } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { LensService, SampleSpace, Space, SpacesService } from '@inspecto/core/api';
import { SessionService } from '@inspecto/core/auth/session.service';
import { SpaceGuideStepId } from '@inspecto/core/onboarding/space-guide';
import { failedSampleRuns } from '@inspecto/core/spaces/sample-space.dialog';
import { SpaceFormDialog } from '@inspecto/core/spaces/space-form.dialog';

/** The Space Template *Try it with sample data* creates: Basic-safe invented transfers with a planted mule ring. */
export const LA_SAMPLE_TEMPLATE = 'la-sample';
/** The sample's saved view the app opens once the sample is loaded (`/link-analysis?view=`). */
export const LA_SAMPLE_VIEW = 'mule_layering_ring';
/** The route of the Add data screen (OB-14; built by LA-ONBOARD-1 phase 4). */
export const ADD_DATA_ROUTE = '/add-data';

/** Where each Space guide line leads in this app (`space` opens the Create Space dialog instead). */
const STEP_ROUTES: Record<Exclude<SpaceGuideStepId, 'space'>, string> = {
    data: ADD_DATA_ROUTE,
    columns: '/entities?tab=columns',
    graph: '/link-analysis',
    list: '/entity-lists',
};

/**
 * The first-run actions of Inspecto LA (LA-ONBOARD-1 phase 3, UC-01/UC-02), shared by the Getting started page and
 * the Space guide on the home page. Entering another Space reloads, as the Space switcher does: every page fetches
 * on init and holds its own state, so a reload is the one correct way to re-scope the app.
 */
@Injectable({ providedIn: 'root' })
export class FirstRun {
    private readonly spaces = inject(SpacesService);
    private readonly session = inject(SessionService);
    private readonly lens = inject(LensService);
    private readonly dialog = inject(MatDialog);
    private readonly router = inject(Router);

    /** May create a Space: no sign-in, or `canAdminister` (OB-11; the server gates `POST /spaces` the same way). */
    readonly canCreate = computed(() => this.session.authMode() !== 'oidc' || this.lens.canAdminister());

    /** Create a Space (name + What kind of data?), then enter it at `then`. */
    createSpace(then: string = ADD_DATA_ROUTE): void {
        this.dialog
            .open(SpaceFormDialog, { width: '520px', maxHeight: '90vh', data: { dataKind: true } })
            .afterClosed()
            .subscribe((created?: Space) => {
                if (created) this.enter(created.id, then);
            });
    }

    /**
     * *Try it with sample data*: one call creates the sample Space and loads and runs its data. When every Pipeline
     * loaded, enter it on Link Analysis with the sample's view drawn; otherwise the caller shows what failed.
     */
    trySample(): Observable<SampleSpace> {
        return this.spaces.createSample(LA_SAMPLE_TEMPLATE, 'Sample: invented data').pipe(
            tap((made) => {
                if (!failedSampleRuns(made).length) this.enter(made.id, `/link-analysis?view=${LA_SAMPLE_VIEW}`);
            }),
        );
    }

    /** Open one Space guide step. */
    openStep(id: SpaceGuideStepId): void {
        if (id === 'space') this.createSpace();
        else void this.router.navigateByUrl(STEP_ROUTES[id]);
    }

    /** Make `spaceId` the active Space and land on `url` with a reload. */
    enter(spaceId: string, url: string): void {
        this.spaces.selectSpace(spaceId);
        void this.router.navigateByUrl(url).then(() => this.reload());
    }

    /** A hard reload in place (its own seam so a spec can stub it: jsdom cannot reload). */
    reload(): void {
        window.location.reload();
    }
}
