import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
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
    breakImpacts,
    breaksFromSets,
    datasetLabels,
    decodePath,
    duplicateImpacts,
    lifecycleId,
    oneSides,
    openAgeBuckets,
    Reconciliation,
    ReconciliationsService,
    ReconBreak,
    reconCardinality,
    reconciliationTitle,
    ReconState,
} from 'app/inspecto/reconciliation';
import { ReconExecService } from './recon-exec.service';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { formatNumber, NumberFormat } from 'app/inspecto/viz/number-format';
import { fmtDateTime } from 'app/inspecto/format';
import { humanizeColumn } from 'app/inspecto/viz/column-label';

/**
 * Breaks page (`/reconciliation/:id/breaks?path=…`) — the record sets behind one Board cell: three
 * tables (only in A / only in B / matched-but-different), computed live at the recon grain by
 * {@link ReconExecService} (server DuckDB, or the offline mirror under mock Studio) and optionally
 * scoped to a Board dimension path. The server-RECORDED Break lifecycle (R2-03, `GET /recon/{id}/state`)
 * overlays by identity — resolve / re-open is `POST /recon/{id}/breaks/status`; auto-close happens when a
 * run is recorded (the Board, or the scheduled `recon.run` Job). The grouped tree stays as a display toggle.
 * Design §5.
 */
@Component({
    selector: 'app-reconciliation-detail',
    standalone: true,
    imports: [
        ChipComponent,
        InspectoPageHeaderComponent,
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
    private datasetsApi = inject(DatasetsService);

    readonly recon = signal<Reconciliation | null>(null);
    /** The server-recorded run + Break lifecycle (R2-03); null until read. */
    readonly state = signal<ReconState | null>(null);
    readonly loading = signal(true);
    readonly computing = signal(false);
    readonly lastEvaluated = signal<Date | null>(new Date());
    /** Date AND time — a bare wall-clock time is ambiguous once the data is as of an earlier day. */
    readonly fmtDateTime = fmtDateTime;
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
    /** Dataset id → readable label, read once on open (R2-16); empty until then, so sides show their ids. */
    readonly datasetNames = signal<Record<string, string>>({});
    private readonly datasetLabel = (id: string): string => this.datasetNames()[id] || id;
    /** Readable labels of the anchor (A) and right (B) Datasets — the id when the Dataset has none. */
    readonly leftLabel = computed(() => this.datasetLabel(this.recon()?.leftDataset ?? 'A'));
    readonly rightLabel = computed(() => this.datasetLabel(this.recon()?.rightDataset ?? 'B'));
    /** The compared side's Dataset id (for tooltips) and its readable label (for the section headers). */
    readonly sideDatasetId = computed(() =>
        this.side() === 'c' ? (this.recon()?.thirdDataset ?? 'C') : (this.recon()?.rightDataset ?? 'B'),
    );
    readonly sideDataset = computed(() => this.datasetLabel(this.sideDatasetId()));

    readonly viewMode = signal<'tables' | 'grouped'>('tables');

    /** UIE-10: the page title is the Reconciliation's business description; its id is a code. */
    readonly title = computed(() => {
        const r = this.recon();
        return r ? reconciliationTitle(r) : '';
    });

    // ── UIE-10: monetary impact per Break ────────────────────────────────────────────────
    /** Break key → impact, from the last compute. Empty when the Reconciliation declares no impact. */
    private readonly impacts = signal<Readonly<Record<string, number>>>({});
    /** Duplicate key → the value of its extra copies (see `duplicateImpacts`) — a separate map because the
     *  same key can also carry a value break, whose impact is a different number. */
    private readonly dupImpacts = signal<Readonly<Record<string, number>>>({});
    private readonly impactOf = (b: ReconBreak): number | undefined =>
        (b.type === 'cardinality_break' ? this.dupImpacts() : this.impacts())[b.key];
    private readonly impactFormat = computed<NumberFormat | null>(() => {
        const imp = this.recon()?.impact;
        return imp ? (imp.currency ? { style: 'currency', currency: imp.currency } : {}) : null;
    });
    /** The impact column header, e.g. "Impact (SAR)", or null when no impact is declared. */
    readonly impactHeader = computed(() => {
        const imp = this.recon()?.impact;
        return imp ? (imp.currency ? `Impact (${imp.currency})` : 'Impact') : null;
    });
    readonly impactText = (b: ReconBreak): string => {
        const f = this.impactFormat();
        const v = this.impactOf(b);
        return f && v !== undefined ? formatNumber(v, f) : '—';
    };
    /** Sorted by the NUMBER, rendered through the shared formatter. */
    private readonly impactColumn = computed<ColDef<ReconBreak>[]>(() => {
        const header = this.impactHeader();
        return header
            ? [
                  {
                      colId: 'impact',
                      headerName: header,
                      width: 150,
                      valueGetter: (p) => (p.data ? (this.impactOf(p.data) ?? null) : null),
                      valueFormatter: (p) => (p.data ? this.impactText(p.data) : '—'),
                  },
              ]
            : [];
    });

    // ── UIE-10: the selected Break — its field diff and a labelled Promote action ────────────
    private readonly selectedId = signal<string | null>(null);
    /** Re-resolved against the live list, so a recompute or a lifecycle change shows through. */
    readonly selected = computed(() => {
        const id = this.selectedId();
        return id ? (this.drillBreaks().find((b) => breakId(b) === id) ?? null) : null;
    });
    /**
     * The selected key's MISMATCHED fields only — one per value break at that key (a field within tolerance
     * produces no break, so it never appears). A cardinality break's one "field" is the row count.
     */
    readonly selectedFields = computed(() => {
        const s = this.selected();
        if (!s) return [];
        if (s.type === 'cardinality_break')
            return [{ field: 'rows', label: 'Rows', left: fmtVal(s.leftValue), right: fmtVal(s.rightValue) }];
        if (s.type !== 'value_break') return [];
        return this.valueBreaks()
            .filter((b) => b.key === s.key)
            .map((b) => ({
                field: b.column ?? '—',
                label: b.column ? humanizeColumn(b.column) : '—',
                left: fmtVal(b.leftValue),
                right: fmtVal(b.rightValue),
            }));
    });
    readonly breakLabel = breakLabel;

    select(row: Record<string, unknown>): void {
        const b = row as unknown as ReconBreak;
        this.selectedId.set(breakId(b));
        // A duplicate key's counts are a summary; selecting it opens the rows they summarise.
        if (b.type === 'cardinality_break' && b.keyValues) void this.showRows(b);
    }

    clearSelection(): void {
        this.selectedId.set(null);
    }

    /**
     * Recorded break status/note/first-seen by LIFECYCLE identity — pair included, so the "A vs C" tab
     * overlays the recorded A-vs-C Breaks and never the A-vs-B one on the same key and column.
     */
    private readonly persistedById = computed(() => {
        const m = new Map<string, ReconBreak>();
        for (const b of this.state()?.breaks ?? []) m.set(lifecycleId(b), b);
        return m;
    });

    /**
     * Live breaks with the recorded lifecycle overlaid. `firstSeenAt` always carries (the next recorded run
     * keeps it too); an `auto_closed` record's status/note do not — the Break is live again, so it is open.
     */
    readonly drillBreaks = computed<ReconBreak[]>(() => {
        const live = this.liveBreaks() ?? [];
        const persisted = this.persistedById();
        return live.map((b) => {
            const p = persisted.get(lifecycleId(b));
            if (!p) return b;
            const aged = p.firstSeenAt ? { ...b, firstSeenAt: p.firstSeenAt } : b;
            return p.status !== 'auto_closed' ? { ...aged, status: p.status, note: p.note } : aged;
        });
    });

    readonly missingA = computed(() => this.drillBreaks().filter((b) => b.type === 'missing_right'));
    readonly missingB = computed(() => this.drillBreaks().filter((b) => b.type === 'missing_left'));
    readonly valueBreaks = computed(() => this.drillBreaks().filter((b) => b.type === 'value_break'));
    /** Duplicate keys (cardinality Breaks) — shown only when the Reconciliation declares a cardinality. */
    readonly cardinality = computed(() => reconCardinality(this.recon()));
    readonly duplicates = computed(() =>
        this.cardinality() ? this.drillBreaks().filter((b) => b.type === 'cardinality_break') : [],
    );
    readonly resolvedCount = computed(() => this.drillBreaks().filter((b) => b.status === 'resolved').length);

    /** Aging histogram over the open breaks in this scope (`BREAK-AGING-1`); the rollup is shared. */
    readonly ageBuckets = computed(() => openAgeBuckets(this.drillBreaks()));
    readonly ageLabel = ageBucketLabel;

    /** Whole days a break has been open, or an em-dash when it carries no first-seen stamp. */
    readonly ageText = (b: ReconBreak): string => {
        const days = breakAgeDays(b);
        return days === null ? '—' : `${days}d`;
    };

    /** Key + impact + status (+ actions) — the shape of the two missing-side tables. */
    readonly missingColumns = computed<ColDef<ReconBreak>[]>(() => [
        { field: 'key', headerName: 'Key', flex: 1 },
        ...this.impactColumn(),
        {
            field: 'status',
            headerName: 'Status',
            width: 130,
            cellRenderer: (p: ICellRendererParams<ReconBreak>) => statusBadgeHtml(p.value as string),
        },
    ]);

    /** The compared side's letter — the rows carry roles a/b, but on a 3-way "A vs C" the compared side is C. */
    readonly sideLetter = computed(() => (this.side() === 'c' ? 'C' : 'B'));

    /** `A 1 · C 2` — the record count per side at a duplicate key (the Break's evidence). */
    readonly recordsText = (b: ReconBreak): string =>
        `A ${fmtVal(b.leftValue)} · ${this.sideLetter()} ${fmtVal(b.rightValue)}`;

    /** The "one" side(s) that repeat the key, e.g. `C — cbs_subscribers`. */
    readonly duplicatedSideText = (b: ReconBreak): string => {
        const card = this.cardinality();
        if (!card) return '—';
        const names: string[] = [];
        for (const s of oneSides(card)) {
            const n = Number(s === 'a' ? b.leftValue : b.rightValue);
            if (n > 1)
                names.push(s === 'a' ? `A — ${this.leftLabel()}` : `${this.sideLetter()} — ${this.sideDataset()}`);
        }
        return names.join(', ') || '—';
    };

    readonly duplicateColumns = computed<ColDef<ReconBreak>[]>(() => [
        { field: 'key', headerName: 'Key', flex: 1 },
        {
            colId: 'duplicatedSide',
            headerName: 'Repeated on',
            flex: 1,
            minWidth: 180,
            valueGetter: (p) => (p.data ? this.duplicatedSideText(p.data) : ''),
        },
        {
            colId: 'records',
            headerName: 'Records',
            width: 140,
            valueGetter: (p) => (p.data ? this.recordsText(p.data) : ''),
        },
        ...this.impactColumn(),
        {
            field: 'status',
            headerName: 'Status',
            width: 130,
            cellRenderer: (p: ICellRendererParams<ReconBreak>) => statusBadgeHtml(p.value as string),
        },
    ]);

    readonly valueColumns = computed<ColDef<ReconBreak>[]>(() => {
        const r = this.recon();
        return [
            { field: 'key', headerName: 'Key', flex: 1 },
            {
                // UIE-10: the field-level diff, `field: A → B`, named by the two Datasets it compares.
                colId: 'fieldDiff',
                headerName: `Field diff (${this.leftLabel()} → ${this.sideDataset()})`,
                headerTooltip: `A = ${r?.leftDataset ?? ''} → ${this.sideLetter()} = ${this.sideDatasetId()}`,
                flex: 1,
                minWidth: 220,
                valueGetter: (p) => (p.data ? fieldDiff(p.data) : ''),
                // R3-03: the cell reads the humanised field; the stored column name stays one hover away.
                tooltipValueGetter: (p) => p.data?.column ?? '',
            },
            { field: 'diff', headerName: 'Δ', width: 120, cellRenderer: varianceCell() },
            ...this.impactColumn(),
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

    /** The Duplicate keys table's actions: the shared ones plus the rows behind the key. */
    readonly duplicateActions: InspectoRowAction<ReconBreak>[] = [
        ...this.rowActions,
        {
            icon: 'heroicons_outline:table-cells',
            hint: () => 'Show the rows behind this break',
            visible: (b) => !!b.keyValues,
            onClick: (b) => this.showRows(b),
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
                headerName: r ? this.leftLabel() : 'Left',
                headerTooltip: r?.leftDataset,
                flex: 1,
                valueFormatter: (p) => fmtVal(p.value),
            },
            {
                field: 'rightValue',
                headerName: r ? this.rightLabel() : 'Right',
                headerTooltip: r?.rightDataset,
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
        // Labels only: a failed read leaves the sides named by their ids.
        this.datasetsApi
            .list()
            .subscribe({ next: (d) => this.datasetNames.set(datasetLabels(d)), error: () => undefined });
        this.api.get(id).subscribe({
            next: (r) => {
                this.recon.set(r);
                this.loading.set(false);
                this.loadPromoted(r.id);
                this.loadState(r.id);
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

    /**
     * Read the recorded lifecycle. A failure toasts: without it every Break would read as open and ageless,
     * which is a wrong answer, not a missing hint.
     */
    private loadState(reconId: string): void {
        this.reconApi.state(reconId).subscribe({
            next: (s) => this.state.set(s),
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not load the recorded Break lifecycle')),
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
        const side = this.side();
        try {
            const sets = await this.exec.breaks(r, this.path(), null, side);
            // Tagged with the pair they were computed on — the recorded state holds both pairs of a 3-way.
            const pair = side === 'c' ? 'AC' : 'AB';
            this.liveBreaks.set(breaksFromSets(r, sets).map((b) => ({ ...b, pair })));
            this.impacts.set(breakImpacts(r, sets));
            this.dupImpacts.set(duplicateImpacts(r, sets));
            this.lastEvaluated.set(new Date());
        } catch (e) {
            this.liveBreaks.set(null);
            this.impacts.set({});
            this.dupImpacts.set({});
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
        // The run is the last RECORDED one (R2-03) — the evidence Ops can trace back to.
        this.reconApi.promote(r.id, b.key, b.type, b.column ?? null, this.state()?.lastRunAt ?? null).subscribe({
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

    /**
     * Resolve / re-open one break — recorded server-side by identity (`POST /recon/{id}/breaks/status`,
     * `canOperateRuns`); a live Break no run has recorded yet is appended by the server on first touch.
     */
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
        this.reconApi.setBreakStatus(r.id, b, resolving ? 'resolved' : 'open').subscribe({
            next: (res) => this.state.set(withRecorded(this.state(), r.id, res.break)),
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not update the break')),
        });
    }
}

/** `state` with `b` replacing every recorded Break of its lifecycle identity, or appended when it had none. */
function withRecorded(state: ReconState | null, reconciliation: string, b: ReconBreak): ReconState {
    const base = state ?? { reconciliation, lastRunAt: null, runs: 0, breaks: [] };
    const id = lifecycleId(b);
    const known = base.breaks.some((x) => lifecycleId(x) === id);
    return {
        ...base,
        breaks: known ? base.breaks.map((x) => (lifecycleId(x) === id ? b : x)) : [...base.breaks, b],
    };
}

function breakLabel(type: string): string {
    return type === 'missing_left'
        ? 'missing left'
        : type === 'missing_right'
          ? 'missing right'
          : type === 'value_break'
            ? 'value break'
            : type === 'cardinality_break'
              ? 'duplicate key'
              : type;
}
/**
 * `Monthly fee (SAR): 149 → 99` — one mismatched field of a value break, A side first, the column named as the
 * Board names columns (`humanizeColumn`, R3-03); the raw name is the cell's tooltip.
 */
export function fieldDiff(b: ReconBreak): string {
    return `${b.column ? humanizeColumn(b.column) : '—'}: ${fmtVal(b.leftValue)} → ${fmtVal(b.rightValue)}`;
}
function fmtVal(v: unknown): string {
    if (v == null) return '—';
    const n = Number(v);
    return Number.isNaN(n) ? String(v) : n.toLocaleString(undefined, { maximumFractionDigits: 2 });
}
