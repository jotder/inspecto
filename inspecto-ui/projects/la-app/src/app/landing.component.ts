import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { SpaceGuideComponent } from '@inspecto/core/onboarding/space-guide.component';
import { guideDone, setupIncomplete, SpaceGuideState } from '@inspecto/core/onboarding/space-guide';
import { OnboardingEventsService } from '@inspecto/core/onboarding/onboarding-events.service';
import { SpaceGuideService } from '@inspecto/core/onboarding/space-guide.service';
import { SessionService } from '@inspecto/core/auth/session.service';
import { NgTemplateOutlet } from '@angular/common';
import { appAreas, areaIcon, productNav, ProductNavEntry } from '@inspecto/core/product/product-host';
import { FirstRun } from './first-run';
import { GettingStartedComponent } from './getting-started.component';
import { MyWorkComponent } from './my-work.component';
import { PRODUCT } from './product.generated';

/** An area as a landing card. */
const card = (n: ProductNavEntry) => ({ path: n.path, label: n.label, icon: areaIcon(n), blurb: n.blurb ?? '' });

/**
 * The front door: **Getting started** while setup is incomplete (no Space, or no Dataset yet; LA-ONBOARD-1 UC-01),
 * otherwise the Space guide until it is done, then **My work** (cases and what changed in the data, LA-ONBOARD-1 phase 7),
 * then one card per area of the application (PA-5b: grouped under each App's name when the Product has several).
 */
@Component({
    selector: 'la-app-landing',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatIconModule,
        NgTemplateOutlet,
        RouterLink,
        InspectoSkeletonComponent,
        GettingStartedComponent,
        SpaceGuideComponent,
        MyWorkComponent,
    ],
    template: `
        @if (state(); as s) {
            @if (firstRunPage()) {
                <la-app-getting-started [state]="s"></la-app-getting-started>
            } @else {
                <!-- LA-NO-LONG-PAGES-1: from md up the page is the window; the title stays, the rest scrolls under it -->
                <section
                    class="mx-auto flex w-full max-w-4xl flex-col gap-6 p-6 md:h-[calc(100dvh-var(--shell-chrome-height,0rem))] md:overflow-hidden"
                    aria-labelledby="la-landing-title"
                >
                    <header class="flex shrink-0 flex-col gap-1">
                        <h1 id="la-landing-title" class="text-title m-0 font-semibold">{{ product }}</h1>
                        @if (tagline) {
                            <p class="text-secondary m-0">{{ tagline }}</p>
                        }
                    </header>
                    <div
                        class="flex min-h-0 flex-auto flex-col gap-6 md:overflow-y-auto md:pr-1"
                        data-testid="la-landing-body"
                    >
                        <inspecto-space-guide [state]="s" (stepOpen)="firstRun.openStep($event)"></inspecto-space-guide>
                        <la-app-my-work></la-app-my-work>
                        <!-- PA-5b: one group per App, under its name, when the Product has several -->
                        @if (groups().length > 1) {
                            @for (g of groups(); track g.id) {
                                <section
                                    class="flex flex-col gap-3"
                                    data-testid="la-landing-app"
                                    [attr.aria-labelledby]="'la-landing-app-' + g.id"
                                >
                                    <h2 [id]="'la-landing-app-' + g.id" class="m-0 text-lg font-semibold">
                                        {{ g.title }}
                                    </h2>
                                    <ng-container
                                        *ngTemplateOutlet="cards; context: { $implicit: g.areas }"
                                    ></ng-container>
                                </section>
                            }
                        } @else {
                            <ng-container *ngTemplateOutlet="cards; context: { $implicit: nav() }"></ng-container>
                        }
                        <ng-template #cards let-items>
                            <ul class="m-0 grid list-none grid-cols-1 gap-4 p-0 md:grid-cols-2">
                                @for (item of items; track item.path) {
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
                        </ng-template>
                    </div>
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
    protected readonly product = PRODUCT.brand.name;
    /** The line under the Product's name (PA-5b: `brand.tagline` in products/<id>.toon). */
    protected readonly tagline = PRODUCT.brand.tagline ?? '';
    private readonly session = inject(SessionService);
    private readonly guide = inject(SpaceGuideService);
    private readonly events = inject(OnboardingEventsService);
    protected readonly firstRun = inject(FirstRun);
    /** The Product's areas (PA-4: the server's nav, else the generated one under the feature rule). */
    private readonly areas = computed(() => productNav(this.session.product(), PRODUCT, this.session.features()));
    /** One card per area the rail offers. */
    protected readonly nav = computed(() => this.areas().map(card));
    /** PA-5b: the same cards grouped under each App that draws one, in Product order. */
    protected readonly groups = computed(() =>
        appAreas(this.session.product(), PRODUCT, this.areas()).map((a) => ({
            id: a.id,
            title: a.title,
            areas: a.areas.map(card),
        })),
    );
    /** The current Space's setup, from the server; null while it loads. */
    protected readonly state = signal<SpaceGuideState | null>(null);
    protected readonly firstRunPage = computed(() => {
        const s = this.state();
        return !!s && setupIncomplete(s);
    });

    ngOnInit(): void {
        this.guide.load().subscribe((s) => {
            this.state.set(s);
            if (guideDone(s)) this.events.guideCompleted(); // OB-13
        });
    }
}
