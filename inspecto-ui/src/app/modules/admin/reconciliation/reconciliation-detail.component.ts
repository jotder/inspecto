import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ReconApiService } from 'app/inspecto/api';
import { ReconRowsResult } from 'app/inspecto/api/recon.service';
import { StatusBadgeComponent, statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { FlatTreeRow, TreeNode, TreeTableComponent, varianceCell } from 'app/inspecto/tree-table';
import { InspectoRowAction } from 'app/inspecto/grid';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import {
    ageBucketLabel,
    breakAgeDays,
    breakId,
    breaksFromSets,
    decodePath,
    openAgeBuckets,
    Reconciliation,
    ReconciliationsService,
    ReconBreak,
    resolveBreak,
} from 'app/inspecto/reconciliation';
import { ReconExecService } from './recon-exec.service';
import { ChipComponent } from 'app/inspecto/components/chip.component';

/**
 * Breaks page (`/reconciliation/:id/breaks?path=…`) — the record sets behind one Board cell: three
 * tables (only in A / only in B / matched-but-different), computed live at the recon grain by
 * {@link ReconExecService} (server DuckDB, or the offline mirror under mock Studio) and optionally
 * scoped to a Board dimension path. The persisted Break lifecycle overlays by identity — resolve /
 * re-open persists here; auto-close happens on the Board's full-scope run (C9 semantics unchanged).
 * The grouped tree stays as a display toggle. Design §5.
 */
@Component({
    selector: 'app-reconciliation-detail',
    standalone: true,
    imports: [
        ChipComponent,
        RouterLink,
        MatButtonModule,
        MatButtonToggleModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        DataTableComponent,
        TreeTableComponent,
        InspectoEmptyStateComponent,
        InspectoAlertComponent,
        StatusBadgeComponent,
        DatePipe,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './reconciliation-detail.component.html',
})
export class ReconciliationDetailComponent implements OnInit {
    private api = inject(ReconciliationsService);
    private exec = inject(ReconExecService);
    private route = inject(ActivatedRoute);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    private reconApi = inject(ReconApiService);
    private router = inject(Router);

    readonly recon = signal<Reconciliation | null>(null);
    readonly loading = signal(true);
    readonly computing = signal(false);
    readonly lastEvaluated = signal<Date | null>(new Date());
    /** Live breaks (all `open` from the engine) — persisted statuses overlay by identity below. */
    private readonly liveBreaks = signal<ReconBreak[] | null>(null);

    /**
     * Set once a promote attempt comes back 503 — the Personal edition has no `inspecto-ops` module, so
     * Incidents do not exist in this bundle. ⚠ That is a deployment state, not a failure: it renders as an
     * explained inline panel and the action hides itself, never a red toast (the Approvals-inbox lesson).
     * It is latched rather than probed, because there is nothing cheap to probe — the first attempt IS the
     * probe, and the operator has already been told what happened by the panel.
     */
    readonly incidentsUnavailable = signal(false);
    /**
     * Break key → the id of the ACTIVE Incident covering it (`BREAK-INCIDENT-RESOLVE-1`).
     *
     * 🔴 Loaded from `GET /recon/promoted`, not remembered in-session. It used to be a bare `Set` filled
     * only by this tab's own promotes, so a reload forgot every promotion and re-offered the action as if
     * it had never happened.
     *
     * 🔴 Keyed by **`breakId(b)`** — `(type, key, column)`, the server's dedupe grain since
     * `BREAK-DEDUPE-GRAIN-1` (2026-09-15). It was keyed by the bare `b.key` until then, matching a server
     * that deduped on `breakKey` alone; two Breaks sharing a key but differing in type or column were ONE
     * Incident, so both read as promoted here. Both halves moved together — ⛔ **they are one contract**:
     * `ReconRoutes.breakIdentity()` renders the byte-identical string into the `breakId` attribute it
     * dedupes on and indexes `GET /recon/promoted` by, and this map is looked up with the value
     * {@link breakId} computes. Changing either spelling alone silently empties this map.
     *
     * ⛔ Do NOT "simplify" this into a client-side filter of `GET /objects` by `breakKey`. Promotion is
     * suppressed only while the Incident is **non-terminal**, so an ARCHIVED one means the Break is
     * promotable again; the server applies that rule to both the offer and the dedupe, and a client
     * matching on mere existence would report an available action as unavailable.
     */
    private readonly promoted = signal<Readonly<Record<string, string>>>({});
    readonly isPromoted = (b: ReconBreak): boolean => breakId(b) in this.promoted();
    /** The Incident covering this Break, or null — the back-reference the board was missing. */
    readonly incidentFor = (b: ReconBreak): string | null => this.promoted()[breakId(b)] ?? null;

    /** The Board dimension path this page is scoped to (from `?path=`), or null for the whole recon. */
    readonly path = signal<Record<string, string> | null>(null);
    readonly pathEntries = computed(() => Object.entries(this.path() ?? {}));

    /** The compared side of the anchor-relative pair — 'b' always, 'c' when the recon is 3-way. */
    readonly side = signal<'b' | 'c'>('b');
    readonly threeWay = computed(() => !!this.recon()?.thirdDataset);
    /** Human label of the compared side's dataset (for the section headers). */
    readonly sideDataset = computed(() =>
        this.side() === 'c' ? (this.recon()?.thirdDataset ?? 'C') : (this.recon()?.rightDataset ?? 'B'),
    );

    readonly viewMode = signal<'tables' | 'grouped'>('tables');

    /** Persisted break status/note by identity. */
    private readonly persistedById = computed(() => {
        const m = new Map<string, ReconBreak>();
        for (const b of this.recon()?.breaks ?? []) m.set(breakId(b), b);
        return m;
    });

    /** Live breaks with the persisted lifecycle overlaid. */
    readonly drillBreaks = computed<ReconBreak[]>(() => {
        const live = this.liveBreaks() ?? [];
        const persisted = this.persistedById();
        return live.map((b) => {
            const p = persisted.get(breakId(b));
            return p && p.status !== 'auto_closed' ? { ...b, status: p.status, note: p.note } : b;
        });
    });

    readonly missingA = computed(() => this.drillBreaks().filter((b) => b.type === 'missing_right'));
    readonly missingB = computed(() => this.drillBreaks().filter((b) => b.type === 'missing_left'));
    readonly valueBreaks = computed(() => this.drillBreaks().filter((b) => b.type === 'value_break'));
    readonly resolvedCount = computed(() => this.drillBreaks().filter((b) => b.status === 'resolved').length);

    /** Aging histogram over the open breaks in this scope (`BREAK-AGING-1`); the rollup is shared. */
    readonly ageBuckets = computed(() => openAgeBuckets(this.drillBreaks()));
    readonly ageLabel = ageBucketLabel;

    /** Whole days a break has been open, or an em-dash when it carries no first-seen stamp. */
    readonly ageText = (b: ReconBreak): string => {
        const days = breakAgeDays(b);
        return days === null ? '—' : `${days}d`;
    };

    /** Key + status (+ actions) — the shape of the two missing-side tables. */
    readonly missingColumns: ColDef<ReconBreak>[] = [
        { field: 'key', headerName: 'Key', flex: 1 },
        {
            field: 'status',
            headerName: 'Status',
            width: 130,
            cellRenderer: (p: ICellRendererParams<ReconBreak>) => statusBadgeHtml(p.value as string),
        },
    ];

    readonly valueColumns = computed<ColDef<ReconBreak>[]>(() => {
        const r = this.recon();
        return [
            { field: 'key', headerName: 'Key', flex: 1 },
            { field: 'column', headerName: 'Column', width: 140 },
            {
                field: 'leftValue',
                headerName: r?.leftDataset || 'A',
                width: 140,
                valueFormatter: (p) => fmtVal(p.value),
            },
            { field: 'rightValue', headerName: this.sideDataset(), width: 140, valueFormatter: (p) => fmtVal(p.value) },
            { field: 'diff', headerName: 'Δ', width: 120, cellRenderer: varianceCell() },
            {
                colId: 'age',
                headerName: 'Age',
                width: 100,
                // Sorted by the NUMBER, rendered as text: a string sort would put "9d" after "30d".
                valueGetter: (p) => (p.data ? breakAgeDays(p.data) : null),
                valueFormatter: (p) => (p.value === null || p.value === undefined ? '—' : `${p.value}d`),
            },
            {
                field: 'status',
                headerName: 'Status',
                width: 130,
                cellRenderer: (p: ICellRendererParams<ReconBreak>) => statusBadgeHtml(p.value as string),
            },
        ];
    });

    readonly rowActions: InspectoRowAction<ReconBreak>[] = [
        {
            icon: (b) => (b.status === 'resolved' ? 'heroicons_outline:arrow-uturn-left' : 'heroicons_outline:check'),
            hint: (b) => (b.status === 'resolved' ? 'Re-open' : 'Resolve'),
            onClick: (b) => this.toggleResolve(b),
        },
        {
            icon: 'heroicons_outline:exclamation-triangle',
            hint: (b) => (this.isPromoted(b) ? 'Already promoted to an Incident' : 'Promote to Incident'),
            // Hidden outright once the bundle has told us Incidents do not exist here — an affordance that
            // can only ever explain itself is worse than no affordance (the ai-status rule).
            visible: () => !this.incidentsUnavailable(),
            // ⛔ Deliberately NOT disabled when already promoted: an ARCHIVED Incident makes re-promoting
            // legitimate, and the server decides that, not this button.
            onClick: (b) => this.promote(b),
        },
        {
            // The back-reference `BREAK-INCIDENT-RESOLVE-1` was filed for: a promoted Break can now be
            // followed to the Incident working it, instead of only hinting that one exists somewhere.
            icon: 'heroicons_outline:arrow-top-right-on-square',
            hint: () => 'Open the Incident for this Break',
            visible: (b) => !this.incidentsUnavailable() && !!this.incidentFor(b),
            onClick: (b) => this.openIncident(b),
        },
    ];

    // ── grouped (tree-table) toggle: breaks grouped by type, aligned columns + Δ ────────
    private readonly breaksById = computed(() => {
        const m = new Map<string, ReconBreak>();
        for (const b of this.drillBreaks()) m.set(breakId(b), b);
        return m;
    });

    readonly treeNodes = computed<TreeNode[]>(() => {
        const groups = new Map<string, ReconBreak[]>();
        for (const b of this.drillBreaks()) {
            const g = groups.get(b.type) ?? [];
            g.push(b);
            groups.set(b.type, g);
        }
        const out: TreeNode[] = [];
        for (const [type, list] of groups) {
            const sumDiff = list.reduce((s, b) => s + (typeof b.diff === 'number' ? b.diff : 0), 0);
            out.push({
                id: `grp:${type}`,
                label: `${breakLabel(type)} (${list.length})`,
                icon: 'heroicons_outline:rectangle-stack',
                expanded: true,
                values: { diff: sumDiff || undefined },
                children: list.map((b) => ({
                    id: breakId(b),
                    label: b.key,
                    values: {
                        column: b.column ?? '—',
                        leftValue: b.leftValue,
                        rightValue: b.rightValue,
                        diff: b.diff,
                        status: b.status,
                    },
                })),
            });
        }
        return out;
    });

    readonly treeColumns = computed<ColDef[]>(() => {
        const r = this.recon();
        return [
            { field: 'column', headerName: 'Column', width: 150, valueFormatter: (p) => p.value ?? '—' },
            {
                field: 'leftValue',
                headerName: r?.leftDataset || 'Left',
                flex: 1,
                valueFormatter: (p) => fmtVal(p.value),
            },
            {
                field: 'rightValue',
                headerName: r?.rightDataset || 'Right',
                flex: 1,
                valueFormatter: (p) => fmtVal(p.value),
            },
            { field: 'diff', headerName: 'Δ', width: 120, cellRenderer: varianceCell() },
            {
                field: 'status',
                headerName: 'Status',
                width: 120,
                cellRenderer: (p: ICellRendererParams) => (p.value ? statusBadgeHtml(String(p.value)) : ''),
            },
        ];
    });

    readonly treeActions: InspectoRowAction<FlatTreeRow>[] = [
        {
            icon: (row) =>
                this.breakOf(row)?.status === 'resolved'
                    ? 'heroicons_outline:arrow-uturn-left'
                    : 'heroicons_outline:check',
            hint: (row) => (this.breakOf(row)?.status === 'resolved' ? 'Re-open' : 'Resolve'),
            visible: (row) => !!this.breakOf(row),
            onClick: (row) => {
                const b = this.breakOf(row);
                if (b) void this.toggleResolve(b);
            },
        },
        {
            icon: 'heroicons_outline:exclamation-triangle',
            hint: (row) => {
                const b = this.breakOf(row);
                return b && this.isPromoted(b) ? 'Already promoted to an Incident' : 'Promote to Incident';
            },
            visible: (row) => !!this.breakOf(row) && !this.incidentsUnavailable(),
            onClick: (row) => {
                const b = this.breakOf(row);
                if (b) void this.promote(b);
            },
        },
        {
            // RECON-CARDINALITY-2: the counts are a summary; this shows the rows they summarise.
            icon: 'heroicons_outline:table-cells',
            hint: () => 'Show the rows behind this break',
            visible: (row) => this.breakOf(row)?.type === 'cardinality_break' && !!this.breakOf(row)?.keyValues,
            onClick: (row) => {
                const b = this.breakOf(row);
                if (b) void this.showRows(b);
            },
        },
        {
            icon: 'heroicons_outline:arrow-top-right-on-square',
            hint: () => 'Open the Incident for this Break',
            visible: (row) => {
                const b = this.breakOf(row);
                return !!b && !this.incidentsUnavailable() && !!this.incidentFor(b);
            },
            onClick: (row) => {
                const b = this.breakOf(row);
                if (b) this.openIncident(b);
            },
        },
    ];

    private breakOf(row: FlatTreeRow): ReconBreak | undefined {
        return this.breaksById().get(row.__id);
    }

    // ── RECON-CARDINALITY-2: the rows behind a cardinality break ─────────────────────────────

    /** The last "show rows" answer: the key it was asked for and both sides' raw rows. */
    readonly breakRows = signal<{ label: string; result: ReconRowsResult } | null>(null);
    readonly breakRowsLoading = signal(false);

    /** Column defs straight from the first raw row — the side's physical columns, verbatim. */
    rawColumns(rows: Record<string, unknown>[]): ColDef[] {
        const first = rows[0];
        return first
            ? Object.keys(first).map((k) => ({
                  field: k,
                  headerName: k,
                  flex: 1,
                  valueFormatter: (p) => fmtVal(p.value),
              }))
            : [];
    }

    async showRows(b: ReconBreak): Promise<void> {
        const r = this.recon();
        if (!r || !b.keyValues) return;
        const key: Record<string, string> = {};
        for (const k of r.keyColumns) key[k] = String(b.keyValues[k] ?? '');
        this.breakRowsLoading.set(true);
        try {
            this.breakRows.set({ label: b.key, result: await this.exec.rows(r, key, this.side()) });
        } catch (err) {
            this.toastr.error(apiErrorMessage(err, 'Could not load the rows behind this break'));
        } finally {
            this.breakRowsLoading.set(false);
        }
    }

    closeRows(): void {
        this.breakRows.set(null);
    }

    ngOnInit(): void {
        const id = this.route.snapshot.paramMap.get('id') ?? '';
        this.path.set(decodePath(this.route.snapshot.queryParamMap.get('path')));
        this.api.get(id).subscribe({
            next: (r) => {
                this.recon.set(r);
                this.loading.set(false);
                this.loadPromoted(r.id);
                void this.compute();
            },
            error: (e) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, `Could not load reconciliation "${id}"`));
            },
        });
    }

    /**
     * Load which Breaks already carry an active Incident.
     *
     * ⚠ A **503** latches the explained panel rather than toasting — a missing `inspecto-ops` module is a
     * deployment state, not an error. ⛔ And it must not leave the map looking merely empty: "no Incidents
     * here" and "Incidents are not installed" are different answers, which is why the panel latch carries it.
     * Any other failure is silent by design: this is an affordance hint, and a toast on every page load
     * would be worse than a missing tooltip.
     */
    private loadPromoted(reconId: string): void {
        this.reconApi.promoted(reconId).subscribe({
            next: (res) => this.promoted.set(res.promoted ?? {}),
            error: (e) => {
                if (e?.status === 503) this.incidentsUnavailable.set(true);
            },
        });
    }

    /** Switch the compared side (3-way) and recompute. */
    setSide(side: 'b' | 'c'): void {
        // Defense in depth behind the template's [disabled]: `compute()` early-returns while one is in
        // flight, so flipping `side` here regardless would re-label the headers over the OLD rows.
        if (this.computing()) return;
        this.side.set(side);
        void this.compute();
    }

    /** (Re)compute the live break sets for the current scope + compared side. */
    async compute(): Promise<void> {
        const r = this.recon();
        if (!r || this.computing()) return;
        this.computing.set(true);
        try {
            this.liveBreaks.set(breaksFromSets(r, await this.exec.breaks(r, this.path(), null, this.side())));
            this.lastEvaluated.set(new Date());
        } catch (e) {
            this.liveBreaks.set(null);
            this.toastr.error(apiErrorMessage(e, 'Could not compute the break sets'));
        } finally {
            this.computing.set(false);
        }
    }

    /**
     * Hand one Break to Ops as an Incident (`BREAK-INCIDENT-1`). Until this action a Break could be marked
     * resolved here but could not be escalated at all — the board and Ops had no connection.
     *
     * <p>The server dedupes on `(reconciliation, key)`, so a second promotion of the same Break reports
     * `deduped` rather than opening a clone; that is surfaced as an info toast, not an error, because
     * nothing went wrong. A **503** means this bundle has no operational-objects module and latches
     * {@link incidentsUnavailable}, which explains itself in place and removes the action.
     */
    /** Follow a promoted Break to its Incident. ⚠ No-op when unpromoted — the action is hidden then, and a
     *  navigate to `/incidents/null` would be a worse answer than none. */
    openIncident(b: ReconBreak): void {
        const id = this.incidentFor(b);
        if (id) void this.router.navigate(['/incidents', id]);
    }

    async promote(b: ReconBreak): Promise<void> {
        const r = this.recon();
        if (!r || this.incidentsUnavailable()) return;
        if (
            !(await this.confirm.confirm(
                `Open an Incident for this ${breakLabel(b.type)} on key "${b.key}"? ` +
                    `Ops will see it with the reconciliation and run as evidence.`,
                'Promote to Incident',
            ))
        )
            return;
        this.reconApi.promote(r.id, b.key, b.type, b.column ?? null, r.lastRunAt ?? null).subscribe({
            next: (res) => {
                // ⚠ `incidentId` is null exactly when `deduped` — the dedupe seam suppresses without naming
                // the survivor — so a re-read is the only way to learn which Incident covers this Break.
                // ⛔ Keyed by `breakId(b)`, the same identity `isPromoted`/`incidentFor` read and the server
                // now returns (`BREAK-DEDUPE-GRAIN-1`). Keying this optimistic write by `b.key` while the
                // readers looked up the identity made a just-promoted Break render as un-promoted until a
                // reload — the write and the read must use one spelling.
                if (res.incidentId) this.promoted.set({ ...this.promoted(), [breakId(b)]: res.incidentId });
                else this.loadPromoted(r.id);
                if (res.deduped) this.toastr.info(`An Incident for key "${b.key}" is already open.`);
                else this.toastr.success(`Incident opened for key "${b.key}".`);
            },
            error: (e) => {
                if (e?.status === 503) {
                    this.incidentsUnavailable.set(true);
                    return; // explained in place by the panel — never a toast for a missing module
                }
                this.toastr.error(apiErrorMessage(e, `Could not promote the break for key "${b.key}"`));
            },
        });
    }

    /** Resolve / re-open one break — persisted by identity (a fresh live break is appended on first touch). */
    async toggleResolve(b: ReconBreak): Promise<void> {
        const r = this.recon();
        if (!r) return;
        const resolving = b.status !== 'resolved';
        if (
            resolving &&
            !(await this.confirm.confirm(
                `Mark this ${breakLabel(b.type)} for key "${b.key}" resolved?`,
                'Resolve break',
            ))
        )
            return;
        const known = this.persistedById().has(breakId(b));
        const breaks = known
            ? resolveBreak(r.breaks, b, resolving)
            : [...r.breaks, { ...b, status: resolving ? ('resolved' as const) : ('open' as const) }];
        const updated: Reconciliation = { ...r, breaks };
        this.api.save(updated).subscribe({
            next: () => this.recon.set(updated),
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not update the break')),
        });
    }
}

function breakLabel(type: string): string {
    return type === 'missing_left'
        ? 'missing left'
        : type === 'missing_right'
          ? 'missing right'
          : type === 'value_break'
            ? 'value break'
            : type;
}
function fmtVal(v: unknown): string {
    if (v == null) return '—';
    const n = Number(v);
    return Number.isNaN(n) ? String(v) : n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
