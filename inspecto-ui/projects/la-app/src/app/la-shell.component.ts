import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { InspectoSplitDirective } from '@inspecto/core/components/split.directive';
import { LaRailService } from '@inspecto/link-analysis/la-rail.service';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SpaceSwitcherComponent } from 'app/layout/common/space-switcher/space-switcher.component';
import { UserComponent } from 'app/layout/common/user/user.component';
import { ConnectivityBannerComponent } from '@inspecto/core/components/connectivity-banner.component';
import { APP_ENVIRONMENT } from '@inspecto/core/api/app-environment';
import { SessionService } from '@inspecto/core/auth/session.service';
import { LA_APP_SHORTCUTS, ShortcutsHelpDialog, isEditingTarget } from 'app/inspecto/shortcuts-help.dialog';
import { areaIcon, productNav, ProductNavEntry } from '@inspecto/core/product/product-host';
import { NextStepsDialog } from './next-steps.dialog';
import { PRODUCT } from './product.generated';

export { LA_RAIL_EXPANDED_KEY } from '@inspecto/link-analysis/la-rail.service';

/** One rail link: an App area (PRODUCT-ASSEMBLY-1 PA-4: from the Product's nav) or the shell's own Settings. */
export interface RailLink {
    readonly path: string;
    readonly label: string;
    readonly icon: string;
}

/**
 * The rail link to Settings: the SHELL's, not an App area, so it is not in any app.toon. Shown to every signed-in user,
 * because the page always has a section for them (their own limits) and shows the others read-only unless they may
 * change them. Not on the landing page, which offers the areas.
 */
export const LA_APP_SETTINGS_NAV: RailLink = {
    path: 'settings',
    label: 'Settings',
    icon: 'heroicons_outline:cog-6-tooth',
};

/** An App area as a rail link. */
export const railLink = (n: ProductNavEntry): RailLink => ({ path: n.path, label: n.label, icon: areaIcon(n) });

/**
 * The signed-in frame: a LEFT navigation rail (product mark, the three areas, Space switcher, user menu) beside the
 * routed page, so the page gets the full window height. The rail is slim (icons) by default and expands to show
 * labels; below the sm breakpoint (600px) the same markup lays out as a compact top bar with icons only, pinned
 * to the top while the page scrolls. The rail stays down to 600px so a laptop at 125-150% scaling keeps the
 * side-by-side Link Analysis layout.
 * Smaller than the gamma host's classic layout on purpose: no sidebar tree, no Lens switcher, no search.
 *
 * The shell has no header or footer of its own, so it provides `--shell-chrome-height` (read by the Link Analysis
 * page's root height formula): 0 beside the rail, the 3rem top bar on a narrow window.
 */
@Component({
    selector: 'la-app-shell',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ConnectivityBannerComponent,
        InspectoSplitDirective,
        MatIconModule,
        MatTooltipModule,
        RouterLink,
        RouterLinkActive,
        RouterOutlet,
        SpaceSwitcherComponent,
        UserComponent,
    ],
    host: {
        class: 'flex h-full min-h-screen w-full flex-col [--shell-chrome-height:3rem] sm:flex-row sm:[--shell-chrome-height:0rem]',
        '(document:keydown)': 'onKeydown($event)',
    },
    template: `
        <aside
            class="bg-card flex min-h-12 flex-0 flex-row items-center gap-1 border-b px-2 max-sm:flex-wrap sticky top-0 z-40 sm:h-screen sm:flex-col sm:items-stretch sm:gap-2 sm:border-r sm:border-b-0 sm:py-3"
            [class.sm:w-16]="!expanded()"
            [style.width.px]="expanded() && wide() ? railSplit.width() : null"
        >
            <a
                routerLink="/"
                class="focus-visible:outline-primary flex h-10 items-center gap-2 rounded-md px-2 no-underline focus-visible:outline-2"
                [attr.aria-label]="product + ' home'"
                [matTooltip]="footer"
                matTooltipPosition="right"
            >
                <img class="h-6 w-6 flex-0 object-contain" [src]="logo" alt="" />
                @if (expanded()) {
                    <span class="truncate font-semibold max-sm:hidden">{{ product }}</span>
                }
            </a>
            <nav
                class="flex flex-1 flex-row items-center gap-1 sm:mt-2 sm:flex-col sm:items-stretch"
                aria-label="Areas"
            >
                @for (item of nav(); track item.path) {
                    <a
                        [routerLink]="'/' + item.path"
                        routerLinkActive="bg-hover text-primary font-semibold"
                        ariaCurrentWhenActive="page"
                        class="focus-visible:outline-primary flex h-10 items-center gap-3 rounded-md px-2.5 text-sm no-underline focus-visible:outline-2"
                        [matTooltip]="expanded() ? '' : item.label"
                        matTooltipPosition="right"
                    >
                        <mat-icon class="icon-size-5 flex-0" [svgIcon]="item.icon"></mat-icon>
                        <span [class]="expanded() ? 'max-sm:sr-only' : 'sr-only'">{{ item.label }}</span>
                    </a>
                }
            </nav>
            <div class="flex flex-row items-center gap-1 sm:flex-col sm:items-stretch">
                <!-- UC-15 rule 4: always one click away, on every screen. -->
                <button
                    type="button"
                    class="focus-visible:outline-primary flex h-10 items-center gap-3 rounded-md px-2.5 text-sm focus-visible:outline-2"
                    data-testid="la-next-steps-open"
                    aria-label="What can I do next?"
                    [matTooltip]="expanded() ? '' : 'What can I do next?'"
                    matTooltipPosition="right"
                    (click)="openNextSteps()"
                >
                    <mat-icon class="icon-size-5 flex-0" svgIcon="heroicons_outline:light-bulb"></mat-icon>
                    <span [class]="expanded() ? 'max-sm:sr-only' : 'sr-only'" aria-hidden="true"
                        >What can I do next?</span
                    >
                </button>
                <div [class.slim-switcher]="!expanded()">
                    <inspecto-space-switcher [dataKind]="true" preferredSample="la-sample"></inspecto-space-switcher>
                </div>
                <user [showAvatar]="false"></user>
                @if (expanded()) {
                    <p class="text-secondary m-0 px-2 text-xs max-sm:hidden">{{ footer }}</p>
                }
            </div>
        </aside>
        <!-- the expanded rail is drag-resizable like the Link Analysis toolbox; the collapse toggle is in the page header -->
        <div
            inspectoSplit="la-app.rail"
            #railSplit="inspectoSplit"
            [min]="160"
            [max]="400"
            [defaultWidth]="224"
            class="max-sm:hidden"
            [class.hidden]="!expanded()"
            aria-label="Resize the navigation (arrow keys or drag)"
        ></div>
        <div class="flex min-h-0 min-w-0 flex-1 flex-col">
            <inspecto-connectivity-banner></inspecto-connectivity-banner>
            <main class="flex min-h-0 flex-1 flex-col">
                <router-outlet></router-outlet>
            </main>
        </div>
    `,
    styles: `
        /* Slim rail: the Space switcher shows only its icon (its label and chevron belong to the expanded rail). */
        .slim-switcher ::ng-deep button {
            min-width: 0 !important;
            padding-inline: 0.625rem !important;
        }
        .slim-switcher ::ng-deep button span,
        .slim-switcher ::ng-deep button mat-icon ~ mat-icon {
            display: none !important;
        }
    `,
})
export class LaShellComponent {
    private readonly env = inject(APP_ENVIRONMENT);
    /**
     * The Product's areas, plus Settings: it always has a section for the signed-in user. PA-4: the server's nav when it
     * resolved one for this Product (`/bootstrap.product.nav`), else the generated nav under the feature rule.
     */
    private readonly session = inject(SessionService);
    protected readonly nav = computed<RailLink[]>(() => [
        ...productNav(this.session.product(), PRODUCT, this.session.features()).map(railLink),
        LA_APP_SETTINGS_NAV,
    ]);
    protected readonly product = PRODUCT.brand.name;
    protected readonly logo = this.env.appLogo;
    protected readonly footer = this.env.footerText;
    private readonly rail = inject(LaRailService);
    protected readonly expanded = this.rail.expanded;
    /** At sm and up the rail is a side column (an inline width applies); below it, a top bar. */
    private readonly smQuery = typeof matchMedia === 'function' ? matchMedia('(min-width: 600px)') : null;
    protected readonly wide = signal(this.smQuery?.matches ?? true);
    private readonly dialog = inject(MatDialog);

    constructor() {
        this.rail.available.set(true);
        const onChange = (e: MediaQueryListEvent) => this.wide.set(e.matches);
        this.smQuery?.addEventListener?.('change', onChange);
        inject(DestroyRef).onDestroy(() => {
            this.rail.available.set(false);
            this.smQuery?.removeEventListener?.('change', onChange);
        });
    }

    /** *What can I do next?* (UC-15 rule 4). */
    protected openNextSteps(): void {
        this.dialog.open(NextStepsDialog, { width: '560px', maxHeight: '90vh', autoFocus: 'first-tabbable' });
    }

    /** `?` (a bare press, never while editing text) opens the keyboard-shortcuts help, as in the main app. */
    protected onKeydown(event: KeyboardEvent): void {
        if (event.key !== '?' || event.ctrlKey || event.metaKey || event.altKey || isEditingTarget(event.target))
            return;
        event.preventDefault();
        this.dialog.open(ShortcutsHelpDialog, {
            width: '460px',
            autoFocus: false,
            data: { shortcuts: LA_APP_SHORTCUTS },
        });
    }
}
