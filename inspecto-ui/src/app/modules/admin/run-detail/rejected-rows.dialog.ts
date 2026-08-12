import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { apiErrorMessage, AuditRow, RunsService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';

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
 */
@Component({
    selector: 'app-rejected-rows-dialog',
    standalone: true,
    imports: [
        MatDialogModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule,
        DataTableComponent, InspectoAlertComponent, InspectoEmptyStateComponent,
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
                <p class="text-secondary mb-2 text-sm">
                    {{ rowCount() }} rejected row(s) from <span class="font-mono">{{ errorsFile() }}</span>.
                    These rows were <strong>not</strong> ingested; the line number is the position in the
                    source file.
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
        <mat-dialog-actions align="end">
            <button mat-flat-button color="primary" mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class RejectedRowsDialog {
    private readonly api = inject(RunsService);
    readonly data = inject<RejectedRowsData>(MAT_DIALOG_DATA);

    readonly loading = signal(true);
    readonly rows = signal<AuditRow[]>([]);
    readonly rowCount = signal(0);
    readonly truncated = signal(false);
    readonly errorsFile = signal('');
    readonly error = signal<string | null>(null);
    /** 404 — no detail recorded. Not a failure: an empty state, never an error banner. */
    readonly notRecorded = signal(false);

    constructor() {
        this.api.rejectedRows(this.data.pipeline, this.data.file).subscribe({
            next: (r) => {
                this.loading.set(false);
                this.rows.set(r.rows ?? []);
                this.rowCount.set(r.rowCount ?? 0);
                this.truncated.set(!!r.truncated);
                this.errorsFile.set(r.errorsFile ?? '');
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
}
