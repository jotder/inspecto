import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { GROW_GUIDE, PackTerms, hintSeen, markHintSeen } from './grow-exclude';

/** One held busy item as the tray lists it. */
export interface HeldItem {
    id: string;
    label: string;
    contacts: number | null;
}

/**
 * LA-GROW-EXCLUDE-1 §3.2 — the **Held** tray: busy items (super nodes) a Grow held back, each with its contact count and
 * why, "Remove held" (one recorded `exclude` with the reason) and "Expand anyway" per item. Presentational: the screen
 * acts on the outputs. Minimises to a pill, like the legend.
 */
@Component({
    selector: 'inspecto-link-analysis-held-tray',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule, ChipComponent],
    template: `
        @if (open()) {
            <section
                class="flex max-h-[40vh] w-[20rem] max-w-[calc(100vw-2rem)] flex-col gap-2 overflow-y-auto rounded-lg border p-2 shadow"
                style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
                aria-labelledby="la-held-title"
                data-testid="la-held-tray"
            >
                <div class="flex items-center gap-1">
                    <h2
                        id="la-held-title"
                        class="m-0 text-xs font-semibold"
                        [matTooltip]="'Busy ' + t().nodes + ' have more linked entities than the busy limit'"
                    >
                        Held: busy {{ t().nodes }} ({{ held().length }})
                    </h2>
                    <button mat-icon-button class="ml-auto" type="button" aria-label="Minimise the Held tray" (click)="open.set(false)">
                        <mat-icon svgIcon="heroicons_outline:minus"></mat-icon>
                    </button>
                </div>
                @if (showHint()) {
                    <div class="rounded-md border p-2 text-xs" style="border-color: var(--gamma-border)" data-testid="la-held-hint">
                        <p class="m-0">
                            Some {{ t().nodes }} link to thousands of others and hide the real links. Expand holds them:
                            they stay on the graph, ringed, but are not expanded.
                        </p>
                        <p class="text-secondary m-0" data-testid="la-hint-guide">More in the user guide: <cite>{{ guide.chapter }}</cite> ({{ guide.where }}).</p>
                        <button mat-button type="button" class="mt-1" (click)="dismissHint()">Got it</button>
                    </div>
                }
                <p class="text-secondary m-0 text-xs">
                    Held because each has more than {{ threshold().toLocaleString('en-GB') }} linked entities.
                </p>
                <ul class="m-0 flex list-none flex-col gap-1 p-0 text-xs">
                    @for (h of shown(); track h.id) {
                        <li class="flex flex-wrap items-center gap-1">
                            <button
                                type="button"
                                class="font-mono underline-offset-2 hover:underline"
                                [attr.aria-label]="'Show ' + h.label + ' on the graph'"
                                (click)="focusItem.emit(h.id)"
                            >
                                {{ h.label }}
                            </button>
                            <inspecto-chip variant="soft" tone="warning">{{
                                h.contacts != null ? h.contacts.toLocaleString('en-GB') + ' linked' : 'held'
                            }}</inspecto-chip>
                            <button
                                mat-button
                                type="button"
                                class="ml-auto"
                                [disabled]="!!whyNot()"
                                [disabledInteractive]="true"
                                [matTooltip]="whyNot() || 'Expand through this one anyway (recorded)'"
                                [attr.aria-label]="'Expand ' + h.label + ' anyway'"
                                (click)="!whyNot() && expandAnyway.emit(h.id)"
                            >
                                Expand anyway
                            </button>
                        </li>
                    }
                </ul>
                @if (held().length > shown().length) {
                    <p class="text-secondary m-0 text-xs">The first {{ shown().length }} are listed.</p>
                }
                <div class="flex flex-col items-end gap-1">
                    <button
                        mat-flat-button
                        color="primary"
                        type="button"
                        data-testid="la-held-remove"
                        [disabled]="!!whyNot()"
                        [disabledInteractive]="true"
                        [matTooltip]="whyNot() || removeHint()"
                        (click)="!whyNot() && removeAll.emit()"
                    >
                        {{ mode() === 'investigation' ? 'Exclude' : 'Hide' }} held ({{ held().length }})
                    </button>
                    @if (whyNot(); as why) {
                        <p class="text-secondary m-0 text-xs" data-testid="la-held-why">{{ why }}</p>
                    }
                </div>
            </section>
        } @else {
            <button
                mat-stroked-button
                type="button"
                class="shadow"
                style="background: var(--gamma-bg-card)"
                [attr.aria-label]="'Show the Held tray: ' + held().length + ' busy ' + t().nodes"
                (click)="open.set(true)"
                data-testid="la-held-pill"
            >
                {{ held().length }} held
            </button>
        }
    `,
})
export class LinkAnalysisHeldTrayComponent {
    readonly held = input.required<HeldItem[]>();
    readonly threshold = input.required<number>();
    readonly terms = input.required<PackTerms>();
    readonly mode = input<'investigation' | 'basic'>('investigation');
    /** Why the actions cannot run (e.g. no write capability); empty = they can. */
    readonly whyNot = input('');
    readonly removeAll = output<void>();
    readonly expandAnyway = output<string>();
    readonly focusItem = output<string>();

    readonly open = signal(true);
    readonly showHint = signal(!hintSeen('busy'));
    readonly guide = GROW_GUIDE;
    readonly t = computed(() => this.terms());
    readonly shown = computed(() => this.held().slice(0, 50));
    readonly removeHint = computed(() =>
        this.mode() === 'investigation'
            ? `Exclude every held ${this.t().node} as one recorded step; Undo reverts it`
            : `Hide every held ${this.t().node} (not recorded); Undo brings them back`,
    );

    dismissHint(): void {
        markHintSeen('busy');
        this.showHint.set(false);
    }
}
