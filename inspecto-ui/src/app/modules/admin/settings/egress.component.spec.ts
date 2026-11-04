import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';

import { EgressSettingsService, LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit, penError, penOpen } from 'app/inspecto/testing/pen-field';
import { EgressSettingsComponent } from './egress.component';

/** The 422 `PUT /settings/egress` answers for a range overlapping a never-liftable one (EgressPolicy.Allowlist.of). */
const REFUSAL =
    "'169.254.0.0/16' overlaps 169.254.0.0/16 — loopback, link-local (the metadata service), unspecified, " +
    'multicast and broadcast ranges can never be allowlisted; name only the private range the target lives in';

function setup(opts: { canAdminister?: boolean; save?: EgressSettingsService['save'] } = {}) {
    const api = {
        get: vi.fn(() => of({ allow: ['tickets.internal', '10.20.0.0/16'] })),
        save: vi.fn(opts.save ?? ((allow: string[]) => of({ allow }))),
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [EgressSettingsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: EgressSettingsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canAdminister: () => opts.canAdminister !== false } },
        ],
    });
    const fixture = TestBed.createComponent(EgressSettingsComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const row = () => penByLabel(el, 'Host name or CIDR')!;
    const button = (label: string) =>
        Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === label);
    /** Type into the row and press Add with the mouse: the click's mousedown blurs the row, which commits it. */
    const type = (value: string) => {
        const box = penOpen(fixture, row());
        box.value = value;
        box.dispatchEvent(new Event('input'));
        box.dispatchEvent(new Event('blur'));
        button('Add')!.click();
        fixture.detectChanges();
    };
    return { fixture, c: fixture.componentInstance, el, api, toastr, row, button, type };
}

describe('EgressSettingsComponent', () => {
    it('lists the served entries and explains what can never be allowlisted (no a11y violations)', async () => {
        const { el } = setup();
        const items = Array.from(el.querySelectorAll('li')).map((li) => li.querySelector('span')?.textContent);
        expect(items).toEqual(['tickets.internal', '10.20.0.0/16']);
        expect(el.textContent).toContain('169.254.169.254');
        expect(el.textContent).toContain('can never be allowlisted');
        expect(el.querySelectorAll('h1').length).toBe(1);
        await expectNoA11yViolations(el);
    });

    it('adds (normalised) and removes entries as a draft, then saves the whole list', () => {
        const { c, fixture, el, api, toastr, button, type } = setup();
        expect(button('Save allowlist')!.disabled).toBe(true);

        type('  PCRF.Core.Internal ');
        el.querySelector<HTMLButtonElement>('button[aria-label="Remove tickets.internal"]')!.click();
        fixture.detectChanges();
        expect(c.hasUnsavedChanges()).toBe(true);
        expect(el.textContent).toContain('Unsaved changes');

        button('Save allowlist')!.click();
        fixture.detectChanges();
        expect(api.save).toHaveBeenCalledWith(['10.20.0.0/16', 'pcrf.core.internal']);
        expect(toastr.success).toHaveBeenCalled();
        expect(c.hasUnsavedChanges()).toBe(false);
    });

    it('adds the entry when Enter commits the row', () => {
        const { c, fixture, row } = setup();
        penEdit(fixture, row(), 'Billing.Internal');
        expect(c.entries()).toEqual(['tickets.internal', '10.20.0.0/16', 'billing.internal']);
        expect(penError(row())).toBe('');
    });

    it('refuses a duplicate or a spaced entry inline, without adding it', () => {
        const { c, row, type } = setup();
        expect(penError(row())).toBe(''); // nothing red on a fresh form
        type('TICKETS.internal');
        expect(penError(row())).toContain('already in the list');
        type('a b');
        expect(penError(row())).toContain('cannot contain spaces');
        expect(c.entries()).toHaveLength(2);
    });

    it("shows the server's refusal inline and keeps the draft when an entry can never be lifted", () => {
        const { c, fixture, el, toastr, button, type } = setup({
            save: () =>
                throwError(
                    () =>
                        new HttpErrorResponse({
                            status: 422,
                            error: { error: { code: 'CONFIG_VALIDATION_FAILED', message: REFUSAL } },
                        }),
                ),
        });
        type('169.254.0.0/16');
        button('Save allowlist')!.click();
        fixture.detectChanges();

        const alert = Array.from(el.querySelectorAll('inspecto-alert')).find((a) =>
            a.textContent?.includes('was not saved'),
        );
        expect(alert?.textContent).toContain(REFUSAL);
        expect(toastr.error).not.toHaveBeenCalled();
        expect(c.hasUnsavedChanges()).toBe(true);
        expect(c.entries()).toContain('169.254.0.0/16');
    });

    it('hides every editing control without canAdminister, keeping the list readable', async () => {
        const { el, button } = setup({ canAdminister: false });
        expect(el.querySelectorAll('li')).toHaveLength(2);
        expect(penByLabel(el, 'Host name or CIDR')).toBeNull();
        expect(el.querySelector('button[aria-label^="Remove"]')).toBeNull();
        expect(button('Save allowlist')).toBeUndefined();
        expect(el.textContent).toContain('Administer capability required');
        await expectNoA11yViolations(el);
    });
});
