import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LensService } from 'app/inspecto/api';
import { ExclusionCategoriesService, ExclusionListChip } from 'app/inspecto/api/exclusion-categories.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ExclusionCategoriesComponent } from './exclusion-categories.component';

const LISTS: ExclusionListChip[] = [
    {
        id: 'pols',
        title: 'Politicians',
        entityType: 'msisdn',
        ownerKind: 'admin',
        autoApply: true,
        visibility: 'restricted',
        size: 12,
    },
    {
        id: 'shops',
        title: 'Known shops',
        entityType: 'msisdn',
        ownerKind: 'analyst',
        autoApply: false,
        visibility: 'open',
        size: 3,
    },
];

function setup(opts: { canEdit?: boolean; setAttributes?: () => unknown; lists?: ExclusionListChip[] } = {}) {
    const api = {
        list: vi.fn(() => of({ lists: opts.lists ?? LISTS })),
        create: vi.fn(() => of({})),
        setAttributes: vi.fn(opts.setAttributes ?? (() => of({}))),
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ExclusionCategoriesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ExclusionCategoriesService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canManageExclusionCategories: () => opts.canEdit !== false } },
        ],
    });
    const fixture = TestBed.createComponent(ExclusionCategoriesComponent);
    fixture.componentRef.setInput('entityTypes', [{ id: 'msisdn', label: 'Subscriber' }]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, c: fixture.componentInstance, el, api, toastr };
}

function setup2(): HTMLElement {
    TestBed.resetTestingModule();
    const lists = [{ ...LISTS[0], problem: "its Entity Type 'msisdn' is no longer in force" }];
    return setup({ lists }).el;
}

describe('ExclusionCategoriesComponent', () => {
    it('lists only the category lists with their switches (no a11y violations)', async () => {
        const { el } = setup();
        const rows = Array.from(el.querySelectorAll('ul[aria-label="Category exclusion lists"] > li'));
        expect(rows.length).toBe(1);
        expect(rows[0].textContent).toContain('Politicians');
        expect(rows[0].textContent).toContain('12 members');
        expect(el.textContent).not.toContain('Known shops');
        const boxes = Array.from(rows[0].querySelectorAll<HTMLInputElement>('input[type="checkbox"]'));
        expect(boxes.map((b) => b.checked)).toEqual([true, true]);
        await expectNoA11yViolations(el);
    });

    it('warns the administrator when an auto-apply list would refuse every new Investigation', () => {
        const { el } = setup();
        expect(el.textContent).not.toContain('New Investigations are refused');
        const broken = setup2();
        expect(broken.textContent).toContain('New Investigations are refused');
        expect(broken.textContent).toContain('its Entity Type');
    });

    it('turns auto-apply off with one PUT and a recorded reason', () => {
        const { fixture, el, api, toastr } = setup();
        const box = el.querySelector<HTMLInputElement>('ul input[type="checkbox"]')!;
        box.click();
        fixture.detectChanges();
        expect(api.setAttributes).toHaveBeenCalledWith('pols', {
            autoApply: false,
            reason: 'changed in Settings > Link Analysis',
        });
        expect(toastr.success).toHaveBeenCalled();
        expect(api.list).toHaveBeenCalledTimes(2);
    });

    it('creates a category list from the form and refuses an empty name inline', () => {
        const { c, fixture, el, api } = setup();
        el.querySelector<HTMLButtonElement>('form button[type="submit"]')!.click();
        fixture.detectChanges();
        expect(api.create).not.toHaveBeenCalled();
        expect(el.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error')?.textContent).toContain(
            'Enter a name',
        );

        c.form.setValue({ title: ' Defence ', entityType: 'msisdn', autoApply: true, restricted: true });
        el.querySelector<HTMLButtonElement>('form button[type="submit"]')!.click();
        fixture.detectChanges();
        expect(api.create).toHaveBeenCalledWith({
            title: 'Defence',
            entityType: 'msisdn',
            autoApply: true,
            visibility: 'restricted',
            reason: 'changed in Settings > Link Analysis',
        });
    });

    it('is read-only without canManageExclusionCategories', () => {
        const { el } = setup({ canEdit: false });
        expect(el.querySelector('form')).toBeNull();
        expect(el.textContent).toContain('needs the canManageExclusionCategories capability');
        const boxes = Array.from(el.querySelectorAll<HTMLInputElement>('ul input[type="checkbox"]'));
        expect(boxes.every((b) => b.disabled)).toBe(true);
    });

    it('says why a refused change failed', () => {
        const err = new HttpErrorResponse({
            status: 403,
            error: { error: { message: "missing capability 'canManageExclusionCategories'" } },
        });
        const { el, fixture } = setup({ setAttributes: () => throwError(() => err) });
        el.querySelector<HTMLInputElement>('ul input[type="checkbox"]')!.click();
        fixture.detectChanges();
        expect(el.textContent).toContain('You are not allowed to change category lists');
    });
});
