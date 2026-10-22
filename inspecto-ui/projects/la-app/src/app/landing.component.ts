import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { SpaceGuideComponent } from '@inspecto/core/onboarding/space-guide.component';
import { setupIncomplete, SpaceGuideState } from '@inspecto/core/onboarding/space-guide';
import { SpaceGuideService } from '@inspecto/core/onboarding/space-guide.service';
import { LA_PRODUCT_NAME } from './la-brand';
import { SessionService } from '@inspecto/core/auth/session.service';
import { FirstRun } from './first-run';
import { GettingStartedComponent } from './getting-started.component';
import { visibleLaNav } from './la-nav';

/**
 * The front door: **Getting started** while setup is incomplete (no Space, or no Dataset yet; LA-ONBOARD-1 UC-01),
 * otherwise one card per area of the application, with the Space guide above them until it is done.
 */
@Component({
    selector: 'la-app-landing',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule, RouterLink, InspectoSkeletonComponent, GettingStartedComponent, SpaceGuideComponent],
    template: `
        @if (state(); as s) {
            @if (firstRunPage()) {
                <la-app-getting-started [state]="s"></la-app-getting-started>
            } @else {
                <section class="mx-auto flex max-w-4xl flex-col gap-6 p-6" aria-labelledby="la-landing-title">
                    <header class="flex flex-col gap-1">
                        <h1 id="la-landing-title" class="text-title m-0 font-semibold">{{ product }}</h1>
                        <p class="text-secondary m-0">
                            Find the links between entities in your data: explore them as a graph, place them on a map,
                            and keep lists of the ones that matter.
                        </p>
                    </header>
                    <inspecto-space-guide [state]="s" (stepOpen)="firstRun.openStep($event)"></inspecto-space-guide>
                    <ul class="m-0 grid list-none grid-cols-1 gap-4 p-0 md:grid-cols-2">
                        @for (item of nav(); track item.path) {
                            <li>
                                <a
                                    [routerLink]="'/' + item.path"
                                    class="bg-card flex h-full flex-col gap-2 rounded-lg border p-4 no-underline"
                                >
                                    <mat-icon class="icon-size-6" [svgIcon]="item.icon"></mat-icon>
                                    <span class="text-lg font-semibold">{{ item.label }}</span>
                                    <span class="text-secondary text-sm">{{ item.blurb }}</span>
                                </a>
                            </li>
                        }
                    </ul>
                </section>
            }
        } @else {
            <section class="mx-auto flex max-w-4xl flex-col gap-6 p-6" aria-busy="true" aria-label="Loading">
                <inspecto-skeleton width="40%" height="1.5rem"></inspecto-skeleton>
                <inspecto-skeleton [lines]="3"></inspecto-skeleton>
            </section>
        }
    `,
})
export class LandingComponent implements OnInit {
    protected readonly product = LA_PRODUCT_NAME;
    private readonly session = inject(SessionService);
    private readonly guide = inject(SpaceGuideService);
    protected readonly firstRun = inject(FirstRun);
    protected readonly nav = computed(() => visibleLaNav(this.session.features()));
    /** The current Space's setup, from the server; null while it loads. */
    protected readonly state = signal<SpaceGuideState | null>(null);
    protected readonly firstRunPage = computed(() => {
        const s = this.state();
        return !!s && setupIncomplete(s);
    });

    ngOnInit(): void {
        this.guide.load().subscribe((s) => this.state.set(s));
    }
}
