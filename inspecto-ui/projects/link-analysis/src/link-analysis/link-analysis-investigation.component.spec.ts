import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import {
    InvService,
    InvestigationCoverage,
    InvestigationLog,
    WorkingSet,
} from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { LA_FEATURES, LA_WIDGETS } from '@inspecto/link-analysis/la-host';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { EntityProjection } from '@inspecto/core/graph';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from '@inspecto/core/grid';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvestigationSection, LinkAnalysisInvestigationComponent } from './link-analysis-investigation.component';
import { By } from '@angular/platform-browser';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { PendingInvestigation } from './investigate-number';
import { WidgetsService } from 'app/modules/admin/studio/widgets/widgets.service';
import { Widget } from 'app/modules/admin/studio/widgets/widget-types';
import { provideLaHostServices } from 'app/modules/admin/studio/la-host.providers';
import { penByLabel, penEdit, penError, penText, refresh } from '@inspecto/core/testing/pen-field';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisInvestigationComponent],
    template: `<inspecto-link-analysis-investigation
        [projection]="projection()"
        projectionIssue="Run a query first."
        [timeCol]="timeCol()"
        [pending]="pending()"
        [degreePresets]="{ budget: 2000 }"
        [maxDegree]="4"
        (pendingDone)="pending.set(null)"
        (settingsRequested)="settingsAsked = true"
        (dockRequested)="dockAsked = $event"
    ><p laNewInvestigation data-testid="projected-new-form">New Investigation form</p></inspecto-link-analysis-investigation>
    <!-- §5.8/§5.9: the parts the Act panel handed to the dock and the settings sections, over the same store -->
    @for (s of sections; track s) {
        <inspecto-link-analysis-investigation [section]="s" [attr.data-section]="s"></inspecto-link-analysis-investigation>
    }`,
})
class Host {
    readonly projection = signal<EntityProjection | null>({ datasetId: 'calls', sourceCol: 'A', targetCol: 'B' });
    readonly timeCol = signal('');
    readonly pending = signal<PendingInvestigation | null>(null);
    readonly sections: InvestigationSection[] = ['settings', 'oplog', 'ws-rows', 'coverage', 'annotations', 'excluded'];
    settingsAsked = false;
    dockAsked: InvestigationSection | null = null;
}

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
    entries: [
        {
            step: 1,
            kind: 'op',
            op: 'seed',
            params: { ids: ['a'] },
            author: 'alice',
            at: '2026-09-23T10:00:00Z',
            undoneBy: null,
            workingSetHash: '',
            text: '1. Seeded 1 entity: a.',
        },
        {
            step: 2,
            kind: 'op',
            op: 'expand',
            params: { ids: ['a'] },
            author: 'alice',
            at: '2026-09-23T10:01:00Z',
            undoneBy: null,
            workingSetHash: '',
            text: '2. Expanded one hop.',
        },
    ],
    total: 2,
    truncated: false,
};

const WS: WorkingSet = {
    entities: [{ id: 'a', type: null, hop: 0, seed: 'a', admittedBy: 1, hidden: false, kept: false }],
    links: [{ source: 'a', target: 'b', kind: 'voice', count: 2, admittedBy: 2, linkId: 'lk.YQBiAHZvaWNl' }],
    excluded: [],
    hash: '',
};

const COVERAGE: InvestigationCoverage = {
    id: 'inv-1',
    dataset: 'calls',
    window: { from: '2026-09-01T00:00:00Z', to: '2026-09-04T00:00:00Z' },
    zone: 'UTC',
    expectedDays: 3,
    coveredDays: 1,
    missingDays: ['2026-09-02', '2026-09-03'],
    complete: false,
    perDay: [
        { date: '2026-09-01', rows: 5 },
        { date: '2026-09-02', rows: 0 },
        { date: '2026-09-03', rows: 0 },
    ],
    readAt: '',
    collectors: {
        assessed: false,
        note: 'per-Collector coverage is not assessed: a Dataset row carries no Collector attribution',
    },
};

/** One queued seed: the projection start form shows only while seeds are queued (LA-PANELS-REDESIGN-1). */
const QUEUED = [{ id: 'q', label: 'Q', ids: ['q'] }];

const refused = (message: string) =>
    throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message } } }));

function create(
    opts: {
        canManage?: boolean;
        noWidgetLibrary?: boolean;
        ws?: WorkingSet;
        confirm?: () => Promise<boolean>;
        /** The caller's role the members route answers; absent = the route is not mocked (the call fails). */
        role?: 'lead' | 'analyst' | null;
    } = {},
) {
    const inv = {
        ...(opts.role !== undefined
            ? {
                  investigationMembers: vi.fn(() =>
                      of({ investigationId: 'inv-1', owner: 'alice', members: [], history: [], you: opts.role }),
                  ),
              }
            : {}),
        editInvestigation: vi.fn((id: string, c: { title?: string; purpose?: string }) =>
            of({ id, title: c.title ?? 'Burners', purpose: c.purpose ?? null, updatedBy: 'alice', updatedAt: 'x' }),
        ),
        createInvestigation: vi.fn(() => of(LOG.header)),
        investigationLog: vi.fn(() => of(LOG)),
        replayInvestigation: vi.fn(() => of({ workingSet: opts.ws ?? WS })),
        appendInvestigationOp: vi.fn(() => of({})),
        undoInvestigation: vi.fn(() => of({})),
        deleteInvestigation: vi.fn(() => of({ id: 'inv-9', deleted: true, steps: 3, drafts: 0 })),
        reorderInvestigation: vi.fn(() =>
            of({ id: 'inv-fork', parent: { id: 'inv-1', order: [2, 1], parentSteps: 2 } }),
        ),
        workingSetRelation: vi.fn(() =>
            of({ head: { step: 2, workingSetHash: 'sha256:h2' }, rows: [], total: 0, truncated: false, cached: false }),
        ),
        investigationCoverage: vi.fn(() => of(COVERAGE)),
        investigationDataChanges: vi.fn(() =>
            of({
                id: 'inv-1',
                dataset: 'calls',
                window: null,
                lastLookAt: 'x',
                assessed: true,
                changes: [],
                changedDays: [],
                capped: false,
                readAt: 'x',
            }),
        ),
        datasetFiles: vi.fn(() =>
            of({ dataset: 'calls', assessed: false, total: 0, offset: 0, limit: 25, items: [], readAt: 'x' }),
        ),
        listInvestigations: vi.fn(() =>
            of({
                items: [
                    {
                        id: 'inv-9',
                        title: 'Shared',
                        dataset: 'calls',
                        owner: 'bob',
                        createdAt: '',
                        headStep: 3,
                        caseRef: 'case-1',
                        access: 'case-member',
                        readOnly: true,
                    },
                ],
                total: 1,
                limit: 100,
                offset: 0,
                truncated: false,
            }),
        ),
        investigationMeasures: vi.fn(() => of({ head: { step: 2, workingSetHash: 'sha256:h2' }, measures: [] })),
        // LA-17: the hosted Entity Lists section reads the Space's lists on creation.
        listEntityLists: vi.fn(() => of({ lists: [], headSeq: 0, headHash: null })),
    };
    const widgets = { save: vi.fn((w: Widget) => of(w)) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            ...provideLaHostServices(),
            provideNoopAnimations(),
            InvestigationSessionStore,
            { provide: InvService, useValue: inv },
            { provide: WidgetsService, useValue: widgets },
            { provide: LA_FEATURES, useValue: { ops: signal(false), exchange: signal(false), geoLink: signal(true) } },
            // la-app has no Widget library: its LA_WIDGETS says so and the pin section is not offered.
            ...(opts.noWidgetLibrary
                ? [{ provide: LA_WIDGETS, useValue: { available: false, saveWorkingSetWidget: vi.fn() } }]
                : []),
            ...(opts.confirm
                ? [
                      {
                          provide: InspectoConfirmService,
                          useValue: { confirm: vi.fn(opts.confirm), confirmDestructive: vi.fn(opts.confirm) },
                      },
                  ]
                : []),
            {
                provide: LensService,
                useValue: { canManageIncidents: signal(opts.canManage ?? true), canShareInvestigation: signal(false), canUploadData: signal(false) },
            },
            { provide: LinkAnalysisSettingsService, useValue: { limits: signal({ entityTypesInForce: [] }) } },
            // the data-table's real theme service walks up to GAMMA_APP_CONFIG — stub it, as its own spec does
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const store = TestBed.inject(InvestigationSessionStore);
    const el = fixture.nativeElement as HTMLElement;
    const button = (text: string) =>
        Array.from(el.querySelectorAll('button')).find((b) =>
            b.textContent?.trim().startsWith(text),
        ) as HTMLButtonElement;
    return { fixture, store, inv, widgets, el, button };
}

/** Open inv-1 as if it had been started here, and let the async refresh land. */
async function openInv(store: InvestigationSessionStore) {
    store.restore([{ id: 'inv-1', title: 'Burners' }]);
    await store.open('inv-1');
}

// Full-component renders run 5-7s on the CI runner, past vitest's 5s default. 45 s since 2026-10-15 (was 20 s, the
// Neighbours block the 15 s default): under `test:coverage` (v8 instrumentation, what ui.yml runs) these specs took
// over 20 s on a 12-thread dev box and timed out.
describe('LinkAnalysisInvestigationComponent (LA-10)', { timeout: 45_000 }, () => {
    it('prefills the time column from the default (DR-D7) and starts with it', async () => {
        const { fixture, el, inv, button, store } = create();
        store.queueSeeds(QUEUED); // LA-PANELS-REDESIGN-1: the projection start form serves queued seeds only
        const host = fixture.componentInstance as unknown as { timeCol: { set(v: string): void } };
        host.timeCol.set('booked_at');
        fixture.detectChanges();
        expect(penText(penByLabel(el, 'Time column (optional)')!)).toBe('booked_at');
        penEdit(fixture, penByLabel(el, 'Purpose / legal basis')!, 'warrant 7');
        button('Start Investigation').click();
        await fixture.whenStable();
        expect(inv.createInvestigation).toHaveBeenCalledWith(expect.objectContaining({ timeCol: 'booked_at' }));
    });

    it('without the Incident management capability the write controls are disabled and say why', async () => {
        const { fixture, store, el, button } = create({ canManage: false });
        await openInv(store);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="investigation-read-only"]')?.textContent).toContain(
            "Your role can't change Investigations (needs Incident management)",
        );
        // Mark this position sits behind More steps ▾ (§5.8 block 7)
        (fixture.debugElement.children[0].componentInstance as LinkAnalysisInvestigationComponent).moreStep.set('snapshot');
        fixture.detectChanges();
        for (const label of ['Seed', 'Expand the whole Working Set', 'Undo', 'Mark this position', 'Fork a Draft']) {
            expect(button(label)?.disabled, label).toBe(true);
        }
    });

    it('without the capability Start Investigation is disabled with the reason, and Enter in a field fires no write', async () => {
        const ro = create({ canManage: false });
        ro.store.queueSeeds(QUEUED);
        ro.fixture.detectChanges();
        penEdit(ro.fixture, penByLabel(ro.el, 'Purpose / legal basis')!, 'warrant 7');
        expect(ro.button('Start Investigation').disabled).toBe(true);
        expect(ro.el.querySelector('[data-testid="investigation-read-only"]')?.textContent).toContain(
            "Your role can't start Investigations (needs Incident management)",
        );
        // Enter in a field commits it and asks to start: with no capability that must write nothing
        penEdit(ro.fixture, penByLabel(ro.el, 'Title')!, 'a title');
        await ro.fixture.whenStable();
        expect(ro.inv.createInvestigation).not.toHaveBeenCalled();

        TestBed.resetTestingModule();
        const { fixture, store, el, inv } = create({ canManage: false });
        await openInv(store);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();
        penEdit(fixture, penByLabel(el, 'Reason to exclude')!, 'because');
        await fixture.whenStable();
        expect(inv.appendInvestigationOp).not.toHaveBeenCalled();
    });

    it('Enter in the Note field records the annotation; an empty note writes nothing', async () => {
        const { fixture, store, el, inv } = create();
        await openInv(store);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();
        inv.appendInvestigationOp.mockClear?.();
        penEdit(fixture, penByLabel(el, 'Note')!, '   ');
        await fixture.whenStable();
        expect(inv.appendInvestigationOp).not.toHaveBeenCalled();
        penEdit(fixture, penByLabel(el, 'Note')!, 'seen at the depot');
        await fixture.whenStable();
        expect(inv.appendInvestigationOp).toHaveBeenCalledTimes(1);
        expect(JSON.stringify(inv.appendInvestigationOp.mock.calls[0])).toContain('seen at the depot');
    });

    it('with the capability the read-only note is absent and Seed is enabled', async () => {
        const { fixture, store, el, button } = create();
        await openInv(store);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="investigation-read-only"]')).toBeNull();
        expect(button('Seed').disabled).toBe(false);
    });

    it('nothing open and nothing queued: the page-projected New Investigation form shows, and no Open… button (File menu)', () => {
        const { el } = create();
        expect(el.querySelector('[data-testid="projected-new-form"]')).not.toBeNull();
        expect(el.querySelector('[data-testid="investigation-queued-start"]')).toBeNull();
        expect(el.querySelector('[data-testid="investigation-open"]')).toBeNull();
    });

    it('start state with queued seeds: explains the binding, starts from the projection, and passes axe', async () => {
        const { fixture, el, inv, button, store } = create();
        store.queueSeeds(QUEUED);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="projected-new-form"]')).toBeNull();
        expect(el.textContent).toContain("the query's filter is not applied");
        expect(el.textContent).toContain('File ▸ Open… lists the ones you may read');
        await expectNoA11yViolations(el);

        // D-U5: no purpose, no start — the server would answer 422.
        expect(button('Start Investigation').disabled).toBe(true);
        penEdit(fixture, penByLabel(el, 'Purpose / legal basis')!, 'warrant 7');
        button('Start Investigation').click();
        await fixture.whenStable();
        expect(inv.createInvestigation).toHaveBeenCalledWith(
            expect.objectContaining({ purpose: 'warrant 7', dataset: 'calls', sourceCol: 'A', targetCol: 'B' }),
        );
    });

    it('open Investigation: renders the ordered op log and refuses an exclusion without a reason', async () => {
        const { fixture, store, el, inv, button } = create();
        await openInv(store);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();

        const steps = Array.from(el.querySelectorAll('[aria-label="Op log"] li')).map((li) => li.textContent);
        expect(steps).toHaveLength(2);
        expect(steps[0]).toContain('Seeded 1 entity: a.');
        expect(steps[1]).toContain('expand');
        await expectNoA11yViolations(el);

        button('Exclude').click();
        fixture.detectChanges();
        expect(inv.appendInvestigationOp).not.toHaveBeenCalled();
        refresh(fixture);
        expect(penError(penByLabel(el, 'Reason to exclude')!)).toContain('needs a reason');
    });

    it('re-order explains the fork on screen, then forks with the new order', async () => {
        const { fixture, store, el, inv, button } = create();
        await openInv(store);
        fixture.detectChanges();

        expect(button('Re-order steps').textContent).toContain('creates a fork'); // DR-D8
        button('Re-order steps').click();
        fixture.detectChanges();
        expect(el.textContent).toContain('Re-ordering creates a fork');
        expect(el.textContent).toContain('The original Investigation stays exactly as it is');
        expect(button('Create fork').disabled).toBe(true); // an unchanged order would fork for nothing

        (el.querySelector('[aria-label="Move step 2 up"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(button('Create fork').disabled).toBe(false);
        button('Create fork').click();
        await fixture.whenStable();
        expect(inv.reorderInvestigation).toHaveBeenCalledWith('inv-1', { order: [2, 1] });
    });

    it('LA-UI-DRAFT-FIX-1: with a Draft as the scope, re-order and re-read are not offered, and say why', async () => {
        const { fixture, store, el, button } = create();
        await openInv(store);
        store.activeDraftId.set('d-1');
        fixture.detectChanges();
        expect(button('Re-order steps').disabled).toBe(true);
        expect(el.textContent).toContain('go back to the Investigation to re-order');
        await fixture.whenStable(); // ngModel applies [disabled] asynchronously
        fixture.detectChanges();
        const reread = el.querySelector('[aria-label="Replay"] input[type="checkbox"]') as HTMLInputElement;
        expect(reread.disabled).toBe(true);
        expect(el.textContent).toContain('a Draft has no re-read of current data');
    });

    it('read-only: Replay and the Re-read (drift check) stay usable and call the service; mutating buttons stay disabled', async () => {
        const { fixture, store, el, inv, button } = create({ canManage: false });
        await openInv(store);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const reread = el.querySelector('[aria-label="Replay"] input[type="checkbox"]') as HTMLInputElement;
        expect(reread.disabled).toBe(false);
        expect(button('Replay').disabled).toBe(false);
        expect((el.querySelector('[aria-label="Undo the latest step"]') as HTMLButtonElement).disabled).toBe(true);
        expect(button('Re-order steps').disabled).toBe(true);
        inv.replayInvestigation.mockClear();
        inv.replayInvestigation.mockReturnValue(
            of({ workingSet: WS, equivalent: true, mismatches: [], reread: false, drift: [], diverged: false }),
        );
        button('Replay').click();
        await fixture.whenStable();
        expect(inv.replayInvestigation).toHaveBeenCalledTimes(1);
        reread.click();
        fixture.detectChanges();
        button('Replay').click();
        await fixture.whenStable();
        expect(inv.replayInvestigation).toHaveBeenLastCalledWith('inv-1', expect.objectContaining({ reread: true }));
    });

    it('does not offer Pin to a Widget when the shell has no Widget library (LA_WIDGETS.available false)', async () => {
        const { fixture, store, el } = create({ noWidgetLibrary: true });
        await openInv(store);
        fixture.detectChanges();
        expect(el.querySelector('[aria-label="Pin to a Widget"]')).toBeNull();
        expect(el.textContent).not.toContain('Save Widget');
    });

    it('LA-21: pins the Working Set to a Widget at the current head — Frozen by default, Live on request', async () => {
        const { fixture, store, inv, widgets, el, button } = create();
        await openInv(store);
        fixture.detectChanges();
        const section = el.querySelector('[aria-label="Pin to a Widget"]') as HTMLElement;
        expect(section.textContent).toContain('a Live Widget cannot leave this Space');
        await expectNoA11yViolations(el);

        button('Save Widget').click(); // no name yet
        fixture.detectChanges();
        expect(widgets.save).not.toHaveBeenCalled();
        refresh(fixture);
        expect(penError(penByLabel(section, 'Widget name')!)).toContain('A name is required');

        penEdit(fixture, penByLabel(section, 'Widget name')!, 'burners_frozen');
        button('Save Widget').click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(inv.workingSetRelation).toHaveBeenCalledWith('inv-1', { of: 'entities', limit: 1 });
        const frozen = widgets.save.mock.calls[0][0];
        expect(frozen).toMatchObject({
            id: 'burners_frozen',
            datasetId: '',
            vizType: 'working-set',
            viewId: 'inv-1',
            workingSet: { relation: 'entities', mode: 'frozen', pin: { step: 2, workingSetHash: 'sha256:h2' } },
        });
        expect(el.textContent).toContain('“burners_frozen” is in the Widget library');
        expect(penError(penByLabel(section, 'Widget name')!)).toBe(''); // the cleared name is not left in error (found in preview)

        const comp = fixture.debugElement.query(By.css('[data-section="settings"]')).componentInstance;
        comp.pinForm.setValue({ name: 'burners_live', relation: 'links', mode: 'live' });
        button('Save Widget').click();
        await fixture.whenStable();
        expect(widgets.save.mock.calls[1][0].workingSet).toMatchObject({ relation: 'links', mode: 'live' });
    });

    it('LA-19: annotates the selected entity with its note, renders the sealed notes, and undo removes them', async () => {
        const { fixture, store, el, inv, button } = create();
        await openInv(store);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();

        button('Annotate').click(); // no note yet
        fixture.detectChanges();
        expect(inv.appendInvestigationOp).not.toHaveBeenCalled();
        refresh(fixture);
        expect(penError(penByLabel(el, 'Note')!)).toContain('Write the note first');

        const annotated: WorkingSet = { ...WS, annotations: [{ id: 'a', step: 3, note: 'burner pattern' }] };
        inv.replayInvestigation.mockReturnValue(of({ workingSet: annotated }));
        const comp = fixture.debugElement.children[0].componentInstance as LinkAnalysisInvestigationComponent;
        comp.note.setValue('  burner pattern ');
        button('Annotate').click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(inv.appendInvestigationOp).toHaveBeenCalledWith('inv-1', {
            op: 'annotate',
            ids: ['a'],
            note: 'burner pattern',
        });
        expect(el.querySelector('[aria-label="Annotations"]')?.textContent).toContain('burner pattern (step 3)');
        expect(el.querySelector('[aria-label="Notes on this entity"]')?.textContent).toContain('burner pattern');
        await expectNoA11yViolations(el);

        // Undo is generic: the re-read sealed state no longer carries the note, so neither does the screen.
        inv.replayInvestigation.mockReturnValue(of({ workingSet: WS }));
        button('Undo').click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(inv.undoInvestigation).toHaveBeenCalledWith('inv-1', 2); // names the newest step the log it read holds (UNDO-RACE-1)
        expect(el.querySelector('[aria-label="Annotations"]')).toBeNull();
        expect(el.querySelector('[aria-label="Notes on this entity"]')).toBeNull();
    });

    it('an expand the flat Dataset answered says why, in text, without blocking', async () => {
        const { fixture, store, el, inv } = create();
        await openInv(store);
        inv.appendInvestigationOp.mockReturnValue(
            of({
                step: 3,
                op: 'expand',
                truncated: false,
                read: {
                    rowCount: 1,
                    fingerprint: 'f',
                    readAt: '',
                    fallback: { reason: 'rung_not_indexable', details: 'the rung has a window' },
                },
            }),
        );
        await store.apply({ op: 'expand' } as never);
        fixture.detectChanges();
        const note = el.querySelector('[data-testid="expand-fallback"]');
        expect(note?.textContent).toContain('Answered from the Dataset because');
        expect(note?.textContent).toContain('the rung has a window');
        expect(note?.getAttribute('role')).toBe('status');
        await expectNoA11yViolations(el);

        inv.appendInvestigationOp.mockReturnValue(
            of({ step: 4, op: 'expand', truncated: false, read: { rowCount: 1 } }),
        );
        await store.apply({ op: 'expand' } as never);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="expand-fallback"]')).toBeNull();
    });

    it('LA-19: a refused annotate (422) is surfaced with the server message, and nothing is rendered as saved', async () => {
        const { fixture, store, el, inv, button } = create();
        await openInv(store);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();
        inv.appendInvestigationOp.mockReturnValue(refused("'a' is not in the Working Set"));
        const comp = fixture.debugElement.children[0].componentInstance as LinkAnalysisInvestigationComponent;
        comp.note.setValue('burner pattern');
        button('Annotate').click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain(
            "The server refused this step: 'a' is not in the Working Set",
        );
        expect(el.querySelector('[aria-label="Annotations"]')).toBeNull();
        expect(comp.note.value).toBe('burner pattern'); // kept for a retry
    });

    it('D-U9: a link note is sent by its linkId and sealed link notes render', async () => {
        const { fixture, store, el, inv, button } = create();
        await openInv(store);
        fixture.detectChanges();
        const notes = fixture.debugElement.query(By.css('[data-section="annotations"]'))
            .componentInstance as LinkAnalysisInvestigationComponent;
        notes.linkPick.set('lk.YQBiAHZvaWNl');
        notes.linkNote.setValue('shared SIM box');
        fixture.detectChanges();
        button('Annotate link').click();
        await fixture.whenStable();
        expect(inv.appendInvestigationOp).toHaveBeenCalledWith('inv-1', {
            op: 'annotate',
            links: ['lk.YQBiAHZvaWNl'],
            note: 'shared SIM box',
        });

        inv.replayInvestigation.mockReturnValue(
            of({
                workingSet: {
                    ...WS,
                    linkAnnotations: [
                        {
                            source: 'a',
                            target: 'b',
                            kind: 'voice',
                            linkId: 'lk.YQBiAHZvaWNl',
                            step: 3,
                            note: 'shared SIM box',
                        },
                    ],
                },
            }),
        );
        await store.open('inv-1');
        fixture.detectChanges();
        expect(el.querySelector('[aria-label="Link annotations"]')?.textContent).toContain('a → b (voice)');
    });


    describe('delete (LA-INVESTIGATION-DELETE-1)', () => {
        it('without the Incident management capability there is no Delete on the open Investigation', async () => {
            const { fixture, store, el } = create({ canManage: false });
            await openInv(store);
            fixture.detectChanges();
            expect(el.querySelector('[data-testid="delete-investigation"]')).toBeNull();
        });

        it('Delete on the open Investigation confirms, deletes it and drops it', async () => {
            const { fixture, store, el, inv } = create({ confirm: async () => true });
            await openInv(store);
            fixture.detectChanges();
            // Delete lives in the Act header's ⋯ menu (§5.8 block 1)
            (el.querySelector('[data-testid="act-menu"]') as HTMLButtonElement).click();
            fixture.detectChanges();
            (document.querySelector('[data-testid="delete-investigation"]') as HTMLButtonElement).click();
            await fixture.whenStable();
            expect(inv.deleteInvestigation).toHaveBeenCalledWith('inv-1');
            expect(store.active()).toBe(false);
        });
    });

    it('LA-19: coverage names the zero-row days and says per-Collector coverage is not assessed; a 422 is shown', async () => {
        const { fixture, store, el, inv, button } = create();
        await openInv(store);
        fixture.detectChanges();
        const section = el.querySelector('[aria-label="Coverage"]') as HTMLElement;

        button('Check coverage').click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(inv.investigationCoverage).toHaveBeenCalledWith('inv-1');
        expect(section.textContent).toContain('2 of 3 days in the window have zero rows in calls (UTC)');
        expect(section.textContent).toContain('2026-09-02, 2026-09-03');
        expect(section.textContent).not.toContain('2026-09-01');
        expect(section.textContent?.match(/Per-Collector coverage is not assessed for this Dataset\./g)).toHaveLength(
            1,
        );
        expect(section.textContent).toContain('a Dataset row carries no Collector attribution');
        expect(section.textContent).not.toMatch(/no collectors delivered/i);
        expect(section.querySelector('inspecto-alert[variant="error"]')).toBeNull();
        await expectNoA11yViolations(section);

        inv.investigationCoverage.mockReturnValue(
            of({
                ...COVERAGE,
                collectors: {
                    assessed: true,
                    collectors: [
                        { collector: 'north', coveredDays: 3, missingDays: [], complete: true },
                        {
                            collector: 'south',
                            coveredDays: 1,
                            missingDays: ['2026-09-02', '2026-09-03'],
                            complete: false,
                        },
                    ],
                },
            }),
        );
        button('Check coverage').click();
        await fixture.whenStable();
        fixture.detectChanges();
        const rows = Array.from(section.querySelectorAll('tbody tr')).map((r) =>
            Array.from(r.children).map((c) => c.textContent?.trim()),
        );
        expect(rows).toEqual([
            ['north', '3 of 3', 'none'],
            ['south', '1 of 3', '2026-09-02, 2026-09-03'],
        ]);
        expect(section.textContent).not.toContain('not assessed');
        expect(section.querySelector('[aria-label="Per-Collector coverage"]')).not.toBeNull();
        await expectNoA11yViolations(section);

        inv.investigationCoverage.mockReturnValue(refused('coverage needs a bounded window'));
        button('Check coverage').click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(section.textContent).toContain('coverage needs a bounded window');
        expect(section.textContent).not.toContain('zero rows');
        await expectNoA11yViolations(section); // axe, LA-A11Y-AUDIT-1: the refused-coverage state
    });
});

describe('LinkAnalysisInvestigationComponent - queued seed entities (operator 2026-10-10)', { timeout: 45_000 }, () => {
    const SEEDS = [
        { id: 'a', label: 'A', ids: ['acct-a'] },
        { id: 'b', label: 'B', ids: ['acct-b', 'ACCT B'] },
    ];

    it('shows the queue and creates NOTHING until the analyst presses Start', async () => {
        const { fixture, store, el, inv, button } = create();
        store.queueSeeds(SEEDS);
        fixture.detectChanges();
        const queued = el.querySelector('[data-testid="queued-seeds"]') as HTMLElement;
        expect(queued.textContent).toContain('Queued seed entities (2)');
        expect(queued.textContent).toContain('Nothing is created until then');
        expect(button('Start Investigation').textContent).toContain('with 2 queued seeds');
        expect(inv.createInvestigation).not.toHaveBeenCalled();
        expect(inv.appendInvestigationOp).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('Start creates the Investigation, then seeds every queued raw id in ONE step and empties the queue', async () => {
        const { fixture, store, el, inv, button } = create();
        store.queueSeeds(SEEDS);
        fixture.detectChanges();
        penEdit(fixture, penByLabel(el, 'Purpose / legal basis')!, 'warrant 7');
        button('Start Investigation').click();
        await fixture.whenStable();
        expect(inv.createInvestigation).toHaveBeenCalledTimes(1);
        expect(inv.appendInvestigationOp).toHaveBeenCalledTimes(1);
        expect(inv.appendInvestigationOp).toHaveBeenCalledWith('inv-1', {
            op: 'seed',
            ids: ['acct-a', 'acct-b', 'ACCT B'],
        });
        expect(store.queuedSeeds()).toEqual([]);
    });

    it('a queued entity can be removed or the queue cleared before Start', () => {
        const { fixture, store, el, button } = create();
        store.queueSeeds(SEEDS);
        fixture.detectChanges();
        (el.querySelector('[aria-label="Remove A from the queued seeds"]') as HTMLButtonElement).click();
        expect(store.queuedSeeds().map((s) => s.id)).toEqual(['b']);
        fixture.detectChanges();
        button('Clear the queue').click();
        expect(store.queuedSeeds()).toEqual([]);
    });

    it('on an open Investigation the queue waits for an explicit Seed press', async () => {
        const { fixture, store, inv, button } = create();
        await openInv(store);
        store.queueSeeds(SEEDS);
        fixture.detectChanges();
        expect(inv.appendInvestigationOp).not.toHaveBeenCalled();
        button('Seed the queued entities').click();
        await fixture.whenStable();
        expect(inv.appendInvestigationOp).toHaveBeenCalledWith('inv-1', {
            op: 'seed',
            ids: ['acct-a', 'acct-b', 'ACCT B'],
        });
        expect(store.queuedSeeds()).toEqual([]);
    });
});

describe('LinkAnalysisInvestigationComponent - Seed returns to the Working Set (DR-D3)', { timeout: 45_000 }, () => {
    it('a successful Seed of a picked query-graph node draws the Working Set again; a failed one does not', async () => {
        const { fixture, store, inv, button } = create();
        await openInv(store);
        store.showWorkingSet.set(false);
        store.selected.set({ id: 'n1', data: { label: 'acct-a', kind: 'entity' } } as never);
        fixture.detectChanges();
        inv.appendInvestigationOp.mockReturnValueOnce(throwError(() => new Error('refused')));
        button('Seed').click();
        await fixture.whenStable();
        expect(store.showWorkingSet()).toBe(false);
        button('Seed').click();
        await fixture.whenStable();
        expect(inv.appendInvestigationOp).toHaveBeenLastCalledWith(
            'inv-1',
            expect.objectContaining({ op: 'seed', ids: ['acct-a'] }),
        );
        expect(store.showWorkingSet()).toBe(true);
    });

    it('a pending "Investigate a number" asks the purpose, then creates, seeds and expands one degree', async () => {
        const { fixture, el, inv, store } = create();
        const host = fixture.componentInstance as unknown as Host;
        host.pending.set({
            seed: '966501',
            profileId: 'telecom',
            projection: {
                datasetId: 'telecom_links_dataset',
                sourceCol: 'a_msisdn',
                targetCol: 'b_msisdn',
                entityType: 'msisdn',
            },
            timeCol: 'last_seen',
            presets: { minEvents: 2 },
            maxDegree: 4,
        });
        fixture.detectChanges();
        const form = el.querySelector('[data-testid="investigate-pending"]') as HTMLFormElement;
        expect(form.textContent).toContain('Investigate 966501');
        expect(form.textContent).toContain('at least 2 events per link');
        // masking not known to be off: the raw number never goes into the title
        expect(penText(penByLabel(form, 'Title')!)).toBe('Suspect number');
        await expectNoA11yViolations(el);

        // no purpose: nothing is created and the error is on screen
        form.dispatchEvent(new Event('submit'));
        fixture.detectChanges();
        expect(inv.createInvestigation).not.toHaveBeenCalled();
        const required = form.querySelector('[data-testid="investigate-purpose-required"]');
        expect(required?.textContent).toContain('A purpose is required to start an Investigation');
        expect(required?.getAttribute('role')).toBe('alert');
        await fixture.whenStable();
        // the missing purpose is opened for editing, with the caret in it
        expect(form.querySelector('[data-testid="investigate-purpose"]')!.contains(document.activeElement)).toBe(true);
        await expectNoA11yViolations(el);

        const spy = vi.spyOn(store, 'investigateNumber').mockResolvedValue(true);
        penEdit(fixture, form.querySelector<HTMLElement>('[data-testid="investigate-purpose"]')!, 'fraud alert 12');
        form.dispatchEvent(new Event('submit'));
        await fixture.whenStable();
        expect(spy).toHaveBeenCalledWith(
            expect.objectContaining({
                seed: '966501',
                title: 'Suspect number',
                profileId: 'telecom',
                purpose: 'fraud alert 12',
                degrees: 1,
            }),
        );
        fixture.detectChanges();
        expect(host.pending()).toBeNull();
    });

    it('"Expand next degree" shows the degree, the per-degree result, and stops at degree 4', async () => {
        const { fixture, el, store } = create();
        await openInv(store);
        // The fixture log's expand (step 2) admitted nobody, so its rebuilt line would block the ladder; start fresh.
        store.degreeOutcomes.set([]);
        fixture.detectChanges();
        const next = () => el.querySelector('[data-testid="expand-next-degree"]') as HTMLButtonElement;
        expect(el.querySelector('[data-testid="degree-label"]')?.textContent?.trim()).toBe('Degree 0 of 4');
        expect(next().disabled).toBe(false);

        const spy = vi.spyOn(store, 'expandNextDegree').mockResolvedValue(true);
        next().click();
        expect(spy).toHaveBeenCalledWith({ budget: 2000 }, 4);

        store.degreeOutcomes.set([
            { degree: 1, admitted: 3, linksAdded: 4, truncated: true, fanOutCapped: 0, budget: 2000 },
        ]);
        store.workingSet.set({
            ...WS,
            entities: [
                WS.entities[0],
                { id: 'd', type: null, hop: 4, seed: 'a', admittedBy: 3, hidden: false, kept: false },
            ],
        });
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="degree-label"]')?.textContent?.trim()).toBe('Degree 4 of 4');
        expect(next().disabled).toBe(true);
        expect(el.querySelector('[data-testid="degree-blocked"]')?.textContent).toContain('Degree 4 reached');
        expect(el.querySelector('[aria-label="Degree results"]')?.textContent).toContain(
            'Degree 1: 3 new entities, 4 links added. The read stopped at its budget of 2,000 rows',
        );
        await expectNoA11yViolations(el);
    });
});

describe('LinkAnalysisInvestigationComponent - hub visibility and override (telecom S3)', { timeout: 45_000 }, () => {
    const HUB_WS: WorkingSet = {
        entities: [
            { id: 'a', type: null, hop: 0, seed: 'a', admittedBy: 1, hidden: false, kept: false },
            { id: 'b', type: null, hop: 1, seed: 'a', admittedBy: 2, hidden: false, kept: false },
            {
                id: 'hub',
                type: null,
                hop: 1,
                seed: 'a',
                admittedBy: 2,
                hidden: false,
                kept: false,
                highConnectivity: true,
            },
        ],
        links: [
            { source: 'a', target: 'b', kind: 'voice', count: 2, admittedBy: 2 },
            { source: 'a', target: 'hub', kind: 'voice', count: 9, admittedBy: 2 },
        ],
        excluded: [],
        hash: '',
    };
    const stepResult = (step: number) =>
        of({
            step,
            op: 'expand',
            delta: { admitted: [], removed: [], linksAdded: 0, linksRemoved: 0, hidden: [], kept: [], excluded: [] },
            truncated: false,
            workingSet: { entities: 3, links: 2, excluded: 0, hash: '' },
        });

    it('badges a flagged entity with its own sealed contact count when served', async () => {
        const ws: WorkingSet = {
            ...HUB_WS,
            entities: HUB_WS.entities.map((e) => (e.id === 'hub' ? { ...e, contacts: 650 } : e)),
        };
        const { fixture, store, el } = create({ ws });
        await openInv(store);
        fixture.detectChanges();
        const list = el.querySelector('[data-testid="hub-list"]') as HTMLElement;
        expect(list.textContent).toMatch(/High connectivity — 650 contacts( \(threshold [\d,]+\))?, not expanded/);
    });

    it('badges the flagged entity, and "Expand anyway" confirms, then sends expandHubs and says it is recorded', async () => {
        const confirm = vi.fn(async () => true);
        const { fixture, store, el, inv } = create({ ws: HUB_WS, confirm });
        await openInv(store);
        inv.appendInvestigationOp.mockReturnValue(stepResult(3) as never);
        fixture.detectChanges();

        const list = el.querySelector('[data-testid="hub-list"]') as HTMLElement;
        expect(list.textContent).toContain('High connectivity (1)');
        expect(list.textContent).toContain('High connectivity — not expanded');
        // The next degree leaves the hub out and says so.
        expect(el.querySelector('[data-testid="degree-hubs"]')?.textContent).toContain('left out of the next degree');
        await expectNoA11yViolations(el);

        (list.querySelector('button[aria-label="Expand anyway from hub"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(confirm).toHaveBeenCalledTimes(1);
        expect(inv.appendInvestigationOp).toHaveBeenCalledWith(
            'inv-1',
            // D4: the profile's preset budget rides along (it read the server's 2 000 default before)
            expect.objectContaining({ op: 'expand', ids: ['hub'], expandHubs: ['hub'], budget: 2000 }),
        );
        expect(el.querySelector('[data-testid="hub-override-recorded"]')?.textContent).toContain(
            'Override recorded at step 3',
        );
    });

    it('the override confirmation\'s button reads "Expand anyway"', async () => {
        const { fixture, store, el } = create({ ws: HUB_WS, confirm: async () => false });
        await openInv(store);
        fixture.detectChanges();
        const svc = TestBed.inject(InspectoConfirmService) as unknown as {
            confirmDestructive: ReturnType<typeof vi.fn>;
        };
        (el.querySelector('button[aria-label="Expand anyway from hub"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        expect(svc.confirmDestructive).toHaveBeenCalledWith(
            expect.any(String),
            expect.objectContaining({ confirmText: 'Expand anyway', destructive: false }),
        );
    });

    it('D3: after an approved override (and on reload) the hub reads expanded through override, no second offer', async () => {
        const { fixture, store, el, inv } = create({ ws: HUB_WS, confirm: async () => true });
        const override = {
            step: 3,
            kind: 'op' as const,
            op: 'expand' as const,
            params: { ids: ['hub'], expandHubs: ['hub'], hubThreshold: 500 },
            author: 'alice',
            at: '2026-09-23T10:02:00Z',
            undoneBy: null,
            workingSetHash: '',
            text: '3. Expanded through hub.',
        };
        inv.investigationLog.mockReturnValue(of({ ...LOG, entries: [...LOG.entries, override] }) as never);
        inv.workingSetRelation.mockReturnValue(
            of({ rows: [{ entityId: 'hub', contacts: 650 }], total: 1, truncated: false }) as never,
        );
        await openInv(store);
        fixture.detectChanges();
        const list = el.querySelector('[data-testid="hub-list"]') as HTMLElement;
        // threshold from the sealed step, contacts from the Working Set rows — not "more than 500"
        expect(list.textContent).toContain(
            'High connectivity — 650 contacts (threshold 500), expanded through override',
        );
        expect(list.querySelector('button[aria-label="Expand anyway from hub"]')).toBeNull();
    });

    it('a declined confirmation sends nothing', async () => {
        const { fixture, store, el, inv } = create({ ws: HUB_WS, confirm: async () => false });
        await openInv(store);
        fixture.detectChanges();
        (el.querySelector('button[aria-label="Expand anyway from hub"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        expect(inv.appendInvestigationOp).not.toHaveBeenCalled();
    });

    it('"Expand next degree" (the quick action) never passes through a held hub, and says to use Expand… or Expand anyway', async () => {
        const confirm = vi.fn(async () => true);
        const { fixture, store, el, inv } = create({ ws: HUB_WS, confirm });
        await openInv(store);
        inv.appendInvestigationOp.mockReturnValue(stepResult(3) as never);
        fixture.detectChanges();
        const next = () => el.querySelector('[data-testid="expand-next-degree"]') as HTMLButtonElement;

        next().click(); // not opted in: the hub stays out of the frontier, no override, no confirmation
        await fixture.whenStable();
        expect(confirm).not.toHaveBeenCalled();
        const first = inv.appendInvestigationOp.mock.calls[0] as unknown as [string, Record<string, unknown>];
        expect(first[1]['ids']).toEqual(['b']);
        expect(first[1]['includeHubs']).toBeUndefined();
        store.degreeOutcomes.set([]); // the stub admitted nothing; clear it so the degree is not "blocked"
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="degree-hubs"]')?.textContent).toContain('Untick “Hold supernodes” in Expand…');
    });

    it('badges a hub with the sealed count its step read answered when the Working Set row has none', async () => {
        const { fixture, store, el } = create({ ws: HUB_WS });
        await openInv(store);
        store.hubContacts.set(new Map([['hub', 650]]));
        fixture.detectChanges();
        const list = el.querySelector('[data-testid="hub-list"]') as HTMLElement;
        expect(list.textContent).toContain('650 contacts');
        expect(list.textContent).not.toContain('more than');
    });

    it('a held degree says it is waiting once; the decision replaces it', async () => {
        const { fixture, store, el } = create({ ws: HUB_WS });
        await openInv(store);
        const held = {
            degree: 2,
            admitted: 0,
            linksAdded: 0,
            truncated: false,
            fanOutCapped: 0,
            awaitingApproval: 'p2',
        };
        store.degreeOutcomes.set([held]);
        fixture.detectChanges();
        const next = el.querySelector('[data-testid="expand-next-degree"]') as HTMLButtonElement;
        expect(next.disabled).toBe(true);
        expect(el.querySelector('[data-testid="degree-blocked"]')).toBeNull();
        expect(el.textContent?.match(/waiting for approval/g)?.length).toBe(1);
        await expectNoA11yViolations(el);

        store.degreeOutcomes.set([{ ...held, decision: 'approved', admitted: 0 }]);
        fixture.detectChanges();
        expect(el.querySelector('[aria-label="Degree results"]')?.textContent).toContain(
            'Degree 2: approved (request p2) — nothing new was found.',
        );
        expect(el.textContent).not.toContain('waiting for approval');
    });

    it('"Expand…" asks the screen for the Expand dialog (the read settings live there, §5.4)', async () => {
        const { fixture, store, el } = create({ ws: HUB_WS });
        await openInv(store);
        fixture.detectChanges();
        const asked = vi.fn();
        (fixture.debugElement.query(By.directive(LinkAnalysisInvestigationComponent)).componentInstance as LinkAnalysisInvestigationComponent).expandRequested.subscribe(asked);
        (el.querySelector('[data-testid="expand-dialog-open"]') as HTMLButtonElement).click();
        expect(asked).toHaveBeenCalledTimes(1);
        expect(el.textContent).not.toContain('Advanced expand settings');
    });

    it('nothing seeded: "Expand…" is locked with "Expand next degree", and the reason shows (UI test 2026-10-29)', async () => {
        const { fixture, store, el } = create({ ws: { ...WS, entities: [], links: [] } });
        await openInv(store);
        fixture.detectChanges();
        expect((el.querySelector('[data-testid="expand-dialog-open"]') as HTMLButtonElement).disabled).toBe(true);
        expect((el.querySelector('[data-testid="expand-next-degree"]') as HTMLButtonElement).disabled).toBe(true);
        expect(el.querySelector('[data-testid="degree-blocked"]')?.textContent).toContain('Seed an entity first.');
    });
});

describe('Neighbours (LA-EXPAND-PICK-NEIGHBOURS-1)', { timeout: 45_000 }, () => {
    const stepResult = (step: number) =>
        of({
            step,
            op: 'expand',
            delta: { admitted: [], removed: [], linksAdded: 0, linksRemoved: 0, hidden: [], kept: [], excluded: [] },
            truncated: false,
            workingSet: { entities: 3, links: 2, excluded: 0, hash: '' },
        });
    it('lists the neighbours of the selected entity strongest first, and Add selected sends ONE restricted expand', async () => {
        const { fixture, store, el, inv } = create();
        (inv as unknown as { neighbourList: unknown }).neighbourList = vi.fn(() =>
            of({
                neighbours: [
                    { id: 'c', count: 9, kinds: ['call'] },
                    { id: 'b', count: 3, kinds: ['call'] },
                ],
                total: 2,
                offset: 0,
                limit: 50,
                hasMore: false,
            }),
        );
        await openInv(store);
        inv.appendInvestigationOp.mockReturnValue(stepResult(3) as never);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();

        (el.querySelector('[data-testid="neighbours-toggle"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        fixture.detectChanges();
        const rows = Array.from(el.querySelectorAll('[data-testid="neighbour-list"] li')).map((li) =>
            (li.textContent ?? '').replace(/\s+/g, ' ').trim(),
        );
        expect(rows).toHaveLength(2);
        expect(rows[0]).toContain('c');
        expect(rows[0]).toContain('9 events');
        expect((el.querySelector('[data-testid="neighbours-add-selected"]') as HTMLButtonElement).disabled).toBe(true);

        const boxes = Array.from(el.querySelectorAll('[data-testid="neighbour-list"] input[type="checkbox"]'));
        (boxes[1] as HTMLInputElement).click(); // b
        fixture.detectChanges();
        (el.querySelector('[data-testid="neighbours-add-selected"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(inv.appendInvestigationOp).toHaveBeenCalledWith(
            'inv-1',
            expect.objectContaining({ op: 'expand', ids: ['a'], only: ['b'] }),
        );
        await expectNoA11yViolations(el);
    });

    it('is offered only for one selected Working Set entity, and Add is disabled without the capability', async () => {
        const { fixture, store, el } = create({ canManage: false });
        await openInv(store);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="neighbours-section"]')).toBeNull();
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="neighbours-section"]')).not.toBeNull();
    });
});

describe('LinkAnalysisInvestigationComponent - the Act panel and its sections (LA-STUDIO-UI-REORG-1 §5.8/§5.9)', { timeout: 45_000 }, () => {
    const act = (el: HTMLElement) =>
        el.querySelector('inspecto-link-analysis-investigation:not([data-section])') as HTMLElement;
    const part = (el: HTMLElement, s: InvestigationSection) =>
        el.querySelector(`inspecto-link-analysis-investigation[data-section="${s}"]`) as HTMLElement;

    it('draws the eight blocks in order with no fold, and keeps setup, governance and dock parts out of it', async () => {
        const { fixture, store, el } = create();
        await openInv(store);
        store.selected.set({ id: 'a', data: { label: 'a' } } as never);
        fixture.detectChanges();
        const panel = act(el);
        expect(panel.querySelector('la-fold')).toBeNull();
        const order = ['act-header', 'degrees', 'act-selected', 'scope-list', 'act-more-steps', 'act-last-steps'].map(
            (id) => panel.querySelector(`[data-testid="${id}"]`),
        );
        expect(order.every(Boolean)).toBe(true);
        for (let i = 1; i < order.length; i++) {
            expect(order[i - 1]!.compareDocumentPosition(order[i]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        }
        for (const gone of ['Op log', 'Coverage', 'Members', 'Pin to a Widget', 'Entity Lists', 'Open an Investigation']) {
            expect(panel.querySelector(`la-fold[title="${gone}"], section[aria-label="${gone}"]`)).toBeNull();
        }
        // UIR-A2: Value Measures live only in the algorithm catalogue
        expect(el.querySelector('inspecto-link-analysis-value-measures')).toBeNull();
        expect(part(el, 'settings').querySelector('la-fold[title="Members"]')).not.toBeNull();
        expect(part(el, 'oplog').querySelector('section[aria-label="Op log"]')).not.toBeNull();
        await expectNoA11yViolations(panel);
    });

    it('LA-REORG-SETTINGS-GAPS-1: This Investigation opens with a General section (title, purpose, time, lineage, pack, Delete)', async () => {
        const { fixture, store, el } = create();
        await openInv(store);
        fixture.detectChanges();
        const settings = part(el, 'settings');
        const folds = Array.from(settings.querySelectorAll('la-fold')).map((f) => f.getAttribute('title'));
        expect(folds[0]).toBe('General');
        const general = settings.querySelector('[data-testid="inv-settings-general"]')!;
        const text = general.textContent!.replace(/\s+/g, ' ');
        expect(text).toContain('Burners');
        expect(text).toContain('calls');
        expect(text).toContain('none (timeless)');
        expect(text).toContain('Original');
        expect(text).toContain("The Space's pack");
        expect(settings.querySelector('[data-testid="inv-settings-delete"]')).not.toBeNull();
        await expectNoA11yViolations(settings);
    });

    describe('rename and restate the purpose (LA-INVESTIGATION-RENAME-1)', () => {
        const pen = (el: HTMLElement, id: string) =>
            part(el, 'settings').querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

        it('a lead edits Title and Purpose in place; the header, the Act panel and the remembered ref show the new name', async () => {
            const { fixture, store, el, inv } = create({ role: 'lead' });
            await openInv(store);
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();
            expect(inv.investigationMembers).toHaveBeenCalledWith('inv-1');
            expect(penText(pen(el, 'inv-settings-title')!)).toBe('Burners');
            expect(part(el, 'settings').querySelector('[data-testid="inv-settings-details-readonly"]')).toBeNull();

            penEdit(fixture, pen(el, 'inv-settings-title')!, 'Ring around b');
            await fixture.whenStable();
            fixture.detectChanges();
            expect(inv.editInvestigation).toHaveBeenCalledWith('inv-1', { title: 'Ring around b' });
            expect(store.header()?.title).toBe('Ring around b');
            expect(store.refs().find((r) => r.id === 'inv-1')?.title).toBe('Ring around b');
            expect(act(el).querySelector('[data-testid="act-header"]')!.textContent).toContain('Ring around b');

            penEdit(fixture, pen(el, 'inv-settings-purpose')!, 'Fraud referral FR-10');
            await fixture.whenStable();
            fixture.detectChanges();
            expect(inv.editInvestigation).toHaveBeenLastCalledWith('inv-1', { purpose: 'Fraud referral FR-10' });
            expect(store.header()?.purpose).toBe('Fraud referral FR-10');
            await expectNoA11yViolations(part(el, 'settings'));
        });

        it('a blank title is refused in place and never sent', async () => {
            const { fixture, store, el, inv } = create({ role: 'lead' });
            await openInv(store);
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();
            penEdit(fixture, pen(el, 'inv-settings-title')!, '   ');
            refresh(fixture);
            expect(penError(pen(el, 'inv-settings-title')!)).toBe('A title is required.');
            expect(inv.editInvestigation).not.toHaveBeenCalled();
        });

        it('a server refusal shows why and puts the name in force back', async () => {
            const { fixture, store, el, inv } = create({ role: 'lead' });
            inv.editInvestigation.mockReturnValue(refused("'title' is at most 200 chars"));
            await openInv(store);
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();
            penEdit(fixture, pen(el, 'inv-settings-title')!, 'Too long');
            await fixture.whenStable();
            refresh(fixture);
            expect(part(el, 'settings').textContent).toContain("'title' is at most 200 chars");
            expect(penText(pen(el, 'inv-settings-title')!)).toBe('Burners');
            expect(store.header()?.title).toBe('Burners');
        });

        it('a member who is not a lead, or a caller without the capability, reads them with the reason', async () => {
            const analyst = create({ role: 'analyst' });
            await openInv(analyst.store);
            analyst.fixture.detectChanges();
            await analyst.fixture.whenStable();
            analyst.fixture.detectChanges();
            const settings = part(analyst.el, 'settings');
            expect(settings.querySelector('[data-testid="inv-settings-title"]')).toBeNull();
            expect(settings.querySelector('[data-testid="inv-settings-general"]')!.textContent).toContain('Burners');
            expect(settings.querySelector('[data-testid="inv-settings-details-readonly"]')!.textContent).toContain(
                'Only a lead of this Investigation',
            );
            TestBed.resetTestingModule();

            const nocap = create({ role: 'lead', canManage: false });
            await openInv(nocap.store);
            nocap.fixture.detectChanges();
            await nocap.fixture.whenStable();
            nocap.fixture.detectChanges();
            const s2 = part(nocap.el, 'settings');
            expect(s2.querySelector('[data-testid="inv-settings-title"]')).toBeNull();
            expect(s2.querySelector('[data-testid="inv-settings-details-readonly"]')!.textContent).toContain(
                'Incident management capability',
            );
        });
    });

    it('lists the last steps newest first; Open the Op log and Settings… ask the screen', async () => {
        const { fixture, store, el } = create();
        await openInv(store);
        fixture.detectChanges();
        const steps = Array.from(act(el).querySelectorAll('[data-testid="act-last-steps"] li')).map((li) =>
            li.textContent?.trim().slice(0, 1),
        );
        expect(steps).toEqual(['2', '1']);
        (act(el).querySelector('[data-testid="open-op-log"]') as HTMLButtonElement).click();
        expect(fixture.componentInstance.dockAsked).toBe('oplog');
        const cmp = fixture.debugElement.query(By.directive(LinkAnalysisInvestigationComponent))
            .componentInstance as LinkAnalysisInvestigationComponent;
        cmp.settingsRequested.emit();
        expect(fixture.componentInstance.settingsAsked).toBe(true);
    });

    it('draws no dock part while no Investigation is open', () => {
        const { el } = create();
        for (const s of ['oplog', 'ws-rows', 'coverage', 'annotations', 'excluded'] as InvestigationSection[]) {
            expect(part(el, s).querySelector('section')!.children.length).toBe(0);
        }
    });
});
