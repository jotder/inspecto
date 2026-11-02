import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, Observable, of, switchMap } from 'rxjs';
import { ComponentDef, ComponentsService, RunsService } from '@inspecto/core/api';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { TermHelpComponent } from '@inspecto/core/onboarding/term-help.component';
import { EntityListSummary, InvService } from '@inspecto/link-analysis/api/inv.service';
import {
    LinkAnalysisService,
    SAVED_VIEW_NOT_EVIDENCE,
} from '@inspecto/link-analysis/link-analysis/link-analysis.service';
import { ADD_DATA_ROUTE } from './first-run';
import { CaseRow, caseRows, filterCases, importedDatasets, lastImportLine } from './my-work';

/**
 * **My work** on the Inspecto LA home page (LA-ONBOARD-1 phase 7; UC-13, UC-14, A-1b): the cases - saved views with the
 * Entity Lists they use - most recent first, each opening its graph; and **What changed in your data**, each imported
 * Dataset's last import read from its Pipeline's run ledger. Every read degrades on its own: a failed one shows as
 * nothing, never as an error page.
 */
@Component({
    selector: 'la-app-my-work',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [RouterLink, InspectoSkeletonComponent, TermHelpComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-4" aria-labelledby="la-my-work-title" data-testid="la-my-work">
            <h2 id="la-my-work-title" class="m-0 text-lg font-semibold">My work</h2>
            <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
                <section
                    class="bg-card flex flex-col gap-2 rounded-2xl border p-4 md:col-span-2"
                    aria-labelledby="la-cases-title"
                >
                    <div class="flex flex-wrap items-center gap-3">
                        <h3 id="la-cases-title" class="m-0 text-sm font-semibold">Saved views</h3>
                        @if (cases()?.length) {
                            <input
                                type="search"
                                class="bg-default ml-auto w-full max-w-xs rounded-md border px-3 py-1 text-sm"
                                placeholder="Search saved views"
                                aria-label="Search saved views"
                                data-testid="la-cases-search"
                                [value]="query()"
                                (input)="search($any($event.target).value)"
                            />
                        }
                    </div>
                    <p class="text-secondary m-0 text-xs">
                        A saved view is a graph you keep and the
                        <inspecto-term term="entity-list" label="Entity Lists"></inspecto-term> it uses.
                        {{ notEvidence }}
                        Most recently saved first.
                    </p>
                    @if (cases(); as cs) {
                        @if (cs.length) {
                            @if (matches().length) {
                                <ul
                                    class="m-0 grid list-none grid-cols-1 gap-3 p-0 md:grid-cols-2 xl:grid-cols-3"
                                    data-testid="la-cases"
                                >
                                    @for (c of page(); track c.id) {
                                        <li class="flex flex-col gap-1 rounded-xl border p-3">
                                            <a
                                                class="truncate font-semibold"
                                                [routerLink]="'/link-analysis'"
                                                [queryParams]="{ view: c.id }"
                                                [attr.aria-label]="'Open the saved view ' + c.name"
                                                [title]="c.name"
                                                >{{ c.name }}</a
                                            >
                                            @if (c.description) {
                                                <span
                                                    class="text-secondary line-clamp-2 text-sm"
                                                    [title]="c.description"
                                                    >{{ c.description }}</span
                                                >
                                            }
                                            <span class="text-secondary mt-auto text-xs">
                                                {{
                                                    [c.saved, c.lists || 'No Entity List yet']
                                                        .filter(nonBlank)
                                                        .join(' · ')
                                                }}
                                            </span>
                                        </li>
                                    }
                                </ul>
                            } @else {
                                <p class="m-0 text-sm" role="status" data-testid="la-cases-no-match">
                                    No saved view matches "{{ query() }}".
                                </p>
                            }
                            <div class="flex flex-wrap items-center gap-3 text-xs" data-testid="la-cases-pager">
                                <label class="flex items-center gap-1">
                                    Per page
                                    <select
                                        class="bg-default rounded border px-1 py-0.5"
                                        aria-label="Saved views per page"
                                        [value]="pageSize()"
                                        (change)="setPageSize(+$any($event.target).value)"
                                    >
                                        @for (n of pageSizes; track n) {
                                            <option [value]="n" [selected]="n === pageSize()">{{ n }}</option>
                                        }
                                    </select>
                                </label>
                                <span class="ml-auto" role="status">
                                    {{ rangeLabel() }}
                                </span>
                                <button
                                    type="button"
                                    class="rounded border px-2 py-0.5 disabled:opacity-40"
                                    aria-label="Previous page of saved views"
                                    [disabled]="pageIndex() === 0"
                                    (click)="pageIndex.set(pageIndex() - 1)"
                                >
                                    ‹
                                </button>
                                <button
                                    type="button"
                                    class="rounded border px-2 py-0.5 disabled:opacity-40"
                                    aria-label="Next page of saved views"
                                    [disabled]="pageIndex() >= pageCount() - 1"
                                    (click)="pageIndex.set(pageIndex() + 1)"
                                >
                                    ›
                                </button>
                            </div>
                        } @else if (casesFailed()) {
                            <p class="m-0 text-sm" role="status" data-testid="la-cases-failed">
                                The saved views could not be read just now.
                                <button type="button" class="text-primary underline" (click)="ngOnInit()">
                                    Try again
                                </button>
                            </p>
                        } @else {
                            <p class="m-0 text-sm" data-testid="la-cases-empty">
                                No saved view yet. Draw a graph in
                                <a routerLink="/link-analysis">Link Analysis</a>, then choose
                                <em>File ▸ Save view as…</em>.
                            </p>
                        }
                    } @else {
                        <inspecto-skeleton [lines]="2"></inspecto-skeleton>
                    }
                </section>
                <section class="bg-card flex flex-col gap-2 rounded-2xl border p-4" aria-labelledby="la-changed-title">
                    <h3 id="la-changed-title" class="m-0 text-sm font-semibold">What changed in your data</h3>
                    @if (changes(); as ch) {
                        @if (ch.length) {
                            <ul class="m-0 flex list-none flex-col gap-1 p-0 text-sm" data-testid="la-changes">
                                @for (line of ch; track line) {
                                    <li>{{ line }}</li>
                                }
                            </ul>
                        } @else {
                            <p class="m-0 text-sm" data-testid="la-changes-empty">
                                Nothing imported with <a [routerLink]="addData">Add data</a> yet. Data a Pipeline keeps
                                up to date is not listed here.
                            </p>
                        }
                    } @else {
                        <inspecto-skeleton [lines]="2"></inspecto-skeleton>
                    }
                </section>
            </div>
        </section>
    `,
})
export class MyWorkComponent implements OnInit {
    private readonly components = inject(ComponentsService);
    private readonly views = inject(LinkAnalysisService);
    private readonly inv = inject(InvService);
    private readonly runs = inject(RunsService);

    protected readonly notEvidence = SAVED_VIEW_NOT_EVIDENCE;
    protected readonly addData = ADD_DATA_ROUTE;
    protected readonly nonBlank = (s: string): boolean => !!s;
    /** The cases, most recent first; null while they load. */
    readonly cases = signal<CaseRow[] | null>(null);
    /** The saved views could not be read (the empty state then says so instead of "No case yet"). */
    readonly casesFailed = signal(false);
    protected readonly pageSizes = [5, 10, 25] as const;
    readonly query = signal('');
    readonly pageSize = signal<number>(5);
    readonly pageIndex = signal(0);
    /** The cases the search keeps, still most recent first. */
    readonly matches = computed(() => filterCases(this.cases() ?? [], this.query()));
    readonly pageCount = computed(() => Math.max(1, Math.ceil(this.matches().length / this.pageSize())));
    readonly page = computed(() => {
        const start = Math.min(this.pageIndex(), this.pageCount() - 1) * this.pageSize();
        return this.matches().slice(start, start + this.pageSize());
    });
    readonly rangeLabel = computed(() => {
        const n = this.matches().length;
        if (!n) return '0 of 0';
        const start = Math.min(this.pageIndex(), this.pageCount() - 1) * this.pageSize();
        return `${start + 1}–${Math.min(start + this.pageSize(), n)} of ${n}`;
    });

    search(q: string): void {
        this.query.set(q);
        this.pageIndex.set(0);
    }

    setPageSize(n: number): void {
        this.pageSize.set(n);
        this.pageIndex.set(0);
    }
    /** One line per imported Dataset, the latest import first; null while they load. */
    readonly changes = signal<string[] | null>(null);

    ngOnInit(): void {
        this.cases.set(null);
        this.casesFailed.set(false);
        forkJoin({
            defs: this.components.list('link-analysis-view').pipe(
                catchError(() => {
                    this.casesFailed.set(true);
                    return of([] as ComponentDef[]);
                }),
            ),
            lists: this.inv.listEntityLists().pipe(
                map((i) => i?.lists ?? []),
                catchError(() => of([] as EntityListSummary[])),
            ),
        }).subscribe(({ defs, lists }) =>
            this.cases.set(caseRows(Array.isArray(defs) ? defs : [], (id, c) => this.views.fromContent(id, c), lists)),
        );
        this.whatChanged().subscribe((lines) => this.changes.set(lines));
    }

    private whatChanged(): Observable<string[]> {
        return this.components.list('dataset').pipe(
            catchError(() => of([] as ComponentDef[])),
            map((defs) => importedDatasets(Array.isArray(defs) ? defs : [])),
            switchMap((ds) =>
                ds.length
                    ? forkJoin(
                          ds.map((d) =>
                              this.runs.batches(d.pipeline).pipe(
                                  map((b) => lastImportLine(d.id, Array.isArray(b) ? b : [], d.last)),
                                  catchError(() => of(null)),
                              ),
                          ),
                      )
                    : of([]),
            ),
            map((lines) =>
                lines
                    .filter((l): l is { text: string; at: number } => !!l)
                    .sort((a, b) => b.at - a.at)
                    .map((l) => l.text),
            ),
        );
    }
}
