import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';

import { ApproverRosterService, LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit, penError, penOpen } from 'app/inspecto/testing/pen-field';
import { ApproverRosterSettingsComponent } from './approver-roster.component';

function setup(opts: { canAdminister?: boolean; applies?: boolean; save?: ApproverRosterService['save'] } = {}) {
    const api = {
        get: vi.fn(() => of({ users: ['ana'], groups: ['ra-approvers'], applies: opts.applies !== false })),
        save: vi.fn(opts.save ?? ((users: string[], groups: string[]) => of({ users, groups, applies: true }))),
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ApproverRosterSettingsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ApproverRosterService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canAdminister: () => opts.canAdminister !== false } },
        ],
    });
    const fixture = TestBed.createComponent(ApproverRosterSettingsComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) =>
        Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === label);
    const rows = () => [penByLabel(el, 'User id')!, penByLabel(el, 'Group name')!];
    /** Type into a row and press its Add button: the click's mousedown blurs the row, which commits it. */
    const type = (index: number, value: string, add: string) => {
        const box = penOpen(fixture, rows()[index]);
        box.value = value;
        box.dispatchEvent(new Event('input'));
        box.dispatchEvent(new Event('blur'));
        button(add)!.click();
        fixture.detectChanges();
    };
    return { fixture, c: fixture.componentInstance, el, api, toastr, button, type, rows };
}

describe('ApproverRosterSettingsComponent', () => {
    it('lists users and groups and says an empty roster means nobody (no a11y violations)', async () => {
        const { el } = setup();
        const items = Array.from(el.querySelectorAll('li > span')).map((s) => s.textContent);
        expect(items).toEqual(['ana', 'ra-approvers']);
        expect(el.textContent).toContain('An empty roster means');
        expect(el.querySelectorAll('h1').length).toBe(1);
        await expectNoA11yViolations(el);
    });

    it('edits both lists as a draft and saves them in one PUT', () => {
        const { c, fixture, el, api, toastr, button, type } = setup();
        expect(button('Save roster')!.disabled).toBe(true);
        type(0, '  bo ', 'Add user');
        el.querySelector<HTMLButtonElement>('button[aria-label="Remove ra-approvers"]')!.click();
        fixture.detectChanges();
        expect(c.hasUnsavedChanges()).toBe(true);
        button('Save roster')!.click();
        fixture.detectChanges();
        expect(api.save).toHaveBeenCalledWith(['ana', 'bo'], []);
        expect(toastr.success).toHaveBeenCalled();
        expect(c.hasUnsavedChanges()).toBe(false);
    });

    it('adds a group when Enter commits its row', () => {
        const { c, fixture, rows } = setup();
        penEdit(fixture, rows()[1], 'cab');
        expect(c.draft().groups).toEqual(['ra-approvers', 'cab']);
        expect(c.hasUnsavedChanges()).toBe(true);
    });

    it('refuses a duplicate inline and shows a 422 verbatim', () => {
        const err = new HttpErrorResponse({ status: 422, error: { error: { message: "'users': an entry is blank" } } });
        const { el, fixture, button, type, rows } = setup({ save: () => throwError(() => err) });
        expect(penError(rows()[0])).toBe(''); // nothing red on a fresh form
        type(0, 'ana', 'Add user');
        expect(penError(rows()[0])).toContain('already in the list');
        type(1, 'cab', 'Add group');
        button('Save roster')!.click();
        fixture.detectChanges();
        expect(el.textContent).toContain('The roster was not saved');
    });

    it('is read-only without canAdminister and explains when the roster does not apply', () => {
        const { el, button } = setup({ canAdminister: false, applies: false });
        expect(button('Save roster')).toBeUndefined();
        expect(el.querySelector('input')).toBeNull();
        expect(el.querySelector('inspecto-pen-field')).toBeNull();
        expect(el.textContent).toContain('approvals follow roles alone');
    });
});
