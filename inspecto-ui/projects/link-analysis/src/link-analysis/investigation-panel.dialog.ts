import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { LinkAnalysisDossierComponent } from './link-analysis-dossier.component';
import { LinkAnalysisMembersComponent } from './link-analysis-members.component';

/** The Investigation panels reachable straight from a menu (LA-FULL-CYCLE-TEST-1 phase 5, finding 22). */
export type InvestigationPanel = 'dossier' | 'members';

export interface InvestigationPanelData {
    panel: InvestigationPanel;
    investigationId: string;
    /** The Investigation's name, for the dialog's heading (the id when it has none). */
    title: string;
}

/** The menu entry's words: the same two in the Investigation actions menu (⋯) and in File. */
export const INVESTIGATION_PANEL_LABELS: Record<InvestigationPanel, string> = {
    dossier: 'Build Dossier…',
    members: 'Members…',
};

const HEADINGS: Record<InvestigationPanel, string> = { dossier: 'Dossier', members: 'Members' };

/**
 * One Investigation panel — the Dossier or Members — in a dialog, so *Build Dossier…* and *Members…* open where the
 * analyst asked instead of as a fold deep inside Investigation settings. It renders the SAME component the settings
 * fold does (the fold stays: settings still lists everything about the Investigation in one place).
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatDialogModule, LinkAnalysisDossierComponent, LinkAnalysisMembersComponent],
    template: `
        <h2 mat-dialog-title>
            {{ heading }} <span class="text-secondary text-base font-normal">· {{ data.title }}</span>
        </h2>
        <mat-dialog-content class="w-[40rem] max-w-full" [attr.data-testid]="'investigation-panel-' + data.panel">
            @if (data.panel === 'dossier') {
                <inspecto-link-analysis-dossier
                    [investigationId]="data.investigationId"
                ></inspecto-link-analysis-dossier>
            } @else {
                <inspecto-link-analysis-members
                    [investigationId]="data.investigationId"
                ></inspecto-link-analysis-members>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="ref.close()">Close</button>
        </mat-dialog-actions>
    `,
})
export class InvestigationPanelDialog {
    readonly data = inject<InvestigationPanelData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<InvestigationPanelDialog>);
    readonly heading = HEADINGS[this.data.panel];
}

/** Open the Dossier or Members of the Investigation `investigationId`. */
export function openInvestigationPanel(
    dialog: MatDialog,
    panel: InvestigationPanel,
    investigationId: string,
    title: string,
): MatDialogRef<InvestigationPanelDialog> {
    return dialog.open(InvestigationPanelDialog, {
        data: { panel, investigationId, title: title || investigationId } satisfies InvestigationPanelData,
        autoFocus: 'dialog',
        maxWidth: '95vw',
    });
}
