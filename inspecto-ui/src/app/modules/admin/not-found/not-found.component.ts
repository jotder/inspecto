import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/**
 * The `**` route (UI consolidation plan UI-16). Before it existed, a URL that matched nothing left the
 * SPA sitting on the splash screen forever with only an `NG04002` in the console — indistinguishable
 * from a hung app. That is exactly what a stale bookmark, a renamed route, or a dead link the app
 * itself still carried (Home's `/catalog-onboard`) did to an operator.
 *
 * It states the path it could not open, so a bad link can be reported rather than guessed at.
 */
@Component({
    selector: 'inspecto-not-found',
    standalone: true,
    imports: [RouterLink, MatButtonModule, InspectoEmptyStateComponent, InspectoPageHeaderComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex min-w-0 flex-auto flex-col">
            <inspecto-page-header title="Page not found" subtitle="This address does not match anything in Inspecto." />
            <div class="flex flex-auto flex-col p-6 sm:p-10">
                <inspecto-empty-state
                    icon="heroicons_outline:map"
                    title="Nothing lives at {{ path }}"
                    message="The link may be out of date, or the feature may belong to an edition this deployment does not bundle. Pick a page from the menu, or go Home."
                />
                <div class="mt-4">
                    <a mat-flat-button color="primary" routerLink="/home">Go Home</a>
                </div>
            </div>
        </div>
    `,
})
export class NotFoundComponent {
    /** The URL that matched nothing — read once, so it reflects the address the operator actually used. */
    readonly path = inject(Router).url;
}
