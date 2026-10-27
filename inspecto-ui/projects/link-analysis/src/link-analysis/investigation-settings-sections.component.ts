import { ChangeDetectionStrategy, Component } from '@angular/core';
import { LinkAnalysisInvestigationComponent } from './link-analysis-investigation.component';

/**
 * **The Investigation's setup and governance sections** (LA-STUDIO-UI-REORG-1 §5.10 "This Investigation", P2a): Case,
 * Members, Share and embed, Drafts, Replay and drift, Pin to a Widget, Oversight, Measures and templates, Dossier and
 * References — moved out of the Act panel, rendered unchanged. The ⚙ Settings dialog hosts it in This Investigation;
 * the instance shares the screen's Investigation store.
 */
@Component({
    selector: 'inspecto-la-investigation-settings-sections',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [LinkAnalysisInvestigationComponent],
    template: `<inspecto-link-analysis-investigation section="settings"></inspecto-link-analysis-investigation>`,
})
export class InvestigationSettingsSections {}
