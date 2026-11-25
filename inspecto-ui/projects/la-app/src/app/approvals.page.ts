import { ChangeDetectionStrategy, Component } from '@angular/core';
import { InspectoPageHeaderComponent } from '@inspecto/core/components/page-header.component';
import { LinkExpansionApprovalsComponent } from 'app/modules/admin/approvals/link-expansions/link-expansion-approvals.component';

/**
 * LA-FULL-CYCLE-RESIDUALS-1 (item 4): approvals inbox across Investigations in Inspecto LA.
 * Wraps the console's `LinkExpansionApprovalsComponent` (`GET /inv/pending`, `approve`, `deny`)
 * inside the LA shell.
 */
@Component({
    selector: 'la-app-approvals-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoPageHeaderComponent, LinkExpansionApprovalsComponent],
    template: `
        <div class="mx-auto w-full max-w-5xl p-6">
            <inspecto-page-header
                title="Approvals"
                subtitle="Held Link Analysis expands across Investigations waiting for a second person’s decision."
                [terms]="['Investigation']"
            />
            <app-link-expansion-approvals />
        </div>
    `,
})
export class ApprovalsPageComponent {}
