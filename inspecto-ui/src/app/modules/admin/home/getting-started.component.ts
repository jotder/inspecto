import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { Router, RouterLink } from '@angular/router';
import {
    apiErrorMessage,
    LensService,
    SampleLoadResult,
    SessionService,
    Space,
    SpacesService,
    SpaceTemplateInfo,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { OnboardingEventsService } from 'app/inspecto/onboarding/onboarding-events.service';
import { SpaceGuideComponent } from 'app/inspecto/onboarding/space-guide.component';
import {
    CONSOLE_SAMPLE_LANDING,
    CONSOLE_SAMPLE_TEMPLATE,
    CONSOLE_STEP_ROUTES,
    SpaceGuideState,
    SpaceGuideStepId,
} from 'app/inspecto/onboarding/space-guide';
import { failedSampleRuns } from 'app/inspecto/spaces/sample-space.dialog';
import { SpaceFormDialog } from 'app/inspecto/spaces/space-form.dialog';
import { SpaceSwitcherComponent } from 'app/layout/common/space-switcher/space-switcher.component';

const ADD_DATA = '/add-data';

/**
 * The console's **Getting started** (ONBOARDING-FIRST-RUN-1 B1, UC-1/UC-2): replaces Home's first-run cards while
 * setup is incomplete (no Space, or a Space with no Dataset yet). Three ways in — *Try it with sample data*,
 * *Add data*, *Open my team's data* — with the console Space guide beside them. A choice the user cannot take says
 * why in one line instead of failing.
 */
@Component({
    selector: 'inspecto-getting-started',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatIconModule,
        RouterLink,
        InspectoAlertComponent,
        SpaceGuideComponent,
        SpaceSwitcherComponent,
    ],
    template: `
        <section class="flex flex-col gap-6 lg:flex-row lg:items-start" aria-labelledby="getting-started-title">
            <div class="flex min-w-0 flex-1 flex-col gap-4">
                <h2 id="getting-started-title" class="m-0 text-lg font-semibold">Getting started</h2>
                <ul class="m-0 grid list-none grid-cols-1 gap-4 p-0 md:grid-cols-3">
                    <li class="bg-card flex flex-col gap-3 rounded-2xl p-6 shadow" data-choice="sample">
                        <mat-icon class="text-primary icon-size-7" svgIcon="heroicons_outline:beaker"></mat-icon>
                        <h3 class="m-0 text-lg font-semibold">Try it with sample data</h3>
                        <p class="text-secondary m-0 flex-auto text-sm">
                            Invented daily orders. One click creates a sample Space, loads the files, runs the Pipeline
                            and opens its dashboard.
                        </p>
                        <button
                            type="button"
                            mat-flat-button
                            color="primary"
                            class="self-start"
                            data-testid="gs-sample"
                            [disabled]="!!sampleBlocked() || loading()"
                            [attr.aria-describedby]="sampleBlocked() ? 'gs-sample-why' : null"
                            (click)="trySample()"
                        >
                            Try the sample
                        </button>
                        @if (sampleBlocked(); as why) {
                            <p id="gs-sample-why" class="text-secondary m-0 text-sm">{{ why }}</p>
                        }
                        @if (loading()) {
                            <p class="m-0 text-sm" role="status">Creating the sample Space and loading its data…</p>
                        }
                        @if (error()) {
                            <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
                        }
                        @if (failed().length) {
                            <inspecto-alert
                                variant="warning"
                                title="The sample Space was created, but its data did not load"
                            >
                                <ul class="m-0 pl-4">
                                    @for (p of failed(); track p.pipeline) {
                                        <li>{{ p.pipeline }}: {{ p.error || 'did not load' }}</li>
                                    }
                                </ul>
                            </inspecto-alert>
                            <button type="button" mat-button class="self-start" (click)="enter(madeId()!, '/')">
                                Open the sample Space
                            </button>
                        }
                    </li>
                    <li class="bg-card flex flex-col gap-3 rounded-2xl p-6 shadow" data-choice="own">
                        <mat-icon
                            class="text-primary icon-size-7"
                            svgIcon="heroicons_outline:arrow-down-tray"
                        ></mat-icon>
                        <h3 class="m-0 text-lg font-semibold">Add data</h3>
                        <p class="text-secondary m-0 flex-auto text-sm">
                            @if (state().hasSpace) {
                                Drop a CSV, Parquet or JSON file: Inspecto detects its columns, shows why, and makes a
                                Dataset you can query. No Pipeline to write.
                            } @else {
                                Create a Space for one kind of data, then add a file: it becomes a Dataset you can
                                query.
                            }
                        </p>
                        <button
                            type="button"
                            mat-stroked-button
                            class="self-start"
                            data-testid="home-add-data"
                            [disabled]="!!ownBlocked()"
                            [attr.aria-describedby]="ownBlocked() ? 'gs-own-why' : null"
                            (click)="addData()"
                        >
                            {{ state().hasSpace ? 'Add data' : 'Create a Space' }}
                        </button>
                        @if (ownBlocked(); as why) {
                            <p id="gs-own-why" class="text-secondary m-0 text-sm">{{ why }}</p>
                        }
                        @if (state().hasSpace && lens.canOnboardConnections()) {
                            <a
                                class="self-start text-sm"
                                data-testid="home-onboard-stream"
                                [routerLink]="'/catalog'"
                                [queryParams]="{ onboard: 'stream' }"
                                >Or onboard a Stream from a folder, a bucket or a database</a
                            >
                        }
                    </li>
                    @if (spaces.multiSpace() && spaces.availableSpaces().length) {
                        <li class="bg-card flex flex-col gap-3 rounded-2xl p-6 shadow" data-choice="team">
                            <mat-icon
                                class="text-primary icon-size-7"
                                svgIcon="heroicons_outline:user-group"
                            ></mat-icon>
                            <h3 class="m-0 text-lg font-semibold">Open my team's data</h3>
                            <p class="text-secondary m-0 flex-auto text-sm">
                                Your team may already keep data in another Space. Pick it here.
                            </p>
                            <inspecto-space-switcher [preferredSample]="sampleTemplate"></inspecto-space-switcher>
                        </li>
                    }
                </ul>
            </div>
            <inspecto-space-guide
                class="w-full lg:w-96"
                kind="console"
                [state]="state()"
                (stepOpen)="openStep($event)"
            ></inspecto-space-guide>
        </section>
    `,
})
export class GettingStartedComponent implements OnInit {
    /** The console guide state Home loaded. */
    readonly state = input.required<SpaceGuideState>();

    protected readonly sampleTemplate = CONSOLE_SAMPLE_TEMPLATE;
    protected readonly spaces = inject(SpacesService);
    protected readonly lens = inject(LensService);
    private readonly session = inject(SessionService);
    private readonly dialog = inject(MatDialog);
    private readonly router = inject(Router);
    private readonly events = inject(OnboardingEventsService);

    /** The sample template as this server lists it; undefined until read, null when it does not ship. */
    private readonly sample = signal<SpaceTemplateInfo | null | undefined>(undefined);
    protected readonly loading = signal(false);
    protected readonly error = signal<string | null>(null);
    protected readonly failed = signal<SampleLoadResult[]>([]);
    /** The sample Space a load with failures still created. */
    protected readonly madeId = signal<string | null>(null);

    /** May create a Space: no sign-in, or `canAdminister` (the server gates `POST /spaces` the same way). */
    private readonly canCreate = computed(() => this.session.authMode() !== 'oidc' || this.lens.canAdminister());

    /** Why *Try the sample* cannot run here, or null. */
    protected readonly sampleBlocked = computed<string | null>(() => {
        if (!this.spaces.multiSpace()) return 'This server runs a single Space, so a sample Space cannot be added.';
        if (!this.canCreate()) return 'Only an administrator can create a Space. Ask yours, or open your team’s data.';
        const s = this.sample();
        if (s === undefined) return null;
        if (s === null || !s.samples) return 'The sample is not installed on this server.';
        if (s.creatable === false)
            return `The sample cannot be created in this edition: ${(s.missingFeatures ?? []).map((m) => m.message).join(' ')}`;
        return null;
    });

    /** Why *Add data* cannot run here, or null (a Space is needed first and the user may not create one). */
    protected readonly ownBlocked = computed<string | null>(() =>
        this.state().hasSpace || this.canCreate()
            ? null
            : 'Only an administrator can create a Space. Ask yours, or open your team’s data.',
    );

    ngOnInit(): void {
        if (!this.spaces.multiSpace()) return; // a single-Space server cannot add a sample Space: nothing to read
        this.spaces.templates().subscribe({
            next: (all) => this.sample.set(all.find((t) => t.id === CONSOLE_SAMPLE_TEMPLATE) ?? null),
            error: () => this.sample.set(null),
        });
    }

    /** One call creates the sample Space and loads and runs its data; when every Pipeline loaded, open its dashboard. */
    protected trySample(): void {
        const name = this.sample()?.name;
        this.loading.set(true);
        this.error.set(null);
        this.failed.set([]);
        this.spaces
            .createSample(CONSOLE_SAMPLE_TEMPLATE, name ? `Sample: ${name}` : 'Sample: invented orders')
            .subscribe({
                next: (made) => {
                    this.loading.set(false);
                    const failed = failedSampleRuns(made);
                    this.failed.set(failed);
                    this.madeId.set(made.id);
                    if (!failed.length) this.enter(made.id, CONSOLE_SAMPLE_LANDING);
                },
                error: (e) => {
                    this.loading.set(false);
                    this.error.set(apiErrorMessage(e, 'Could not create the sample Space.'));
                },
            });
    }

    protected addData(): void {
        if (this.state().hasSpace) void this.router.navigateByUrl(ADD_DATA);
        else this.createSpace();
    }

    protected openStep(id: SpaceGuideStepId): void {
        const to = CONSOLE_STEP_ROUTES[id];
        if (id === 'space') this.createSpace();
        else if (to) void this.router.navigateByUrl(to);
    }

    /** Create a Space, then enter it on Add data. */
    private createSpace(): void {
        this.dialog
            .open(SpaceFormDialog, { width: '520px', maxHeight: '90vh' })
            .afterClosed()
            .subscribe((created?: Space) => {
                if (created) this.enter(created.id, ADD_DATA);
                else this.events.stepAbandoned('space'); // OB-13: the step where people stop
            });
    }

    /** Make `spaceId` the active Space and land on `url` with a reload (every pane re-scopes on load). */
    protected enter(spaceId: string, url: string): void {
        this.spaces.selectSpace(spaceId);
        void this.router.navigateByUrl(url).then(() => this.reload());
    }

    /** A hard reload in place (its own seam so a spec can stub it: jsdom cannot reload). */
    reload(): void {
        window.location.reload();
    }
}
