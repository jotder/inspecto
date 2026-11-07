import { HttpErrorResponse } from '@angular/common/http';
import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    ElementRef,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    untracked,
    viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatRadioModule } from '@angular/material/radio';
import { RouterLink } from '@angular/router';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { LockedActionComponent } from '@inspecto/core/components/locked-action.component';
import { AddDataAccess } from './add-data-access';
import {
    AddFileMode,
    Arrival,
    Arrivals,
    ImportDataset,
    ImportSummary,
    isSnapshot,
    keepsRowsLine,
    RowCondition,
    ArrivalRow,
    arrivalColumns,
    arrivalRow,
    datasetFacts,
    fileProblem,
} from './add-data.model';
import { DataTableComponent } from '@inspecto/core/data-table';
import { ImportSummaryComponent } from './import-summary.component';
import { ImportFilterEditComponent } from './import-filter-edit.component';
import { DatasetFeedComponent } from './dataset-feed.component';
import { DatasetStorageComponent } from './dataset-storage.component';
import { ReferenceCoverageComponent } from './reference-coverage.component';
import { ImportRunFailed, OnboardService } from './onboard.service';

/**
 * The **Dataset card** of an imported Dataset (LA-ONBOARD-1 phase 4, UC-06, UC-14; console B4's manual-run gesture):
 * "one-time import", the last import in plain words, and **Add a file** beside **Run now** (DT-14: Basic is manual, so the
 * two sit together). *Keep this up to date* is locked with its reason on Basic; on Standard and above it hands off to the
 * console's guided editor when the host has one (`editorRoute`) and the person may author, else it says to ask an
 * administrator. Inspecto LA never edits a Pipeline itself (A-6).
 *
 * @example <inspecto-import-dataset-card [dataset]="d" [summary]="last" (imported)="last = $event" />
 *
 * A host may add to the card (all optional): `[cardActions]` at the header's end, `[cardSummary]` under the description,
 * and anything else at the card's end (the console Datasets pane adds the B4 summary and *What this Dataset can feed*).
 */
@Component({
    selector: 'inspecto-import-dataset-card',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        RouterLink,
        MatButtonModule,
        MatIconModule,
        MatRadioModule,
        MatCheckboxModule,
        InspectoAlertComponent,
        ChipComponent,
        LockedActionComponent,
        ImportSummaryComponent,
        DataTableComponent,
        DatasetFeedComponent,
        DatasetStorageComponent,
        ImportFilterEditComponent,
        ReferenceCoverageComponent,
    ],
    host: { class: 'block' },
    template: `
        <article
            class="bg-card flex flex-col gap-3 rounded-2xl border p-4"
            [attr.aria-labelledby]="titleId"
            data-testid="import-dataset-card"
        >
            <header class="flex flex-wrap items-center gap-2">
                <mat-icon class="icon-size-5" svgIcon="heroicons_outline:table-cells" aria-hidden="true"></mat-icon>
                <h3 class="m-0 text-base font-semibold" [id]="titleId">{{ dataset().id }}</h3>
                <inspecto-chip variant="soft">{{ snapshot() ? 'Postgres snapshot' : 'one-time import' }}</inspecto-chip>
                <span class="text-secondary text-sm">{{ kindWords() }}</span>
                <span class="ml-auto flex items-center gap-1"><ng-content select="[cardActions]"></ng-content></span>
            </header>
            <!-- LA-STREAM-FACTS-1: the Dataset's facts as labelled rows, not sentences -->
            @if (facts().length) {
                <dl class="m-0 grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2" data-testid="dataset-facts">
                    @for (f of facts(); track f.id) {
                        <div class="flex min-w-0 flex-col">
                            <dt class="text-secondary text-xs font-semibold uppercase tracking-wider">{{ f.label }}</dt>
                            <dd class="m-0 break-words" [attr.data-testid]="f.id">{{ f.value }}</dd>
                        </div>
                    }
                </dl>
            }
            <ng-content select="[cardSummary]"></ng-content>
            @if (dataset().kind === 'reference') {
                <inspecto-la-reference-coverage [dataset]="dataset().id" [refresh]="arrivalsAnswer()" />
            }
            @if (last(); as s) {
                <div class="flex flex-col gap-1">
                    <h4 class="m-0 text-sm font-semibold">Last import</h4>
                    <inspecto-import-summary [summary]="s"></inspecto-import-summary>
                </div>
            }
            @if (arrivals().length) {
                <section
                    class="flex flex-col gap-1"
                    [attr.aria-labelledby]="titleId + '-arrivals'"
                    data-testid="arrivals"
                >
                    <h4 class="m-0 text-sm font-semibold" [id]="titleId + '-arrivals'">Arrivals</h4>
                    <!-- LA-STREAM-FACTS-1: one row per file, newest first, ten to a page -->
                    <inspecto-data-table
                        tier="mini"
                        [rows]="arrivalRows()"
                        [columns]="arrivalColumns"
                        [rowClass]="arrivalRowClass"
                        noRowsTitle="No file arrived yet"
                    ></inspecto-data-table>
                </section>
            }

            @if (dataset().kind === 'stream' && !snapshot()) {
                <mat-radio-group
                    class="flex flex-wrap gap-x-4 text-sm"
                    [formControl]="mode"
                    aria-label="What a new file does"
                    data-testid="add-file-mode"
                >
                    <mat-radio-button value="replace">A new file replaces the data</mat-radio-button>
                    <mat-radio-button value="append">A new file adds its rows</mat-radio-button>
                    @if (arrivalsAnswer()?.eventDateColumn) {
                        <mat-radio-button value="period" data-testid="add-file-period"
                            >A new file replaces the days it covers</mat-radio-button
                        >
                    }
                </mat-radio-group>
            }
            @if (!snapshot()) {
                <mat-checkbox class="text-sm" [formControl]="keepNewColumns" data-testid="keep-new-columns"
                    >Keep new columns as details</mat-checkbox
                >
            }
            <div class="flex flex-wrap items-center gap-2">
                <input
                    #fileInput
                    type="file"
                    class="sr-only"
                    tabindex="-1"
                    aria-hidden="true"
                    (change)="onFile($event)"
                />
                @if (!snapshot()) {
                    <button
                        mat-flat-button
                        color="primary"
                        type="button"
                        data-testid="add-a-file"
                        [disabled]="busy() || !!locked()"
                        (click)="fileInput.click()"
                    >
                        <mat-icon svgIcon="heroicons_outline:arrow-up-tray"></mat-icon>
                        Add a file
                    </button>
                }
                <button
                    mat-stroked-button
                    type="button"
                    data-testid="run-now"
                    [disabled]="busy() || !!locked()"
                    (click)="runNow()"
                >
                    <mat-icon
                        [svgIcon]="snapshot() ? 'heroicons_outline:arrow-path' : 'heroicons_outline:play'"
                    ></mat-icon>
                    {{ snapshot() ? 'Refresh now' : 'Run now' }}
                </button>
            </div>
            @if (snapshot()) {
                <p class="text-secondary m-0 text-xs" data-testid="snapshot-note">
                    Refresh now copies the table again and replaces everything in {{ dataset().id }}.
                </p>
            }
            @if (locked(); as why) {
                <p class="text-secondary m-0 text-sm" data-testid="card-locked">{{ why }}</p>
            }
            @if (busy()) {
                <p class="m-0 text-sm" role="status">
                    {{
                        following()
                            ? 'The file is large, so it runs on the server - following it…'
                            : 'Running the import…'
                    }}
                </p>
            }
            @if (error()) {
                <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
            }

            <inspecto-import-filter-edit
                [datasetId]="dataset().id"
                [snapshot]="snapshot()"
                [busy]="busy()"
                [locked]="locked()"
                (saved)="savedFilter.set($event)"
            ></inspecto-import-filter-edit>

            <div class="flex flex-col gap-3 border-t pt-3" data-testid="keep-up-to-date">
                <inspecto-dataset-feed
                    [datasetId]="dataset().id"
                    [snapshot]="snapshot()"
                    [offerKeep]="!snapshot() && !access.automationLock()"
                    [busy]="busy()"
                    [locked]="locked()"
                    (importWaiting)="runNow()"
                ></inspecto-dataset-feed>
                <inspecto-dataset-storage
                    [datasetId]="dataset().id"
                    [busy]="busy()"
                    [locked]="locked()"
                ></inspecto-dataset-storage>
                @if (access.automationLock(); as why) {
                    <inspecto-locked-action label="Keep this up to date" [reason]="why"></inspecto-locked-action>
                } @else if (editorRoute() && access.canAuthor()) {
                    <a
                        mat-stroked-button
                        data-testid="open-in-editor"
                        [routerLink]="editorRoute()"
                        [queryParams]="{ guided: 1, open: pipeline() }"
                    >
                        <mat-icon svgIcon="heroicons_outline:pencil-square"></mat-icon>
                        Open in editor
                    </a>
                    <p class="text-secondary m-0 text-xs">More settings for the import in the guided editor.</p>
                }
            </div>
            <ng-content></ng-content>
        </article>
    `,
})
export class ImportDatasetCardComponent {
    private static nextId = 0;
    protected readonly access = inject(AddDataAccess);
    private readonly onboard = inject(OnboardService);
    private readonly destroyRef = inject(DestroyRef);

    /** The imported Dataset (its registry entry: `origin: import`). */
    readonly dataset = input.required<ImportDataset>();
    /** The last import's summary, when the host has it (the Add data result passes the commit's). */
    readonly summary = input<ImportSummary | null>(null);
    /**
     * The console's Pipelines route (`/pipelines`), whose `?guided=1&open=<pipeline>` opens the guided editor; null in a
     * host without that editor (Inspecto LA), which shows "Ask an administrator" instead.
     */
    readonly editorRoute = input<string | null>(null);
    /** A file was added, or Run now ran: the new summary. */
    readonly imported = output<ImportSummary>();
    /**
     * Every `GET /datasets/{id}/arrivals` answer the card reads (null when the read fails), so a host can show more about
     * the same arrivals without reading them twice (the console Dataset page's *Set aside*, ONBOARDING-FIRST-RUN-1 C5).
     */
    readonly arrivalsLoaded = output<Arrivals | null>();

    protected readonly titleId = `inspecto-import-card-${ImportDatasetCardComponent.nextId++}`;
    /** A Postgres snapshot (BASIC-ONBOARD-1 P3): refreshed from its table, never added to by a file. */
    protected readonly snapshot = computed(() => isSnapshot(this.dataset()));
    /** ADD-DATA-ROW-FILTER-1: "Keeps rows where …" when the import keeps only some rows; '' otherwise. */
    protected readonly keepsRows = computed(() => keepsRowsLine(this.savedFilter() ?? this.dataset().importFilter));
    /** ADD-DATA-FILTER-EDIT-1: the conditions Edit filter saved, until the host passes a fresh entry. */
    protected readonly savedFilter = signal<RowCondition[] | null>(null);
    protected readonly mode = new FormControl<AddFileMode>('replace', { nonNullable: true });
    /** LA-ARRIVAL-NEW-COLUMNS-1 (operator 2026-11-06): a later file's new columns are kept unless unticked. */
    protected readonly keepNewColumns = new FormControl(true, { nonNullable: true });
    protected readonly last = signal<ImportSummary | null>(null);
    protected readonly busy = signal(false);
    protected readonly following = signal(false);
    protected readonly error = signal('');
    protected readonly pipeline = computed(() => this.dataset().importPipeline ?? this.dataset().id);
    protected readonly kindWords = computed(() =>
        this.dataset().kind === 'reference' ? 'Details about entities' : 'Events between entities',
    );
    /** Add a file and Run now follow the Add data lock: the server refuses both the same way. */
    protected readonly locked = computed(() => this.access.lockReason());
    private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');
    /** LA-ARRIVAL-FEEDS-1: the inbox and the feed, re-read after every import. */
    private readonly feedPanel = viewChild(DatasetFeedComponent);
    /** LA-DATASET-COMPACT-1: the files and the last compaction, re-read after every import (it may have compacted). */
    private readonly storagePanel = viewChild(DatasetStorageComponent);

    /** LA-ARRIVAL-LEDGER-1: the last `GET /datasets/{id}/arrivals` answer; null while unread or when the read fails. */
    protected readonly arrivalsAnswer = signal<Arrivals | null>(null);
    /** Every file the import took, newest first. */
    protected readonly arrivals = computed<Arrival[]>(() => this.arrivalsAnswer()?.arrivals ?? []);
    /** LA-STREAM-FACTS-1: the arrivals as table rows; a file whose rows a later replace set aside reads muted. */
    protected readonly arrivalRows = computed<ArrivalRow[]>(() => this.arrivals().map(arrivalRow));
    protected readonly arrivalColumns = arrivalColumns();
    protected readonly arrivalRowClass = (row: unknown): string | undefined =>
        (row as ArrivalRow | undefined)?.kept === false ? 'text-secondary' : undefined;
    /** LA-STREAM-FACTS-1: Origin, Filter, the Data status, the Inbox and Storage as labelled rows. */
    protected readonly facts = computed(() =>
        datasetFacts(this.arrivalsAnswer(), this.dataset().description ?? '', this.keepsRows()),
    );

    constructor() {
        effect(() => {
            const s = this.summary();
            untracked(() => this.last.set(s));
        });
        effect(() => {
            this.dataset();
            untracked(() => this.savedFilter.set(null)); // a fresh entry carries its own importFilter
        });
        effect(() => {
            const id = this.dataset().id;
            untracked(() => this.loadArrivals(id));
        });
    }

    private arrivalsRead?: Subscription;

    /** Re-read the arrivals; a read still in flight is dropped, so an older answer never overwrites a newer one. */
    private loadArrivals(id: string): void {
        this.arrivalsRead?.unsubscribe();
        this.arrivalsRead = this.onboard
            .arrivals(id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (r) => {
                    this.arrivalsAnswer.set(r);
                    this.arrivalsLoaded.emit(r);
                },
                error: () => {
                    this.arrivalsAnswer.set(null); // the card stays usable; only the history is missing
                    this.arrivalsLoaded.emit(null);
                },
            });
    }

    protected onFile(event: Event): void {
        const el = event.target as HTMLInputElement;
        const file = el.files?.[0] ?? null;
        el.value = ''; // the same file can be chosen again
        if (!file) return;
        const problem = fileProblem(file, null);
        if (problem) {
            this.error.set(problem);
            return;
        }
        const mode = this.dataset().kind === 'stream' ? this.mode.value : 'replace';
        this.start(
            this.onboard.addFile(
                this.dataset().id,
                file,
                mode,
                () => this.following.set(true),
                this.keepNewColumns.value,
            ),
        );
    }

    protected runNow(): void {
        this.start(this.onboard.runNow(this.dataset().id, () => this.following.set(true)));
    }

    private start(run: ReturnType<OnboardService['runNow']>): void {
        this.busy.set(true);
        this.following.set(false);
        this.error.set('');
        run.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: (s) => {
                this.busy.set(false);
                this.last.set(s);
                this.imported.emit(s);
                this.loadArrivals(this.dataset().id);
                this.feedPanel()?.load();
                this.storagePanel()?.load();
            },
            error: (err: unknown) => {
                this.busy.set(false);
                if (err instanceof HttpErrorResponse && err.status === 403)
                    this.access.refusedBy(apiErrorMessage(err, 'Adding data is not allowed here.'));
                this.error.set(cardError(err));
            },
        });
    }

    /** For a spec: the hidden chooser. */
    chooser(): HTMLInputElement | undefined {
        return this.fileInput()?.nativeElement;
    }
}

/** A refused Add a file / Run now in plain words: the server's reason, after what it means. */
export function cardError(err: unknown): string {
    if (err instanceof ImportRunFailed) return `The import did not finish: ${err.message}`;
    const server = apiErrorMessage(err, 'The import did not run.');
    const status = err instanceof HttpErrorResponse ? err.status : 0;
    if (status === 409) return `It cannot run now: ${server}`;
    if (status === 413) return `The file is too large: ${server}`;
    if (status === 422) return `Nothing was imported: ${server}`;
    if (status === 503) return `This Space cannot take data: ${server}`;
    return server;
}
