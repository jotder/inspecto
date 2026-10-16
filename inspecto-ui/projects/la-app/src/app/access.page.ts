import { ChangeDetectionStrategy, Component } from '@angular/core';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { AccessRolesComponent } from 'app/modules/admin/access/access-roles.component';

/**
 * Access on its own page: the console's Settings > Access > Roles editor (the per-Space `roles.toon`: what each role
 * may do), reused whole. The component reads `canConfigureAccess` itself: without it the table is read-only, and the
 * server refuses a save (403) anyway. Who is in which role stays in the identity provider.
 */
@Component({
    selector: 'la-app-access-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoPageHeaderComponent, AccessRolesComponent],
    template: `
        <section class="mx-auto flex w-full max-w-4xl flex-col gap-4 p-6" aria-label="Access">
            <inspecto-page-header
                title="Access"
                subtitle="What each role may do in this Space. Who holds a role is set in the identity provider."
            />
            <app-access-roles />
        </section>
    `,
})
export class AccessPageComponent {}
