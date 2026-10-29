import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ColDef } from 'ag-grid-community';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { DbBrowserService } from '@inspecto/core/api/db-browser.service';
import { RunsService } from '@inspecto/core/api/runs.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { StatusBadgeComponent, statusBadgeHtml } from '@inspecto/core/components/status-badge.component';
import { DataTableComponent } from '@inspecto/core/data-table';
import {
    DatasetFile,
    DataChange,
    DatasetFilesPage,
    InvService,
    InvestigationCoverage,
} from '@inspecto/link-analysis/api/inv.service';
import { GraphRunsService, LinkIndexSummary } from '@inspecto/link-analysis/api/graph-runs.service';
import { KIND_TEXT, LinkAnalysisCorrectedDaysComponent } from './link-analysis-corrected-days.component';
import { buildStrip, dayCells, DataTrustFacts, daysWindow, formatDay, formatInstant, outcomeTone } from './data-trust';
import { InvestigationSessionStore } from './link-analysis-investigation.store';

const PAGE = 25;
const NO_TIME_COLUMN = 'this Investigation has no time column, so no day can be judged missing.';
const NO_WINDOW = 'this Investigation sets no time window, and its Files name no event day.';

/**
 * LA-DATA-TRUST-1 S2 - what data does this graph stand on? A one-line strip (how fresh, how complete, which index) with an
 * ok / warning / problem badge, and a drawer under it: Fresh, Complete (per-day strip), Files (table), Index.
 *
 * <p>Every read degrades on its own: a failed one leaves its part of the strip saying "not assessed" / "not known", never a
 * blank that reads as "fine". Read-only: nothing here re-processes or re-indexes anything.
 *
 * <p><b>Mount point for banners:</b> project any element carrying the attribute {@code la-data-alerts} into this component
 * and it renders directly under the strip line, above the drawer (the corrected-day warning, S5, and later Alert states go
 * there). The strip owns no Alert logic itself.
 *
 * <p>The corrected-day warning (S5) is mounted inside that area directly: its read feeds the badge (any correction turns
 * it at least WARNING) and the Corrections section of the drawer. Its refresh prompt re-runs every read this strip makes
 * and emits {@code refreshed}, so the host re-reads the Investigation's own data (log + Working Set).
 */
@Component({
    selector: 'la-data-strip',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
        DataTableComponent,
        LinkAnalysisCorrectedDaysComponent,
    ],
    template: `
        <section class="flex flex-col gap-2 text-sm" aria-label="Data this graph stands on" data-test="data-strip">
            <div class="flex flex-wrap items-center gap-2">
                <inspecto-status-badge [value]="strip().tone" [label]="strip().label"></inspecto-status-badge>
                <span data-test="strip-text">{{ strip().text }}</span>
                @if (coverageError()) {
                    <span class="text-secondary text-xs" data-test="strip-days-reason">Days not assessed: {{ coverageError() }}</span>
                }
                <button
                    mat-button
                    class="ml-auto"
                    data-test="strip-toggle"
                    [attr.aria-expanded]="open()"
                    aria-controls="la-data-drawer"
                    (click)="open.set(!open())"
                >
                    {{ open() ? 'Hide data details' : 'Data details' }}
                </button>
            </div>

            <!-- Banner / alerts mount point: project an element with the la-data-alerts attribute. -->
            <div class="flex flex-col gap-2 empty:hidden" data-test="strip-alerts">
                <inspecto-link-analysis-corrected-days
                    [investigationId]="investigationId()"
                    [reloadToken]="reloadToken()"
                    (changesRead)="corrections.set($event)"
                    (refresh)="refresh()"
                ></inspecto-link-analysis-corrected-days>
                <ng-content select="[la-data-alerts]"></ng-content>
            </div>

            @if (open()) {
                <div id="la-data-drawer" class="flex flex-col gap-4 rounded border p-3" data-test="drawer">
                    @if (corrections().length) {
                        <section class="flex flex-col gap-1" aria-label="Corrections">
                            <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                                Corrections since you last looked
                            </h3>
                            <table class="m-0 w-full text-xs" aria-label="Corrected days" data-test="corrections">
                                <thead>
                                    <tr class="text-secondary text-left">
                                        <th scope="col">Day</th>
                                        <th scope="col">Kind</th>
                                        <th scope="col">File</th>
                                        <th scope="col">When</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    @for (c of corrections(); track c.day + c.at + c.file) {
                                        <tr data-test="correction-row">
                                            <th scope="row" class="text-left font-medium tabular-nums">{{ c.day }}</th>
                                            <td>{{ kindText(c) }}</td>
                                            <td>{{ c.file ?? 'not named' }}</td>
                                            <td class="tabular-nums">{{ c.at }}</td>
                                        </tr>
                                    }
                                </tbody>
                            </table>
                        </section>
                    }

                    <section class="flex flex-col gap-1" aria-label="Fresh">
                        <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Fresh</h3>
                        <p class="m-0" data-test="fresh-asof">
                            @if (dataAsOf()) {
                                Data as of {{ asOfText() }} (the newest event time in {{ dataset() }}).
                            } @else {
                                Data as-of date not known: the Dataset names no event-date column, or it holds no rows.
                            }
                        </p>
                        <p class="m-0" data-test="fresh-ingested">
                            @if (files()?.summary?.latestIngestedAt; as at) {
                                Latest File ingested at {{ at }}. This is the Consignment's commit time, not when the
                                File arrived.
                            } @else {
                                Ingested-at not known.
                            }
                            @if (inbox() !== null) {
                                <span data-test="fresh-inbox">
                                    {{ inbox() }} {{ inbox() === 1 ? 'File is' : 'Files are' }} waiting in the inbox.
                                </span>
                            }
                        </p>
                    </section>

                    <section class="flex flex-col gap-1" aria-label="Complete">
                        <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Complete</h3>
                        @if (coverageError()) {
                            <p class="text-secondary m-0" data-test="complete-unassessed">
                                Days are not assessed: {{ coverageError() }}
                            </p>
                        } @else if (coverage(); as c) {
                            <ul
                                class="m-0 flex list-none flex-wrap gap-1 p-0"
                                aria-label="Per-day rows"
                                data-test="day-strip"
                            >
                                @for (d of cells(); track d.date) {
                                    <li class="rounded border px-2 py-1 text-xs">
                                        <span class="tabular-nums">{{ d.date }}</span>
                                        <inspecto-status-badge
                                            variant="dot"
                                            [value]="
                                                d.state === 'ok'
                                                    ? 'success'
                                                    : d.state === 'empty' || d.state === 'pending'
                                                      ? 'info'
                                                      : 'warning'
                                            "
                                            [label]="
                                                d.state === 'ok'
                                                    ? d.rows + ' rows'
                                                    : d.state === 'empty'
                                                      ? '0 rows'
                                                      : d.state === 'pending'
                                                        ? 'not delivered yet'
                                                        : 'no File'
                                            "
                                        ></inspecto-status-badge>
                                    </li>
                                }
                            </ul>
                            @if (coverageOverFiles()) {
                                <p class="text-secondary m-0 text-xs" data-test="coverage-over-files">
                                    This Investigation has no time window, so days are judged over the span the Files
                                    cover; a day before the first File or after the last is not counted.
                                </p>
                            }
                            <p class="text-secondary m-0 text-xs">
                                "no File" = no rows and no File names a day between delivered days (a gap); "not
                                delivered yet" = a day before the first File or after the newest; "0 rows" = a File
                                arrived and held nothing.
                            </p>
                            @if (c.collectors.assessed) {
                                <table class="m-0 w-full text-xs" aria-label="Per-Collector coverage">
                                    <thead>
                                        <tr class="text-secondary text-left">
                                            <th scope="col">Collector</th>
                                            <th scope="col">Days covered</th>
                                            <th scope="col">Missing days</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        @for (k of c.collectors.collectors; track k.collector) {
                                            <tr>
                                                <th scope="row" class="text-left font-medium">{{ k.collector }}</th>
                                                <td>{{ k.coveredDays }} of {{ c.expectedDays }}</td>
                                                <td>{{ k.missingDays.length ? k.missingDays.join(', ') : 'none' }}</td>
                                            </tr>
                                        }
                                    </tbody>
                                </table>
                            } @else {
                                <p class="text-secondary m-0 text-xs">
                                    Per-Collector coverage is not assessed for this Dataset.
                                </p>
                            }
                        } @else {
                            <p class="text-secondary m-0">Reading days…</p>
                        }
                        <p class="text-secondary m-0 text-xs" data-test="complete-basis">
                            Complete is judged on days and File sequence, not against a list of expected Collectors: a
                            Collector that never delivered is not visible here.
                        </p>
                    </section>

                    <section class="flex flex-col gap-1" aria-label="Files">
                        <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Files</h3>
                        @if (filesError()) {
                            <inspecto-alert variant="error" title="Files not read">{{ filesError() }}</inspecto-alert>
                        } @else if (files(); as f) {
                            @if (!f.assessed) {
                                <inspecto-empty-state
                                    title="Files not assessed"
                                    message="No Consignment audit trail attributes this Dataset to a Pipeline, so its Files cannot be listed. This is not the same as having no Files."
                                ></inspecto-empty-state>
                            } @else if (!rows().length) {
                                <inspecto-empty-state
                                    title="No Files yet"
                                    message="The audit trail lists no File for this Dataset."
                                ></inspecto-empty-state>
                            } @else {
                                <inspecto-data-table
                                    tier="standard"
                                    [rows]="rows()"
                                    [columns]="columns"
                                    [pageSize]="10"
                                    [serverPage]="true"
                                    [hasMore]="rows().length < f.total"
                                    [loading]="moreBusy()"
                                    (loadMore)="loadMore()"
                                    exportName="dataset-files"
                                    noRowsTitle="No Files"
                                ></inspecto-data-table>
                            }
                            <p class="text-secondary m-0 text-xs">
                                An unchanged duplicate delivery is skipped silently and is not listed; a File still in
                                the inbox is counted, not listed. Index state is matched from the index's recorded input
                                Files; UNKNOWN means it could not be matched.
                            </p>
                        } @else {
                            <p class="text-secondary m-0">Reading Files…</p>
                        }
                    </section>

                    <section class="flex flex-col gap-1" aria-label="Index">
                        <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Index</h3>
                        @if (index(); as ix) {
                            <p class="m-0" data-test="index-line">
                                Version {{ ix.version }}{{ ix.stale ? ' — stale' : '' }}
                                @if (ix.reason) {
                                    <span> ({{ ix.reason }})</span>
                                }
                                @if (files()?.summary; as s) {
                                    · {{ s.waiting }} {{ s.waiting === 1 ? 'File' : 'Files' }} waiting
                                    @if (s.unknown) {
                                        · {{ s.unknown }} unknown
                                    }
                                }
                                @if (ix.plan && ix.plan.added) {
                                    · {{ ix.plan.added }} partition files added since it was built
                                }
                            </p>
                        } @else {
                            <p class="m-0" data-test="index-line">
                                No edge index for this Dataset{{ indexesRead() ? '' : ' (not read)' }}.
                            </p>
                        }
                    </section>
                </div>
            }
        </section>
    `,
})
export class LinkAnalysisDataStripComponent {
    private readonly inv = inject(InvService);
    private readonly db = inject(DbBrowserService);
    private readonly runs = inject(RunsService);
    private readonly graphRuns = inject(GraphRunsService);
    /** The host's Investigation session, when the strip sits in one: its log tells whether the Investigation sets a window. */
    private readonly session = inject(InvestigationSessionStore, { optional: true });

    /** The open Investigation (the coverage read is over its window). */
    readonly investigationId = input.required<string>();
    /** The Investigation's Dataset. */
    readonly dataset = input.required<string>();
    /** False when the Investigation binds no event-time column: the coverage read would be refused (422), so it is not sent. */
    readonly hasTimeColumn = input(true);

    /** The host re-reads the Investigation's own data (log + Working Set) when the analyst refreshes. */
    readonly refreshed = output<void>();

    readonly open = signal(false);
    readonly corrections = signal<DataChange[]>([]);
    /** Bumped by a refresh so the corrected-day banner re-reads too. */
    readonly reloadToken = signal(0);
    readonly dataAsOf = signal<string | null>(null);
    readonly files = signal<DatasetFilesPage | null>(null);
    readonly filesError = signal('');
    readonly rows = signal<DatasetFile[]>([]);
    readonly moreBusy = signal(false);
    readonly coverage = signal<InvestigationCoverage | null>(null);
    readonly coverageError = signal('');
    /** True when the days were judged over the Files' own span because the Investigation holds no window. */
    readonly coverageOverFiles = signal(false);
    readonly inbox = signal<number | null>(null);

    readonly indexesRead = computed(() => this.graphRuns.indexes() !== null);
    readonly index = computed<LinkIndexSummary | null>(
        () => this.graphRuns.indexes()?.indexes.find((i) => i.dataset === this.dataset()) ?? null,
    );
    readonly asOfText = computed(() => formatDay(this.dataAsOf()));
    readonly cells = computed(() => {
        const c = this.coverage();
        return c ? dayCells(c.perDay, this.files()?.days ?? []) : [];
    });

    readonly strip = computed(() => {
        const c = this.coverage();
        const ix = this.index();
        const facts: DataTrustFacts = {
            dataAsOf: this.dataAsOf(),
            days: c ? { expected: c.expectedDays, covered: c.coveredDays, missing: c.missingDays } : null,
            index: ix ? { version: ix.version, stale: ix.stale } : null,
            files: this.files(),
            correctedDays: this.corrections().map((c) => c.day),
        };
        return buildStrip(facts);
    });

    /** Blank (never 0) for an unmeasured error count; a File with 0 rows shows 0. */
    readonly columns: ColDef[] = [
        { field: 'name', headerName: 'File', flex: 2, minWidth: 160 },
        { field: 'day', headerName: 'Event day', valueFormatter: (p) => p.value ?? '' },
        { field: 'rows', headerName: 'Rows', type: 'rightAligned' },
        {
            field: 'errorRows',
            headerName: 'Error rows',
            type: 'rightAligned',
            valueFormatter: (p) => (p.value == null ? '' : String(p.value)),
        },
        {
            field: 'ingestedAt',
            headerName: 'Ingested at',
            minWidth: 180,
            valueFormatter: (p) => formatInstant(p.value) || (p.value ?? ''),
        },
        {
            field: 'outcome',
            headerName: 'Outcome',
            cellRenderer: (p: { value: string }) => statusBadgeHtml(outcomeTone(p.value), p.value),
        },
        {
            field: 'indexState',
            headerName: 'Index',
            valueGetter: (p) => {
                const f = p.data as DatasetFile | undefined;
                if (!f || !f.indexState) return '';
                return f.indexState === 'INDEXED'
                    ? `INDEXED v${f.indexVersion}`
                    : f.indexState === 'NO_INDEX'
                      ? 'NO INDEX'
                      : f.indexState;
            },
        },
    ];

    constructor() {
        effect(() => {
            const id = this.investigationId();
            const ds = this.dataset();
            this.hasTimeColumn(); // track
            untracked(() => void this.load(id, ds));
        });
    }

    kindText(c: DataChange): string {
        return KIND_TEXT[c.kind] ?? 'its data moved';
    }

    /** "Refresh this Investigation's data": every read the strip makes, the banner's own read, and the host's reads. */
    refresh(): void {
        this.reloadToken.update((n) => n + 1);
        void this.load(this.investigationId(), this.dataset());
        this.refreshed.emit();
    }

    private async load(id: string, ds: string): Promise<void> {
        this.dataAsOf.set(null);
        this.files.set(null);
        this.rows.set([]);
        this.filesError.set('');
        this.coverage.set(null);
        this.coverageError.set('');
        this.coverageOverFiles.set(false);
        this.inbox.set(null);
        this.graphRuns.loadIndexes();
        const current = () => this.investigationId() === id && this.dataset() === ds;

        const freshness = firstValueFrom(this.db.datasetFreshness(ds)).then(
            (f) => current() && this.dataAsOf.set(f.dataAsOf),
            () => undefined,
        );
        const coverage = !this.hasTimeColumn()
            ? this.coverageError.set(NO_TIME_COLUMN)
            : this.setsNoWindow(id)
              ? this.coverageError.set(NO_WINDOW) // the bare read would be refused (422): go straight to the Files' span
              : firstValueFrom(this.inv.investigationCoverage(id)).then(
                  (c) => current() && this.coverage.set(c),
                  (err) => current() && this.coverageError.set(apiErrorMessage(err, 'the coverage read failed.')),
              );
        const files = firstValueFrom(this.inv.datasetFiles(ds, { limit: PAGE })).then(
            (f) => {
                if (!current()) return;
                this.files.set(f);
                this.rows.set(f.items);
                void this.readInbox(f.items, current);
            },
            (err) => current() && this.filesError.set(apiErrorMessage(err, 'Could not read the Files.')),
        );
        await Promise.all([freshness, coverage, files]);
        await this.coverageOverFilesSpan(id, current);
    }

    /**
     * True only when this Investigation's whole log is in hand and holds no Window step: the server then has no window of
     * its own to judge, and the bare coverage read is refused with 422. A log with any Window step (even one set to All
     * time) is left to the server.
     */
    private setsNoWindow(id: string): boolean {
        const log = this.session?.activeId() === id ? this.session.log() : null;
        return !!log && !log.truncated && !log.entries.some((e) => e.op === 'window');
    }

    /** The coverage read refuses an Investigation with no window; retry over the span of the Files so the days are judged. */
    private async coverageOverFilesSpan(id: string, current: () => boolean): Promise<void> {
        const w = this.coverageError() && this.hasTimeColumn() ? daysWindow(this.files()?.days) : null;
        if (!w) return;
        try {
            const c = await firstValueFrom(this.inv.investigationCoverage(id, w));
            if (!current()) return;
            this.coverage.set(c);
            this.coverageError.set('');
            this.coverageOverFiles.set(true);
        } catch {
            // the first error stays: days are "not assessed", never a clean bill
        }
    }

    /** The inbox count of every Pipeline the listed Files came through; null when none could be read. */
    private async readInbox(items: DatasetFile[], current: () => boolean): Promise<void> {
        const pipelines = [...new Set(items.map((i) => i.pipeline).filter(Boolean))];
        if (!pipelines.length) return;
        let total = 0;
        let any = false;
        for (const p of pipelines) {
            try {
                const s = await firstValueFrom(this.runs.pending(p));
                if (s.pending >= 0) {
                    total += s.pending;
                    any = true;
                }
            } catch {
                // an unreadable inbox is "not known", never 0
            }
        }
        if (any && current()) this.inbox.set(total);
    }

    loadMore(): void {
        const ds = this.dataset();
        if (this.moreBusy()) return;
        this.moreBusy.set(true);
        this.inv.datasetFiles(ds, { limit: PAGE, offset: this.rows().length }).subscribe({
            next: (f) => {
                if (this.dataset() === ds) this.rows.update((r) => [...r, ...f.items]);
                this.moreBusy.set(false);
            },
            error: (err) => {
                this.filesError.set(apiErrorMessage(err, 'Could not read more Files.'));
                this.moreBusy.set(false);
            },
        });
    }
}
