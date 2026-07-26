import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AiStatusDialog } from './ai-status.dialog';

/**
 * The "why is this red" affordance (AGT-6a A4-status) — one icon button a pane puts on a *row* or a
 * detail header, next to the thing whose state is in question.
 *
 * Unlike `<inspecto-ai-explain>`, which a pane drops in its header once, this one addresses a specific
 * entity: it needs a real id, so it belongs only where the pane has one. Everything renders in a
 * dialog, so adopting it disturbs no layout.
 *
 * @example
 * <inspecto-ai-status [label]="row.name" [pipelineId]="row.name" />
 * <inspecto-ai-status [label]="incident.id" [correlationId]="incident.correlationId" />
 */
@Component({
    selector: 'inspecto-ai-status',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <button
            mat-icon-button
            type="button"
            [attr.aria-label]="'What happened to ' + label()"
            [matTooltip]="'What happened to ' + label()"
            (click)="open()"
        >
            <mat-icon svgIcon="heroicons_outline:information-circle" />
        </button>
    `,
})
export class AiStatusComponent {
    private dialog = inject(MatDialog);

    /** The entity as the operator sees it — used in the tooltip, the aria-label and the dialog title. */
    readonly label = input.required<string>();

    /** The pipeline to read live state for; omit on panes that have no pipeline. */
    readonly pipelineId = input<string | undefined>(undefined);

    /** A correlation id, when the pane has one — yields the exact causal chain instead of a window. */
    readonly correlationId = input<string | undefined>(undefined);

    /** How far back the windowed timeline reaches; ignored when a `correlationId` is given. */
    readonly windowMinutes = input<number | undefined>(undefined);

    open(): void {
        this.dialog.open(AiStatusDialog, {
            data: {
                label: this.label(),
                pipelineId: this.pipelineId(),
                correlationId: this.correlationId(),
                windowMinutes: this.windowMinutes(),
            },
        });
    }
}
