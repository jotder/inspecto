import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LA_APP_SHORTCUTS, ShortcutsHelpDialog, isEditingTarget } from './shortcuts-help.dialog';

describe('ShortcutsHelpDialog', () => {
    async function create(data?: unknown) {
        TestBed.configureTestingModule({
            imports: [ShortcutsHelpDialog],
            providers: [provideNoopAnimations(), ...(data ? [{ provide: MAT_DIALOG_DATA, useValue: data }] : [])],
        });
        const fixture = TestBed.createComponent(ShortcutsHelpDialog);
        fixture.detectChanges();
        return fixture;
    }

    it('lists the documented shortcuts (Ctrl+K, ?, Esc) with no a11y violations', async () => {
        const fixture = await create();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('command palette');
        const keys = Array.from(fixture.nativeElement.querySelectorAll('kbd')).map(
            (k) => (k as HTMLElement).textContent,
        );
        expect(keys).toContain('Ctrl');
        expect(keys).toContain('K');
        expect(keys).toContain('?');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the list a shell passes (la-app: no command palette, the Grow / Exclude / Undo keys)', async () => {
        const fixture = await create({ shortcuts: LA_APP_SHORTCUTS });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).not.toContain('command palette');
        expect(text).toContain('Grow the graph');
        expect(text).toContain('Undo the last Grow or Exclude');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('treats text fields as editing targets, where ? is typed text', () => {
        expect(isEditingTarget(document.createElement('input'))).toBe(true);
        expect(isEditingTarget(document.createElement('textarea'))).toBe(true);
        expect(isEditingTarget(document.createElement('button'))).toBe(false);
        expect(isEditingTarget(null)).toBe(false);
    });
});
