import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { InvService, WorkingSetRelation } from 'app/inspecto/api';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoStatTileComponent } from 'app/inspecto/components/stat-tile.component';
import {
    BROKEN_PIN_MESSAGE,
    NOT_AVAILABLE_MESSAGE,
    RELATION_NOUN,
    WorkingSetDrift,
    asWorkingSetBinding,
    driftLine,
    readFailure,
    workingSetDrift,
} from './working-set-widget';

/** Rows a tile lists; the count above them is always the TRUE total. */
const TILE_ROWS = 20;
/** Rows read per side when a Live tile diffs its pin against the head (the route's cap). */
const DIFF_ROWS = 10_000;

type TileState =
    | { kind: 'loading' }
    | { kind: 'unbound' }
    | { kind: 'unavailable' }
    | { kind: 'error'; message: string }
    | { kind: 'broken-pin' }
    | { kind: 'ready'; shown: WorkingSetRelation; drift: WorkingSetDrift | null; baselineBroken: boolean };

/**
 * **Working Set Widget** tile (LA-21, decision D-E6) — a dashboard tile over an Investigation's Working Set, loaded
 * lazily through the viz component registry (`working-set`). It reads ONLY through
 * `GET /inv/investigations/{id}/working-set`, so the owner-only / PDP gate (D-E7) runs for every viewer: a shared
 * dashboard's viewer who is not the owner gets a 404 and sees "Not available to you", never rows.
 *
 * - **Frozen** (the default) reads the relation AT its pinned step and checks the answer's hash against the pin — a
 *   mismatch shows as a broken pin, never as quietly different evidence.
 * - **Live** reads the head, plus the pinned step as its baseline, and states the drift since the pin.
 *
 * The tile always states its kind.
 */
@Component({
    selector: 'app-working-set-widget',
    standalone: true,
    imports: [ChipComponent, InspectoAlertComponent, InspectoEmptyStateComponent, InspectoStatTileComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex h-full min-h-0 flex-col gap-2 text-sm">
            @if (spec(); as b) {
                <div class="flex flex-wrap items-center gap-2">
                    <inspecto-chip variant="soft" [tone]="b.mode === 'live' ? 'primary' : 'neutral'">
                        {{ b.mode === 'live' ? 'Live' : 'Frozen' }}
                    </inspecto-chip>
                    <span class="text-secondary truncate text-xs">Investigation {{ viewId() }}</span>
                </div>
            }
            @switch (state().kind) {
                @case ('unbound') {
                    <inspecto-empty-state
                        icon="heroicons_outline:finger-print"
                        message="This Widget has no Working Set binding — save one from the Link Analysis Investigation panel."
                    />
                }
                @case ('unavailable') {
                    <inspecto-empty-state
                        icon="heroicons_outline:lock-closed"
                        title="Not available to you"
                        [message]="notAvailable"
                    />
                }
                @case ('broken-pin') {
                    <inspecto-alert variant="warning" title="The pin no longer holds">{{ brokenPin }}</inspecto-alert>
                }
                @case ('error') {
                    <inspecto-alert variant="warning">{{ errorMessage() }}</inspecto-alert>
                }
                @case ('ready') {
                    @if (ready(); as r) {
                        <inspecto-stat-tile [label]="relationTitle()" [value]="r.shown.total" [hint]="pinLine()" />
                        @if (spec()?.mode === 'live') {
                            <p class="m-0 text-xs tabular-nums" role="status">
                                @if (r.baselineBroken) {
                                    The pin no longer matches the log — drift since it cannot be measured.
                                } @else if (r.drift; as d) {
                                    {{ driftText(d) }}
                                }
                            </p>
                        }
                        @if (r.shown.rows.length) {
                            <div class="min-h-0 flex-auto overflow-auto">
                                <table class="w-full text-left text-xs">
                                    <caption class="sr-only">
                                        {{
                                            caption()
                                        }}
                                    </caption>
                                    <thead>
                                        <tr>
                                            @for (c of r.shown.columns; track c) {
                                                <th scope="col" class="px-1 font-semibold">{{ c }}</th>
                                            }
                                        </tr>
                                    </thead>
                                    <tbody>
                                        @for (row of listed(); track $index) {
                                            <tr>
                                                @for (c of r.shown.columns; track c) {
                                                    <td class="px-1 tabular-nums">{{ cell(row[c]) }}</td>
                                                }
                                            </tr>
                                        }
                                    </tbody>
                                </table>
                                @if (r.shown.total > listed().length) {
                                    <p class="text-secondary m-0 pt-1 text-xs">
                                        First {{ listed().length }} of {{ r.shown.total }} shown.
                                    </p>
                                }
                            </div>
                        }
                    }
                }
                @default {
                    <div class="text-secondary flex h-full items-center justify-center text-sm">Loading…</div>
                }
            }
        </div>
    `,
})
export class WorkingSetWidgetComponent {
    private inv = inject(InvService);

    /** The Investigation this Widget reads (the widget's `viewId`). */
    readonly viewId = input<string | undefined>(undefined);
    /** The widget's `workingSet` binding {relation, mode, pin}, as stored. */
    readonly binding = input<unknown>(undefined);

    readonly notAvailable = NOT_AVAILABLE_MESSAGE;
    readonly brokenPin = BROKEN_PIN_MESSAGE;

    readonly spec = computed(() => asWorkingSetBinding(this.binding()));
    readonly state = signal<TileState>({ kind: 'loading' });
    readonly ready = computed(() => {
        const s = this.state();
        return s.kind === 'ready' ? s : null;
    });
    readonly errorMessage = computed(() => {
        const s = this.state();
        return s.kind === 'error' ? s.message : '';
    });
    readonly listed = computed(() => this.ready()?.shown.rows.slice(0, TILE_ROWS) ?? []);
    readonly caption = computed(() => `${this.relationTitle()} of Investigation ${this.viewId() ?? ''}`);
    readonly relationTitle = computed(() => {
        const b = this.spec();
        return b ? RELATION_NOUN[b.relation].title : '';
    });
    readonly pinLine = computed(() => {
        const b = this.spec();
        const r = this.ready();
        if (!b || !r) return '';
        const when = new Date(b.pin.pinnedAt);
        const saved = isNaN(when.getTime()) ? '' : ` (saved ${when.toLocaleString()})`;
        return b.mode === 'live'
            ? `Live at step ${r.shown.head.step} · pinned at step ${b.pin.step}${saved}`
            : `Frozen at step ${b.pin.step}${saved} — it never moves`;
    });

    constructor() {
        effect((onCleanup) => {
            const id = this.viewId();
            const b = this.spec();
            if (!id || !b) {
                this.state.set({ kind: 'unbound' });
                return;
            }
            this.state.set({ kind: 'loading' });
            const fail = (err: unknown) => {
                const f = readFailure(err);
                this.state.set(
                    f === 'unavailable'
                        ? { kind: 'unavailable' }
                        : f === 'pin-gone'
                          ? {
                                kind: 'error',
                                message: `The pinned step (${b.pin.step}) is no longer in the Investigation’s log.`,
                            }
                          : { kind: 'error', message: 'The Working Set could not be read.' },
                );
            };
            const sub =
                b.mode === 'frozen'
                    ? this.inv.workingSetRelation(id, { of: b.relation, at: b.pin.step, limit: TILE_ROWS }).subscribe({
                          next: (shown) =>
                              this.state.set(
                                  shown.head.workingSetHash === b.pin.workingSetHash
                                      ? { kind: 'ready', shown, drift: null, baselineBroken: false }
                                      : { kind: 'broken-pin' },
                              ),
                          error: fail,
                      })
                    : forkJoin({
                          pinned: this.inv.workingSetRelation(id, { of: b.relation, at: b.pin.step, limit: DIFF_ROWS }),
                          now: this.inv.workingSetRelation(id, { of: b.relation, limit: DIFF_ROWS }),
                      }).subscribe({
                          next: ({ pinned, now }) => {
                              const baselineBroken = pinned.head.workingSetHash !== b.pin.workingSetHash;
                              this.state.set({
                                  kind: 'ready',
                                  shown: now,
                                  drift: baselineBroken ? null : workingSetDrift(pinned, now),
                                  baselineBroken,
                              });
                          },
                          error: fail,
                      });
            onCleanup(() => sub.unsubscribe());
        });
    }

    driftText(d: WorkingSetDrift): string {
        const b = this.spec();
        return b ? driftLine(b.relation, d) : '';
    }

    cell(v: unknown): string {
        return v === null || v === undefined ? '—' : String(v);
    }
}
