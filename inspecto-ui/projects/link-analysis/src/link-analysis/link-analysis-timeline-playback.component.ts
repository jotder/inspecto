import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { G6GraphData } from '@inspecto/core/graph';
import { GraphSelection } from '@inspecto/link-analysis/graph/graph-analysis';
import { activeInSlice, playbackSlice, playbackSliceCount, prefersReducedMotion } from './timeline-playback';

/** Milliseconds one slice stays on screen while playing. */
const TICK_MS = 1000;

const label = (t: number): string => new Date(t).toISOString().slice(0, 16).replace('T', ' ');

/**
 * **Timeline playback** (LA-INVESTIGATION-OPS-DEFERRED-1, SPA slice). Steps through the timeline column's range in
 * equal slices (play / pause / step / scrub) and emits the links whose date falls in the current slice, so the canvas
 * highlights them. It only EMITS A HIGHLIGHT: the graph, the timeline filter and the cutoff are untouched. Under
 * `prefers-reduced-motion` nothing advances by itself — Play is disabled and the step buttons and the scrubber do the
 * work, one deliberate move at a time.
 */
@Component({
    selector: 'inspecto-link-analysis-timeline-playback',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-1.5"
            aria-label="Timeline playback"
            data-test="playback"
        >
            <button
                mat-icon-button
                type="button"
                data-test="playback-back"
                aria-label="Previous slice"
                matTooltip="Previous slice"
                [disabled]="index() <= 0"
                (click)="step(-1)"
            >
                <mat-icon svgIcon="heroicons_outline:backward"></mat-icon>
            </button>
            <button
                mat-icon-button
                type="button"
                data-test="playback-play"
                [attr.aria-label]="playing() ? 'Pause playback' : 'Play timeline'"
                [matTooltip]="reduced ? 'Autoplay is off (reduced motion) — use the step buttons' : ''"
                [disabled]="reduced || (!playing() && atEnd())"
                (click)="toggle()"
            >
                <mat-icon [svgIcon]="playing() ? 'heroicons_outline:pause' : 'heroicons_outline:play'"></mat-icon>
            </button>
            <button
                mat-icon-button
                type="button"
                data-test="playback-forward"
                aria-label="Next slice"
                matTooltip="Next slice"
                [disabled]="atEnd()"
                (click)="step(1)"
            >
                <mat-icon svgIcon="heroicons_outline:forward"></mat-icon>
            </button>
            <label class="flex min-w-32 flex-auto items-center gap-2 text-xs">
                <span class="text-secondary">Slice</span>
                <input
                    class="w-full"
                    type="range"
                    data-test="playback-scrub"
                    min="0"
                    [max]="count() - 1"
                    step="1"
                    [value]="Math.max(index(), 0)"
                    [attr.aria-valuetext]="valueText()"
                    (input)="seek($event)"
                />
            </label>
            <button
                mat-button
                type="button"
                class="!min-h-0 !px-2 !py-1 text-xs"
                data-test="playback-reset"
                [disabled]="index() < 0"
                (click)="reset()"
            >
                Reset
            </button>
            <p
                class="text-secondary m-0 w-full text-xs"
                role="status"
                data-test="playback-status"
                [attr.aria-live]="playing() ? 'off' : 'polite'"
            >
                {{ status() }}
            </p>
            @if (reduced) {
                <p class="text-secondary m-0 w-full text-xs" data-test="playback-reduced">
                    Your system asks for reduced motion, so playback does not advance by itself. Step through the slices
                    instead.
                </p>
            }
        </section>
    `,
})
export class LinkAnalysisTimelinePlaybackComponent {
    /** The displayed graph (read, never changed). */
    readonly graph = input.required<G6GraphData | null>();
    /** The edge `attrs` column holding each link's date (the timeline's column). */
    readonly column = input.required<string>();
    /** `[min, max]` epoch millis of that column — the playback's range. */
    readonly extent = input.required<[number, number]>();

    /** The slice's links to highlight, or `null` to clear the highlight. */
    readonly highlight = output<GraphSelection | null>();

    protected readonly Math = Math;
    /** Read once: a user who flips the setting mid-session gets it on the next open, which is fine for a toggle. */
    protected readonly reduced = prefersReducedMotion();
    /** `-1` = not started (nothing highlighted). */
    readonly index = signal(-1);
    readonly playing = signal(false);
    readonly count = computed(() => playbackSliceCount(this.extent()));
    readonly atEnd = computed(() => this.index() >= this.count() - 1);

    private readonly active = computed(() => {
        const i = this.index();
        if (i < 0) return null;
        const slice = playbackSlice(this.extent(), i);
        return { slice, selection: activeInSlice(this.graph() ?? { nodes: [], edges: [] }, this.column(), slice) };
    });

    readonly valueText = computed(() => {
        const a = this.active();
        return a ? `${label(a.slice.start)} to ${label(a.slice.end)}` : 'not started';
    });

    readonly status = computed(() => {
        const a = this.active();
        if (!a)
            return `Not started. ${this.count()} slices from ${label(this.extent()[0])} to ${label(this.extent()[1])}.`;
        const n = a.selection.edgeIds.length;
        return `Slice ${a.slice.index + 1} of ${a.slice.count}: ${label(a.slice.start)} to ${label(a.slice.end)} — ${n} ${n === 1 ? 'link' : 'links'} active.`;
    });

    private timer: ReturnType<typeof setInterval> | null = null;
    private lit = false;

    constructor() {
        // The range or column changed under us (a new graph, another column): start over rather than show a stale slice.
        effect(() => {
            this.extent();
            this.column();
            untracked(() => this.reset());
        });
        // Keep the canvas highlight in step with the slice and the graph (a link filter changes what is active).
        effect(() => {
            const a = this.active();
            untracked(() => {
                if (!a) return;
                this.lit = true;
                this.highlight.emit(a.selection.edgeIds.length ? a.selection : null);
            });
        });
        // Outputs are dead once destroyed, so the host clears the highlight when it removes this strip.
        inject(DestroyRef).onDestroy(() => this.halt());
    }

    step(delta: number): void {
        this.halt();
        this.go(this.index() + delta);
    }

    seek(e: Event): void {
        this.halt();
        this.go(Number((e.target as HTMLInputElement).value));
    }

    toggle(): void {
        if (this.playing()) return this.halt();
        if (this.reduced) return;
        if (this.atEnd()) this.go(0);
        else if (this.index() < 0) this.go(0);
        this.playing.set(true);
        this.timer = setInterval(() => {
            if (this.atEnd()) return this.halt();
            this.go(this.index() + 1);
            if (this.atEnd()) this.halt();
        }, TICK_MS);
    }

    reset(): void {
        this.halt();
        this.index.set(-1);
        if (this.lit) {
            this.lit = false;
            this.highlight.emit(null);
        }
    }

    private go(i: number): void {
        this.index.set(Math.min(this.count() - 1, Math.max(0, i)));
    }

    private halt(): void {
        if (this.timer !== null) clearInterval(this.timer);
        this.timer = null;
        this.playing.set(false);
    }
}
