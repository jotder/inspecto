import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { ICellRendererParams, SuppressKeyboardEventParams } from 'ag-grid-community';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { followRowLinkOnEnter, RowLinkCell, rowLinkCommands } from './row-link-cell.component';
import { RowLinkKind } from './viz-types';

function render(kind: RowLinkKind, value: unknown) {
    TestBed.configureTestingModule({ imports: [RowLinkCell], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(RowLinkCell);
    fixture.componentInstance.agInit({ kind, value } as ICellRendererParams & { kind: RowLinkKind });
    fixture.detectChanges();
    return fixture;
}

function keyParams(key: string, target: HTMLElement, type = 'keydown'): SuppressKeyboardEventParams {
    const event = new KeyboardEvent(type, { key });
    Object.defineProperty(event, 'target', { value: target });
    return { event } as SuppressKeyboardEventParams;
}

describe('UIE-6 row link — target', () => {
    it('opens each kind on its own detail route', () => {
        expect(rowLinkCommands('case', 'CASE-7')).toEqual(['/cases', 'CASE-7']);
        expect(rowLinkCommands('incident', 'INC-3')).toEqual(['/incidents', 'INC-3']);
        expect(rowLinkCommands('reconciliation', 'ra_c02_offer_fee')).toEqual(['/reconciliation', 'ra_c02_offer_fee']);
        expect(rowLinkCommands('case', 42)).toEqual(['/cases', '42']);
    });

    it('a row with no id has no link', () => {
        for (const id of [null, undefined, '', '   ']) expect(rowLinkCommands('case', id)).toBeNull();
    });
});

describe('UIE-6 row link — cell', () => {
    it('renders the id as a real link to the object, named for a screen reader', async () => {
        const fixture = render('reconciliation', 'ra_c02_offer_fee');
        const a = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
        expect(a.getAttribute('href')).toBe('/reconciliation/ra_c02_offer_fee');
        expect(a.textContent?.trim()).toBe('ra_c02_offer_fee');
        expect(a.getAttribute('aria-label')).toBe('Open Reconciliation ra_c02_offer_fee');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('navigates in-app on click', async () => {
        const fixture = render('case', 'CASE-7');
        const router = TestBed.inject(Router);
        const nav = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
        (fixture.nativeElement.querySelector('a') as HTMLAnchorElement).click();
        expect(nav).toHaveBeenCalledTimes(1);
        expect(String(nav.mock.calls[0][0])).toBe('/cases/CASE-7');
    });

    it('a row with no id renders plain text, no link', () => {
        const fixture = render('incident', '');
        expect(fixture.nativeElement.querySelector('a')).toBeNull();
    });
});

describe('UIE-6 row link — keyboard', () => {
    it('Enter on a focused link cell follows the link', () => {
        const cell = document.createElement('div');
        const a = document.createElement('a');
        a.href = '/cases/CASE-7';
        cell.appendChild(a);
        const click = vi.spyOn(a, 'click').mockImplementation(() => undefined);
        expect(followRowLinkOnEnter(keyParams('Enter', cell))).toBe(true);
        expect(click).toHaveBeenCalledTimes(1);
    });

    it('leaves every other key, and a cell without a link, to the grid', () => {
        const cell = document.createElement('div');
        const a = document.createElement('a');
        a.href = '/cases/CASE-7';
        cell.appendChild(a);
        const click = vi.spyOn(a, 'click').mockImplementation(() => undefined);
        expect(followRowLinkOnEnter(keyParams('ArrowDown', cell))).toBe(false);
        expect(followRowLinkOnEnter(keyParams('Enter', cell, 'keyup'))).toBe(false);
        expect(followRowLinkOnEnter(keyParams('Enter', document.createElement('div')))).toBe(false);
        expect(click).not.toHaveBeenCalled();
    });
});
