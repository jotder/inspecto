import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import {
    comparingMessage,
    RECON_DEFAULT_PAGE_SIZE,
    Reconciliation,
    ReconciliationsService,
    ReconFilter,
    ReconGrainTableComponent,
    ReconRunQuery,
    ReconToolbarComponent,
} from 'app/inspecto/reconciliation';
import { ReconExecService } from './recon-exec.service';
import { ReconPageLoader } from './recon-page.loader';

/**
 * Read-only **Reconciliation widget** host (DAT-7 P3): renders a saved `reconciliation` Component on a dashboard tile
 * as ONE day's page of its grain rows (RECON-PERF-1, operator 2026-10-09) — band-tinted, Δ% only (values stay on the
 * Board). Day, filter, page and sample are the tile's own state, never persisted. Loaded lazily through the viz
 * component-loader registry (`widget.kind`); editing happens at `/reconciliation/:id`.
 */
@Component({
    selector: 'app-recon-view-widget',
    standalone: true,
    imports: [
        RouterLink,
        MatButtonModule,
        MatProgressSpinnerModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        ReconGrainTableComponent,
        ReconToolbarComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex h-full min-h-0 flex-col gap-1">
            @if (loadError(); as message) {
                <inspecto-alert class="block" variant="warning">{{ message }}</inspecto-alert>
            } @else if (recon(); as r) {
                <inspecto-recon-toolbar
                    [availableDays]="result()?.availableDays ?? []"
                    [day]="result()?.day ?? query().day"
                    [filter]="query().filter"
                    [sample]="query().sample"
                    [threeWay]="!!r.thirdDataset"
                    [bands]="r.bands"
                    (dayChange)="request({ day: $event, offset: 0 })"
                    (filterChange)="request({ filter: $event, offset: 0 })"
                    (sampleChange)="request({ sample: $event, offset: 0 })"
                />
                @if (pages.loading()) {
                    <div class="text-secondary flex flex-auto items-center justify-center gap-2 text-sm" role="status">
                        <mat-progress-spinner diameter="20" mode="indeterminate" aria-hidden="true" />
                        <span>{{ comparing() }}</span>
                    </div>
                } @else if (pages.error(); as message) {
                    <inspecto-alert class="block" variant="error">
                        {{ message }}
                        @if (pages.retryable()) {
                            <button mat-button type="button" (click)="pages.retry()">Retry</button>
                        } @else {
                            <span>Fix the Dataset or the Reconciliation — retrying will not change this.</span>
                        }
                    </inspecto-alert>
                } @else if (result(); as res) {
                    <div class="text-secondary flex flex-wrap items-center gap-x-3 text-xs">
                        <a [routerLink]="['/reconciliation', r.id]" class="hover:underline">
                            {{ r.leftDataset }} vs {{ r.rightDataset }}
                        </a>
                        <span>{{ res.day }}</span>
                        <span>{{ res.summary.matchedKeys }} matched</span>
                        <span>{{ res.summary.byType.missing_right + res.summary.byType.missing_left }} missing</span>
                        <span>{{ res.summary.byType.value_break }} value breaks</span>
                        @if (res.sample?.sampled) {
                            <span data-testid="sampled"
                                >sampled: {{ res.sample?.keys }} of {{ res.sample?.totalKeys }} keys</span
                            >
                        }
                    </div>
                    <inspecto-recon-grain-table
                        class="min-h-0 flex-auto overflow-auto"
                        [result]="res"
                        [bands]="r.bands"
                        [includeValues]="false"
                        (pageChange)="request($event)"
                    />
                }
            } @else if (loaded()) {
                <inspecto-empty-state
                    icon="heroicons_outline:scale"
                    message="No saved Reconciliation bound to this widget."
                />
            } @else {
                <div class="text-secondary flex h-full items-center justify-center gap-2 text-sm" role="status">
                    <mat-progress-spinner diameter="20" mode="indeterminate" aria-hidden="true" />
                    <span>Loading the Reconciliation…</span>
                </div>
            }
        </div>
    `,
})
export class ReconViewWidgetComponent {
    private reconApi = inject(ReconciliationsService);
    private exec = inject(ReconExecService);

    /** The saved `reconciliation` id this widget renders (the widget's binding). */
    readonly viewId = input<string | undefined>(undefined);

    readonly recon = signal<Reconciliation | null>(null);
    readonly loadError = signal<string | null>(null);
    /** The recon fetch settled (found or not) — gates the not-found empty state vs the loading strip. */
    readonly loaded = signal(false);
    /** The tile's view state — component state, never persisted (operator, 2026-10-09). */
    readonly query = signal<ReconRunQuery>({ offset: 0, limit: RECON_DEFAULT_PAGE_SIZE, filter: 'all' as ReconFilter });

    readonly pages = new ReconPageLoader(this.exec, inject(DestroyRef));
    readonly result = this.pages.result;

    readonly comparing = computed(() => {
        const r = this.recon();
        const ids = r ? [r.leftDataset, r.rightDataset, ...(r.thirdDataset ? [r.thirdDataset] : [])] : [];
        return comparingMessage(ids, this.query().day ?? this.result()?.day);
    });

    constructor() {
        effect(() => {
            const id = this.viewId();
            this.recon.set(null);
            this.pages.result.set(null);
            this.loadError.set(null);
            this.loaded.set(false);
            if (!id) {
                this.loaded.set(true);
                return;
            }
            this.reconApi.get(id).subscribe({
                next: (recon) => {
                    this.recon.set(recon);
                    this.loaded.set(true);
                    this.pages.load(recon, this.query());
                },
                error: () => {
                    this.loaded.set(true);
                    this.loadError.set(`Could not load the saved reconciliation “${id}”.`);
                },
            });
        });
    }

    /** Change the view state and re-request (the loader cancels whatever is still in flight). */
    request(patch: Partial<ReconRunQuery>): void {
        const r = this.recon();
        if (!r) return;
        const next = { ...this.query(), ...patch };
        this.query.set(next);
        this.pages.load(r, next);
    }
}
