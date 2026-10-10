import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SpaceSwitcherComponent } from 'app/layout/common/space-switcher/space-switcher.component';
import { UserComponent } from 'app/layout/common/user/user.component';
import { ConnectivityBannerComponent } from '@inspecto/core/components/connectivity-banner.component';
import { APP_ENVIRONMENT } from '@inspecto/core/api/app-environment';
import { LA_APP_NAV } from './la-nav';

/** localStorage key remembering whether the navigation rail is expanded ('1') or slim (anything else). */
export const LA_RAIL_EXPANDED_KEY = 'la-app.rail-expanded';

function readExpanded(): boolean {
    try {
        return localStorage.getItem(LA_RAIL_EXPANDED_KEY) === '1';
    } catch {
        return false;
    }
}

/**
 * The signed-in frame: a LEFT navigation rail (product mark, the three areas, Space switcher, user menu) beside the
 * routed page, so the page gets the full window height. The rail is slim (icons) by default and expands to show
 * labels; below the md breakpoint (960px) the same markup lays out as a compact top bar with icons only.
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
        MatIconModule,
        MatTooltipModule,
        RouterLink,
        RouterLinkActive,
        RouterOutlet,
        SpaceSwitcherComponent,
        UserComponent,
    ],
    host: {
        class: 'flex h-full min-h-screen w-full flex-col [--shell-chrome-height:3rem] md:flex-row md:[--shell-chrome-height:0rem]',
    },
    template: `
        <aside
            class="bg-card flex h-12 flex-0 flex-row items-center gap-1 border-b px-2 md:sticky md:top-0 md:h-screen md:flex-col md:items-stretch md:gap-2 md:border-r md:border-b-0 md:py-3"
            [class.md:w-16]="!expanded()"
            [class.md:w-56]="expanded()"
        >
            <a
                routerLink="/"
                class="focus-visible:outline-primary flex h-10 items-center gap-2 rounded-md px-2 no-underline focus-visible:outline-2"
                aria-label="Link Analysis home"
                [matTooltip]="footer"
                matTooltipPosition="right"
            >
                <img class="h-6 w-6 flex-0 object-contain" [src]="logo" alt="" />
                @if (expanded()) {
                    <span class="truncate font-semibold max-md:hidden">Link Analysis</span>
                }
            </a>
            <nav class="flex flex-1 flex-row items-center gap-1 md:mt-2 md:flex-col md:items-stretch" aria-label="Areas">
                @for (item of nav; track item.path) {
                    <a
                        [routerLink]="'/' + item.path"
                        routerLinkActive="bg-hover text-primary font-semibold"
                        ariaCurrentWhenActive="page"
                        class="focus-visible:outline-primary flex h-10 items-center gap-3 rounded-md px-2.5 text-sm no-underline focus-visible:outline-2"
                        [matTooltip]="expanded() ? '' : item.label"
                        matTooltipPosition="right"
                    >
                        <mat-icon class="icon-size-5 flex-0" [svgIcon]="item.icon"></mat-icon>
                        <span [class]="expanded() ? 'max-md:sr-only' : 'sr-only'">{{ item.label }}</span>
                    </a>
                }
            </nav>
            <div class="flex flex-row items-center gap-1 md:flex-col md:items-stretch">
                <div [class.slim-switcher]="!expanded()">
                    <inspecto-space-switcher></inspecto-space-switcher>
                </div>
                <user [showAvatar]="false"></user>
                <button
                    type="button"
                    class="focus-visible:outline-primary flex h-10 items-center gap-3 rounded-md px-2.5 text-sm max-md:hidden focus-visible:outline-2"
                    [attr.aria-expanded]="expanded()"
                    [attr.aria-label]="expanded() ? 'Collapse navigation' : 'Expand navigation'"
                    [matTooltip]="expanded() ? '' : 'Expand navigation'"
                    matTooltipPosition="right"
                    (click)="toggle()"
                >
                    <mat-icon
                        class="icon-size-5 flex-0"
                        [svgIcon]="
                            expanded() ? 'heroicons_outline:chevron-double-left' : 'heroicons_outline:chevron-double-right'
                        "
                    ></mat-icon>
                    @if (expanded()) {
                        <span>Collapse</span>
                    }
                </button>
                @if (expanded()) {
                    <p class="text-secondary m-0 px-2 text-xs max-md:hidden">{{ footer }}</p>
                }
            </div>
        </aside>
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
    protected readonly nav = LA_APP_NAV;
    protected readonly logo = this.env.appLogo;
    protected readonly footer = this.env.footerText;
    protected readonly expanded = signal(readExpanded());

    protected toggle(): void {
        const next = !this.expanded();
        this.expanded.set(next);
        try {
            localStorage.setItem(LA_RAIL_EXPANDED_KEY, next ? '1' : '0');
        } catch {
            /* storage blocked: the choice just does not persist */
        }
    }
}
