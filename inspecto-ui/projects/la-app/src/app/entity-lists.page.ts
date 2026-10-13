import { ChangeDetectionStrategy, Component } from '@angular/core';
import { InvestigationSessionStore } from '@inspecto/link-analysis/link-analysis/link-analysis-investigation.store';
import { LinkAnalysisEntityListsComponent } from '@inspecto/link-analysis/link-analysis/link-analysis-entity-lists.component';
import { LinkAnalysisIdentityComponent } from '@inspecto/link-analysis/link-analysis/link-analysis-identity.component';

/**
 * Entity Lists on their own page. The library renders them inside an Investigation; here there is no open
 * Investigation, so this page provides the (empty) session store the section reads. Listing, creating, adding to and
 * retiring a list work without an Investigation; excluding or seeding by a list needs one and stays off.
 */
@Component({
    selector: 'la-app-entity-lists-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [LinkAnalysisEntityListsComponent, LinkAnalysisIdentityComponent],
    providers: [InvestigationSessionStore],
    template: `
        <div class="mx-auto flex w-full max-w-4xl flex-col gap-4 p-6">
            <h1 id="la-entity-lists-title" class="text-title m-0 font-semibold">Entity Lists</h1>
            <h2 class="sr-only">Lists and identity</h2>
            <inspecto-link-analysis-entity-lists></inspecto-link-analysis-entity-lists>
            <inspecto-link-analysis-identity></inspecto-link-analysis-identity>
        </div>
    `,
})
export class EntityListsPageComponent {}
