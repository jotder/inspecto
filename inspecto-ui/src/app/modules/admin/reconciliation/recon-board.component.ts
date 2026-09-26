import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ReconApiService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoRowAction } from 'app/inspecto/grid';
import { FlatTreeRow, TreeNode, TreeTableComponent } from 'app/inspecto/tree-table';
import {
    ageBucketLabel,
    bandFor,
    bandGlyph,
    bandTone,
    BoardSide,
    boardColumns,
    buildBoardTree,
    comparedSides,
    datasetLabels,
    DEFAULT_BANDS,
    deltaPct,
    fmtMeasure,
    markBreachesExpanded,
    measureLabel,
    openAgeBuckets,
    Reconciliation,
    ReconciliationsService,
    ReconRunResult,
    ReconState,
    reconciliationTitle,
    SideKey,
} from 'app/inspecto/reconciliation';
import { humanizeColumn } from 'app/inspecto/viz/column-label';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { ReconExecService } from './recon-exec.service';
import { ReconciliationFormDialog, ReconciliationFormResult } from './reconciliation-form.dialog';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/** One measure line of the pinned TOTAL strip. */
interface TotalLine {
    label: string;
    a: string;
    b: string;
    pct: string;
    tone: string;
    glyph: string;
}

/**
 * Reconciliation Board (`/reconciliation/:id`) — the aggregate comparison tree: key columns in selection
 * order form the hierarchy; each compare column shows both sides + a banded Δ% vs the anchor (A).
 * Runs on open via {@link ReconExecService} (server DuckDB, or the offline mirror under mock Studio);
 * the details action drills to the Breaks page carrying the encoded dimension path.
 * Design: `docs/superpower/reconciliation-board-design.md` §4.
 */
@Component({
    selector: 'app-recon-board',
    standalone: true,
    imports: [
        InspectoPageHeaderComponent,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatSlideToggleModule,
        MatTooltipModule,
        TreeTableComponent,
        InspectoEmptyStateComponent,
        ChipComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './recon-board.component.html',
})
export class ReconBoardComponent implements OnInit {
    private reconApi = inject(ReconciliationsService);
    private stateApi = inject(ReconApiService);
    private exec = inject(ReconExecService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private datasetsApi = inject(DatasetsService);

    private tree = viewChild(TreeTableComponent);

    readonly recon = signal<Reconciliation | null>(null);
    readonly result = signal<ReconRunResult | null>(null);
    /** The server-recorded run + Break lifecycle (R2-03) — what the aging strip reads. */
    readonly state = signal<ReconState | null>(null);
    readonly loading = signal(true);
    readonly running = signal(false);
    readonly breachesOnly = signal(false);
    /** Dataset id → readable label, read once on open (R2-16); empty until then, so sides show their ids. */
    readonly datasetNames = signal<Record<string, string>>({});

    readonly bands = computed(() => this.recon()?.bands ?? DEFAULT_BANDS);

    /** The Breaks page's title rule (UIE-10): the business description, falling back to the name (a code). */
    readonly title = computed(() => {
        const r = this.recon();
        return r ? reconciliationTitle(r) : '';
    });

    /** Each side named by its Dataset's readable label, falling back to the id (R2-16). */
    readonly sides = computed<Partial<Record<SideKey, BoardSide>>>(() => {
        const r = this.recon();
        if (!r) return {};
        const names = this.datasetNames();
        const side = (id: string): BoardSide => ({ id, label: names[id] || id });
        return {
            a: side(r.leftDataset),
            b: side(r.rightDataset),
            ...(r.thirdDataset ? { c: side(r.thirdDataset) } : {}),
        };
    });

    readonly keyColumnsLabel = computed(() => (this.recon()?.keyColumns ?? []).map(humanizeColumn).join(' › '));

    readonly subtitle = computed(() => {
        const r = this.recon();
        if (!r) return '';
        const s = this.sides();
        const b = this.bands();
        return (
            (this.title() !== r.name ? r.name + ' · ' : '') +
            `A: ${s.a?.label} ⇄ B: ${s.b?.label}` +
            (s.c ? ` ⇄ C: ${s.c.label}` : '') +
            ` · tree ${this.keyColumnsLabel()}` +
            ` · bands ok < ${b.warnPct}% · warn ${b.warnPct}–${b.breachPct}% · breach > ${b.breachPct}%`
        );
    });

    /**
     * Duplicate-key (cardinality Break) counts per compared side — present only when the server reports them,
     * i.e. when the Reconciliation declares a cardinality. Per pair, because on a 3-way the duplicates can sit
     * on C alone (RA-C01: CBS bills 4 MSISDNs twice) and the flat summary mirrors A↔B only.
     */
    readonly duplicateCounts = computed(() => {
        const s = this.result()?.summary;
        if (!s) return [];
        const pairs = s.pairs?.length
            ? s.pairs
            : [{ side: 'b' as const, matchedKeys: s.matchedKeys, byType: s.byType }];
        return pairs
            .filter((p) => typeof p.byType.cardinality_break === 'number')
            .map((p) => ({
                label: pairs.length > 1 ? `duplicate keys A·${p.side.toUpperCase()}` : 'duplicate keys',
                count: p.byType.cardinality_break!,
            }));
    });

    /**
     * Open breaks by age across the WHOLE reconciliation (`BREAK-AGING-1`) — the Board is the landing
     * page, so "how long has this been broken" belongs here as well as on the Breaks page. Same shared
     * rollup as the Breaks page, so the two can never disagree.
     */
    readonly ageBuckets = computed(() => openAgeBuckets(this.state()?.breaks ?? []));
    readonly ageLabel = ageBucketLabel;

    readonly treeNodes = computed<TreeNode[]>(() => {
        const r = this.result();
        if (!r) return [];
        const nodes = buildBoardTree(r, this.bands());
        return this.breachesOnly() ? markBreachesExpanded(nodes, this.bands()) : nodes;
    });

    readonly treeColumns = computed<ColDef[]>(() => {
        const r = this.result();
        return r ? boardColumns(r, this.bands(), { includeValues: true, sides: this.sides() }) : [];
    });

    readonly totalLines = computed<TotalLine[]>(() => {
        const r = this.result();
        if (!r) return [];
        const sides = comparedSides(r);
        const lines: TotalLine[] = [];
        for (const m of r.measures) {
            const label = measureLabel(m);
            const a = r.totals.a[m];
            for (const s of sides) {
                const v = (s === 'c' ? r.totals.c : r.totals.b)?.[m] ?? null;
                const pct = deltaPct(a, v);
                const band = pct === null ? 'structural' : bandFor(pct, this.bands());
                lines.push({
                    label: sides.length > 1 ? `${label} A·${s.toUpperCase()}` : label,
                    a: fmtMeasure(a),
                    b: fmtMeasure(v),
                    pct: pct === null ? 'n/a' : `${pct > 0 ? '+' : ''}${pct.toFixed(1)}%`,
                    tone: bandTone(band),
                    glyph: bandGlyph(band),
                });
            }
        }
        return lines;
    });

    readonly rowActions: InspectoRowAction<FlatTreeRow>[] = [
        {
            icon: 'heroicons_outline:magnifying-glass',
            hint: 'View breaks under this path',
            onClick: (row) => this.viewBreaks(String(row['__path'] ?? '')),
        },
    ];

    ngOnInit(): void {
        const id = this.route.snapshot.paramMap.get('id') ?? '';
        // Labels only: a failed read leaves the sides named by their ids.
        this.datasetsApi
            .list()
            .subscribe({ next: (d) => this.datasetNames.set(datasetLabels(d)), error: () => undefined });
        this.reconApi.get(id).subscribe({
            next: (r) => {
                this.recon.set(r);
                this.loading.set(false);
                void this.run();
            },
            error: (e) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, `Could not load reconciliation "${id}"`));
            },
        });
    }

    /**
     * Run the aggregate comparison, then RECORD the run (R2-03): the server computes every Break of the saved
     * Reconciliation itself, merges the locked lifecycle (re-matched keys auto-close, resolutions and
     * first-seen stamps carry forward) and stamps the run — gated `canOperateRuns`, so an operations-only
     * user records it too. A failed record is toasted and the Board keeps the last RECORDED lifecycle.
     */
    async run(): Promise<void> {
        const r = this.recon();
        if (!r || this.running()) return;
        this.running.set(true);
        try {
            this.result.set(await this.exec.run(r));
        } catch (e) {
            this.result.set(null);
            this.toastr.error(apiErrorMessage(e, 'Reconciliation run failed'));
            return;
        } finally {
            this.running.set(false);
        }
        this.stateApi.record(r.id).subscribe({
            next: (s) => this.state.set(s),
            error: (e) => {
                this.toastr.error(apiErrorMessage(e, 'This run was not recorded'));
                this.stateApi.state(r.id).subscribe({ next: (s) => this.state.set(s), error: () => undefined });
            },
        });
    }

    edit(): void {
        const r = this.recon();
        if (!r) return;
        this.dialog
            .open(ReconciliationFormDialog, { width: '640px', maxHeight: '85vh', data: { recon: r } })
            .afterClosed()
            .subscribe((result?: ReconciliationFormResult) => {
                if (!result) return;
                const updated: Reconciliation = {
                    ...r,
                    name: result.name,
                    leftDataset: result.leftDataset,
                    rightDataset: result.rightDataset,
                    thirdDataset: result.thirdDataset,
                    keyColumns: result.keyColumns,
                    compareColumns: result.compareColumns,
                    bands: result.bands,
                };
                this.reconApi.save(updated).subscribe({
                    next: () => {
                        this.recon.set(updated);
                        void this.run();
                    },
                    error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not save the reconciliation')),
                });
            });
    }

    viewBreaks(path?: string): void {
        const r = this.recon();
        if (!r) return;
        void this.router.navigate(['/reconciliation', r.id, 'breaks'], path ? { queryParams: { path } } : {});
    }

    expandAll(): void {
        this.tree()?.expandAll();
    }

    collapseAll(): void {
        this.tree()?.collapseAll();
    }

    exportCsv(): void {
        this.tree()?.exportCsv();
    }
}
