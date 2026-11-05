import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Router, RouterLink } from '@angular/router';
import { LensService, SessionService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { LockedActionComponent } from 'app/inspecto/components/locked-action.component';
import { Dataset } from './dataset-types';
import { FeedOffer, feedOffers, starterBoard, starterColumns } from './dataset-page';
import { DatasetsService } from './datasets.service';

/** Where each offer goes in the console. */
export const LA_ROUTE = '/studio/link-analysis';
export const NEW_WIDGET_ROUTE = '/studio/widgets/new';
export const DASHBOARDS_ROUTE = '/studio/dashboards';

/**
 * **What this Dataset can feed** (ONBOARDING-FIRST-RUN-1 B4, C5; console UC-8, H9): offers read from the Dataset's
 * columns and roles. A link between two columns → *Explore as a network in Link Analysis* (and, Standard and up, *Show
 * the network on a dashboard*); a date and an amount → *Make a starter dashboard* (`POST /bi/templates/trend-monitor/apply`
 * over its own columns). An offer this edition or this person cannot fulfil shows locked with its reason (H4).
 *
 * @example <app-dataset-offers [dataset]="d" />
 */
@Component({
    selector: 'app-dataset-offers',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, RouterLink, InspectoAlertComponent, LockedActionComponent],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 border-t pt-3"
            [attr.aria-labelledby]="titleId"
            data-testid="dataset-offers"
        >
            <h4 class="m-0 text-sm font-semibold" [id]="titleId">What this Dataset can feed</h4>
            @for (o of offers(); track o.id) {
                <div class="flex flex-col gap-1" [attr.data-testid]="'offer-' + o.id">
                    <p class="text-secondary m-0 text-sm">{{ o.because }}</p>
                    @if (o.lock) {
                        <inspecto-locked-action [label]="o.action" [reason]="o.lock"></inspecto-locked-action>
                    } @else {
                        <button
                            mat-stroked-button
                            type="button"
                            class="self-start"
                            [attr.data-testid]="'offer-' + o.id + '-go'"
                            [disabled]="busy()"
                            (click)="go(o)"
                        >
                            <mat-icon [svgIcon]="icon(o)"></mat-icon>
                            {{ o.action }}
                        </button>
                    }
                </div>
            } @empty {
                <p class="text-secondary m-0 text-sm" data-testid="no-offers">
                    Nothing to suggest yet. A Dataset whose rows link two columns can be explored as a network; one with
                    a date and an amount can start a dashboard.
                </p>
            }
            @if (made(); as board) {
                <p class="m-0 text-sm" data-testid="starter-exists">
                    A starter dashboard for this Dataset exists already:
                    <a [routerLink]="[dashboards, board]">open {{ board }}</a
                    >.
                </p>
            }
            @if (error()) {
                <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
            }
        </section>
    `,
})
export class DatasetOffersComponent {
    private static nextId = 0;
    private readonly api = inject(DatasetsService);
    private readonly router = inject(Router);
    private readonly session = inject(SessionService);
    private readonly lens = inject(LensService);
    private readonly destroyRef = inject(DestroyRef);

    readonly dataset = input.required<Dataset>();

    protected readonly titleId = `app-dataset-offers-${DatasetOffersComponent.nextId++}`;
    protected readonly dashboards = DASHBOARDS_ROUTE;
    protected readonly busy = signal(false);
    protected readonly error = signal('');
    /** The starter board when applying found it made already (409). */
    protected readonly made = signal<string | null>(null);

    protected readonly offers = computed(() =>
        feedOffers(this.dataset(), {
            flags: this.session.features(),
            reasons: this.session.featureReasons(),
            signIn: this.session.authMode() === 'oidc',
            canAuthor: this.lens.canAuthorWorkbench(),
        }),
    );

    protected icon(o: FeedOffer): string {
        return o.id === 'starter-dashboard' ? 'heroicons_outline:chart-bar' : 'heroicons_outline:share';
    }

    protected go(o: FeedOffer): void {
        const id = this.dataset().id;
        if (o.id === 'network') void this.router.navigate([LA_ROUTE], { queryParams: { dataset: id } });
        else if (o.id === 'network-widget') void this.router.navigateByUrl(NEW_WIDGET_ROUTE);
        else this.makeStarter();
    }

    private makeStarter(): void {
        const d = this.dataset();
        const columns = starterColumns(d);
        if (!columns) return;
        this.busy.set(true);
        this.error.set('');
        this.made.set(null);
        this.api
            .applyStarter(d.id, columns)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (board) => {
                    this.busy.set(false);
                    void this.router.navigate([DASHBOARDS_ROUTE, board]);
                },
                error: (e: unknown) => {
                    this.busy.set(false);
                    const why = apiErrorMessage(e, 'The starter dashboard was not made.');
                    if (!(e instanceof HttpErrorResponse && e.status === 409)) {
                        this.error.set(why);
                        return;
                    }
                    // A 409 is a board made before, or an approval hold / a left-over widget: link only to a board that exists.
                    const board = starterBoard(d.id);
                    this.api
                        .dashboardExists(board)
                        .pipe(takeUntilDestroyed(this.destroyRef))
                        .subscribe((exists) => (exists ? this.made.set(board) : this.error.set(why)));
                },
            });
    }
}
