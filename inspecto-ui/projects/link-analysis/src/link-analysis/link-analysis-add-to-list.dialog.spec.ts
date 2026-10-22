import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { EntityListSummary, InvService } from '@inspecto/link-analysis/api/inv.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penByLabel, penEdit, penError, pickerSet } from '@inspecto/core/testing/pen-field';
import { AddToListData, LinkAnalysisAddToListDialog } from './link-analysis-add-to-list.dialog';
import { ENTITY_LIST_PURPOSES, purposeWords } from './link-analysis-entity-lists.dialogs';

const list = (id: string, title: string, entityType: string, extra: Partial<EntityListSummary> = {}): EntityListSummary => ({
    id,
    title,
    purpose: 'watch',
    entityType,
    size: 3,
    retired: false,
    createdAt: '2026-10-01T00:00:00Z',
    createdBy: null,
    lastSeq: 1,
    ...extra,
});
const DATA: AddToListData = {
    labels: ['MERCHANT-BIG'],
    values: ['MERCHANT-BIG'],
    entityType: 'account',
    lists: [
        list('suspects', 'Suspects', 'account'),
        list('old', 'Old list', 'account', { retired: true }),
        list('phones', 'Phones', 'msisdn'),
    ],
    terms: { node: 'account', nodes: 'accounts', link: 'transfer', links: 'transfers' },
};

function create(over: Partial<AddToListData> = {}, inv: Partial<InvService> = {}) {
    const close = vi.fn();
    const changeEntityListMembers = vi.fn(() => of({ ...list('x', 'x', 'account'), changed: 1, size: 4, members: [], atSeq: 2, headHash: null }));
    const createEntityList = vi.fn((req: { title: string; purpose: string; entityType: string }) =>
        of({ ...list('busy', req.title, req.entityType), purpose: req.purpose, members: [], atSeq: 1, headHash: null }),
    );
    TestBed.configureTestingModule({
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { ...DATA, ...over } },
            { provide: InvService, useValue: { changeEntityListMembers, createEntityList, ...inv } },
            {
                provide: MatDialogRef,
                useValue: {
                    close,
                    disableClose: false,
                    backdropClick: () => ({ subscribe: () => undefined }),
                    keydownEvents: () => ({ subscribe: () => undefined }),
                },
            },
        ],
    });
    const f = TestBed.createComponent(LinkAnalysisAddToListDialog);
    f.detectChanges();
    const go = async () => {
        (f.nativeElement.querySelector('[data-testid="la-add-list-go"]') as HTMLButtonElement).click();
        for (let i = 0; i < 3; i++) await new Promise((r) => setTimeout(r));
        f.detectChanges();
    };
    return { f, el: f.nativeElement as HTMLElement, close, c: f.componentInstance, go, changeEntityListMembers, createEntityList };
}

describe('LinkAnalysisAddToListDialog - Add to a list (LA-ONBOARD-1 phase 7, UC-10)', () => {
    it('offers the lists of the same Entity Type (never a retired one) and a new list (no violations)', async () => {
        const { el, c } = create();
        expect(el.querySelector('h2')?.textContent).toContain('Add 1 account to a list');
        expect(c.listOptions().map((o) => o.value)).toEqual(['suspects', '__new__']);
        expect(c.listOptions()[0].hint).toBe('Watch these · 3 members');
        await expectNoA11yViolations(el);
    });

    it('says a purpose in the same plain words as the Entity Lists page (LA-ONBOARD-1 phase 8)', () => {
        const { c } = create();
        const offered = Object.fromEntries(c.purposeOptions.map((o) => [o.value, o.label]));
        for (const p of ENTITY_LIST_PURPOSES) expect(offered[p.value]).toBe(purposeWords(p.value));
        expect(offered['exclusion']).toBe('Never follow these (exclusion)');
        expect(offered['watch']).toBe('Watch these');
    });

    it('adds to a chosen list with the reason and closes with what it wrote', async () => {
        const { f, close, go, changeEntityListMembers, createEntityList } = create();
        pickerSet(f, '[data-testid="la-add-list-pick"]', 'suspects');
        await go();
        expect(createEntityList).not.toHaveBeenCalled();
        expect(changeEntityListMembers).toHaveBeenCalledWith('suspects', {
            add: ['MERCHANT-BIG'],
            reason: 'Found with Find something interesting',
        });
        expect(close).toHaveBeenCalledWith({
            listId: 'suspects',
            title: 'Suspects',
            purpose: 'watch',
            message: '1 added to “Suspects” - it now has 4.',
        });
    });

    it('creates a new list with the purpose the busiest hint asked for (exclusion), then adds', async () => {
        const { f, el, close, go, createEntityList } = create({ lists: [], purpose: 'exclusion' });
        // no list of this type yet: the new list is the one choice, already picked
        penEdit(f, penByLabel(el, 'New list name')!, 'Busy merchants');
        await go();
        expect(createEntityList).toHaveBeenCalledWith({
            title: 'Busy merchants',
            purpose: 'exclusion',
            entityType: 'account',
            reason: 'Found with Find something interesting',
        });
        expect(close).toHaveBeenCalledWith(expect.objectContaining({ listId: 'busy', purpose: 'exclusion' }));
    });

    it('nothing is written without a list, a name for a new one, or a reason; each says why on screen', async () => {
        const { f, el, close, go, changeEntityListMembers } = create();
        await go();
        expect(el.textContent).toContain('Choose a list, or create a new one.');
        pickerSet(f, '[data-testid="la-add-list-pick"]', '__new__');
        penEdit(f, penByLabel(el, 'Reason')!, '  ');
        await go();
        expect(penError(penByLabel(el, 'New list name')!)).toBe('Name the new list.');
        expect(penError(penByLabel(el, 'Reason')!)).toContain('A reason is required');
        expect(changeEntityListMembers).not.toHaveBeenCalled();
        expect(close).not.toHaveBeenCalled();
    });

    it('a refusal stays in the dialog', async () => {
        const { f, el, close, go } = create({}, {
            changeEntityListMembers: () => throwError(() => new HttpErrorResponse({ status: 403 })),
        } as Partial<InvService>);
        pickerSet(f, '[data-testid="la-add-list-pick"]', 'suspects');
        await go();
        expect(close).not.toHaveBeenCalled();
        expect(el.textContent).toContain('Not added');
    });

    it('entities with no Entity Type can only join an existing list', () => {
        const { c } = create({ entityType: null });
        expect(c.listOptions().map((o) => o.value)).toEqual(['suspects', 'phones']);
    });

    it('no list can take them and no Entity Type: says why and where to make one, never an empty choice alone', async () => {
        const { el } = create({ entityType: null, lists: [] });
        expect(el.querySelector('[data-testid="la-add-list-none"]')?.textContent).toContain('Entity Lists page');
        await expectNoA11yViolations(el);
    });
});
