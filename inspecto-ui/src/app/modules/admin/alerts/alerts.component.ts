import {
    ChangeDetectionStrategy,
    Component,
    computed,
    ElementRef,
    inject,
    OnInit,
    signal,
    viewChild,
    ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    AlertRule,
    AlertsService,
    apiErrorMessage,
    FiredAlert,
    LensService,
    optimisticMutate,
    PendingAlertRule,
} from 'app/inspecto/api';
import { HttpErrorResponse } from '@angular/common/http';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { AlertRuleFormData, AlertRuleFormDialog, AlertRuleFormResult } from './alert-rule-form.dialog';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';
import { AiStatusData, AiStatusDialog } from 'app/inspecto/ai-assist/ai-status.dialog';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

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
        InspectoPageHeaderComponent,
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
    private route = inject(ActivatedRoute);
    private router = inject(Router);

    readonly alerts = signal<FiredAlert[]>([]);
    readonly rules = signal<AlertRule[]>([]);
    readonly loading = signal(false);
    readonly loadError = signal(false);
    readonly evaluating = signal(false);
    readonly pending = signal<PendingAlertRule[]>([]);
    /** `hidden` = the route is absent (404/503 — an edition without it); `error` = it failed. */
    readonly pendingState = signal<'loading' | 'ready' | 'error' | 'hidden'>('loading');

    readonly pendingColumnDefs: ColDef<PendingAlertRule>[] = [
        { field: 'name', headerName: 'Rule', flex: 1, minWidth: 160 },
        {
            colId: 'afterScore',
            headerName: 'Waits on',
            flex: 1,
            minWidth: 160,
            valueGetter: (p) => p.data?.afterScore ?? null,
            valueFormatter: (p) => {
                const a = p.value as PendingAlertRule['afterScore'];
                if (!a) return '—';
                return `${a.kind === 'anomaly-model' ? 'Anomaly Model' : 'Risk Score'} ${a.model}`;
            },
        },
        { field: 'dataset', headerName: 'Dataset', flex: 1, minWidth: 140, valueFormatter: (p) => p.value || '—' },
        { field: 'error', headerName: 'Problem', flex: 2, minWidth: 160, valueFormatter: (p) => p.value || '' },
        {
            colId: 'lastRefusal',
            headerName: 'Why still pending',
            flex: 3,
            minWidth: 220,
            valueGetter: (p) => p.data?.lastRefusal ?? null,
            valueFormatter: (p) => {
                const r = p.value as PendingAlertRule['lastRefusal'];
                if (!r) return '';
                const at = new Date(r.at);
                return `${r.reason} (${isNaN(at.getTime()) ? r.at : at.toLocaleString()})`;
            },
        },
    ];

    /** True once any pending rule carries its latest refusal — the reason is then shown per row, not in the audit log. */
    readonly pendingHasRefusal = computed(() => this.pending().some((p) => !!p.lastRefusal));

    // DataTable's default flex overrides a bare width, so widths here are floors (minWidth); fixed badges also cap (maxWidth).
    readonly columnDefs: ColDef<FiredAlert>[] = [
        {
            field: 'epochMillis',
            headerName: 'When',
            minWidth: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        {
            field: 'severity',
            headerName: 'Severity',
            minWidth: 120,
            maxWidth: 120,
            cellRenderer: (p: ICellRendererParams<FiredAlert>) => statusBadgeHtml(p.value as string),
        },
        {
            field: 'state',
            headerName: 'State',
            minWidth: 150,
            maxWidth: 150,
            cellRenderer: (p: ICellRendererParams<FiredAlert>) => (p.value ? statusBadgeHtml(p.value as string) : '—'),
        },
        { field: 'rule', headerName: 'Rule', flex: 1 },
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        { field: 'metric', headerName: 'Metric', minWidth: 140 },
        { field: 'value', headerName: 'Value', minWidth: 110 },
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
        { headerName: 'Metric', flex: 1, minWidth: 140, valueGetter: (p) => (p.data ? ruleMetricText(p.data) : '') },
        { headerName: 'Condition', minWidth: 170, valueGetter: (p) => (p.data ? ruleConditionText(p.data) : '') },
        {
            field: 'severity',
            headerName: 'Severity',
            minWidth: 120,
            maxWidth: 120,
            cellRenderer: (p: ICellRendererParams<AlertRule>) => statusBadgeHtml(p.value as string),
        },
        {
            headerName: 'Scope',
            minWidth: 160,
            valueGetter: (p) => p.data?.onPipeline || 'every pipeline',
        },
    ];

    /** Polite live region: success ("<title> acknowledged") and plain-language failures. Focus lands here after an action. */
    readonly statusMessage = signal<{ text: string; error: boolean } | null>(null);
    private readonly statusRegion = viewChild<ElementRef<HTMLElement>>('statusRegion');
    /** A 403 on ack/resolve means the session lacks `canWorkIncidents` after all — drop to read-only. */
    private readonly forbidden = signal(false);
    /** Ack/Resolve ride `canWorkIncidents` (same gate as the Incident lifecycle verbs). */
    readonly canWork = computed(() => this.lens.canWorkIncidents() && !this.forbidden());

    /**
     * "What happened" on a fired alert (AGT-6a A4-status) — the alert IS the red thing, so this is the
     * reference adoption. It reads the pipeline's live state plus everything the ledger recorded around
     * it, focused on that pipeline.
     *
     * Ungated on purpose: it has no write path, and a Business-lens operator asking why an alert fired
     * is exactly who needs it. Do not "make it consistent" with `ruleActions` below, which gates because
     * it authors config.
     *
     * Acknowledge / Resolve are `canWorkIncidents`-gated and only offered for a stored Alert (`id`) in a
     * state that allows the move (OPEN → Acknowledge + Resolve, ACKNOWLEDGED → Resolve). Resolve asks no
     * confirmation, mirroring the Incident lifecycle verbs. Computed so the array identity is stable.
     */
    /** The Investigation an alert's rule watches (an Investigation rule fires with the Investigation as its scope). */
    investigationOf(a: FiredAlert): string | null {
        return this.rules().find((r) => r.name === a.rule)?.investigation ?? null;
    }

    readonly firedActions = computed<InspectoRowAction<FiredAlert>[]>(() => {
        const actions: InspectoRowAction<FiredAlert>[] = [
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
            {
                // DR-U9: the Alert carries the COUNT only (by design — an Alert is readable by people who are not members
                // of the Investigation), so the breaching entities are read where the Investigation's own gates and
                // masking apply: open it, and its Value Measures show who breached.
                icon: 'heroicons_outline:share',
                hint: (a) => `Open Investigation ${this.investigationOf(a)} — it lists the breaching entities`,
                visible: (a) => !!this.investigationOf(a),
                onClick: (a) =>
                    void this.router.navigate(['/studio/link-analysis'], {
                        queryParams: { investigation: this.investigationOf(a) },
                    }),
            },
        ];
        if (this.canWork()) {
            actions.push(
                {
                    icon: 'heroicons_outline:check',
                    hint: (a) => `Acknowledge alert ${alertTitle(a)}`,
                    visible: (a) => !!a.id && a.state === 'OPEN',
                    onClick: (a) => this.work(a, 'acknowledged'),
                },
                {
                    icon: 'heroicons_outline:check-circle',
                    hint: (a) => `Resolve alert ${alertTitle(a)}`,
                    visible: (a) => !!a.id && (a.state === 'OPEN' || a.state === 'ACKNOWLEDGED'),
                    onClick: (a) => this.work(a, 'resolved'),
                },
            );
        }
        return actions;
    });

    /**
     * Acknowledge / resolve one Alert, optimistically: the row's State flips at once, the server's record
     * reconciles it, and a failure restores the previous state and says why in the live region.
     */
    work(alert: FiredAlert, verb: 'acknowledged' | 'resolved'): void {
        const id = alert.id;
        if (!id) return;
        const before = alert.state;
        const title = alertTitle(alert);
        const setState = (state: FiredAlert['state']) =>
            this.alerts.update((rows) => rows.map((r) => (r.id === id ? { ...r, state } : r)));
        optimisticMutate({
            apply: () => setState(verb === 'acknowledged' ? 'ACKNOWLEDGED' : 'RESOLVED'),
            commit: verb === 'acknowledged' ? this.api.acknowledge(id) : this.api.resolve(id),
            reconcile: (w) => {
                setState(w.state);
                this.announce(`${title} ${verb}`, false);
            },
            rollback: () => setState(before),
            onError: (e) => {
                const status = e instanceof HttpErrorResponse ? e.status : 0;
                if (status === 403) this.forbidden.set(true);
                this.announce(workErrorMessage(status, title), true);
            },
        });
    }

    /** Set the live-region text and move focus to it (the acted-on button may have just left the row). */
    private announce(text: string, error: boolean): void {
        this.statusMessage.set({ text, error });
        setTimeout(() => this.statusRegion()?.nativeElement.focus());
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
        // `?newRule=1&dataset=…` — a prefilled create handed over by another pane (Risk Scores, D-RP9). Strip the
        // params first, then open: MatDialog closes open dialogs on navigation.
        const q = this.route.snapshot.queryParamMap;
        if (q.get('newRule') === '1') {
            const seed = alertRuleSeed(q);
            this.router
                .navigate([], { relativeTo: this.route, queryParams: {}, replaceUrl: true })
                .then(() => this.newRule(seed));
        }
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
        this.api.pendingRules().subscribe({
            next: (p) => {
                this.pending.set(p);
                this.pendingState.set('ready');
            },
            error: (e: unknown) => {
                this.pending.set([]);
                const status = e instanceof HttpErrorResponse ? e.status : 0;
                this.pendingState.set(status === 404 || status === 503 ? 'hidden' : 'error');
            },
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

    newRule(seed?: Partial<AlertRule>): void {
        const data: AlertRuleFormData = {
            existingNames: this.rules().map((r) => r.name),
            ...(seed ? { seed } : {}),
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

/** What an Alert is called in a button name or announcement: its message, else its rule. */
export function alertTitle(a: FiredAlert): string {
    return a.message || a.rule;
}

/** Plain-language reason a 403 / 404 / 422 ack-or-resolve failed. */
export function workErrorMessage(status: number, title: string): string {
    switch (status) {
        case 403:
            return 'You do not have permission to work alerts.';
        case 404:
            return `Alert ${title} no longer exists — refresh the list.`;
        case 422:
            return `Alert ${title} was already resolved or acknowledged — refresh the list.`;
        default:
            return `Could not update alert ${title}. Try again.`;
    }
}

/** The prefilled rule a `?newRule=1` link carries: only the keys it names; `by` is a comma list. */
export function alertRuleSeed(q: ParamMap): Partial<AlertRule> {
    const seed: Partial<AlertRule> = {};
    for (const k of ['dataset', 'measure', 'comparator', 'description'] as const) {
        const v = q.get(k);
        if (v) (seed as Record<string, unknown>)[k] = v;
    }
    const by = q.get('by');
    if (by)
        seed.by = by
            .split(',')
            .map((s) => s.trim())
            .filter(Boolean);
    const t = q.get('threshold');
    if (t != null && t !== '' && isFinite(Number(t))) seed.threshold = Number(t);
    return seed;
}

/** The Metric cell: a ledger metric, else a Dataset rule's Measure "on" its Dataset (per `by` keys when set). */
export function ruleMetricText(r: AlertRule): string {
    if (r.metric) return r.metric;
    if (!r.measure) return r.dataset ?? '';
    const by = r.by?.length ? ` by ${r.by.join(', ')}` : '';
    return r.dataset ? `${r.measure} on ${r.dataset}${by}` : `${r.measure}${by}`;
}

/** The Condition cell: comparator and threshold, plus ` / window` only when the rule has a window. */
export function ruleConditionText(r: AlertRule): string {
    const base = `${r.comparator} ${r.threshold}`;
    return r.window ? `${base} / ${r.window}` : base;
}
