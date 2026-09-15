import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Router, RouterLink } from '@angular/router';
import { lensHome } from 'app/app.routes';
import {
    AgentApproval,
    ApprovalsService,
    EventsService,
    ExpectationsService,
    JobRunRow,
    JobsService,
    LensService,
    ObjectsService,
    OperationalObject,
    SessionService,
    SpacesService,
    apiErrorMessage,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { ToastrService } from 'ngx-toastr';

/** One row of the "Needs attention" list — the three sources normalised onto one shape. */
interface AttentionRow {
    /** The semantic token `<inspecto-status-badge>` derives its tone from — never a colour. */
    tone: string;
    /** What the badge reads, which is the kind of thing rather than the token. */
    badge: string;
    /** The entity's own identifier, rendered monospaced. */
    subject: string;
    detail: string;
    /** Epoch ms, for ordering and the relative timestamp. */
    at: number;
    link: string;
}

const NOTICE_DISMISSED = 'inspecto.home.bindNoticeDismissed';

/**
 * Home — the one landing route, in every edition (landing-page plan D1, 2026-09-15). Root redirects
 * here; the Lens landing route (`LENS_HOME`) becomes this page's primary action rather than the root
 * target, so all three Lenses and a fresh install share one front door.
 *
 * ⛔ **Nothing here branches on the edition string.** Every difference between a Personal and an
 * Enterprise Home is driven by `SessionService`'s module flags (derived from what actually registered)
 * and by the subject's capabilities — the shell's standing rule
 * (`docs/okf/capabilities/surfaces/surfaces.md` §3.2). The `edition` field is inert and unread.
 *
 * **Five data sources, each behind a CHEAP call.** The two the mockups carried and the first build dropped —
 * "Expectations breached" and "Datasets written" — landed 2026-09-16 (`HOME-TILES-1`) only once the backend
 * served a count for each (`GET /expectations/breached-count`, `GET /signals/count?type=dataset.write`): a
 * landing page that fetched every Expectation to count failures was the wrong trade, and neither tile is
 * computed client-side. Both hide, rather than show a dash, when their call fails.
 */
@Component({
    selector: 'inspecto-home',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './home.component.html',
    imports: [
        MatButtonModule,
        MatIconModule,
        RouterLink,
        InspectoAlertComponent,
        ChipComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
})
export class HomeComponent implements OnInit {
    private jobs = inject(JobsService);
    private objects = inject(ObjectsService);
    private approvalsApi = inject(ApprovalsService);
    private expectations = inject(ExpectationsService);
    private events = inject(EventsService);
    private spaces = inject(SpacesService);
    private router = inject(Router);
    private toastr = inject(ToastrService);
    readonly session = inject(SessionService);
    readonly lens = inject(LensService);

    // ── Identity + shell context ────────────────────────────────────────────────────────────────
    /** Only ever a real principal: `SessionService.actor` is null on Personal and while signed out. */
    readonly actor = this.session.actor;
    /** Only worth naming where there is more than one — in a single-Space install it is noise, and the
     *  seeded name is literally "default". */
    readonly spaceName = computed(
        () => (this.spaces.multiSpace() ? this.spaces.currentSpace()?.displayName : null) ?? null,
    );
    readonly multiSpace = this.spaces.multiSpace;

    readonly greeting = computed(() => {
        const who = this.actor();
        return who ? `Welcome back, ${who}.` : 'Welcome to Inspecto.';
    });

    /** The active Lens's landing route — the page's primary action (D1). Shares `lensHome` with the root
     *  redirect rather than restating its Ops fallback, so the two cannot drift. */
    readonly lensHome = computed(() => lensHome(this.lens.currentLens(), this.session.eventsEnabled()));
    readonly lensLabel = computed(() => LensService.LENSES.find((l) => l.id === this.lens.currentLens())?.label ?? '');
    readonly lensIcon = computed(() => LensService.LENSES.find((l) => l.id === this.lens.currentLens())?.icon ?? '');

    /** Personal ships no authenticator and binds every interface by default, so the control plane —
     *  config writes included — is reachable by anyone who can reach the port (EDITIONS, the listen
     *  -address note). Shown only there, and dismissible per browser. */
    readonly showBindNotice = computed(() => this.session.authMode() === 'none' && !this.noticeDismissed());
    private readonly noticeDismissed = signal(this.restoreDismissed());

    // ── Loaded state ────────────────────────────────────────────────────────────────────────────
    readonly loading = signal(true);
    private readonly runs = signal<JobRunRow[]>([]);
    /** `GET /jobs/runs` needs the DuckDB jobs backend; without it there is no run history to show —
     *  which is NOT the same as "nothing has run yet", so it must not trigger the first-run page. */
    readonly runsUnavailable = signal(false);
    /** The Runs tile's figure, or an em dash when there is no history to count. */
    readonly runsLabel = computed(() => (this.runsUnavailable() ? '—' : String(this.runs().length)));
    private readonly incidents = signal<OperationalObject[]>([]);
    private readonly approvals = signal<AgentApproval[]>([]);

    readonly failedRuns = computed(() => this.runs().filter((r) => r.status === 'FAILED'));
    readonly openIncidents = this.incidents;
    readonly pendingApprovals = computed(() => this.approvals().filter((a) => a.status === 'PENDING'));
    /** HOME-TILES-1: server counts; `null` = the call failed or the module is absent, and the tile hides. */
    readonly breached = signal<number | null>(null);
    readonly datasetsWritten = signal<{ count: number; datasets: number; capped: boolean } | null>(null);

    /** A genuinely empty install: the run history loaded and holds nothing. */
    readonly firstRun = computed(() => !this.loading() && !this.runsUnavailable() && this.runs().length === 0);

    readonly attention = computed<AttentionRow[]>(() =>
        [
            ...this.failedRuns().map((r) => ({
                tone: 'FAILED',
                badge: 'FAILED',
                subject: r.job,
                detail: r.message || 'The run failed.',
                at: Date.parse(r.endTime || r.startTime) || 0,
                link: '/runs',
            })),
            ...this.openIncidents().map((o) => ({
                tone: o.severity || o.status,
                badge: 'INCIDENT',
                subject: o.id,
                detail: o.title,
                at: o.updatedAt || o.createdAt || 0,
                link: '/incidents',
            })),
            ...this.pendingApprovals().map((a) => ({
                tone: 'PENDING',
                badge: 'APPROVAL',
                subject: a.tool,
                detail: a.summary,
                at: Date.parse(a.requestedAt) || 0,
                link: '/approvals',
            })),
        ]
            .sort((a, b) => b.at - a.at)
            .slice(0, 6),
    );

    /** The line under the greeting. Counting to zero ("0 thing(s) need attention") is worse than saying
     *  the good news, so the empty case gets its own sentence. */
    readonly subtitle = computed(() => {
        const n = this.attention().length;
        if (n === 0) return 'Everything is running. Nothing is waiting on you.';
        return n === 1 ? '1 thing needs your attention.' : `${n} things need your attention.`;
    });

    // ── Capability-gated actions ────────────────────────────────────────────────────────────────
    readonly canOnboard = this.lens.canOnboardConnections;
    readonly canAuthor = this.lens.canAuthorWorkbench;
    readonly canOperate = this.lens.canOperateRuns;
    readonly canAdminister = this.lens.canAdminister;
    /** Incident triage is only reachable when the optional ops module registered its routes. */
    readonly opsEnabled = this.session.opsEnabled;

    /** The subject's effective grants, for the access card — server-resolved, so it is the honest
     *  answer to "what may I do here". Empty (and the card hidden) off OIDC. */
    readonly grants = this.session.capabilities;

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.jobs.recentRuns(25).subscribe({
            next: (r) => {
                this.runs.set(r);
                this.runsUnavailable.set(false);
                this.loading.set(false);
            },
            error: (err) => {
                // No run history is an expected deployment state (the DuckDB jobs backend is opt-in),
                // so it explains itself in place rather than toasting. Connectivity stays the banner's job.
                this.runs.set([]);
                this.runsUnavailable.set(true);
                this.loading.set(false);
                if (err?.status !== 404 && err?.status !== 503 && err?.status !== 0) {
                    this.toastr.error(apiErrorMessage(err, 'Failed to load recent runs'));
                }
            },
        });

        // Gate on the flag first: an absent ops module 503s on every path, so asking at all would only
        // produce an error to swallow (the dashboard's precedent).
        if (this.opsEnabled()) {
            this.objects.list({ type: 'INCIDENT', status: 'OPEN', limit: 10 }).subscribe({
                next: (o) => this.incidents.set(o),
                error: () => this.incidents.set([]),
            });
        }

        // Approvals have no bootstrap flag — the intelligence module's absence shows up as a 503, which
        // is expected and silent (the Approvals pane's own latch).
        this.approvalsApi.list(20).subscribe({
            next: (a) => this.approvals.set(a),
            error: () => this.approvals.set([]),
        });

        // HOME-TILES-1: two more cheap counts. Silent on error — a missing write root or an older backend
        // hides the tile; the numbers that ARE shown are always the server's, never a client-side sweep.
        this.expectations.breachedCount().subscribe({
            next: (r) => this.breached.set(r.count),
            error: () => this.breached.set(null),
        });
        this.events.signalCount('dataset.write', Date.now() - 24 * 3_600_000).subscribe({
            next: (r) => this.datasetsWritten.set(r),
            error: () => this.datasetsWritten.set(null),
        });
    }

    openLensHome(): void {
        void this.router.navigate([this.lensHome()]);
    }

    dismissBindNotice(): void {
        this.noticeDismissed.set(true);
        try {
            localStorage.setItem(NOTICE_DISMISSED, '1');
        } catch {
            /* private browsing / blocked storage — the notice simply returns next load */
        }
    }

    /** Relative age, e.g. "3h ago". Epoch 0 (an unparsable timestamp) renders as an em dash. */
    ago(at: number): string {
        if (!at) return '—';
        const mins = Math.max(0, Math.round((Date.now() - at) / 60000));
        if (mins < 1) return 'just now';
        if (mins < 60) return `${mins}m ago`;
        const hours = Math.round(mins / 60);
        if (hours < 24) return `${hours}h ago`;
        return `${Math.round(hours / 24)}d ago`;
    }

    private restoreDismissed(): boolean {
        try {
            return localStorage.getItem(NOTICE_DISMISSED) === '1';
        } catch {
            return false;
        }
    }
}
