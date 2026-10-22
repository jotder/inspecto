import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { guideDone, SpaceGuideState, SpaceGuideStepId, spaceGuideSteps } from './space-guide';

/** localStorage key remembering a collapsed guide ('1'). */
export const SPACE_GUIDE_COLLAPSED_KEY = 'inspecto.space-guide.collapsed';

function readCollapsed(): boolean {
    try {
        return localStorage.getItem(SPACE_GUIDE_COLLAPSED_KEY) === '1';
    } catch {
        return false;
    }
}

/**
 * The **Space guide** (help H1): *Space · Data · What the columns hold · First graph · (optional) A list*, each ticked
 * from server state ({@link SpaceGuideState}, loaded by `SpaceGuideService`). Collapsible, never modal, and gone once
 * every required step is done. Presentational and host-agnostic, so the console can reuse it: each line emits
 * {@link stepOpen} and the HOST decides where that step lives.
 */
@Component({
    selector: 'inspecto-space-guide',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule],
    template: `
        @if (visible()) {
            <section class="bg-card flex flex-col gap-3 rounded-2xl border p-4" aria-labelledby="space-guide-title">
                <div class="flex items-center gap-3">
                    <h2 id="space-guide-title" class="m-0 flex-1 text-lg font-semibold">Space guide</h2>
                    <span class="text-secondary text-sm tabular-nums">{{ doneCount() }} of {{ required() }} done</span>
                    <button
                        type="button"
                        class="focus-visible:outline-primary flex h-8 w-8 items-center justify-center rounded-md focus-visible:outline-2"
                        [attr.aria-expanded]="!collapsed()"
                        aria-controls="space-guide-steps"
                        [attr.aria-label]="collapsed() ? 'Show the Space guide' : 'Hide the Space guide'"
                        (click)="toggle()"
                    >
                        <mat-icon
                            class="icon-size-5"
                            [svgIcon]="collapsed() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-up'"
                        ></mat-icon>
                    </button>
                </div>
                <ol id="space-guide-steps" class="m-0 flex list-none flex-col gap-1 p-0" [hidden]="collapsed()">
                    @for (step of steps(); track step.id) {
                        <li>
                            <button
                                type="button"
                                class="hover:bg-hover focus-visible:outline-primary flex w-full items-start gap-3 rounded-md px-2 py-2 text-left focus-visible:outline-2"
                                [attr.data-step]="step.id"
                                (click)="stepOpen.emit(step.id)"
                            >
                                <mat-icon
                                    class="icon-size-5 mt-0.5 flex-0"
                                    [class.text-primary]="step.done"
                                    [class.text-secondary]="!step.done"
                                    [svgIcon]="
                                        step.done ? 'heroicons_solid:check-circle' : 'heroicons_outline:minus-circle'
                                    "
                                    aria-hidden="true"
                                ></mat-icon>
                                <span class="flex min-w-0 flex-col">
                                    <span class="font-medium">
                                        {{ step.label }}
                                        @if (step.optional) {
                                            <span class="text-secondary text-sm font-normal">(optional)</span>
                                        }
                                        <span class="sr-only">{{ step.done ? ', done. ' : ', to do. ' }}</span>
                                    </span>
                                    <span class="text-secondary text-sm">{{ step.hint }}</span>
                                </span>
                            </button>
                        </li>
                    }
                </ol>
            </section>
        }
    `,
})
export class SpaceGuideComponent {
    /** The server state the ticks come from; null while it loads (nothing renders). */
    readonly state = input<SpaceGuideState | null>(null);
    /** A line was chosen: the host opens that step. */
    readonly stepOpen = output<SpaceGuideStepId>();

    protected readonly steps = computed(() => {
        const s = this.state();
        return s ? spaceGuideSteps(s) : [];
    });
    protected readonly visible = computed(() => {
        const s = this.state();
        return !!s && !guideDone(s);
    });
    protected readonly required = computed(() => this.steps().filter((s) => !s.optional).length);
    protected readonly doneCount = computed(() => this.steps().filter((s) => !s.optional && s.done).length);
    protected readonly collapsed = signal(readCollapsed());

    protected toggle(): void {
        const next = !this.collapsed();
        this.collapsed.set(next);
        try {
            localStorage.setItem(SPACE_GUIDE_COLLAPSED_KEY, next ? '1' : '0');
        } catch {
            /* storage blocked: the choice just does not persist */
        }
    }
}
