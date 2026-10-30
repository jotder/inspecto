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
            <app-access-roles [labels]="roleLabels" product="link-analysis" [capabilityLabels]="capabilityLabels" />
        </section>
    `,
})
export class AccessPageComponent {
    /** `pipeline-developer` is a Pipeline word, which an Inspecto LA screen does not use; the id is unchanged. */
    protected readonly roleLabels = { 'pipeline-developer': 'Data builder' };
    /** Each capability Link Analysis gates, in this app's words (the id stays in the tooltip). */
    protected readonly capabilityLabels: Record<string, string> = {
        canRevealLinkEntities: 'See unmasked ids',
        canApproveLinkExpansions: 'Approve large expansions',
        canRunLinkGraphAnalysis: 'Run server algorithms',
        canBuildLinkIndex: 'Build the link index',
        canShareInvestigation: 'Share and embed Investigations',
        canManageExclusionCategories: 'Manage exclusion categories',
        canUploadData: 'Upload data',
        canAuthorAlertRules: 'Author Alert Rules',
        canManageIncidents: 'Create and edit Investigations, lists and identities',
        canWorkIncidents: 'Acknowledge and resolve Alerts',
        canAuthorWorkbench: 'Set up Datasets, Entity Types and saved views',
        canAdminister: 'Administer Spaces and their settings',
        canConfigureAccess: 'Edit roles and access',
    };
}
