import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ColDef } from 'ag-grid-community';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';
import { apiErrorMessage, LensService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoPageHeaderComponent } from '@inspecto/core/components/page-header.component';
import { InspectoStatTileComponent } from '@inspecto/core/components/stat-tile.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { DataTableComponent } from '@inspecto/core/data-table';
import { EntityNormaliser, typedEntityKey } from '@inspecto/core/graph/entity-key';
import {
    EntityProfile,
    EntityProfileService,
    EntityTypeSummary,
} from '@inspecto/link-analysis/api/entity-profile.service';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { DomainProfileService } from '@inspecto/link-analysis/api/domain-profile.service';
import { profileForEntityType } from '@inspecto/link-analysis/graph/domain-profile';
import { investigateQueryParams } from '@inspecto/link-analysis/link-analysis/investigate-number';
import { NodeInsightComponent, NodeInsightContext } from '@inspecto/link-analysis/link-analysis/node-insight.component';
import { EntityBarListComponent, EntityDayCalendarComponent } from './entity-charts.component';
import {
    bars,
    calendar,
    count,
    day,
    dayStart,
    entitiesBase,
    freshness,
    linkAnalysisFrom,
    notAssessedText,
} from './entity-profile-view';

type Load = { profile: EntityProfile; type: EntityTypeSummary | null } | { error: string } | null;
/** The identity group's members when read, else the reason it was not. */
type Identity = { members: string[] | null; note: string } | null;

/**
 * The **Entity Profile** page (la-entity-profile-plan section 3.2): one Entity, by its key - an alias under masking,
 * displayed as the server sent it. Lists and groups reuse the node dialog's reads (Entity List membership, Case
 * history, Risk) and the identity-group read. *Investigate* seeds an Investigation the way the starter card does.
 */
@Component({
    selector: 'inspecto-la-entity',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatExpansionModule,
        RouterLink,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
        InspectoStatTileComponent,
        StatusBadgeComponent,
        DataTableComponent,
        EntityBarListComponent,
        EntityDayCalendarComponent,
        NodeInsightComponent,
    ],
    template: `
        <inspecto-page-header
            [title]="key()"
            [mono]="true"
            [subtitle]="subtitle()"
            [backLink]="typeLink()"
            [backLabel]="typeLabel()"
        >
            <button
                actions
                mat-flat-button
                color="primary"
                data-testid="investigate"
                [disabled]="!profile()"
                (click)="investigate()"
            >
                Investigate
            </button>
        </inspecto-page-header>
        <div class="flex flex-col gap-4 p-6">
            @if (error(); as e) {
                <inspecto-alert variant="error" data-testid="profile-error">{{ e }}</inspecto-alert>
            } @else if (profile(); as p) {
                @if (window(); as w) {
                    <p class="m-0 text-sm" data-testid="window">
                        Scoped to {{ w.from ?? 'the start' }} to {{ w.to ?? 'now' }}: rows, days and the span are inside
                        it; links and related are the whole history.
                        <a data-testid="window-clear" [routerLink]="entityLink()">Show the whole history</a>
                    </p>
                }
                @if (p.entity.masked) {
                    <p class="text-secondary m-0 text-xs" data-testid="masked">
                        This Entity Type is masked: the key is the exploration alias, never the raw value.
                    </p>
                }
                @if (!p.assessed) {
                    <inspecto-alert variant="warning" data-testid="not-assessed">{{ na(p) }}</inspecto-alert>
                }
                <mat-accordion multi class="flex flex-col gap-2">
                    <!-- Seen -->
                    <mat-expansion-panel [expanded]="true" data-section="seen">
                        <mat-expansion-panel-header><mat-panel-title>Seen</mat-panel-title></mat-expansion-panel-header>
                        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                            <inspecto-stat-tile label="First seen" [value]="dayStart(p.firstSeen)"></inspecto-stat-tile>
                            <inspecto-stat-tile label="Last seen" [value]="dayStart(p.lastSeen)"></inspecto-stat-tile>
                            <inspecto-stat-tile
                                label="Active days"
                                [value]="activeText()"
                                absentReason="Not assessed"
                            ></inspecto-stat-tile>
                            <div class="sm:col-span-2">
                                <h2 id="entity-rows-per-day" class="m-0 mb-1 text-sm font-semibold">Rows per day</h2>
                                <inspecto-la-day-calendar
                                    labelledBy="entity-rows-per-day"
                                    [weeks]="weeks()"
                                ></inspecto-la-day-calendar>
                            </div>
                        </div>
                    </mat-expansion-panel>

                    <!-- Where -->
                    <mat-expansion-panel data-section="where">
                        <mat-expansion-panel-header>
                            <mat-panel-title>Where</mat-panel-title>
                            <mat-panel-description>{{ p.where.length }}</mat-panel-description>
                        </mat-expansion-panel-header>
                        <inspecto-data-table
                            tier="mini"
                            [rows]="p.where"
                            [columns]="whereCols"
                            [noRowsTitle]="
                                window() ? 'No activity in this window' : 'This Entity appears in no bound Dataset'
                            "
                        ></inspecto-data-table>
                    </mat-expansion-panel>

                    <!-- Links -->
                    <mat-expansion-panel data-section="links">
                        <mat-expansion-panel-header
                            ><mat-panel-title>Links</mat-panel-title></mat-expansion-panel-header
                        >
                        @if (p.links.assessed) {
                            <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                                <inspecto-stat-tile
                                    label="Degree"
                                    [value]="count(p.links.degree)"
                                    [hint]="p.links.from === 'index' ? 'From the link index' : 'From a Dataset query'"
                                ></inspecto-stat-tile>
                                <inspecto-stat-tile
                                    label="Among its type"
                                    [value]="p.links.percentile != null ? 'p' + p.links.percentile : null"
                                    hint="Where this degree sits in the type's distribution"
                                    absentReason="Not assessed"
                                ></inspecto-stat-tile>
                                <div class="sm:col-span-2">
                                    <h2 class="m-0 mb-1 text-sm font-semibold">Link kinds</h2>
                                    <inspecto-la-bar-list
                                        label="Links by kind"
                                        [bars]="kindBars()"
                                    ></inspecto-la-bar-list>
                                </div>
                            </div>
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="links-not-assessed">{{ na(p.links) }}</p>
                        }
                    </mat-expansion-panel>

                    <!-- Related identifiers -->
                    <mat-expansion-panel data-section="related">
                        <mat-expansion-panel-header>
                            <mat-panel-title>Related identifiers</mat-panel-title>
                            <mat-panel-description>{{ p.related.length }}</mat-panel-description>
                        </mat-expansion-panel-header>
                        @if (p.related.length) {
                            <ul class="m-0 flex list-none flex-col gap-2 p-0 text-sm" data-testid="related">
                                @for (r of p.related; track r.type) {
                                    <li>
                                        <span class="font-medium">{{ r.type }}</span>
                                        <span class="text-secondary"> · {{ count(r.count) }} in the same rows</span>
                                        <span class="ml-2 inline-flex flex-wrap gap-2">
                                            @for (k of r.keys; track k) {
                                                <a class="font-mono" [routerLink]="[...base, r.type, k]">{{ k }}</a>
                                            }
                                        </span>
                                    </li>
                                }
                            </ul>
                        } @else {
                            <p class="text-secondary m-0 text-sm">
                                No other Entity Type shares a row with this Entity.
                            </p>
                        }
                    </mat-expansion-panel>

                    <!-- Lists and groups -->
                    <mat-expansion-panel data-section="lists">
                        <mat-expansion-panel-header
                            ><mat-panel-title>Lists and groups</mat-panel-title></mat-expansion-panel-header
                        >
                        <div class="text-sm" data-testid="identity">
                            <span class="text-secondary">Identity group:</span>
                            @if (identity(); as i) {
                                @if (i.members) {
                                    @if (i.members.length > 1) {
                                        <span class="ml-1"
                                            >{{ i.members.length }} identifiers: {{ i.members.join(', ') }}</span
                                        >
                                    } @else {
                                        <span class="ml-1">in no group (resolves to itself)</span>
                                    }
                                } @else {
                                    <span class="ml-1">{{ i.note }}</span>
                                }
                            } @else {
                                <span class="ml-1">checking…</span>
                            }
                        </div>
                        @if (insight(); as ctx) {
                            <inspecto-la-node-insight [ctx]="ctx"></inspecto-la-node-insight>
                        }
                    </mat-expansion-panel>

                    <!-- Freshness -->
                    <mat-expansion-panel data-section="freshness">
                        <mat-expansion-panel-header
                            ><mat-panel-title>Freshness</mat-panel-title></mat-expansion-panel-header
                        >
                        <p class="text-secondary m-0 mb-2 text-xs">Read "last seen" against the data, not the clock.</p>
                        <ul class="m-0 flex list-none flex-col gap-1 p-0 text-sm" data-testid="as-of">
                            @for (d of p.dataAsOf; track d.dataset) {
                                <li class="flex items-center gap-2">
                                    <span>{{ d.dataset }}: {{ day(d.dataAsOf) || 'as of not known' }}</span>
                                    <inspecto-status-badge
                                        [value]="fresh(d.dataAsOf).value"
                                        [label]="fresh(d.dataAsOf).label"
                                    ></inspecto-status-badge>
                                </li>
                            } @empty {
                                <li class="text-secondary">No Dataset reported a data-as-of.</li>
                            }
                        </ul>
                    </mat-expansion-panel>
                </mat-accordion>
            } @else {
                <p class="text-secondary m-0" role="status">Reading the profile…</p>
            }
        </div>
    `,
})
export class EntityComponent implements OnInit {
    private readonly svc = inject(EntityProfileService);
    /** Installs the Space's Industry packs: `profileForEntityType` reads them (LA-EDITIONS-1). */
    private readonly domainProfiles = inject(DomainProfileService);
    private readonly inv = inject(InvService);
    private readonly lens = inject(LensService);
    private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);
    private readonly destroyRef = inject(DestroyRef);
    protected readonly base = entitiesBase(this.route, 2);

    private readonly load = signal<Load>(null);
    readonly identity = signal<Identity>(null);
    readonly typeId = signal('');
    readonly keyParam = signal('');
    /** The deep link's window (`?from=&to=`), or null for the whole history; the server scopes the profile to it. */
    readonly window = signal<{ from?: string; to?: string } | null>(null);
    readonly entityLink = computed(() => [...this.base, this.typeId(), this.keyParam()]);

    readonly profile = computed(() => {
        const l = this.load();
        return l && 'profile' in l ? l.profile : null;
    });
    readonly error = computed(() => {
        const l = this.load();
        return l && 'error' in l ? l.error : '';
    });
    private readonly type = computed(() => {
        const l = this.load();
        return l && 'profile' in l ? l.type : null;
    });
    /** The key as the server returned it (an alias under masking); the route's until it answers. */
    readonly key = computed(() => this.profile()?.entity.key || this.keyParam() || 'Entity');
    readonly typeLabel = computed(() => this.type()?.label || this.typeId() || 'Entity Type');
    readonly typeLink = computed(() => [...this.base, this.typeId()]);
    readonly subtitle = computed(() => `Entity Profile: one ${this.typeLabel()} across the Space's Datasets.`);

    protected readonly count = count;
    protected readonly day = day;
    protected readonly dayStart = dayStart;
    protected na(s: { assessed: boolean; reason: string | null }): string {
        return notAssessedText(s);
    }
    fresh(asOf: string | null) {
        return freshness(asOf, true);
    }

    readonly activeText = computed(() => {
        const p = this.profile();
        if (!p || p.activeDays == null) return null;
        return p.coveredDays != null ? `${count(p.activeDays)} of ${count(p.coveredDays)}` : count(p.activeDays);
    });
    readonly weeks = computed(() => calendar(this.profile()?.perDay ?? []));
    readonly kindBars = computed(() =>
        bars((this.profile()?.links.kinds ?? []).map((k) => ({ label: k.kind, rows: k.count }))),
    );

    /** The node dialog's Risk / Enrichment + Entity Lists / Case history / Actions, for this Entity. */
    readonly insight = computed((): NodeInsightContext | null => {
        const p = this.profile();
        if (!p) return null;
        return {
            nodeId: p.entity.key,
            label: p.entity.key,
            key: p.entity.key,
            profileId: profileForEntityType(p.entity.type)?.id ?? 'generic',
            entityType: p.entity.type,
            investigationId: null,
            graphKey: null,
            serverId: null,
            serverNodeIds: [],
            originLabel: (id) => id,
            canRunGraph: false,
            canManageLists: this.lens.canManageIncidents(),
            canHide: false,
        };
    });

    readonly whereCols: ColDef[] = [
        { field: 'dataset', headerName: 'Dataset' },
        { field: 'column', headerName: 'Column' },
        { headerName: 'Rows', valueGetter: (p) => count((p.data as { rows?: number } | undefined)?.rows) },
    ];

    ngOnInit(): void {
        const types$ = this.svc.entityTypes().pipe(
            map((r) => r.items ?? []),
            catchError(() => of<EntityTypeSummary[]>([])),
        );
        combineLatest([this.route.paramMap, this.route.queryParamMap, types$])
            .pipe(
                switchMap(([pm, qp, types]) => {
                    const type = pm.get('type') ?? '';
                    const key = pm.get('key') ?? '';
                    const from = qp.get('from') ?? undefined;
                    const to = qp.get('to') ?? undefined;
                    const window = from || to ? { ...(from ? { from } : {}), ...(to ? { to } : {}) } : null;
                    this.typeId.set(type);
                    this.keyParam.set(key);
                    this.window.set(window);
                    const summary = types.find((t) => t.id === type) ?? null;
                    return (
                        window ? this.svc.entityProfile(type, key, window) : this.svc.entityProfile(type, key)
                    ).pipe(
                        map((profile): Load => ({ profile, type: summary })),
                        catchError((e) =>
                            of<Load>({ error: apiErrorMessage(e, 'The Entity Profile could not be read.') }),
                        ),
                        startWith<Load>(null),
                    );
                }),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe((l) => {
                this.load.set(l);
                if (l && 'profile' in l) this.readIdentity(l.profile, l.type);
            });
    }

    /** The identity group, keyed by the normalised typed key - so only for an unmasked type whose normaliser is known. */
    private readIdentity(p: EntityProfile, type: EntityTypeSummary | null): void {
        this.identity.set(null);
        if (p.entity.masked) {
            this.identity.set({
                members: null,
                note: 'not assessed: identity groups are keyed by the raw value, and this type is masked',
            });
            return;
        }
        if (!type) {
            this.identity.set({ members: null, note: 'not assessed: the Entity Type is not in force' });
            return;
        }
        if (!this.lens.canManageIncidents()) {
            this.identity.set({
                members: null,
                note: 'not assessed: reading identity groups needs the Manage incidents capability',
            });
            return;
        }
        this.inv.identityGroup(typedEntityKey(type.id, p.entity.key, type.normaliser as EntityNormaliser)).subscribe({
            next: (r) => this.identity.set({ members: r.group.members, note: '' }),
            error: (e) =>
                this.identity.set({
                    members: null,
                    note: `not assessed: ${apiErrorMessage(e, 'the identity group could not be read')}`,
                }),
        });
    }

    /** Seed an Investigation from this Entity: the starter card's deep link (`?seed=&entityType=[&dataset=]`). */
    investigate(): void {
        const p = this.profile();
        if (!p) return;
        void this.router.navigate(linkAnalysisFrom(this.base), {
            queryParams: investigateQueryParams(p.entity.key, p.entity.type, p.where[0]?.dataset),
        });
    }
}
