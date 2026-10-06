import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { RiskScore, RiskScoresService } from 'app/inspecto/api/risk-scores.service';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { RiskScorePanelComponent } from 'app/inspecto/components/risk-score-panel.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { RiskModelView, riskModelView } from 'app/inspecto/risk/risk-score-view';
import { RiskScoreActionsComponent } from './risk-score-actions.component';
import { RiskScoreHeldBadgeComponent, RiskScoreHeldStore } from './risk-score-held';

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
        RiskScoreActionsComponent,
        RiskScoreHeldBadgeComponent,
        RouterLink,
    ],
    templateUrl: './risk-scores.component.html',
})
export class RiskScoresComponent implements OnInit {
    private components = inject(ComponentsService);
    private dialog = inject(MatDialog);
    private riskScores = inject(RiskScoresService);
    private held = inject(RiskScoreHeldStore);

    readonly loading = signal(true);
    readonly error = signal<string | null>(null);
    readonly models = signal<RiskModelView[]>([]);
    readonly selectedId = signal<string | null>(null);
    /**
     * Held CREATES: a pending `risk-score` create whose model is not stored yet. Read-only rows carrying only what the
     * Pending Change holds; a held edit of a stored model stays a badge on that model's row.
     */
    readonly heldCreates = computed(() => {
        const stored = new Set(this.models().map((m) => m.id));
        return Object.values(this.held.byModel()).filter((p) => p.operation === 'create' && !stored.has(p.name));
    });
    readonly selected = computed(() => this.models().find((m) => m.id === this.selectedId()) ?? null);
    /** The entity key typed into the lookup; committed on submit so the panel loads once. */
    readonly entityDraft = new FormControl('', { nonNullable: true });
    readonly entityKey = signal<string | null>(null);
    /** The S3 preview: scored now over the data, nothing written. */
    readonly previewed = signal<{ score: RiskScore; found: boolean } | null>(null);
    readonly previewError = signal<string | null>(null);

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.error.set(null);
        this.held.refresh();
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
        this.previewed.set(null);
        this.previewError.set(null);
    }

    lookUp(): void {
        const k = this.entityDraft.value.trim();
        this.previewed.set(null);
        this.entityKey.set(k || null);
    }

    preview(m: RiskModelView): void {
        const k = this.entityDraft.value.trim();
        if (!k) return;
        this.entityKey.set(null);
        this.previewed.set(null);
        this.previewError.set(null);
        this.riskScores.preview(m.id, k).subscribe({
            next: (p) =>
                this.previewed.set({
                    found: p.found,
                    score: { ...p, modelVersion: '', runId: '', scoredAt: 'preview, not saved' },
                }),
            error: (err) => this.previewError.set(apiErrorMessage(err, 'Could not preview this Risk Score.')),
        });
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
