import { DestroyRef, Directive, ElementRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatMenuTrigger } from '@angular/material/menu';

/**
 * For a `mat-menu` that holds a FORM (search box, timeline picker, save-view form, display options) rather than
 * menu items. Material hard-codes `role="menu"` on the panel, and a menu may own only menu items (axe
 * `aria-required-children`, critical), so once the panel opens it is re-roled to `dialog` and named after its trigger.
 * Put it on the trigger beside `[matMenuTriggerFor]`. Menus of real items need none of this.
 */
@Directive({ selector: '[matMenuTriggerFor][laFormMenu]', standalone: true })
export class FormMenuDirective {
    constructor() {
        const trigger = inject(MatMenuTrigger);
        const host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
        trigger.menuOpened.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => {
            const panel = document.getElementById(trigger.menu?.panelId ?? '');
            if (!panel) return;
            panel.setAttribute('role', 'dialog');
            const name = host.getAttribute('aria-label');
            if (name) panel.setAttribute('aria-label', name);
        });
    }
}
