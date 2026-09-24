import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    CommitRetriesPage,
    commitRetryErrorMessage,
    CommitRetryRow,
    LensService,
    RunsService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';

const NEEDS_OPERATE = 'needs the Operate runs capability (canOperateRuns)';

/**
 * The Run Detail **Commit retries** tab (X1, `GET /runs/{name}/retries`): the files of ONE pipeline waiting
 * on a bounded COMMIT retry, with **Retry now** (clears the backoff, KEEPS the attempt count) and **Cancel**
 * (quarantines the file now under `retry_cancelled`) per row.
 *
 * <p>⚠ "keeps no retry state" (no `dirs.status_dir` — failures are retried every cycle without bound) renders
 * as its own warning, never as the "no retries pending" empty state: the two look identical as an empty list
 * and mean opposite things. Without `canOperateRuns` the row actions stay visible but disabled, with the
 * reason — the rejected-rows dialog's replay convention.
 */
@Component({
    selector: 'app-commit-retries-panel',
    standalone: true,
    imports: [
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
        <div class="mt-4 flex items-center gap-3">
            @if (page()?.keepsRetryState) {
                <span class="text-secondary text-sm" data-testid="retry-policy">{{ policyText() }}</span>
            }
            <button
                mat-icon-button
                class="ml-auto"
                (click)="load()"
                matTooltip="Reload the retry queue"
                aria-label="Reload the retry queue"
            >
                <mat-icon svgIcon="heroicons_outline:arrow-path"></mat-icon>
            </button>
        </div>
        @if (loading()) {
            <div class="mt-4 flex items-center gap-3">
                <mat-progress-spinner diameter="24" mode="indeterminate"></mat-progress-spinner>
                <span class="text-secondary">Loading…</span>
            </div>
        } @else if (loadError()) {
            <inspecto-alert class="mt-4 block" variant="error" title="Could not load the retry queue">
                {{ loadError() }}
            </inspecto-alert>
        } @else if (page(); as p) {
            @if (!p.keepsRetryState) {
                <inspecto-alert
                    class="mt-4 block"
                    variant="warning"
                    title="This pipeline keeps no retry state"
                    data-testid="no-retry-state"
                >
                    It has no <code>dirs.status_dir</code>, so nothing is recorded: a failed Consignment is retried
                    every cycle without bound, and there is nothing to list, retry now or cancel.
                    @if (p.note) {
                        <span class="text-secondary">({{ p.note }})</span>
                    }
                </inspecto-alert>
            } @else {
                @if (p.error) {
                    <inspecto-alert class="mt-4 block" variant="warning" title="The retry queue was read only in part">
                        {{ p.error }}
                    </inspecto-alert>
                }
                @if (note(); as n) {
                    <inspecto-alert
                        class="mt-4 block"
                        variant="success"
                        title="Retry scheduled"
                        data-testid="retry-note"
                    >
                        {{ n }}
                    </inspecto-alert>
                }
                @if (p.retries.length === 0) {
                    <div class="mt-4" data-testid="no-retries">
                        <inspecto-empty-state
                            icon="heroicons_outline:check-circle"
                            title="No retries pending"
                            message="No file of this pipeline is waiting on a commit retry. A failed Consignment's files appear here with their attempts and next retry time."
                        ></inspecto-empty-state>
                    </div>
                } @else {
                    @if (p.truncated) {
                        <inspecto-alert
                            class="mt-4 block"
                            variant="info"
                            title="Showing part of the queue"
                            data-testid="retries-truncated"
                        >
                            Only the first {{ p.retries.length }} of {{ p.total }} waiting files are listed.
                        </inspecto-alert>
                    }
                    @if (unreadableCount() > 0) {
                        <inspecto-alert
                            class="mt-4 block"
                            variant="warning"
                            title="Unreadable retry records"
                            data-testid="retries-unreadable"
                        >
                            {{ unreadableCount() }} retry record(s) could not be read, so their attempts and next retry
                            time are unknown. They are marked <em>unreadable</em> below.
                        </inspecto-alert>
                    }
                    @if (!lens.canOperateRuns()) {
                        <p class="text-secondary mt-4 text-xs" data-testid="retry-reason">
                            Retry now and Cancel {{ needsOperate }}.
                        </p>
                    }
                    <inspecto-data-table
                        class="mt-4 block"
                        tier="standard"
                        sourceName="commit-retries"
                        exportName="commit-retries"
                        stateKey="run-detail-commit-retries"
                        [rows]="p.retries"
                        [columns]="columns"
                        [rowActions]="rowActions"
                        [pinActions]="true"
                        noRowsTitle="No retries pending"
                    />
                }
            }
        }
    `,
})
export class CommitRetriesPanelComponent {
    private readonly api = inject(RunsService);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly toastr = inject(ToastrService);
    protected readonly lens = inject(LensService);

    readonly pipeline = input.required<string>();

    readonly loading = signal(false);
    readonly loadError = signal<string | null>(null);
    readonly page = signal<CommitRetriesPage | null>(null);
    /** The server's `note` after a retry-now (attempts kept, how many remain). */
    readonly note = signal<string | null>(null);
    readonly unreadableCount = signal(0);
    readonly policyText = signal('');
    private readonly acting = signal(false);
    protected readonly needsOperate = NEEDS_OPERATE;

    private readonly reloadOnPipeline = effect(() => {
        this.pipeline();
        untracked(() => {
            this.note.set(null);
            this.load();
        });
    });

    readonly columns: ColDef<CommitRetryRow>[] = [
        { field: 'file', headerName: 'File', flex: 2 },
        {
            headerName: 'Attempts',
            colId: 'attempts',
            width: 130,
            valueGetter: (p) => (p.data ? this.attemptsText(p.data) : ''),
        },
        {
            headerName: 'Next retry',
            colId: 'nextRetry',
            width: 190,
            cellRenderer: (p: ICellRendererParams<CommitRetryRow>) => {
                const r = p.data;
                if (!r || !r.readable) return '';
                return r.due ? statusBadgeHtml('INFO', 'due') : fmtDateTime(r.nextRetryAt);
            },
        },
        { field: 'lastError', headerName: 'Last error', flex: 3 },
        {
            headerName: 'Record',
            colId: 'readable',
            width: 130,
            cellRenderer: (p: ICellRendererParams<CommitRetryRow>) =>
                p.data && !p.data.readable ? statusBadgeHtml('WARNING', 'unreadable') : '',
        },
    ];

    readonly rowActions: InspectoRowAction<CommitRetryRow>[] = [
        {
            icon: 'heroicons_outline:arrow-path',
            hint: () => (this.lens.canOperateRuns() ? 'Retry now' : `Retry now — ${NEEDS_OPERATE}`),
            disabled: () => !this.lens.canOperateRuns() || this.acting(),
            onClick: (r) => this.retryNow(r),
        },
        {
            icon: 'heroicons_outline:no-symbol',
            hint: () => (this.lens.canOperateRuns() ? 'Cancel the retry (quarantine)' : `Cancel — ${NEEDS_OPERATE}`),
            disabled: () => !this.lens.canOperateRuns() || this.acting(),
            onClick: (r) => this.cancel(r),
        },
    ];

    load(): void {
        const name = this.pipeline();
        this.loading.set(true);
        this.loadError.set(null);
        this.api.retries(name).subscribe({
            next: (p) => {
                this.page.set(p);
                this.unreadableCount.set(p.retries.filter((r) => !r.readable).length);
                this.policyText.set(this.describePolicy(p));
                this.loading.set(false);
            },
            error: (e: unknown) => {
                this.page.set(null);
                this.loadError.set(apiErrorMessage(e, 'Could not load the retry queue'));
                this.loading.set(false);
            },
        });
    }

    retryNow(r: CommitRetryRow): void {
        if (!this.lens.canOperateRuns() || this.acting()) return;
        this.acting.set(true);
        this.note.set(null);
        this.api.retryNow(this.pipeline(), r.file).subscribe({
            next: (res) => {
                this.acting.set(false);
                this.note.set(res.note ?? `"${r.file}" will be retried on the next cycle.`);
                this.toastr.success(`"${r.file}" will be retried on the next cycle`);
                this.load();
            },
            error: (e: unknown) => {
                this.acting.set(false);
                this.toastr.error(commitRetryErrorMessage(e, r.file, 'retry-now'));
            },
        });
    }

    async cancel(r: CommitRetryRow): Promise<void> {
        if (!this.lens.canOperateRuns() || this.acting()) return;
        const ok = await this.confirm.confirmDestructive(
            `Cancel the commit retry of "${r.file}"? The file is quarantined now under retry_cancelled and is ` +
                `not retried again — its fate is decided, exactly as when the attempts run out.`,
            { title: 'Cancel retry', confirmText: 'Quarantine file', cancelText: 'Keep retrying' },
        );
        if (!ok) return;
        this.acting.set(true);
        this.note.set(null);
        this.api.cancelRetry(this.pipeline(), r.file).subscribe({
            next: (res) => {
                this.acting.set(false);
                this.toastr.success(`"${r.file}" quarantined under ${res.quarantineReason ?? 'retry_cancelled'}`);
                this.load();
            },
            error: (e: unknown) => {
                this.acting.set(false);
                this.toastr.error(commitRetryErrorMessage(e, r.file, 'cancel'));
            },
        });
    }

    private attemptsText(r: CommitRetryRow): string {
        if (!r.readable) return '—';
        const p = this.page()?.policy;
        return p?.bounded ? `${r.attempts} / ${p.maxAttempts}` : `${r.attempts} / unbounded`;
    }

    private describePolicy(p: CommitRetriesPage): string {
        const secs = (ms: number) => `${Math.round(ms / 1000)}s`;
        const backoff = `backoff ${secs(p.policy.initialBackoffMs)}–${secs(p.policy.maxBackoffMs)}`;
        return p.policy.bounded
            ? `Up to ${p.policy.maxAttempts} attempts, ${backoff}; then quarantined under retry_exhausted.`
            : `Retried without an attempt cap, ${backoff}.`;
    }
}
