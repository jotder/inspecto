import { computed, inject, Injectable } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Observable, of, tap } from 'rxjs';
import { apiErrorMessage, LensService, SampleSpace, Space, SpacesService } from '@inspecto/core/api';
import { SessionService } from '@inspecto/core/auth/session.service';
import { LaGuideStepId, SpaceGuideStepId } from '@inspecto/core/onboarding/space-guide';
import { OnboardingEventsService } from '@inspecto/core/onboarding/onboarding-events.service';
import { failedSampleRuns } from '@inspecto/core/spaces/sample-space.dialog';
import { SpaceFormDialog } from '@inspecto/core/spaces/space-form.dialog';

/** The Space Template *Try it with sample data* creates: Basic-safe invented transfers with a planted mule ring. */
export const LA_SAMPLE_TEMPLATE = 'la-sample';
/** The sample's saved view the app opens once the sample is loaded (`/link-analysis?view=`). */
export const LA_SAMPLE_VIEW = 'mule_layering_ring';
/** The route of the Add data screen (OB-14; built by LA-ONBOARD-1 phase 4). */
export const ADD_DATA_ROUTE = '/add-data';
/** Where a create step that was refused waits for the Add data page: entering a Space reloads, which drops a toast. */
const NOTICE_KEY = 'inspecto.la.firstRunNotice';

/** Where each Space guide line leads in this app (`space` opens the Create Space dialog instead). */
const STEP_ROUTES: Record<Exclude<LaGuideStepId, 'space'>, string> = {
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
    private readonly events = inject(OnboardingEventsService);

    /** May create a Space: no sign-in, or `canAdminister` (OB-11; the server gates `POST /spaces` the same way). */
    readonly canCreate = computed(() => this.session.authMode() !== 'oidc' || this.lens.canAdminister());

    /**
     * Signed in on a multi-Space server where no Space admits this person (a closed Space lists only the people it admits)
     * and they may not create one: the shell then shows nothing but the no-access page and the user menu. False until
     * the Space list has loaded (`multiSpace` starts false), so nothing flashes on sign-in.
     */
    readonly noAccess = computed(
        () => this.spaces.multiSpace() && this.spaces.availableSpaces().length === 0 && !this.canCreate(),
    );

    /**
     * Create a Space (name + What kind of data?), turn Add data on in it, then enter it at `then`. A Space made here
     * is made to add data to (LA-FULL-CYCLE-TEST-1 D2), so its creator does not meet a locked Add data page: where
     * people sign in Add data starts off, and this asks the server to turn it on (`canAdminister`, as the create
     * itself). A refusal is carried to the Add data page, which names it and still offers the switch.
     */
    createSpace(then: string = ADD_DATA_ROUTE): void {
        this.dialog
            .open(SpaceFormDialog, { width: '520px', maxHeight: '90vh', data: { dataKind: true } })
            .afterClosed()
            .subscribe((created?: Space) => {
                if (!created) {
                    this.events.stepAbandoned('space'); // OB-13: the step where people stop
                    return;
                }
                // With no sign-in Add data is on by default: nothing to turn on, and nothing is pinned.
                const turnedOn$: Observable<unknown> =
                    this.session.authMode() === 'oidc' ? this.spaces.setSelfImport(created.id, true) : of(null);
                turnedOn$.subscribe({
                    next: () => this.enter(created.id, then),
                    error: (e: unknown) =>
                        this.enter(
                            created.id,
                            then,
                            `The Space was created, but Add data was not turned on: ${apiErrorMessage(e, 'the server did not accept the change.')}`,
                        ),
                });
            });
    }

    /** The create step that was refused, once: the Add data page shows it after the reload that entered the Space. */
    takeNotice(): string | null {
        try {
            const notice = sessionStorage.getItem(NOTICE_KEY);
            sessionStorage.removeItem(NOTICE_KEY);
            return notice;
        } catch {
            return null; // no session storage here: nothing was left
        }
    }

    private leaveNotice(notice: string): void {
        try {
            sessionStorage.setItem(NOTICE_KEY, notice);
        } catch {
            // no session storage here: the Add data page still shows the switch
        }
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
        else if (id in STEP_ROUTES) void this.router.navigateByUrl(STEP_ROUTES[id as keyof typeof STEP_ROUTES]);
    }

    /**
     * Make `spaceId` the active Space and land on `url` with a reload. A `notice` is left for the page the reload
     * draws, so it is written AFTER the navigation: the router already builds that page once before the reload, and
     * a notice left earlier would be taken by that first copy and never seen.
     */
    enter(spaceId: string, url: string, notice?: string): void {
        this.spaces.selectSpace(spaceId);
        void this.router.navigateByUrl(url).then(() => {
            if (notice) this.leaveNotice(notice);
            this.reload();
        });
    }

    /** A hard reload in place (its own seam so a spec can stub it: jsdom cannot reload). */
    reload(): void {
        window.location.reload();
    }
}
