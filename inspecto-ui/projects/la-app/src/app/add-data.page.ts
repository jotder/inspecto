import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * PLACEHOLDER for **Add data** (`/add-data`, OB-14: the screen title is *Onboard a file*). LA-ONBOARD-1 phase 4
 * replaces this file with the one Add data component both apps use (OF-1); the first-run page already links here.
 */
@Component({
    selector: 'la-app-add-data',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [RouterLink],
    template: `
        <section class="mx-auto flex max-w-3xl flex-col gap-3 p-6" aria-labelledby="la-add-data-title">
            <h1 id="la-add-data-title" class="text-title m-0 font-semibold">Onboard a file</h1>
            <p class="text-secondary m-0">
                This screen is being built: soon you will drop a file here, check its columns and say what they hold.
            </p>
            <p class="m-0"><a routerLink="/" class="text-primary">Back to Getting started</a></p>
        </section>
    `,
})
export class AddDataPageComponent {}
