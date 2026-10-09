import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, LensService, ReconApiService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import {
    ageBucketLabel,
    bandFor,
    bandGlyph,
    bandTone,
    BoardSide,
    comparedSides,
    comparingMessage,
    encodePath,
    datasetLabels,
    DEFAULT_BANDS,
    deltaPct,
    fmtMeasure,
    lifecycleCounts,
    measureLabel,
    openAgeBuckets,
    Reconciliation,
    ReconciliationsService,
    RECON_DEFAULT_PAGE_SIZE,
    RECON_FILTERS,
    RECON_PAGE_SIZES,
    ReconBands,
    ReconFilter,
    ReconGrainTableComponent,
    ReconRunQuery,
    ReconState,
    ReconToolbarComponent,
    reconciliationTitle,
    SideKey,
} from 'app/inspecto/reconciliation';
import { humanizeColumn } from 'app/inspecto/viz/column-label';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { ReconExecService } from './recon-exec.service';
import { ReconPageLoader } from './recon-page.loader';
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
 * Reconciliation Board (`/reconciliation/:id`) — ONE DAY of the comparison at a time (RECON-PERF-1, operator
 * 2026-10-09): the toolbar's day / filter / sample and the table's page live in the URL query params (view state, never
 * saved); each change requests one server page through {@link ReconPageLoader}, which cancels the request in flight.
 * The rows are band-tinted (no status column); totals and the Break summary are the whole day's. Opening the Board
 * never records — "Record run" (canOperateRuns, the server's gate) records the selected day. The bands are the one
 * saved setting (author only). A row's action drills to the Breaks page for that key and day.
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
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        ReconGrainTableComponent,
        ReconToolbarComponent,
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
    private lens = inject(LensService);
    private datasetsApi = inject(DatasetsService);

    private destroyRef = inject(DestroyRef);

    readonly recon = signal<Reconciliation | null>(null);
    /** ONE day's page (RECON-PERF-1) — every date / page / filter / sample change cancels the request in flight. */
    readonly pages = new ReconPageLoader(this.exec, this.destroyRef);
    readonly result = this.pages.result;
    readonly running = this.pages.loading;
    /** The view state, read from the URL query params (`day`, `filter`, `offset`, `limit`, `sample`) — never saved. */
    readonly query = signal<ReconRunQuery>({ offset: 0, limit: RECON_DEFAULT_PAGE_SIZE, filter: 'all' });
    /** The server-recorded run + Break lifecycle (R2-03) — what the aging strip reads. */
    readonly state = signal<ReconState | null>(null);
    readonly loading = signal(true);
    readonly recording = signal(false);
    readonly savingBands = signal(false);
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
    /** Unresolved Breaks someone owns, and those that came back after auto-closing (ASSURE-BREAK-LIFECYCLE-1). */
    readonly lifecycle = computed(() => lifecycleCounts(this.state()?.breaks ?? []));

    /** "Comparing HLR, CRM and CBS for 2026-09-26…" — what the spinner says while the backend works. */
    readonly comparing = computed(() => {
        const s = this.sides();
        const labels = (['a', 'b', 'c'] as const).map((k) => s[k]?.label).filter((l): l is string => !!l);
        return comparingMessage(labels, this.query().day ?? this.result()?.day);
    });

    /** Side letter → readable label, for the grain table's headers. */
    readonly sideLabels = computed(() => {
        const s = this.sides();
        return { a: s.a?.label, b: s.b?.label, ...(s.c ? { c: s.c.label } : {}) };
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
                // Opening a Board READS the recorded lifecycle; it never records (operator, 2026-10-09).
                this.loadState(r.id);
                this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((q) => {
                    this.query.set(queryFromParams(q));
                    this.pages.load(r, this.query());
                });
            },
            error: (e) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, `Could not load reconciliation "${id}"`));
            },
        });
    }

    /** Move the view (day / page / filter / sample) by rewriting the URL; the param subscription re-requests. */
    setView(patch: Partial<ReconRunQuery>): void {
        const next = { ...this.query(), ...patch };
        void this.router.navigate([], {
            relativeTo: this.route,
            replaceUrl: true,
            queryParams: {
                day: next.day || null,
                filter: next.filter === 'all' ? null : next.filter,
                offset: next.offset || null,
                limit: next.limit === RECON_DEFAULT_PAGE_SIZE ? null : next.limit,
                sample: next.sample || null,
            },
            queryParamsHandling: 'merge',
        });
    }

    /** Re-run the current view (the Refresh button). */
    run(): void {
        const r = this.recon();
        if (r) this.pages.load(r, this.query());
    }

    private loadState(id: string): void {
        this.stateApi.state(id).subscribe({ next: (s) => this.state.set(s), error: () => undefined });
    }

    /** Recording is the operate act the server gates (`canOperateRuns`); the button shows only to who holds it. */
    readonly canRecord = computed(() => this.lens.canOperateRuns());

    /**
     * RECORD a run of the selected day (R2-03 + RECON-PERF-1): the server computes every Break of that day, merges the
     * lifecycle (re-matched keys auto-close, resolutions and first-seen stamps carry forward) and stamps the run. An
     * explicit act — opening the Board no longer records (operator, 2026-10-09).
     */
    record(): void {
        const r = this.recon();
        if (!r || !this.canRecord() || this.recording()) return;
        this.recording.set(true);
        this.stateApi.record(r.id, this.query().day ?? this.result()?.day ?? null).subscribe({
            next: (s) => {
                this.recording.set(false);
                this.state.set(s);
                this.toastr.success(`Recorded the run for ${s.day ?? 'the latest day'}`);
            },
            error: (e) => {
                this.recording.set(false);
                this.toastr.error(apiErrorMessage(e, 'This run was not recorded'));
            },
        });
    }

    /** Save the tolerance bands — the ONE persisted change here, through the author-gated component save. */
    saveBands(bands: ReconBands): void {
        const r = this.recon();
        if (!r || !this.canAuthor()) return;
        const updated: Reconciliation = { ...r, bands };
        this.savingBands.set(true);
        this.reconApi.save(updated).subscribe({
            next: () => {
                this.savingBands.set(false);
                this.recon.set(updated);
            },
            error: (e) => {
                this.savingBands.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the bands'));
            },
        });
    }

    /** Editing writes the `reconciliation` Component, which the server gates on `canAuthorWorkbench` (R3-05). */
    readonly canAuthor = computed(() => this.lens.canAuthorWorkbench());

    edit(): void {
        const r = this.recon();
        if (!r || !this.canAuthor()) return;
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
                        this.run();
                    },
                    error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not save the reconciliation')),
                });
            });
    }

    viewBreaks(path?: string): void {
        const r = this.recon();
        if (!r) return;
        const day = this.query().day ?? this.result()?.day;
        const queryParams = { ...(path ? { path } : {}), ...(day ? { day } : {}) };
        void this.router.navigate(
            ['/reconciliation', r.id, 'breaks'],
            Object.keys(queryParams).length ? { queryParams } : {},
        );
    }

    /** A grain row's "view breaks" action: the Breaks page scoped to that key on this day. */
    viewKey(key: Record<string, string>): void {
        this.viewBreaks(encodePath(key, this.recon()?.keyColumns ?? Object.keys(key)));
    }
}

/** The Board's view state from its URL — anything unknown or out of range falls back to the default. */
export function queryFromParams(q: { get(name: string): string | null }): ReconRunQuery {
    const filter = q.get('filter') as ReconFilter | null;
    const limit = Number(q.get('limit'));
    const offset = Number(q.get('offset'));
    const sample = Number(q.get('sample'));
    const day = q.get('day');
    return {
        day: day && /^\d{4}-\d{2}-\d{2}$/.test(day) ? day : null,
        filter: filter && (RECON_FILTERS as readonly string[]).includes(filter) ? filter : 'all',
        limit: (RECON_PAGE_SIZES as readonly number[]).includes(limit) ? limit : RECON_DEFAULT_PAGE_SIZE,
        offset: Number.isInteger(offset) && offset > 0 ? offset : 0,
        sample: Number.isInteger(sample) && sample > 0 ? sample : null,
    };
}
