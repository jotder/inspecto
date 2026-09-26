import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import {
    EntityListSummary,
    InvService,
    InvestigationLog,
    LensService,
    SessionService,
    WorkingSet,
} from 'app/inspecto/api';
import { LinkAnalysisSettingsService } from 'app/inspecto/api/link-analysis-settings.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LinkAnalysisEntityListsComponent } from './link-analysis-entity-lists.component';
import { CreateEntityListDialog, EntityListReasonDialog } from './link-analysis-entity-lists.dialogs';
import { InvestigationSessionStore } from './link-analysis-investigation.store';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisEntityListsComponent],
    template: `<inspecto-link-analysis-entity-lists></inspecto-link-analysis-entity-lists>`,
})
class Host {}

function summary(extra: Partial<EntityListSummary>): EntityListSummary {
    return {
        id: 'mules',
        title: 'Known mules',
        purpose: 'exclusion',
        entityType: 'msisdn',
        size: 40,
        retired: false,
        createdAt: '2026-09-26T10:00:00Z',
        createdBy: 'alice',
        lastSeq: 17,
        ...extra,
    };
}

const LISTS = [summary({}), summary({ id: 'old', title: 'Old watch', purpose: 'watch', size: 1, retired: true })];

const LOG: InvestigationLog = {
    header: {
        id: 'inv-1',
        title: 'Burners',
        owner: 'alice',
        dataset: 'calls',
        sourceCol: 'A',
        targetCol: 'B',
        linkKindCol: null,
        createdAt: '',
        datasetVersion: null,
        parent: null,
    },
    entries: [],
    total: 0,
    truncated: false,
};
const WS: WorkingSet = { entities: [], links: [], excluded: [], hash: '' };

const http = (status: number, message = 'server says no') =>
    throwError(() => new HttpErrorResponse({ status, error: { error: { message } } }));

interface Options {
    lists?: EntityListSummary[];
    canWrite?: boolean;
    geoLink?: boolean;
}

function create({ lists = LISTS, canWrite = true, geoLink = true }: Options = {}) {
    const inv = {
        listEntityLists: vi.fn(() => of({ lists, headSeq: 17, headHash: 'sha256:h' })),
        changeEntityListMembers: vi.fn((id: string) =>
            of({ ...summary({ id, size: 41 }), members: [], atSeq: 18, headHash: null, changed: 1 }),
        ),
        retireEntityList: vi.fn((id: string) =>
            of({ ...summary({ id, retired: true }), members: [], atSeq: 18, headHash: null }),
        ),
        investigationLog: vi.fn(() => of(structuredClone(LOG))),
        replayInvestigation: vi.fn(() => of({ workingSet: WS })),
        appendInvestigationOp: vi.fn(() =>
            of({ list: { listId: 'mules', removed: 12, unmatched: ['k1', 'k2', 'k3'] } }),
        ),
    };
    /** What the next dialog closes with — a reason string, a created list, or undefined (cancelled). */
    const next = { result: undefined as unknown };
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of(next.result) })) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            InvestigationSessionStore,
            { provide: InvService, useValue: inv },
            { provide: MatDialog, useValue: dialog },
            { provide: SessionService, useValue: { geoLinkEnabled: signal(geoLink) } },
            { provide: LensService, useValue: { canManageIncidents: signal(canWrite) } },
            {
                provide: LinkAnalysisSettingsService,
                useValue: {
                    limits: signal({
                        entityTypesInForce: [
                            { id: 'msisdn', label: 'MSISDN', normaliser: 'e164', masked: true, classifications: [] },
                        ],
                    }),
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const store = TestBed.inject(InvestigationSessionStore);
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) => el.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
    const settle = async () => {
        await fixture.whenStable();
        fixture.detectChanges();
    };
    return { fixture, store, inv, dialog, next, el, button, settle };
}

describe('LinkAnalysisEntityListsComponent (LA-17)', () => {
    it('renders the empty state when the Space has no lists, and passes axe', async () => {
        const { el, inv, settle } = create({ lists: [] });
        await settle();
        expect(inv.listEntityLists).toHaveBeenCalledTimes(1);
        expect(el.querySelector('inspecto-empty-state')?.textContent).toContain('This Space has no Entity Lists yet');
        await expectNoA11yViolations(el);
    });

    it('lists title, purpose, Entity Type label, size and a retired badge — never members', async () => {
        const { el, button, settle } = create();
        await settle();
        const items = Array.from(el.querySelectorAll('[aria-label="Entity Lists of this Space"] li'));
        expect(items).toHaveLength(2);
        expect(items[0].textContent).toContain('Known mules');
        expect(items[0].textContent).toContain('Exclusion · MSISDN · 40 members');
        expect(items[0].querySelector('inspecto-status-badge')).toBeNull();
        expect(items[1].textContent).toContain('Watch · MSISDN · 1 member');
        expect(items[1].querySelector('inspecto-status-badge')?.textContent).toContain('Retired');
        // A retired list offers no writes.
        expect(button('Retire Old watch')).toBeNull();
        expect(button('Retire Known mules')).not.toBeNull();
        // No Investigation open ⇒ the list-bound ops are not offered.
        expect(button('Exclude by list Known mules')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('adds the selected entity’s raw values with the reason, skips masked ones, and shows `changed`', async () => {
        const { store, inv, dialog, next, el, button, settle } = create();
        await settle();
        expect(button('Add the selected entity to Known mules')!.disabled).toBe(true); // nothing selected

        store.selected.set({
            id: 'entity:msisdn:+15550001',
            data: { label: '+1 555 0001', kind: 'entity', spellings: ['+1 555 0001', 'masked:0123456789abcdef'] },
        });
        await settle();
        expect(el.textContent).toContain('1 masked value(s) cannot be added');
        next.result = 'seen on the ring';
        button('Add the selected entity to Known mules')!.click();
        await settle();

        expect(dialog.open).toHaveBeenCalledWith(EntityListReasonDialog, expect.anything());
        expect(inv.changeEntityListMembers).toHaveBeenCalledWith('mules', {
            add: ['+1 555 0001'],
            reason: 'seen on the ring',
        });
        expect(el.textContent).toContain('1 member added to “Known mules” — it now has 41.');
        expect(inv.listEntityLists).toHaveBeenCalledTimes(2); // re-read after the write
    });

    it('a cancelled reason dialog writes nothing', async () => {
        const { inv, next, button, settle } = create();
        await settle();
        next.result = undefined;
        button('Retire Known mules')!.click();
        await settle();
        expect(inv.retireEntityList).not.toHaveBeenCalled();
    });

    it('retires through a destructive confirm that asks the reason', async () => {
        const { inv, dialog, next, el, button, settle } = create();
        await settle();
        next.result = 'superseded';
        button('Retire Known mules')!.click();
        await settle();
        const [, config] = dialog.open.mock.calls[0] as unknown as [unknown, { data: { destructive: boolean } }];
        expect(config.data.destructive).toBe(true);
        expect(inv.retireEntityList).toHaveBeenCalledWith('mules', 'superseded');
        expect(el.textContent).toContain('“Known mules” retired.');
    });

    it('opens the create dialog with the Entity Types in force and re-reads after a create', async () => {
        const { inv, dialog, next, el, settle } = create();
        await settle();
        next.result = { ...summary({ id: 'new', title: 'Fresh' }), members: [], atSeq: 18, headHash: null };
        (
            Array.from(el.querySelectorAll('button')).find((b) =>
                b.textContent?.includes('New list'),
            ) as HTMLButtonElement
        ).click();
        await settle();
        const [type, config] = dialog.open.mock.calls[0] as unknown as [unknown, { data: { entityTypes: unknown[] } }];
        expect(type).toBe(CreateEntityListDialog);
        expect(config.data.entityTypes).toHaveLength(1);
        expect(inv.listEntityLists).toHaveBeenCalledTimes(2);
        expect(el.textContent).toContain('“Fresh” created.');
    });

    it('on the open Investigation, excludeBy / seedBy go through the store’s op path and refresh the log', async () => {
        const { store, inv, next, el, button, settle } = create();
        store.restore([{ id: 'inv-1', title: 'Burners' }]);
        await store.open('inv-1');
        await settle();
        const logReads = inv.investigationLog.mock.calls.length;

        next.result = 'known mules';
        button('Exclude by list Known mules')!.click();
        await settle();
        expect(inv.appendInvestigationOp).toHaveBeenCalledWith('inv-1', {
            op: 'excludeBy',
            listId: 'mules',
            reason: 'known mules',
        });
        expect(inv.investigationLog.mock.calls.length).toBe(logReads + 1);
        expect(el.textContent).toContain('Excluded 12 entities on “Known mules” — 3 members matched nothing.');

        inv.appendInvestigationOp.mockReturnValue(of({ list: { listId: 'mules', seeded: 2, unmatched: 0 } }) as never);
        button('Seed by list Known mules')!.click();
        await settle();
        expect(inv.appendInvestigationOp).toHaveBeenLastCalledWith('inv-1', { op: 'seedBy', listId: 'mules' });
        expect(el.textContent).toContain('Seeded 2 entities from “Known mules”.');
    });

    it('renders refusals: 503 as an explained notice, 409 with the server reason, an op 404 as list-or-Investigation', async () => {
        const { store, inv, next, el, button, settle } = create();
        await settle();

        inv.changeEntityListMembers.mockReturnValue(http(409, 'list is retired') as never);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        next.result = 'r';
        await settle();
        button('Add the selected entity to Known mules')!.click();
        await settle();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain('Refused — list is retired');

        store.restore([{ id: 'inv-1' }]);
        await store.open('inv-1');
        inv.appendInvestigationOp.mockReturnValue(http(404, 'unknown list') as never);
        await settle();
        button('Seed by list Known mules')!.click();
        await settle();
        expect(store.error()).toContain('The Entity List or the Investigation is not available');
        expect(store.error()).toContain('unknown list');

        inv.listEntityLists.mockReturnValue(http(503, 'not installed') as never);
        button('Refresh the Entity Lists')!.click();
        await settle();
        const alert = el.querySelector('inspecto-alert');
        expect(alert?.querySelector('[role="status"]')).not.toBeNull(); // info, not an error
        expect(alert?.textContent).toContain('Entity Lists are not available here');
        expect(el.querySelector('[aria-label="Entity Lists of this Space"]')).toBeNull();
    });

    it('without canManageIncidents the lists are read-only', async () => {
        const { el, button, settle } = create({ canWrite: false });
        await settle();
        expect(el.textContent).toContain('needs the Incident-management capability');
        expect(el.textContent).not.toContain('New list');
        expect(button('Retire Known mules')).toBeNull();
        expect(button('Add the selected entity to Known mules')).toBeNull();
    });

    it('renders nothing and calls nothing when the link-analysis module is absent', async () => {
        const { el, inv, settle } = create({ geoLink: false });
        await settle();
        expect(el.querySelector('[aria-label="Entity Lists"]')).toBeNull();
        expect(inv.listEntityLists).not.toHaveBeenCalled();
    });
});
