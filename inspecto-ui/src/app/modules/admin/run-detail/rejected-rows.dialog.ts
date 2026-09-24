import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    AuditRow,
    LensService,
    ReplayRejectsResult,
    replayRejectsErrorMessage,
    RunsService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import { BatchDetailDialog } from './batch-detail.dialog';

/** Dialog data: which pipeline, and the input file's bare NAME (the route's key). */
export interface RejectedRowsData {
    pipeline: string;
    file: string;
}

/**
 * The rejected ROWS behind a file's `error_rows` count (audit hole 2, `GET /runs/{n}/errors?file=`).
 *
 * <p>The audit ledgers carry counts, filenames and an error string — never row content. The content
 * existed all along in the companion `_errors.csv`, but only on disk, so an operator without
 * filesystem access could see THAT rows failed and never WHICH. A 404 is the expected, non-error
 * answer for "no detail was recorded" and gets an empty state rather than a red toast.
 *
 * <p>Since 2026-09-25 (X4) it also offers **Replay rejected records** — `POST /runs/{n}/replay-rejects`,
 * which re-ingests only these lines as a new Consignment, once per reject file. Without `canOperateRuns`
 * the button stays visible but disabled, with the reason beside it.
 */
@Component({
    selector: 'app-rejected-rows-dialog',
    standalone: true,
    imports: [
        MatDialogModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        DataTableComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title class="flex min-w-0 items-center gap-2">
            <mat-icon class="shrink-0" svgIcon="heroicons_outline:exclamation-triangle"></mat-icon>
            <span class="min-w-0 truncate">Rejected rows · {{ data.file }}</span>
        </h2>
        <mat-dialog-content>
            @if (loading()) {
                <div class="flex items-center gap-2 py-6">
                    <mat-spinner diameter="20"></mat-spinner>
                    <span class="text-secondary text-sm">Reading the rejected-row detail…</span>
                </div>
            } @else if (notRecorded()) {
                <inspecto-empty-state
                    icon="heroicons_outline:document-magnifying-glass"
                    title="No rejected-row detail recorded"
                    message="This file has no companion errors file. Detail is written only when the
                             parser actually rejected rows — a count of 0 means nothing was lost here."
                ></inspecto-empty-state>
            } @else if (error()) {
                <div role="alert">
                    <inspecto-alert variant="error" title="Could not load the rejected rows">
                        {{ error() }}
                    </inspecto-alert>
                </div>
            } @else {
                @if (replayed(); as r) {
                    <div class="mb-2 block" data-testid="replay-result">
                        @if (r.status === 'SUCCESS') {
                            <inspecto-alert variant="success" title="Rejected records replayed">
                                {{ r.outputRows }} of {{ r.records }} record(s) landed as Consignment
                                <span class="font-mono">{{ r.batchId }}</span
                                >.
                                @if (r.errorRows > 0) {
                                    {{ r.errorRows }} still rejected — they are now in the reject file of
                                    <span class="font-mono">{{ r.replayFile }}</span
                                    >.
                                }
                            </inspecto-alert>
                        } @else {
                            <inspecto-alert variant="warning" title="No replayed record landed">
                                Consignment <span class="font-mono">{{ r.batchId }}</span> ended {{ r.status }}. The
                                records are still rejected and are now in the reject file of
                                <span class="font-mono">{{ r.replayFile }}</span
                                >, which can be replayed in turn.
                            </inspecto-alert>
                        }
                    </div>
                }
                <p class="text-secondary mb-2 text-sm">
                    {{ rowCount() }} rejected row(s) from <span class="font-mono">{{ errorsFile() }}</span
                    >. These rows were <strong>not</strong> ingested; the line number is the position in the source
                    file.
                </p>
                @if (truncated()) {
                    <inspecto-alert class="mb-2 block" variant="warning" title="Showing a sample">
                        Only the first {{ rows().length }} of {{ rowCount() }} rows are shown — this view is a
                        diagnostic sample, not an export. The full detail is in the errors file on disk.
                    </inspecto-alert>
                }
                <inspecto-data-table [rows]="rows()" [pageSize]="20"></inspecto-data-table>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end" class="gap-2">
            @if (replayable()) {
                @if (!lens.canOperateRuns()) {
                    <span class="text-secondary mr-auto text-xs" data-testid="replay-reason">
                        Replaying needs the Operate runs capability (canOperateRuns).
                    </span>
                }
                @if (replayed()?.batchId; as batchId) {
                    <button
                        mat-stroked-button
                        type="button"
                        data-testid="open-replay-batch"
                        (click)="openBatch(batchId)"
                    >
                        Open Consignment {{ batchId }}
                    </button>
                }
                <button
                    mat-stroked-button
                    type="button"
                    data-testid="replay-rejects"
                    [disabled]="!lens.canOperateRuns() || replaying() || replayed() !== null"
                    [matTooltip]="lens.canOperateRuns() ? '' : 'Needs the Operate runs capability (canOperateRuns)'"
                    (click)="replay()"
                >
                    Replay rejected records
                </button>
            }
            <button mat-flat-button color="primary" mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class RejectedRowsDialog {
    private readonly api = inject(RunsService);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly toastr = inject(ToastrService);
    private readonly dialog = inject(MatDialog);
    protected readonly lens = inject(LensService);
    readonly data = inject<RejectedRowsData>(MAT_DIALOG_DATA);

    readonly loading = signal(true);
    readonly rows = signal<AuditRow[]>([]);
    readonly rowCount = signal(0);
    readonly truncated = signal(false);
    readonly errorsFile = signal('');
    readonly error = signal<string | null>(null);
    /** 404 — no detail recorded. Not a failure: an empty state, never an error banner. */
    readonly notRecorded = signal(false);
    /** Only a loaded, non-empty reject file can be replayed. */
    readonly replayable = signal(false);
    readonly replaying = signal(false);
    /** The replay's outcome. Once set the button stays disabled — a reject file replays once. A FAILED
     *  replay released its claim server-side, so it is never stored and the button stays available. */
    readonly replayed = signal<ReplayRejectsResult | null>(null);

    constructor() {
        this.api.rejectedRows(this.data.pipeline, this.data.file).subscribe({
            next: (r) => {
                this.loading.set(false);
                this.rows.set(r.rows ?? []);
                this.rowCount.set(r.rowCount ?? 0);
                this.truncated.set(!!r.truncated);
                this.errorsFile.set(r.errorsFile ?? '');
                this.replayable.set((r.rowCount ?? 0) > 0);
            },
            error: (e: unknown) => {
                this.loading.set(false);
                if ((e as { status?: number })?.status === 404) {
                    this.notRecorded.set(true);
                    return;
                }
                this.error.set(apiErrorMessage(e, 'Could not load the rejected rows'));
            },
        });
    }

    async replay(): Promise<void> {
        if (!this.lens.canOperateRuns() || this.replaying()) return;
        const ok = await this.confirm.confirm(
            `Replay the ${this.rowCount()} rejected record(s) of "${this.data.file}"? Only these rejected lines ` +
                `are re-ingested, as a new Consignment — the file's good rows already landed and are not touched. ` +
                `This can be done ONCE per reject file; records rejected again go to the replay's own reject file. ` +
                `Note: reject files written before 2026-09-25 may have turned double quotes into apostrophes, ` +
                `so a quoted value in them can land split or altered.`,
            'Replay rejected records',
        );
        if (!ok) return;
        this.replaying.set(true);
        this.api.replayRejects(this.data.pipeline, this.data.file).subscribe({
            next: (r) => {
                this.replaying.set(false);
                if (r.status === 'FAILED') {
                    this.toastr.warning(
                        `The replay of "${this.data.file}" did not complete — nothing landed, and it can be tried ` +
                            `again once the cause is fixed.${r.error ? ' ' + r.error : ''}`,
                    );
                    return;
                }
                this.replayed.set(r);
                this.toastr.success(`Replayed ${r.records} record(s) of ${this.data.file} as ${r.batchId}`);
            },
            error: (e: unknown) => {
                this.replaying.set(false);
                this.toastr.error(replayRejectsErrorMessage(e, this.data.file));
            },
        });
    }

    openBatch(batchId: string): void {
        this.dialog.open(BatchDetailDialog, {
            data: { pipeline: this.data.pipeline, batchId },
            width: '880px',
            maxHeight: '85vh',
        });
    }
}
