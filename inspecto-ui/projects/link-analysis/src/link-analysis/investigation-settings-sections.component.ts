import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { LinkAnalysisInvestigationComponent } from './link-analysis-investigation.component';

/**
 * **The Investigation's setup and governance sections** (LA-STUDIO-UI-REORG-1 §5.10 "This Investigation", P2a): Open an
 * Investigation, Case, Members, Share and embed, Drafts, Replay and drift, Pin to a Widget, Oversight, Measures and
 * templates, Dossier, References, Entity Lists, Identities and Upload data — moved out of the Act panel, rendered
 * unchanged. P2b's ⚙ Settings dialog hosts this component; the instance shares the screen's Investigation store.
 */
@Component({
    selector: 'inspecto-la-investigation-settings-sections',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [LinkAnalysisInvestigationComponent],
    template: `<inspecto-link-analysis-investigation section="settings"></inspecto-link-analysis-investigation>`,
})
export class InvestigationSettingsSections {}

/**
 * TEMPORARY host (P2a → P2b): opened from the Act header's ⋯ ▸ Settings… until the ⚙ Settings dialog lands and takes
 * {@link InvestigationSettingsSections} into its "This Investigation" group. Delete this dialog then.
 */
@Component({
    selector: 'inspecto-la-investigation-settings-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatDialogModule, InvestigationSettingsSections],
    template: `
        <h2 mat-dialog-title>Investigation settings</h2>
        <mat-dialog-content>
            <inspecto-la-investigation-settings-sections></inspecto-la-investigation-settings-sections>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class InvestigationSettingsDialog {}
