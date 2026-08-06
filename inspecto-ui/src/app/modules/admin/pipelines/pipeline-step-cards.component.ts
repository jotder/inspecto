import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { AuthoredNode } from 'app/inspecto/api';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import {
    NodeStatus,
    StepRow,
    categoryLabel,
    nodeConfigEntries,
    paletteHeroIcon,
    statusIcon,
    statusLabel,
    statusTint,
} from './pipeline-graph';

/**
 * The read-only recipe view (ELT amendment UI plan §1, S1): one card per Step in chain order, a
 * `route` Step's branches nested and indented beneath it. Purely presentational — the host computes
 * {@link rows} ({@link flattenStepChain} over {@link detectStepChain}'s tree), the category label per
 * node type, and each node's status (it alone holds the registry-refs/test-outcome state that needs).
 * Editing (S2) and the Guarantees panel land in later slices; this view only shows the chain.
 */
@Component({
    selector: 'app-pipeline-step-cards',
    standalone: true,
    imports: [MatIconModule, ChipComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <ol class="flex flex-col gap-2 p-3" aria-label="Pipeline Steps">
            @for (row of rows; track row.rowId) {
                @if (row.kind === 'node') {
                    <li
                        class="flex flex-col gap-1 rounded border p-2.5"
                        [style.margin-left.px]="row.depth * 24"
                        style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
                    >
                        <div class="flex items-center gap-2">
                            <mat-icon
                                class="icon-size-4 shrink-0 opacity-70"
                                [svgIcon]="paletteHeroIcon(typeCat.get(row.node.type) ?? '')"
                            ></mat-icon>
                            <span class="truncate text-sm font-semibold">
                                {{ row.node.name && row.node.name.trim() ? row.node.name : row.node.id }}
                            </span>
                            <span class="shrink-0 text-xs opacity-60">{{ categoryLabel(typeCat.get(row.node.type) ?? '') }}</span>
                            @if (statusOf) {
                                <span
                                    class="ml-auto inline-flex shrink-0 items-center gap-1 text-xs font-semibold"
                                    [style.color]="statusTint(statusOf(row.node))"
                                >
                                    <mat-icon class="icon-size-4" [svgIcon]="statusIcon(statusOf(row.node))"></mat-icon>
                                    {{ statusLabel(statusOf(row.node)) }}
                                </span>
                            }
                        </div>
                        @if (row.node.description) {
                            <p class="truncate text-xs opacity-70">{{ row.node.description }}</p>
                        }
                        @if (configSummary(row.node); as entries) {
                            @if (entries.length) {
                                <div class="flex flex-wrap gap-1">
                                    @for (e of entries; track e.k) {
                                        <inspecto-chip variant="outline" tone="neutral">{{ e.k }}: {{ e.v }}</inspecto-chip>
                                    }
                                </div>
                            }
                        }
                    </li>
                } @else {
                    <li
                        class="flex items-center gap-2 pt-1 text-xs font-semibold uppercase tracking-wide opacity-60"
                        [style.margin-left.px]="row.depth * 24"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-turn-down-right"></mat-icon>
                        <span>branch: {{ row.key }}</span>
                        @if (row.where) {
                            <span class="font-normal normal-case opacity-80">when {{ row.where }}</span>
                        }
                        @if (row.isDefault) {
                            <inspecto-chip variant="soft" tone="neutral">default</inspecto-chip>
                        }
                    </li>
                }
            }
        </ol>
    `,
})
export class PipelineStepCardsComponent {
    @Input({ required: true }) rows: StepRow[] = [];
    @Input({ required: true }) typeCat!: Map<string, string>;
    /** Optional — omitted when the host has no registry-refs/test-outcome state loaded yet. */
    @Input() statusOf?: (node: AuthoredNode) => NodeStatus;

    readonly categoryLabel = categoryLabel;
    readonly paletteHeroIcon = paletteHeroIcon;
    readonly statusIcon = statusIcon;
    readonly statusLabel = statusLabel;
    readonly statusTint = statusTint;

    /** The card's compact config summary — up to 4 entries, so a wide config doesn't dwarf the card. */
    configSummary(node: AuthoredNode): { k: string; v: string }[] {
        return nodeConfigEntries(node).slice(0, 4);
    }
}
