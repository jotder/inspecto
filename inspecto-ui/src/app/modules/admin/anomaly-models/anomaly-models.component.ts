import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { AnomalyModelView, anomalyAlertRuleParams, anomalyModelView } from 'app/inspecto/anomaly/anomaly-model-form';
import { AnomalyModelsService } from 'app/inspecto/api/anomaly-models.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { ComponentDef } from 'app/inspecto/api/components.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import {
    AnomalyModelFormData,
    AnomalyModelFormDialog,
    AnomalyModelFormResult,
    heldAnomalyChange,
} from './anomaly-model-form.dialog';
import { AnomalyModelPreviewComponent } from './anomaly-model-preview.component';

/**
 * Anomaly Models — the authoring pane beside Risk Scores (ANOMALY-DETECTION-1 S5 lane A, design §13.1). List +
 * detail of the `anomaly-model` components (`/components/anomaly-model`); create / edit / delete gated on
 * `canAuthorWorkbench` (the generic component write gate); the preview box gated on `canWorkIncidents` (the gate of
 * `POST /anomaly-scores/preview`). The server gates every call again.
 */
@Component({
    selector: 'app-anomaly-models',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatIconModule,
        RouterLink,
        InspectoPageHeaderComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        AnomalyModelPreviewComponent,
    ],
    templateUrl: './anomaly-models.component.html',
})
export class AnomalyModelsComponent implements OnInit {
    readonly lens = inject(LensService);
    private api = inject(AnomalyModelsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    readonly loading = signal(true);
    readonly error = signal<string | null>(null);
    readonly models = signal<AnomalyModelView[]>([]);
    readonly selectedId = signal<string | null>(null);
    readonly selected = computed(() => this.models().find((m) => m.id === this.selectedId()) ?? null);

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.error.set(null);
        this.api.list().subscribe({
            next: (defs: ComponentDef[]) => {
                this.models.set(defs.map((d) => anomalyModelView(d.name, d.content ?? {})));
                this.loading.set(false);
            },
            error: (err) => {
                this.error.set(apiErrorMessage(err, 'Could not load Anomaly Models.'));
                this.loading.set(false);
            },
        });
    }

    select(id: string): void {
        this.selectedId.set(id);
    }

    alertRuleParams(m: AnomalyModelView): Record<string, string> {
        return anomalyAlertRuleParams(m.id, m.highThreshold);
    }

    create(): void {
        this.open({});
    }

    /** A fresh read first, so `If-Match` carries the hash of what is stored now. */
    edit(id: string): void {
        this.api.get(id).subscribe({
            next: (existing) => this.open({ existing }),
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not open the Anomaly Model.')),
        });
    }

    async remove(id: string): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Delete Anomaly Model "${id}"? Its derived Datasets stay; History can restore it.`,
            { title: 'Delete Anomaly Model?', confirmText: 'Delete' },
        );
        if (!ok) return;
        this.api.remove(id).subscribe({
            next: (res) => {
                const held = heldAnomalyChange(res);
                if (held) this.toastr.info('Delete held for approval' + (held.id ? ` as ${held.id}` : ''));
                else {
                    this.toastr.success(`Anomaly Model "${id}" deleted`);
                    this.selectedId.set(null);
                }
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not delete the Anomaly Model.')),
        });
    }

    history(m: AnomalyModelView): void {
        this.dialog
            .open(ComponentHistoryDialog, { data: { type: 'anomaly-model', id: m.id, label: m.id } })
            .afterClosed()
            .subscribe((restored) => {
                if (restored) this.load();
            });
    }

    private open(data: AnomalyModelFormData): void {
        this.dialog
            .open(AnomalyModelFormDialog, { data, width: '880px', maxHeight: '90vh' })
            .afterClosed()
            .subscribe((r?: AnomalyModelFormResult) => {
                if (r?.held) {
                    this.toastr.info('Change held for approval' + (r.held.id ? ` as ${r.held.id}` : ''));
                    this.load();
                } else if (r?.saved) {
                    this.toastr.success(`Anomaly Model "${r.saved.name}" saved`);
                    this.selectedId.set(r.saved.name);
                    this.load();
                }
            });
    }
}
