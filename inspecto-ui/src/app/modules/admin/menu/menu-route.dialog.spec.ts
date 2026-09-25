import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MenuRouteDialog, MenuRouteDialogData } from './menu-route.dialog';

function make(data: MenuRouteDialogData) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [MenuRouteDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const f = TestBed.createComponent(MenuRouteDialog);
    f.detectChanges();
    return { f, c: f.componentInstance, ref };
}

describe('MenuRouteDialog (UIE-7)', () => {
    it('refuses a route that leaves the app, shows why, and does not close', () => {
        const { f, c, ref } = make({ heading: 'Add screen link', takenTitles: [] });
        c.form.controls.title.setValue('Evil');
        c.form.controls.route.setValue('https://evil.example');
        expect(f.nativeElement.querySelector('mat-error')).toBeNull();
        c.save();
        f.detectChanges();
        expect(ref.close).not.toHaveBeenCalled();
        const err = f.nativeElement.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error');
        expect(err?.textContent).toContain('Start the route with /');
    });

    it('blocks a duplicate sibling name', () => {
        const { c, ref } = make({ heading: 'Add screen link', takenTitles: ['Cases'] });
        c.form.controls.title.setValue('cases');
        c.form.controls.route.setValue('/cases');
        c.save();
        expect(c.form.controls.title.hasError('duplicate')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('returns the trimmed name and route, prefilled when editing', async () => {
        const { f, c, ref } = make({ heading: 'Edit screen link', title: 'Cases', route: '/cases', takenTitles: [] });
        await expectNoA11yViolations(f.nativeElement);
        c.form.controls.route.setValue('  /cases?status=open  ');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ title: 'Cases', route: '/cases?status=open' });
    });

    it('narrows the screen suggestions by what is typed', () => {
        const { c } = make({ heading: 'Add screen link', takenTitles: [] });
        c.form.controls.route.setValue('inc');
        expect(c.suggestions().map((s) => s.route)).toEqual(['/incidents']);
    });
});
