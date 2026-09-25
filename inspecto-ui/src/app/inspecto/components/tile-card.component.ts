import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import type { TileShape } from 'app/inspecto/viz/dashboard-grid';
import { InspectoSkeletonComponent } from './skeleton.component';

/** `loading` → a skeleton shaped like {@link TileShape}; `empty` → one quiet line; `ready` → the projected body. */
export type TileState = 'loading' | 'empty' | 'ready';

let nextTileId = 0;

/**
 * The frame every Widget sits in on a Dashboard — the editor, the Menu viewer and the share viewer all draw
 * it, so a tile looks the same wherever it is seen. It owns the card (surface, border, radius, padding), the
 * header (a clear title, an optional muted subtitle, a status slot and the tile's actions) and the two states
 * before a body exists: a skeleton matched to the Widget type while the query runs, and a compact one-line
 * empty state when it returns no rows. It fills its grid cell, so tiles in one row share a height.
 *
 * Slots: `[tileStatus]` (always visible — the Stale badge), `[tileActions]` (icon buttons; revealed on hover
 * AND on keyboard focus, and always shown on a touch screen — never hover-only), and the default body. The
 * body slot is projected unconditionally; the HOST decides whether to put anything in it, so a body never
 * sits inside an un-rendered branch.
 */
@Component({
    selector: 'inspecto-tile-card',
    standalone: true,
    imports: [MatIconModule, InspectoSkeletonComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block h-full min-w-0' },
    template: `
        <section
            class="tile bg-card flex h-full min-w-0 flex-col rounded-2xl border p-4 shadow-sm"
            [attr.aria-labelledby]="titleId"
            [attr.aria-busy]="state() === 'loading' ? 'true' : null"
        >
            <header class="mb-3 flex min-h-8 items-start gap-2">
                <div class="min-w-0 flex-1">
                    <h2 [id]="titleId" class="truncate text-base font-semibold leading-6" [title]="title()">
                        {{ title() }}
                    </h2>
                    @if (subtitle()) {
                        <p class="text-secondary truncate text-sm leading-5" [title]="subtitle()">{{ subtitle() }}</p>
                    }
                </div>
                <div class="flex shrink-0 items-center"><ng-content select="[tileStatus]" /></div>
                <div class="tile-actions -my-1 -mr-2 flex shrink-0 items-center" data-testid="tile-actions">
                    <ng-content select="[tileActions]" />
                </div>
            </header>
            <div class="flex min-h-0 flex-1 flex-col">
                @if (state() === 'loading') {
                    <div class="flex flex-1 flex-col" data-testid="tile-skeleton" [attr.data-shape]="shape()">
                        @switch (shape()) {
                            @case ('kpi') {
                                <div class="flex h-36 flex-col justify-center gap-3">
                                    <inspecto-skeleton width="55%" height="2.25rem" />
                                    <inspecto-skeleton width="35%" height="1rem" />
                                </div>
                            }
                            @case ('table') {
                                <inspecto-skeleton height="1.25rem" />
                                <inspecto-skeleton class="mt-3" [lines]="5" height="0.875rem" lastLineWidth="70%" />
                            }
                            @default {
                                <div class="flex h-64 items-end gap-3 border-b border-l pb-px pl-px">
                                    @for (h of chartBars; track $index) {
                                        <div class="flex-1" [style.height]="h">
                                            <inspecto-skeleton class="h-full" height="100%" />
                                        </div>
                                    }
                                </div>
                            }
                        }
                    </div>
                    <span class="sr-only" role="status">Loading {{ title() }}</span>
                } @else if (state() === 'empty') {
                    <div
                        class="text-secondary flex flex-1 items-center justify-center gap-2 py-6 text-sm"
                        [class.min-h-36]="shape() === 'kpi'"
                        [class.min-h-40]="shape() !== 'kpi'"
                        role="status"
                        data-testid="tile-empty"
                    >
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:inbox" aria-hidden="true"></mat-icon>
                        <span>{{ emptyMessage() }}</span>
                    </div>
                }
                <ng-content />
            </div>
        </section>
    `,
    styles: [
        `
            .tile-actions {
                opacity: 0;
                transition: opacity 150ms ease-in-out;
            }
            .tile:hover .tile-actions,
            .tile:focus-within .tile-actions {
                opacity: 1;
            }
            @media (hover: none) {
                .tile-actions {
                    opacity: 1;
                }
            }
            @media (prefers-reduced-motion: reduce) {
                .tile-actions {
                    transition: none;
                }
            }
        `,
    ],
})
export class InspectoTileCardComponent {
    readonly title = input.required<string>();
    readonly subtitle = input<string | undefined>(undefined);
    readonly state = input<TileState>('ready');
    readonly shape = input<TileShape>('chart');
    readonly emptyMessage = input('No data for this selection');

    protected readonly titleId = `inspecto-tile-title-${nextTileId++}`;
    /** Bar heights for the chart skeleton — reads as a bar chart without pretending to be data. */
    protected readonly chartBars = ['45%', '70%', '55%', '85%', '40%', '65%'];
}
