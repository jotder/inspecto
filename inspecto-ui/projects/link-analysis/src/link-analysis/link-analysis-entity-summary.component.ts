import {
    ChangeDetectionStrategy,
    Component,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { Subscription, catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { DataTableComponent } from '@inspecto/core/data-table';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';
import { EntitySummaryResult, InvService } from '@inspecto/link-analysis/api/inv.service';
import {
    activityBars,
    activityLine,
    summaryCounterpartColumns,
    summaryHeadline,
    summaryMeasureLine,
} from './entity-summary';
import type { PresetDraw } from './link-analysis-presets.component';
import { NodeRiskService } from './node-risk.service';

/**
 * LA-ENTITY-SUMMARY-1 **Summary**: one entity over one Dataset - its links in and out, first and last seen, who it links with
 * most (by links and by amount), its kinds of link and its activity per day - and *Draw it*. The lite profile Basic has; the
 * full Entity page stays Standard (D-LAE4). With `showLists` it also says which watch or block lists hold it (the node dialog
 * shows those itself).
 */
@Component({
    selector: 'inspecto-link-analysis-entity-summary',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent, InspectoSkeletonComponent, DataTableComponent],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 text-sm"
            data-testid="la-entity-summary"
            [attr.aria-label]="'Summary of ' + key()"
        >
            @if (busy()) {
                <inspecto-skeleton [lines]="3"></inspecto-skeleton>
            }
            @if (error()) {
                <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
            }
            @if (result(); as s) {
                @if (!s.assessed) {
                    <p class="text-secondary m-0" data-testid="la-summary-why">Not summarised: {{ s.reason }}</p>
                } @else {
                    <p class="m-0" data-testid="la-summary-headline">{{ headline() }}</p>
                    @if (measureLine()) {
                        <p class="m-0" data-testid="la-summary-measure">{{ measureLine() }}</p>
                    }
                    @if (s.kinds?.length) {
                        <p class="m-0" data-testid="la-summary-kinds">
                            Kinds of link:
                            @for (k of s.kinds; track k.kind; let last = $last) {
                                {{ k.kind }} ({{ k.out }} out, {{ k.in }} in){{ last ? '' : ', ' }}
                            }
                            @if (s.kindsTruncated) {
                                and more
                            }
                        </p>
                    }
                    @if (bars().length) {
                        <div class="flex flex-col gap-1" data-testid="la-summary-activity">
                            <p class="text-secondary m-0 text-xs">{{ activity() }}</p>
                            <div class="flex h-10 items-end gap-px" aria-hidden="true">
                                @for (b of bars(); track b.day) {
                                    <div
                                        class="bg-primary min-w-0.5 flex-1 rounded-sm"
                                        [style.height.%]="b.pct"
                                        [title]="b.day + ': ' + b.links"
                                    ></div>
                                }
                            </div>
                        </div>
                    }
                    @if (s.counterpartsByLinks?.length) {
                        <p role="heading" [attr.aria-level]="level()" class="m-0 font-semibold">Most links with</p>
                        <inspecto-data-table
                            tier="mini"
                            [rows]="s.counterpartsByLinks!"
                            [columns]="columns()"
                            noRowsTitle="No counterparts"
                            data-testid="la-summary-by-links"
                        ></inspecto-data-table>
                    }
                    @if (s.counterpartsByAmount?.length) {
                        <p role="heading" [attr.aria-level]="level()" class="m-0 font-semibold">Most money with</p>
                        <inspecto-data-table
                            tier="mini"
                            [rows]="s.counterpartsByAmount!"
                            [columns]="columns()"
                            noRowsTitle="No counterparts"
                            data-testid="la-summary-by-amount"
                        ></inspecto-data-table>
                    }
                    @if (showLists()) {
                        <p class="m-0" data-testid="la-summary-lists">
                            Entity Lists:
                            @if (listsError()) {
                                <span class="text-secondary">{{ listsError() }}</span>
                            } @else if (lists() === null) {
                                <span class="text-secondary">reading…</span>
                            } @else if (!lists()!.length) {
                                <span class="text-secondary">on none</span>
                            } @else {
                                {{ lists()!.join(', ') }}
                            }
                        </p>
                    }
                    <button
                        mat-stroked-button
                        type="button"
                        class="self-start"
                        data-testid="la-summary-draw"
                        [disabled]="!s.links || !!drawWhyNot()"
                        [disabledInteractive]="true"
                        (click)="drawIt()"
                    >
                        Draw it
                    </button>
                    @if (drawWhyNot()) {
                        <p class="text-secondary m-0 text-xs">{{ drawWhyNot() }}</p>
                    }
                }
            }
        </section>
    `,
})
export class LinkAnalysisEntitySummaryComponent {
    private readonly inv = inject(InvService);
    private readonly lists$ = inject(NodeRiskService);
    private readonly profiles = inject(EntityProfileService);

    readonly type = input.required<string>();
    /** The entity's key: a raw value, or the exploration alias (a masked type takes only the alias). */
    readonly key = input.required<string>();
    readonly dataset = input.required<string>();
    /** Also say which Entity Lists hold it (off inside the node dialog, which shows them itself). */
    readonly showLists = input(false);
    /** The level of its sub-headings. */
    readonly level = input<3 | 4>(3);
    /** Why *Draw it* cannot run here; '' = it can. */
    readonly drawWhyNot = input('');

    readonly draw = output<PresetDraw>();

    readonly result = signal<EntitySummaryResult | null>(null);
    readonly busy = signal(false);
    readonly error = signal('');
    /** The titles of the lists holding it; null while reading. */
    readonly lists = signal<string[] | null>(null);
    readonly listsError = signal('');

    readonly headline = computed(() => summaryHeadline(this.result()));
    readonly measureLine = computed(() => summaryMeasureLine(this.result()));
    readonly bars = computed(() => activityBars(this.result()));
    readonly activity = computed(() => activityLine(this.result()));
    readonly columns = computed(() => summaryCounterpartColumns(!!this.result()?.measure));

    private loading?: Subscription;

    constructor() {
        effect(() => {
            const t = this.type(),
                k = this.key(),
                d = this.dataset();
            untracked(() => this.load(t, k, d));
        });
    }

    private load(type: string, key: string, dataset: string): void {
        this.loading?.unsubscribe();
        this.result.set(null);
        this.error.set('');
        this.lists.set(null);
        this.listsError.set('');
        this.busy.set(true);
        // a raw value never travels in a URL: it is resolved in a POST body first (the alias for a masked type, the normalised
        // value otherwise) - the Entity page's rule; an alias goes as it is
        const urlKey = key.startsWith('masked:')
            ? of(key)
            : this.profiles.resolveEntity(type, key).pipe(map((r) => r.key));
        this.loading = urlKey.pipe(switchMap((k) => this.inv.entitySummary(type, k, dataset))).subscribe({
            next: (s) => {
                this.busy.set(false);
                this.result.set(s);
                if (this.showLists() && s.assessed) this.loadLists(type, key);
            },
            error: (e) => {
                this.busy.set(false);
                this.error.set(apiErrorMessage(e, 'Could not summarise this entity.'));
            },
        });
    }

    /** The watch / block lists of its type that hold it - the node dialog's own check (`match` per list). */
    private loadLists(type: string, key: string): void {
        this.lists$
            .entityLists()
            .pipe(
                map((idx) => idx.lists.filter((l) => !l.retired && l.entityType.toLowerCase() === type.toLowerCase())),
                switchMap((ls) =>
                    ls.length
                        ? forkJoin(
                              ls.map((l) =>
                                  this.lists$.match(l.id, [key]).pipe(
                                      map((r) => (r.matches[0]?.matched ? l.title : null)),
                                      catchError(() => of(null)),
                                  ),
                              ),
                          )
                        : of([] as (string | null)[]),
                ),
            )
            .subscribe({
                next: (titles) => this.lists.set(titles.filter((t): t is string => !!t)),
                error: (e) => this.listsError.set(apiErrorMessage(e, 'the lists could not be read')),
            });
    }

    drawIt(): void {
        const s = this.result();
        if (!s?.assessed || !s.links || this.drawWhyNot()) return;
        this.draw.emit({ datasetId: this.dataset(), values: [this.key()], what: `${this.key()} (from its summary)` });
    }
}

/** What the summary dialog is opened with. */
export interface EntitySummaryDialogData {
    type: string;
    key: string;
    dataset: string;
    drawWhyNot?: string;
}

/** The summary in a dialog - Discover's busiest rows open it. Closes with a {@link PresetDraw} when *Draw it* is pressed. */
@Component({
    selector: 'inspecto-link-analysis-entity-summary-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatDialogModule, MatButtonModule, LinkAnalysisEntitySummaryComponent],
    template: `
        <h2 mat-dialog-title>{{ data.key }}</h2>
        <mat-dialog-content>
            <p class="text-secondary m-0 mb-2 text-xs">Its links in {{ data.dataset }}.</p>
            <inspecto-link-analysis-entity-summary
                [type]="data.type"
                [key]="data.key"
                [dataset]="data.dataset"
                [showLists]="true"
                [drawWhyNot]="data.drawWhyNot ?? ''"
                (draw)="ref.close($event)"
            ></inspecto-link-analysis-entity-summary>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class EntitySummaryDialog {
    readonly data = inject<EntitySummaryDialogData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<EntitySummaryDialog, PresetDraw>);
}
