import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import { StageChip } from './pipeline-stages';

/**
 * **Guided-mode checklist chips** (definition-surface P6-d) — the compact stage strip the wizard's
 * rail becomes: Collect → Parse → Schema → Enrich → Publish, each chip carrying its status glyph and
 * finding count, each click opening that stage's node.
 *
 * <p>Chips ONLY (plan §11-2, operator-resolved): no "next suggested step" affordance and no wizard
 * lifecycle carried over. Presentational — it computes nothing; {@link stageChecklist} does, and the
 * host owns what a click opens.
 *
 * <p>Status reads as glyph + WORD, never colour alone (WCAG 1.4.1) — the same `✓ / ● / ⚠ / ○`
 * vocabulary the wizard rail used, so the two surfaces are legible to the same eye while both exist.
 * An empty stage has no node to open, so its chip is disabled rather than a click that does nothing.
 */
@Component({
    selector: 'inspecto-pipeline-checklist',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatTooltipModule],
    template: `
        <ul class="m-0 flex list-none items-center gap-1 p-0" aria-label="Pipeline stages">
            @for (c of chips(); track c.id) {
                <li>
                    <button
                        type="button"
                        class="flex items-center gap-1 rounded border px-2 py-1 text-xs hover:bg-hover disabled:opacity-60"
                        [disabled]="!c.nodeId"
                        [matTooltip]="tip(c)"
                        [attr.aria-label]="label(c)"
                        (click)="open.emit(c)"
                    >
                        <span aria-hidden="true">{{ glyph(c) }}</span>
                        <span class="font-medium">{{ c.label }}</span>
                        <span [class.text-warn]="c.status === 'blocked'" class="text-secondary">{{ word(c) }}</span>
                        @if (c.findings) {
                            <span class="text-secondary">({{ c.findings }})</span>
                        }
                    </button>
                </li>
            }
        </ul>
    `,
})
export class PipelineChecklistComponent {
    readonly chips = input.required<StageChip[]>();
    /** The chip the operator wants to work on — the host opens its node's drawer or dialog. */
    readonly open = output<StageChip>();

    glyph(c: StageChip): string {
        switch (c.status) {
            case 'validated':
                return '✓';
            case 'configured':
                return '●';
            case 'blocked':
                return '⚠';
            default:
                return c.optional ? '–' : '○';
        }
    }

    /** The status as a WORD, so the chip is not colour- or glyph-only. */
    word(c: StageChip): string {
        switch (c.status) {
            case 'validated':
                return 'Validated';
            case 'configured':
                return 'Configured';
            case 'blocked':
                return 'Blocked';
            default:
                return c.optional ? 'Not used' : 'Not configured';
        }
    }

    /** What a screen reader hears — the glyph is `aria-hidden`, so this is the whole chip. */
    label(c: StageChip): string {
        const n = c.findings;
        return `${c.label}: ${this.word(c)}${n ? `, ${n} finding${n === 1 ? '' : 's'}` : ''}`;
    }

    tip(c: StageChip): string {
        if (!c.nodeId) return c.optional ? `${c.label} — optional, not used` : `${c.label} — nothing added yet`;
        return `Open ${c.nodeId}`;
    }
}
