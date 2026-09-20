import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { WorkingSetStat } from 'app/inspecto/graph';

/** One swatch row of the legend. */
export interface LegendItem {
    kind: string;
    color: string;
    count: number;
}

/**
 * **Link Analysis — canvas legend** (presentational). Node kinds with their canvas colour and count, plus the
 * link kinds present. Minimises to a pill so the graph gets the space; the host owns the open state so the
 * View toolbox and a saved view can drive it.
 */
@Component({
    selector: 'inspecto-link-analysis-legend',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    host: { class: 'block' },
    template: `
        @if (open()) {
            <div class="rounded-lg border p-2 text-xs shadow-sm" style="background: var(--gamma-bg-card)">
                <div class="flex items-center gap-1">
                    <span class="font-semibold">Legend</span>
                    <button
                        mat-icon-button
                        class="ml-auto !h-6 !w-6 !p-0"
                        (click)="openChange.emit(false)"
                        matTooltip="Minimize the legend"
                        aria-label="Minimize the legend"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:minus"></mat-icon>
                    </button>
                </div>
                <ul class="mt-1 flex flex-col gap-0.5" aria-label="Node kinds">
                    @for (i of items(); track i.kind) {
                        <li class="flex items-center gap-2">
                            <span
                                class="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
                                [style.background]="i.color"
                                aria-hidden="true"
                            ></span>
                            <span>{{ i.kind }}</span>
                            <span class="text-secondary ml-auto tabular-nums">{{ i.count }}</span>
                        </li>
                    }
                </ul>
                @if (edgeKinds().length) {
                    <div class="text-secondary mt-1 border-t pt-1">links: {{ edgeKinds().join(' · ') }}</div>
                }
                @if (hint()) {
                    <div class="text-secondary mt-1">{{ hint() }}</div>
                }
            </div>
        } @else {
            <button
                type="button"
                class="rounded-full border px-2.5 py-0.5 text-xs font-semibold shadow-sm"
                style="background: var(--gamma-bg-card)"
                (click)="openChange.emit(true)"
                aria-expanded="false"
                aria-label="Show the legend"
            >
                Legend
            </button>
        }
    `,
})
export class LinkAnalysisLegendComponent {
    readonly items = input<LegendItem[]>([]);
    readonly edgeKinds = input<string[]>([]);
    readonly hint = input('');
    readonly open = input(true);
    readonly openChange = output<boolean>();
}

/**
 * **Link Analysis — working set** (presentational). What is on the canvas versus what was loaded, as stat
 * tiles derived by `workingSetStats` (shaped by the domain profile), plus the truncation signal — the
 * two-stage query loop's "are you looking at the whole answer" indicator (spec §3.7).
 */
@Component({
    selector: 'inspecto-link-analysis-working-set',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    host: { class: 'block' },
    template: `
        @if (open()) {
            <div class="w-64 rounded-lg border p-2 text-xs shadow-sm" style="background: var(--gamma-bg-card)">
                <div class="flex items-center gap-1">
                    <span class="font-semibold">Working set</span>
                    @if (truncated()) {
                        <span class="text-warn font-semibold" title="The server returned a bounded slice">
                            truncated
                        </span>
                    } @else {
                        <span class="text-secondary">{{ mode() }}</span>
                    }
                    <button
                        mat-icon-button
                        class="ml-auto !h-6 !w-6 !p-0"
                        (click)="openChange.emit(false)"
                        matTooltip="Minimize the working set"
                        aria-label="Minimize the working set"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:minus"></mat-icon>
                    </button>
                </div>
                <dl class="mt-1 grid grid-cols-2 gap-1" aria-label="Working set statistics">
                    @for (s of stats(); track s.label) {
                        <div class="rounded-md border px-2 py-1" [title]="s.hint ?? ''">
                            <dt class="text-secondary truncate text-[10px] font-semibold">{{ s.label }}</dt>
                            <dd class="m-0 truncate font-semibold tabular-nums">{{ s.value }}</dd>
                        </div>
                    }
                </dl>
                @if (customizable()) {
                    <button type="button" class="text-primary mt-1 text-[11px] underline" (click)="customize.emit()">
                        Customize statistics
                    </button>
                }
            </div>
        } @else {
            <button
                type="button"
                class="rounded-full border px-2.5 py-0.5 text-xs font-semibold shadow-sm"
                style="background: var(--gamma-bg-card)"
                (click)="openChange.emit(true)"
                aria-expanded="false"
                aria-label="Show the working set"
            >
                Working set
                @if (truncated()) {
                    <span class="text-warn">· truncated</span>
                }
            </button>
        }
    `,
})
export class LinkAnalysisWorkingSetComponent {
    readonly stats = input<WorkingSetStat[]>([]);
    readonly truncated = input(false);
    /** Short label for how the set was sampled, e.g. "top-N by count". */
    readonly mode = input('');
    readonly customizable = input(false);
    readonly open = input(true);
    readonly openChange = output<boolean>();
    readonly customize = output<void>();
}
