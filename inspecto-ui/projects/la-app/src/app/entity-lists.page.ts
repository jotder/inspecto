import { ChangeDetectionStrategy, Component } from '@angular/core';
import { InvestigationSessionStore } from '@inspecto/link-analysis/link-analysis/link-analysis-investigation.store';
import { LinkAnalysisEntityListsComponent } from '@inspecto/link-analysis/link-analysis/link-analysis-entity-lists.component';

/**
 * Entity Lists on their own page. The library renders them inside an Investigation; here there is no open
 * Investigation, so this page provides the (empty) session store the section reads. Listing, creating, adding to and
 * retiring a list work without an Investigation; excluding or seeding by a list needs one and stays off.
 */
@Component({
    selector: 'la-app-entity-lists-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [LinkAnalysisEntityListsComponent],
    providers: [InvestigationSessionStore],
    template: `
        <section class="mx-auto flex w-full max-w-4xl flex-col gap-4 p-6" aria-labelledby="la-entity-lists-title">
            <h1 id="la-entity-lists-title" class="text-title m-0 font-semibold">Entity Lists</h1>
            <inspecto-link-analysis-entity-lists></inspecto-link-analysis-entity-lists>
        </section>
    `,
})
export class EntityListsPageComponent {}
