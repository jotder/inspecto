import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { LA_APP_NAV } from './la-nav';

/** The front door: one card per area of the application. */
@Component({
    selector: 'la-app-landing',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule, RouterLink],
    template: `
        <section class="mx-auto flex max-w-4xl flex-col gap-6 p-6" aria-labelledby="la-landing-title">
            <header class="flex flex-col gap-1">
                <h1 id="la-landing-title" class="text-title m-0 font-semibold">Link Analysis</h1>
                <p class="text-secondary m-0">
                    Find the links between entities in your data: explore them as a graph, place them on a map, and keep
                    lists of the ones that matter.
                </p>
            </header>
            <ul class="m-0 grid list-none grid-cols-1 gap-4 p-0 md:grid-cols-3">
                @for (item of nav; track item.path) {
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
    `,
})
export class LandingComponent {
    protected readonly nav = LA_APP_NAV;
}
