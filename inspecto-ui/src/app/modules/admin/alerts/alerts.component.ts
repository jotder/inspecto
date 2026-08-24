import { ChangeDetectionStrategy, Component, inject, OnInit, signal, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { AlertRule, AlertsService, apiErrorMessage, FiredAlert, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { AlertRuleFormData, AlertRuleFormDialog, AlertRuleFormResult } from './alert-rule-form.dialog';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';
import { AiStatusData, AiStatusDialog } from 'app/inspecto/ai-assist/ai-status.dialog';

/**
 * Alerts — the core alert engine's surface (v4.1, B5): recent fired alerts (GET /alerts) over the
 * armed Alert Rules (GET /alerts/rules), with a manual evaluation sweep. Rules are authored right
 * here (audit C3 — create/edit/delete, Ops-gated via `canAuthorAlertRules`); the Assistant's
 * diagnose-and-alert skill can still draft one.
 */
@Component({
    selector: 'app-alerts',
    standalone: true,
    imports: [
        AiExplainComponent,
        FormsModule,
        InspectoEmptyStateComponent,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        DataTableComponent,
    ],
    templateUrl: './alerts.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class AlertsComponent implements OnInit {
    private api = inject(AlertsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    protected lens = inject(LensService);

    readonly alerts = signal<FiredAlert[]>([]);
    readonly rules = signal<AlertRule[]>([]);
    readonly loading = signal(false);
    readonly loadError = signal(false);
    readonly evaluating = signal(false);

    readonly columnDefs: ColDef<FiredAlert>[] = [
        {
            field: 'epochMillis',
            headerName: 'When',
            width: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        {
            field: 'severity',
            headerName: 'Severity',
            width: 120,
            cellRenderer: (p: ICellRendererParams<FiredAlert>) => statusBadgeHtml(p.value as string),
        },
        { field: 'rule', headerName: 'Rule', flex: 1 },
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        { field: 'metric', headerName: 'Metric', width: 140 },
        { field: 'value', headerName: 'Value', width: 110 },
        {
            field: 'message',
            headerName: 'Message',
            flex: 3,
            wrapText: true,
            autoHeight: true,
        },
    ];

    readonly ruleColumnDefs: ColDef<AlertRule>[] = [
        { field: 'name', headerName: 'Rule', flex: 1, minWidth: 160 },
        { field: 'metric', headerName: 'Metric', flex: 1, minWidth: 140 },
        {
            headerName: 'Condition',
            width: 170,
            valueGetter: (p) => (p.data ? `${p.data.comparator} ${p.data.threshold} / ${p.data.window}` : ''),
        },
        {
            field: 'severity',
            headerName: 'Severity',
            width: 120,
            cellRenderer: (p: ICellRendererParams<AlertRule>) => statusBadgeHtml(p.value as string),
        },
        {
            headerName: 'Scope',
            width: 160,
            valueGetter: (p) => p.data?.onPipeline || 'every pipeline',
        },
    ];

    /**
     * "What happened" on a fired alert (AGT-6a A4-status) — the alert IS the red thing, so this is the
     * reference adoption. It reads the pipeline's live state plus everything the ledger recorded around
     * it, focused on that pipeline.
     *
     * Ungated on purpose: it has no write path, and a Business-lens operator asking why an alert fired
     * is exactly who needs it. Do not "make it consistent" with `ruleActions` below, which gates because
     * it authors config.
     */
    get firedActions(): InspectoRowAction<FiredAlert>[] {
        return [
            {
                icon: 'heroicons_outline:information-circle',
                hint: 'What happened',
                onClick: (a) =>
                    this.dialog.open(AiStatusDialog, {
                        data: {
                            label: a.rule,
                            pipelineId: a.pipeline,
                        } satisfies AiStatusData,
                    }),
            },
        ];
    }

    /** Edit/delete author monitoring config — Ops-gated (audit C3). */
    get ruleActions(): InspectoRowAction<AlertRule>[] {
        if (!this.lens.canAuthorAlertRules()) return [];
        return [
            {
                icon: 'heroicons_outline:pencil-square',
                hint: 'Edit',
                onClick: (r) => this.editRule(r),
            },
            {
                icon: 'heroicons_outline:trash',
                hint: 'Delete',
                onClick: (r) => this.removeRule(r),
            },
        ];
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.loadError.set(false);
        this.api.recent(100).subscribe({
            next: (a) => {
                this.alerts.set(a);
                this.loading.set(false);
            },
            error: () => {
                // Unreachable-backend messaging is the connectivity banner's job (§8) — surface an
                // inline error state with retry rather than a transient toast.
                this.alerts.set([]);
                this.loadError.set(true);
                this.loading.set(false);
            },
        });
        this.api.rules().subscribe({
            next: (r) => this.rules.set(r),
            error: () => this.rules.set([]),
        });
    }

    evaluate(): void {
        this.evaluating.set(true);
        this.api.evaluate().subscribe({
            next: (fired) => {
                this.evaluating.set(false);
                this.toastr.info(
                    fired.length === 0
                        ? 'Evaluation pass complete — nothing breached'
                        : `${fired.length} alert(s) fired`,
                );
                this.load();
            },
            error: (e) => {
                this.evaluating.set(false);
                this.toastr.warning(apiErrorMessage(e, 'No alert rules armed — create one under Alert Rules below.'));
            },
        });
    }

    newRule(): void {
        const data: AlertRuleFormData = {
            existingNames: this.rules().map((r) => r.name),
        };
        this.dialog
            .open(AlertRuleFormDialog, { data, width: '560px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: AlertRuleFormResult) => {
                if (r?.saved) {
                    this.toastr.success(`Alert rule "${r.saved.name}" armed`);
                    this.load();
                }
            });
    }

    editRule(rule: AlertRule): void {
        const data: AlertRuleFormData = { rule };
        this.dialog
            .open(AlertRuleFormDialog, { data, width: '560px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: AlertRuleFormResult) => {
                if (r?.saved) {
                    this.toastr.success(`Alert rule "${r.saved.name}" saved`);
                    this.load();
                }
            });
    }

    async removeRule(rule: AlertRule): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete alert rule "${rule.name}"?`))) return;
        this.api.removeRule(rule.name).subscribe({
            next: () => {
                this.toastr.success(`Alert rule "${rule.name}" deleted`);
                this.rules.set(this.rules().filter((r) => r.name !== rule.name));
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Could not delete "${rule.name}".`)),
        });
    }
}
