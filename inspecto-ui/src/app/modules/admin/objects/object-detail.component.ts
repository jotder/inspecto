import {
    ChangeDetectionStrategy,
    Component,
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
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    EventRow,
    EventsService,
    NodeKind,
    ObjectGraph,
    ObjectGraphNode,
    ObjectNote,
    ObjectsService,
    OperationalObject,
} from 'app/inspecto/api';
import { AiStatusComponent } from 'app/inspecto/ai-assist/ai-status.component';
import { InspectoBreadcrumbComponent } from 'app/inspecto/components/breadcrumb.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { fmtDateTime } from 'app/inspecto/grid';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';
import { ObjectLinkDialog } from './object-link.dialog';

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
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTabsModule,
        MatTooltipModule,
        ReactiveFormsModule,
        AiStatusComponent,
        GraphViewComponent,
        InspectoBreadcrumbComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './object-detail.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class ObjectDetailComponent implements OnInit {
    private api = inject(ObjectsService);
    private eventsApi = inject(EventsService);
    private route = inject(ActivatedRoute);
    private destroyRef = inject(DestroyRef);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);

    readonly id = signal('');
    readonly obj = signal<OperationalObject | null>(null);
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

    /** Legal next workflow actions from the current status, per object type (backend re-validates). */
    private static readonly TRANSITIONS: Record<string, Record<string, string[]>> = {
        INCIDENT: {
            OPEN: ['assign'],
            ASSIGNED: ['start'],
            IN_PROGRESS: ['resolve'],
            RESOLVED: ['close'],
        },
        CASE: {
            OPEN: ['investigate'],
            INVESTIGATING: ['escalate', 'resolve'],
            ESCALATED: ['resolve'],
            RESOLVED: ['close'],
        },
        ALERT: { OPEN: ['ack', 'resolve'], ACKNOWLEDGED: ['resolve'] },
    };

    get actions(): string[] {
        if (!this.obj()) return [];
        return (
            ObjectDetailComponent.TRANSITIONS[this.obj().objectType]?.[(this.obj().status ?? '').toUpperCase()] ?? []
        );
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

    back(): void {
        this.router.navigate(['/' + this.listBase]);
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
