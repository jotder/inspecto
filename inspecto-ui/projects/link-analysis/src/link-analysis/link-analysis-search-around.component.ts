import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import {
    describeSearchAround,
    SearchAroundCondition,
    SearchAroundSpec,
    searchAroundIssues,
} from './search-around';

/**
 * **Search around** - the plain-language view of the Pattern section's path motif: pick a start entity type,
 * then conditions ("follow this link kind, in this direction, to this neighbour type"). Presentational: it
 * edits a {@link SearchAroundSpec} and the toolbox compiles it to motif steps (`searchAroundToSteps`), so it is
 * only another front end over `matchPattern`. It runs ON THE LOADED GRAPH; the beyond-the-graph run (chained
 * expands) is not built - see docs/superpower/la-visual-query-plan.md.
 */
@Component({
    selector: 'inspecto-la-search-around',
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule, MatSelectModule],
    template: `
        <div class="flex flex-col gap-2" data-testid="search-around">
            <mat-form-field subscriptSizing="dynamic" class="w-48">
                <mat-label>Start entity type</mat-label>
                <mat-select
                    [value]="spec().startType ?? ''"
                    (valueChange)="specChange.emit({ ...spec(), startType: $event || undefined })"
                >
                    <mat-option [value]="''">Any</mat-option>
                    @for (k of nodeKinds(); track k) {
                        <mat-option [value]="k">{{ k }}</mat-option>
                    }
                </mat-select>
            </mat-form-field>
            @for (c of spec().conditions; track $index; let i = $index) {
                <div class="flex flex-wrap items-center gap-2">
                    <mat-button-toggle-group
                        class="shrink-0"
                        [value]="c.direction"
                        (change)="edit(i, { direction: $event.value })"
                        [attr.aria-label]="'Condition ' + (i + 1) + ' direction'"
                    >
                        <mat-button-toggle value="out" aria-label="outgoing">→</mat-button-toggle>
                        <mat-button-toggle value="in" aria-label="incoming">←</mat-button-toggle>
                        <mat-button-toggle value="both" aria-label="either direction">↔</mat-button-toggle>
                    </mat-button-toggle-group>
                    <mat-form-field subscriptSizing="dynamic" class="w-40">
                        <mat-label>Link kind</mat-label>
                        <mat-select [value]="c.linkKind ?? ''" (valueChange)="edit(i, { linkKind: $event || undefined })">
                            <mat-option [value]="''">Any</mat-option>
                            @for (k of edgeKinds(); track k) {
                                <mat-option [value]="k">{{ k }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic" class="w-40">
                        <mat-label>Neighbour type</mat-label>
                        <mat-select
                            [value]="c.neighbourType ?? ''"
                            (valueChange)="edit(i, { neighbourType: $event || undefined })"
                        >
                            <mat-option [value]="''">Any</mat-option>
                            @for (k of nodeKinds(); track k) {
                                <mat-option [value]="k">{{ k }}</mat-option>
                            }
                        </mat-select>
                    </mat-form-field>
                    <button mat-icon-button type="button" (click)="remove(i)" [attr.aria-label]="'Remove condition ' + (i + 1)">
                        <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                    </button>
                </div>
            }
            <ol class="text-secondary m-0 list-none p-0 text-sm" aria-label="This search in words" data-testid="search-around-words">
                @for (line of words(); track $index) {
                    <li>{{ line }}</li>
                }
            </ol>
            <div class="flex flex-wrap items-center gap-2">
                <button mat-stroked-button type="button" (click)="add()">
                    <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                    Add condition
                </button>
                <button mat-stroked-button type="button" (click)="run.emit()" [disabled]="issues().length > 0">
                    <mat-icon svgIcon="heroicons_outline:magnifying-glass-circle"></mat-icon>
                    Search around
                </button>
            </div>
            @if (issues().length) {
                <p class="m-0 text-sm" role="status" data-testid="search-around-issues">{{ issues()[0] }}</p>
            }
            <p class="text-secondary m-0 text-xs">
                Searches the graph drawn here. Reaching entities that are not loaded yet is Expand, which follows link kinds and
                direction only, never a neighbour type.
            </p>
        </div>
    `,
})
export class LinkAnalysisSearchAroundComponent {
    readonly spec = input.required<SearchAroundSpec>();
    readonly nodeKinds = input<string[]>([]);
    readonly edgeKinds = input<string[]>([]);
    readonly specChange = output<SearchAroundSpec>();
    readonly run = output<void>();

    protected readonly words = computed(() => describeSearchAround(this.spec()));
    protected readonly issues = computed(() => searchAroundIssues(this.spec()));

    protected edit(i: number, patch: Partial<SearchAroundCondition>): void {
        this.specChange.emit({
            ...this.spec(),
            conditions: this.spec().conditions.map((c, idx) => (idx === i ? { ...c, ...patch } : c)),
        });
    }

    protected add(): void {
        this.specChange.emit({ ...this.spec(), conditions: [...this.spec().conditions, { direction: 'out' }] });
    }

    protected remove(i: number): void {
        this.specChange.emit({ ...this.spec(), conditions: this.spec().conditions.filter((_, idx) => idx !== i) });
    }
}
