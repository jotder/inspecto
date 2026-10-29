import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import type { ExclusionListSummary } from '@inspecto/link-analysis/api/inv.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penByLabel, penEdit, penError, pickerSet } from '@inspecto/core/testing/pen-field';
import { ExcludeDialogData, LinkAnalysisExcludeDialog } from './link-analysis-exclude.dialog';

const LISTS: ExclusionListSummary[] = [
    { id: 'cc', title: 'Call centres', entityType: 'msisdn', ownerKind: 'analyst', autoApply: false, visibility: 'open', size: 12 },
    { id: 'vip', title: 'Marketing VIPs', entityType: 'msisdn', ownerKind: 'admin', autoApply: true, visibility: 'restricted', size: 40 },
    { id: 'imei', title: 'Lab phones', entityType: 'imei', ownerKind: 'analyst', autoApply: false, visibility: 'open', size: 2 },
];
const DATA: ExcludeDialogData = {
    count: 2,
    labels: ['+4477001', '+4477002'],
    mode: 'investigation',
    terms: { node: 'number', nodes: 'numbers', link: 'call', links: 'calls' },
    lists: LISTS,
    entityType: 'msisdn',
    canManageLists: true,
    canManageCategories: false,
    hereWhyNot: '',
};

function create(over: Partial<ExcludeDialogData> = {}) {
    const close = vi.fn();
    TestBed.configureTestingModule({
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { ...DATA, ...over } },
            { provide: MatDialogRef, useValue: { close, disableClose: false, backdropClick: () => ({ subscribe: () => undefined }), keydownEvents: () => ({ subscribe: () => undefined }) } },
        ],
    });
    const f = TestBed.createComponent(LinkAnalysisExcludeDialog);
    f.detectChanges();
    return { f, el: f.nativeElement as HTMLElement, close, c: f.componentInstance };
}

const choose = (el: HTMLElement, id: string) =>
    (el.querySelector(`[data-testid="${id}"] input`) as HTMLInputElement).click();
const exclude = (el: HTMLElement) => (el.querySelector('[data-testid="la-exclude-go"]') as HTMLButtonElement).click();

describe('LinkAnalysisExcludeDialog (LA-GROW-EXCLUDE-1 §3.3)', () => {
    it('offers the two choices in plain words, says what is excluded, and is axe-clean', async () => {
        const { el } = create();
        expect(el.querySelector('h2')?.textContent).toContain('Exclude 2 numbers');
        expect(el.querySelector('[data-testid="la-exclude-what"]')?.textContent).toContain('+4477001, +4477002');
        expect(el.querySelector('[data-testid="la-exclude-here"]')?.textContent).toContain('From this Investigation only');
        expect(el.textContent).toContain('One recorded step (exclude), with your reason. Undo reverts it.');
        expect(el.querySelector('[data-testid="la-exclude-list"]')?.textContent).toContain('Add to a list…');
        await expectNoA11yViolations(el);
    });

    it('"From this Investigation only" closes with the reason', () => {
        const { f, el, close } = create();
        penEdit(f, penByLabel(el, 'Reason')!, 'Switchboard, not a person');
        exclude(el);
        expect(close).toHaveBeenCalledWith({ kind: 'here', reason: 'Switchboard, not a person' });
    });

    it('a blank reason is refused on screen; nothing closes', () => {
        const { f, el, close } = create();
        penEdit(f, penByLabel(el, 'Reason')!, '   ');
        exclude(el);
        f.detectChanges();
        expect(penError(penByLabel(el, 'Reason')!)).toContain('A reason is required');
        expect(close).not.toHaveBeenCalled();
    });

    it('"Add to a list…" offers the analyst lists of this Entity Type; a category list only says it is kept by an administrator', () => {
        const { f, el, close, c } = create();
        choose(el, 'la-exclude-list');
        f.detectChanges();
        expect(c.listOptions().map((o) => o.label)).toEqual(['Call centres', 'Create a new list…']);
        expect(el.querySelector('[data-testid="la-exclude-admin-only"]')?.textContent).toContain(
            '1 category list is kept by an administrator',
        );
        exclude(el); // no list picked yet: said, not sent
        f.detectChanges();
        expect(el.textContent).toContain('Choose a list, or create a new one.');
        expect(close).not.toHaveBeenCalled();
        pickerSet(f, '[data-testid="la-exclude-pick"]', 'cc');
        exclude(el);
        expect(close).toHaveBeenCalledWith({ kind: 'list', listId: 'cc', reason: 'Not relevant to this Investigation' });
    });

    it('with the category capability an admin list is offered too', () => {
        const { c } = create({ canManageCategories: true });
        expect(c.listOptions().map((o) => o.value)).toEqual(['cc', 'vip', '__new__']);
    });

    it('a new list needs a name', () => {
        const { f, el, close } = create();
        choose(el, 'la-exclude-list');
        f.detectChanges();
        pickerSet(f, '[data-testid="la-exclude-pick"]', '__new__');
        exclude(el);
        f.detectChanges();
        expect(penError(penByLabel(el, 'New list name')!)).toContain('Name the new list.');
        penEdit(f, penByLabel(el, 'New list name')!, 'Call centres 2');
        exclude(el);
        expect(close).toHaveBeenCalledWith({ kind: 'list', newTitle: 'Call centres 2', reason: 'Not relevant to this Investigation' });
    });

    it('says why "Add to a list…" is not possible: no capability, or no list route on this server', () => {
        const { el } = create({ canManageLists: false });
        expect(el.querySelector('[data-testid="la-exclude-list-hint"]')?.textContent).toContain(
            'Needs Incident management.',
        );
        expect((el.querySelector('[data-testid="la-exclude-list"] input') as HTMLInputElement).disabled).toBe(true);
    });

    it('says why when the lists cannot be read here', () => {
        const { el } = create({ lists: null });
        expect(el.querySelector('[data-testid="la-exclude-list-hint"]')?.textContent).toContain(
            'Exclusion lists cannot be read on this server.',
        );
    });

    it('without the write capability "this Investigation only" says why, and the dialog starts on the list choice', () => {
        const { el, c } = create({ hereWhyNot: 'This changes the Investigation log and needs the Incident management capability.' });
        expect(el.textContent).toContain('needs the Incident management capability');
        expect(c.kind()).toBe('list');
    });

    it('Basic: hide from this graph, not recorded', async () => {
        const { el, f, close } = create({ mode: 'basic' });
        expect(el.querySelector('[data-testid="la-exclude-here"]')?.textContent).toContain('Hide from this graph');
        expect(el.textContent).toContain('Only on this screen, not recorded.');
        expect(penByLabel(el, 'Reason')).toBeNull(); // a local hide records nothing: no reason asked
        choose(el, 'la-exclude-list');
        f.detectChanges();
        expect(penByLabel(el, 'Reason')).not.toBeNull(); // a list write carries one
        choose(el, 'la-exclude-here');
        f.detectChanges();
        exclude(el);
        f.detectChanges();
        expect(close).toHaveBeenCalledWith({ kind: 'here', reason: 'Not relevant to this analysis' });
        await expectNoA11yViolations(el);
    });
});
