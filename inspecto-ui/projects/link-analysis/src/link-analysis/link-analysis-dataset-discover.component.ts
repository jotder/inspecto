import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { DataTableComponent } from '@inspecto/core/data-table';
import { DiscoverResult, InvService } from '@inspecto/link-analysis/api/inv.service';
import {
    discoverBusyColumns,
    discoverCount,
    discoverDrawValues,
    discoverMeasureLine,
    discoverPeriod,
    discoverSourceLine,
} from './dataset-discover';
import { EntitySummaryDialog, EntitySummaryDialogData } from './link-analysis-entity-summary.component';
import type { PresetDraw } from './link-analysis-presets.component';

/**
 * LA-BASIC-DISCOVER-1 **Discover**: what is in one Dataset, before drawing anything - its entities per Entity Type, the
 * busiest at each end of the links, the kinds of link, the period it covers and its Measure totals. Read on demand (a
 * large Dataset takes seconds the first time; the server keeps the answer until the data changes). *Draw these* starts a
 * graph from the busiest entities. A section the server could not measure says why.
 */
@Component({
    selector: 'inspecto-link-analysis-dataset-discover',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent, InspectoSkeletonComponent, DataTableComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2 text-sm" [attr.aria-labelledby]="titleId()" data-testid="la-discover">
            <div class="flex flex-wrap items-center gap-2">
                <p role="heading" [attr.aria-level]="level()" [id]="titleId()" class="m-0 text-base font-semibold">
                    {{ title() }}
                </p>
                @if (!result() && !busy()) {
                    <button mat-stroked-button type="button" data-testid="la-discover-run" (click)="load()">
                        Show what is in it
                    </button>
                } @else if (result()) {
                    <button mat-button type="button" data-testid="la-discover-hide" (click)="hide()">Hide</button>
                }
            </div>
            @if (busy()) {
                <inspecto-skeleton [lines]="3"></inspecto-skeleton>
            }
            @if (error()) {
                <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
            }
            @if (result(); as r) {
                <p class="m-0" data-testid="la-discover-summary">
                    @if (r.rows !== null) {
                        {{ count(r.rows) }} link rows.
                    }
                    @if (period()) {
                        {{ period() }}.
                    } @else if (r.period.reason) {
                        <span class="text-secondary">Period not measured: {{ r.period.reason }}</span>
                    }
                </p>

                <p role="heading" [attr.aria-level]="level() + 1" class="m-0 text-sm font-semibold">Entities</p>
                @if (r.entityTypes.assessed) {
                    <ul class="m-0 list-none p-0" data-testid="la-discover-types">
                        @for (t of r.entityTypes.items; track t.type) {
                            <li>
                                <span class="font-medium">{{ t.label }}</span
                                >:
                                {{ t.distinct === null ? 'not counted' : count(t.distinct, t.approximate) }}
                                <span class="text-secondary text-xs">(in {{ t.columns.join(', ') }})</span>
                            </li>
                        } @empty {
                            <li class="text-secondary">No column of this Dataset holds an Entity Type.</li>
                        }
                    </ul>
                } @else {
                    <p class="text-secondary m-0 text-xs">Not counted: {{ r.entityTypes.reason }}</p>
                }

                <p role="heading" [attr.aria-level]="level() + 1" class="m-0 text-sm font-semibold">Kinds of link</p>
                @if (r.linkKinds.assessed) {
                    <ul class="m-0 list-none p-0" data-testid="la-discover-kinds">
                        @for (k of r.linkKinds.items; track k.kind) {
                            <li>{{ k.kind }}: {{ count(k.links) }}</li>
                        }
                    </ul>
                    @if (r.linkKinds.truncated) {
                        <p class="text-secondary m-0 text-xs">Only the most common kinds are listed.</p>
                    }
                } @else {
                    <p class="text-secondary m-0 text-xs">Not listed: {{ r.linkKinds.reason }}</p>
                }

                <p role="heading" [attr.aria-level]="level() + 1" class="m-0 text-sm font-semibold">Measures</p>
                @if (r.measures.assessed) {
                    <ul class="m-0 list-none p-0" data-testid="la-discover-measures">
                        @for (m of r.measures.items; track m.column) {
                            <li>
                                <span class="font-medium">{{ m.column }}</span
                                >: {{ measureLine(m) }}
                            </li>
                        }
                    </ul>
                } @else {
                    <p class="text-secondary m-0 text-xs">Not totalled: {{ r.measures.reason }}</p>
                }

                <p role="heading" [attr.aria-level]="level() + 1" class="m-0 text-sm font-semibold">Busiest</p>
                @if (r.busiest.assessed) {
                    @if (sourceLine(); as line) {
                        <p class="text-secondary m-0 text-xs" data-testid="la-discover-source">{{ line }}</p>
                    }
                    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                        <inspecto-data-table
                            tier="mini"
                            [rows]="r.busiest.from"
                            [columns]="fromColumns"
                            noRowsTitle="No links"
                            (rowClick)="pick($event, 'from')"
                            data-testid="la-discover-busy-from"
                        ></inspecto-data-table>
                        <inspecto-data-table
                            tier="mini"
                            [rows]="r.busiest.to"
                            [columns]="toColumns"
                            noRowsTitle="No links"
                            (rowClick)="pick($event, 'to')"
                            data-testid="la-discover-busy-to"
                        ></inspecto-data-table>
                    </div>
                    <button
                        mat-stroked-button
                        type="button"
                        class="self-start"
                        data-testid="la-discover-draw"
                        [disabled]="!drawValues().length || !!drawWhyNot()"
                        [disabledInteractive]="true"
                        [attr.aria-describedby]="drawWhyNot() ? titleId() + '-why' : null"
                        (click)="drawThese()"
                    >
                        Draw these
                    </button>
                    @if (drawWhyNot()) {
                        <p [id]="titleId() + '-why'" class="text-secondary m-0 text-xs">{{ drawWhyNot() }}</p>
                    }
                } @else {
                    <p class="text-secondary m-0 text-xs" data-testid="la-discover-busy-why">
                        Not ranked: {{ r.busiest.reason }}
                    </p>
                }
            }
        </section>
    `,
})
export class LinkAnalysisDatasetDiscoverComponent {
    private readonly inv = inject(InvService);
    private readonly dialog = inject(MatDialog);

    /** The Dataset to discover. */
    readonly dataset = input.required<string>();
    /** The section heading. */
    readonly title = input('What is in it');
    /** The heading level of the title (its sections one below): 3 under a Dataset's h2, 2 on a page's own section. */
    readonly level = input<2 | 3>(3);
    /** Why *Draw these* cannot run here; '' = it can, and the host listens to `draw`. */
    readonly drawWhyNot = input('');

    readonly draw = output<PresetDraw>();

    readonly result = signal<DiscoverResult | null>(null);
    readonly busy = signal(false);
    readonly error = signal('');

    readonly titleId = computed(() => 'la-discover-' + this.dataset().replace(/[^A-Za-z0-9_-]/g, '_'));
    readonly period = computed(() => discoverPeriod(this.result()));
    readonly sourceLine = computed(() => discoverSourceLine(this.result()));
    readonly drawValues = computed(() => discoverDrawValues(this.result()));
    readonly fromColumns = discoverBusyColumns('from');
    readonly toColumns = discoverBusyColumns('to');
    protected readonly count = discoverCount;
    protected readonly measureLine = discoverMeasureLine;

    private loading?: Subscription;

    load(): void {
        this.loading?.unsubscribe();
        this.busy.set(true);
        this.error.set('');
        this.loading = this.inv.discover(this.dataset()).subscribe({
            next: (r) => {
                this.busy.set(false);
                this.result.set(r);
            },
            error: (e) => {
                this.busy.set(false);
                this.error.set(apiErrorMessage(e, 'Could not read what is in this Dataset.'));
            },
        });
    }

    hide(): void {
        this.loading?.unsubscribe();
        this.result.set(null);
        this.error.set('');
    }

    /** A busiest row opens that entity's summary (LA-ENTITY-SUMMARY-1); its *Draw it* draws through this panel's `draw`. */
    pick(row: Record<string, unknown>, end: 'from' | 'to'): void {
        const e = row['entity'];
        const type = end === 'from' ? this.result()?.busiest.fromType : this.result()?.busiest.toType;
        if (e === null || e === undefined || String(e) === '' || !type) return;
        const data: EntitySummaryDialogData = {
            type,
            key: String(e),
            dataset: this.dataset(),
            drawWhyNot: this.drawWhyNot(),
        };
        this.dialog
            .open<EntitySummaryDialog, EntitySummaryDialogData, PresetDraw>(EntitySummaryDialog, {
                data,
                width: '44rem',
            })
            .afterClosed()
            .subscribe((d) => {
                if (d) this.draw.emit(d);
            });
    }

    drawThese(): void {
        const values = this.drawValues();
        if (!values.length || this.drawWhyNot()) return;
        this.draw.emit({ datasetId: this.dataset(), values, what: 'the busiest entities' });
    }
}
