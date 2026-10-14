import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ScreeningCheckResponse, ScreeningService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CheckNameDialog, parseSubjects } from './check-name.dialog';

const LISTS = {
    lists: [
        { id: 'sanctions', title: 'Sanctions', purpose: 'block', retired: false },
        { id: 'old', title: 'Old list', purpose: 'watch', retired: true },
    ],
};
const RESPONSE: ScreeningCheckResponse = {
    threshold: 0.85,
    results: [
        {
            key: '1',
            name: 'Vladimir Putin',
            identifier: null,
            matches: [
                { listId: 'sanctions', purpose: 'block', entry: 'vladimir putin', method: 'name', score: 0.9862 },
            ],
        },
        { key: '2', name: 'Jane Doe', identifier: null, matches: [] },
    ],
};

async function create(overrides: Partial<Record<keyof ScreeningService, unknown>> = {}) {
    const api = {
        entityLists: vi.fn(() => of(LISTS)),
        check: vi.fn(() => of(RESPONSE)),
        ...overrides,
    } as unknown as ScreeningService;
    TestBed.configureTestingModule({
        imports: [CheckNameDialog],
        providers: [
            provideNoopAnimations(),
            { provide: ScreeningService, useValue: api },
            { provide: MatDialogRef, useValue: { close: vi.fn() } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(CheckNameDialog);
    fixture.detectChanges();
    return { fixture, api, c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

describe('CheckNameDialog', () => {
    it('parses one name per line', () => {
        expect(parseSubjects(' A \n\r\nB\n  \nC')).toEqual(['A', 'B', 'C']);
    });

    it('states persistently that nothing is raised and offers only active lists', async () => {
        const { el, fixture } = await create();
        expect(el.textContent).toContain('this check does not create Hits');
        expect(el.textContent).toContain('Sanctions');
        expect(el.textContent).not.toContain('Old list');
        await expectNoA11yViolations(el);
        fixture.destroy();
    });

    it('refuses an empty submit inline and sends nothing', async () => {
        const { c, api, fixture, el } = await create();
        c.run();
        fixture.detectChanges();
        expect(api.check).not.toHaveBeenCalled();
        const alerts = Array.from(el.querySelectorAll('[role="alert"]')).map((a) => a.textContent);
        expect(alerts.join(' ')).toContain('Enter at least one name.');
        expect(alerts.join(' ')).toContain('Pick at least one Entity List.');
        await expectNoA11yViolations(el);
    });

    it('sends names, lists and optional limits, then shows scored matches and the no-hit note', async () => {
        const { c, api, fixture, el } = await create();
        c.form.patchValue({ names: 'Vladimir Putin\nJane Doe', threshold: 0.9, maxMatches: 3 });
        c.toggle('sanctions', true);
        c.run();
        fixture.detectChanges();
        expect(api.check).toHaveBeenCalledWith({
            subjects: [
                { key: '1', name: 'Vladimir Putin' },
                { key: '2', name: 'Jane Doe' },
            ],
            lists: ['sanctions'],
            threshold: 0.9,
            maxMatches: 3,
        });
        const cells = Array.from(el.querySelectorAll('tbody td')).map((t) => t.textContent?.trim());
        expect(cells).toEqual([
            'Vladimir Putin',
            'sanctions (block)',
            'vladimir putin',
            'name',
            '99 %',
            'Jane Doe',
            'Checked: no match at or above 0.85',
        ]);
        expect(el.textContent).toContain('this check does not create Hits');
        await expectNoA11yViolations(el);
    });

    it('omits blank threshold and maxMatches so the server defaults apply', async () => {
        const { c, api } = await create();
        c.form.patchValue({ names: 'X' });
        c.toggle('sanctions', true);
        c.run();
        expect(api.check).toHaveBeenCalledWith({ subjects: [{ key: '1', name: 'X' }], lists: ['sanctions'] });
    });

    it('rejects an out-of-range threshold without calling the server', async () => {
        const { c, api, fixture, el } = await create();
        c.form.patchValue({ names: 'X', threshold: 0.2 });
        c.toggle('sanctions', true);
        c.run();
        fixture.detectChanges();
        expect(api.check).not.toHaveBeenCalled();
        expect(el.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error')?.textContent).toContain(
            'A number from 0.5 to 1.',
        );
    });

    it('shows a Checked row for a clean name, beside the names that matched', async () => {
        const { c, fixture, el } = await create({
            check: vi.fn(() =>
                of({ threshold: 0.85, results: [{ key: '1', name: 'X', identifier: null, matches: [] }] }),
            ),
        });
        c.form.patchValue({ names: 'X' });
        c.toggle('sanctions', true);
        c.run();
        fixture.detectChanges();
        expect(el.querySelector('table')).not.toBeNull();
        expect(el.textContent).toContain('Checked: no match at or above 0.85');
        await expectNoA11yViolations(el);
    });

    it('shows the server error text verbatim', async () => {
        const err = new HttpErrorResponse({
            status: 422,
            error: { error: { message: "'lists' names an unknown Entity List 'nope'" } },
        });
        const { c, fixture, el } = await create({ check: vi.fn(() => throwError(() => err)) });
        c.form.patchValue({ names: 'X' });
        c.toggle('sanctions', true);
        c.run();
        fixture.detectChanges();
        expect(el.textContent).toContain("'lists' names an unknown Entity List 'nope'");
        await expectNoA11yViolations(el);
    });

    it('explains a failed Entity List load', async () => {
        const err = new HttpErrorResponse({
            status: 503,
            error: { error: { message: 'Entity Lists are unavailable' } },
        });
        const { el } = await create({ entityLists: vi.fn(() => throwError(() => err)) });
        expect(el.textContent).toContain('Entity Lists are unavailable');
    });
});
