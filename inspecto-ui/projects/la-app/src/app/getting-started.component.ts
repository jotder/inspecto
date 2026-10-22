import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { apiErrorMessage, SampleLoadResult, SpacesService, SpaceTemplateInfo } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { SpaceGuideComponent } from '@inspecto/core/onboarding/space-guide.component';
import { SpaceGuideState } from '@inspecto/core/onboarding/space-guide';
import { failedSampleRuns } from '@inspecto/core/spaces/sample-space.dialog';
import { SpaceSwitcherComponent } from 'app/layout/common/space-switcher/space-switcher.component';
import { ADD_DATA_ROUTE, FirstRun, LA_SAMPLE_TEMPLATE } from './first-run';
import { LA_PRODUCT_NAME } from './la-brand';

/**
 * **Getting started** (LA-ONBOARD-1 phase 3, UC-01): replaces the home cards while setup is incomplete (no Space, or a
 * Space with no Dataset yet). Three ways in — *Try it with sample data*, *Use my own data*, *Open my team's data* —
 * and the Space guide (H1) under them. A choice the user cannot take says why in one line instead of failing.
 */
@Component({
    selector: 'la-app-getting-started',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, InspectoAlertComponent, SpaceGuideComponent, SpaceSwitcherComponent],
    template: `
        <section class="mx-auto flex max-w-5xl flex-col gap-6 p-6" aria-labelledby="la-getting-started-title">
            <header class="flex flex-col gap-1">
                <h1 id="la-getting-started-title" class="text-title m-0 font-semibold">Getting started</h1>
                <p class="text-secondary m-0">
                    {{ product }} finds the links between entities in your data. Choose how to start.
                </p>
            </header>
            <ul class="m-0 grid list-none grid-cols-1 gap-4 p-0 md:grid-cols-3">
                <li class="bg-card flex flex-col gap-3 rounded-2xl border p-4" data-choice="sample">
                    <mat-icon class="icon-size-6" svgIcon="heroicons_outline:beaker"></mat-icon>
                    <h2 class="m-0 text-lg font-semibold">Try it with sample data</h2>
                    <p class="text-secondary m-0 flex-1 text-sm">
                        Invented money transfers with a planted money-mule ring. One click creates a sample Space, loads
                        the data and draws the graph.
                    </p>
                    <button
                        type="button"
                        mat-flat-button
                        color="primary"
                        [disabled]="!!sampleBlocked() || loading()"
                        [attr.aria-describedby]="sampleBlocked() ? 'la-sample-why' : null"
                        (click)="trySample()"
                    >
                        Try the sample
                    </button>
                    @if (sampleBlocked(); as why) {
                        <p id="la-sample-why" class="text-secondary m-0 text-sm">{{ why }}</p>
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
                        <button type="button" mat-button (click)="firstRun.enter(madeId()!, '/')">
                            Open the sample Space
                        </button>
                    }
                </li>
                <li class="bg-card flex flex-col gap-3 rounded-2xl border p-4" data-choice="own">
                    <mat-icon class="icon-size-6" svgIcon="heroicons_outline:arrow-up-tray"></mat-icon>
                    <h2 class="m-0 text-lg font-semibold">Use my own data</h2>
                    <p class="text-secondary m-0 flex-1 text-sm">
                        @if (state().hasSpace) {
                            Add a file to this Space: it becomes a Dataset, a table Inspecto keeps and you can explore.
                        } @else {
                            Create a Space for one kind of data, such as mobile money or call records, then add a file.
                        }
                    </p>
                    <button
                        type="button"
                        mat-stroked-button
                        [disabled]="!!ownBlocked()"
                        [attr.aria-describedby]="ownBlocked() ? 'la-own-why' : null"
                        (click)="useOwnData()"
                    >
                        {{ state().hasSpace ? 'Add data' : 'Create a Space' }}
                    </button>
                    @if (ownBlocked(); as why) {
                        <p id="la-own-why" class="text-secondary m-0 text-sm">{{ why }}</p>
                    }
                </li>
                @if (spaces.availableSpaces().length) {
                    <li class="bg-card flex flex-col gap-3 rounded-2xl border p-4" data-choice="team">
                        <mat-icon class="icon-size-6" svgIcon="heroicons_outline:user-group"></mat-icon>
                        <h2 class="m-0 text-lg font-semibold">Open my team's data</h2>
                        <p class="text-secondary m-0 flex-1 text-sm">
                            Your team may already keep data in another Space. Pick it here.
                        </p>
                        <inspecto-space-switcher
                            [dataKind]="true"
                            preferredSample="la-sample"
                        ></inspecto-space-switcher>
                    </li>
                }
            </ul>
            <inspecto-space-guide [state]="state()" (stepOpen)="firstRun.openStep($event)"></inspecto-space-guide>
        </section>
    `,
})
export class GettingStartedComponent implements OnInit {
    /** The guide state the landing page loaded. */
    readonly state = input.required<SpaceGuideState>();

    protected readonly product = LA_PRODUCT_NAME;
    protected readonly firstRun = inject(FirstRun);
    protected readonly spaces = inject(SpacesService);
    private readonly router = inject(Router);

    /** The sample template as this server lists it; undefined until read, null when it does not ship. */
    private readonly sample = signal<SpaceTemplateInfo | null | undefined>(undefined);
    protected readonly loading = signal(false);
    protected readonly error = signal<string | null>(null);
    protected readonly failed = signal<SampleLoadResult[]>([]);
    /** The sample Space a load with failures still created. */
    protected readonly madeId = signal<string | null>(null);

    /** Why *Try the sample* cannot run here, or null. */
    protected readonly sampleBlocked = computed<string | null>(() => {
        if (!this.spaces.multiSpace()) return 'This server runs a single Space, so a sample Space cannot be added.';
        if (!this.firstRun.canCreate())
            return 'Only an administrator can create a Space. Ask yours, or open your team’s data.';
        const s = this.sample();
        if (s === undefined) return null;
        if (s === null || !s.samples) return 'The sample is not installed on this server.';
        if (s.creatable === false)
            return `The sample cannot be created in this edition: ${(s.missingFeatures ?? []).map((m) => m.message).join(' ')}`;
        return null;
    });

    /** Why *Use my own data* cannot run here, or null (a Space is needed first and the user may not create one). */
    protected readonly ownBlocked = computed<string | null>(() =>
        this.state().hasSpace || this.firstRun.canCreate()
            ? null
            : 'Only an administrator can create a Space. Ask yours, or open your team’s data.',
    );

    ngOnInit(): void {
        this.spaces.templates().subscribe({
            next: (all) => this.sample.set(all.find((t) => t.id === LA_SAMPLE_TEMPLATE) ?? null),
            error: () => this.sample.set(null),
        });
    }

    protected trySample(): void {
        this.loading.set(true);
        this.error.set(null);
        this.failed.set([]);
        this.firstRun.trySample().subscribe({
            next: (made) => {
                this.loading.set(false);
                this.failed.set(failedSampleRuns(made));
                this.madeId.set(made.id);
            },
            error: (e) => {
                this.loading.set(false);
                this.error.set(apiErrorMessage(e, 'Could not create the sample Space.'));
            },
        });
    }

    protected useOwnData(): void {
        if (this.state().hasSpace) void this.router.navigateByUrl(ADD_DATA_ROUTE);
        else this.firstRun.createSpace(ADD_DATA_ROUTE);
    }
}
