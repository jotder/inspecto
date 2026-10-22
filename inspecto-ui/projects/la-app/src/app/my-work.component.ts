import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, Observable, of, switchMap } from 'rxjs';
import { ComponentDef, ComponentsService, RunsService } from '@inspecto/core/api';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { TermHelpComponent } from '@inspecto/core/onboarding/term-help.component';
import { EntityListSummary, InvService } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisService, SAVED_VIEW_NOT_EVIDENCE } from '@inspecto/link-analysis/link-analysis/link-analysis.service';
import { ADD_DATA_ROUTE } from './first-run';
import { CaseRow, caseRows, importedDatasets, lastImportLine } from './my-work';

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
                <section class="bg-card flex flex-col gap-2 rounded-2xl border p-4" aria-labelledby="la-cases-title">
                    <h3 id="la-cases-title" class="m-0 text-sm font-semibold">Cases</h3>
                    <p class="text-secondary m-0 text-xs">
                        A <inspecto-term term="case"></inspecto-term> is a saved graph and the
                        <inspecto-term term="entity-list" label="Entity Lists"></inspecto-term> it uses. {{ notEvidence }}
                    </p>
                    @if (cases(); as cs) {
                        @if (cs.length) {
                            <ul class="m-0 flex list-none flex-col gap-2 p-0" data-testid="la-cases">
                                @for (c of cs; track c.id) {
                                    <li class="flex flex-col">
                                        <a
                                            class="font-semibold"
                                            [routerLink]="'/link-analysis'"
                                            [queryParams]="{ view: c.id }"
                                            [attr.aria-label]="'Open the case ' + c.name"
                                            >{{ c.name }}</a
                                        >
                                        @if (c.description) {
                                            <span class="text-secondary text-sm">{{ c.description }}</span>
                                        }
                                        <span class="text-secondary text-xs">
                                            {{ [c.saved, c.lists || 'No Entity List yet'].filter(nonBlank).join(' · ') }}
                                        </span>
                                    </li>
                                }
                            </ul>
                        } @else if (casesFailed()) {
                            <p class="m-0 text-sm" role="status" data-testid="la-cases-failed">
                                The cases could not be read just now.
                                <button type="button" class="text-primary underline" (click)="ngOnInit()">
                                    Try again
                                </button>
                            </p>
                        } @else {
                            <p class="m-0 text-sm" data-testid="la-cases-empty">
                                No case yet. Draw a graph in
                                <a routerLink="/link-analysis">Link Analysis</a>, then choose <em>Save as a case</em>.
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
                                Nothing imported with <a [routerLink]="addData">Add data</a> yet. Data a Pipeline keeps up
                                to date is not listed here.
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
                                  map((b) => lastImportLine(d.id, Array.isArray(b) ? b : [])),
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
