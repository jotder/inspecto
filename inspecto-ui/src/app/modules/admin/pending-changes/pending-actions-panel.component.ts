import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    LensService,
    PendingAction,
    PendingActionsService,
    PendingActionStatus,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { StatusBadgeComponent, statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';

/**
 * The **Pending Actions** tab of the Pending Changes screen (AGENT-KIT-1 AK-2d): the operational actions an unattended
 * machine agent asked for — re-run, resume, reprocess, drain… — held for a person. A change approver approves one (it
 * then runs as that machine) or declines it; four eyes. The machine withdraws its own through the API, never here.
 */
@Component({
    selector: 'app-pending-actions-panel',
    standalone: true,
    imports: [
        DatePipe,
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatIconModule,
        PenFieldComponent,
        DataTableComponent,
        InspectoAlertComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './pending-actions-panel.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PendingActionsPanelComponent implements OnInit {
    private readonly api = inject(PendingActionsService);
    private readonly toastr = inject(ToastrService);
    private readonly confirm = inject(InspectoConfirmService);
    protected readonly lens = inject(LensService);

    readonly filter = signal<'pending' | 'all'>('pending');
    readonly actions = signal<PendingAction[]>([]);
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    readonly selected = signal<PendingAction | null>(null);
    readonly deciding = signal(false);
    readonly decideError = signal<string | null>(null);
    readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] });

    /** Approve / Decline show only for an approver, on an action still waiting, once its whole request is on screen. */
    readonly canDecide = computed(
        () => this.lens.canApproveChanges() && this.selected()?.status === 'pending' && !!this.selected()?.request,
    );

    readonly columnDefs: ColDef<PendingAction>[] = [
        {
            field: 'createdAt',
            headerName: 'Asked',
            minWidth: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        { field: 'action', headerName: 'Action', flex: 1 },
        { field: 'author', headerName: 'Asked by', minWidth: 160 },
        {
            field: 'status',
            headerName: 'Status',
            minWidth: 120,
            maxWidth: 120,
            cellRenderer: (p: ICellRendererParams<PendingAction>) => statusBadgeHtml(p.value as string),
        },
    ];

    ngOnInit(): void {
        this.load();
    }

    setFilter(f: 'pending' | 'all'): void {
        this.filter.set(f);
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.error.set(null);
        const status: PendingActionStatus | undefined = this.filter() === 'pending' ? 'pending' : undefined;
        this.api.list(status).subscribe({
            next: (r) => {
                this.actions.set(r.items);
                this.loading.set(false);
            },
            error: (err) => {
                this.actions.set([]);
                this.loading.set(false);
                this.error.set(apiErrorMessage(err, 'The Pending Actions could not be read'));
            },
        });
    }

    open(row: Record<string, unknown>): void {
        this.decideError.set(null);
        this.reason.reset('');
        const a = row as unknown as PendingAction;
        this.selected.set(a);
        // the list row carries no request: read the whole one, so the approver sees what an approval replays
        this.api.get(a.id).subscribe({
            next: (full) => {
                if (this.selected()?.id === full.id) this.selected.set(full);
            },
            error: (err) => this.decideError.set(apiErrorMessage(err, 'The request of this action could not be read')),
        });
    }

    close(): void {
        this.selected.set(null);
    }

    async approve(): Promise<void> {
        const a = this.selected();
        if (
            !a ||
            !(await this.confirm.confirm(
                `Run ${a.action} now, as ${a.author} asked? It runs as that machine, with every check run again.`,
                'Approve the action?',
            ))
        )
            return;
        this.decide('approve');
    }

    async decline(): Promise<void> {
        const a = this.selected();
        if (!a) return;
        const ok = await this.confirm.confirmDestructive(
            `Decline ${a.action}? Nothing runs; ${a.author} can ask again.`,
            {
                title: 'Decline the action?',
                confirmText: 'Decline',
            },
        );
        if (ok) this.decide('decline');
    }

    private decide(verdict: 'approve' | 'decline'): void {
        const a = this.selected();
        if (!a) return;
        if (this.reason.invalid) {
            this.reason.markAsTouched();
            return;
        }
        const reason = this.reason.value.trim() || undefined;
        this.deciding.set(true);
        this.decideError.set(null);
        const call = verdict === 'approve' ? this.api.approve(a.id, reason) : this.api.decline(a.id, reason);
        call.subscribe({
            next: (r) => {
                this.deciding.set(false);
                this.toastr.success(
                    verdict === 'decline'
                        ? 'Action declined'
                        : r.ran
                          ? 'Action approved and run'
                          : 'Action approved, but it did not run',
                );
                this.selected.set(r.pendingAction);
                this.load();
            },
            error: (err) => {
                this.deciding.set(false);
                this.decideError.set(apiErrorMessage(err, `The action could not be ${verdict}d`));
                this.load();
            },
        });
    }
}
