import { AfterViewInit, Directive, ElementRef, inject, input, signal } from '@angular/core';
import { MatDialogRef } from '@angular/material/dialog';

/**
 * **Resizable / maximizable dialog chrome** — the shared extraction of the grammar dialog's
 * hand-rolled fullscreen toggle, generalised so every dialog resizes the same way. Put the attribute
 * on the dialog's title (any element inside the dialog works):
 *
 * ```html
 * <h2 mat-dialog-title inspectoDialogResize #chrome="inspectoDialogResize">…
 *     <!-- big dialogs add a maximize button -->
 *     <button mat-icon-button type="button" (click)="chrome.toggleMaximize()"
 *             [attr.aria-label]="chrome.maximized() ? 'Exit full screen' : 'Full screen'">…</button>
 * </h2>
 * ```
 *
 * The directive appends a drag grip to the dialog's bottom-right corner (pointer drag, or arrow keys
 * when focused) and tags the overlay pane `inspecto-dialog-resizable`; once the user sizes it (or
 * maximizes), `inspecto-dialog-sized` drops Material's 65vh content clamp so the content flexes into
 * the chosen size instead of scrolling. Maximize reuses the existing `.dialog-fullscreen` panel class
 * (styles.scss). Outside a dialog (tests, the /design gallery) it is inert.
 */
@Directive({
    selector: '[inspectoDialogResize]',
    standalone: true,
    exportAs: 'inspectoDialogResize',
})
export class InspectoDialogResizeDirective implements AfterViewInit {
    /** Smallest size the grip can drag the dialog down to. */
    readonly minWidth = input(360);
    readonly minHeight = input(240);

    /** Whether the dialog currently fills the viewport (drives the host's maximize button icon). */
    readonly maximized = signal(false);

    private readonly ref = inject<MatDialogRef<unknown> | null>(MatDialogRef, { optional: true });
    private readonly el = inject<ElementRef<HTMLElement>>(ElementRef);

    /** The CDK overlay pane the dialog renders in — the element whose size the grip drives. */
    private pane: HTMLElement | null = null;
    /** Set once the user drags/keys a size, so restoring from maximize keeps their chosen size. */
    private sized = false;

    ngAfterViewInit(): void {
        this.pane = this.el.nativeElement.closest<HTMLElement>('.cdk-overlay-pane');
        if (!this.pane || !this.ref) return; // not in a dialog — stay inert
        this.pane.classList.add('inspecto-dialog-resizable');

        const grip = document.createElement('button');
        grip.type = 'button';
        grip.className = 'inspecto-dialog-resize-grip';
        grip.setAttribute('aria-label', 'Resize dialog — drag, or use the arrow keys');
        grip.addEventListener('pointerdown', (e) => this.startResize(e));
        grip.addEventListener('keydown', (e) => this.onKeydown(e));
        this.pane.appendChild(grip);
    }

    /** Fill the viewport (the `.dialog-fullscreen` panel class), or restore the previous size. */
    toggleMaximize(): void {
        if (!this.ref || !this.pane) return;
        const on = !this.maximized();
        this.maximized.set(on);
        if (on) {
            this.ref.addPanelClass('dialog-fullscreen');
            this.pane.classList.add('inspecto-dialog-sized');
        } else {
            this.ref.removePanelClass('dialog-fullscreen');
            if (!this.sized) this.pane.classList.remove('inspecto-dialog-sized');
        }
    }

    private startResize(e: PointerEvent): void {
        if (!this.pane) return;
        e.preventDefault();
        const start = this.pane.getBoundingClientRect();
        const move = (ev: PointerEvent): void =>
            this.resizeTo(start.width + (ev.clientX - e.clientX), start.height + (ev.clientY - e.clientY));
        const up = (): void => {
            window.removeEventListener('pointermove', move);
            window.removeEventListener('pointerup', up);
        };
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', up);
    }

    private onKeydown(e: KeyboardEvent): void {
        if (!this.pane) return;
        const r = this.pane.getBoundingClientRect();
        const step = 32;
        if (e.key === 'ArrowLeft') this.resizeTo(r.width - step, r.height);
        else if (e.key === 'ArrowRight') this.resizeTo(r.width + step, r.height);
        else if (e.key === 'ArrowUp') this.resizeTo(r.width, r.height - step);
        else if (e.key === 'ArrowDown') this.resizeTo(r.width, r.height + step);
        else return;
        e.preventDefault();
    }

    private resizeTo(width: number, height: number): void {
        if (!this.ref || !this.pane) return;
        if (this.maximized()) this.toggleMaximize(); // dragging out of maximize resumes manual sizing
        const w = Math.round(Math.min(Math.max(width, this.minWidth()), window.innerWidth - 16));
        const h = Math.round(Math.min(Math.max(height, this.minHeight()), window.innerHeight - 16));
        // The open-time maxWidth/maxHeight would silently cap the drag — the user's size wins.
        this.pane.style.maxWidth = 'calc(100vw - 16px)';
        this.pane.style.maxHeight = 'calc(100vh - 16px)';
        this.ref.updateSize(`${w}px`, `${h}px`);
        this.pane.classList.add('inspecto-dialog-sized');
        this.sized = true;
    }
}
