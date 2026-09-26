import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ObjectsService, OperationalObject, SessionService, WorkflowDef } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { DEFAULT_CASE_WORKFLOW } from './mail-model';
import { ObjectMailComponent } from './object-mail.component';

function incident(id: string, status: string, extra: Partial<OperationalObject> = {}): OperationalObject {
    return {
        id,
        objectType: 'INCIDENT',
        title: `Incident ${id}`,
        description: 'sample',
        status,
        createdAt: 1,
        updatedAt: 1,
        closedAt: 0,
        ...extra,
    };
}

const OBJECTS: OperationalObject[] = [
    incident('i1', 'IDENTIFIED'),
    incident('i2', 'DIAGNOSING', { assignee: 'operator', attributes: { tags: 'urgent,network', escalated: 'true' } }),
    incident('i3', 'RESOLVED', { attributes: { category: 'Pipeline / Ingest / Parse failure' } }),
    incident('i4', 'OPEN'), // legacy status → normalizes to IDENTIFIED
    incident('i5', 'ARCHIVED'),
];

function kase(id: string, status: string): OperationalObject {
    return { ...incident(id, status), objectType: 'CASE', title: `Case ${id}` };
}

interface CreateOpts {
    type?: 'INCIDENT' | 'CASE';
    list?: Observable<OperationalObject[]>;
    workflow?: Observable<WorkflowDef>;
    /** A signed-in session (OIDC / Demo User): the Subject id `/bootstrap` reported. */
    actor?: string;
    /** Signed in with exactly these grants (arms `authMode: 'oidc'`, where capabilities are enforced client-side). */
    capabilities?: string[];
}

async function create(opts: CreateOpts = {}) {
    const type = opts.type ?? 'INCIDENT';
    const api = {
        list: vi.fn(() => opts.list ?? of(OBJECTS)),
        // CASE only: an erroring workflow fetch keeps the built-in DEFAULT_CASE_WORKFLOW (the pane's fallback).
        workflow: vi.fn(() => opts.workflow ?? throwError(() => new Error('offline'))),
        findingsSpec: vi.fn(() => throwError(() => new Error('offline'))),
        update: vi.fn((id: string) => of(OBJECTS.find((o) => o.id === id))),
        transition: vi.fn((id: string) => of(OBJECTS.find((o) => o.id === id))),
        assign: vi.fn((id: string) => of(OBJECTS.find((o) => o.id === id))),
        addComment: vi.fn(() => of({})),
        tags: vi.fn(() => of([{ name: 'billing', createdAt: 1 }])),
        tagRules: vi.fn(() => of([])),
        createTag: vi.fn((name: string) => of({ name, createdAt: 2 })),
    } as unknown as ObjectsService;
    TestBed.configureTestingModule({
        imports: [ObjectMailComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ObjectsService, useValue: api },
            { provide: MatDialog, useValue: {} },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            provideRouter([]),
            {
                provide: ActivatedRoute,
                useValue: {
                    snapshot: {
                        data: { type, title: type === 'CASE' ? 'Case Manager' : 'Incidents', subtitle: '' },
                    },
                    queryParamMap: of(convertToParamMap({})),
                },
            },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    // EDG-01 cell 7: opsEnabled defaults FALSE, where this pane renders only the explained alert.
    // ⚠ Armed here because the coverage baseline caught what no assertion did — the spec still
    // PASSED while the template it exists to exercise fell to 2.2%: it asserts on component
    // state, not on rendered output, so the gated branch was invisible to it.
    const session = TestBed.inject(SessionService);
    session.opsEnabled.set(true);
    if (opts.actor) session.actor.set(opts.actor);
    if (opts.capabilities) {
        session.authMode.set('oidc');
        session.capabilities.set(opts.capabilities);
    }
    const fixture = TestBed.createComponent(ObjectMailComponent);
    fixture.detectChanges(); // ngOnInit → reload()
    return { fixture, c: fixture.componentInstance, api };
}

describe('ObjectMailComponent', () => {
    it('folders the loaded incidents on the normalized mail lifecycle', async () => {
        const { c } = await create();
        const counts = c.counts();
        expect(counts.get('identified')).toBe(2); // i1 + legacy OPEN i4
        expect(counts.get('diagnosing')).toBe(1);
        expect(counts.get('resolved')).toBe(1);
        expect(counts.get('archived')).toBe(1);
        expect(counts.get('mine')).toBe(1); // i2 assigned to 'operator'
        expect(counts.get('escalated')).toBe(1); // i2 flag
        // Default folder is the Inbox (Identified).
        expect(
            c
                .rows()
                .map((o) => o.id)
                .sort(),
        ).toEqual(['i1', 'i4']);
    });

    it('merges registry tags (zero-count) with tags derived from the rows, and filters by tag', async () => {
        const { c } = await create();
        expect(c.tags()).toEqual([
            { tag: 'network', count: 1 },
            { tag: 'urgent', count: 1 },
            { tag: 'billing', count: 0 }, // registry-only tag stays visible
        ]);
        c.selectTag('urgent');
        expect(c.rows().map((o) => o.id)).toEqual(['i2']);
        c.selectFolder('resolved');
        expect(c.tagFilter()).toBeNull();
        expect(c.rows().map((o) => o.id)).toEqual(['i3']);
    });

    it('creates a tag from the nav inline input and refreshes the registry', async () => {
        const { c, api } = await create();
        c.navNewTag.setValue('  feeds ');
        c.createNavTag();
        expect(api.createTag).toHaveBeenCalledWith('feeds');
        expect(api.tags).toHaveBeenCalledTimes(2); // init + refresh
        expect(c.navNewTag.value).toBe('');
    });

    it('enables toolbar actions from the selection lifecycle', async () => {
        const { c } = await create();
        expect(c.canAccept()).toBe(false);
        c.onSelection([OBJECTS[0] as unknown as Record<string, unknown>]); // IDENTIFIED
        expect(c.canAccept()).toBe(true);
        expect(c.canResolve()).toBe(true);
        expect(c.canReopen()).toBe(false);
        c.onSelection([OBJECTS[2] as unknown as Record<string, unknown>]); // RESOLVED
        expect(c.canAccept()).toBe(false);
        expect(c.canReopen()).toBe(true);
    });

    it('accept assigns me + transitions when the category is already set', async () => {
        const { c, api } = await create();
        const categorized = incident('i9', 'IDENTIFIED', {
            attributes: { category: 'Security / Access / Expired credentials' },
        });
        c.accept([categorized]);
        // POST /assign (canWorkIncidents), not the canAdminister PATCH — an analyst may accept an unassigned one.
        expect(api.assign).toHaveBeenCalledWith('i9', 'operator', 'operator');
        expect(api.update).not.toHaveBeenCalled();
        expect(api.transition).toHaveBeenCalledWith('i9', 'accept', 'operator');
    });

    // ── "me" is the signed-in Subject (the Mine folder never matched anyone on a signed-in edition) ──
    it('the Mine folder matches the signed-in subject, not the Personal placeholder', async () => {
        const list = of([
            incident('a1', 'DIAGNOSING', { assignee: 'ana' }),
            incident('a2', 'DIAGNOSING', { assignee: 'operator' }),
        ]);
        const { c } = await create({ list, actor: 'ana' });
        expect(c.me).toBe('ana');
        c.selectFolder('mine');
        expect(c.rows().map((o) => o.id)).toEqual(['a1']);
    });

    it('accepting as a signed-in subject assigns and attributes the move to that subject', async () => {
        const { c, api } = await create({ actor: 'ana' });
        c.accept([
            incident('i9', 'IDENTIFIED', { attributes: { category: 'Security / Access / Expired credentials' } }),
        ]);
        expect(api.assign).toHaveBeenCalledWith('i9', 'ana', 'ana');
        expect(api.transition).toHaveBeenCalledWith('i9', 'accept', 'ana');
    });

    // ── the lifecycle verbs show for exactly the subjects the server lets move an object (canWorkIncidents) ──
    it('offers the Incident lifecycle verbs only to a subject holding canWorkIncidents', async () => {
        const { c, fixture } = await create({ capabilities: ['canOperateRuns'] });
        c.onSelection([OBJECTS[0]] as unknown as Record<string, unknown>[]); // IDENTIFIED
        const ids = (): string[] => c.bulkActions().map((a) => a.id);
        for (const verb of ['accept', 'resolve', 'reopen', 'archive']) expect(ids()).not.toContain(verb);
        expect(ids()).toContain('tag'); // collaboration stays
        TestBed.inject(SessionService).capabilities.set(['canWorkIncidents']);
        fixture.detectChanges();
        expect(ids()).toEqual(expect.arrayContaining(['accept', 'resolve', 'reopen', 'archive']));
    });

    it('offers the Case workflow verbs only to a subject holding canWorkIncidents', async () => {
        const { c } = await create({
            type: 'CASE',
            list: of([kase('c1', 'OPEN')]),
            capabilities: ['canManageIncidents'],
        });
        c.onSelection([kase('c1', 'OPEN')] as unknown as Record<string, unknown>[]);
        expect(c.bulkActions().some((a) => a.id.startsWith('case:'))).toBe(false);
        TestBed.inject(SessionService).capabilities.set(['canWorkIncidents']);
        expect(c.bulkActions().map((a) => a.id)).toContain('case:investigate');
    });

    it('prioritize patches every selected object', async () => {
        const { c, api } = await create();
        c.onSelection([OBJECTS[0], OBJECTS[1]] as unknown as Record<string, unknown>[]);
        c.prioritize('MAJOR');
        expect(api.update).toHaveBeenCalledWith('i1', { priority: 'MAJOR' });
        expect(api.update).toHaveBeenCalledWith('i2', { priority: 'MAJOR' });
    });

    it('escalate toggles the escalated attribute (de-escalates when all selected are escalated)', async () => {
        const { c, api } = await create();
        c.onSelection([OBJECTS[1]] as unknown as Record<string, unknown>[]); // escalated
        expect(c.escalateLabel()).toBe('De-escalate');
        c.escalate();
        expect(api.update).toHaveBeenCalledWith('i2', { attributes: { escalated: 'false' } });
    });

    it('triage verbs are optimistic: rows reconcile in place, no list refetch, selection clears', async () => {
        const { c, api } = await create();
        // The server echoes the updated object — reconcile must adopt it (updatedAt stamp).
        (api.update as unknown as Mock).mockImplementation((id: string, patch: { priority?: string }) =>
            of({ ...OBJECTS.find((o) => o.id === id)!, priority: patch.priority, updatedAt: 99 }),
        );
        c.onSelection([OBJECTS[0]] as unknown as Record<string, unknown>[]);
        c.prioritize('MAJOR');
        const row = c.objects().find((o) => o.id === 'i1')!;
        expect(row.priority).toBe('MAJOR');
        expect(row.updatedAt).toBe(99); // server truth, not just the optimistic patch
        expect(api.list).toHaveBeenCalledTimes(1); // R4: success never triggers a full refetch
        expect(c.selected()).toEqual([]); // old reload() contract preserved
    });

    it('a failed triage verb reloads from the server (partial success ⇒ only a refetch is honest)', async () => {
        const { c, api } = await create();
        (api.update as unknown as Mock).mockReturnValue(throwError(() => new Error('boom')));
        c.onSelection([OBJECTS[0]] as unknown as Record<string, unknown>[]);
        c.prioritize('MAJOR');
        expect(api.list).toHaveBeenCalledTimes(2); // initial load + error-path reload
    });

    it('renders the 3-pane shell with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    // ── folder vocabulary: Incident and Case are distinct concepts (GLOSSARY §9) ─────────────────
    /** The rendered folder-nav labels, in display order (folder buttons carry an aria-label; tag buttons don't). */
    function folderLabels(el: HTMLElement): string[] {
        return [...el.querySelectorAll('nav[aria-label="Folders"] > button[aria-label]')].map(
            (b) => b.querySelector('span')?.textContent?.trim() ?? '',
        );
    }

    it('names the pinned folder for the Incidents pane — "My Incidents", never "My Cases"', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(folderLabels(el)[0]).toBe('My Incidents');
        expect(el.textContent).not.toContain('My Cases');
    });

    it('names the pinned folder for the Case Manager pane — "My Cases"', async () => {
        const { fixture } = await create({ type: 'CASE', list: of([kase('c1', 'OPEN')]) });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(folderLabels(el)[0]).toBe('My Cases');
        expect(el.textContent).not.toContain('My Incidents');
        expect(el.textContent).toContain('New case');
    });

    // ── landing folder: never open on an empty folder while others hold items ───────────────────
    it('lands on the first non-empty folder when the default (Open) is empty', async () => {
        // The telco demo: Open 0 · Investigating 1 · Resolved 1 — the analyst landed on "Nothing in Open".
        const { c } = await create({
            type: 'CASE',
            list: of([kase('c1', 'RESOLVED'), kase('c2', 'INVESTIGATING')]),
        });
        expect(c.folderId()).toBe('investigating'); // display order, not load order
        expect(c.rows().map((o) => o.id)).toEqual(['c2']);
    });

    it('keeps the default folder while it has items, even when an earlier folder (My Cases) has some', async () => {
        const mine = { ...kase('c1', 'INVESTIGATING'), assignee: 'operator' };
        const { c } = await create({ type: 'CASE', list: of([mine, kase('c2', 'OPEN')]) });
        expect(c.counts().get('mine')).toBe(1);
        expect(c.folderId()).toBe('open');
    });

    it('falls back to the default folder when every folder is empty', async () => {
        const { c } = await create({ type: 'CASE', list: of([]) });
        expect(c.folderId()).toBe('open');
    });

    it('re-lands when the served workflow (the case folder set) arrives after the list', async () => {
        const wf$ = new Subject<WorkflowDef>();
        const { c } = await create({ type: 'CASE', list: of([kase('c1', 'TRIAGE')]), workflow: wf$ });
        expect(c.folderId()).toBe('open'); // built-in folders: nothing matches TRIAGE yet
        wf$.next({
            type: 'CASE',
            initial: 'NEW',
            states: ['NEW', 'TRIAGE', 'DONE'],
            terminal: ['DONE'],
            transitions: [],
        });
        expect(c.folderId()).toBe('triage');
    });

    it('an explicit folder choice made before the list lands always wins', async () => {
        const list$ = new Subject<OperationalObject[]>();
        const wf$ = new Subject<WorkflowDef>();
        const { c } = await create({ type: 'CASE', list: list$, workflow: wf$ });
        c.selectFolder('open'); // the operator deliberately opens the (empty) Open folder
        list$.next([kase('c1', 'INVESTIGATING')]);
        expect(c.folderId()).toBe('open');
        wf$.next({ ...DEFAULT_CASE_WORKFLOW });
        expect(c.folderId()).toBe('open');
    });

    it('renders the Case Manager landing with no a11y violations', async () => {
        const { fixture } = await create({ type: 'CASE', list: of([kase('c1', 'INVESTIGATING')]) });
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('offers Load more once a fetch returns a full page, and appends the next offset page on click (R6)', async () => {
        const fullPage = Array.from({ length: 500 }, (_, i) => incident(`p${i}`, 'IDENTIFIED'));
        const api = {
            list: vi.fn(() => of(fullPage)),
            tags: vi.fn(() => of([])),
            tagRules: vi.fn(() => of([])),
        } as unknown as ObjectsService;
        TestBed.configureTestingModule({
            imports: [ObjectMailComponent],
            providers: [
                provideNoopAnimations(),
                { provide: ObjectsService, useValue: api },
                { provide: MatDialog, useValue: {} },
                { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
                { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
                provideRouter([]),
                {
                    provide: ActivatedRoute,
                    useValue: {
                        snapshot: { data: { type: 'INCIDENT', title: 'Incidents', subtitle: '' } },
                        queryParamMap: of(convertToParamMap({})),
                    },
                },
                { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
                { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            ],
        });
        await TestBed.compileComponents();
        const fixture = TestBed.createComponent(ObjectMailComponent);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.hasMore()).toBe(true);
        c.loadMore();
        // The next page is fetched at offset = rows already loaded and APPENDED — no refetch from 0.
        expect((api.list as unknown as Mock).mock.calls[1][0]).toMatchObject({ limit: 500, offset: 500 });
        expect(c.objects().length).toBe(1000);
    });
});
