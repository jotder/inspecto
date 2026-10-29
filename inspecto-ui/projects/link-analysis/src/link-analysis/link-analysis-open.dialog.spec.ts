import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService, TagsService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { LaOpenDialogData, LinkAnalysisOpenDialog, copyId, withinDays } from './link-analysis-open.dialog';

const view = (id: string, title: string, datasetId: string) => ({
    type: 'link-analysis-view',
    name: id,
    ref: id,
    modified: '2026-10-20T10:00:00Z',
    content: { title, description: `about ${title}`, sourceId: 'entity-projection', query: { projection: { datasetId } } },
});

function create(opts: { tab?: 'views' | 'investigations'; confirm?: boolean } = {}) {
    const components = {
        list: vi.fn(() => of([view('calls-hub', 'Calls hub', 'calls'), view('transfers', 'Transfers', 'transfers')])),
        create: vi.fn(() => of({})),
        update: vi.fn(() => of({})),
        remove: vi.fn(() => of({})),
    };
    const inv = {
        listInvestigations: vi.fn(() =>
            of({
                items: [
                    { id: 'inv-1', title: 'Mule ring', dataset: 'transfers', owner: 'ana', ownerName: 'Ana Analyst', createdAt: '2026-10-25T09:00:00Z', headStep: 4, access: 'owner', readOnly: false },
                    { id: 'inv-2', title: null, dataset: 'calls', owner: 'bo', createdAt: '2026-10-01T09:00:00Z', headStep: 2, caseRef: 'case-9', access: 'case-member', readOnly: true },
                ],
                total: 2,
                limit: 100,
                offset: 0,
                truncated: false,
            }),
        ),
        investigationLog: vi.fn((id: string) => of({ header: { id, pack: id === 'inv-1' ? 'mobile-money' : null }, entries: [], total: 0, truncated: false })),
    };
    const store = { refs: signal([{ id: 'inv-old', title: 'Remembered' }]), deleteInvestigation: vi.fn(async () => true), error: signal('') };
    const close = vi.fn();
    const data: LaOpenDialogData = {
        tab: opts.tab,
        opsEnabled: true,
        shareable: () => false,
        history: vi.fn(),
        comments: vi.fn(),
        tags: vi.fn(),
        offer: vi.fn(),
        canDeleteInvestigations: true,
        store: store as unknown as InvestigationSessionStore,
    };
    TestBed.configureTestingModule({
        imports: [LinkAnalysisOpenDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: { close } },
            { provide: ComponentsService, useValue: components },
            { provide: TagsService, useValue: { targets: vi.fn(() => of([{ tag: 'aml', targetKind: 'link-analysis-view', targetId: 'transfers', createdAt: 0 }])) } },
            { provide: InvService, useValue: inv },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => opts.confirm ?? true) } },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisOpenDialog);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const settle = async () => {
        await fixture.whenStable();
        fixture.detectChanges();
    };
    return { fixture, c: fixture.componentInstance, el, components, inv, store, close, settle };
}

describe('LinkAnalysisOpenDialog (§5.11)', () => {
    it('copyId never reuses an id; withinDays treats an unknown time as matching only "any"', () => {
        expect(copyId('a', new Set())).toBe('a-copy');
        expect(copyId('a', new Set(['a-copy', 'a-copy-2']))).toBe('a-copy-3');
        expect(withinDays(null, 0)).toBe(true);
        expect(withinDays(null, 7)).toBe(false);
        expect(withinDays('2026-10-26T00:00:00Z', 7, Date.parse('2026-10-27T00:00:00Z'))).toBe(true);
    });

    it('searches and previews saved views with the Not-evidence note; axe-clean', async () => {
        const { c, el, settle } = create();
        await settle();
        expect(c.viewRows().map((r) => r.view.id)).toEqual(['calls-hub', 'transfers']);
        c.search.setValue('transf');
        await settle();
        expect(c.viewRows().map((r) => r.view.id)).toEqual(['transfers']);
        (el.querySelector('[data-testid="la-open-view-transfers"]') as HTMLElement).click();
        await settle();
        const preview = el.querySelector('[data-testid="la-open-preview"]') as HTMLElement;
        expect(preview.textContent).toContain('about Transfers');
        expect(preview.textContent).toContain('transfers');
        expect(preview.querySelector('[data-testid="la-open-not-evidence"]')?.textContent).toContain('Not evidence');
        await expectNoA11yViolations(el);
    });

    it('filters views by Dataset and by tag', async () => {
        const { c, settle } = create();
        await settle();
        c.dataset.setValue('calls');
        await settle();
        expect(c.viewRows().map((r) => r.view.id)).toEqual(['calls-hub']);
        c.dataset.setValue('');
        c.tag.setValue('aml');
        await c.applyTag();
        expect(c.viewRows().map((r) => r.view.id)).toEqual(['transfers']);
    });

    it('renames (an update of the same id), duplicates (a create under a fresh id) and deletes (after a confirm)', async () => {
        const { c, components, settle } = create();
        await settle();
        const hub = c.viewRows()[0].view;
        c.beginRename(hub);
        c.renameTo.setValue('Hub of calls');
        await c.rename(hub);
        expect(components.update).toHaveBeenCalledWith('link-analysis-view', 'calls-hub', expect.objectContaining({ title: 'Hub of calls' }), undefined);
        expect(c.allViews().find((r) => r.view.id === 'calls-hub')?.view.name).toBe('Hub of calls');

        await c.duplicate(c.allViews()[0].view);
        expect(components.create).toHaveBeenCalledWith('link-analysis-view', expect.objectContaining({ id: 'calls-hub-copy', title: 'Copy of Hub of calls' }));
        expect(c.selectedViewId()).toBe('calls-hub-copy');

        await c.deleteView(c.allViews().find((r) => r.view.id === 'transfers')!.view);
        expect(components.remove).toHaveBeenCalledWith('link-analysis-view', 'transfers');
        expect(c.allViews().map((r) => r.view.id)).not.toContain('transfers');
    });

    it('a declined delete removes nothing', async () => {
        const { c, components, settle } = create({ confirm: false });
        await settle();
        await c.deleteView(c.allViews()[0].view);
        expect(components.remove).not.toHaveBeenCalled();
    });

    it('Open closes with the view', async () => {
        const { c, close, settle } = create();
        await settle();
        c.openView(c.allViews()[0].view);
        expect(close).toHaveBeenCalledWith({ kind: 'view', view: c.allViews()[0].view });
    });

    it('Investigations: lists plus Recent, filters by status and owner, searches an id, and opens one', async () => {
        const { c, el, close, settle } = create({ tab: 'investigations' });
        await settle();
        expect(c.investigationRows().map((r) => r.id)).toEqual(['inv-1', 'inv-2', 'inv-old']);
        c.status.setValue('recent');
        await settle();
        expect(c.investigationRows().map((r) => r.id)).toEqual(['inv-old']);
        c.status.setValue('case');
        await settle();
        expect(c.investigationRows().map((r) => r.id)).toEqual(['inv-2']);
        c.status.setValue('');
        c.owner.setValue('ana');
        await settle();
        expect(c.investigationRows().map((r) => r.id)).toEqual(['inv-1']);
        c.owner.setValue('');
        c.search.setValue('inv-404');
        await settle();
        (el.querySelector('[data-testid="la-open-by-id"]') as HTMLButtonElement).click();
        expect(close).toHaveBeenCalledWith({ kind: 'investigation', id: 'inv-404', title: undefined });
        await expectNoA11yViolations(el);
    });

    it('T-186: shows the owner by display name where the server gave one, else by login id', async () => {
        const { c, el, settle } = create({ tab: 'investigations' });
        await settle();
        expect(c.owners()).toEqual(['ana', 'bo']); // the filter still matches on the login id
        expect(c.ownerLabel('ana')).toBe('Ana Analyst');
        expect(c.ownerLabel('bo')).toBe('bo');
        const owner = () => el.querySelector('[data-testid="la-open-inv-owner"]')?.textContent?.trim();
        (el.querySelector('[data-testid="la-open-inv-inv-1"]') as HTMLButtonElement).click();
        await settle();
        expect(owner()).toBe('Ana Analyst');
        (el.querySelector('[data-testid="la-open-inv-inv-2"]') as HTMLButtonElement).click();
        await settle();
        expect(owner()).toBe('bo');
    });

    it('LA-REORG-SETTINGS-GAPS-1: the list sorts by name or by last changed, and the Investigation preview shows its pack', async () => {
        const { c, el, settle } = create({ tab: 'investigations' });
        await settle();
        expect(c.investigationRows().map((r) => r.id)).toEqual(['inv-1', 'inv-2', 'inv-old']); // newest first, unknown last
        c.sortBy.setValue('name');
        await settle();
        expect(c.investigationRows().map((r) => r.id)).toEqual(['inv-2', 'inv-1', 'inv-old']); // inv-2, Mule ring, Remembered

        (el.querySelector('[data-testid="la-open-inv-inv-1"]') as HTMLButtonElement).click();
        await settle();
        expect(el.querySelector('[data-testid="la-open-inv-pack"]')?.textContent?.trim()).toBe('mobile-money');
        (el.querySelector('[data-testid="la-open-inv-inv-2"]') as HTMLButtonElement).click();
        await settle();
        expect(el.querySelector('[data-testid="la-open-inv-pack"]')?.textContent?.trim()).toBe('Space default');
        // the list carries only createdAt (set once at create, fork or instantiate): the label says so (T-161 review)
        expect(Array.from(el.querySelectorAll('dt')).map((d) => d.textContent?.trim())).toContain('Created');
        // T-186: one labelled zone and format, the same as the step log
        expect(el.querySelector('[data-testid="la-open-preview"]')?.textContent).toContain('2026-10-01 09:00 UTC');

        c.setTab('views');
        await settle();
        expect(c.viewRows().map((r) => r.view.id)).toEqual(['calls-hub', 'transfers']);
        await expectNoA11yViolations(el);
    });

    it('a read-only (Case member) Investigation shows Delete disabled with the reason; an own one deletes after a confirm', async () => {
        const { c, el, store, settle } = create({ tab: 'investigations' });
        await settle();
        c.selectedInvestigationId.set('inv-2');
        await settle();
        const del = () => el.querySelector<HTMLButtonElement>('[data-testid="la-open-inv-delete"]')!;
        expect(del().disabled).toBe(true);
        expect(c.investigationDeleteBlock(c.selectedInvestigation()!)).toBe('Only the owner can delete.');
        expect(del().getAttribute('aria-label')).toBe('Delete: Only the owner can delete.');
        c.selectedInvestigationId.set('inv-1');
        await settle();
        expect(del().disabled).toBe(false);
        expect(c.investigationDeleteBlock(c.selectedInvestigation()!)).toBe('');
        await c.deleteInvestigation(c.selectedInvestigation()!);
        expect(store.deleteInvestigation).toHaveBeenCalledWith('inv-1');
        expect(c.investigationRows().map((r) => r.id)).not.toContain('inv-1');
    });
});
