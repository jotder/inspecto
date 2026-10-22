import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ComponentsService, LensService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { LockedActionComponent } from '@inspecto/core/components/locked-action.component';
import { InspectoPageHeaderComponent } from '@inspecto/core/components/page-header.component';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { EntityProfileService, EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import { LinkAnalysisSettingsService } from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import { EntityColumnsComponent } from './entity-columns.component';
import { DatasetEntry, EntitiesChange, changeResult, datasetEntries, splitByPresence } from './entity-columns';
import { count, day, entitiesBase, freshness, maskedUnder, notAssessedText } from './entity-profile-view';
import { NewEntityTypeComponent } from './new-entity-type.component';

/** Why *New Entity Type* is locked for a signed-in person without the right to change the Space's settings. */
export const NEW_TYPE_LOCKED_REASON =
    "Adding an Entity Type needs permission to change this Space's Link Analysis settings. Ask an administrator.";

/** The two tabs, addressed by `?tab=` (the Space guide's "Say what the columns hold" step opens `columns`). */
const TABS = ['types', 'columns'] as const;

/**
 * **Entities** (LA-ONBOARD-1 phase 5, UC-07): the Entity Types that occur in this Space's data, one card each (the
 * cached headline of `GET /inv/entity-types` - no profile query), the primary ones first; the types bound to no column
 * sit collapsed under "Not in your data". *Columns* sets what each identifier column holds and each Dataset's primary
 * Entity Type; *New Entity Type* adds one to the Space. A headline nobody computed yet says "not assessed" with its
 * reason, never a zero. Presence is read off the bindings that read already returns plus one Dataset registry read:
 * no per-type request.
 */
@Component({
    selector: 'inspecto-la-entities',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        NgTemplateOutlet,
        RouterLink,
        MatButtonModule,
        MatExpansionModule,
        MatTabsModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoEmptyStateComponent,
        InspectoPageHeaderComponent,
        InspectoSkeletonComponent,
        LockedActionComponent,
        StatusBadgeComponent,
        EntityColumnsComponent,
        NewEntityTypeComponent,
    ],
    template: `
        <inspecto-page-header
            title="Entities"
            subtitle="What the data says about each Entity Type: how many, where they appear, how fresh."
        >
            <ng-container actions>
                @if (canAddType()) {
                    <button
                        mat-flat-button
                        color="primary"
                        type="button"
                        data-testid="new-entity-type-open"
                        [disabled]="adding()"
                        (click)="openNewType()"
                    >
                        New Entity Type
                    </button>
                } @else {
                    <inspecto-locked-action
                        label="New Entity Type"
                        [reason]="newTypeLockedReason"
                        data-testid="new-entity-type-locked"
                    ></inspecto-locked-action>
                }
            </ng-container>
        </inspecto-page-header>
        <div class="flex flex-col gap-4 p-6">
            @if (adding()) {
                <inspecto-la-new-entity-type
                    (added)="typeAdded($event)"
                    (cancelled)="adding.set(false)"
                ></inspecto-la-new-entity-type>
            }
            <mat-tab-group
                [selectedIndex]="tabIndex()"
                (selectedIndexChange)="selectTab($event)"
                mat-stretch-tabs="false"
                animationDuration="0ms"
            >
                <mat-tab label="Entity Types">
                    <div class="flex flex-col gap-4 pt-4">
                        <p class="m-0 text-sm" role="status" data-testid="types-result">{{ typeResult() }}</p>
                        @if (error(); as e) {
                            <inspecto-alert variant="error" data-testid="entities-error">{{ e }}</inspecto-alert>
                        } @else if (split(); as s) {
                            @if (s.present.length) {
                                <ul
                                    class="m-0 grid list-none grid-cols-1 gap-4 p-0 sm:grid-cols-2 lg:grid-cols-3"
                                    aria-label="Entity Types in your data"
                                    data-testid="present-types"
                                >
                                    @for (t of s.present; track t.id) {
                                        <ng-container
                                            *ngTemplateOutlet="
                                                card;
                                                context: { $implicit: t, primaryIn: s.primaryIn[t.id] }
                                            "
                                        ></ng-container>
                                    }
                                </ul>
                            } @else {
                                <inspecto-empty-state
                                    icon="heroicons_outline:identification"
                                    title="No Entity Type in your data yet"
                                    message="Say what your identifier columns hold under Columns; each Entity Type a column holds shows here."
                                ></inspecto-empty-state>
                            }
                            @if (s.absent.length) {
                                <mat-expansion-panel data-testid="absent-types">
                                    <mat-expansion-panel-header>
                                        <mat-panel-title>Not in your data ({{ s.absent.length }})</mat-panel-title>
                                    </mat-expansion-panel-header>
                                    <p class="text-secondary mt-0 text-sm">
                                        No column of any Dataset holds these yet. Set one under Columns to bring it in.
                                    </p>
                                    <ul
                                        class="m-0 grid list-none grid-cols-1 gap-4 p-0 sm:grid-cols-2 lg:grid-cols-3"
                                        aria-label="Entity Types not in your data"
                                    >
                                        @for (t of s.absent; track t.id) {
                                            <ng-container
                                                *ngTemplateOutlet="
                                                    card;
                                                    context: { $implicit: t, primaryIn: s.primaryIn[t.id] }
                                                "
                                            ></ng-container>
                                        }
                                    </ul>
                                </mat-expansion-panel>
                            }
                        } @else {
                            <inspecto-skeleton [lines]="3" height="6rem"></inspecto-skeleton>
                        }
                    </div>
                </mat-tab>
                <mat-tab label="Columns">
                    <div class="pt-4">
                        @if (items(); as types) {
                            <inspecto-la-entity-columns
                                [types]="types"
                                [datasets]="datasets()"
                                [loadError]="datasetsError()"
                                [canEdit]="canEditColumns()"
                                [result]="tab() === 'columns' ? result() : ''"
                                (changed)="onChanged($event)"
                            ></inspecto-la-entity-columns>
                        } @else if (error(); as e) {
                            <inspecto-alert variant="error">{{ e }}</inspecto-alert>
                        } @else {
                            <inspecto-skeleton [lines]="3" height="4rem"></inspecto-skeleton>
                        }
                    </div>
                </mat-tab>
            </mat-tab-group>
        </div>

        <ng-template #card let-t let-primaryIn="primaryIn">
            <li class="bg-card flex flex-col gap-2 rounded-2xl p-5 shadow" data-testid="entity-type-card">
                <div class="flex items-start justify-between gap-2">
                    <h2 class="m-0 text-base font-semibold">
                        <a [routerLink]="[...base, t.id]" data-testid="entity-type-link">{{ t.label }}</a>
                    </h2>
                    <inspecto-status-badge
                        [value]="fresh(t).value"
                        [label]="fresh(t).label"
                        data-testid="freshness"
                    ></inspecto-status-badge>
                </div>
                @if (primaryIn?.length) {
                    <div>
                        <inspecto-chip variant="soft" tone="primary" data-testid="primary-chip"
                            >Primary in {{ primaryIn.join(', ') }}</inspecto-chip
                        >
                    </div>
                }
                @if (t.headline.assessed) {
                    <dl class="m-0 grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                        <dt class="text-secondary">Distinct</dt>
                        <dd class="m-0 tabular-nums" data-testid="distinct">
                            {{ count(t.headline.distinct) }}
                            @if (t.headline.approximate) {
                                <span class="text-secondary text-xs" data-testid="approx">approx.</span>
                            }
                        </dd>
                        <dt class="text-secondary">Datasets</dt>
                        <dd class="m-0 tabular-nums" data-testid="datasets">{{ datasetCount(t) }}</dd>
                        <dt class="text-secondary">Data as of</dt>
                        <dd class="m-0" data-testid="as-of">{{ day(t.headline.dataAsOf) || 'not known' }}</dd>
                    </dl>
                } @else {
                    <dl class="m-0 grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                        <dt class="text-secondary">Datasets</dt>
                        <dd class="m-0 tabular-nums" data-testid="datasets">{{ datasetCount(t) }}</dd>
                    </dl>
                    <p class="text-secondary m-0 text-sm" data-testid="not-assessed">
                        {{ notAssessed(t.headline) }}
                    </p>
                }
                @if (maskedHere(t)) {
                    <p class="text-secondary m-0 text-xs" data-testid="masked">Masked: shown as aliases.</p>
                }
            </li>
        </ng-template>
    `,
})
export class EntitiesComponent implements OnInit {
    private readonly svc = inject(EntityProfileService);
    private readonly components = inject(ComponentsService);
    private readonly settings = inject(LinkAnalysisSettingsService);
    private readonly lens = inject(LensService);
    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly destroyRef = inject(DestroyRef);
    protected readonly base = entitiesBase(this.route, 0);

    /** The Space's masking mode in force (`none` on Basic by default); unknown until the settings are read. */
    private readonly maskingMode = computed(() => this.settings.limits().maskingModeInForce);
    readonly items = signal<EntityTypeSummary[] | null>(null);
    readonly error = signal('');
    /** The Dataset registry entries (`GET /components/dataset`); `null` while read. A failure leaves the cards alone. */
    readonly datasets = signal<DatasetEntry[] | null>(null);
    readonly datasetsError = signal('');
    readonly split = computed(() => {
        const items = this.items();
        return items ? splitByPresence(items, this.datasets() ?? []) : null;
    });

    /** Both writes the page makes ask `canAuthorWorkbench` (columns, the registry entry, the settings document). */
    readonly canEditColumns = computed(() => this.lens.canAuthorWorkbench());
    readonly canAddType = computed(() => this.lens.canAuthorWorkbench());
    protected readonly newTypeLockedReason = NEW_TYPE_LOCKED_REASON;

    readonly tab = signal<(typeof TABS)[number]>(
        this.route.snapshot.queryParamMap.get('tab') === 'columns' ? 'columns' : 'types',
    );
    readonly tabIndex = computed(() => TABS.indexOf(this.tab()));
    readonly adding = signal(false);
    private readonly lastChange = signal<EntitiesChange | null>(null);
    /** The two reads in flight (replaced by the next {@link load}). */
    private reads = new Subscription();
    /** The last change, stated from the Entity Types as read AFTER it (the re-assessment). */
    readonly result = computed(() => changeResult(this.lastChange(), this.items() ?? []));
    readonly typeResult = computed(() => (this.lastChange()?.kind === 'type' ? this.result() : ''));

    protected readonly count = count;
    protected readonly day = day;
    protected readonly notAssessed = notAssessedText;

    ngOnInit(): void {
        this.load();
        this.destroyRef.onDestroy(() => this.reads.unsubscribe());
    }

    /** Read the Entity Types (with their bindings) and the Dataset registry; each failure is stated on its own. */
    load(change: EntitiesChange | null = null): void {
        // A newer read replaces an older one still in flight, so a slow earlier answer never lands last.
        this.reads.unsubscribe();
        this.reads = new Subscription();
        this.reads.add(
            this.svc.entityTypes().subscribe({
                next: (r) => {
                    this.error.set('');
                    this.items.set(r.items ?? []);
                    // The result is stated only from the Entity Types read AFTER the change (the re-assessment).
                    if (change) this.lastChange.set(change);
                },
                error: (e) => this.error.set(apiErrorMessage(e, 'The Entity Types could not be read.')),
            }),
        );
        this.reads.add(
            this.components.list('dataset').subscribe({
                next: (defs) => {
                    this.datasetsError.set('');
                    this.datasets.set(datasetEntries(defs ?? []));
                },
                error: (e) => {
                    this.datasets.set([]);
                    this.datasetsError.set(apiErrorMessage(e, 'The Datasets could not be read.'));
                },
            }),
        );
    }

    selectTab(i: number): void {
        const tab = TABS[i] ?? 'types';
        this.tab.set(tab);
        void this.router.navigate([], {
            relativeTo: this.route,
            queryParams: { tab: tab === 'columns' ? 'columns' : null },
            queryParamsHandling: 'merge',
            replaceUrl: true,
        });
    }

    openNewType(): void {
        this.adding.set(true);
    }

    typeAdded(id: string): void {
        this.adding.set(false);
        this.onChanged({ kind: 'type', entityType: id });
    }

    /** A write landed: read both again, so the cards, the presence split and the result line say what is true now. */
    onChanged(change: EntitiesChange): void {
        this.lastChange.set(null);
        this.load(change);
    }

    fresh(t: EntityTypeSummary) {
        return freshness(t.headline.dataAsOf, t.headline.assessed);
    }

    /**
     * "Masked: shown as aliases." only where it is true: the type's own setting under the Space's mode in force
     * (LA-ONBOARD-FIXES-1, F-6 - under mode `none` nothing is masked). Said nothing until the mode is known.
     */
    maskedHere(t: EntityTypeSummary): boolean {
        const mode = this.maskingMode();
        return !!mode && maskedUnder(mode, t.masked);
    }

    /** Distinct Datasets the type is bound in. */
    datasetCount(t: EntityTypeSummary): number {
        return new Set(t.bindings.map((b) => b.dataset)).size;
    }
}
