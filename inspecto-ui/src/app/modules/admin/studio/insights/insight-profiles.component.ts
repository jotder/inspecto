import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { SessionService } from 'app/inspecto/auth/session.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InsightFeedComponent } from './insight-feed.component';
import {
    InsightProfileFormData,
    InsightProfileFormDialog,
    InsightProfileFormResult,
} from './insight-profile-form.dialog';

/** What the list shows of one Profile. */
interface ProfileRow {
    id: string;
    dataset: string;
    measure: string;
    grain: string;
    dimensions: string[];
    types: string[];
    description: string;
}

function row(d: ComponentDef): ProfileRow {
    const c = d.content ?? {};
    const m = (c['measure'] ?? {}) as Record<string, unknown>;
    const arr = (v: unknown) => (Array.isArray(v) ? v.map(String) : []);
    return {
        id: d.name,
        dataset: String(c['dataset'] ?? ''),
        measure: m['agg'] === 'count' ? 'count of rows' : `sum of ${m['field'] ?? '?'}`,
        grain: String(c['grain'] ?? ''),
        dimensions: arr(c['dimensions']),
        types: arr(c['types']),
        description: String(c['description'] ?? ''),
    };
}

/**
 * Studio ▸ Insight Profiles (BI-INSIGHTS-1 P3): list + detail of the `insight-profile` components, the editor dialog
 * (with the replay preview) and the Insight feed of the selected Profile. Behind the `insights` feature, like Explain.
 */
@Component({
    selector: 'app-insight-profiles',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatIconModule,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        InsightFeedComponent,
    ],
    template: `
        <div class="flex min-w-0 flex-auto flex-col">
            <inspecto-page-header
                title="Insight Profiles"
                subtitle="What to scan for Insights: a Dataset, a Measure, the dimensions to slice by and how sure to be."
                [terms]="['Insight Profile', 'Insight', 'Insight Type', 'Dataset', 'Measure']"
            >
                @if (enabled() && lens.canAuthorWorkbench()) {
                    <button actions mat-flat-button color="primary" type="button" (click)="create()">
                        New Insight Profile
                    </button>
                }
                <button actions mat-icon-button type="button" aria-label="Refresh Insight Profiles" (click)="load()">
                    <mat-icon svgIcon="heroicons_outline:arrow-path"></mat-icon>
                </button>
            </inspecto-page-header>

            @if (!enabled()) {
                <div class="p-6 sm:p-10">
                    <inspecto-alert variant="info" title="Business Insights is not installed">
                        Insight Profiles need the Business Insights add-on, which this deployment does not run.
                    </inspecto-alert>
                </div>
            } @else {
                <div class="flex flex-auto flex-col gap-6 p-6 sm:flex-row sm:p-10">
                    <nav class="sm:w-72" aria-label="Insight Profiles">
                        @if (loading()) {
                            <inspecto-skeleton [lines]="4"></inspecto-skeleton>
                        } @else if (error(); as e) {
                            <p class="text-warn text-sm" role="alert">{{ e }}</p>
                            <button mat-stroked-button type="button" class="mt-2" (click)="load()">Retry</button>
                        } @else if (profiles().length === 0) {
                            <inspecto-empty-state
                                icon="heroicons_outline:light-bulb"
                                title="No Insight Profiles yet"
                                message="An Insight Profile tells the insight.scan Job what to look at."
                            ></inspecto-empty-state>
                        } @else {
                            <ul class="flex flex-col gap-1">
                                @for (p of profiles(); track p.id) {
                                    <li>
                                        <button
                                            type="button"
                                            class="hover:bg-hover w-full rounded-lg px-3 py-2 text-left"
                                            [class.bg-hover]="p.id === selectedId()"
                                            [attr.aria-current]="p.id === selectedId() ? 'true' : null"
                                            (click)="selectedId.set(p.id); missingProfile.set(null)"
                                        >
                                            <span class="block text-sm font-semibold">{{ p.id }}</span>
                                            <span class="text-secondary block text-xs"
                                                >{{ p.dataset }} · {{ p.grain }}</span
                                            >
                                        </button>
                                    </li>
                                }
                            </ul>
                        }
                    </nav>

                    <section class="min-w-0 flex-auto">
                        @if (missingProfile(); as m) {
                            <p class="text-secondary mb-3 text-sm" role="status" data-testid="missing-profile">
                                The linked Insight Profile {{ m }} no longer exists.
                            </p>
                        }
                        @if (selected(); as p) {
                            <div class="flex flex-wrap items-center gap-2">
                                <h2 class="text-lg font-semibold">{{ p.id }}</h2>
                                @if (lens.canAuthorWorkbench()) {
                                    <button mat-stroked-button type="button" (click)="edit(p.id)">Edit</button>
                                    <button mat-stroked-button type="button" (click)="remove(p.id)">Delete</button>
                                }
                            </div>
                            @if (p.description) {
                                <p class="text-secondary mt-1 text-sm">{{ p.description }}</p>
                            }
                            <dl class="mt-4 grid grid-cols-2 gap-x-6 gap-y-1 text-sm sm:grid-cols-4">
                                <dt class="text-secondary">Dataset</dt>
                                <dd>{{ p.dataset }}</dd>
                                <dt class="text-secondary">Measure</dt>
                                <dd>{{ p.measure }}</dd>
                                <dt class="text-secondary">Grain</dt>
                                <dd>{{ p.grain }}</dd>
                                <dt class="text-secondary">Dimensions</dt>
                                <dd>{{ p.dimensions.join(', ') || 'whole Measure only' }}</dd>
                                <dt class="text-secondary">Insight Types</dt>
                                <dd>{{ p.types.join(', ') || 'all' }}</dd>
                                <dt class="text-secondary">Insights Dataset</dt>
                                <dd class="font-mono">insights_{{ p.id }}</dd>
                            </dl>
                            <h3 class="mt-6 mb-2 text-sm font-semibold">Insights</h3>
                            <app-insight-feed
                                [profile]="p.id"
                                [highlight]="p.id === linkedProfile ? linkedInsight : null"
                            />
                        } @else if (profiles().length > 0) {
                            <p class="text-secondary text-sm">Select an Insight Profile to see its Insights.</p>
                        }
                    </section>
                </div>
            }
        </div>
    `,
})
export class InsightProfilesComponent implements OnInit {
    readonly lens = inject(LensService);
    private session = inject(SessionService);
    private api = inject(ComponentsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    private route = inject(ActivatedRoute);

    readonly enabled = computed(() => this.session.features()['insights'] === true);
    readonly loading = signal(true);
    readonly error = signal<string | null>(null);
    readonly profiles = signal<ProfileRow[]>([]);
    readonly selectedId = signal<string | null>(null);
    /** From a link back (`?profile=&insight=`, BI-INSIGHTS-1 I-17). */
    linkedProfile: string | null = null;
    linkedInsight: string | null = null;
    readonly missingProfile = signal<string | null>(null);
    readonly selected = computed(() => this.profiles().find((p) => p.id === this.selectedId()) ?? null);

    ngOnInit(): void {
        // A link back from a Case or an Action Request names the Profile (`?profile=`, BI-INSIGHTS-1 I-17).
        const q = this.route.snapshot.queryParamMap;
        this.linkedProfile = q.get('profile');
        this.linkedInsight = q.get('insight');
        if (this.linkedProfile) this.selectedId.set(this.linkedProfile);
        this.load();
    }

    load(): void {
        if (!this.enabled()) return;
        this.loading.set(true);
        this.error.set(null);
        this.api.list('insight-profile').subscribe({
            next: (defs) => {
                this.profiles.set(defs.map(row));
                // A linked Profile that no longer exists: say so, and show the first instead of nothing.
                const id = this.selectedId();
                if (id && !defs.some((d) => d.name === id)) {
                    if (id === this.linkedProfile) this.missingProfile.set(id);
                    this.selectedId.set(null);
                }
                if (!this.selectedId() && defs.length) this.selectedId.set(defs[0].name);
                this.loading.set(false);
            },
            error: (e) => {
                this.error.set(apiErrorMessage(e, 'Could not load Insight Profiles.'));
                this.loading.set(false);
            },
        });
    }

    create(): void {
        this.open({});
    }

    /** A fresh read first, so `If-Match` carries the hash of what is stored now. */
    edit(id: string): void {
        this.api.get('insight-profile', id).subscribe({
            next: (existing) => this.open({ existing }),
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not open the Insight Profile.')),
        });
    }

    async remove(id: string): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Delete Insight Profile "${id}"? Its Insights Dataset stays; History can restore it.`,
            { title: 'Delete Insight Profile?', confirmText: 'Delete' },
        );
        if (!ok) return;
        this.api.remove('insight-profile', id).subscribe({
            next: () => {
                this.toastr.success(`Insight Profile "${id}" deleted`);
                this.selectedId.set(null);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not delete the Insight Profile.')),
        });
    }

    private open(data: InsightProfileFormData): void {
        this.dialog
            .open(InsightProfileFormDialog, { data, width: '880px', maxHeight: '90vh' })
            .afterClosed()
            .subscribe((r?: InsightProfileFormResult) => {
                if (r?.saved) {
                    this.toastr.success(`Insight Profile "${r.saved.name}" saved`);
                    this.selectedId.set(r.saved.name);
                    this.load();
                }
            });
    }
}
