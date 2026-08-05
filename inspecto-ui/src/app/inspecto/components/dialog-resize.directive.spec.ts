import { Component, ViewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoDialogResizeDirective } from './dialog-resize.directive';

@Component({
    standalone: true,
    imports: [MatDialogModule, InspectoDialogResizeDirective],
    template: `
        <h2 mat-dialog-title inspectoDialogResize>Sizable</h2>
        <mat-dialog-content>content</mat-dialog-content>
    `,
})
class SizableDialog {
    @ViewChild(InspectoDialogResizeDirective) chrome!: InspectoDialogResizeDirective;
}

function openDialog(): { ref: MatDialogRef<SizableDialog>; pane: HTMLElement; grip: HTMLButtonElement } {
    TestBed.configureTestingModule({ providers: [provideNoopAnimations()] });
    // ariaLabel: the fixture dialog has no projected title text at open time, so name it explicitly
    // (real adopters carry a mat-dialog-title; axe's aria-dialog-name rule is about THEM, not this rig).
    const ref = TestBed.inject(MatDialog).open(SizableDialog, { ariaLabel: 'Sizable dialog' });
    TestBed.tick();
    const pane = document.querySelector('.cdk-overlay-pane') as HTMLElement;
    const grip = pane.querySelector('.inspecto-dialog-resize-grip') as HTMLButtonElement;
    return { ref, pane, grip };
}

describe('InspectoDialogResizeDirective', () => {
    afterEach(() => document.querySelector('.cdk-overlay-container')?.remove());

    it('tags the overlay pane and appends a labelled resize grip', () => {
        const { pane, grip } = openDialog();
        expect(pane.classList.contains('inspecto-dialog-resizable')).toBe(true);
        expect(grip).toBeTruthy();
        expect(grip.getAttribute('aria-label')).toContain('Resize dialog');
    });

    it('arrow keys on the grip resize the dialog and drop the content clamp', () => {
        const { ref, pane, grip } = openDialog();
        const updateSize = vi.spyOn(ref, 'updateSize');
        grip.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
        expect(updateSize).toHaveBeenCalledTimes(1);
        // jsdom rects are 0×0, so both dimensions clamp to the minimums.
        expect(updateSize).toHaveBeenCalledWith('360px', '240px');
        expect(pane.classList.contains('inspecto-dialog-sized')).toBe(true);
    });

    it('toggleMaximize round-trips the fullscreen panel class', () => {
        const { ref, pane } = openDialog();
        const chrome = ref.componentInstance.chrome;
        chrome.toggleMaximize();
        expect(chrome.maximized()).toBe(true);
        expect(pane.classList.contains('dialog-fullscreen')).toBe(true);
        expect(pane.classList.contains('inspecto-dialog-sized')).toBe(true);

        chrome.toggleMaximize();
        expect(chrome.maximized()).toBe(false);
        expect(pane.classList.contains('dialog-fullscreen')).toBe(false);
        // Never manually sized → the content clamp comes back with the normal size.
        expect(pane.classList.contains('inspecto-dialog-sized')).toBe(false);
    });

    it('stays inert outside a dialog', () => {
        TestBed.configureTestingModule({ providers: [provideNoopAnimations()] });
        // Rendering the host template directly (no overlay) must not throw.
        @Component({
            standalone: true,
            imports: [InspectoDialogResizeDirective],
            template: `<div inspectoDialogResize>plain</div>`,
        })
        class Plain {}
        const fixture = TestBed.createComponent(Plain);
        expect(() => fixture.detectChanges()).not.toThrow();
        expect(document.querySelector('.inspecto-dialog-resize-grip')).toBeNull();
    });

    it('has no a11y violations', async () => {
        openDialog();
        const container = document.querySelector('.mat-mdc-dialog-container') as HTMLElement;
        await expectNoA11yViolations(container);
    });
});
