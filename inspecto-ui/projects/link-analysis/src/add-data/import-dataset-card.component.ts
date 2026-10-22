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
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatRadioModule } from '@angular/material/radio';
import { RouterLink } from '@angular/router';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { LockedActionComponent } from '@inspecto/core/components/locked-action.component';
import { AddDataAccess } from './add-data-access';
import { ImportDataset, ImportSummary, fileProblem } from './add-data.model';
import { ImportSummaryComponent } from './import-summary.component';
import { ImportRunFailed, OnboardService } from './onboard.service';

/**
 * The **Dataset card** of an imported Dataset (LA-ONBOARD-1 phase 4, UC-06, UC-14; console B4's manual-run gesture):
 * "one-time import", the last import in plain words, and **Add a file** beside **Run now** (DT-14: Basic is manual, so the
 * two sit together). *Keep this up to date* is locked with its reason on Basic; on Standard and above it hands off to the
 * console's guided editor when the host has one (`editorRoute`) and the person may author, else it says to ask an
 * administrator. Inspecto LA never edits a Pipeline itself (A-6).
 *
 * @example <inspecto-import-dataset-card [dataset]="d" [summary]="last" (imported)="last = $event" />
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
        InspectoAlertComponent,
        ChipComponent,
        LockedActionComponent,
        ImportSummaryComponent,
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
                <inspecto-chip variant="soft">one-time import</inspecto-chip>
                <span class="text-secondary text-sm">{{ kindWords() }}</span>
            </header>
            @if (dataset().description) {
                <p class="text-secondary m-0 text-sm">{{ dataset().description }}</p>
            }
            @if (last(); as s) {
                <div class="flex flex-col gap-1">
                    <h4 class="m-0 text-sm font-semibold">Last import</h4>
                    <inspecto-import-summary [summary]="s"></inspecto-import-summary>
                </div>
            }

            @if (dataset().kind === 'stream') {
                <mat-radio-group
                    class="flex flex-wrap gap-x-4 text-sm"
                    [formControl]="mode"
                    aria-label="What a new file does"
                    data-testid="add-file-mode"
                >
                    <mat-radio-button value="replace">A new file replaces the data</mat-radio-button>
                    <mat-radio-button value="append">A new file adds its rows</mat-radio-button>
                </mat-radio-group>
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
                <button
                    mat-stroked-button
                    type="button"
                    data-testid="run-now"
                    [disabled]="busy() || !!locked()"
                    (click)="runNow()"
                >
                    <mat-icon svgIcon="heroicons_outline:play"></mat-icon>
                    Run now
                </button>
            </div>
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

            <div class="flex flex-col gap-1 border-t pt-3" data-testid="keep-up-to-date">
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
                    <p class="text-secondary m-0 text-xs">
                        Keep this up to date: watch a folder or a schedule in the guided editor.
                    </p>
                } @else {
                    <p class="text-secondary m-0 text-sm" data-testid="ask-administrator">
                        Ask an administrator to keep this up to date.
                    </p>
                }
            </div>
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

    protected readonly titleId = `inspecto-import-card-${ImportDatasetCardComponent.nextId++}`;
    protected readonly mode = new FormControl<'replace' | 'append'>('replace', { nonNullable: true });
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

    constructor() {
        effect(() => {
            const s = this.summary();
            untracked(() => this.last.set(s));
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
        this.start(this.onboard.addFile(this.dataset().id, file, mode, () => this.following.set(true)));
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
