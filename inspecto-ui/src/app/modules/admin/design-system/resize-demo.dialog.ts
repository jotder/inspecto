import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';

/**
 * Gallery demo host for the shared `[inspectoDialogResize]` chrome — a minimal dialog wearing the
 * drag grip + maximize button exactly the way a real adopter (grammar / node-config dialog) does.
 */
@Component({
    selector: 'inspecto-resize-demo-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatDialogModule, MatIconModule, MatTooltipModule, InspectoDialogResizeDirective],
    template: `
        <h2 mat-dialog-title class="flex items-center gap-2" inspectoDialogResize #chrome="inspectoDialogResize">
            <span class="min-w-0 truncate">Resizable dialog</span>
            <span class="flex-1"></span>
            <button
                mat-icon-button
                type="button"
                [attr.aria-label]="chrome.maximized() ? 'Exit full screen' : 'Full screen'"
                [matTooltip]="chrome.maximized() ? 'Exit full screen' : 'Full screen'"
                (click)="chrome.toggleMaximize()"
            >
                <mat-icon
                    class="icon-size-5"
                    [svgIcon]="chrome.maximized() ? 'heroicons_outline:arrows-pointing-in' : 'heroicons_outline:arrows-pointing-out'"
                ></mat-icon>
            </button>
        </h2>
        <mat-dialog-content>
            <p class="text-secondary text-sm">
                Drag the grip in the bottom-right corner (or focus it and use the arrow keys), or toggle
                the maximize button in the title. Once sized, the content flexes into the chosen size
                instead of scrolling inside a bigger dialog.
            </p>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-stroked-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class ResizeDemoDialog {}
