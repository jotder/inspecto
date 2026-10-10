import {
    ChangeDetectionStrategy,
    Component,
    computed,
    DestroyRef,
    inject,
    OnInit,
    signal,
    ViewEncapsulation,
} from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { ToastrService } from 'ngx-toastr';
import {
    LINK_ANALYSIS_ROUTE,
    investigableEntityOf,
    investigateQueryParams,
} from '@inspecto/link-analysis/link-analysis/investigate-number';
import {
    apiErrorMessage,
    EventRow,
    EventsService,
    LensService,
    SessionService,
    NodeKind,
    ObjectGraph,
    ObjectGraphNode,
    ObjectNote,
    ObjectsService,
    OperationalObject,
    WorkflowDef,
} from 'app/inspecto/api';
import { AiStatusComponent } from 'app/inspecto/ai-assist/ai-status.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { fmtDateTime } from 'app/inspecto/grid';
import { G6GraphData } from 'app/inspecto/graph/catalog-graph';
import { GraphViewComponent } from 'app/inspecto/graph/graph-view.component';
import { ObjectLinkDialog } from './object-link.dialog';
import { ActionRequestsPanelComponent } from './action-requests-panel.component';
import { ImpactPanelComponent } from './impact-panel.component';
import { RiskScorePanelComponent } from 'app/inspecto/components/risk-score-panel.component';
import { AnomalyPanelComponent } from 'app/inspecto/components/anomaly-panel.component';
import { RunbookPanelComponent } from 'app/inspecto/components/runbook-panel.component';
import { ResolveDialog, ResolveDialogData, ResolveResult } from './resolve.dialog';
import { postmortemGaps, slaBadges } from './mail-model';

type TabKey = 'overview' | 'graph' | 'timeline' | 'events' | 'comments' | 'attachments';

/** One entry in the auto member-timeline: a member's comment, tagged with the member it came from. */
interface MemberTimelineEntry {
    memberId: string;
    memberTitle: string;
    memberType: string;
    author: string;
    body: string;
    createdAt: number;
}

/**
 * Operational-object detail (Phase 2–4) — one object with its lifecycle actions, its correlation
 * graph (reusing the catalog G6 view fed by GET /objects/{id}/graph), and its append-only note thread
 * (comments + attachment references), plus a one-click RCA skeleton. Reused for {@code /cases/:id} and
 * {@code /incidents/:id}; type-agnostic (it reads the object's own {@code objectType}).
 */
@Component({
    selector: 'app-object-detail',
    standalone: true,
    imports: [
        RouterLink,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTabsModule,
        MatTooltipModule,
        ReactiveFormsModule,
        AiStatusComponent,
        GraphViewComponent,
        InspectoPageHeaderComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
        ImpactPanelComponent,
        ActionRequestsPanelComponent,
        RiskScorePanelComponent,
        AnomalyPanelComponent,
        RunbookPanelComponent,
    ],
    templateUrl: './object-detail.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class ObjectDetailComponent implements OnInit {
    private api = inject(ObjectsService);
    private eventsApi = inject(EventsService);
    private session = inject(SessionService);
    /** The lifecycle buttons ride `POST /objects/{id}/transition` — `canWorkIncidents` (operator, 2026-09-26). */
    private canWork = inject(LensService).canWorkIncidents;

    /**
     * `bootstrap.features.events` — the correlation timeline reads the optional `inspecto-events` module
     * (EDITIONS CP-13, EDG-01 cell 6). False on Personal, where the Events tab explains itself rather than
     * showing "no events recorded", which would be a lie about the data.
     */
    readonly eventsEnabled = this.session.eventsEnabled;
    /**
     * `bootstrap.features.ops` — this pane IS an operational object (EDITIONS CP-11, EDG-01 cell 7), so
     * unlike `eventsEnabled` above (which degrades one tab) this gates the whole detail view.
     */
    readonly opsEnabled = this.session.opsEnabled;
    /**
     * `bootstrap.features.actionRequests` - the optional `inspecto-action-requests` module registered /action-requests*
     * (MODULE-REORG-P7). Without it the pane's Action Requests panel is not rendered (its calls would all be 503).
     */
    readonly actionRequestsEnabled = computed(() => this.session.features()['actionRequests'] === true);
    private route = inject(ActivatedRoute);
    private destroyRef = inject(DestroyRef);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);

    readonly id = signal('');
    readonly obj = signal<OperationalObject | null>(null);
    /** The server-stamped SLA due / breached facts (ASSURE-WORKFLOW-SLA-1). */
    /**
     * "Investigate in Link Analysis": the entity a keyed Alert Rule's `key.<column>` attribute names (an MSISDN), as the
     * deep link's query params; null when the record carries none — then no link is offered.
     */
    readonly investigateParams = computed(() => {
        const e = investigableEntityOf(this.obj()?.attributes);
        return e ? investigateQueryParams(e.seed, e.entityType) : null;
    });
    readonly linkAnalysisRoute = LINK_ANALYSIS_ROUTE;
    readonly sla = computed(() => (this.obj() ? slaBadges(this.obj()!) : []));
    readonly loading = signal(false);

    readonly tabs: { id: TabKey; label: string }[] = [
        { id: 'overview', label: 'Overview' },
        { id: 'graph', label: 'Graph' },
        { id: 'timeline', label: 'Member timeline' },
        { id: 'events', label: 'Events' },
        { id: 'comments', label: 'Comments' },
        { id: 'attachments', label: 'Attachments' },
    ];
    /** Signal, not a plain field: applyRca() sets it from a subscribe callback to jump to Comments,
     * which zonelessly never re-rendered the tab group. Two-way `[(selectedIndex)]` writes signals. */
    readonly selectedIndex = signal(0);
    get activeTab(): TabKey {
        return this.tabs[this.selectedIndex()].id;
    }

    readonly comments = signal<ObjectNote[]>([]);
    readonly attachments = signal<ObjectNote[]>([]);
    readonly relatedEvents = signal<EventRow[]>([]);
    readonly eventsLoaded = signal(false);
    readonly g6 = signal<G6GraphData | null>(null);

    /** Member objects (depth-1 CONTAINS children) + their merged comment timeline. */
    readonly members = signal<ObjectGraphNode[]>([]);
    readonly memberTimeline = signal<MemberTimelineEntry[]>([]);
    readonly memberTimelineLoaded = signal(false);

    readonly commentForm: FormGroup = this.fb.group({
        body: ['', Validators.required],
    });
    readonly attachForm: FormGroup = this.fb.group({
        name: ['', Validators.required],
        uri: ['', Validators.required],
    });

    readonly fmt = fmtDateTime;

    /**
     * The effective lifecycle of the object's type (`GET /workflows/{type}`, possibly TOON-overridden); `null`
     * until it loads or when it cannot, and then {@link TRANSITIONS} (the built-in defaults) answers.
     */
    readonly workflowDef = signal<WorkflowDef | null>(null);

    /**
     * Fallback legal next actions from the current status, per object type (backend re-validates) — the
     * servers' built-in lifecycles. ⚠ The INCIDENT row once named states the Incident workflow does not have
     * (OPEN/ASSIGNED/IN_PROGRESS), so this page offered NO action — Resolve included — on any Incident
     * (browser pass 2026-09-28); the served workflow is now the source, this only its fallback.
     */
    private static readonly TRANSITIONS: Record<string, Record<string, string[]>> = {
        INCIDENT: {
            IDENTIFIED: ['accept', 'resolve', 'archive'],
            DIAGNOSING: ['resolve', 'archive'],
            RESOLVED: ['archive', 'reopen'],
            ARCHIVED: ['reopen'],
        },
        CASE: {
            OPEN: ['investigate'],
            INVESTIGATING: ['escalate', 'resolve'],
            ESCALATED: ['resolve'],
            RESOLVED: ['close'],
        },
    };

    get actions(): string[] {
        if (!this.obj() || !this.canWork() || this.obj().inert) return []; // an inert (unknown-type) object is read-only
        const status = (this.obj().status ?? '').toUpperCase();
        const wf = this.workflowDef();
        if (wf?.type === this.obj().objectType)
            return [...new Set(wf.transitions.filter((t) => t.from === status).map((t) => t.action))];
        return ObjectDetailComponent.TRANSITIONS[this.obj().objectType]?.[status] ?? [];
    }

    /** The object's attributes as display rows. */
    get attributeRows(): { key: string; value: string }[] {
        const a = this.obj()?.attributes ?? {};
        return Object.keys(a).map((k) => ({ key: k, value: a[k] }));
    }

    ngOnInit(): void {
        // ⚠ paramMap, not the snapshot. `onNodeClick` navigates to a SIBLING of this same route config
        // (`:id` in both incidents.routes and cases.routes), which Angular REUSES rather than recreating,
        // so ngOnInit does not run again. Read once from the snapshot, `this.id()` stayed on the previous
        // object: the URL said INC-2 while the whole page still showed INC-1 — and transition(),
        // addComment() and applyRca() all post to `this.id()`, so an action the operator took believing
        // they were looking at INC-2 mutated INC-1. Stale render is the mild half of that bug.
        this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
            const next = params.get('id') ?? '';
            if (next === this.id()) return;
            this.id.set(next);
            // Per-object caches, or the new object shows the previous one's comments and timeline until
            // each tab happens to refetch.
            this.obj.set(null);
            this.comments.set([]);
            this.relatedEvents.set([]);
            this.eventsLoaded.set(false);
            this.loadObject();
        });
    }

    loadObject(): void {
        this.loading.set(true);
        this.api.get(this.id()).subscribe({
            next: (o) => {
                this.obj.set(o);
                this.loading.set(false);
                if (this.workflowDef()?.type !== o.objectType)
                    this.api.workflow(o.objectType).subscribe({
                        next: (wf) => this.workflowDef.set(wf),
                        error: () => this.workflowDef.set(null), // the built-in fallback answers
                    });
                // The active tab may have been opened before the object existed; its loader bailed out
                // rather than latching an empty result, so drive it now that there is something to read.
                this.onTabChange();
            },
            error: () => {
                this.obj.set(null);
                this.loading.set(false);
                this.toastr.error(`Object ${this.id()} not found`);
            },
        });
    }

    onTabChange(): void {
        if (this.activeTab === 'graph') this.loadGraph();
        else if (this.activeTab === 'timeline') this.loadMemberTimeline();
        else if (this.activeTab === 'events') this.loadEvents();
        else if (this.activeTab === 'comments') this.loadComments();
        else if (this.activeTab === 'attachments') this.loadAttachments();
    }

    /**
     * Auto member-timeline: the depth-1 members this object CONTAINS (a Case's incidents), with each
     * member's comment thread merged into one chronological (newest-first) activity view, attributed by
     * member. Built entirely from existing id-keyed endpoints (`graph` + per-member `comments`) — no new
     * backend. An object with no members (e.g. a lone incident) shows an empty state.
     */
    loadMemberTimeline(): void {
        this.memberTimelineLoaded.set(false);
        this.api.graph(this.id(), 1).subscribe({
            next: (g) => {
                // Members = depth-1 nodes this object CONTAINS (excluding self); fall back to any linked
                // node so the view is still useful if a deployment uses a different containment verb.
                const contained = new Set(
                    g.edges
                        .filter((e) => e.from === this.id() && e.relationship?.toUpperCase() === 'CONTAINS')
                        .map((e) => e.to),
                );
                this.members.set(
                    g.nodes.filter((n) => n.id !== this.id() && (contained.size === 0 || contained.has(n.id))),
                );
                if (!this.members().length) {
                    this.memberTimeline.set([]);
                    this.memberTimelineLoaded.set(true);
                    return;
                }
                forkJoin(
                    this.members().map((m) =>
                        this.api.comments(m.id).pipe(
                            map((cs) =>
                                cs.map(
                                    (c): MemberTimelineEntry => ({
                                        memberId: m.id,
                                        memberTitle: m.title || m.id,
                                        memberType: m.objectType,
                                        author: c.author || 'unknown',
                                        body: c.body,
                                        createdAt: c.createdAt,
                                    }),
                                ),
                            ),
                            catchError(() => of([] as MemberTimelineEntry[])),
                        ),
                    ),
                ).subscribe((perMember) => {
                    this.memberTimeline.set(perMember.flat().sort((a, b) => b.createdAt - a.createdAt));
                    this.memberTimelineLoaded.set(true);
                });
            },
            error: () => {
                this.members.set([]);
                this.memberTimeline.set([]);
                this.memberTimelineLoaded.set(true);
            },
        });
    }

    /** Events sharing this object's correlation id — the engine-level timeline behind the object. */
    loadEvents(): void {
        this.eventsLoaded.set(false);
        // ⚠ No object yet is NOT "no events". Opening this tab while the header is still a skeleton used
        // to latch `eventsLoaded = true` over an empty list, and nothing re-drove it — onTabChange fires
        // only on a CHANGE, so staying put never retried and the operator had to switch away and back.
        // Left unloaded here, `loadObject()`'s completion re-drives the active tab.
        if (!this.obj()) return;
        const cid = this.obj().correlationId;
        if (!cid) {
            this.relatedEvents.set([]);
            this.eventsLoaded.set(true);
            return;
        }
        // ⚠ Skip the call entirely when the module is absent — every /events* path 503s there, and the tab
        // must not report "no events" for a feed it simply cannot read.
        if (!this.eventsEnabled()) return;
        this.eventsApi.search({ correlationId: cid, limit: 200 }).subscribe({
            next: (e) => {
                this.relatedEvents.set(e);
                this.eventsLoaded.set(true);
            },
            error: () => {
                this.relatedEvents.set([]);
                this.eventsLoaded.set(true);
            },
        });
    }

    loadGraph(): void {
        this.api.graph(this.id(), 2).subscribe({
            next: (g) => this.g6.set(this.toG6(g)),
            error: () => this.g6.set({ nodes: [], edges: [] }),
        });
    }

    loadComments(): void {
        this.api.comments(this.id()).subscribe({
            next: (c) => this.comments.set(c),
            error: () => this.comments.set([]),
        });
    }

    loadAttachments(): void {
        this.api.attachments(this.id()).subscribe({
            next: (a) => this.attachments.set(a),
            error: () => this.attachments.set([]),
        });
    }

    transition(action: string): void {
        // WS-10: an Incident resolves only with a Disposition (and, as in the mail view, a resolution comment).
        if (action === 'resolve' && this.obj()?.objectType === 'INCIDENT') {
            // The resolution pattern is a hard gate on the server: say what is missing up front, rather than
            // ask for a Disposition and a comment, post the comment, and then fail the resolve.
            const gaps = postmortemGaps(this.obj()!);
            if (gaps.length) {
                this.toastr.warning(
                    `Complete the resolution pattern first (missing: ${gaps.join(', ')}).`,
                    'Resolution pattern',
                );
                return;
            }
            const data: ResolveDialogData = { count: 1, label: 'incident', askDisposition: true };
            this.dialog
                .open(ResolveDialog, { width: '560px', data })
                .afterClosed()
                .subscribe((r?: ResolveResult | null) => {
                    if (!r?.comment) return;
                    this.api
                        .addComment(this.id(), r.comment)
                        .pipe(switchMap(() => this.api.transition(this.id(), 'resolve', undefined, r.disposition)))
                        .subscribe({
                            next: (o) => {
                                this.obj.set(o);
                                this.toastr.success(`${o.title}: ${o.status}`);
                            },
                            error: (e) => this.toastr.error(apiErrorMessage(e, 'Transition failed')),
                        });
                });
            return;
        }
        this.api.transition(this.id(), action).subscribe({
            next: (o) => {
                this.obj.set(o);
                this.toastr.success(`${o.title}: ${o.status}`);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Transition failed')),
        });
    }

    addComment(): void {
        if (this.commentForm.invalid) {
            this.commentForm.markAllAsTouched();
            return;
        }
        const body = (this.commentForm.value.body as string).trim();
        if (!body) return;
        this.api.addComment(this.id(), body).subscribe({
            next: () => {
                this.commentForm.reset({ body: '' });
                this.loadComments();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not add comment')),
        });
    }

    addAttachment(): void {
        if (this.attachForm.invalid) {
            this.attachForm.markAllAsTouched();
            return;
        }
        const name = (this.attachForm.value.name as string).trim();
        const uri = (this.attachForm.value.uri as string).trim();
        if (!name || !uri) return;
        this.api.addAttachment(this.id(), { name, uri }).subscribe({
            next: () => {
                this.attachForm.reset({ name: '', uri: '' });
                this.loadAttachments();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not add attachment')),
        });
    }

    applyRca(): void {
        const sections = ['Summary', 'Timeline', 'Root cause', 'Impact', 'Remediation'];
        this.api.applyRca(this.id(), { sections }).subscribe({
            next: () => {
                this.toastr.success('RCA skeleton added to comments');
                this.selectedIndex.set(this.tabs.findIndex((t) => t.id === 'comments'));
                this.loadComments();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not apply RCA')),
        });
    }

    openLink(): void {
        if (!this.obj()) return;
        this.dialog
            .open(ObjectLinkDialog, {
                data: { fromId: this.id(), fromType: this.obj().objectType },
                width: '520px',
                maxHeight: '85vh',
            })
            .afterClosed()
            .subscribe((created) => {
                if (created) this.loadGraph();
            });
    }

    onNodeClick(nodeId: string): void {
        if (!nodeId || nodeId === this.id()) return;
        const base = this.router.url.split('/')[1] || 'incidents';
        this.router.navigate(['/' + base, nodeId]);
    }

    /** The owning list route (`incidents` / `cases`) this detail was opened from. */
    get listBase(): string {
        return this.router.url.split('/')[1] || 'incidents';
    }

    /** Title-cased label for the breadcrumb (e.g. `Incidents`). */
    get listLabel(): string {
        const b = this.listBase;
        return b.charAt(0).toUpperCase() + b.slice(1);
    }

    private toG6(g: ObjectGraph): G6GraphData {
        return {
            nodes: g.nodes.map((n) => ({
                id: n.id,
                data: {
                    label: n.title || n.id,
                    kind: n.objectType as unknown as NodeKind,
                },
            })),
            edges: g.edges.map((e, i) => ({
                id: `${e.from}->${e.to}:${e.relationship}:${i}`,
                source: e.from,
                target: e.to,
                data: { kind: e.relationship },
            })),
        };
    }
}
