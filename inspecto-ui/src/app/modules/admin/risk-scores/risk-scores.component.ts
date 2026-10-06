import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { RiskScorePanelComponent } from 'app/inspecto/components/risk-score-panel.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { RiskModelView, riskModelView } from 'app/inspecto/risk/risk-score-view';

/**
 * Risk Scores — read-only list + detail (ASSURE-RISK-SCORE-RESIDUALS-1 (2), slice S1; D-RP1 own admin route).
 * Models come from `GET /components/risk-score`; an entity's latest score through the existing panel.
 */
@Component({
    selector: 'app-risk-scores',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatIconModule,
        InspectoPageHeaderComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
        RiskScorePanelComponent,
    ],
    templateUrl: './risk-scores.component.html',
})
export class RiskScoresComponent implements OnInit {
    private components = inject(ComponentsService);
    private dialog = inject(MatDialog);

    readonly loading = signal(true);
    readonly error = signal<string | null>(null);
    readonly models = signal<RiskModelView[]>([]);
    readonly selectedId = signal<string | null>(null);
    readonly selected = computed(() => this.models().find((m) => m.id === this.selectedId()) ?? null);
    /** The entity key typed into the lookup; committed on submit so the panel loads once. */
    readonly entityDraft = new FormControl('', { nonNullable: true });
    readonly entityKey = signal<string | null>(null);

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.error.set(null);
        this.components.list('risk-score').subscribe({
            next: (defs: ComponentDef[]) => {
                this.models.set(defs.map((d) => riskModelView(d.name, d.content ?? {})));
                this.loading.set(false);
            },
            error: (err) => {
                this.error.set(apiErrorMessage(err, 'Could not load Risk Scores.'));
                this.loading.set(false);
            },
        });
    }

    select(id: string): void {
        this.selectedId.set(id);
        this.entityDraft.reset();
        this.entityKey.set(null);
    }

    lookUp(): void {
        const k = this.entityDraft.value.trim();
        this.entityKey.set(k || null);
    }

    history(m: RiskModelView): void {
        this.dialog
            .open(ComponentHistoryDialog, { data: { type: 'risk-score', id: m.id, label: m.id } })
            .afterClosed()
            .subscribe((restored) => {
                if (restored) this.load();
            });
    }
}
