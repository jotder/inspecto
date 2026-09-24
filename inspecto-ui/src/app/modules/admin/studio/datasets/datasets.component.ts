import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { catchError, of } from 'rxjs';
import {
    AlertRule,
    AlertsService,
    apiErrorMessage,
    EventsService,
    ExchangeService,
    LensService,
    parseSharedRef,
    SessionService,
    SpacesService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { OfferShareDialog, OfferShareResult } from 'app/inspecto/components/offer-share.dialog';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { TransferMenuComponent } from 'app/inspecto/transfer';
import { BindSharedDatasetDialog, BindSharedDatasetResult } from './bind-shared-dataset.dialog';
import { TagAssignmentDialog } from 'app/inspecto/tags/tag-assignment.dialog';
import { buildDataset, Dataset } from './dataset-types';
import { DatasetsService } from './datasets.service';
import {
    DatasetFreshness,
    deriveFreshness,
    FRESHNESS_SIGNAL_LIMIT,
    freshnessBadge,
    maximumAgeFor,
} from './dataset-freshness';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/**
 * Studio **Datasets** — the data-source abstractions (physical / virtual / materialized) widgets build on.
 * Lists the `dataset` components (mock-served) with their kind + source, and links to the editor. Mirrors
 * `ConnectionsComponent`; the first Studio surface on the unified component model.
 */
@Component({
    selector: 'app-datasets',
    standalone: true,
    imports: [
        InspectoPageHeaderComponent,
        AiExplainComponent,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        RouterLink,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
        TransferMenuComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './datasets.component.html',
})
export class DatasetsComponent implements OnInit {
    private api = inject(DatasetsService);
    /**
     * `bootstrap.features.ops` — cross-entity tags and comments are
     * operational-object edges, so they live in the optional inspecto-ops module
     * (EDITIONS CP-11, EDG-01 cell 7). The menu action is HIDDEN when absent, the
     * geoLink precedent: an affordance that can only 503 is worse than none.
     */
    readonly opsEnabled = inject(SessionService).opsEnabled;
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    private dialog = inject(MatDialog);
    private exchange = inject(ExchangeService);
    private spaces = inject(SpacesService);
    private events = inject(EventsService);
    private alerts = inject(AlertsService);

    /** Offering is available only on a multi-space runtime (bootstrap.features.exchange) **and** to a
     *  subject holding `canOfferDatasets` — the server gates `POST /exchange/offers` on it (D14: Admin
     *  only, cross-space data exposure has no second gate), so without the capability check the button
     *  renders for everyone and fails 403 on click. */
    private readonly exchangeEnabled = inject(SessionService).exchangeEnabled;
    private readonly lens = inject(LensService);
    readonly canShare = computed(() => this.exchangeEnabled() && this.lens.canOfferDatasets());

    readonly datasets = signal<Dataset[]>([]);
    readonly loading = signal(false);
    readonly writesDisabled = signal(false);
    readonly filterText = signal('');
    /** Per-Dataset freshness, derived at read time; absent = still checking. Never persisted. */
    readonly freshness = signal<Record<string, DatasetFreshness>>({});

    readonly visibleDatasets = computed(() => {
        const q = this.filterText().trim().toLowerCase();
        const all = this.datasets();
        if (!q) return all;
        return all.filter((d) => [d.id, d.kind, d.sourceName].join(' ').toLowerCase().includes(q));
    });

    /** The filtered datasets as transfer references — what the export/import menu offers. */
    readonly transferItems = computed(() =>
        this.visibleDatasets().map((d) => ({ kind: 'dataset' as const, id: d.id })),
    );

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.list().subscribe({
            next: (d) => {
                this.datasets.set(d);
                this.loading.set(false);
                this.loadFreshness(d);
            },
            error: () => {
                this.datasets.set([]);
                this.loading.set(false);
                this.toastr.warning('Could not load datasets — is ControlApi running?');
            },
        });
    }

    /**
     * Freshness badges (DUCKLE-C1 residual 4). Loaded AFTER the cards render, one rules fetch plus one
     * ledger read per Dataset, each resolving into its own card — nothing blocks the list. Every failure
     * lands on `unknown`; a failed rules fetch just means no declared limit (never a `fresh` verdict).
     */
    private loadFreshness(datasets: Dataset[]): void {
        this.freshness.set({});
        if (!datasets.length) return;
        this.alerts
            .rules()
            .pipe(catchError(() => of([] as AlertRule[])))
            .subscribe((rules) => {
                for (const d of datasets) {
                    const maxAge = maximumAgeFor(Array.isArray(rules) ? rules : [], d.id);
                    this.events
                        .signals({ type: 'dataset.write', source: `dataset:${d.id}`, limit: FRESHNESS_SIGNAL_LIMIT })
                        .pipe(catchError(() => of(null)))
                        .subscribe((rows) =>
                            this.freshness.update((m) => ({ ...m, [d.id]: deriveFreshness(rows, maxAge) })),
                        );
                }
            });
    }

    /** Badge for a card, or null while its ledger read is in flight. */
    freshnessBadge(d: Dataset): { value: string; label: string; tooltip: string } | null {
        const f = this.freshness()[d.id];
        return f ? freshnessBadge(f) : null;
    }

    onFilter(ev: Event): void {
        this.filterText.set((ev.target as HTMLInputElement).value);
    }

    /**
     * Column count gives a quick sense of the dataset's shape on the card — but only when the Dataset
     * DECLARES columns.
     *
     * <p>⚠ Zero declared is not zero columns. A physical Dataset registered automatically (the one a
     * pipeline registers at go-live) declares none, and `DatasetRowsService.columns` answers for it with a
     * 1-row probe of the store, so every screen that needs a column list already gets one — the card was
     * the only thing asserting "0 columns" over a store that resolves perfectly well, which reads as a
     * registration that produced nothing (BACKLOG CATALOG-DS-COLUMNS-1). The template therefore says
     * where the shape comes from instead of printing a false zero.
     *
     * <p>⛔ Do not "fix" this by projecting the schema's fields into `columns` at registration: a DECLARED
     * list WINS over the probe, so a baked snapshot would silently outlive a schema change and start
     * describing the store wrongly. Deriving at read time is the property worth keeping.
     */
    columnCount(d: Dataset): number {
        return d.columns.length;
    }

    /** The owner space when this dataset is bound to a cross-space shared ref, else null (scope badge). */
    sharedOwner(d: Dataset): string | null {
        return parseSharedRef(d.physicalRef)?.owner ?? null;
    }

    /** Tags on this dataset (D7) — cross-entity assignment edges, so the label is findable from `/tags`
     *  alongside tagged incidents and saved views. An annotation, not an edit of the dataset config. */
    openTags(d: Dataset): void {
        this.dialog.open(TagAssignmentDialog, {
            data: { targetKind: 'dataset', targetId: d.id, label: d.name || d.id },
        });
    }

    /** Offer this dataset in the cross-space shareable catalog (owner = the active space). */
    offer(d: Dataset): void {
        const owner = this.spaces.currentSpaceId() ?? 'default';
        this.dialog
            .open(OfferShareDialog, { data: { kind: 'dataset', owner, item: d.id } })
            .afterClosed()
            .subscribe((r: OfferShareResult | undefined) => {
                if (!r) return;
                this.exchange.offer({ kind: 'dataset', owner, item: d.id, description: r.description }).subscribe({
                    next: () => this.toastr.success(`Dataset "${d.id}" offered for sharing.`),
                    error: (e) => this.toastr.error(apiErrorMessage(e, `Could not offer "${d.id}".`)),
                });
            });
    }

    /** Bind an active cross-space dataset grant as a local `physical` dataset (physicalRef = `shared/<owner>/<item>`). */
    bindShared(): void {
        const me = this.spaces.currentSpaceId() ?? 'default';
        this.dialog
            .open(BindSharedDatasetDialog, {
                data: { me, existingNames: this.datasets().map((d) => d.id) },
            })
            .afterClosed()
            .subscribe((r: BindSharedDatasetResult | undefined) => {
                if (!r) return;
                const ref = `shared/${r.owner}/${r.item}`;
                this.api.save(buildDataset(r.name, 'physical', r.item, { physicalRef: ref })).subscribe({
                    next: () => {
                        this.toastr.success(`Bound ${r.owner}/${r.item} as local dataset "${r.name}".`);
                        this.load();
                    },
                    error: (e) => {
                        if (e?.status === 503) this.writesDisabled.set(true);
                        this.toastr.error(
                            e?.status === 503
                                ? 'Writes are disabled (no write root configured).'
                                : apiErrorMessage(e, `Could not bind "${r.item}".`),
                        );
                    },
                });
            });
    }

    async remove(d: Dataset): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete dataset "${d.id}"?`, { title: 'Delete dataset' }))) return;
        this.api.remove(d.id).subscribe({
            next: () => {
                this.toastr.success(`Dataset "${d.id}" deleted`);
                this.load();
            },
            error: (e) => {
                if (e?.status === 503) this.writesDisabled.set(true);
                this.toastr.error(
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : apiErrorMessage(e, `Could not delete "${d.id}".`),
                );
            },
        });
    }
}
