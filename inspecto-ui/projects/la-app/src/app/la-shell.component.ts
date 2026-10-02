import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SpaceSwitcherComponent } from 'app/layout/common/space-switcher/space-switcher.component';
import { UserComponent } from 'app/layout/common/user/user.component';
import { ConnectivityBannerComponent } from '@inspecto/core/components/connectivity-banner.component';
import { APP_ENVIRONMENT } from '@inspecto/core/api/app-environment';
import { LA_APP_NAV } from './la-nav';

/**
 * The signed-in frame: a top bar (product name, the three areas, Space switcher, user menu) over the routed page.
 * Smaller than the gamma host's classic layout on purpose: no sidebar tree, no Lens switcher, no search.
 */
@Component({
    selector: 'la-app-shell',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ConnectivityBannerComponent,
        MatIconModule,
        RouterLink,
        RouterLinkActive,
        RouterOutlet,
        SpaceSwitcherComponent,
        UserComponent,
    ],
    host: { class: 'flex h-full min-h-screen w-full flex-col' },
    template: `
        <inspecto-connectivity-banner></inspecto-connectivity-banner>
        <header class="bg-card flex h-14 flex-0 items-center gap-4 border-b px-4 md:px-6">
            <a routerLink="/" class="flex items-center gap-2 no-underline" aria-label="Link Analysis home">
                <img class="h-6" [src]="logo" alt="" />
                <span class="font-semibold">Link Analysis</span>
            </a>
            <nav class="flex flex-1 items-center gap-1" aria-label="Areas">
                @for (item of nav; track item.path) {
                    <a
                        [routerLink]="'/' + item.path"
                        routerLinkActive="font-semibold underline"
                        class="rounded-md px-3 py-1.5 text-sm no-underline"
                    >
                        {{ item.label }}
                    </a>
                }
            </nav>
            <inspecto-space-switcher></inspecto-space-switcher>
            <user [showAvatar]="false"></user>
        </header>
        <main class="flex min-h-0 flex-1 flex-col">
            <router-outlet></router-outlet>
        </main>
        <footer class="text-secondary flex-0 border-t px-4 py-2 text-xs md:px-6">{{ footer }}</footer>
    `,
})
export class LaShellComponent {
    private readonly env = inject(APP_ENVIRONMENT);
    protected readonly nav = LA_APP_NAV;
    protected readonly logo = this.env.appLogo;
    protected readonly footer = this.env.footerText;
}
