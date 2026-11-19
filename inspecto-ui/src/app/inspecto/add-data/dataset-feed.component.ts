import { HttpErrorResponse } from '@angular/common/http';
import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
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
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { CollectorConfigComponent, CollectorMode } from '@inspecto/core/collector/collector-config.component';
import { COLLECTOR_ATTRIBUTES } from '@inspecto/core/component-model/collector-attributes';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent } from '@inspecto/core/components/option-picker.component';
import { AddDataAccess } from './add-data-access';
import {
    DatasetFeedState,
    EVERY_OPTIONS,
    everyWords,
    feedWords,
    waitingFileLine,
    waitingWords,
} from './dataset-feed.model';
import { OnboardService } from './onboard.service';

/**
 * The Collector questions *Keep this up to date* asks (BASIC-ELT-PROFILE-1 U4-Keep): the shared Collector spec's own
 * entries, limited to what its route saves (`PUT /datasets/{id}/feed`: the Connection and the ready marker). The
 * server writes the rest of the `collector:` block itself (`DatasetFeed`), so asking more would ask for nothing.
 */
const KEEP_KEYS = ['connection', 'stability__ready_marker'];
const KEEP_SPECS = COLLECTOR_ATTRIBUTES.filter((a) => KEEP_KEYS.includes(a.key));
/** A feed reads the Dataset's inbox or an SFTP Connection, never another Dataset. */
const KEEP_MODES: CollectorMode[] = ['local', 'connection'];
const KEEP_CONNECTORS = ['sftp'];

/**
 * How files reach an import Dataset (`LA-ARRIVAL-FEEDS-1`, plan P3, D-1), inside the Dataset card:
 * - **Inbox** (every edition): the folder on the server another system writes files into, *N files waiting* and
 *   **Import waiting files** - the card's Run now (`importWaiting`); and **Watch this folder** (`BASIC-ONBOARD-1` P2,
 *   Basic and up): the inbox is looked at every 30 seconds and imported the same way once its files have settled.
 * - **Keep this up to date** (`offerKeep`, Standard and above): the inbox watched, or an SFTP Connection read, on a
 *   schedule; then the proposed index Job is saved through `POST /jobs` when the person may author, so the graph follows.
 *   *Stop* goes back to uploads.
 *
 * @example <inspecto-dataset-feed [datasetId]="d.id" [offerKeep]="!lock" [busy]="busy()" (importWaiting)="runNow()" />
 */
@Component({
    selector: 'inspecto-dataset-feed',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatIconModule,
        MatSlideToggleModule,
        CollectorConfigComponent,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
    ],
    host: { class: 'flex flex-col gap-3' },
    template: `
        @if (state(); as s) {
            @if (snapshot()) {
                <!-- a Postgres snapshot is manual only (operator 2026-11-05): no 30 s refresh, no inbox to show -->
                <p class="text-secondary m-0 text-xs" data-testid="snapshot-manual">
                    The table is copied again only when you press Refresh now; nothing refreshes it on its own.
                </p>
            } @else {
                <section
                    class="flex flex-col gap-1"
                    [attr.aria-labelledby]="ids + '-inbox'"
                    data-testid="dataset-inbox"
                >
                    <h4 class="m-0 text-sm font-semibold" [id]="ids + '-inbox'">Inbox</h4>
                    <p class="text-secondary m-0 text-xs">
                        @if (s.automatic && s.feed.kind !== 'uploads') {
                            Files another system writes into this folder on the server are imported on their own.
                        } @else if (s.watch) {
                            Files another system writes into this folder on the server are imported on their own once
                            they have stopped changing; the folder is checked every 30 seconds.
                        } @else {
                            Files another system writes into this folder on the server wait there until you import them.
                        }
                    </p>
                    <code class="break-all text-xs" data-testid="inbox-path">{{ s.inbox }}</code>
                    <div class="flex flex-wrap items-center gap-2">
                        <span class="text-sm" data-testid="waiting">{{ waiting(s.waiting, s.alreadyImported) }}</span>
                        @if (s.waiting) {
                            <button
                                mat-stroked-button
                                type="button"
                                data-testid="import-waiting"
                                [disabled]="busy() || !!locked()"
                                (click)="importWaiting.emit()"
                            >
                                <mat-icon svgIcon="heroicons_outline:inbox-arrow-down"></mat-icon>
                                Import waiting files
                            </button>
                        }
                    </div>
                    @if (s.watchable) {
                        <mat-slide-toggle
                            class="self-start"
                            data-testid="watch-folder"
                            [checked]="!!s.watch"
                            [disabled]="saving() || !!locked()"
                            (change)="setWatch($event)"
                            >Watch this folder</mat-slide-toggle
                        >
                        @if (s.watch && s.watchError) {
                            <inspecto-alert variant="warning" data-testid="watch-error"
                                >The last import of this folder failed: {{ s.watchError }}</inspecto-alert
                            >
                        }
                    }
                    @if (s.files.length) {
                        <ul class="text-secondary m-0 flex list-none flex-col p-0 text-xs" data-testid="waiting-files">
                            @for (f of shownFiles(); track f.name) {
                                <li>{{ fileLine(f) }}</li>
                            }
                            @if (s.waiting > shownFiles().length) {
                                <li>and {{ s.waiting - shownFiles().length }} more</li>
                            }
                        </ul>
                    }
                </section>

                @if (offerKeep()) {
                    <section class="flex flex-col gap-2" [attr.aria-labelledby]="ids + '-keep'" data-testid="keep-form">
                        <h4 class="m-0 text-sm font-semibold" [id]="ids + '-keep'">Keep this up to date</h4>
                        @if (s.feed.kind !== 'uploads') {
                            <p class="m-0 text-sm" data-testid="feed-on">
                                New files are imported on their own: {{ words(s) }}.
                            </p>
                            <button
                                mat-stroked-button
                                type="button"
                                class="self-start"
                                data-testid="stop-feed"
                                [disabled]="saving() || !!locked()"
                                (click)="stop()"
                            >
                                Stop: import only when asked
                            </button>
                        } @else {
                            <!-- the shared Collector surface, limited to what a feed saves (U4-Keep) -->
                            <inspecto-collector-config
                                data-testid="feed-collector"
                                [specs]="keepSpecs"
                                [modes]="keepModes"
                                [connectors]="keepConnectors"
                                [readOnly]="saving() || !!locked()"
                                (submitted)="keep()"
                            />
                            <inspecto-option-picker
                                label="Check"
                                data-testid="feed-every"
                                [formControl]="every"
                                [options]="everyOptions"
                            ></inspecto-option-picker>
                            @if (formError()) {
                                <p class="text-warn m-0 text-xs" role="alert">{{ formError() }}</p>
                            }
                            <button
                                mat-stroked-button
                                type="button"
                                class="self-start"
                                data-testid="keep-up-to-date"
                                [disabled]="saving() || !!locked()"
                                (click)="keep()"
                            >
                                <mat-icon svgIcon="heroicons_outline:arrow-path"></mat-icon>
                                Keep this up to date
                            </button>
                        }
                    </section>
                }
            }
        }
        @if (note(); as n) {
            <inspecto-alert [variant]="n.variant" data-testid="feed-note">{{ n.text }}</inspecto-alert>
        }
    `,
})
export class DatasetFeedComponent {
    private static nextId = 0;
    private readonly onboard = inject(OnboardService);
    private readonly access = inject(AddDataAccess);
    private readonly destroyRef = inject(DestroyRef);

    readonly datasetId = input.required<string>();
    /** *Keep this up to date* is on offer (the host shows the edition lock otherwise). */
    readonly offerKeep = input(false);
    /** A Postgres table snapshot (P3): manual only, so neither an inbox nor a refresh toggle is shown. */
    readonly snapshot = input(false);
    /** The card is running an import. */
    readonly busy = input(false);
    /** Why the person may not change data here, or null. */
    readonly locked = input<string | null>(null);
    /** Import waiting files = the card's Run now. */
    readonly importWaiting = output<void>();
    /** The feed as last read. */
    readonly feedChange = output<DatasetFeedState | null>();

    protected readonly ids = `inspecto-feed-${DatasetFeedComponent.nextId++}`;
    private readonly collector = viewChild(CollectorConfigComponent);
    protected readonly state = signal<DatasetFeedState | null>(null);
    protected readonly saving = signal(false);
    protected readonly formError = signal('');
    protected readonly note = signal<{ variant: 'info' | 'warning' | 'error' | 'success'; text: string } | null>(null);
    protected readonly keepSpecs = KEEP_SPECS;
    protected readonly keepModes = KEEP_MODES;
    protected readonly keepConnectors = KEEP_CONNECTORS;
    protected readonly everyOptions = EVERY_OPTIONS;
    protected readonly every = new FormControl<string>('15m', { nonNullable: true });
    protected readonly shownFiles = computed(() => (this.state()?.files ?? []).slice(0, 5));
    protected readonly waiting = waitingWords;
    protected readonly fileLine = waitingFileLine;

    constructor() {
        effect(() => {
            const id = this.datasetId();
            untracked(() => this.load(id));
        });
    }

    private read?: Subscription;

    /** Re-read the inbox and the feed (the card calls it after an import ran). */
    load(id = this.datasetId()): void {
        this.read?.unsubscribe();
        this.read = this.onboard
            .feed(id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => this.adopt(s),
                error: () => this.adopt(null), // the card stays usable; only the inbox is not shown
            });
    }

    private adopt(s: DatasetFeedState | null): void {
        this.state.set(s);
        this.feedChange.emit(s);
    }

    protected words(s: DatasetFeedState): string {
        const every = everyWords(s.feed.every);
        return every ? `${feedWords(s.feed)}, checked ${every}` : feedWords(s.feed);
    }

    protected keep(): void {
        this.formError.set('');
        this.note.set(null);
        const collector = this.collector();
        if (!collector || !collector.validate()) return;
        // the local inbox, or the picked SFTP Connection; null = refused, and the Collector surface says why
        const connector = collector.resolveConnector();
        if (!connector) return;
        const sftp = connector !== 'local';
        const v = collector.value();
        const marker = String(v['stability__ready_marker'] ?? '').trim();
        this.saving.set(true);
        this.onboard
            .keepUpToDate(this.datasetId(), {
                kind: sftp ? 'sftp' : 'folder',
                every: this.every.value,
                ...(sftp ? { connection: String(v['connection'] ?? '').trim() } : {}),
                ...(marker ? { readyMarker: marker } : {}),
            })
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => {
                    this.saving.set(false);
                    this.adopt(s);
                    this.saveIndexJob(s);
                },
                error: (err: unknown) => {
                    this.saving.set(false);
                    this.formError.set(apiErrorMessage(err, 'This Dataset could not be kept up to date.'));
                },
            });
    }

    /** The graph follows the feed only with the index Job: saved here when the person may author, else said. */
    private saveIndexJob(s: DatasetFeedState): void {
        const job = s.indexJob;
        if (!job) {
            this.note.set({ variant: 'success', text: 'New files are now imported on their own.' });
            return;
        }
        if (!this.access.canAuthor()) {
            this.note.set({
                variant: 'warning',
                text: `New files are imported on their own, but the graph is not updated with them: ask an administrator to add the Job ${job.name}.`,
            });
            return;
        }
        this.onboard
            .saveIndexJob(job)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: () =>
                    this.note.set({
                        variant: 'success',
                        text: `New files are now imported on their own, and the Job ${job.name} adds them to the graph.`,
                    }),
                error: (err: unknown) =>
                    // Keep after Stop: the Job saved the first time is still there (409), and still does its work
                    this.note.set(
                        err instanceof HttpErrorResponse && err.status === 409
                            ? {
                                  variant: 'success',
                                  text: `New files are now imported on their own, and the Job ${job.name} (already saved) adds them to the graph.`,
                              }
                            : {
                                  variant: 'warning',
                                  text: `New files are imported on their own, but the graph Job ${job.name} was not saved: ${apiErrorMessage(err, 'the Jobs route refused it')}.`,
                              },
                    ),
            });
    }

    /** *Watch this folder* on or off; a refusal puts the toggle back and says why. */
    protected setWatch(change: MatSlideToggleChange): void {
        const on = change.checked;
        this.note.set(null);
        this.saving.set(true);
        this.onboard
            .watch(this.datasetId(), on)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => {
                    this.saving.set(false);
                    this.adopt(s);
                },
                error: (err: unknown) => {
                    this.saving.set(false);
                    change.source.checked = !on; // the toggle shows the stored value again
                    this.note.set({
                        variant: 'error',
                        text: apiErrorMessage(
                            err,
                            on ? 'This folder could not be watched.' : 'The folder could not stop being watched.',
                        ),
                    });
                },
            });
    }

    protected stop(): void {
        this.note.set(null);
        this.saving.set(true);
        this.onboard
            .stopFeed(this.datasetId())
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => {
                    this.saving.set(false);
                    this.adopt(s);
                    this.note.set({
                        variant: 'info',
                        text: 'The feed is stopped. Add a file or press Run now to import.',
                    });
                },
                error: (err: unknown) => {
                    this.saving.set(false);
                    this.note.set({ variant: 'error', text: apiErrorMessage(err, 'The feed could not be stopped.') });
                },
            });
    }
}
