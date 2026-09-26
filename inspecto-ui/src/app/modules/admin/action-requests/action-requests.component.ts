import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    ActionRequest,
    ActionRequestDetail,
    ActionRequestsService,
    ActionRequestStatus,
    apiErrorMessage,
    LensService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { StatusBadgeComponent, statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';

/** What the inbox lists. */
export type ActionRequestsFilter = 'pending' | 'all';

/**
 * The **Action Requests** inbox (`ASSURE-ACTION-REQUESTS-1`) — the Pending Changes inbox pattern for outbound
 * calls: select one to read the Connection, method, idempotency key and the exact rendered payload that will be
 * sent; an approver approves it (it is then sent, with retries) or declines it, and retries a failed one.
 *
 * The buttons show only with {@link LensService.canApproveChanges}; the server is the boundary and adds four-eyes
 * (an author deciding their own request is refused, and the message is shown) and the record's integrity check.
 */
@Component({
    selector: 'app-action-requests',
    standalone: true,
    imports: [
        DatePipe,
        JsonPipe,
        ReactiveFormsModule,
        RouterLink,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        DataTableComponent,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './action-requests.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActionRequestsComponent implements OnInit {
    private readonly api = inject(ActionRequestsService);
    private readonly toastr = inject(ToastrService);
    private readonly confirm = inject(InspectoConfirmService);
    protected readonly lens = inject(LensService);

    readonly filter = signal<ActionRequestsFilter>('pending');
    readonly requests = signal<ActionRequest[]>([]);
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    readonly selected = signal<ActionRequestDetail | null>(null);
    readonly deciding = signal(false);
    readonly decideError = signal<string | null>(null);
    readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] });

    readonly canDecide = computed(() => this.lens.canApproveChanges() && this.selected()?.status === 'pending');
    readonly canRetry = computed(() => this.lens.canApproveChanges() && this.selected()?.status === 'failed');
    /** A request left in `dispatched` (the server stopped mid-send) can be marked failed; the server enforces the idle time. */
    readonly canMarkFailed = computed(() => this.lens.canApproveChanges() && this.selected()?.status === 'dispatched');

    readonly columnDefs: ColDef<ActionRequest>[] = [
        {
            field: 'createdAt',
            headerName: 'Requested',
            width: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        { field: 'method', headerName: 'Method', width: 100 },
        { field: 'connection', headerName: 'Connection', flex: 1 },
        {
            headerName: 'From',
            width: 200,
            valueGetter: (p) => p.data?.incidentId ?? p.data?.caseId ?? '',
        },
        { field: 'author', headerName: 'Author', width: 150 },
        { field: 'attempts', headerName: 'Attempts', width: 110 },
        {
            field: 'status',
            headerName: 'Status',
            width: 130,
            cellRenderer: (p: ICellRendererParams<ActionRequest>) => statusBadgeHtml(p.value as string),
        },
    ];

    ngOnInit(): void {
        this.load();
    }

    setFilter(f: ActionRequestsFilter): void {
        this.filter.set(f);
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.error.set(null);
        const status: ActionRequestStatus | undefined = this.filter() === 'pending' ? 'pending' : undefined;
        this.api.list({ status }).subscribe({
            next: (r) => {
                this.requests.set(r.items);
                this.loading.set(false);
            },
            error: (err) => {
                this.requests.set([]);
                this.loading.set(false);
                this.error.set(apiErrorMessage(err, 'The Action Requests could not be read'));
            },
        });
    }

    open(row: Record<string, unknown>): void {
        this.decideError.set(null);
        this.reason.reset('');
        this.api.get(String(row['id'])).subscribe({
            next: (d) => this.selected.set(d),
            error: (err) => this.toastr.error(apiErrorMessage(err, 'The Action Request could not be read')),
        });
    }

    close(): void {
        this.selected.set(null);
    }

    /** The linked object's detail route. */
    link(r: ActionRequest): string[] {
        return r.caseId ? ['/cases', r.caseId] : ['/incidents', r.incidentId ?? ''];
    }

    async approve(): Promise<void> {
        const r = this.selected();
        if (
            !r ||
            !(await this.confirm.confirm(
                `Send ${r.method} to Connection '${r.connection}' (${r.targetUrl}) with the payload shown, requested by ${r.author}? It is sent now, retried on failure under the same idempotency key.`,
                'Approve the Action Request?',
            ))
        )
            return;
        this.decide('approve');
    }

    async decline(): Promise<void> {
        const r = this.selected();
        if (!r) return;
        const ok = await this.confirm.confirmDestructive(
            `Decline this ${r.method} to Connection '${r.connection}'? Nothing is sent.`,
            { title: 'Decline the Action Request?', confirmText: 'Decline' },
        );
        if (ok) this.decide('decline');
    }

    retry(): void {
        this.decide('retry');
    }

    async markFailed(): Promise<void> {
        const ok = await this.confirm.confirm(
            'Mark this request failed? Its delivery was never confirmed. Nothing is sent now; a retry re-sends it under the same idempotency key.',
            'Mark as failed?',
        );
        if (ok) this.decide('mark-failed');
    }

    private decide(verdict: 'approve' | 'decline' | 'retry' | 'mark-failed'): void {
        const r = this.selected();
        if (!r) return;
        if (this.reason.invalid) {
            this.reason.markAsTouched();
            return;
        }
        const reason = this.reason.value.trim() || undefined;
        this.deciding.set(true);
        this.decideError.set(null);
        const call =
            verdict === 'approve'
                ? this.api.approve(r.id, reason)
                : verdict === 'decline'
                  ? this.api.decline(r.id, reason)
                  : verdict === 'retry'
                    ? this.api.retry(r.id)
                    : this.api.markFailed(r.id);
        call.subscribe({
            next: (d) => {
                this.deciding.set(false);
                this.toastr.success(verdict === 'decline' ? 'Action Request declined' : `Action Request ${d.status}`);
                this.selected.set(d);
                this.load();
            },
            error: (err) => {
                this.deciding.set(false);
                // The server's own words: four-eyes, an integrity failure, a status that moved on.
                this.decideError.set(apiErrorMessage(err, 'The Action Request could not be updated'));
                this.load();
            },
        });
    }
}
