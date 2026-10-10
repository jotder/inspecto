import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { EventsService, ObjectsService, OperationalObject, SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { AnomalyScoresService } from 'app/inspecto/api/anomaly-scores.service';
import { RiskScoresService } from 'app/inspecto/api/risk-scores.service';
import { ObjectDetailComponent } from './object-detail.component';

const CASE: OperationalObject = {
    id: 'obj-9',
    objectType: 'CASE',
    title: 'Fraud ring',
    description: 'Suspicious voucher redemptions',
    status: 'INVESTIGATING',
    severity: 'CRITICAL',
    correlationId: 'corr-7',
    createdAt: 1,
    updatedAt: 2,
    closedAt: 0,
};

function create(overrides: Partial<Record<keyof ObjectsService, unknown>> = {}, extra: unknown[] = []) {
    const api = {
        get: () => of(CASE),
        comments: vi.fn(() => of([])),
        addComment: vi.fn(() => of({})),
        transition: vi.fn(() => of({ ...CASE, status: 'ESCALATED' })),
        graph: () => of({ root: CASE.id, depth: 2, nodes: [], edges: [] }),
        attachments: () => of([]),
        applyRca: vi.fn(() => of([])),
        workflow: vi.fn(() => throwError(() => new Error('no workflow endpoint'))), // → the built-in fallback
        ...overrides,
    } as unknown as ObjectsService;
    TestBed.configureTestingModule({
        imports: [ObjectDetailComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ObjectsService, useValue: api },
            { provide: EventsService, useValue: { search: () => of([]) } },
            { provide: MatDialog, useValue: {} },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } },
            // A real router (RouterLink needs createUrlTree); at the root URL listBase falls back to 'incidents'.
            provideRouter([]),
            ...(extra as never[]),
            // ⚠ `paramMap` as an OBSERVABLE, not just the snapshot: the component tracks param changes,
            // because navigating from the graph tab to a neighbour reuses this same route config and
            // ngOnInit does not run again. A snapshot-only stub is what the component used to read.
            {
                provide: ActivatedRoute,
                useValue: {
                    snapshot: { paramMap: new Map([['id', 'obj-9']]) },
                    paramMap: of(new Map([['id', 'obj-9']])),
                },
            },
        ],
    });
    // EDG-01 cell 6: SessionService.eventsEnabled defaults to FALSE (the absent-module state),
    // where the events section explains itself and no API call is made. Arm it for the
    // INSTALLED path these specs assert.
    TestBed.inject(SessionService).eventsEnabled.set(true);
    TestBed.inject(SessionService).opsEnabled.set(true); // EDG-01 cell 7: the whole pane is gated
    const fixture = TestBed.createComponent(ObjectDetailComponent);
    fixture.detectChanges(); // ngOnInit → loadObject()
    return { fixture, api };
}

describe('ObjectDetailComponent', () => {
    it('loads the object and derives its legal transitions', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        expect(c.obj()).toEqual(CASE);
        expect(c.actions).toEqual(['escalate', 'resolve']); // CASE @ INVESTIGATING
        expect(c.listBase).toBe('incidents'); // root test URL → fallback list base
        expect(c.listLabel).toBe('Incidents');
    });

    /** Browser pass 2026-09-28: the page offered no action at all on an Incident — Resolve included. */
    it("offers an Incident's actions from its served workflow, and from the built-in one without it", () => {
        const INCIDENT: OperationalObject = { ...CASE, objectType: 'INCIDENT', status: 'IDENTIFIED' };
        const served = {
            type: 'INCIDENT',
            initial: 'IDENTIFIED',
            states: ['IDENTIFIED', 'RESOLVED'],
            terminal: [],
            transitions: [{ from: 'IDENTIFIED', to: 'RESOLVED', action: 'resolve' }],
        };
        const { fixture } = create({ get: () => of(INCIDENT), workflow: vi.fn(() => of(served)) });
        const c = fixture.componentInstance;
        expect(c.actions).toEqual(['resolve']); // the served (possibly TOON-overridden) lifecycle wins
        c.workflowDef.set(null);
        expect(c.actions).toEqual(['accept', 'resolve', 'archive']); // the server's built-in, as the fallback
        c.obj.set({ ...INCIDENT, status: 'RESOLVED' });
        expect(c.actions).toEqual(['archive', 'reopen']);
    });

    /** P7 ALERT retirement: a stored row of a type the build does not know is read-only and says so in text. */
    it('shows an inert (unknown-type) object with an "Unknown type" text badge and no actions', () => {
        const inert: OperationalObject = {
            ...CASE,
            objectType: 'ALERT',
            status: 'OPEN',
            inert: true,
            diagnostic: 'type ALERT is not installed/known: left untouched',
        };
        const { fixture } = create({ get: () => of(inert) });
        const c = fixture.componentInstance;
        expect(c.actions).toEqual([]); // nothing to do to it: every write answers 409
        const badge = fixture.nativeElement.querySelector('[data-testid="inert-badge"]') as HTMLElement;
        expect(badge.textContent?.trim()).toBe('Unknown type');
        expect(badge.getAttribute('title')).toBe('type ALERT is not installed/known: left untouched');
        const rca = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
            (b) => b.textContent?.includes('Apply RCA'),
        );
        expect(rca?.disabled).toBe(true); // the one always-visible write is disabled too
    });

    /** MODULE-REORG-P7: the Action Requests panel follows `bootstrap.features.actionRequests`, not `ops`. */
    it('gates the Action Requests panel on features.actionRequests', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        expect(c.actionRequestsEnabled()).toBe(false); // the default (absent-module) state
        TestBed.inject(SessionService).features.set({ ops: true, actionRequests: true });
        expect(c.actionRequestsEnabled()).toBe(true);
        TestBed.inject(SessionService).features.set({ ops: true, actionRequests: false });
        expect(c.actionRequestsEnabled()).toBe(false);
    });

    it('offers "what happened" only when the object carries a correlation id', () => {
        const { fixture } = create();
        expect(
            fixture.nativeElement.querySelector('inspecto-ai-status button[aria-label="What happened to obj-9"]'),
        ).toBeTruthy();

        // Same fixture, no correlation id: nothing the timeline could be addressed by.
        fixture.componentInstance.obj.set({ ...CASE, correlationId: undefined });
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('inspecto-ai-status')).toBeNull();
    });

    it('shows the server-stamped SLA breach in the header, with no axe violations', async () => {
        const breached = { ...CASE, objectType: 'INCIDENT', attributes: { dueAt: '5', slaBreachedAt: '6' } };
        const { fixture } = create({ get: () => of(breached) });
        fixture.detectChanges();
        const badges = [...fixture.nativeElement.querySelectorAll('[data-testid="sla-badge"]')] as HTMLElement[];
        expect(badges.map((b) => b.textContent?.trim())).toEqual(['SLA breached']);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the entity anomaly panel on an Incident raised over an Anomaly Score', async () => {
        const incident = {
            ...CASE,
            objectType: 'INCIDENT',
            attributes: { 'key.model': 'usage', 'key.entity_key': 'masked:0123456789abcdef' },
        };
        const anomalyLatest = vi.fn(() =>
            of({
                model: 'usage',
                entityType: 'subscriber',
                entityKey: 'masked:0123456789abcdef',
                keyMasked: true,
                score: 91,
                band: 'high',
                elevatedThreshold: 60,
                highThreshold: 80,
                periodStart: '2026-10-09',
                modelVersion: 'v',
                runId: 'r',
                scoredAt: 't',
                features: [{ feature: 'data', label: 'Data MB', contribution: 1, reason: 'data MB 4 812 vs 310' }],
                history: [],
            }),
        );
        const { fixture } = create({ get: () => of(incident) }, [
            { provide: AnomalyScoresService, useValue: { latest: anomalyLatest } },
            { provide: RiskScoresService, useValue: { latest: () => throwError(() => ({ status: 404 })) } },
        ]);
        await fixture.whenStable();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(anomalyLatest).toHaveBeenCalledWith('usage', 'masked:0123456789abcdef');
        expect(el.querySelector('inspecto-anomaly-panel section')?.textContent).toContain('data MB 4 812 vs 310');
        expect(el.querySelector('inspecto-risk-score-panel section')).toBeNull();
    });

    it('a transition replaces the object in place', () => {
        const { fixture, api } = create();
        const c = fixture.componentInstance;
        c.transition('escalate');
        expect(api.transition).toHaveBeenCalledWith('obj-9', 'escalate');
        expect(c.obj()?.status).toBe('ESCALATED');
    });

    it("names an Incident's missing resolution pattern instead of opening a resolve the server refuses", () => {
        const { fixture, api } = create({ get: () => of({ ...CASE, objectType: 'INCIDENT', status: 'IDENTIFIED' }) });
        const toastr = TestBed.inject(ToastrService) as unknown as { warning: ReturnType<typeof vi.fn> };
        fixture.componentInstance.transition('resolve'); // MatDialog is a bare {} here: an open() would throw
        expect(toastr.warning.mock.calls[0][0]).toContain('timeline');
        expect(api.addComment).not.toHaveBeenCalled();
        expect(api.transition).not.toHaveBeenCalled();
    });

    it('blocks an empty comment and submits a valid one', () => {
        const { fixture, api } = create();
        const c = fixture.componentInstance;
        c.addComment();
        expect(api.addComment).not.toHaveBeenCalled();
        c.commentForm.setValue({ body: 'triaged' });
        c.addComment();
        expect(api.addComment).toHaveBeenCalledWith('obj-9', 'triaged');
        expect(api.comments).toHaveBeenCalled();
    });

    it('degrades to not-found when the object load fails', () => {
        const { fixture } = create({ get: () => throwError(() => ({ status: 404 })) });
        expect(fixture.componentInstance.obj()).toBeNull();
        expect(fixture.componentInstance.loading()).toBe(false);
    });

    it('builds the member timeline: CONTAINS members merged, comments newest-first, attributed', () => {
        const graph = {
            root: 'obj-9',
            depth: 1,
            nodes: [
                { id: 'obj-9', objectType: 'CASE', title: 'Fraud ring', status: 'INVESTIGATING' },
                { id: 'inc-1', objectType: 'INCIDENT', title: 'Redemption spike', status: 'OPEN' },
                { id: 'inc-2', objectType: 'INCIDENT', title: 'Geo anomaly', status: 'OPEN' },
                { id: 'other', objectType: 'TASK', title: 'Unrelated', status: 'OPEN' },
            ],
            edges: [
                { from: 'obj-9', to: 'inc-1', relationship: 'CONTAINS' },
                { from: 'obj-9', to: 'inc-2', relationship: 'contains' },
                { from: 'obj-9', to: 'other', relationship: 'RELATED' },
            ],
        };
        const commentsById: Record<string, unknown[]> = {
            'inc-1': [{ id: 'c1', author: 'alice', body: 'first', createdAt: 100 }],
            'inc-2': [{ id: 'c2', author: 'bob', body: 'later', createdAt: 300 }],
        };
        const { fixture } = create({
            graph: () => of(graph),
            comments: vi.fn((mid: string) => of(commentsById[mid] ?? [])),
        });
        const c = fixture.componentInstance;
        c.loadMemberTimeline();
        // Only the two CONTAINS members (not the RELATED alert) contribute.
        expect(c.members().map((m) => m.id)).toEqual(['inc-1', 'inc-2']);
        expect(c.memberTimeline().map((t) => t.body)).toEqual(['later', 'first']); // newest-first
        expect(c.memberTimeline()[0].memberTitle).toBe('Geo anomaly');
        expect(c.memberTimelineLoaded()).toBe(true);
    });

    it('member timeline empty-states when the object contains nothing', () => {
        const { fixture } = create({
            graph: () =>
                of({
                    root: 'obj-9',
                    depth: 1,
                    nodes: [{ id: 'obj-9', objectType: 'CASE', title: 'X', status: 'OPEN' }],
                    edges: [],
                }),
        });
        const c = fixture.componentInstance;
        c.loadMemberTimeline();
        expect(c.members()).toEqual([]);
        expect(c.memberTimeline()).toEqual([]);
        expect(c.memberTimelineLoaded()).toBe(true);
    });

    it('offers Link Analysis on a CASE, carrying the id so the analysis can be saved back onto it', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const link = Array.from(fixture.nativeElement.querySelectorAll('a,button')).find((e) =>
            (e as HTMLElement).textContent?.includes('Link Analysis'),
        ) as HTMLAnchorElement | undefined;
        expect(link).toBeTruthy();
        // The href is the contract the Case page and Link Analysis share: `?case=<id>`, which the pane
        // reads and does NOT strip, so the deep link survives a reload and a bookmark.
        expect(link!.getAttribute('href')).toBe('/studio/link-analysis?case=obj-9');
    });

    it('does NOT offer Link Analysis on an INCIDENT — there is no Case-scoped working set to open', () => {
        const { fixture } = create({ get: () => of({ ...CASE, objectType: 'INCIDENT' }) });
        fixture.detectChanges();
        const link = Array.from(fixture.nativeElement.querySelectorAll('a,button')).find((e) =>
            (e as HTMLElement).textContent?.includes('Link Analysis'),
        );
        expect(link).toBeUndefined();
    });

    it('renders the overview with no a11y violations', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
