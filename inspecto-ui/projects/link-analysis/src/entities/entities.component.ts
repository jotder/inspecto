import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { InspectoPageHeaderComponent } from '@inspecto/core/components/page-header.component';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { EntityProfileService, EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import { count, day, entitiesBase, freshness, notAssessedText } from './entity-profile-view';

/**
 * **Entities**: one card per Entity Type in force (`GET /inv/entity-types`, the cached headline - no profile query on
 * this read). A headline nobody computed yet says "not assessed" with its reason, never a zero.
 */
@Component({
    selector: 'inspecto-la-entities',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        RouterLink,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoPageHeaderComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    template: `
        <inspecto-page-header
            title="Entities"
            subtitle="What the data says about each Entity Type: how many, where they appear, how fresh."
        ></inspecto-page-header>
        <div class="flex flex-col gap-4 p-6">
            @if (error(); as e) {
                <inspecto-alert variant="error" data-testid="entities-error">{{ e }}</inspecto-alert>
            } @else if (items(); as list) {
                @if (list.length) {
                    <ul
                        class="m-0 grid list-none grid-cols-1 gap-4 p-0 sm:grid-cols-2 lg:grid-cols-3"
                        aria-label="Entity Types"
                    >
                        @for (t of list; track t.id) {
                            <li
                                class="bg-card flex flex-col gap-2 rounded-2xl p-5 shadow"
                                data-testid="entity-type-card"
                            >
                                <div class="flex items-start justify-between gap-2">
                                    <h2 class="m-0 text-base font-semibold">
                                        <a [routerLink]="[...base, t.id]" data-testid="entity-type-link">{{
                                            t.label
                                        }}</a>
                                    </h2>
                                    <inspecto-status-badge
                                        [value]="fresh(t).value"
                                        [label]="fresh(t).label"
                                        data-testid="freshness"
                                    ></inspecto-status-badge>
                                </div>
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
                                        <dd class="m-0 tabular-nums" data-testid="datasets">{{ datasets(t) }}</dd>
                                        <dt class="text-secondary">Data as of</dt>
                                        <dd class="m-0" data-testid="as-of">
                                            {{ day(t.headline.dataAsOf) || 'not known' }}
                                        </dd>
                                    </dl>
                                } @else {
                                    <dl class="m-0 grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                                        <dt class="text-secondary">Datasets</dt>
                                        <dd class="m-0 tabular-nums" data-testid="datasets">{{ datasets(t) }}</dd>
                                    </dl>
                                    <p class="text-secondary m-0 text-sm" data-testid="not-assessed">
                                        {{ notAssessed(t.headline) }}
                                    </p>
                                }
                                @if (t.masked) {
                                    <p class="text-secondary m-0 text-xs" data-testid="masked">
                                        Masked: shown as aliases.
                                    </p>
                                }
                            </li>
                        }
                    </ul>
                } @else {
                    <inspecto-empty-state
                        icon="heroicons_outline:identification"
                        title="No Entity Types in force"
                        message="Entity Types are set in Settings > Link Analysis; a Dataset column's classification binds it to one."
                    ></inspecto-empty-state>
                }
            } @else {
                <inspecto-skeleton [lines]="3" height="6rem"></inspecto-skeleton>
            }
        </div>
    `,
})
export class EntitiesComponent implements OnInit {
    private readonly svc = inject(EntityProfileService);
    protected readonly base = entitiesBase(inject(ActivatedRoute), 0);

    readonly items = signal<EntityTypeSummary[] | null>(null);
    readonly error = signal('');

    protected readonly count = count;
    protected readonly day = day;
    protected readonly notAssessed = notAssessedText;

    ngOnInit(): void {
        this.svc.entityTypes().subscribe({
            next: (r) => this.items.set(r.items ?? []),
            error: (e) => this.error.set(apiErrorMessage(e, 'The Entity Types could not be read.')),
        });
    }

    fresh(t: EntityTypeSummary) {
        return freshness(t.headline.dataAsOf, t.headline.assessed);
    }

    /** Distinct Datasets the type is bound in. */
    datasets(t: EntityTypeSummary): number {
        return new Set(t.bindings.map((b) => b.dataset)).size;
    }
}
