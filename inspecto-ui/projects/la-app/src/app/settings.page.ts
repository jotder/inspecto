import { ChangeDetectionStrategy, Component } from '@angular/core';
import { LinkAnalysisSettingsComponent } from 'app/modules/admin/settings/link-analysis-settings.component';

/**
 * Settings > Link Analysis on its own page (the gamma host shows the same component inside its Settings). The component
 * reads `canAuthorWorkbench` itself: without it the fields are read-only, and the server refuses a save (403) anyway.
 */
@Component({
    selector: 'la-app-settings-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [LinkAnalysisSettingsComponent],
    template: `
        <section class="mx-auto flex w-full max-w-4xl flex-col gap-4 p-6" aria-label="Link Analysis settings">
            <inspecto-link-analysis-settings></inspecto-link-analysis-settings>
        </section>
    `,
})
export class SettingsPageComponent {}
