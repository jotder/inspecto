import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatMenuModule, MatMenuTrigger } from '@angular/material/menu';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { describe, expect, it } from 'vitest';
import { FormMenuDirective } from './form-menu.directive';

@Component({
    standalone: true,
    imports: [MatMenuModule, FormMenuDirective],
    template: `
        <button [matMenuTriggerFor]="m" laFormMenu aria-label="Search nodes">open</button>
        <mat-menu #m="matMenu"><input aria-label="query" /></mat-menu>
        <button [matMenuTriggerFor]="m2" aria-label="Plain">plain</button>
        <mat-menu #m2="matMenu"><button mat-menu-item>one</button></mat-menu>
    `,
})
class Host {}

describe('FormMenuDirective', () => {
    it('re-roles a form menu panel to a named dialog, and leaves an item menu a menu', async () => {
        TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
        const f = TestBed.createComponent(Host);
        f.detectChanges();
        const [form, plain] = f.debugElement.queryAll(By.directive(MatMenuTrigger)).map((d) => d.injector.get(MatMenuTrigger));
        form.openMenu();
        f.detectChanges();
        await f.whenStable();
        const panel = document.getElementById(form.menu!.panelId)!;
        expect(panel.getAttribute('role')).toBe('dialog');
        expect(panel.getAttribute('aria-label')).toBe('Search nodes');
        form.closeMenu();
        plain.openMenu();
        f.detectChanges();
        await f.whenStable();
        expect(document.getElementById(plain.menu!.panelId)!.getAttribute('role')).toBe('menu');
    });
});
