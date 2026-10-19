import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatExpansionModule } from '@angular/material/expansion';
import { ActivatedRoute } from '@angular/router';
import { ColDef } from 'ag-grid-community';
import { catchError, map, of, startWith, switchMap } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { InspectoPageHeaderComponent } from '@inspecto/core/components/page-header.component';
import { InspectoStatTileComponent } from '@inspecto/core/components/stat-tile.component';
import { DataTableComponent } from '@inspecto/core/data-table';
import {
    EntityProfileService,
    EntityTypeProfile,
    ProfileBinding,
    ProfileLineage,
    ProfileRelationship,
    ProfileSentinel,
} from '@inspecto/link-analysis/api/entity-profile.service';
import { EntityBarListComponent, EntityDayCalendarComponent } from './entity-charts.component';
import { EntityLookupComponent } from './entity-lookup.component';
import {
    NOT_RETURNED,
    bars,
    calendar,
    count,
    day,
    entitiesBase,
    notAssessedText,
    notHere,
    percent,
} from './entity-profile-view';

type Load = { profile: EntityTypeProfile } | { error: string } | null;

/** A per-row number that was not measured reads "not assessed", never 0. */
function measured<T extends { assessed: boolean }>(fmt: (r: T) => string): ColDef['valueGetter'] {
    return (p) => (p.data ? ((p.data as T).assessed ? fmt(p.data as T) : 'not assessed') : '');
}

/**
 * The **Entity Type Profile** page (la-entity-profile-plan section 3.1): aggregates only - counts, shares, shapes,
 * quantiles - never a raw identifier. Each section is a collapsible group (the first open) of stat tiles and small
 * tables; a section the server could not read says "not assessed" with its reason, and an approximate count says so.
 */
@Component({
    selector: 'inspecto-la-entity-type',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatExpansionModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoPageHeaderComponent,
        InspectoStatTileComponent,
        DataTableComponent,
        EntityBarListComponent,
        EntityDayCalendarComponent,
        EntityLookupComponent,
    ],
    template: `
        <inspecto-page-header
            [title]="title()"
            subtitle="Entity Type Profile: what the Space's Datasets say about this type, in aggregate."
            [backLink]="base"
            backLabel="Entities"
        ></inspecto-page-header>
        <div class="flex flex-col gap-4 p-6">
            @if (typeId()) {
                <inspecto-la-entity-lookup [type]="typeId()" [base]="base"></inspecto-la-entity-lookup>
            }
            @if (error(); as e) {
                <inspecto-alert variant="error" data-testid="profile-error">{{ e }}</inspecto-alert>
            } @else if (profile(); as p) {
                @if (p.truncated) {
                    <inspecto-alert variant="warning" data-testid="truncated">{{ truncatedText() }}</inspecto-alert>
                }
                <p class="text-secondary m-0 text-xs" data-testid="computed-at">
                    Computed {{ day(p.computedAt) || 'at an unknown time' }}{{ p.cached ? ' (cached)' : '' }}.
                </p>
                <mat-accordion multi class="flex flex-col gap-2">
                    <!-- Identity -->
                    <mat-expansion-panel [expanded]="true" data-section="identity">
                        <mat-expansion-panel-header
                            ><mat-panel-title>Identity</mat-panel-title></mat-expansion-panel-header
                        >
                        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                            <inspecto-stat-tile label="Label" [value]="p.entityType.label"></inspecto-stat-tile>
                            <inspecto-stat-tile label="Id" [value]="p.entityType.id"></inspecto-stat-tile>
                            <inspecto-stat-tile
                                label="Normaliser"
                                [value]="p.entityType.normaliser"
                            ></inspecto-stat-tile>
                            <inspecto-stat-tile
                                label="Masked"
                                [value]="p.entityType.masked ? 'Yes' : 'No'"
                                [hint]="p.masking ? 'Masking mode ' + p.masking.mode : ''"
                            ></inspecto-stat-tile>
                            <div class="sm:col-span-2">
                                <span class="text-secondary text-xs font-medium">Classifications it claims</span>
                                <div class="mt-1 flex flex-wrap gap-1">
                                    @for (c of p.entityType.classifications; track c) {
                                        <inspecto-chip>{{ c }}</inspecto-chip>
                                    } @empty {
                                        <span class="text-secondary text-sm">None.</span>
                                    }
                                </div>
                            </div>
                        </div>
                    </mat-expansion-panel>

                    <!-- Where it appears -->
                    <mat-expansion-panel data-section="bindings">
                        <mat-expansion-panel-header>
                            <mat-panel-title>Where it appears</mat-panel-title>
                            <mat-panel-description>{{ p.bindings?.length ?? 0 }}</mat-panel-description>
                        </mat-expansion-panel-header>
                        @if (p.bindings; as b) {
                            <inspecto-data-table
                                tier="mini"
                                [rows]="b"
                                [columns]="bindingCols"
                                noRowsTitle="No Dataset column is bound to this type"
                            ></inspecto-data-table>
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="not-assessed">{{ na(undefined) }}</p>
                        }
                    </mat-expansion-panel>

                    <!-- Lineage -->
                    <mat-expansion-panel data-section="lineage">
                        <mat-expansion-panel-header
                            ><mat-panel-title>Lineage</mat-panel-title></mat-expansion-panel-header
                        >
                        @if (p.lineage; as l) {
                            <inspecto-data-table
                                tier="mini"
                                [rows]="l"
                                [columns]="lineageCols"
                                noRowsTitle="No lineage was read"
                            ></inspecto-data-table>
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="not-assessed">{{ na(undefined) }}</p>
                        }
                    </mat-expansion-panel>

                    <!-- Volume and quality -->
                    <mat-expansion-panel data-section="volume">
                        <mat-expansion-panel-header
                            ><mat-panel-title>Volume and quality</mat-panel-title></mat-expansion-panel-header
                        >
                        @if (p.volume?.assessed) {
                            <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                                <inspecto-stat-tile label="Distinct Entities" [value]="count(p.volume!.distinct)">
                                    @if (p.volume!.approximate) {
                                        <span class="text-secondary text-xs" data-testid="approx">approx.</span>
                                    }
                                </inspecto-stat-tile>
                                <inspecto-stat-tile label="Rows" [value]="count(p.volume!.rows)"></inspecto-stat-tile>
                                <div class="sm:col-span-2">
                                    <h2 class="m-0 mb-1 text-sm font-semibold">Sentinel values</h2>
                                    <inspecto-data-table
                                        tier="mini"
                                        [rows]="p.volume!.sentinels"
                                        [columns]="sentinelCols"
                                        noRowsTitle="No sentinel value found"
                                    ></inspecto-data-table>
                                </div>
                            </div>
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="not-assessed">{{ na(p.volume) }}</p>
                        }
                    </mat-expansion-panel>

                    <!-- Value shapes -->
                    <mat-expansion-panel data-section="shapes">
                        <mat-expansion-panel-header
                            ><mat-panel-title>Value shapes</mat-panel-title></mat-expansion-panel-header
                        >
                        @if (p.shapes?.assessed) {
                            @if (p.shapes!.sampled) {
                                <p class="text-secondary m-0 mb-2 text-xs" data-testid="sampled">
                                    Read from a sample of the rows.
                                </p>
                            }
                            <div class="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
                                <div>
                                    <h2 class="m-0 mb-1 text-sm font-semibold">Length</h2>
                                    <inspecto-la-bar-list
                                        label="Rows by value length"
                                        [bars]="lengthBars()"
                                    ></inspecto-la-bar-list>
                                </div>
                                <div>
                                    <h2 class="m-0 mb-1 text-sm font-semibold">Leading characters</h2>
                                    @if (p.shapes!.prefixLength > 0) {
                                        <inspecto-la-bar-list
                                            [label]="'Rows by the first ' + p.shapes!.prefixLength + ' characters'"
                                            [bars]="prefixBars()"
                                        ></inspecto-la-bar-list>
                                    } @else {
                                        <p class="text-secondary m-0 text-sm" data-testid="no-prefix">
                                            The normaliser declares no safe prefix: no prefix histogram.
                                        </p>
                                    }
                                </div>
                                <div>
                                    <h2 class="m-0 mb-1 text-sm font-semibold">Characters</h2>
                                    <inspecto-la-bar-list
                                        label="Rows by character class"
                                        [bars]="charBars()"
                                    ></inspecto-la-bar-list>
                                </div>
                            </div>
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="not-assessed">{{ na(p.shapes) }}</p>
                        }
                    </mat-expansion-panel>

                    <!-- Time -->
                    <mat-expansion-panel data-section="time">
                        <mat-expansion-panel-header><mat-panel-title>Time</mat-panel-title></mat-expansion-panel-header>
                        @if (p.time?.assessed) {
                            <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                                <inspecto-stat-tile
                                    label="Earliest event"
                                    [value]="day(p.time!.earliest)"
                                ></inspecto-stat-tile>
                                <inspecto-stat-tile
                                    label="Latest event"
                                    [value]="day(p.time!.latest)"
                                ></inspecto-stat-tile>
                                <inspecto-stat-tile
                                    label="Days read"
                                    [value]="count(p.time!.perDay.length)"
                                ></inspecto-stat-tile>
                                <inspecto-stat-tile
                                    label="Missing days"
                                    [value]="count(p.time!.missingDays.length)"
                                ></inspecto-stat-tile>
                                <div class="sm:col-span-2">
                                    <h2 id="type-rows-per-day" class="m-0 mb-1 text-sm font-semibold">
                                        Rows and distinct Entities per day
                                    </h2>
                                    <inspecto-la-day-calendar
                                        labelledBy="type-rows-per-day"
                                        [weeks]="weeks()"
                                    ></inspecto-la-day-calendar>
                                </div>
                                <div class="sm:col-span-2">
                                    <h2 class="m-0 mb-1 text-sm font-semibold">Data as of</h2>
                                    <ul class="m-0 list-none p-0 text-sm" data-testid="as-of">
                                        @for (d of p.time!.dataAsOf; track d.dataset) {
                                            <li>{{ d.dataset }}: {{ day(d.dataAsOf) || 'not known' }}</li>
                                        }
                                    </ul>
                                </div>
                            </div>
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="not-assessed">{{ na(p.time) }}</p>
                        }
                    </mat-expansion-panel>

                    <!-- Links -->
                    <mat-expansion-panel data-section="links">
                        <mat-expansion-panel-header
                            ><mat-panel-title>Links</mat-panel-title></mat-expansion-panel-header
                        >
                        @if (p.links?.assessed) {
                            <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                                <inspecto-stat-tile
                                    label="Degree p50 / p90 / p99"
                                    [value]="degreeText()"
                                    [hint]="p.links!.from === 'index' ? 'From the link index' : 'From a Dataset query'"
                                ></inspecto-stat-tile>
                                <inspecto-stat-tile
                                    label="Highest degree"
                                    [value]="count(p.links!.degree?.max)"
                                ></inspecto-stat-tile>
                                <inspecto-stat-tile
                                    label="Hubs"
                                    [value]="count(p.links!.hubs)"
                                    [hint]="
                                        p.links!.hubThreshold != null
                                            ? 'At or above ' + count(p.links!.hubThreshold) + ' links'
                                            : ''
                                    "
                                ></inspecto-stat-tile>
                                <inspecto-stat-tile
                                    label="Entities with one link"
                                    [value]="count(p.links!.singleLink)"
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
                            <p class="text-secondary m-0 text-sm" data-testid="not-assessed">{{ na(p.links) }}</p>
                        }
                    </mat-expansion-panel>

                    <!-- Relationships -->
                    <mat-expansion-panel data-section="relationships">
                        <mat-expansion-panel-header
                            ><mat-panel-title>Relationships</mat-panel-title></mat-expansion-panel-header
                        >
                        @if (p.relationships; as r) {
                            <inspecto-data-table
                                tier="mini"
                                [rows]="r"
                                [columns]="relationshipCols"
                                noRowsTitle="No other Entity Type shares a row with this one"
                            ></inspecto-data-table>
                        } @else {
                            <p class="text-secondary m-0 text-sm" data-testid="not-assessed">{{ na(undefined) }}</p>
                        }
                    </mat-expansion-panel>

                    <!-- What is not here -->
                    <mat-expansion-panel data-section="not-here">
                        <mat-expansion-panel-header>
                            <mat-panel-title>What is not here</mat-panel-title>
                            <mat-panel-description>{{ gaps().length }}</mat-panel-description>
                        </mat-expansion-panel-header>
                        @if (gaps().length) {
                            <ul class="m-0 list-disc pl-5 text-sm" data-testid="gaps">
                                @for (g of gaps(); track g) {
                                    <li>{{ g }}</li>
                                }
                            </ul>
                        } @else {
                            <p class="text-secondary m-0 text-sm">Every section was assessed.</p>
                        }
                    </mat-expansion-panel>
                </mat-accordion>
            } @else {
                <p class="text-secondary m-0" role="status">Reading the profile…</p>
            }
        </div>
    `,
})
export class EntityTypeComponent implements OnInit {
    private readonly svc = inject(EntityProfileService);
    private readonly route = inject(ActivatedRoute);
    private readonly destroyRef = inject(DestroyRef);
    protected readonly base = entitiesBase(this.route, 1);

    private readonly load = signal<Load>(null);
    readonly typeId = signal('');
    readonly profile = computed(() => {
        const l = this.load();
        return l && 'profile' in l ? l.profile : null;
    });
    readonly error = computed(() => {
        const l = this.load();
        return l && 'error' in l ? l.error : '';
    });
    readonly title = computed(() => this.profile()?.entityType.label || this.typeId() || 'Entity Type');

    protected readonly count = count;
    protected readonly day = day;
    protected na(s: { assessed: boolean; reason: string | null } | undefined): string {
        return notAssessedText(s ?? NOT_RETURNED);
    }

    readonly lengthBars = computed(() =>
        bars((this.profile()?.shapes?.length ?? []).map((l) => ({ label: `${l.length} chars`, rows: l.rows }))),
    );
    readonly prefixBars = computed(() =>
        bars((this.profile()?.shapes?.prefix ?? []).map((l) => ({ label: l.prefix, rows: l.rows }))),
    );
    readonly charBars = computed(() => {
        const c = this.profile()?.shapes?.charClass;
        return c
            ? bars([
                  { label: 'Digits', rows: c.digits },
                  { label: 'Letters', rows: c.letters },
                  { label: 'Mixed', rows: c.mixed },
              ])
            : [];
    });
    readonly kindBars = computed(() =>
        bars((this.profile()?.links?.kinds ?? []).map((k) => ({ label: k.kind, rows: k.edges }))),
    );
    /** Names each section the server marked cut by a limit; never a section it did not mark. */
    readonly truncatedText = computed(() => {
        const p = this.profile();
        if (!p) return '';
        const parts: string[] = [];
        if (p.volume?.truncated) parts.push('Volume');
        if (p.shapes?.truncated) parts.push('Shapes');
        if (p.time?.truncated) parts.push(`Time (newest ${count(p.time.perDay.length)} days read)`);
        if (p.links?.truncated) parts.push('Links');
        return parts.length
            ? `A limit was reached: ${parts.join(', ')} ${parts.length === 1 ? 'is' : 'are'} partial.`
            : 'A limit was reached: this profile may not have read every Dataset bound to this type.';
    });
    readonly weeks = computed(() => {
        const t = this.profile()?.time;
        return t ? calendar(t.perDay, t.missingDays) : [];
    });
    readonly degreeText = computed(() => {
        const d = this.profile()?.links?.degree;
        return d ? `${count(d.p50)} / ${count(d.p90)} / ${count(d.p99)}` : null;
    });
    readonly gaps = computed(() => {
        const p = this.profile();
        return p ? notHere(p as unknown as Parameters<typeof notHere>[0]) : [];
    });

    readonly bindingCols: ColDef[] = [
        { field: 'dataset', headerName: 'Dataset' },
        { field: 'column', headerName: 'Column' },
        { field: 'role', headerName: 'Role' },
        { headerName: 'Rows', valueGetter: measured<ProfileBinding>((r) => count(r.rows)) },
        { headerName: 'Fill rate', valueGetter: measured<ProfileBinding>((r) => percent(r.fillRate)) },
        {
            headerName: 'Distinct',
            valueGetter: measured<ProfileBinding>((r) => count(r.distinct) + (r.approximate ? ' approx.' : '')),
        },
        { headerName: 'Fail normaliser', valueGetter: measured<ProfileBinding>((r) => count(r.failNormaliser)) },
        { headerName: 'Time column', valueGetter: (p) => (p.data as ProfileBinding | undefined)?.timeColumn ?? 'none' },
        {
            headerName: 'Note',
            valueGetter: (p) => ((p.data as ProfileBinding)?.assessed ? '' : notAssessedText(p.data)),
        },
    ];

    readonly lineageCols: ColDef[] = [
        { field: 'dataset', headerName: 'Dataset' },
        { headerName: 'Pipelines', valueGetter: measured<ProfileLineage>((r) => r.pipelines.join(', ') || 'none') },
        { headerName: 'Collectors', valueGetter: measured<ProfileLineage>((r) => r.collectors.join(', ') || 'none') },
        { headerName: 'Files', valueGetter: measured<ProfileLineage>((r) => count(r.files)) },
        { headerName: 'Rows', valueGetter: measured<ProfileLineage>((r) => count(r.rows)) },
        {
            headerName: 'Latest ingest',
            valueGetter: measured<ProfileLineage>((r) => day(r.latestIngestedAt) || 'not known'),
        },
        { headerName: 'Waiting for the index', valueGetter: measured<ProfileLineage>((r) => count(r.waitingForIndex)) },
        { headerName: 'Problem Files', valueGetter: measured<ProfileLineage>((r) => count(r.problemFiles)) },
        {
            headerName: 'Note',
            valueGetter: (p) => ((p.data as ProfileLineage)?.assessed ? '' : notAssessedText(p.data)),
        },
    ];

    readonly sentinelCols: ColDef[] = [
        { field: 'dataset', headerName: 'Dataset' },
        { field: 'column', headerName: 'Column' },
        {
            headerName: 'Value',
            valueGetter: (p) => {
                const s = p.data as ProfileSentinel | undefined;
                if (!s) return '';
                // A dominant value is never shown (aggregates only, D-EP3); a built-in sentinel is not an identifier.
                return s.kind === 'dominant'
                    ? 'one dominant value (not shown)'
                    : s.value === ''
                      ? '(blank)'
                      : (s.value ?? 'NULL');
            },
        },
        { headerName: 'Rows', valueGetter: (p) => count((p.data as ProfileSentinel | undefined)?.rows) },
        { headerName: 'Share', valueGetter: (p) => percent((p.data as ProfileSentinel | undefined)?.share) },
    ];

    readonly relationshipCols: ColDef[] = [
        { field: 'otherType', headerName: 'Other Entity Type' },
        { field: 'dataset', headerName: 'Dataset' },
        { headerName: 'One to one', valueGetter: measured<ProfileRelationship>((r) => percent(r.oneToOneShare)) },
        { headerName: 'Most per Entity', valueGetter: measured<ProfileRelationship>((r) => count(r.maxPerEntity)) },
        {
            headerName: 'Entities at or above the threshold',
            valueGetter: measured<ProfileRelationship>(
                (r) => `${count(r.entitiesAtOrAboveThreshold)} (≥ ${count(r.threshold)})`,
            ),
        },
        {
            headerName: 'Note',
            valueGetter: (p) => ((p.data as ProfileRelationship)?.assessed ? '' : notAssessedText(p.data)),
        },
    ];

    ngOnInit(): void {
        this.route.paramMap
            .pipe(
                map((pm) => pm.get('type') ?? ''),
                switchMap((id) => {
                    this.typeId.set(id);
                    return this.svc.entityTypeProfile(id).pipe(
                        map((profile): Load => ({ profile })),
                        catchError((e) =>
                            of<Load>({ error: apiErrorMessage(e, 'The Entity Type Profile could not be read.') }),
                        ),
                        startWith<Load>(null),
                    );
                }),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe((l) => this.load.set(l));
    }
}
