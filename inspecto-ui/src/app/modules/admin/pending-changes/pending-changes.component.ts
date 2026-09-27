import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    LensService,
    PendingChange,
    PendingChangeDetail,
    PendingChangeDiff,
    PendingChangesService,
    PendingChangeStatus,
    SessionService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoLineDiffComponent } from 'app/inspecto/components/line-diff.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent, statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';

/** What the inbox lists. */
export type PendingChangesFilter = 'pending' | 'all';

/**
 * The **Pending Changes** inbox (`ASSURE-MAKER-CHECKER-1` S4): config changes a Space's Approval Policy held
 * for approval. Select one to read who proposed it, why, and a line diff of what it changes (the Pipeline
 * history's diff, `<inspecto-line-diff>`); an approver approves it — the server applies it through the route
 * it was proposed through — or declines it, with an optional reason.
 *
 * The decide buttons show only with {@link LensService.canApproveChanges}; the server is the boundary and adds
 * the kind's own capability and four-eyes (an author approving their own change is refused, and the message is
 * shown). Its author may **withdraw** their own waiting change ({@link canWithdraw} — identity is the session
 * Subject, {@link SessionService.actor}; the server alone decides, and refuses anyone else). ⚠ Not the agent Approvals Inbox (`/approvals`): that one decides what the assistant may do.
 */
@Component({
    selector: 'app-pending-changes',
    standalone: true,
    imports: [
        DatePipe,
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        DataTableComponent,
        InspectoAlertComponent,
        InspectoLineDiffComponent,
        InspectoPageHeaderComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './pending-changes.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PendingChangesComponent implements OnInit {
    private readonly api = inject(PendingChangesService);
    private readonly toastr = inject(ToastrService);
    private readonly confirm = inject(InspectoConfirmService);
    protected readonly lens = inject(LensService);
    private readonly session = inject(SessionService);

    readonly filter = signal<PendingChangesFilter>('pending');
    readonly changes = signal<PendingChange[]>([]);
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    readonly selected = signal<PendingChangeDetail | null>(null);
    readonly diff = signal<PendingChangeDiff | null>(null);
    readonly diffError = signal<string | null>(null);
    readonly deciding = signal(false);
    readonly decideError = signal<string | null>(null);
    readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] });

    /** Approve / Decline show only for an approver, and only on a change still waiting. */
    readonly canDecide = computed(() => this.lens.canApproveChanges() && this.selected()?.status === 'pending');

    /** Withdraw shows only to the change's author (the signed-in Subject), and only while it waits. */
    readonly canWithdraw = computed(() => {
        const c = this.selected();
        const me = this.session.actor();
        return !!me && c?.status === 'pending' && c.author === me;
    });

    readonly diffSummary = computed(() => {
        const d = this.diff();
        if (!d) return '';
        if (d.added === 0 && d.removed === 0) return 'No differences.';
        return `${d.added} line(s) added, ${d.removed} removed.`;
    });

    // DataTable's default flex overrides a bare width, so widths here are floors (minWidth); fixed badges also cap (maxWidth).
    readonly columnDefs: ColDef<PendingChange>[] = [
        {
            field: 'createdAt',
            headerName: 'Proposed',
            minWidth: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        { field: 'kind', headerName: 'Kind', minWidth: 140 },
        { field: 'name', headerName: 'Name', flex: 1 },
        { field: 'operation', headerName: 'Change', minWidth: 110 },
        { field: 'author', headerName: 'Author', minWidth: 150 },
        {
            field: 'status',
            headerName: 'Status',
            minWidth: 120,
            maxWidth: 120,
            cellRenderer: (p: ICellRendererParams<PendingChange>) => statusBadgeHtml(p.value as string),
        },
    ];

    ngOnInit(): void {
        this.load();
    }

    setFilter(f: PendingChangesFilter): void {
        this.filter.set(f);
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.error.set(null);
        const status: PendingChangeStatus | undefined = this.filter() === 'pending' ? 'pending' : undefined;
        this.api.list(status).subscribe({
            next: (r) => {
                this.changes.set(r.items);
                this.loading.set(false);
            },
            error: (err) => {
                this.changes.set([]);
                this.loading.set(false);
                this.error.set(apiErrorMessage(err, 'The Pending Changes could not be read'));
            },
        });
    }

    open(row: Record<string, unknown>): void {
        const id = String(row['id']);
        this.decideError.set(null);
        this.reason.reset('');
        this.diff.set(null);
        this.diffError.set(null);
        this.api.get(id).subscribe({
            next: (d) => this.selected.set(d),
            error: (err) => this.toastr.error(apiErrorMessage(err, 'The Pending Change could not be read')),
        });
        this.api.diff(id).subscribe({
            next: (d) => this.diff.set(d),
            error: (err) => this.diffError.set(apiErrorMessage(err, 'The diff could not be computed')),
        });
    }

    close(): void {
        this.selected.set(null);
        this.diff.set(null);
    }

    async approve(): Promise<void> {
        const c = this.selected();
        if (
            !c ||
            !(await this.confirm.confirm(
                `Apply this ${c.operation} of ${c.kind} '${c.name}', proposed by ${c.author}? It is written through the same route it was proposed through, with every check run again.`,
                'Approve the change?',
            ))
        )
            return;
        this.decide('approve');
    }

    async decline(): Promise<void> {
        const c = this.selected();
        if (!c) return;
        const ok = await this.confirm.confirmDestructive(
            `Decline this ${c.operation} of ${c.kind} '${c.name}'? Nothing is written; ${c.author} can propose it again.`,
            { title: 'Decline the change?', confirmText: 'Decline' },
        );
        if (ok) this.decide('decline');
    }

    async withdraw(): Promise<void> {
        const c = this.selected();
        if (!c) return;
        if (this.reason.invalid) {
            this.reason.markAsTouched();
            return;
        }
        const ok = await this.confirm.confirmDestructive(
            `Withdraw your ${c.operation} of ${c.kind} '${c.name}'? Nothing is written, and no one can approve it afterwards; you can propose it again.`,
            { title: 'Withdraw the change?', confirmText: 'Withdraw' },
        );
        if (!ok) return;
        this.deciding.set(true);
        this.api.withdraw(c.id, this.reason.value.trim() || undefined).subscribe({
            next: (r) => {
                this.deciding.set(false);
                this.toastr.success('Change withdrawn');
                this.selected.set({ ...c, ...r.pendingChange });
                this.load();
            },
            error: (err) => {
                this.deciding.set(false);
                this.toastr.error(apiErrorMessage(err, 'The change could not be withdrawn'));
                this.load();
            },
        });
    }

    private decide(verdict: 'approve' | 'decline'): void {
        const c = this.selected();
        if (!c) return;
        if (this.reason.invalid) {
            this.reason.markAsTouched();
            return;
        }
        const reason = this.reason.value.trim() || undefined;
        this.deciding.set(true);
        this.decideError.set(null);
        const call = verdict === 'approve' ? this.api.approve(c.id, reason) : this.api.decline(c.id, reason);
        call.subscribe({
            next: (r) => {
                this.deciding.set(false);
                this.toastr.success(verdict === 'approve' ? 'Change approved and applied' : 'Change declined');
                this.selected.set({ ...c, ...r.pendingChange });
                this.load();
            },
            error: (err) => {
                this.deciding.set(false);
                // The server's own words: four-eyes, a stale base, the route's refusal — the reviewer acts on it.
                this.decideError.set(apiErrorMessage(err, `The change could not be ${verdict}d`));
                this.load();
            },
        });
    }
}
